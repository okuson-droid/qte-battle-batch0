/**
 * 対戦画面のクライアント処理。
 *
 * 構造は3層:
 *   1) 接続: STOMPで /ws に接続し、自分専用の宛先を購読する
 *   2) 送信: ボタンやカードのクリック → /app/room/... へメッセージ送信
 *   3) 受信: サーバから届いたビュー(自分視点にフィルタ済み)で画面を全描画し直す
 *
 * クライアントが自分で持つ状態は2つだけ:
 *   - selectedAttackerId: 攻撃元として選択中のミニオン
 *   - pending: 対象選択の進行状態(どのカードを、どの要求まで選んだか)
 * 「今の盤面」は常にサーバから届いた最新ビューが正(再描画方式)。
 * 対象の正当性(潜伏・知識フィルタ等)の最終判定はサーバが行い、
 * クライアントの絞り込みはあくまで操作補助である。
 */

let latestView = null;
let selectedAttackerId = null;

/**
 * 対象選択の進行状態。
 * { action: 'play-card'|'special-summon', handIndex, specs: [要求...],
 *   collected: [確定済み選択...], current: {handIndexes:[], minionIds:[]} }
 */
let pending = null;

/** 自動進行モード(トグル)。とれるアクションがないフェイズを自動で進める */
let autoMode = false;
let lastAutoKey = null;

/** マリガンで引き直しに選んだ手札インデックス */
let mulliganPicks = [];

// ---------------------------------------------------------------
// 1) 接続
// ---------------------------------------------------------------

// ページがHTTPSで配信されている場合(ngrok・クラウド経由)はWebSocketも暗号化版のwssを使う。
// ws://決め打ちだとHTTPSページからの接続がブラウザに拒否される(混在コンテンツ)
const wsProtocol = location.protocol === 'https:' ? 'wss' : 'ws';
const client = new StompJs.Client({
    brokerURL: `${wsProtocol}://${location.host}/ws`,
    reconnectDelay: 3000,
});

client.onConnect = () => {
    setConnectionStatus('接続済み');
    client.subscribe(`/topic/room/${ROOM_ID}/player/${PLAYER_ID}`, onMessage);
    send('ready', {});
};

client.onWebSocketClose = () => setConnectionStatus('切断(再接続中...)');
client.activate();

function setConnectionStatus(text) {
    document.getElementById('connection-status').textContent = text;
}

// ---------------------------------------------------------------
// 2) 送信
// ---------------------------------------------------------------

function send(action, payload) {
    client.publish({
        destination: `/app/room/${ROOM_ID}/${action}`,
        body: JSON.stringify({ playerId: PLAYER_ID, ...payload }),
    });
}

function chooseOrder(goFirst) { send('choose-order', { goFirst }); }
function nextPhase() { send('next-phase', {}); }
function endTurn() { send('end-turn', {}); }

// ---------------------------------------------------------------
// 手札のプレイと対象選択
// ---------------------------------------------------------------

function onHandCardClick(index) {
    if (!latestView) return;
    // マリガン中の手札クリックは引き直し対象のトグル
    if (latestView.mulligan) {
        const pos = mulliganPicks.indexOf(index);
        if (pos >= 0) mulliganPicks.splice(pos, 1); else mulliganPicks.push(index);
        render(latestView);
        return;
    }
    if (!latestView.myTurn) return;
    // 対象選択中の手札クリックは「対象として選ぶ」操作になる
    if (pending) {
        pickHandCard(index);
        return;
    }
    if (latestView.phase === 'MANA_CHARGE') {
        send('charge-mana', { handIndex: index });
        return;
    }
    if (latestView.phase !== 'MAIN' && latestView.phase !== 'SUB') {
        showMessage('カードを使えるのはマナチャージ/メイン/サブフェイズです');
        return;
    }
    const card = latestView.you.hand[index];

    // 特殊召喚が可能なら通常召喚とどちらにするか確認する
    let action = 'play-card';
    let specs = card.targets;
    if (card.canSpecialSummon && latestView.phase === 'MAIN') {
        if (confirm(card.specialSummonText + '\n\nOK = 特殊召喚 / キャンセル = 通常プレイ')) {
            action = 'special-summon';
            specs = card.specialTargets;
        }
    }
    beginSelection(action, index, specs);
}

