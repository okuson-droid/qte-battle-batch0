/**
 * 手動モード盤面のクライアント処理(Batch 18b)。
 *
 * 構造は通常モードの battle.js と同じ3層。
 *   1) 接続: STOMPで /ws に接続し、自分専用の宛先を購読する
 *   2) 送信: 操作 → /app/manual/{roomId}/{action} へメッセージ送信
 *   3) 受信: サーバから届いたビュー(ManualGameView)で画面を全描画し直す
 *
 * ★18bは画面の骨組み(設計書 4-1〜4-4)のみを作った。本バッチ(18c)はそこに
 * ゾーンを開く画面(帯・全面表示・検索。設計書 4-6)を足す。既存の操作は無変更。
 *
 * クライアントが自分で持つ状態:
 *   - latestView: サーバから届いた最新のビュー(ManualGameView)。再描画の元になる
 *   - selected: 進化の素材や複数移動のために Ctrl/Cmd+クリックで選んだ instanceId の集合
 *   - cardLocation: instanceId -> {seatId, zone} の索引。直近の描画から作り直す。
 *     ドロップ判定(進化か移動か)に使う。★18cで進化スタックの下段(素材)も含めるよう拡張した
 *     (renderStackRowが最上段と一緒に登録する)。
 *   - pinnedZoom: 右下に固定表示中のカード
 *   - activeOverlay: 帯・全面表示のうち現在開いているものの種別と対象(12章)。
 *     null なら何も開いていない。renderAll の最後で毎回このオーバーレイを描き直す。
 */

let latestView = null;
let selected = new Set();
let cardLocation = new Map();
let pinnedZoom = null;

// ---------------------------------------------------------------
// 1) 接続
// ---------------------------------------------------------------

const wsProtocol = location.protocol === 'https:' ? 'wss' : 'ws';
const client = new StompJs.Client({
    brokerURL: `${wsProtocol}://${location.host}/ws`,
    reconnectDelay: 3000,
});

client.onConnect = () => {
    setConnectionStatus('接続済み');
    client.subscribe(`/topic/manual/${ROOM_ID}/view/${OCCUPANT_ID}`, onMessage);
    send('ready', {});
};

client.onWebSocketClose = () => setConnectionStatus('切断(再接続中...)');
client.activate();

function setConnectionStatus(text) {
    document.getElementById('connection-status').textContent = text;
}

function send(action, payload) {
    client.publish({
        destination: `/app/manual/${ROOM_ID}/${action}`,
        body: JSON.stringify({ occupantId: OCCUPANT_ID, ...payload }),
    });
}

// ---------------------------------------------------------------
// 2) 受信
// ---------------------------------------------------------------

function onMessage(frame) {
    const msg = JSON.parse(frame.body);
    if (msg.type === 'ERROR') {
        showTransientError(msg.message);
        return;
    }
    latestView = msg.view;
    renderAll(latestView);
}

function showTransientError(message) {
    const box = document.getElementById('deck-import-status');
    box.textContent = '⚠ ' + message;
    box.classList.add('text-danger');
    setTimeout(() => {
        if (box.textContent === '⚠ ' + message) {
            box.textContent = '';
            box.classList.remove('text-danger');
        }
    }, 4000);
}

/** ゾーンが空のときなど、操作できないことをログ欄で軽く案内する */
function showTransientNotice(message) {
    showTransientError(message);
}

// ---------------------------------------------------------------
// 3) 文明色(設計書 4-2)
// ---------------------------------------------------------------

const CIV_COLORS = {
    WATER: '#5E17EB', FIRE: '#FF5757', DARK: '#CB6CE6',
    LIGHT: '#FFDE59', WIND: '#7ED957', EARTH: '#FF66C4', NONE: '#B4B2A9',
};

/** 本体色に対する黒のコントラスト比が4.5未満なら白を返す(直書きしない計算) */
function textColorFor(hex) {
    const rgb = [1, 3, 5].map(i => parseInt(hex.slice(i, i + 2), 16) / 255);
    const lin = rgb.map(c => (c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4)));
    const luminance = 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2];
    const contrastWithBlack = (luminance + 0.05) / 0.05;
    return contrastWithBlack < 4.5 ? '#ffffff' : '#000000';
}

function civColor(civ) {
    return CIV_COLORS[civ] || CIV_COLORS.NONE;
}

// ---------------------------------------------------------------
// 4) 描画本体
// ---------------------------------------------------------------

function renderAll(view) {
    cardLocation = new Map();
    renderHeader(view);
    renderZoneBar('zone-bar-self', view.seatA, true);
    renderOpponent(view);
    renderSelfField(view);
    renderLeaderAndMana(view);
    renderHand(view);
    renderLog(view.log);
    if (pinnedZoom) {
        renderZoom(pinnedZoom, view.backImageId);
    }
    refreshOverlay();
}

function renderHeader(view) {
    document.getElementById('turn-number').textContent = view.turnNumber;
    document.getElementById('phase-name').textContent = phaseLabel(view.phase);
    document.getElementById('btn-undo').disabled = !view.canUndo;
    document.getElementById('btn-redo').disabled = !view.canRedo;
}

const PHASE_LABELS = {
    DRAW: 'ドロー', UNTAP: 'アンタップ', MANA_CHARGE: 'マナチャージ',
    MAIN: 'メイン', BATTLE: 'バトル', SUB: 'サブ', END: 'ターンエンド',
};
function phaseLabel(phase) { return PHASE_LABELS[phase] || phase; }

const ZONE_LABELS = {
    DECK: '山札', HAND: '手札', MANA: 'マナ', FIELD: 'ミニオン', WEAPON: 'ウェポン',
    TRASH: '墓地', LOST: '消滅', TABOO: '禁忌', REVEAL: '一時公開',
};

