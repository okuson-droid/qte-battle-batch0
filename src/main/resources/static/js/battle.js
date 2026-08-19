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
 *
 * ★★Batch 42: カードの描画を手動モードと同じフェイス(.mcard 系)へ載せ替えた。
 *   - 見た目の正は battle.css の .mcard-* / --civ-* ただ1つである(裁定60・107・141)。
 *     DOM の構造とクラス名は手動モードの cardFace と同じにし、コードは複製する(裁定111)。
 *   - 手札・禁忌は CardView が面に必要な情報を全部持っている。ミニオンとリーダーの
 *     文明色・効果テキストだけは /manual/api/card-library から起動時1回取得し、
 *     カードID(cardId / leaderCardId)で引く。
 *     ★46b: サーバのカードIDが台帳ID から QTE-M-* へ移ったので、索引も id に変えた。
 *     ★取得前・取得失敗時でも壊れない(25 と同じ性質): 色は無文明色になり、
 *     拡大のテキストが空になるだけで、対戦は続けられる。
 *   - ★状態のクラス名(playable / can-attack / attack-target / selected-attacker /
 *     exhausted など)は 42 以前から<b>変えていない</b>。変えたのは要素の中身だけである。
 *   - 右クリック = 拡大(手動モードの 22 1-7 と同じ規約)。クリックは従来どおり操作。
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

// 割り込み選択(a9)で選んだ候補のindex群。選択の解決を送るまで保持する
let choicePicks = [];

/**
 * 禁忌コストの支払い進行状態。
 * { tabooIndex, cost, manaIndexes: [], specs: [対象要求...] }
 * 禁忌は「コスト支払い(マナ選択) → 対象選択 → 送信」の2段階になるため、
 * 前段をこの変数、後段を既存のpendingが担当する。
 */
let tabooPay = null;

// ---------------------------------------------------------------
// 1) 接続
// ---------------------------------------------------------------

// ページがHTTPSで配信されている場合(ngrok・クラウド経由)はWebSocketも暗号化版のwssを使う。
// ws://決め打ちだとHTTPSページからの接続がブラウザに拒否される(混在コンテンツ)
const wsProtocol = location.protocol === 'https:' ? 'wss' : 'ws';
// ★Batch 28: ハートビートを明示する(サーバ側 WebSocketConfig と対で初めて有効になる)。
//   通常モードも同じ /ws を使うため、設定を片方のモードだけにしない。
const client = new StompJs.Client({
    brokerURL: `${wsProtocol}://${location.host}/ws`,
    reconnectDelay: 3000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
});

client.onConnect = () => {
    setConnectionStatus('接続済み');
    client.subscribe(`/topic/room/${ROOM_ID}/player/${PLAYER_ID}`, onMessage);
    send('ready', {});
};

client.onWebSocketClose = () => setConnectionStatus('切断(再接続中...)');
client.activate();
// ★Batch 42: カード定義(文明色・効果テキスト)。失敗しても対戦は続けられる
loadCardLibrary();

// ★拡大の出口は3つ: クリック / 右クリック / Esc。どこを押しても閉じる。
//   右クリックも preventDefault する(閉じる操作でブラウザのメニューが出ない)
document.addEventListener('DOMContentLoaded', () => {
    const overlay = document.getElementById('auto-zoom');
    overlay.addEventListener('click', closeZoom);
    overlay.addEventListener('contextmenu', (e) => { e.preventDefault(); closeZoom(); });
    // ★45: ログパネルは外側クリックで閉じる(パネル本体のクリックは閉じない)
    const logPanel = document.getElementById('log-panel');
    logPanel.addEventListener('click', (e) => {
        if (e.target === logPanel) toggleLogPanel();
    });
});
document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    const overlay = document.getElementById('auto-zoom');
    if (overlay && !overlay.classList.contains('d-none')) {
        e.preventDefault();
        closeZoom();
        return;
    }
    const logPanel = document.getElementById('log-panel');
    if (logPanel && !logPanel.classList.contains('d-none')) {
        e.preventDefault();
        toggleLogPanel();
    }
});

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

/** 割り込み選択(a9)の待ちがあるか。サーバ側も同じ状態の間は他の操作を拒否する */
function hasPendingChoice() {
    return !!(latestView && latestView.you && latestView.you.pendingChoice);
}

// ---------------------------------------------------------------
// 手札のプレイと対象選択
// ---------------------------------------------------------------

function onHandCardClick(index) {
    if (!latestView) return;
    if (hasPendingChoice()) return; // 割り込み選択を先に済ませる必要がある
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
    let enhanced = false;
    if (card.canSpecialSummon && latestView.phase === 'MAIN') {
        if (confirm(card.specialSummonText + '\n\nOK = 特殊召喚 / キャンセル = 通常プレイ')) {
            action = 'special-summon';
            specs = card.specialTargets;
        }
    } else if (card.enhancedCost > 0) {
        // 追加コストによる強化使用(a5: 回帰の風穴・風弾の跳弾)。
        // コストに影響するモード選択のため、対象選択より前に確定させる
        enhanced = confirm(card.enhancedText + `\n\nOK = 追加コスト+${card.enhancedCost}を払う / キャンセル = 通常使用`);
    }
    beginSelection(action, index, specs, enhanced ? { enhanced: true } : { enhanced: false });
}

/** 対象選択を開始する。要求がなければ即送信 */
function beginSelection(action, handIndex, specs, extra) {
    if (!specs || specs.length === 0) {
        send(action, buildActionPayload(handIndex, [], extra));
        return;
    }
    pending = {
        action, handIndex, specs, extra,
        collected: [],
        current: { handIndexes: [], minionIds: [], manaIndexes: [], trashIndexes: [], weaponSides: [] },
    };
    render(latestView);
    maybeOpenTrashPicker();
}

/** 墓地を対象に取る要求に進んだら、選択用のモーダルを自動で開く */
function maybeOpenTrashPicker() {
    const req = currentRequirement();
    if (req && req.kind === 'TRASH') {
        openTrashPicker();
    }
}

function buildActionPayload(handIndex, targets, extra) {
    // リーダー能力・禁忌カードはhandIndexを持たない
    const base = handIndex === null ? { targets } : { handIndex, targets };
    return Object.assign(base, extra || {});
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
    if (!matchesFilters(req, card, null)) return;
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
    if (!matchesFilters(req, null, minion)) return;
    const ignoresStealth = (req.filters || []).includes('IGNORES_STEALTH');
    if (!isOwn && minion.keywords.includes('潜伏') && !ignoresStealth) {
        showMessage('【潜伏】持ちは相手の効果の対象になりません');
        return;
    }
    pending.current.minionIds.push(instanceId);
    advanceIfComplete();
}

/**
 * 絞り込み条件(AND)の判定。クライアント側は操作補助であり、
 * 最終的な正当性はサーバのvalidateTargetsが判定する。
 * cardは手札のカードビュー、minionは場のミニオンビュー(該当しない方はnull)。
 */