/** 対象選択を開始する。要求がなければ即送信 */
function beginSelection(action, handIndex, specs) {
    if (!specs || specs.length === 0) {
        send(action, buildActionPayload(handIndex, []));
        return;
    }
    pending = {
        action, handIndex, specs,
        collected: [],
        current: { handIndexes: [], minionIds: [], manaIndexes: [] },
    };
    render(latestView);
}

function buildActionPayload(handIndex, targets) {
    // リーダー能力はhandIndexを持たない
    return handIndex === null ? { targets } : { handIndex, targets };
}

function currentRequirement() {
    return pending ? pending.specs[pending.collected.length] : null;
}

function pickHandCard(index) {
    const req = currentRequirement();
    if (!req || req.kind !== 'HAND') return;
    if (pending.handIndex !== null && index === pending.handIndex) {
        showMessage('プレイするカード自身は選べません');
        return;
    }
    if (isPicked('HAND', index)) return;
    const card = latestView.you.hand[index];
    if (req.filter === 'KNOWLEDGE' && !card.keywords.includes('知識')) {
        showMessage('【知識】を持つカードを選んでください');
        return;
    }
    pending.current.handIndexes.push(index);
    advanceIfComplete();
}

function pickMinion(instanceId, isOwn) {
    const req = currentRequirement();
    if (!req || req.kind !== 'MINION') return;
    if ((req.side === 'SELF' && !isOwn) || (req.side === 'OPPONENT' && isOwn)) return;
    if (isPicked('MINION', instanceId)) return;
    const list = isOwn ? latestView.you.minions : latestView.opponent.minions;
    const minion = list.find(m => m.instanceId === instanceId);
    if (req.filter === 'KNOWLEDGE' && !minion.keywords.includes('知識')) {
        showMessage('【知識】を持つミニオンを選んでください');
        return;
    }
    if (!isOwn && minion.keywords.includes('潜伏')) {
        showMessage('【潜伏】持ちは相手の効果の対象になりません');
        return;
    }
    pending.current.minionIds.push(instanceId);
    advanceIfComplete();
}

function isPicked(kind, value) {
    const inCurrent = kind === 'HAND'
        ? pending.current.handIndexes.includes(value)
        : pending.current.minionIds.includes(value);
    const inCollected = pending.collected.some(sel => kind === 'HAND'
        ? sel.handIndexes.includes(value)
        : sel.minionIds.includes(value));
    return inCurrent || inCollected;
}

function advanceIfComplete() {
    const req = currentRequirement();
    const picked = pending.current.handIndexes.length + pending.current.minionIds.length
        + pending.current.manaIndexes.length;
    if (picked >= req.count) {
        commitRequirement();
    } else {
        render(latestView);
    }
}

function commitRequirement() {
    pending.collected.push(pending.current);
    pending.current = { handIndexes: [], minionIds: [], manaIndexes: [] };
    if (pending.collected.length === pending.specs.length) {
        const { action, handIndex, collected } = pending;
        pending = null;
        send(action, buildActionPayload(handIndex, collected));
    } else {
        render(latestView);
    }
}

/** 「〜してもよい」の要求を選ばずに確定する */
function skipRequirement() {
    const req = currentRequirement();
    if (!req || !req.optional) return;
    pending.current = { handIndexes: [], minionIds: [], manaIndexes: [] };
    commitRequirement();
}

function cancelSelection() {
    pending = null;
    render(latestView);
}

function pickMana(index) {
    const req = currentRequirement();
    if (!req || req.kind !== 'MANA') return;
    if (pending.current.manaIndexes.includes(index)) return;
    pending.current.manaIndexes.push(index);
    advanceIfComplete();
}

function useLeaderAbility() {
    const ability = latestView && latestView.you && latestView.you.leaderAbility;
    if (!ability || !ability.usable || pending) return;
    beginSelection('leader-ability', null, ability.targets);
}

function submitMulligan() {
    send('mulligan', { handIndexes: mulliganPicks });
    mulliganPicks = [];
}

function keepHand() {
    send('mulligan', { handIndexes: [] });
    mulliganPicks = [];
}

// ---------------------------------------------------------------
// 自動進行
// ---------------------------------------------------------------

function toggleAutoMode() {
    autoMode = !autoMode;
    const btn = document.getElementById('btn-auto-mode');
    btn.textContent = autoMode ? '進行: 自動' : '進行: 手動';
    btn.classList.toggle('btn-warning', autoMode);
    btn.classList.toggle('btn-outline-light', !autoMode);
    if (latestView) maybeAutoAdvance(latestView);
}