/** 自席ゾーンバー(左110px)。山札・墓地・消滅・禁忌・一時公開を縦に並べる */
function renderZoneBar(containerId, seatView, isSelf) {
    const el = document.getElementById(containerId);
    el.innerHTML = '';
    const zones = ['DECK', 'TRASH', 'LOST', 'TABOO', 'REVEAL'];
    for (const zoneName of zones) {
        const pile = seatView.zones[zoneName] || [];
        const box = document.createElement('div');
        box.className = 'zone-pile mb-2';
        box.dataset.seat = seatView.id;
        box.dataset.zone = zoneName;

        const header = document.createElement('div');
        header.className = 'small text-muted d-flex justify-content-between';
        header.innerHTML = `<span>${ZONE_LABELS[zoneName]}</span><span>${pile.length}</span>`;
        box.appendChild(header);

        const face = document.createElement('div');
        face.className = 'zone-pile-face';
        if (pile.length > 0) {
            const top = pile[pile.length - 1];
            face.textContent = top.name || '(名前なし)';
            face.title = top.name || '';
        }
        box.appendChild(face);

        // ドロップ対象として登録
        registerDropTarget(box, seatView.id, zoneName);

        if (zoneName === 'DECK') {
            box.addEventListener('click', () => send('draw', { seat: seatView.id, count: 1 }));
            box.addEventListener('contextmenu', (e) => { e.preventDefault(); openDeckFullscreen(seatView.id); });
            box.title = '左クリック: 1枚ドロー / 右クリック: 全面表示';

            const shuffleBtn = document.createElement('button');
            shuffleBtn.className = 'btn btn-sm btn-outline-secondary w-100 mt-1 py-0';
            shuffleBtn.textContent = 'シャッフル';
            shuffleBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                send('shuffle', { seat: seatView.id });
            });
            box.appendChild(shuffleBtn);

            const dropRow = document.createElement('div');
            dropRow.className = 'd-flex gap-1 mt-1';
            const top1 = document.createElement('div');
            top1.className = 'zone-drop-mini';
            top1.textContent = '上へ';
            registerDropTarget(top1, seatView.id, 'DECK', 0);
            const bottom1 = document.createElement('div');
            bottom1.className = 'zone-drop-mini';
            bottom1.textContent = '下へ';
            registerDropTarget(bottom1, seatView.id, 'DECK', 999999);
            dropRow.appendChild(top1);
            dropRow.appendChild(bottom1);
            box.appendChild(dropRow);
        } else {
            // ★18c: 左クリックで帯を開く(4-6)。最上段の拡大は右クリックへ寄せた
            // (場のカードの右クリック規約と揃える。マスター確認済み)。
            box.addEventListener('click', () => openZoneBand(seatView.id, zoneName));
            box.addEventListener('contextmenu', (e) => {
                e.preventDefault();
                if (pile.length > 0) {
                    setZoom(pile[pile.length - 1]);
                } else {
                    showTransientNotice(ZONE_LABELS[zoneName] + 'は空です');
                }
            });
        }

        el.appendChild(box);
    }
}

/** 相手席(B席)。LP + ミニオンゾーン + 小型ゾーンバー(枚数のみ) */
function renderOpponent(view) {
    const el = document.getElementById('seat-opponent');
    el.innerHTML = '';
    const seat = view.seatB;

    const top = document.createElement('div');
    top.className = 'd-flex align-items-center gap-2 mb-1';
    top.appendChild(createLeaderTile(seat));

    const fieldRow = document.createElement('div');
    fieldRow.className = 'minion-row flex-grow-1';
    fieldRow.dataset.seat = 'B';
    fieldRow.dataset.zone = 'FIELD';
    renderStackRow(fieldRow, seat, 'FIELD', 7);
    top.appendChild(fieldRow);

    const bar = document.createElement('div');
    bar.className = 'd-flex gap-1';
    for (const zoneName of ['DECK', 'TRASH', 'LOST', 'TABOO', 'REVEAL']) {
        const count = (seat.zones[zoneName] || []).length;
        const chip = document.createElement('div');
        chip.className = 'zone-pile-mini';
        chip.title = ZONE_LABELS[zoneName];
        chip.textContent = `${ZONE_LABELS[zoneName][0]}${count}`;
        registerDropTarget(chip, 'B', zoneName);
        bar.appendChild(chip);
    }
    top.appendChild(bar);

    el.appendChild(top);
}

/** 自席ミニオン + ウェポン */
function renderSelfField(view) {
    const el = document.getElementById('seat-self-field');
    el.innerHTML = '';
    const seat = view.seatA;

    const fieldRow = document.createElement('div');
    fieldRow.className = 'minion-row';
    fieldRow.dataset.seat = 'A';
    fieldRow.dataset.zone = 'FIELD';
    renderStackRow(fieldRow, seat, 'FIELD', 7);

    const weaponRow = document.createElement('div');
    weaponRow.className = 'minion-row mt-1';
    weaponRow.dataset.seat = 'A';
    weaponRow.dataset.zone = 'WEAPON';
    renderStackRow(weaponRow, seat, 'WEAPON', 1);

    const label1 = document.createElement('div');
    label1.className = 'small text-muted';
    label1.textContent = 'ミニオン';
    const label2 = document.createElement('div');
    label2.className = 'small text-muted mt-1';
    label2.textContent = 'ウェポン';

    el.appendChild(label1);
    el.appendChild(fieldRow);
    el.appendChild(label2);
    el.appendChild(weaponRow);
}