function matchesFilters(req, card, minion) {
    for (const filter of (req.filters || [])) {
        let ok = true;
        switch (filter) {
            case 'KNOWLEDGE':
                ok = (minion || card).keywords.includes('知識'); break;
            case 'GUARD':
                ok = (minion || card).keywords.includes('守護'); break;
            case 'MINION_CARD':
                ok = card && card.type === 'MINION'; break;
            case 'HP_5_OR_LESS':
                ok = minion ? minion.currentHp <= 5 : (card.hp != null && card.hp <= 5); break;
            case 'COST_4_OR_LESS':
                ok = (minion || card).cost != null && (minion || card).cost <= 4; break;
            case 'COST_3_OR_LESS':
                ok = (minion || card).cost != null && (minion || card).cost <= 3; break;
            case 'SPELL_CARD':
                ok = card && card.type === 'SPELL'; break;
            case 'LIGHT_CIVILIZATION':
                ok = card && card.civilization === 'LIGHT'; break;
            case 'COST_7_OR_LESS':
                ok = (minion || card).cost != null && (minion || card).cost <= 7; break;
            case 'HIGHEST_ATTACK_OPPONENT': {
                const maxAtk = Math.max(0, ...latestView.opponent.minions.map(m => m.attack));
                ok = !!minion && minion.attack === maxAtk;
                break;
            }
            case 'IGNORES_STEALTH':
                ok = true; break; // 絞り込みではなく潜伏チェックの上書き指示。pickMinion側で見る
        }
        if (!ok) {
            showMessage('このカードは選べません(条件: ' + (req.filters || []).join(', ') + ')');
            return false;
        }
    }
    return true;
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
        + pending.current.manaIndexes.length + pending.current.trashIndexes.length
        + pending.current.weaponSides.length;
    if (picked >= req.count) {
        commitRequirement();
    } else {
        render(latestView);
    }
}

function commitRequirement() {
    pending.collected.push(pending.current);
    pending.current = { handIndexes: [], minionIds: [], manaIndexes: [], trashIndexes: [], weaponSides: [] };
    if (pending.collected.length === pending.specs.length) {
        const { action, handIndex, collected, extra } = pending;
        pending = null;
        hideModal();
        send(action, buildActionPayload(handIndex, collected, extra));
    } else {
        hideModal(); // 前の要求で開いた墓地モーダルを閉じてから次へ進む
        render(latestView);
        maybeOpenTrashPicker();
    }
}

/** 「〜してもよい」の要求を選ばずに確定する */
function skipRequirement() {
    const req = currentRequirement();
    if (!req || !req.optional) return;
    pending.current = { handIndexes: [], minionIds: [], manaIndexes: [], trashIndexes: [], weaponSides: [] };
    commitRequirement();
}

/** 「好きな数だけ」の要求を、今選んでいる分で確定する */
function confirmRequirement() {
    const req = currentRequirement();
    if (!req || !req.upTo) return;
    commitRequirement();
}

function cancelSelection() {
    pending = null;
    tabooPay = null;
    hideModal();
    render(latestView);
}

/**
 * 墓地からの選択。墓地は枚数が多くなるためモーダルに一覧を出して選ばせる。
 * 選んでも墓地からは消えない(移動はサーバ側の効果が行う)ため、表示は選択済みの印で区別する。
 */
function pickTrashCard(index) {
    const req = currentRequirement();
    if (!req || req.kind !== 'TRASH') return;
    if (pending.current.trashIndexes.includes(index)) return;
    const card = latestView.you.trash[index];
    if (!matchesFilters(req, card, null)) return;
    pending.current.trashIndexes.push(index);
    if (pending.current.trashIndexes.length >= req.count) {
        commitRequirement();
        return;
    }
    openTrashPicker();
    render(latestView);
}

/** 墓地の一覧をモーダルに出す。用途は対象選択(mode省略)と墓地からの召喚(mode='summon') */
function openTrashPicker(mode) {
    const trash = (latestView && latestView.you && latestView.you.trash) || [];
    const title = mode === 'summon' ? '墓地から召喚するミニオンを選択' : '墓地からカードを選択';
    const rows = [];
    trash.forEach((card, index) => {
        if (mode === 'summon' && card.type !== 'MINION') return;
        const picked = pending && pending.current.trashIndexes.includes(index);
        const cost = card.effectiveCost != null ? card.effectiveCost : card.cost;
        const label = `${card.name} (${card.type === 'SPELL' ? 'スペル' : card.type === 'WEAPON' ? 'ウェポン' : 'ミニオン'}${cost != null ? ' コスト' + cost : ''})`;
        rows.push({ index, label, picked });
    });
    showModalRows(title, rows, mode);
}

/** クリックできる行を持つモーダル。情報表示用のshowModalとは別に用意する */
function showModalRows(title, rows, mode) {
    document.getElementById('info-modal-title').textContent = title;
    const content = document.getElementById('info-modal-content');
    content.innerHTML = '';
    if (rows.length === 0) {
        content.textContent = '(選べるカードがありません)';
    } else {
        rows.forEach(row => {
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'btn btn-sm w-100 text-start mb-1 '
                + (row.picked ? 'btn-secondary' : 'btn-outline-secondary');
            btn.textContent = (row.picked ? '選択中: ' : '') + row.label;
            btn.onclick = () => {
                if (mode === 'summon') {
                    hideModal();
                    send('summon-from-grave', { trashIndex: row.index });
                } else {
                    pickTrashCard(row.index);
                }
            };
            content.appendChild(btn);
        });
    }
    document.getElementById('info-modal').classList.remove('d-none');
}

/**
 * 墓地からの召喚(リーダー【黄泉の召喚主】の常在能力)。
 * サブフェイズ中のみ・回数制限なし・コストは通常どおり支払う。
 */
function openGraveSummon() {
    if (!latestView || !latestView.myTurn || latestView.phase !== 'SUB') {
        showMessage('墓地からの召喚はサブフェイズにのみ行えます');
        return;
    }
    openTrashPicker('summon');
}

function pickMana(index) {
    const req = currentRequirement();
    if (!req || req.kind !== 'MANA') return;
    if (pending.current.manaIndexes.includes(index)) return;
    pending.current.manaIndexes.push(index);
    advanceIfComplete();
}

/**
 * ウェポンの選択(聖光の武装解除)。ウェポンは1人1枚のためインスタンスIDを持たず、
 * 「自分」「相手」のどちら側かだけを選ぶ。マナ・墓地と同様に選択後は即座に確定に進める。
 */
function pickWeapon(side) {
    const req = currentRequirement();
    if (!req || req.kind !== 'WEAPON') return;
    if (pending.current.weaponSides.includes(side)) return;
    pending.current.weaponSides.push(side);
    advanceIfComplete();
}

// ---------------------------------------------------------------
// 禁忌カード
// ---------------------------------------------------------------

/**
 * ★Batch 43: 禁忌の帯の開閉。既定は畳む(1画面レイアウトの前提)。
 * 支払い中(tabooPay)は render() が強制的に開く —— マナを選ぶ間、
 * どの禁忌カードを使おうとしているかが見えていないと操作にならない。
 */