/**
 * 自動進行: 現在のフェイズでとれるアクションが何もなければ次のフェイズへ進める。
 * とれるアクションが残っている間は待つ(使うか進めるかはプレイヤーの判断)。
 * 同じ(ターン, フェイズ)で二重送信しないようキーで抑止する。
 */
function maybeAutoAdvance(view) {
    if (!autoMode || !view.myTurn || view.status !== 'PLAYING' || pending || view.mulligan) return;
    if (hasAvailableActions(view)) return;
    const key = view.turnNumber + ':' + view.phase;
    if (lastAutoKey === key) return;
    lastAutoKey = key;
    setTimeout(() => {
        if (autoMode && latestView && latestView.myTurn
            && latestView.turnNumber + ':' + latestView.phase === key) {
            nextPhase();
        }
    }, 700);
}

/** 現在のフェイズでプレイヤーがとれるアクションが残っているか(クライアント側の近似判定) */
function hasAvailableActions(view) {
    const you = view.you;
    if (!you) return true;
    const costOf = c => (c.effectiveCost != null ? c.effectiveCost : c.cost);
    switch (view.phase) {
        case 'MANA_CHARGE':
            return !you.manaCharged && you.hand.length > 0 && you.totalMana < 15;
        case 'MAIN': {
            const abilityUsable = you.leaderAbility && you.leaderAbility.usable;
            if (you.cannotUseCards) return abilityUsable; // 起動能力はカードの使用ではない
            const playable = you.hand.some(c => c.type !== 'LEADER'
                && (costOf(c) <= you.availableMp || c.canSpecialSummon));
            return playable || abilityUsable;
        }
        case 'BATTLE':
            // 近似: 攻撃可能なミニオン/リーダーがいれば待つ(対象が全て威圧などの稀な盤面は手動で進める)
            return you.leaderCanAttack
                || you.minions.some(m => m.canAttackMinion || m.canAttackLeader);
        case 'SUB':
            if (you.cannotUseCards) return false;
            return you.hand.some(c => c.type === 'SPELL' && costOf(c) <= you.availableMp);
        default:
            return true;
    }
}

// ---------------------------------------------------------------
// 情報モーダル(墓地・マナ・リーダー能力)
// ---------------------------------------------------------------

function showModal(title, lines) {
    document.getElementById('info-modal-title').textContent = title;
    const content = document.getElementById('info-modal-content');
    content.innerHTML = '';
    if (lines.length === 0) {
        content.textContent = '(なし)';
    } else {
        lines.forEach(line => {
            const div = document.createElement('div');
            div.textContent = line;
            content.appendChild(div);
        });
    }
    document.getElementById('info-modal').classList.remove('d-none');
}

function hideModal() {
    document.getElementById('info-modal').classList.add('d-none');
}

function showTrashList(isSelf) {
    if (!latestView || !latestView.you) return;
    const p = isSelf ? latestView.you : latestView.opponent;
    showModal(`${p.displayName}の墓地(${p.trashCount}枚)`, p.trashCardNames || []);
}

function showManaList(isSelf) {
    if (!latestView || !latestView.you) return;
    const p = isSelf ? latestView.you : latestView.opponent;
    const lines = p.manaZone.map((m, i) => {
        const state = (m.tapped ? 'タップ' : 'アンタップ') + '/' + (m.faceUp ? '表' : '裏');
        // 相手の裏向きマナだけは中身が見えない(サーバがnameを送っていない)
        const name = m.name || '(裏向きのカード)';
        return `${i + 1}. ${name} [${state}]${m.temporary ? '(一時マナ)' : ''}`;
    });
    showModal(`${p.displayName}のマナ(${p.totalMana}枚)`, lines);
}

function showLeaderInfo(isSelf) {
    if (!latestView || !latestView.you) return;
    const p = isSelf ? latestView.you : latestView.opponent;
    const lines = [p.leaderText || '(効果テキストなし)'];
    if (p.leaderAbility && p.leaderAbility.mpCost > 0) {
        lines.push(`使用コスト: MP${p.leaderAbility.mpCost}`);
    }
    showModal(`リーダー: ${p.leaderName}`, lines);
}

// ---------------------------------------------------------------
// 攻撃
// ---------------------------------------------------------------

function onMyMinionClick(instanceId) {
    if (pending) {
        pickMinion(instanceId, true);
        return;
    }
    if (!latestView || !latestView.myTurn || latestView.phase !== 'BATTLE') return;
    selectedAttackerId = (selectedAttackerId === instanceId) ? null : instanceId;
    render(latestView);
}