/** ゾーン1つぶんのタイル列を描画する。最小枠数(minSlots)まで空き枠を用意する */
function renderStackRow(container, seatView, zoneName, minSlots) {
    const cards = seatView.zones[zoneName] || [];
    for (const card of cards) {
        container.appendChild(createFieldTile(card, seatView.id, zoneName));
        cardLocation.set(card.instanceId, { seatId: seatView.id, zone: zoneName });
        // ★18c: 進化スタックの下段(素材)も索引に含める。帯を開いていなくても
        // Ctrl/Cmd選択→別の進化ドラッグの組み合わせで参照されうるため、常に登録する。
        for (const material of (card.materials || [])) {
            cardLocation.set(material.instanceId, { seatId: seatView.id, zone: zoneName });
        }
    }
    // ★常に最低1枠は空きを見せる(ドロップ先として)。FIELDはminSlots(7)まで埋める
    const emptyCount = Math.max(minSlots - cards.length, 1);
    for (let i = 0; i < emptyCount; i++) {
        const slot = document.createElement('div');
        slot.className = 'tile-slot-empty';
        registerDropTarget(slot, seatView.id, zoneName);
        container.appendChild(slot);
    }
}

/** リーダーとマナ */
function renderLeaderAndMana(view) {
    const el = document.getElementById('seat-self-leader-mana');
    el.innerHTML = '';
    const seat = view.seatA;

    const row = document.createElement('div');
    row.className = 'd-flex align-items-center gap-2';
    row.appendChild(createLeaderTile(seat));

    const manaWrap = document.createElement('div');
    manaWrap.className = 'flex-grow-1';
    const manaHeader = document.createElement('div');
    manaHeader.className = 'small text-muted';
    manaHeader.textContent = `MP ${seat.mp}`;
    manaWrap.appendChild(manaHeader);

    const manaRow = document.createElement('div');
    manaRow.className = 'mana-row';
    for (const card of (seat.zones.MANA || [])) {
        const chip = document.createElement('div');
        chip.className = 'mana-chip' + (card.tapped ? ' tapped' : '') + (card.faceDown ? ' face-down' : '');
        chip.title = card.faceDown ? '裏向き' : (card.name || '');
        chip.draggable = true;
        chip.addEventListener('dragstart', (e) => onDragStart(e, card, 'A', 'MANA'));
        chip.addEventListener('click', (e) => onCardClick(e, card, 'A', 'MANA'));
        chip.addEventListener('contextmenu', (e) => { e.preventDefault(); setZoom(card); });
        manaRow.appendChild(chip);
    }
    manaWrap.appendChild(manaRow);

    const dropRow = document.createElement('div');
    dropRow.className = 'd-flex gap-1 mt-1';
    const faceUpDrop = document.createElement('div');
    faceUpDrop.className = 'zone-drop-mini';
    faceUpDrop.textContent = 'マナ(表)へ';
    registerDropTarget(faceUpDrop, 'A', 'MANA', null, false);
    const faceDownDrop = document.createElement('div');
    faceDownDrop.className = 'zone-drop-mini';
    faceDownDrop.textContent = 'マナ(裏)へ';
    registerDropTarget(faceDownDrop, 'A', 'MANA', null, true);
    dropRow.appendChild(faceUpDrop);
    dropRow.appendChild(faceDownDrop);
    manaWrap.appendChild(dropRow);

    row.appendChild(manaWrap);
    el.appendChild(row);

    cardLocation.set(seat.leader ? seat.leader.instanceId : null, { seatId: seat.id, zone: 'LEADER' });
    for (const card of (seat.zones.MANA || [])) {
        cardLocation.set(card.instanceId, { seatId: seat.id, zone: 'MANA' });
    }
}

/** 手札 */
function renderHand(view) {
    const el = document.getElementById('hand-row');
    el.innerHTML = '';
    const label = document.createElement('div');
    label.className = 'small text-muted';
    label.textContent = '手札';
    el.appendChild(label);

    const row = document.createElement('div');
    row.className = 'hand-row';
    row.dataset.seat = 'A';
    row.dataset.zone = 'HAND';
    const cards = view.seatA.zones.HAND || [];
    const width = cards.length > 10 ? Math.max(45, Math.floor(900 / cards.length)) : 90;
    for (const card of cards) {
        row.appendChild(createHandCard(card, width));
        cardLocation.set(card.instanceId, { seatId: 'A', zone: 'HAND' });
    }
    registerDropTarget(row, 'A', 'HAND');
    el.appendChild(row);
}

function renderLog(entries) {
    const box = document.getElementById('log-box');
    box.innerHTML = '';
    for (const e of entries) {
        const line = document.createElement('div');
        line.textContent = `[${e.time}] ${e.text}`;
        box.appendChild(line);
    }
    box.scrollTop = box.scrollHeight;
}

// ---------------------------------------------------------------
// 5) カードタイル生成
// ---------------------------------------------------------------