let tabooOpen = false;
function toggleTabooRow() {
    tabooOpen = !tabooOpen;
    syncTabooRow();
}
function syncTabooRow() {
    if (tabooPay) tabooOpen = true;
    document.getElementById('taboo-strip').classList.toggle('d-none', !tabooOpen);
    document.getElementById('btn-taboo-toggle')
        .classList.toggle('auto-chip-active', tabooOpen);
}

function onTabooCardClick(index) {
    if (hasPendingChoice()) return;
    if (pending || tabooPay || !latestView || !latestView.myTurn) return;
    if (latestView.phase !== 'MAIN') {
        showMessage('禁忌カードはメインフェイズにのみ使用できます');
        return;
    }
    const card = latestView.you.taboo[index];
    // 支払いに使えるマナ(ピュア・エレメントの一時マナは禁忌コストに使えない)
    const payable = latestView.you.manaZone.filter(m => !m.temporary).length;
    if (payable < card.cost) {
        showMessage(`禁忌コストの支払いに使えるマナが足りません(必要${card.cost}枚)`);
        return;
    }
    tabooPay = { tabooIndex: index, cost: card.cost, manaIndexes: [], specs: card.targets };
    if (card.cost === 0) {
        finishTabooPayment();
        return;
    }
    render(latestView);
}

function pickTabooMana(index) {
    if (!tabooPay) return;
    const mana = latestView.you.manaZone[index];
    if (mana.temporary) {
        showMessage('【ピュア・エレメント】は禁忌のコストにできません');
        return;
    }
    if (tabooPay.manaIndexes.includes(index)) return;
    tabooPay.manaIndexes.push(index);
    if (tabooPay.manaIndexes.length >= tabooPay.cost) {
        finishTabooPayment();
    } else {
        render(latestView);
    }
}

function finishTabooPayment() {
    const { tabooIndex, manaIndexes, specs } = tabooPay;
    tabooPay = null;
    beginSelection('play-taboo', null, specs, { tabooIndex, manaIndexes });
}

function cancelTabooPayment() {
    tabooPay = null;
    render(latestView);
}