function onMyLeaderClick() {
    if (pending) return;
    if (!latestView || !latestView.myTurn || latestView.phase !== 'BATTLE') return;
    if (!latestView.you.leaderCanAttack) return;
    // 'LEADER'はリーダー自身を攻撃元として選択中であることを示す特別値
    selectedAttackerId = (selectedAttackerId === 'LEADER') ? null : 'LEADER';
    render(latestView);
}

function onOpponentMinionClick(instanceId) {
    if (pending) {
        pickMinion(instanceId, false);
        return;
    }
    if (!selectedAttackerId) return;
    const action = selectedAttackerId === 'LEADER' ? 'leader-attack' : 'attack';
    const payload = selectedAttackerId === 'LEADER'
        ? { targetInstanceId: instanceId }
        : { attackerInstanceId: selectedAttackerId, targetInstanceId: instanceId };
    send(action, payload);
    selectedAttackerId = null;
}

function onOpponentLeaderClick() {
    if (pending || !selectedAttackerId) return;
    const action = selectedAttackerId === 'LEADER' ? 'leader-attack' : 'attack';
    const payload = selectedAttackerId === 'LEADER'
        ? { targetInstanceId: null }
        : { attackerInstanceId: selectedAttackerId, targetInstanceId: null };
    send(action, payload);
    selectedAttackerId = null;
}

// ---------------------------------------------------------------
// 3) 受信と描画
// ---------------------------------------------------------------

function onMessage(frame) {
    const message = JSON.parse(frame.body);
    if (message.type === 'ERROR') {
        showMessage(message.message);
        pending = null; // サーバに拒否された選択は最初からやり直す
        render(latestView);
        return;
    }
    latestView = message.view;
    // 盤面が変わったら選択状態は仕切り直す(対象が既にいない可能性があるため)
    if (!latestView.myTurn || latestView.phase !== 'BATTLE') {
        selectedAttackerId = null;
    }
    pending = null;
    render(latestView);
}

function render(view) {
    if (!view) return;
    renderHeader(view);
    renderControls(view);
    renderSelection();
    renderMulligan(view);
    renderLog(view.log);
    if (!view.you) {
        showMessage('相手の入室を待っています。部屋コードを伝えてください: ' + view.roomId);
        return;
    }
    renderOpponent(view.opponent, view);
    renderSelf(view.you, view);

    if (view.status === 'FINISHED') {
        showMessage('対戦終了: ' + view.winnerName + ' の勝利');
    }
    maybeAutoAdvance(view);
}

function renderMulligan(view) {
    document.getElementById('mulligan-area').classList.toggle('d-none', !view.mulligan);
    if (view.mulligan) {
        document.getElementById('mulligan-count').textContent = mulliganPicks.length;
    } else {
        mulliganPicks = [];
    }
}

function renderHeader(view) {
    const indicator = document.getElementById('phase-indicator');
    if (view.status === 'PLAYING') {
        indicator.textContent = `ターン${view.turnNumber} / ${view.phaseDisplay}フェイズ` +
            (view.myTurn ? '(あなたの番)' : '(相手の番)');
        indicator.className = view.myTurn ? 'fs-5 text-warning' : 'fs-5 text-muted';
    } else {
        indicator.textContent = '';
    }
}

function renderControls(view) {
    document.getElementById('choose-order-area').classList.toggle('d-none', !view.chooseOrder);
    const controls = document.getElementById('turn-controls');
    controls.classList.toggle('d-none', !(view.status === 'PLAYING' && view.myTurn));
}

function renderSelection() {
    const area = document.getElementById('selection-area');
    const req = currentRequirement();
    area.classList.toggle('d-none', !req);
    if (!req) return;
    const picked = pending.current.handIndexes.length + pending.current.minionIds.length
        + pending.current.manaIndexes.length;
    document.getElementById('selection-prompt').textContent =
        `${req.prompt} (${picked}/${req.count})`;
    document.getElementById('btn-skip-target').classList.toggle('d-none', !req.optional);
}

function renderLog(log) {
    const box = document.getElementById('log-area');
    box.innerHTML = '';
    (log || []).forEach(line => {
        const div = document.createElement('div');
        div.textContent = line;
        box.appendChild(div);
    });
    box.scrollTop = box.scrollHeight;
}