/** 場・ウェポンのタイル(110×110、設計書 4-3) */
function createFieldTile(card, seatId, zone) {
    const tile = document.createElement('div');
    tile.className = 'manual-tile';
    tile.dataset.instanceId = card.instanceId;
    tile.draggable = true;

    if (!card.cardId) {
        tile.classList.add('manual-tile-unresolved');
        const name = document.createElement('div');
        name.className = 'manual-tile-name';
        name.textContent = card.name || '(不明)';
        tile.appendChild(name);
    } else {
        const bg = civColor(card.civilization);
        tile.style.background = bg;
        tile.style.color = textColorFor(bg);

        const name = document.createElement('div');
        name.className = 'manual-tile-name';
        name.textContent = card.name;
        tile.appendChild(name);

        const stats = document.createElement('div');
        stats.className = 'manual-tile-stats';
        stats.appendChild(statSpan(card.attack, card.printedAttack));
        if (zone !== 'WEAPON' && card.hp !== null && card.hp !== undefined) {
            stats.appendChild(document.createTextNode(' / '));
            stats.appendChild(statSpan(card.hp, card.printedHp));
        }
        stats.addEventListener('click', (e) => { e.stopPropagation(); openStatModal(card); });
        tile.appendChild(stats);

        if (zone === 'WEAPON') {
            const usedBadge = document.createElement('div');
            usedBadge.className = 'manual-tile-used' + (card.used ? '' : ' manual-tile-used-off');
            usedBadge.textContent = card.used ? '使用済' : '未使用';
            usedBadge.addEventListener('click', (e) => {
                e.stopPropagation();
                send('used', { cardIds: [card.instanceId] });
            });
            tile.appendChild(usedBadge);
        }
    }

    if (card.stackSize > 1) {
        const badge = document.createElement('div');
        badge.className = 'manual-tile-badge';
        badge.textContent = '+' + (card.stackSize - 1);
        badge.addEventListener('click', (e) => { e.stopPropagation(); openEvolutionBand(card, seatId); });
        tile.appendChild(badge);
    }

    const labelArea = document.createElement('div');
    labelArea.className = 'manual-tile-labels';
    for (const label of card.labels) {
        const chip = document.createElement('span');
        chip.className = 'manual-label-chip';
        chip.textContent = label;
        chip.addEventListener('click', (e) => {
            e.stopPropagation();
            send('label-remove', { cardId: card.instanceId, label });
        });
        labelArea.appendChild(chip);
    }
    const plus = document.createElement('span');
    plus.className = 'manual-label-plus';
    plus.textContent = '+';
    plus.addEventListener('click', (e) => { e.stopPropagation(); openLabelModal(card); });
    labelArea.appendChild(plus);
    tile.appendChild(labelArea);

    if (card.tapped) {
        tile.classList.add('manual-tile-tapped');
    }
    if (card.faceDown) {
        tile.classList.add('manual-tile-facedown');
    }
    if (selected.has(card.instanceId)) {
        tile.classList.add('manual-tile-selected');
    }

    tile.addEventListener('dragstart', (e) => onDragStart(e, card, seatId, zone));
    tile.addEventListener('click', (e) => onCardClick(e, card, seatId, zone));
    tile.addEventListener('contextmenu', (e) => { e.preventDefault(); setZoom(card); });
    registerDropTarget(tile, seatId, zone, null, null, card);

    return tile;
}

function statSpan(current, printed) {
    const span = document.createElement('span');
    span.textContent = current === null || current === undefined ? '-' : current;
    if (printed !== null && printed !== undefined && current !== null && current !== undefined
            && current !== printed) {
        const chip = document.createElement('span');
        chip.className = 'manual-stat-chip';
        chip.style.color = current > printed ? '#27500A' : '#791F1F';
        chip.style.borderColor = current > printed ? '#27500A' : '#791F1F';
        chip.textContent = (current > printed ? '+' : '') + (current - printed);
        const wrap = document.createElement('span');
        wrap.appendChild(span);
        wrap.appendChild(chip);
        return wrap;
    }
    return span;
}

function createLeaderTile(seat) {
    const tile = document.createElement('div');
    tile.className = 'leader-card manual-leader-tile';
    if (!seat.leader) {
        tile.textContent = '(未読込)';
        return tile;
    }
    const card = seat.leader;
    tile.dataset.instanceId = card.instanceId;
    const name = document.createElement('div');
    name.className = 'manual-tile-name';
    name.textContent = card.name || 'リーダー';
    tile.appendChild(name);

    const lp = document.createElement('div');
    lp.className = 'manual-tile-stats';
    lp.textContent = 'LP ' + seat.lp;
    lp.addEventListener('click', (e) => {
        e.stopPropagation();
        const value = prompt('LPを入力', seat.lp);
        if (value !== null && value.trim() !== '' && !isNaN(Number(value))) {
            send('lp', { seat: seat.id, value: Number(value) });
        }
    });
    tile.appendChild(lp);

    if (card.tapped) {
        tile.classList.add('manual-tile-tapped');
    }
    tile.addEventListener('click', (e) => {
        if (e.target === lp) return;
        if (e.shiftKey || e.ctrlKey || e.metaKey) return;
        send('tap', { cardIds: [card.instanceId] });
    });
    tile.addEventListener('contextmenu', (e) => { e.preventDefault(); setZoom(card); });
    return tile;
}

/** 手札のカード。画像を使う(設計書 4-1) */
function createHandCard(card, width) {
    const wrap = document.createElement('div');
    wrap.className = 'manual-hand-card';
    wrap.style.width = width + 'px';
    wrap.dataset.instanceId = card.instanceId;
    wrap.draggable = true;

    if (card.imageId && !card.faceDown) {
        const img = document.createElement('img');
        img.src = `/cards/${card.imageId}.png`;
        img.loading = 'lazy';
        img.style.width = '100%';
        wrap.appendChild(img);
    } else {
        wrap.classList.add('manual-hand-card-blank');
        wrap.textContent = card.faceDown ? '(裏向き)' : (card.name || '(不明)');
    }
    if (selected.has(card.instanceId)) {
        wrap.classList.add('manual-tile-selected');
    }

    wrap.addEventListener('dragstart', (e) => onDragStart(e, card, 'A', 'HAND'));
    wrap.addEventListener('click', (e) => onCardClick(e, card, 'A', 'HAND'));
    wrap.addEventListener('contextmenu', (e) => { e.preventDefault(); setZoom(card); });
    return wrap;
}

// ---------------------------------------------------------------
// 6) クリック規約(設計書 4-4)
// ---------------------------------------------------------------

function onCardClick(e, card, seatId, zone) {
    if (e.shiftKey) {
        send('flip', { cardIds: [card.instanceId] });
        return;
    }
    if (e.ctrlKey || e.metaKey) {
        toggleSelect(card.instanceId);
        return;
    }
    if (zone === 'HAND') {
        setZoom(card);
        return;
    }
    // 場・マナの空白部分(タイル背景)のクリック → タップ⇔アンタップ
    send('tap', { cardIds: [card.instanceId] });
}

function toggleSelect(instanceId) {
    if (selected.has(instanceId)) {
        selected.delete(instanceId);
    } else {
        selected.add(instanceId);
    }
    if (latestView) {
        renderAll(latestView);
    }
}

// ---------------------------------------------------------------
// 7) 拡大画像
// ---------------------------------------------------------------

function setZoom(card) {
    pinnedZoom = card;
    if (latestView) {
        renderZoom(card, latestView.backImageId);
    }
}