function useLeaderAbility() {
    if (hasPendingChoice()) return;
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
            // 禁忌はMPではなくマナ枚数で支払う(一時マナは使えない)
            const payableMana = you.manaZone.filter(m => !m.temporary).length;
            const tabooPlayable = (you.taboo || []).some(c => c.cost <= payableMana);
            return playable || abilityUsable || tabooPlayable;
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

/** ★44: 名前の文字列 → フェイス一覧に格上げ(showZoneFaces)。旧関数名は互換のため残す */
function showTrashList(isSelf) {
    if (!latestView || !latestView.you) return;
    const p = isSelf ? latestView.you : latestView.opponent;
    showZoneFaces(`${p.displayName}の墓地(${p.trashCount}枚)`, p.trash || []);
}

function showLostList(isSelf) {
    if (!latestView || !latestView.you) return;
    const p = isSelf ? latestView.you : latestView.opponent;
    showZoneFaces(`${p.displayName}の消滅ゾーン(${p.lostCount}枚)`, p.lost || []);
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
    if (hasPendingChoice()) {
        // 割り込み選択中で、ミニオンを選ばせる問い合わせなら選択として扱う
        pickChoiceCandidateByMinion(instanceId);
        return;
    }
    if (pending) {
        pickMinion(instanceId, true);
        return;
    }
    if (!latestView || !latestView.myTurn) return;
    // メインフェイズ: 起動能力を持ちタップしていないミニオンのクリックは能力発動
    const minion = (latestView.you.minions || []).find(m => m.instanceId === instanceId);
    if (latestView.phase === 'MAIN' && minion && minion.canUseAbility) {
        useMinionAbility(instanceId);
        return;
    }
    if (latestView.phase !== 'BATTLE') return;
    selectedAttackerId = (selectedAttackerId === instanceId) ? null : instanceId;
    render(latestView);
}

/** ミニオンの起動能力を発動する(a6)。対象選択が要れば選択UIを開く */
function useMinionAbility(instanceId) {
    const minion = (latestView.you.minions || []).find(m => m.instanceId === instanceId);
    if (!minion || !minion.canUseAbility) return;
    // 起動能力の対象仕様はビューに載せていないため、まず対象なしで送る。
    // 対象を要する能力が出た時点で MinionView に仕様を足す(現状の静空の風使いは対象なし)
    send('minion-ability', { instanceId, targets: [] });
}

function onMyLeaderClick() {
    if (hasPendingChoice()) return;
    if (pending) return;
    if (!latestView || !latestView.myTurn || latestView.phase !== 'BATTLE') return;
    if (!latestView.you.leaderCanAttack) return;
    // 'LEADER'はリーダー自身を攻撃元として選択中であることを示す特別値
    selectedAttackerId = (selectedAttackerId === 'LEADER') ? null : 'LEADER';
    render(latestView);
}

function onOpponentMinionClick(instanceId) {
    if (hasPendingChoice()) {
        // 割り込み選択中で、相手のミニオンも候補になりうる(回帰の風穴の2回目対象など)
        pickChoiceCandidateByMinion(instanceId);
        return;
    }
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
// カードフェイス描画(Batch 42)
// ---------------------------------------------------------------

const FACE_TYPE_LABELS = {
    LEADER: 'リーダー', MINION: 'ミニオン', EVOLUTION: '進化ミニオン',
    SPELL: 'スペル', WEAPON: 'ウェポン',
};

/** 文明色。★値は書かない。正は battle.css の :root(裁定60)。manual-battle.js と同じ形 */
const civColorCache = new Map();
function civColor(civ) {
    const key = String(civ || 'NONE').toLowerCase();
    if (civColorCache.has(key)) return civColorCache.get(key);
    const read = (name) => getComputedStyle(document.documentElement)
        .getPropertyValue(name).trim();
    const value = read('--civ-' + key) || read('--civ-none');
    civColorCache.set(key, value);
    return value;
}

/**
 * カード定義の索引。カードID(id)→ {civilization, text, cost, ...}。
 * ★MinionView は文明とテキストを運ばない(現在値と可否だけを運ぶ)。サーバの
 *   ビューを太らせる代わりに、既にある口(card-library)から引く —— Java 変更ゼロで済み、
 *   「サーバの知っているカード」と「画面の知っているカード」の正が1つに保たれる(設計判断28)。
 *
 * ★★Batch 46b: 索引の鍵を ledgerCardId から id に変えた。
 *   通常モードのカードマスタが manual-cards.json になり、サーバが送ってくる cardId が
 *   このファイルの id そのものになったためである。★副産物として、台帳に無かった
 *   新カード66枚も索引に入る(以前は ledgerCardId が null で入らず、色もテキストも出なかった)。
 */
const autoLibrary = new Map();
/** ★45: 実物のカード裏面の画像ID。card-library の meta.backImageId(手動モードと同じ正) */
let autoBackImageId = null;
function loadCardLibrary() {
    fetch('/manual/api/card-library')
        .then(r => (r.ok ? r.json() : Promise.reject(r.status)))
        .then(data => {
            (data.cards || []).forEach(c => {
                if (c.id) autoLibrary.set(c.id, c);
            });
            if (data.meta && data.meta.backImageId) autoBackImageId = data.meta.backImageId;
            if (latestView) render(latestView); // 取得前に描いた分へ色とテキストを行き渡らせる
        })
        .catch(() => { /* ★無くても対戦は続けられる(上の javadoc)。壊さない */ });
}

/**
 * ★45: カードの裏面。実物の裏面画像(手動モード 25b と同じ)を使い、
 * 画像IDが未取得の間は縞のCSSにフォールバックする(壊さない、の系列)。
 */
function fillCardBack(el) {
    if (autoBackImageId) {
        const img = document.createElement('img');
        img.src = `/cards/${autoBackImageId}.png`;
        img.loading = 'lazy';
        img.className = 'auto-back-img';
        el.appendChild(img);
        return true;
    }
    return false;
}

/** カードIDから文明色を引く。未取得・未知IDは無文明色に落ちる */
function libCivColor(cardId) {
    const entry = autoLibrary.get(cardId);
    return entry ? civColor(entry.civilization) : civColor('NONE');
}

/**
 * フェイスの本文。★Batch 48-hotfix で「テキストに既に出ているキーワードは畳まない」に変えた。
 *
 * <b>なぜ畳む処理があるのか。</b> ビューの keywords は「印刷 + 効果で付与されたもの」である
 * (GameViewBuilder が master.keywords と minion.grantedKeywords を合成している)。
 * そよ風の加護で【守護】をもらったミニオンは、カードのテキストには何も書いていないのに
 * 守護を持つ —— これを面に出すために、キーワードを【】にして先頭へ足していた。
 *
 * <b>なぜ二重になったのか。</b> この処理は Ver0.4 の台帳を前提に書かれていた。
 * あの頃は keywords が独立フィールドで、text は<b>効果の文だけ</b>を持っていた。
 * ところが Batch 46b で正が manual-cards.json に移り、キーワードのフィールドは廃止されて
 * <b>テキストがキーワードの唯一の出どころ</b>になった(裁定158)。
 * 畳む相手とテキストが同じものになったので、印刷キーワードが2回出る。
 * 該当は235枚中125枚だった。
 *
 * <b>直し方。</b> 全部やめるのでは付与ぶんが見えなくなる。
 * <b>テキストに現れていないキーワードだけを畳む</b> —— こうすると、
 * 印刷キーワードは常にテキスト側の1回だけになり、効果で得たものだけが先頭に並ぶ。
 * ★判定はテキストとの突き合わせであり、印刷キーワードの一覧をここに持たない(裁定131)。
 */
function faceText(keywords, text) {
    const body = text || '';
    const granted = (keywords || []).filter(k => !body.includes('【' + k + '】'));
    const kw = granted.map(k => '【' + k + '】').join('');
    if (!kw) return body;
    return body ? kw + '\n' + body : kw;
}

/**
 * カードの表面。manual-battle.js の cardFace と同じ DOM・同じクラス名を出す(裁定111)。
 * data = { name, cost, type, civilization, keywords, text, attack, hp }
 * ★盤面のミニオン面(mini)は「タイル」であり<b>現在値</b>を出す。手動モードの
 *   .manual-tile が現在値を出すのと同じ意味論で、「フェイスは印刷値」(25)の例外である。
 */
function cardFace(data, variant) {
    const el = document.createElement('div');
    el.className = 'mcard mcard-' + variant;
    el.style.setProperty('--mc', data.civilization ? civColor(data.civilization) : civColor('NONE'));

    const inner = document.createElement('div');
    inner.className = 'mcard-inner';

    const head = document.createElement('div');
    head.className = 'mcard-head';
    if (data.type !== 'LEADER' && data.cost !== null && data.cost !== undefined) {
        const cost = document.createElement('span');
        cost.className = 'mcard-cost';
        cost.textContent = data.cost;
        head.appendChild(cost);
    }
    const name = document.createElement('span');
    name.className = 'mcard-name';
    name.textContent = data.name || '(不明)';
    head.appendChild(name);
    inner.appendChild(head);

    if (variant === 'large' || variant === 'full') {
        const type = document.createElement('div');
        type.className = 'mcard-type';
        type.textContent = FACE_TYPE_LABELS[data.type] || '';
        inner.appendChild(type);
        const text = document.createElement('div');
        text.className = 'mcard-text';
        text.textContent = faceText(data.keywords, data.text);
        inner.appendChild(text);
    } else {
        const spacer = document.createElement('div');
        spacer.className = 'mcard-spacer';
        inner.appendChild(spacer);
    }

    if (data.type === 'LEADER') {
        const foot = document.createElement('div');
        foot.className = 'mcard-foot mcard-foot-leader';
        foot.textContent = 'LEADER';
        inner.appendChild(foot);
    } else if (data.attack !== null && data.attack !== undefined
            || data.hp !== null && data.hp !== undefined) {
        const foot = document.createElement('div');
        foot.className = 'mcard-foot';
        if (data.attack !== null && data.attack !== undefined) {
            const atk = document.createElement('span');
            atk.className = 'mcard-atk';
            atk.textContent = '⚔' + data.attack;
            foot.appendChild(atk);
        }
        if (data.hp !== null && data.hp !== undefined) {
            const hp = document.createElement('span');
            hp.className = 'mcard-hp';
            if (data.hurt) hp.classList.add('mcard-hp-hurt');
            hp.textContent = '♥' + data.hp;
            foot.appendChild(hp);
        }
        inner.appendChild(foot);
    }

    el.appendChild(inner);
    return el;
}

/** CardView(手札・禁忌・墓地)→ フェイスのデータ。実効コストは呼び出し側で選ぶ */
function faceDataFromCardView(card, costOverride) {
    return {
        name: card.name, type: card.type, civilization: card.civilization,
        cost: costOverride !== undefined ? costOverride : card.cost,
        keywords: card.keywords, text: card.text, attack: card.attack, hp: card.hp,
    };
}

/** MinionView → フェイスのデータ。文明とテキストはカード定義から補う */
function faceDataFromMinion(minion) {
    const lib = autoLibrary.get(minion.cardId);
    return {
        name: minion.name, type: 'MINION',
        civilization: lib ? lib.civilization : null,
        cost: lib ? lib.cost : null,
        keywords: minion.keywords, text: lib ? lib.text : '',
        attack: minion.attack, hp: minion.currentHp + '/' + minion.maxHp,
        hurt: minion.currentHp < minion.maxHp,
    };
}

// ---------------------------------------------------------------
// 拡大(Batch 42)。★右クリック = 拡大(手動モードの 22 1-7 と同じ規約)
// ---------------------------------------------------------------

/**
 * ★oncontextmenu への<b>代入</b>で付ける(addEventListener にしない)。
 * リーダータイルなど描画のたびに使い回す静的要素にも付けるため、
 * addEventListener だと再描画のぶんだけハンドラが積み重なる。代入は何度でも冪等である。
 */
function attachZoom(el, dataFn) {
    el.oncontextmenu = (e) => {
        e.preventDefault();
        openZoom(dataFn());
    };
}

function openZoom(data) {
    const overlay = document.getElementById('auto-zoom');
    const holder = document.getElementById('auto-zoom-card');
    holder.innerHTML = '';
    holder.appendChild(cardFace(data, 'large'));
    overlay.classList.remove('d-none');
}

function closeZoom() {
    document.getElementById('auto-zoom').classList.add('d-none');
}

// ---------------------------------------------------------------
// パイルとゾーン一覧(Batch 44・案1)
// ---------------------------------------------------------------

/**
 * パイル1つ(山札・墓地・消滅・禁忌)。
 * ★枚数バッジが従来のチップの id(opp-deck-count 等)をそのまま引き継ぐ。
 *   数字を書き込む既存のコードは1行も変えずに、書き込み先だけが移る。
 * ★山札と禁忌は裏面である。山札の中身を見せる経路は作らない(仕分け D1)。
 */
function pileEl(label, opts) {
    const el = document.createElement('div');
    el.className = 'auto-pile';
    if (opts.id) el.id = opts.id;
    const cardHolder = document.createElement('div');
    cardHolder.className = 'auto-pile-card';
    if (opts.top) {
        cardHolder.appendChild(cardFace(faceDataFromCardView(opts.top), 'micro'));
        cardHolder.classList.add('auto-pile-stacked');
        attachZoom(el, () => faceDataFromCardView(opts.top));
    } else if (opts.back) {
        cardHolder.classList.add('auto-pile-back', 'auto-pile-stacked');
        if (!fillCardBack(cardHolder)) {
            const mark = document.createElement('span');
            mark.textContent = opts.back;
            cardHolder.appendChild(mark);
        }
    } else {
        cardHolder.classList.add('auto-pile-empty');
    }
    el.appendChild(cardHolder);
    const count = document.createElement('span');
    count.className = 'auto-pile-count';
    if (opts.countId) count.id = opts.countId;
    count.textContent = '-';
    el.appendChild(count);
    const lab = document.createElement('div');
    lab.className = 'auto-pile-label';
    lab.textContent = label;
    el.appendChild(lab);
    if (opts.onClick) {
        el.classList.add('auto-pile-clickable');
        el.onclick = opts.onClick;
    }
    return el;
}

/** 両席のパイル列。★描画のたびに作り直す(バッジの id はここで生まれる) */
function renderPiles(isSelf, p) {
    const wrap = document.getElementById(isSelf ? 'my-piles' : 'opp-piles');
    wrap.innerHTML = '';
    const prefix = isSelf ? 'my' : 'opp';
    const who = isSelf ? 'あなた' : p.displayName;
    wrap.appendChild(pileEl('山札', { back: 'QTE', countId: prefix + '-deck-count' }));
    const trashTop = (p.trash && p.trash.length > 0) ? p.trash[p.trash.length - 1] : null;
    wrap.appendChild(pileEl('墓地', {
        top: trashTop, countId: prefix + '-trash-count',
        onClick: () => showZoneFaces(`${who}の墓地(${p.trashCount}枚)`, p.trash),
    }));
    const lostTop = (p.lost && p.lost.length > 0) ? p.lost[p.lost.length - 1] : null;
    wrap.appendChild(pileEl('消滅', {
        top: lostTop, countId: prefix + '-lost-count',
        onClick: () => showZoneFaces(`${who}の消滅ゾーン(${p.lostCount}枚)`, p.lost),
    }));
    const tabooOpts = { back: '禁忌', countId: prefix + '-taboo-count' };
    if (isSelf) {
        tabooOpts.onClick = toggleTabooRow;
        tabooOpts.id = 'btn-taboo-toggle';   // ★43 のチップの id を引き継ぐ(syncTabooRow の参照先)
    }
    wrap.appendChild(pileEl('禁忌', tabooOpts));
}

/**
 * ゾーンの中身をフェイスの一覧で出す(墓地・消滅)。
 * 33 までの「名前の文字列」を面に格上げした。右クリックで1枚ずつ拡大できる。
 */
function showZoneFaces(title, cards) {
    document.getElementById('info-modal-title').textContent = title;
    const content = document.getElementById('info-modal-content');
    content.innerHTML = '';
    if (!cards || cards.length === 0) {
        content.textContent = '(なし)';
    } else {
        const grid = document.createElement('div');
        grid.className = 'auto-zone-grid';
        cards.forEach(card => {
            const holder = document.createElement('div');
            holder.className = 'auto-zone-card';
            holder.appendChild(cardFace(faceDataFromCardView(card), 'mini'));
            attachZoom(holder, () => faceDataFromCardView(card));
            grid.appendChild(holder);
        });
        content.appendChild(grid);
    }
    document.getElementById('info-modal').classList.remove('d-none');
}

// ---------------------------------------------------------------
// ホバープレビュー(Batch 44・B-1)。まずはリーダーから
// ---------------------------------------------------------------

let hoverTimer = null;
function attachHover(el, dataFn) {
    el.onmouseenter = () => {
        clearTimeout(hoverTimer);
        hoverTimer = setTimeout(() => {
            const holder = document.getElementById('auto-hover-card');
            holder.innerHTML = '';
            holder.appendChild(cardFace(dataFn(), 'large'));
            document.getElementById('auto-hover').classList.remove('d-none');
        }, 350);
    };
    el.onmouseleave = () => {
        clearTimeout(hoverTimer);
        document.getElementById('auto-hover').classList.add('d-none');
    };
}

/**
 * マナタイル1枚(両席で共用・45)。表向き=名前と文明色、裏向き=実物の裏面画像。
 * ★表向きのマナは公開情報であり、相手の表向きにも名前が出る(45・マスター指摘)。
 *   裏向きの中身はサーバが持ち主にしか送らない(こちらは title にだけ出る)。
 */
function buildManaTile(mana) {
    const tile = document.createElement('div');
    tile.className = 'mana-tile' + (mana.tapped ? ' tapped' : '') + (mana.faceUp ? '' : ' face-down');
    if (mana.faceUp) {
        const lib = mana.cardId ? autoLibrary.get(mana.cardId) : null;
        if (lib) tile.style.setProperty('--mana-civ', civColor(lib.civilization));
        const nameEl = document.createElement('div');
        nameEl.className = 'mana-tile-name';
        nameEl.textContent = mana.name || '';
        tile.appendChild(nameEl);
    } else {
        fillCardBack(tile);
        if (mana.name) tile.title = `(裏向き)${mana.name}`;   // 持ち主にだけ届いている
        else tile.title = '(裏向き)';
    }
    if (mana.temporary) tile.classList.add('mana-temporary');
    return tile;
}

/** ウェポンの面。効果テキストは card-library から(B2 の weaponCardId 経由・裁定144) */
function weaponFaceData(p) {
    const lib = p.weaponCardId ? autoLibrary.get(p.weaponCardId) : null;
    return {
        name: p.weaponName, type: 'WEAPON',
        civilization: lib ? lib.civilization : null,
        cost: lib ? lib.cost : null, keywords: [],
        text: lib ? lib.text : '', attack: p.weaponAttack, hp: null,
    };
}

// ---------------------------------------------------------------
// 3) 受信と描画
// ---------------------------------------------------------------

function onMessage(frame) {
    const message = JSON.parse(frame.body);
    if (message.type === 'ERROR') {
        showMessage(message.message);
        pending = null; // サーバに拒否された選択は最初からやり直す
        tabooPay = null;
        render(latestView);
        return;
    }
    latestView = message.view;
    // 盤面が変わったら選択状態は仕切り直す(対象が既にいない可能性があるため)
    if (!latestView.myTurn || latestView.phase !== 'BATTLE') {
        selectedAttackerId = null;
    }
    // 割り込み選択が無くなった(解決済み)なら、選びかけの内容も捨てる
    if (!latestView.you || !latestView.you.pendingChoice) {
        choicePicks = [];
    }
    pending = null;
    tabooPay = null;
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
    renderPendingChoice(view);
    syncTabooRow();

    if (view.status === 'FINISHED') {
        showMessage('対戦終了: ' + view.winnerName + ' の勝利');
    }
    maybeAutoAdvance(view);
}

/**
 * 割り込み選択(a9): 効果の解決中にサーバが問い合わせてきた選択のUI。
 * 手札・場・墓地・公開領域のいずれからでも、候補の並び順の位置を選んで送り返す。
 * 既存の対象選択(pending)とは別物(あちらは使用宣言時、こちらは解決の途中)。
 */
function renderPendingChoice(view) {
    const area = document.getElementById('reveal-area');
    const choice = view.you && view.you.pendingChoice;
    area.classList.toggle('d-none', !choice);
    const row = document.getElementById('reveal-cards');
    const promptEl = document.getElementById('reveal-prompt');
    row.innerHTML = '';
    if (!choice) return;
    promptEl.textContent = choice.prompt;
    const multi = choice.max > 1 || choice.min === 0;
    choice.candidates.forEach(cand => {
        const btn = document.createElement('button');
        btn.type = 'button';
        const picked = choicePicks.includes(cand.index);
        btn.className = 'btn btn-sm ' + (picked ? 'btn-warning' : 'btn-outline-warning');
        btn.textContent = cand.label
            + (cand.keywords && cand.keywords.length ? ` [${cand.keywords.join(' ')}]` : '');
        btn.onclick = () => toggleChoicePick(cand.index, choice);
        row.appendChild(btn);
    });
    // 確定・キャンセルの制御。1つだけ選ぶ選択(min=max=1)はクリックで即確定するため、
    // 「確定」ボタンは複数選択・任意選択のときだけ出す
    const confirmBtn = document.getElementById('btn-confirm-choice');
    confirmBtn.classList.toggle('d-none', !multi);
    if (multi) {
        confirmBtn.textContent = `この内容で確定 (${choicePicks.length}/${choice.max})`;
    }
}

/** 候補のトグル(複数選択)または即確定(単一選択) */
function toggleChoicePick(index, choice) {
    if (choice.max === 1 && choice.min === 1) {
        // 1つだけ選ぶ: クリックで即送信
        send('resolve-choice', { chosenIndexes: [index] });
        choicePicks = [];
        return;
    }
    const pos = choicePicks.indexOf(index);
    if (pos >= 0) {
        choicePicks.splice(pos, 1);
    } else if (choicePicks.length < choice.max) {
        choicePicks.push(index);
    }
    render(latestView);
}

/**
 * 割り込み選択中に場のミニオンを直接クリックしたときの処理(Batch 12b)。
 * サーバから届く候補には minionInstanceId が入っているため(kindがMINIONのときのみ)、
 * クリックされたインスタンスIDと一致する候補のindexを探して送り返す。
 * 一致する候補が無い(選べない側のミニオンをクリックした等)場合は何もしない。
 */
function pickChoiceCandidateByMinion(instanceId) {
    const choice = latestView.you && latestView.you.pendingChoice;
    if (!choice || choice.kind !== 'MINION') return;
    const cand = choice.candidates.find(c => c.minionInstanceId === instanceId);
    if (!cand) return;
    toggleChoicePick(cand.index, choice);
}
function confirmChoice() {
    const choice = latestView.you && latestView.you.pendingChoice;
    if (!choice) return;
    if (choicePicks.length < choice.min) {
        showMessage(`最低${choice.min}枚選んでください`);
        return;
    }
    send('resolve-choice', { chosenIndexes: choicePicks });
    choicePicks = [];
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
    const choosing = !!(view.you && view.you.pendingChoice);
    controls.classList.toggle('d-none', !(view.status === 'PLAYING' && view.myTurn) || choosing);
    // 墓地からの召喚は【黄泉の召喚主】のサブフェイズ限定(常在能力)
    const graveSummon = view.status === 'PLAYING' && view.myTurn && view.phase === 'SUB'
        && view.you.leaderCardId === 'QTE-M-DARK-15';
    document.getElementById('btn-summon-grave').classList.toggle('d-none', !graveSummon);
}

function renderSelection() {
    const area = document.getElementById('selection-area');
    const skipBtn = document.getElementById('btn-skip-target');
    if (tabooPay) {
        // 禁忌コストの支払い中(表向き→裏向き / 裏向き→墓地送り)
        area.classList.remove('d-none');
        document.getElementById('selection-prompt').textContent =
            `禁忌コストに充てるマナを選んでください(${tabooPay.manaIndexes.length}/${tabooPay.cost}) `
            + '表向き=裏向きにする / 裏向き=墓地へ送る';
        skipBtn.classList.add('d-none');
        return;
    }
    const req = currentRequirement();
    area.classList.toggle('d-none', !req);
    if (!req) return;
    const picked = pending.current.handIndexes.length + pending.current.minionIds.length
        + pending.current.manaIndexes.length + pending.current.trashIndexes.length
        + pending.current.weaponSides.length;
    document.getElementById('selection-prompt').textContent =
        `${req.prompt} (${picked}/${req.count}${req.upTo ? 'まで' : ''})`;
    // upTo(好きな数)は0枚でも確定できるため「選ばない」ではなく「確定」を出す
    skipBtn.classList.toggle('d-none', !req.optional || req.upTo);
    document.getElementById('btn-confirm-target').classList.toggle('d-none', !req.upTo);
    document.getElementById('btn-open-trash').classList.toggle('d-none', req.kind !== 'TRASH');
}

/**
 * ★45: ログは畳む(マスター指示)。常設はバー(最新1行+件数)だけで、
 * 全文はバーのクリックで開くパネルにある。どちらも同じ配列から毎回描き直す。
 */
function renderLog(log) {
    const lines = log || [];
    document.getElementById('log-bar-last').textContent =
        lines.length > 0 ? lines[lines.length - 1] : '(ログなし)';
    document.getElementById('log-bar-count').textContent = lines.length + '件';
    const box = document.getElementById('log-area');
    box.innerHTML = '';
    lines.forEach(line => {
        const div = document.createElement('div');
        div.textContent = line;
        box.appendChild(div);
    });
    box.scrollTop = box.scrollHeight;
}

function toggleLogPanel() {
    document.getElementById('log-panel').classList.toggle('d-none');
}

function renderOpponent(opp, view) {
    renderPiles(false, opp);   // ★44: 先にパイルを作る(枚数バッジの id はパイルが持つ)
    document.getElementById('opp-leader-name').textContent = opp.leaderName;
    document.getElementById('opp-lp').textContent = opp.lp;
    document.getElementById('opp-leader-ability').textContent = opp.leaderText || '';
    // 手札は裏面の列で見せる(枚数の体感化)
    const backs = document.getElementById('opp-hand-backs');
    backs.innerHTML = '';
    for (let i = 0; i < opp.handCount; i++) {
        const b = document.createElement('div');
        b.className = 'auto-back';
        fillCardBack(b);
        backs.appendChild(b);
    }
    document.getElementById('opp-hand-count').textContent = opp.handCount;
    document.getElementById('opp-deck-count').textContent = opp.deckCount;
    document.getElementById('opp-mp').textContent = opp.availableMp;
    document.getElementById('opp-mana-count').textContent = opp.totalMana;
    const oppWeaponEl = document.getElementById('opp-weapon');
    oppWeaponEl.textContent = opp.weaponName ? `${opp.weaponName} ⚔${opp.weaponAttack}` : 'なし';
    const weaponReqOpp = currentRequirement();
    const oppWeaponPickable = weaponReqOpp && weaponReqOpp.kind === 'WEAPON' && opp.weaponName
        && !pending.current.weaponSides.includes('OPPONENT');
    oppWeaponEl.classList.toggle('text-warning', !!oppWeaponPickable);
    oppWeaponEl.style.cursor = oppWeaponPickable ? 'pointer' : '';
    oppWeaponEl.onclick = oppWeaponPickable ? () => pickWeapon('OPPONENT') : null;
    document.getElementById('opp-trash-count').textContent = opp.trashCount;
    document.getElementById('opp-lost-count').textContent = opp.lostCount;
    document.getElementById('opp-taboo-count').textContent = opp.tabooCount;
    document.getElementById('opp-deck-name').textContent = opp.deckName;

    // ★45: 相手のマナもタイル(マスター指摘: 表向きの中身が分からないのは問題)。
    //   表向きは名前・文明色つき。裏向きは裏面で、中身はそもそも届いていない
    const oppManaRow = document.getElementById('opp-mana-row');
    oppManaRow.innerHTML = '';
    oppManaRow.classList.remove('auto-mana-overlap');
    opp.manaZone.forEach(mana => oppManaRow.appendChild(buildManaTile(mana)));
    oppManaRow.classList.toggle('auto-mana-overlap', oppManaRow.scrollWidth > oppManaRow.clientWidth);

    const leaderEl = document.getElementById('opp-leader');
    // ★Batch 42: 文明色。リーダーの文明はビューに無いので台帳IDから引く(取得前は無文明色)
    leaderEl.style.setProperty('--mc', libCivColor(opp.leaderCardId));
    const oppLeaderFace = () => ({
        name: opp.leaderName, type: 'LEADER', keywords: [],
        civilization: (autoLibrary.get(opp.leaderCardId) || {}).civilization,
        text: opp.leaderText, cost: null, attack: null, hp: null,
    });
    attachZoom(leaderEl, oppLeaderFace);
    attachHover(leaderEl, oppLeaderFace);   // ★44: ホバープレビュー(B-1)
    // ★ウェポンの行は右クリックでウェポンの面(効果は library から。B2)
    const oppWeaponLine = leaderEl.querySelector('.auto-leader-weapon');
    if (opp.weaponName) {
        oppWeaponLine.oncontextmenu = (e) => {
            e.preventDefault();
            e.stopPropagation();
            openZoom(weaponFaceData(opp));
        };
    } else {
        oppWeaponLine.oncontextmenu = null;
    }
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
    renderPiles(true, you);   // ★44: 先にパイルを作る(バッジと禁忌トグルの id はパイルが持つ)
    document.getElementById('my-leader-name').textContent = you.leaderName;
    document.getElementById('my-lp').textContent = you.lp;
    document.getElementById('my-leader-ability').textContent = you.leaderText || '';
    document.getElementById('my-deck-count').textContent = you.deckCount;
    document.getElementById('my-mp').textContent = you.availableMp;
    document.getElementById('my-mana-count').textContent = you.totalMana;
    const myWeaponEl = document.getElementById('my-weapon');
    myWeaponEl.textContent = you.weaponName ? `${you.weaponName} ⚔${you.weaponAttack}` : 'なし';
    const weaponReqSelf = currentRequirement();
    const selfWeaponPickable = weaponReqSelf && weaponReqSelf.kind === 'WEAPON' && you.weaponName
        && !pending.current.weaponSides.includes('SELF');
    myWeaponEl.classList.toggle('text-warning', !!selfWeaponPickable);
    myWeaponEl.style.cursor = selfWeaponPickable ? 'pointer' : '';
    myWeaponEl.onclick = selfWeaponPickable ? () => pickWeapon('SELF') : null;
    document.getElementById('my-trash-count').textContent = you.trashCount;
    document.getElementById('my-lost-count').textContent = you.lostCount;
    document.getElementById('my-taboo-count').textContent = you.tabooCount;
    document.getElementById('my-deck-name').textContent = you.deckName;

    // リーダー能力ボタン
    const abilityBtn = document.getElementById('btn-leader-ability');
    const ability = you.leaderAbility;
    abilityBtn.classList.toggle('d-none', !(ability && ability.usable && !pending));
    if (ability) abilityBtn.title = ability.description + (ability.mpCost > 0 ? `(MP${ability.mpCost})` : '');

    // 自リーダー: バトルフェイズにウェポンで攻撃できるならクリック可能
    const myLeaderEl = document.getElementById('my-leader');
    myLeaderEl.style.setProperty('--mc', libCivColor(you.leaderCardId));
    const myLeaderFace = () => ({
        name: you.leaderName, type: 'LEADER', keywords: [],
        civilization: (autoLibrary.get(you.leaderCardId) || {}).civilization,
        text: you.leaderText, cost: null, attack: null, hp: null,
    });
    attachZoom(myLeaderEl, myLeaderFace);
    attachHover(myLeaderEl, myLeaderFace);
    const myWeaponLine = myLeaderEl.querySelector('.auto-leader-weapon');
    if (you.weaponName) {
        myWeaponLine.oncontextmenu = (e) => {
            e.preventDefault();
            e.stopPropagation();
            openZoom(weaponFaceData(you));
        };
    } else {
        myWeaponLine.oncontextmenu = null;
    }
    const leaderReady = !pending && view.myTurn && view.phase === 'BATTLE' && you.leaderCanAttack;
    myLeaderEl.classList.toggle('attackable', leaderReady);
    myLeaderEl.classList.toggle('selected-attacker', selectedAttackerId === 'LEADER');
    myLeaderEl.onclick = leaderReady ? onMyLeaderClick : null;

    // ★44→45: マナは名前つきタイル(buildManaTile)。選択のクラス名と click は 43 以前から不変
    const manaReq = currentRequirement();
    const manaRow = document.getElementById('my-mana-row');
    manaRow.innerHTML = '';
    manaRow.classList.remove('auto-mana-overlap');
    you.manaZone.forEach((mana, index) => {
        const tile = buildManaTile(mana);
        if (tabooPay) {
            if (!mana.temporary) {
                tile.classList.add('taboo-payable');
                tile.onclick = () => pickTabooMana(index);
            }
            if (tabooPay.manaIndexes.includes(index)) tile.classList.add('taboo-picked');
        } else if (manaReq && manaReq.kind === 'MANA') {
            tile.classList.add('mana-selectable');
            if (pending.current.manaIndexes.includes(index)) tile.classList.add('mana-picked');
            tile.onclick = () => pickMana(index);
        }
        manaRow.appendChild(tile);
    });
    // ★並びきらないときだけ重ねる(手動モードの applyManaOverlap と同じ思想の簡易版)
    manaRow.classList.toggle('auto-mana-overlap', manaRow.scrollWidth > manaRow.clientWidth);

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
            // メインフェイズ: 起動能力が使えるミニオンもクリック可能にする(a6)
            if (view.myTurn && view.phase === 'MAIN' && minion.canUseAbility) {
                el.classList.add('can-attack');
                el.onclick = () => onMyMinionClick(minion.instanceId);
            }
            if (!minion.canAttackMinion && !minion.canAttackLeader && !minion.canUseAbility) {
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

    // 禁忌デッキ(所有者のみ中身が届く)
    const tabooRow = document.getElementById('my-taboo');
    tabooRow.innerHTML = '';
    (you.taboo || []).forEach((card, index) => {
        const el = createTabooCardEl(card, index, view);
        el.onclick = () => onTabooCardClick(index);
        tabooRow.appendChild(el);
    });
}

function createTabooCardEl(card, index, view) {
    const el = document.createElement('div');
    el.className = 'auto-card auto-card-hand';
    const payable = view.you.manaZone.filter(m => !m.temporary).length;
    if (!pending && !tabooPay && view.myTurn && view.phase === 'MAIN' && payable >= card.cost) {
        el.classList.add('playable');
    }
    if (tabooPay && tabooPay.tabooIndex === index) el.classList.add('selected-attacker');
    el.appendChild(cardFace(faceDataFromCardView(card), 'full'));
    const badges = newBadgeBox();
    addUnimplementedBadge(badges, card);
    attachBadges(el, badges);
    attachZoom(el, () => faceDataFromCardView(card));
    return el;
}

/**
 * バッジの入れ物(★Batch 47)。手札・禁忌・場のミニオンで同じ形のバッジを出すため、
 * 作り方を1箇所にまとめた。
 * ★面(.mcard-*)には手を入れない —— バッジは面の<b>上に重ねる</b>別要素である(裁定141)。
 */
function newBadgeBox() {
    const box = document.createElement('div');
    box.className = 'auto-badges';
    return box;
}

/** バッジを1枚足す。extraClass は色を変えたいときだけ渡す */
function addBadge(box, label, extraClass) {
    const b = document.createElement('span');
    b.className = extraClass ? 'auto-badge ' + extraClass : 'auto-badge';
    b.textContent = label;
    box.appendChild(b);
}

/** 1枚もバッジが無いときは入れ物ごと出さない(空の箱を DOM に残さない) */
function attachBadges(el, box) {
    if (box.childNodes.length > 0) el.appendChild(box);
}

/**
 * 「効果未実装」の印(★Batch 47・裁定D2)。
 *
 * ★判定はサーバが済ませてある(EffectImplementation)。クライアントは
 * 届いた真偽値を描くだけで、実装済みかどうかの規則をこちら側に持たない ——
 * 持った瞬間、同じ規則が Java とブラウザの2箇所に置かれることになる(裁定163)。
 */
function addUnimplementedBadge(box, cardOrMinion) {
    if (cardOrMinion && cardOrMinion.effectUnimplemented) {
        addBadge(box, '⚠効果未実装', 'auto-badge-unimplemented');
    }
}

function createMinionEl(minion) {
    const el = document.createElement('div');
    el.className = 'auto-card auto-card-minion';
    if (minion.tapped) el.classList.add('tapped-minion');
    el.appendChild(cardFace(faceDataFromMinion(minion), 'mini'));
    // ★一時状態は面に混ぜず、バッジで上に重ねる(manual の .manual-tapped-badge と同じ考え方)
    const badges = newBadgeBox();
    if (minion.frozen) addBadge(badges, '❄凍結');
    if (minion.tapped) addBadge(badges, '⟳');
    if (minion.canUseAbility) addBadge(badges, '⚡能力');
    addUnimplementedBadge(badges, minion);
    attachBadges(el, badges);
    // ★拡大は「効果テキストを読む」ためのもの。abilityText(起動能力)があれば添える
    attachZoom(el, () => {
        const data = faceDataFromMinion(minion);
        if (minion.abilityText && !(data.text || '').includes(minion.abilityText)) {
            data.text = (data.text ? data.text + '\n' : '') + minion.abilityText;
        }
        return data;
    });
    return el;
}

function createHandCardEl(card, index, view) {
    const el = document.createElement('div');
    el.className = 'auto-card auto-card-hand';
    if (latestView && latestView.mulligan) {
        el.classList.add('playable');
        if (mulliganPicks.includes(index)) el.classList.add('mulligan-selected');
    }
    const req = currentRequirement();
    if (req && req.kind === 'HAND') {
        // 対象選択中: 選択可能な手札を光らせる
        const selectable = index !== pending.handIndex
            && !isPicked('HAND', index)
            && (req.filters || []).every(f => {
                switch (f) {
                    case 'KNOWLEDGE': return card.keywords.includes('知識');
                    case 'GUARD': return card.keywords.includes('守護');
                    case 'MINION_CARD': return card.type === 'MINION';
                    case 'HP_5_OR_LESS': return card.hp != null && card.hp <= 5;
                    case 'COST_4_OR_LESS': return card.cost != null && card.cost <= 4;
                    case 'COST_3_OR_LESS': return card.cost != null && card.cost <= 3;
                    case 'COST_7_OR_LESS': return card.cost != null && card.cost <= 7;
                    case 'LIGHT_CIVILIZATION': return card.civilization === 'LIGHT';
                    default: return true;
                }
            });
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
    // ★実効コストが違うときはコストの宝石に実効値を出し、印で分かるようにする
    //   (例: 双流の幻術師)。両方の数字は title と拡大で確かめられる
    const eff = card.effectiveCost != null ? card.effectiveCost : card.cost;
    const face = cardFace(faceDataFromCardView(card, eff), 'full');
    if (card.effectiveCost != null && card.effectiveCost !== card.cost) {
        face.classList.add('mcard-cost-modified');
        el.title = `印刷コスト${card.cost} → 実効${card.effectiveCost}`;
    }
    el.appendChild(face);
    const badges = newBadgeBox();
    if (card.canSpecialSummon) addBadge(badges, '★特殊召喚可');
    addUnimplementedBadge(badges, card);
    attachBadges(el, badges);
    attachZoom(el, () => faceDataFromCardView(card));
    return el;
}

function showMessage(text) {
    const area = document.getElementById('message-area');
    area.textContent = text;
    area.classList.remove('d-none');
    clearTimeout(showMessage.timer);
    showMessage.timer = setTimeout(() => area.classList.add('d-none'), 4000);
}

// ★Batch 42: escapeHtml は退役した。フェイスは createElement + textContent で組むため、
//   エスケープという工程そのものが存在しない(忘れようがない)。