function renderOpponent(opp, view) {
    document.getElementById('opp-leader-name').textContent = opp.leaderName;
    document.getElementById('opp-lp').textContent = opp.lp;
    document.getElementById('opp-hand-count').textContent = opp.handCount;
    document.getElementById('opp-deck-count').textContent = opp.deckCount;
    document.getElementById('opp-mp').textContent = opp.availableMp;
    document.getElementById('opp-mana-count').textContent = opp.totalMana;
    document.getElementById('opp-weapon').textContent =
        opp.weaponName ? `${opp.weaponName} ⚔${opp.weaponAttack}` : 'なし';
    document.getElementById('opp-trash-count').textContent = opp.trashCount;

    // 相手のマナ行(裏向きは中身が送られてこないためツールチップも伏せる)
    const oppManaRow = document.getElementById('opp-mana-row');
    oppManaRow.innerHTML = '';
    opp.manaZone.forEach(mana => {
        const chip = document.createElement('div');
        chip.className = 'mana-chip' + (mana.tapped ? ' tapped' : '') + (mana.faceUp ? '' : ' face-down');
        chip.title = mana.name || '(裏向き)';
        oppManaRow.appendChild(chip);
    });

    const leaderEl = document.getElementById('opp-leader');
    const leaderAttackable = !pending && selectedAttackerId !== null && canSelectedAttackLeader(view);
    leaderEl.classList.toggle('attackable', leaderAttackable);
    leaderEl.onclick = leaderAttackable ? onOpponentLeaderClick : null;

    const req = currentRequirement();
    const row = document.getElementById('opp-minions');
    row.innerHTML = '';
    opp.minions.forEach(minion => {
        const el = createMinionEl(minion);
        if (req && req.kind === 'MINION' && req.side !== 'SELF') {
            el.classList.add('attack-target');
            el.onclick = () => onOpponentMinionClick(minion.instanceId);
        } else if (!pending && selectedAttackerId !== null) {
            el.classList.add('attack-target');
            el.onclick = () => onOpponentMinionClick(minion.instanceId);
        }
        row.appendChild(el);
    });
}

function canSelectedAttackLeader(view) {
    if (selectedAttackerId === 'LEADER') return true; // 対象の妥当性(守護等)はサーバが判定
    const attacker = view.you.minions.find(m => m.instanceId === selectedAttackerId);
    return attacker ? attacker.canAttackLeader : false;
}

function renderSelf(you, view) {
    document.getElementById('my-leader-name').textContent = you.leaderName;
    document.getElementById('my-lp').textContent = you.lp;
    document.getElementById('my-deck-count').textContent = you.deckCount;
    document.getElementById('my-mp').textContent = you.availableMp;
    document.getElementById('my-mana-count').textContent = you.totalMana;
    document.getElementById('my-weapon').textContent =
        you.weaponName ? `${you.weaponName} ⚔${you.weaponAttack}` : 'なし';
    document.getElementById('my-trash-count').textContent = you.trashCount;

    // リーダー能力ボタン
    const abilityBtn = document.getElementById('btn-leader-ability');
    const ability = you.leaderAbility;
    abilityBtn.classList.toggle('d-none', !(ability && ability.usable && !pending));
    if (ability) abilityBtn.title = ability.description + (ability.mpCost > 0 ? `(MP${ability.mpCost})` : '');

    // 自リーダー: バトルフェイズにウェポンで攻撃できるならクリック可能
    const myLeaderEl = document.getElementById('my-leader');
    const leaderReady = !pending && view.myTurn && view.phase === 'BATTLE' && you.leaderCanAttack;
    myLeaderEl.classList.toggle('attackable', leaderReady);
    myLeaderEl.classList.toggle('selected-attacker', selectedAttackerId === 'LEADER');
    myLeaderEl.onclick = leaderReady ? onMyLeaderClick : null;

    const manaReq = currentRequirement();
    const manaRow = document.getElementById('my-mana-row');
    manaRow.innerHTML = '';
    you.manaZone.forEach((mana, index) => {
        const chip = document.createElement('div');
        chip.className = 'mana-chip' + (mana.tapped ? ' tapped' : '') + (mana.faceUp ? '' : ' face-down');
        chip.title = mana.name || '(裏向き)';
        if (manaReq && manaReq.kind === 'MANA') {
            chip.classList.add('mana-selectable');
            if (pending.current.manaIndexes.includes(index)) chip.classList.add('mana-picked');
            chip.onclick = () => pickMana(index);
        }
        manaRow.appendChild(chip);
    });

    const req = currentRequirement();
    const row = document.getElementById('my-minions');
    row.innerHTML = '';
    you.minions.forEach(minion => {
        const el = createMinionEl(minion);
        if (req && req.kind === 'MINION' && req.side !== 'OPPONENT') {
            el.classList.add('attack-target');
            el.onclick = () => onMyMinionClick(minion.instanceId);
        } else if (!pending) {
            const battleReady = view.myTurn && view.phase === 'BATTLE'
                    && (minion.canAttackMinion || minion.canAttackLeader);
            if (battleReady) {
                el.classList.add('can-attack');
                el.onclick = () => onMyMinionClick(minion.instanceId);
            }
            if (!minion.canAttackMinion && !minion.canAttackLeader) {
                el.classList.add('exhausted');
            }
            if (minion.instanceId === selectedAttackerId) {
                el.classList.add('selected-attacker');
            }
        }
        if (pending && pending.current.minionIds.includes(minion.instanceId)) {
            el.classList.add('selected-attacker');
        }
        row.appendChild(el);
    });

    const hand = document.getElementById('my-hand');
    hand.innerHTML = '';
    (you.hand || []).forEach((card, index) => {
        const el = createHandCardEl(card, index, view);
        el.onclick = () => onHandCardClick(index);
        hand.appendChild(el);
    });
}