function renderZoom(card, backImageId) {
    const panel = document.getElementById('zoom-panel');
    panel.innerHTML = '';
    const showBack = card.faceDown && card.imageId;
    const src = showBack ? backImageId : card.imageId;
    if (src) {
        const img = document.createElement('img');
        img.src = `/cards/${src}.png`;
        img.style.maxWidth = '100%';
        img.style.maxHeight = '100%';
        panel.appendChild(img);
    } else {
        const span = document.createElement('span');
        span.className = 'text-muted small';
        span.textContent = card.name || '(画像なし)';
        panel.appendChild(span);
    }
}

// ---------------------------------------------------------------
// 8) ドラッグ&ドロップ(設計書 4-5)
// ---------------------------------------------------------------

function onDragStart(e, card, seatId, zone) {
    let ids;
    if (selected.has(card.instanceId) && selected.size > 1) {
        ids = [...selected];
    } else {
        ids = [card.instanceId];
    }
    e.dataTransfer.setData('text/plain', JSON.stringify({ cardIds: ids, seatId, zone }));
    e.dataTransfer.effectAllowed = 'move';
}

/**
 * ドロップ先を1箇所登録する。
 * targetCard が渡された場合、そのタイルの上に落とすことになる
 * (占有中の FIELD なら進化、占有中の WEAPON なら受け付けない)。
 */
function registerDropTarget(el, seatId, zoneName, toIndex, faceDown, targetCard) {
    el.addEventListener('dragover', (e) => {
        e.preventDefault();
        if (zoneName === 'WEAPON' && targetCard) {
            el.classList.add('manual-drop-reject');
        } else {
            el.classList.add('manual-drop-hover');
        }
    });
    el.addEventListener('dragleave', () => {
        el.classList.remove('manual-drop-hover', 'manual-drop-reject');
    });
    el.addEventListener('drop', (e) => {
        e.preventDefault();
        el.classList.remove('manual-drop-hover');
        if (zoneName === 'WEAPON' && targetCard) {
            flashReject(el);
            return;
        }
        let data;
        try {
            data = JSON.parse(e.dataTransfer.getData('text/plain'));
        } catch (err) {
            return;
        }
        if (!data || !data.cardIds || data.cardIds.length === 0) {
            return;
        }

        if (zoneName === 'FIELD' && targetCard) {
            // 既存タイルの上 → 進化として重ねる(設計書 4-5-1)
            const evolutionCardId = data.cardIds[0];
            let materialCardIds;
            const sameFieldSelection = [...selected].filter((id) => {
                const loc = cardLocation.get(id);
                return loc && loc.seatId === seatId && loc.zone === 'FIELD' && id !== evolutionCardId;
            });
            if (sameFieldSelection.length > 0) {
                materialCardIds = sameFieldSelection;
            } else {
                materialCardIds = [targetCard.instanceId];
            }
            send('evolve', { seat: seatId, evolutionCardId, materialCardIds });
            selected.clear();
            return;
        }

        send('move', {
            cardIds: data.cardIds,
            toSeat: seatId,
            toZone: zoneName,
            toIndex: toIndex === undefined ? null : toIndex,
            faceDown: faceDown === undefined ? null : faceDown,
        });
        selected.clear();
    });
}

function flashReject(el) {
    el.classList.add('manual-drop-reject');
    setTimeout(() => el.classList.remove('manual-drop-reject'), 400);
}

// ---------------------------------------------------------------
// 9) 数値編集モーダル(5-3の3・4)
// ---------------------------------------------------------------

function openStatModal(card) {
    const modal = document.getElementById('stat-modal');
    const fields = document.getElementById('stat-modal-fields');
    document.getElementById('stat-modal-title').textContent = card.name + ' の数値';
    fields.innerHTML = '';

    fields.appendChild(statInput('ATK', card.attack, (value) => {
        send('stat', { cardId: card.instanceId, attack: value });
    }));
    if (card.hp !== null && card.hp !== undefined) {
        fields.appendChild(statInput('HP', card.hp, (value) => {
            send('stat', { cardId: card.instanceId, hp: value });
        }));
    }

    document.getElementById('stat-modal-reset').onclick = () => {
        send('stat-reset', { cardId: card.instanceId });
        modal.classList.add('d-none');
    };
    document.getElementById('stat-modal-close').onclick = () => modal.classList.add('d-none');
    modal.classList.remove('d-none');
}

function statInput(labelText, value, onCommit) {
    const wrap = document.createElement('div');
    const label = document.createElement('label');
    label.className = 'small text-muted d-block';
    label.textContent = labelText;
    const input = document.createElement('input');
    input.type = 'number';
    input.className = 'form-control form-control-sm';
    input.style.width = '80px';
    input.value = value === null || value === undefined ? '' : value;
    input.addEventListener('change', () => {
        if (input.value !== '' && !isNaN(Number(input.value))) {
            onCommit(Number(input.value));
        }
    });
    wrap.appendChild(label);
    wrap.appendChild(input);
    return wrap;
}

// ---------------------------------------------------------------
// 10) 札モーダル(設計書 5-3の5)
// ---------------------------------------------------------------

function openLabelModal(card) {
    const modal = document.getElementById('label-modal');
    const current = document.getElementById('label-modal-current');
    const defaultsBox = document.getElementById('label-modal-defaults');
    current.innerHTML = '';
    defaultsBox.innerHTML = '';

    for (const label of card.labels) {
        const chip = document.createElement('span');
        chip.className = 'manual-label-chip';
        chip.textContent = label + ' ×';
        chip.addEventListener('click', () => send('label-remove', { cardId: card.instanceId, label }));
        current.appendChild(chip);
    }

    for (const label of DEFAULT_LABELS) {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'btn btn-sm btn-outline-light';
        btn.textContent = label;
        btn.addEventListener('click', () => send('label-add', { cardId: card.instanceId, label }));
        defaultsBox.appendChild(btn);
    }

    const form = document.getElementById('label-modal-form');
    form.onsubmit = (e) => {
        e.preventDefault();
        const input = document.getElementById('label-modal-input');
        if (input.value.trim() !== '') {
            send('label-add', { cardId: card.instanceId, label: input.value.trim() });
            input.value = '';
        }
    };
    document.getElementById('label-modal-close').onclick = () => modal.classList.add('d-none');
    modal.classList.remove('d-none');
}