function createMinionEl(minion) {
    const el = document.createElement('div');
    el.className = 'game-card';
    const frozenMark = minion.frozen ? '<div class="kw">❄凍結</div>' : '';
    el.innerHTML = `
        <div class="card-name">${escapeHtml(minion.name)}</div>
        <div class="kw">${minion.keywords.map(escapeHtml).join(' ')}</div>
        ${frozenMark}
        <div class="card-stats"><span>⚔${minion.attack}</span><span>♥${minion.currentHp}/${minion.maxHp}</span></div>`;
    return el;
}

function createHandCardEl(card, index, view) {
    const el = document.createElement('div');
    el.className = 'game-card';
    if (latestView && latestView.mulligan) {
        el.classList.add('playable');
        if (mulliganPicks.includes(index)) el.classList.add('mulligan-selected');
    }
    const req = currentRequirement();
    if (req && req.kind === 'HAND') {
        // 対象選択中: 選択可能な手札を光らせる
        const selectable = index !== pending.handIndex
            && !isPicked('HAND', index)
            && (req.filter !== 'KNOWLEDGE' || card.keywords.includes('知識'));
        if (selectable) el.classList.add('attack-target');
        if (pending.current.handIndexes.includes(index)
            || pending.collected.some(s => s.handIndexes.includes(index))) {
            el.classList.add('selected-attacker');
        }
        if (index === pending.handIndex) el.classList.add('exhausted');
    } else if (!pending) {
        const cost = card.effectiveCost != null ? card.effectiveCost : card.cost;
        const affordable = cost <= view.you.availableMp;
        // ミニオン・スペル・ウェポンはメインフェイズにプレイ可能(ウェポンの光り漏れバグを修正)
        const playable = view.myTurn && (
            view.phase === 'MANA_CHARGE' ||
            (view.phase === 'MAIN' && card.type !== 'LEADER'
                && (affordable || card.canSpecialSummon)) ||
            (view.phase === 'SUB' && card.type === 'SPELL' && affordable));
        if (playable) el.classList.add('playable');
    }
    // 実効コストが印刷コストと違うときは両方見せる(例: 双流の幻術師)
    const costText = (card.effectiveCost != null && card.effectiveCost !== card.cost)
        ? `<s>${card.cost}</s>→${card.effectiveCost}` : `${card.cost}`;
    const stats = card.type === 'MINION' ? `⚔${card.attack} ♥${card.hp}` : card.type;
    const ssMark = card.canSpecialSummon ? '<div class="kw">★特殊召喚可</div>' : '';
    el.innerHTML = `
        <div class="card-name"><span class="card-cost">(${costText})</span> ${escapeHtml(card.name)}</div>
        <div class="kw">${card.keywords.map(escapeHtml).join(' ')}</div>
        ${ssMark}
        <div class="small">${escapeHtml(card.text || '')}</div>
        <div class="card-stats"><span>${stats}</span></div>`;
    return el;
}

function showMessage(text) {
    const area = document.getElementById('message-area');
    area.textContent = text;
    area.classList.remove('d-none');
    clearTimeout(showMessage.timer);
    showMessage.timer = setTimeout(() => area.classList.add('d-none'), 4000);
}

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    })[c]);
}