// ---------------------------------------------------------------
// 11) ヘッダ操作(ターン・フェイズ・Undo/Redo・デッキ読み込み・宣言・メモ)
// ---------------------------------------------------------------

document.getElementById('turn-minus').addEventListener('click', () => send('turn', { delta: -1 }));
document.getElementById('turn-plus').addEventListener('click', () => send('turn', { delta: 1 }));
document.getElementById('phase-back').addEventListener('click', () => send('phase', { step: -1 }));
document.getElementById('phase-fwd').addEventListener('click', () => send('phase', { step: 1 }));
document.getElementById('btn-undo').addEventListener('click', () => send('undo', {}));
document.getElementById('btn-redo').addEventListener('click', () => send('redo', {}));

document.getElementById('note-form').addEventListener('submit', (e) => {
    e.preventDefault();
    const input = document.getElementById('note-input');
    if (input.value.trim() !== '') {
        send('note', { text: input.value.trim() });
        input.value = '';
    }
});

let declareSeat = 'A';
document.getElementById('declare-buttons').addEventListener('click', (e) => {
    const btn = e.target.closest('button[data-declaration]');
    if (!btn) return;
    send('declare', { seat: declareSeat, declaration: btn.dataset.declaration, note: null });
});

for (const seatSel of ['deck-file-a', 'deck-file-b']) {
    document.getElementById(seatSel).addEventListener('change', async (e) => {
        const file = e.target.files[0];
        if (!file) return;
        const seat = seatSel.endsWith('a') ? 'A' : 'B';
        const status = document.getElementById('deck-import-status');
        status.classList.remove('text-danger');
        status.textContent = `${seat}席へ読み込み中...`;
        try {
            const buf = await file.arrayBuffer();
            const res = await fetch(`/manual/api/rooms/${ROOM_ID}/deck?seat=${seat}&occupantId=${OCCUPANT_ID}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/octet-stream' },
                body: buf,
            });
            if (!res.ok) {
                const err = await res.json();
                showTransientError(err.message || 'デッキの読み込みに失敗しました');
                return;
            }
            const body = await res.json();
            status.textContent = `${seat}席: ${body.deckName}(${body.mainCount}枚 + 禁忌${body.tabooCount}枚)`;
            if (body.warnings && body.warnings.length > 0) {
                status.textContent += ` / 警告${body.warnings.length}件`;
            }
        } catch (err) {
            showTransientError('デッキの読み込みに失敗しました: ' + err.message);
        }
    });
}

// ---------------------------------------------------------------
// 12) 帯・全面表示(Batch 18c。設計書 4-6)
// ---------------------------------------------------------------
//
// ★このバッチはサーバに新しい判断ロジックを足していない。素材を instanceId で
// 直接動かす経路(move操作)と任意位置への挿入(toIndex)は17b/18aの時点で既に
// 用意されており(ManualBoardIndex.detach / ManualOperationService.move)、
// ここで作るのはその経路を画面から呼べるようにするUIだけである。
//
// activeOverlay = { kind: 'zone' | 'evolution' | 'deck', seatId, zoneName?,
//                    evolutionCardId?, searchQuery }
// null なら何も開いていない。renderAll の最後で毎回 refreshOverlay() を呼び、
// 開いていれば最新の view で描き直す(サーバからの再配信のたびに帯・全面表示も
// 追従する)。

let activeOverlay = null;

function refreshOverlay() {
    if (!activeOverlay) return;
    if (activeOverlay.kind === 'zone') {
        renderZoneBand();
    } else if (activeOverlay.kind === 'evolution') {
        renderEvolutionBand();
    } else if (activeOverlay.kind === 'deck') {
        renderDeckFullscreen();
    }
}

function closeOverlay() {
    activeOverlay = null;
    const root = document.getElementById('manual-overlay-root');
    if (root) root.remove();
}

function overlayRoot() {
    let root = document.getElementById('manual-overlay-root');
    if (!root) {
        root = document.createElement('div');
        root.id = 'manual-overlay-root';
        document.body.appendChild(root);
    }
    return root;
}

function findCardByInstanceId(list, instanceId) {
    for (const card of (list || [])) {
        if (card.instanceId === instanceId) return card;
    }
    return null;
}

// ---- 帯: 墓地・消滅・禁忌・一時公開(自席のみ。相手席は飾りのため対象外。マスター確認済み) ----

function openZoneBand(seatId, zoneName) {
    activeOverlay = { kind: 'zone', seatId, zoneName, searchQuery: '' };
    renderZoneBand();
}

function renderZoneBand() {
    if (!latestView) return;
    const seatView = activeOverlay.seatId === 'A' ? latestView.seatA : latestView.seatB;
    const items = seatView.zones[activeOverlay.zoneName] || [];
    // ★検索対象は山札・墓地・消滅・禁忌のみ(設計書4-6)。一時公開は対象外(マスター確認済み)。
    const showSearch = ['TRASH', 'LOST', 'TABOO'].includes(activeOverlay.zoneName);
    renderBandDom({
        title: `${ZONE_LABELS[activeOverlay.zoneName]}(${items.length}枚)`,
        seatId: activeOverlay.seatId,
        zoneName: activeOverlay.zoneName,
        items,
        showSearch,
    });
}

// ---- 帯: 進化スタック(+nバッジ) ----

function openEvolutionBand(card, seatId) {
    activeOverlay = { kind: 'evolution', seatId, evolutionCardId: card.instanceId };
    renderEvolutionBand();
}

function renderEvolutionBand() {
    if (!latestView) return;
    const seatView = activeOverlay.seatId === 'A' ? latestView.seatA : latestView.seatB;
    const top = findCardByInstanceId(seatView.zones['FIELD'], activeOverlay.evolutionCardId)
        || findCardByInstanceId(seatView.zones['WEAPON'], activeOverlay.evolutionCardId);
    if (!top || !top.materials || top.materials.length === 0) {
        // ★束が解消された(素材を全部抜き出した等)。開いたままにする意味が無いので自動で閉じる。
        closeOverlay();
        return;
    }
    renderBandDom({
        title: `${top.name || '(不明)'} の進化スタック(素材 ${top.materials.length}枚)`,
        seatId: activeOverlay.seatId,
        zoneName: 'FIELD',
        items: top.materials,
        showSearch: false, // 検索対象は設計書4-6の4ゾーンのみ。進化スタックは対象外
    });
}

/** 帯の共通DOM。検索欄は入力のたびに一覧行だけを再描画し、入力欄自体は作り直さない(フォーカス維持)。 */
function renderBandDom({ title, seatId, zoneName, items, showSearch }) {
    const root = overlayRoot();
    root.innerHTML = '';

    const backdrop = document.createElement('div');
    backdrop.className = 'manual-band-backdrop';
    backdrop.addEventListener('click', () => closeOverlay());
    root.appendChild(backdrop);

    const band = document.createElement('div');
    band.className = 'manual-band';
    band.addEventListener('click', (e) => e.stopPropagation());

    const header = document.createElement('div');
    header.className = 'manual-band-header';
    const titleSpan = document.createElement('span');
    titleSpan.textContent = title;
    header.appendChild(titleSpan);

    let search = null;
    if (showSearch) {
        search = document.createElement('input');
        search.type = 'text';
        search.className = 'form-control form-control-sm manual-band-search';
        search.placeholder = 'カード名で検索';
        search.value = activeOverlay.searchQuery || '';
        header.appendChild(search);
    }

    const closeBtn = document.createElement('button');
    closeBtn.type = 'button';
    closeBtn.className = 'btn btn-sm btn-outline-light';
    closeBtn.textContent = '閉じる';
    closeBtn.addEventListener('click', () => closeOverlay());
    header.appendChild(closeBtn);

    band.appendChild(header);

    const row = document.createElement('div');
    row.className = 'manual-band-row';
    band.appendChild(row);
    root.appendChild(band);

    function renderRow() {
        row.innerHTML = '';
        const query = (activeOverlay.searchQuery || '').trim().toLowerCase();
        const filtered = query ? items.filter((c) => (c.name || '').toLowerCase().includes(query)) : items;
        if (filtered.length === 0) {
            const empty = document.createElement('div');
            empty.className = 'text-muted small p-2';
            empty.textContent = items.length === 0 ? '(なし)' : '(該当なし)';
            row.appendChild(empty);
            return;
        }
        for (const card of filtered) {
            row.appendChild(createBandItem(card, seatId, zoneName));
        }
    }
    renderRow();

    if (search) {
        search.addEventListener('input', () => {
            activeOverlay.searchQuery = search.value;
            renderRow();
        });
    }
}

/**
 * 帯の中の1枚。設計書4-4「左クリック 開いたゾーンの中身 → 拡大」のとおり、
 * 手札カードと同じ挙動(拡大 / Shift+表裏 / Ctrl,Cmd+複数選択)にする。
 * ドラッグは onDragStart 経由でそのまま盤面へ渡す(進化スタックの素材でも、
 * instanceId 指定であれば ManualBoardIndex が透過的に探してくれるため、
 * クライアント側で特別扱いは要らない)。
 */
function createBandItem(card, seatId, zoneName) {
    const wrap = document.createElement('div');
    wrap.className = 'manual-band-card';
    wrap.dataset.instanceId = card.instanceId;
    wrap.draggable = true;

    if (card.imageId && !card.faceDown) {
        const img = document.createElement('img');
        img.src = `/cards/${card.imageId}.png`;
        img.loading = 'lazy';
        wrap.appendChild(img);
    } else {
        wrap.classList.add('manual-band-card-blank');
        wrap.textContent = card.faceDown ? '(裏向き)' : (card.name || '(不明)');
    }
    if (selected.has(card.instanceId)) {
        wrap.classList.add('manual-tile-selected');
    }

    wrap.addEventListener('dragstart', (e) => onDragStart(e, card, seatId, zoneName));
    wrap.addEventListener('click', (e) => {
        if (e.shiftKey) {
            send('flip', { cardIds: [card.instanceId] });
            return;
        }
        if (e.ctrlKey || e.metaKey) {
            toggleSelect(card.instanceId);
            return;
        }
        setZoom(card);
    });
    wrap.addEventListener('contextmenu', (e) => { e.preventDefault(); setZoom(card); });
    return wrap;
}

// ---- 全面表示: 山札 ----

function openDeckFullscreen(seatId) {
    activeOverlay = { kind: 'deck', seatId, searchQuery: '' };
    renderDeckFullscreen();
}

/**
 * 山札の全面表示(設計書4-6)。並びは常に山札の順序そのまま(index 0 = 一番上 =
 * 次にドローされる1枚。ManualGameService.drawCards が deck.remove(0) で引くのと揃える)。
 * 表示用の並べ替えは提供しない。ドラッグでの並べ替えのみ、検索中は無効にする
 * (フィルタで隠れた行を挟んで index がずれるのを避けるため。マスター確認済み)。
 */
function renderDeckFullscreen() {
    if (!latestView) return;
    const seatView = activeOverlay.seatId === 'A' ? latestView.seatA : latestView.seatB;
    const deck = seatView.zones['DECK'] || [];

    const root = overlayRoot();
    root.innerHTML = '';

    const screen = document.createElement('div');
    screen.className = 'manual-fullscreen';

    const header = document.createElement('div');
    header.className = 'manual-fullscreen-header';
    const title = document.createElement('span');
    title.textContent = `山札(${deck.length}枚) — 席${activeOverlay.seatId}`;
    header.appendChild(title);

    const search = document.createElement('input');
    search.type = 'text';
    search.className = 'form-control form-control-sm manual-band-search';
    search.placeholder = 'カード名で検索';
    search.value = activeOverlay.searchQuery || '';
    header.appendChild(search);

    const shuffleBtn = document.createElement('button');
    shuffleBtn.type = 'button';
    shuffleBtn.className = 'btn btn-sm btn-outline-secondary';
    shuffleBtn.textContent = 'シャッフル';
    shuffleBtn.addEventListener('click', () => send('shuffle', { seat: activeOverlay.seatId }));
    header.appendChild(shuffleBtn);

    const closeBtn = document.createElement('button');
    closeBtn.type = 'button';
    closeBtn.className = 'btn btn-sm btn-outline-light';
    closeBtn.textContent = '閉じる';
    closeBtn.addEventListener('click', () => closeOverlay());
    header.appendChild(closeBtn);

    screen.appendChild(header);

    const list = document.createElement('div');
    list.className = 'manual-deck-list';
    screen.appendChild(list);
    root.appendChild(screen);

    function renderList() {
        list.innerHTML = '';
        const query = (activeOverlay.searchQuery || '').trim().toLowerCase();
        const filtering = query.length > 0;
        if (filtering) {
            const note = document.createElement('div');
            note.className = 'text-muted small mb-2';
            note.textContent = '検索中は並べ替えを無効にします(手札へ/場へ/一番上へ/一番下へは使えます)';
            list.appendChild(note);
        }
        let shown = 0;
        deck.forEach((card, index) => {
            if (filtering && !(card.name || '').toLowerCase().includes(query)) return;
            list.appendChild(createDeckRow(card, index, activeOverlay.seatId, filtering));
            shown++;
        });
        if (shown === 0) {
            const empty = document.createElement('div');
            empty.className = 'text-muted small p-2';
            empty.textContent = deck.length === 0 ? '(山札が空です)' : '(該当なし)';
            list.appendChild(empty);
        }
    }
    renderList();

    search.addEventListener('input', () => {
        activeOverlay.searchQuery = search.value;
        renderList();
    });
}

/**
 * 全面表示1行。★並べ替えのドロップ判定:
 * カード1枚を index i から drop先の行(現在 index j)の上半分/下半分どちらに
 * 落としたかで挿入位置(j または j+1)を決め、i より後ろならその挿入位置を
 * 1つ詰める(move操作は「外してから数える」ため、外した分だけ後続の添字が
 * 1つずつ前へ詰まることを送信側で先取りして補正する)。
 */
function createDeckRow(card, index, seatId, dragDisabled) {
    const row = document.createElement('div');
    row.className = 'manual-deck-row';
    row.dataset.index = index;
    row.draggable = !dragDisabled;

    if (card.imageId && !card.faceDown) {
        const img = document.createElement('img');
        img.src = `/cards/${card.imageId}.png`;
        img.loading = 'lazy';
        row.appendChild(img);
    } else {
        const blank = document.createElement('div');
        blank.className = 'manual-deck-row-blank';
        blank.textContent = card.faceDown ? '(裏向き)' : (card.name || '(不明)');
        row.appendChild(blank);
    }

    const name = document.createElement('div');
    name.className = 'manual-deck-row-name';
    name.textContent = card.name || '(不明)';
    row.appendChild(name);

    const btns = document.createElement('div');
    btns.className = 'manual-deck-row-buttons';
    btns.appendChild(deckRowButton('一番上へ', () => sendDeckMove(card.instanceId, seatId, 'DECK', 0)));
    btns.appendChild(deckRowButton('一番下へ', () => sendDeckMove(card.instanceId, seatId, 'DECK', 999999)));
    btns.appendChild(deckRowButton('手札へ', () => sendDeckMove(card.instanceId, seatId, 'HAND', null)));
    btns.appendChild(deckRowButton('場へ', () => sendDeckMove(card.instanceId, seatId, 'FIELD', null)));
    row.appendChild(btns);

    if (!dragDisabled) {
        row.addEventListener('dragstart', (e) => {
            e.dataTransfer.setData('text/plain',
                JSON.stringify({ cardIds: [card.instanceId], seatId, zone: 'DECK', sourceIndex: index }));
            e.dataTransfer.effectAllowed = 'move';
        });
        row.addEventListener('dragover', (e) => {
            e.preventDefault();
            row.classList.add('manual-drop-hover');
        });
        row.addEventListener('dragleave', () => row.classList.remove('manual-drop-hover'));
        row.addEventListener('drop', (e) => {
            e.preventDefault();
            row.classList.remove('manual-drop-hover');
            let data;
            try {
                data = JSON.parse(e.dataTransfer.getData('text/plain'));
            } catch (err) {
                return;
            }
            if (!data || data.zone !== 'DECK' || data.seatId !== seatId || data.sourceIndex === undefined) {
                return;
            }
            const rect = row.getBoundingClientRect();
            const dropBeforeThisRow = (e.clientY - rect.top) < rect.height / 2;
            let target = dropBeforeThisRow ? index : index + 1;
            if (target > data.sourceIndex) {
                target -= 1;
            }
            send('move', { cardIds: data.cardIds, toSeat: seatId, toZone: 'DECK', toIndex: target, faceDown: null });
        });
    }

    return row;
}

function deckRowButton(label, onClick) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'btn btn-sm btn-outline-light py-0';
    btn.textContent = label;
    btn.addEventListener('click', onClick);
    return btn;
}

function sendDeckMove(cardId, seatId, toZone, toIndex) {
    send('move', { cardIds: [cardId], toSeat: seatId, toZone, toIndex, faceDown: null });
}
