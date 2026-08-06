/**
 * 手動モード盤面のクライアント処理(Batch 18b〜)。
 *
 * 構造は通常モードの battle.js と同じ3層。
 *   0) 在室: occupantId を localStorage から復元するか、無ければ入室APIで新規取得する
 *   1) 接続: STOMPで /ws に接続し、自分専用の宛先を購読する
 *   2) 送信: 操作 → /app/manual/{roomId}/{action} へメッセージ送信
 *   3) 受信: サーバから届いたビュー(ManualGameView)で画面を全描画し直す
 *
 * ★Batch 19a で「0) 在室」を足した。それまでは occupantId をサーバがページ生成時に
 * 発行してテンプレートへ埋め込んでいたが(暫定入口 ManualBattleController の方式)、
 * これだと「同じ人がタブを開き直す」だけで毎回新しい在室者になってしまい、
 * 切断復帰(設計書 6-3)が成立しない。19a からは occupantId を localStorage に保存し、
 * 次回以降はそれを使って同じ在室者として戻る。
 *
 * クライアントが自分で持つ状態:
 *   - OCCUPANT_ID: 在室が確定してから埋まる(0章)。それまで STOMP は接続しない
 *   - latestView: サーバから届いた最新のビュー(ManualGameView)。再描画の元になる
 *   - selected: 進化の素材や複数移動のために Ctrl/Cmd+クリックで選んだ instanceId の集合
 *   - cardLocation: instanceId -> {seatId, zone} の索引。直近の描画から作り直す。
 *     ドロップ判定(進化か移動か)に使う。
 *   - pinnedZoom: 右下に固定表示中のカード
 *   - activeOverlay: 帯・全面表示のうち現在開いているものの種別と対象(12章)。
 *     null なら何も開いていない。renderAll の最後で毎回このオーバーレイを描き直す。
 *   - lpModalSeatId: LPモーダル(20a 2-4)が開いている対象席('A'/'B')。null なら未オープン。
 *     専用の再描画経路は作らず、renderAll の末尾でこの値を見て数値だけ差し替える。
 *   - weaponModalCardId: ウェポン操作モーダル(20b 2-2)が開いている対象の instanceId。
 *     LPモーダルと同じ考え方で、再配信のたびに中身を組み直す。
 *
 * ★Batch 20b でレイアウトを全面的に組み替えた(20b 設計書1章)。
 *   - リーダー行(パイル込み・185px)を廃止し、パイル群は右列へ移した
 *   - リーダーとウェポンを1つのタイルに合体した(リーダー自身が WEAPON のドロップ先)
 *   - ターン/フェイズUIを表示ごと削除した(サーバ側の turn/phase 操作は残っている)
 *   - 両ミニオン行の間にセンターライン(共有ゾーン PLAY / REVEAL)を新設した
 */

let latestView = null;

// ---------------------------------------------------------------
// カードフェイス描画(Batch 25)
// 画像ファイルの代わりに、ビューの項目(名前・コスト・文明・種別・印刷値)から
// カードの見た目を組み立てる。デッキメーカーと同じ描画方針である。
// 効果テキストだけはビューに載らないため、カード定義(/manual/api/card-library)を
// 起動時に1回取得して cardId で引く。
// ★取得前・取得失敗時でも壊れない: テキスト欄が空のフェイスで描画し続け、
//   取得できた時点で最新ビューを描き直す。
// ---------------------------------------------------------------
let cardTextById = null;    // cardId -> text(取得前は null)
let cardTextByImage = null; // imageId -> text(グレー個体やID欠落への保険)

function applyCardLibrary(lib) {
    if (!lib || !Array.isArray(lib.cards)) {
        return;
    }
    cardTextById = new Map();
    cardTextByImage = new Map();
    lib.cards.forEach((c) => {
        if (c.id) cardTextById.set(c.id, c.text || '');
        if (c.imageId) cardTextByImage.set(c.imageId, c.text || '');
    });
}

function loadCardLibrary() {
    fetch('/manual/api/card-library')
        .then((r) => { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
        .then((lib) => {
            applyCardLibrary(lib);
            if (latestView) renderAll(latestView);
        })
        .catch(() => { /* テキスト欄なしで動き続ける */ });
}

const FACE_TYPE_LABELS = {
    LEADER: 'リーダー', MINION: 'ミニオン', EVOLUTION: '進化ミニオン',
    SPELL: 'スペル', WEAPON: 'ウェポン',
};
const FACE_CIV_COLORS = {
    WATER: '#2f6fb5', FIRE: '#b53a3a', WIND: '#3a8f57',
    LIGHT: '#b8952f', DARK: '#6b42a8', EARTH: '#c05a8e', NONE: '#6f6f7c',
};

function cardFaceText(card) {
    if (cardTextById && card.cardId && cardTextById.has(card.cardId)) {
        return cardTextById.get(card.cardId);
    }
    if (cardTextByImage && card.imageId && cardTextByImage.has(card.imageId)) {
        return cardTextByImage.get(card.imageId);
    }
    return '';
}

let faceBackImageId = null;   // renderAll がビューから拾う(裏面画像のID)

/**
 * 裏面。★裏面だけはカード画像を使う(25b・マスター指示)。実物の裏面デザインには
 * テキスト情報が無く、フェイス描画に置き換える利益が無いためである。
 * 個々のカードの imageId は持たない(非公開情報を運ばない)。
 * 画像IDが未取得の間はCSS描画の紋章にフォールバックする。
 */
function cardBackFace() {
    const el = document.createElement('div');
    el.className = 'mcard mcard-backface';
    if (faceBackImageId) {
        const img = document.createElement('img');
        img.src = `/cards/${faceBackImageId}.png`;
        img.loading = 'lazy';
        el.appendChild(img);
    } else {
        const mark = document.createElement('div');
        mark.className = 'mcard-back-mark';
        mark.textContent = 'TABOO';
        el.appendChild(mark);
    }
    return el;
}

/**
 * カードの表面。variant は大きさと情報量を決める。
 *   'large' = 拡大(効果テキストあり) / 'full' = 手札・マリガン(テキストあり)
 *   'mini'  = 帯・パイル・ウェポン枠(テキストなし) / 'micro' = 相手上段・山札行
 * ★数値は<b>印刷値</b>を出す。現在値は盤面タイルの数値チップの仕事であり、
 *   フェイスは「カードに印刷されているもの」を再現する(画像時代と同じ意味論)。
 */
function cardFace(card, variant) {
    const el = document.createElement('div');
    el.className = 'mcard mcard-' + variant;
    if (card.imageId) {
        // 検証がカードの同一性を確かめるためのキー(verify/verify.js の zoomedImage)
        el.dataset.imageId = card.imageId;
    }
    el.style.setProperty('--mc', FACE_CIV_COLORS[card.civilization] || '#5a5468');

    const inner = document.createElement('div');
    inner.className = 'mcard-inner';

    const head = document.createElement('div');
    head.className = 'mcard-head';
    if (card.type !== 'LEADER' && card.cost !== null && card.cost !== undefined) {
        const cost = document.createElement('span');
        cost.className = 'mcard-cost';
        cost.textContent = card.cost;
        head.appendChild(cost);
    }
    const name = document.createElement('span');
    name.className = 'mcard-name';
    name.textContent = card.name || '(不明)';
    head.appendChild(name);
    inner.appendChild(head);

    if (variant === 'large' || variant === 'full') {
        const type = document.createElement('div');
        type.className = 'mcard-type';
        type.textContent = FACE_TYPE_LABELS[card.type] || '';
        inner.appendChild(type);
        const text = document.createElement('div');
        text.className = 'mcard-text';
        text.textContent = cardFaceText(card);
        inner.appendChild(text);
    } else {
        const spacer = document.createElement('div');
        spacer.className = 'mcard-spacer';
        inner.appendChild(spacer);
    }

    if (card.type === 'LEADER') {
        const foot = document.createElement('div');
        foot.className = 'mcard-foot mcard-foot-leader';
        foot.textContent = 'LEADER';
        inner.appendChild(foot);
    } else if (card.printedAttack !== null && card.printedAttack !== undefined
            || card.printedHp !== null && card.printedHp !== undefined) {
        const foot = document.createElement('div');
        foot.className = 'mcard-foot';
        if (card.printedAttack !== null && card.printedAttack !== undefined) {
            const atk = document.createElement('span');
            atk.className = 'mcard-atk';
            atk.textContent = '⚔' + card.printedAttack;
            foot.appendChild(atk);
        }
        if (card.printedHp !== null && card.printedHp !== undefined) {
            const hp = document.createElement('span');
            hp.className = 'mcard-hp';
            hp.textContent = '♥' + card.printedHp;
            foot.appendChild(hp);
        }
        inner.appendChild(foot);
    }

    el.appendChild(inner);
    return el;
}
let selected = new Set();
let cardLocation = new Map();
let pinnedZoom = null;
let OCCUPANT_ID = null;
let lpModalSeatId = null;
let weaponModalCardId = null;
// ★21c 3-2: 上下反転(観戦者のみ)。クライアント描画だけで完結し、サーバへは送らない
let boardFlipped = false;
// ★21c 7-1: ゾーン → 画面上のアンカー要素の対応表。矢印はここだけを見て描く
let zoneAnchors = new Map();

// ---------------------------------------------------------------
// 0) 在室(設計書 6-3)
// ---------------------------------------------------------------

const OCCUPANT_STORAGE_KEY = `qte-manual-occupant-${ROOM_ID}`;

function loadSavedOccupant() {
    try {
        const raw = localStorage.getItem(OCCUPANT_STORAGE_KEY);
        if (!raw) return null;
        const parsed = JSON.parse(raw);
        return parsed && parsed.occupantId ? parsed : null;
    } catch (e) {
        return null;
    }
}

function saveOccupant(occupantId, displayName) {
    localStorage.setItem(OCCUPANT_STORAGE_KEY, JSON.stringify({ occupantId, displayName }));
}

function forgetOccupant() {
    localStorage.removeItem(OCCUPANT_STORAGE_KEY);
}

// ---------------------------------------------------------------
// 0-2) 席選択画面(★Batch 21b。設計書 2-1 / 2-2)
// ---------------------------------------------------------------
//
// ★独立ページではなく盤面ページ内のゲートにしてある。
//   /manual/battle/{roomId} を開いた時点で必ずここを通るため、「盤面の前に必ず通る」を
//   ロビー側の分岐ではなく構造で保証できる。部屋コードの直リンクで来た人にも同じく効く。
//
// 同じ画面を2つの場面で使う。
//   gateMode = 'join' — 入室前。部屋情報は GET /manual/api/rooms/{roomId} から取る。
//                        決着したら resolveOccupant の Promise を occupantId で解決する。
//   gateMode = 'seat' — 入室後の昇格(観戦者 → 席)。部屋情報は latestView から取る。
//                        決着は WebSocket の seat メッセージであり、Promise は絡まない。
//
// ★入室前に部屋情報を引く API が要る理由:
//   一覧 API は鍵つき部屋の roomId を null で返す(1-3・F1)。そのため一覧から
//   自分の部屋を特定できない経路(鍵つき・直リンク)が必ず存在する。21b で
//   GET /manual/api/rooms/{roomId} を1つ足してある。

const ROOM_TYPE_LABELS = { OPEN: '全公開', VERSUS: '対戦' };
const SPECTATOR_VIEW_LABELS = { ALL: '全見え', PUBLIC_ONLY: '公開のみ' };

let gateMode = null;
let gateResolve = null;
let gateRoomType = 'OPEN';

function gateEl(id) {
    return document.getElementById(id);
}

function showGateError(message) {
    const box = gateEl('seat-gate-error');
    box.textContent = message;
    box.classList.remove('d-none');
}

function clearGateError() {
    gateEl('seat-gate-error').classList.add('d-none');
}

function setGateBusy(busy) {
    for (const button of gateEl('seat-gate-buttons').querySelectorAll('button')) {
        button.disabled = busy || button.dataset.occupied === 'true';
    }
}

function closeGate() {
    gateEl('seat-gate').classList.add('d-none');
    gateMode = null;
}

/**
 * 席ボタンの状態を1箇所で決める(2-1)。
 *
 * ★埋まっている席のボタンは無効化し、在席者名を出す。
 * 「押せるが失敗する」より「押せないと分かる」ほうが速い。
 * 無効の理由は dataset.occupied に残す。setGateBusy が通信中の一時的な無効化と
 * 取り違えて、埋まっている席まで有効に戻してしまわないようにするためである。
 *
 * @param names 席ごとの在席者名(空席は null)
 * @param notes 名前に添える補足(「切断中 あと n 秒」など。無ければ null)
 */
function applyGateSeats(names, notes, spectatorAllowed, mySeat) {
    // ★25c: 全公開部屋は1人で両席を操作するモードなので、席の選択肢を出さない。
    //   席Aのボタン1つに畳み、文言も「両方を操作できる」ことを言う。
    //   (席Bに座り直したいという要求は、入室後の反転ボタンが吸収する)
    const single = gateRoomType !== 'VERSUS';
    for (const seatId of ['A', 'B']) {
        const button = gateEl('seat-gate-' + seatId.toLowerCase());
        const name = names[seatId];
        const note = notes[seatId];
        const mine = mySeat === seatId;
        const occupied = !!name && !mine;
        button.dataset.occupied = occupied ? 'true' : 'false';
        button.disabled = occupied;
        button.textContent = name
            ? `席${seatId}: ${name}${note ? '(' + note + ')' : ''}${mine ? ' — あなた' : ''}`
            : (single && seatId === 'A' ? '席に着く(A・B両方を操作できます)' : `席${seatId}に座る`);
        // ★toggle は毎回通す。single のときだけ付ける形だと、部屋種別が変わった後の
        //   再描画で前回の d-none が残る(25c 検証で検出)
        button.classList.toggle('d-none', single && seatId === 'B' && !name);
    }
    const spectate = gateEl('seat-gate-spectate');
    // ★観戦を許可しない部屋では観戦ボタンを出さない(2-1)。届く宛先が存在しないためである。
    //   入室後の昇格(seat モード)では既に観戦しているので出す意味が無い。
    spectate.classList.toggle('d-none', !spectatorAllowed || gateMode === 'seat');
    spectate.dataset.occupied = 'false';
}

/** 入室前の席選択。決着(入室成功)まで盤面は描かない。 */
function openJoinGate(summary) {
    gateMode = 'join';
    gateRoomType = summary.type;
    gateEl('seat-gate-room').textContent = summary.roomName || '(名称未設定)';
    gateEl('seat-gate-code').textContent = ROOM_ID;
    gateEl('seat-gate-type').textContent = ROOM_TYPE_LABELS[summary.type] || summary.type;
    gateEl('seat-gate-name-wrap').classList.remove('d-none');
    // ★対戦部屋では名前が必須である(F4)。サーバも同じ検証をする(ManualRoom.join)
    gateEl('seat-gate-name-req').textContent = summary.type === 'VERSUS' ? '(必須)' : '(省略可)';
    gateEl('seat-gate-cancel').classList.add('d-none');
    clearGateError();
    applyGateSeats(
        { A: summary.seatAName, B: summary.seatBName },
        { A: null, B: null },
        summary.spectatorAllowed,
        null);
    gateEl('seat-gate').classList.remove('d-none');
    return new Promise((resolve) => { gateResolve = resolve; });
}

/** 入室後の昇格(観戦者 → 席。2-2)。部屋情報はビューから取る。 */
function openSeatChangeGate() {
    if (!latestView) return;
    gateMode = 'seat';
    gateRoomType = latestView.roomType;
    gateEl('seat-gate-room').textContent = latestView.roomName || '(名称未設定)';
    gateEl('seat-gate-code').textContent = ROOM_ID;
    gateEl('seat-gate-type').textContent =
        ROOM_TYPE_LABELS[latestView.roomType] || latestView.roomType;
    // 入室済みなので名前は変えない
    gateEl('seat-gate-name-wrap').classList.add('d-none');
    gateEl('seat-gate-cancel').classList.remove('d-none');
    clearGateError();

    const names = { A: null, B: null };
    const notes = { A: null, B: null };
    for (const occupant of latestView.occupants || []) {
        if (!occupant.seatId) continue;
        names[occupant.seatId] = occupant.displayName;
        // ★切断猶予中でも席は空かない(2-4)。残り時間まで出す
        notes[occupant.seatId] = occupant.connected
            ? null
            : `切断中 あと${occupant.disconnectSecondsLeft || 0}秒`;
    }
    applyGateSeats(names, notes, latestView.spectatorAllowed, latestView.viewerSeat);
    gateEl('seat-gate').classList.remove('d-none');
}

/** 部屋が見つからない等、席選択そのものが成立しないとき。 */
function showGateFatal(message) {
    gateEl('seat-gate-room').textContent = '入れませんでした';
    gateEl('seat-gate-code').textContent = ROOM_ID;
    gateEl('seat-gate-type').textContent = '';
    gateEl('seat-gate-name-wrap').classList.add('d-none');
    gateEl('seat-gate-buttons').classList.add('d-none');
    gateEl('seat-gate-cancel').classList.add('d-none');
    showGateError(message);
    gateEl('seat-gate').classList.remove('d-none');
}

gateEl('seat-gate-buttons').addEventListener('click', async (e) => {
    const button = e.target.closest('button');
    if (!button || button.disabled) return;
    // ★観戦ボタンには data-seat が無い。null が「席に着かない」を表す
    const seat = button.dataset.seat || null;

    if (gateMode === 'seat') {
        closeGate();
        send('seat', { seat });
        return;
    }

    const name = gateEl('seat-gate-name').value.trim();
    if (gateRoomType === 'VERSUS' && name === '') {
        showGateError('対戦部屋では名前が必要です');
        return;
    }
    clearGateError();
    setGateBusy(true);
    try {
        const res = await fetch(`/manual/api/rooms/${ROOM_ID}/occupants`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            // ★席を指定しないときは spectate を立てる。全公開部屋の後方互換では
            //   「席も spectate も無い」が自動着席として扱われるためである(21a の join)
            body: JSON.stringify({ displayName: name || null, seat, spectate: seat === null }),
        });
        if (!res.ok) throw new Error((await res.json()).message || '入室できませんでした');
        const data = await res.json();
        saveOccupant(data.occupantId, data.displayName);
        closeGate();
        gateResolve(data.occupantId);
    } catch (err) {
        showGateError(err.message);
        setGateBusy(false);
    }
});

gateEl('seat-gate-cancel').addEventListener('click', closeGate);

/** occupantId を確定させてから解決する。localStorage にあればそれを使い、無ければ席選択へ。 */
async function resolveOccupant() {
    const saved = loadSavedOccupant();
    if (saved) {
        // ★復帰は席選択を経ない(2-1)。同じ人が同じ席へ戻るだけだからである
        return saved.occupantId;
    }
    const res = await fetch(`/manual/api/rooms/${ROOM_ID}`);
    if (!res.ok) {
        throw new Error((await res.json()).message || '部屋が見つかりません');
    }
    return openJoinGate(await res.json());
}

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

// ★カード定義の取得は接続と独立に始める(Batch 25)。失敗しても対戦は続けられる
loadCardLibrary();

/** occupantId が決まってから初めて STOMP 接続を始める(0章)。 */
resolveOccupant()
    .then((occupantId) => {
        OCCUPANT_ID = occupantId;
        client.activate();
    })
    .catch((e) => {
        setConnectionStatus('入室に失敗しました: ' + e.message);
        showGateFatal(e.message);
    });

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
    // ★21c 7-2: 矢印は揮発メッセージである。ビューでもログでもないので、
    //   latestView を書き換えず、盤面の再描画も起こさない。
    if (msg.type === 'CUE') {
        applyDragCue(msg.cue);
        return;
    }
    if (msg.type === 'ERROR') {
        if (msg.message === 'この部屋に入室していません') {
            // ★猶予切れで席が空けられた等、サーバが在室者として認識できなかった場合。
            //   古い occupantId を捨てて新規入室からやり直す(0章)。
            forgetOccupant();
            location.reload();
            return;
        }
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

/**
 * 小トースト(★21c 3-5)。
 *
 * ★ヘッダの {@code deck-import-status} ではなく独立した要素にした理由:
 * 非公開チップは相手上段(画面の上端近く)にあり、ヘッダの文字は視線から遠い。
 * 「押したのに何も起きない」を解消するのが目的である以上、
 * 反応は<b>押した場所の近く</b>で見えなければ意味が無い。
 * 中央下に固定するのは、盤面のどこを押しても同じ場所に出るほうが探さずに済むためである。
 */
let toastTimer = null;
function showToast(message) {
    let box = document.getElementById('manual-toast');
    if (!box) {
        box = document.createElement('div');
        box.id = 'manual-toast';
        box.className = 'manual-toast';
        document.body.appendChild(box);
    }
    box.textContent = message;
    box.classList.remove('d-none');
    if (toastTimer !== null) {
        clearTimeout(toastTimer);
    }
    toastTimer = setTimeout(() => {
        toastTimer = null;
        box.classList.add('d-none');
    }, 1600);
}

/**
 * 要素を短く明滅させる(★21c 3-5)。
 * ★同じ要素を続けて押したときにアニメーションが再生されるよう、クラスを外して
 * リフローを1回強制してから付け直す。付けっぱなしだと2回目以降は無反応に見える。
 */
function flashDenied(el) {
    if (!el) return;
    el.classList.remove('manual-denied');
    void el.offsetWidth;
    el.classList.add('manual-denied');
    setTimeout(() => el.classList.remove('manual-denied'), 700);
}

/**
 * 相手のゾーンをクリックしたときの反応(★21c 3-5)。
 *
 * ★<b>無反応にしない。</b>20c までは非公開ゾーンでも {@code openZoneBand} を呼んでいたため、
 * 対戦部屋では中身が空の帯が開いていた。「空である」と「見えない」は別のことであり、
 * 空の帯は前者を意味してしまう。帯を開かず、明滅と小トーストで「非公開」と伝える。
 */
function openZoneOrDeny(el, seatView, zoneName) {
    if (isZoneVisible(seatView, zoneName)) {
        openZoneBand(seatView.id, zoneName);
        return;
    }
    flashDenied(el);
    showToast(`${ZONE_LABELS[zoneName]}は非公開`);
}

// ---------------------------------------------------------------
// 3) 文明色(設計書 4-2)
// ---------------------------------------------------------------

// ★Batch 25b: パレットはカードフェイス(FACE_CIV_COLORS)と同一の1系統に統一した。
//   タイルは明色ベタ塗り+コントラスト計算(textColorFor)をやめ、
//   フェイスと同じ暗色トーン(.mcard-frame)+明色文字で描く。
function civColor(civ) {
    return FACE_CIV_COLORS[civ] || FACE_CIV_COLORS.NONE;
}

/** タイルをフェイスと同じ見た目の枠にする(場・リーダー・マナ・相手上段マナ) */
function applyCivFrame(el, civ) {
    el.classList.add('mcard-frame');
    el.style.setProperty('--mc', civ ? civColor(civ) : '#5a5468');
}

// ---------------------------------------------------------------
// 4) 描画本体
// ---------------------------------------------------------------

// ---------------------------------------------------------------
// 4-0) 視点(★Batch 21b。設計書 3-1 / 10章)
// ---------------------------------------------------------------
//
// ★「A=下」の固定をここで外す。常に自席が下に来る。
//
// ★★入れ替えるのは<b>表示位置だけ</b>である(10章)。
//   `cardLocation` / `registerDropTarget` / 送信ペイロードに渡す席は必ず<b>実席</b>を保つ。
//   視点を送信データに混ぜると、サーバが受け取った "A" が誰の A なのか決まらなくなる。
//   そのためこの節の関数は「どの席のビューをどちらの入れ物に描くか」だけを答え、
//   席そのものは `seatView.id` から取る。描画関数の中で 'A' / 'B' をリテラルで書かない。
//
// 観戦者(viewerSeat が null)は既定で A を下に置き、上下反転トグル(3-2)で入れ替えられる。

/**
 * 画面下段に描く席のID。★自席。観戦者は A(反転トグルで B)。
 *
 * ★21c 3-2: 上下反転はこの関数に条件を1つ足すだけで入る。
 * 反転しているのは<b>どの席ビューをどちらの入れ物へ描くか</b>だけであり、
 * `cardLocation` / `registerDropTarget` / 送信ペイロードが受け取る席は
 * 相変わらず `seatView.id`(実席)である(10章)。
 *
 * ★対戦部屋のプレイヤーには反転を出さない(3-1)。自分の手札が上段に出た状態で
 * 相手の場へ落とす操作は事故の温床だからである。
 * ★25c(マスター指示): <b>全公開部屋では着席者も反転できる</b>。一人回しは
 * 1人で両席を回すモードであり、反転が「今どちらの席を操作するか」の切替そのものになる。
 * 下段に来た席は手札・マナ・パイルまで完全なUIで操作できる(元からサーバは
 * 全公開部屋で席の権限チェックをしない。ManualPermissions.denySeatAction)。
 */
function canFlipBoard(view) {
    return !view.viewerSeat || view.roomType !== 'VERSUS';
}

function bottomSeatId(view) {
    const own = view.viewerSeat || 'A';
    if (canFlipBoard(view) && boardFlipped) {
        return own === 'A' ? 'B' : 'A';
    }
    return own;
}

/** 画面上段に描く席のID。★相手 */
function topSeatId(view) {
    return bottomSeatId(view) === 'A' ? 'B' : 'A';
}

/** 席IDから席ビューを引く。★席の実体はサーバの席A/席Bのままである */
function seatOf(view, seatId) {
    return seatId === 'A' ? view.seatA : view.seatB;
}

function bottomSeat(view) {
    return seatOf(view, bottomSeatId(view));
}

function topSeat(view) {
    return seatOf(view, topSeatId(view));
}

/**
 * ゾーンの枚数(★21b)。
 *
 * 対戦部屋では非公開ゾーンが `zones` にキーごと現れない(21a 1-2)。
 * 中身の配列を数えると 0 になってしまうため、サーバが全ゾーンぶん送っている
 * `counts` を優先する。「何枚あるか」は公開情報である(3-3)。
 *
 * ★これは 21c の「相手上段の再構成」の前倒しではない。並びには一切触れず、
 * 表示している数の出所だけを正しくしている。
 */
/**
 * 対戦部屋の観戦者か(★21b)。
 * ★全公開部屋では席に着いていなくても操作できる(権限が効かない部屋である)ため、
 * 部屋の種類と席の両方を見る。判定を1箇所に閉じ込めておく。
 */
function isSpectatorViewer() {
    return !!latestView && latestView.roomType === 'VERSUS' && !latestView.viewerSeat;
}

function zoneCount(seatView, zoneName) {
    if (seatView.counts && seatView.counts[zoneName] !== undefined) {
        return seatView.counts[zoneName];
    }
    return (seatView.zones[zoneName] || []).length;
}

/**
 * そのゾーンの中身が閲覧者に届いているか(★21c 3-5)。
 *
 * ★判定材料は「キーがあるか」だけである。21a は非公開ゾーンを
 * <b>zones にキーごと載せない</b>と決めており(3-3・B1)、空配列とは区別できる。
 * 「対戦部屋か」「相手席か」をクライアントで組み立て直すと、サーバの公開範囲の
 * 定義がクライアントにも書かれることになり、2箇所に分かれる(21a の落とし穴)。
 */
function isZoneVisible(seatView, zoneName) {
    return !!seatView.zones
        && Object.prototype.hasOwnProperty.call(seatView.zones, zoneName);
}

function renderAll(view) {
    faceBackImageId = view.backImageId || faceBackImageId;
    cardLocation = new Map();
    zoneAnchors = new Map();
    renderHeader(view);
    renderOpponentTop(view);
    renderOpponentMinions(view);
    renderCenterLine(view);
    renderSelfMinions(view);
    renderManaRow(view);
    renderHand(view);
    renderPiles(view);
    renderLog(view.log);
    if (pinnedZoom) {
        renderZoom(pinnedZoom);
    }
    refreshOverlay();
    refreshLpModal(view); // ★20a 2-4: LPモーダルが開いている間は数値だけ差し替える
    refreshWeaponModal(view); // ★20b 2-2: ウェポン操作モーダルも同じ扱い
    // ★23: 開始シーケンスのモーダル・オーバーレイ・待機表示。
    //   refreshOverlay の後に置くのは、マリガンオーバーレイの開閉をここが決めるためである
    //   (開いていれば描くのは refreshOverlay 側で、二重に描かない)
    renderStartUi(view);
    // ★21c 7-1: アンカー要素を作り直したので、表示中の矢印を引き直す
    renderDragCues();
}

/**
 * ★20b 2-1: ターン数・フェイズ名の表示は削除した。人間が数えるほうが早く、
 * 縦の3行(約100px)を1行へ縮めるための最大の削り代だったためである。
 * サーバ側の turn / phase 操作は休眠コードとして残してある(フェイズ2で再導入しうる)。
 */
function renderHeader(view) {
    document.getElementById('btn-undo').disabled = !view.canUndo;
    document.getElementById('btn-redo').disabled = !view.canRedo;
    // ★対戦部屋は Redo を提供しない(6-3・D6)。永久に押せないボタンを残さず隠す
    document.getElementById('btn-redo').classList.toggle('d-none', view.roomType === 'VERSUS');

    document.getElementById('room-name').textContent = view.roomName || '';
    document.getElementById('room-type').textContent =
        ROOM_TYPE_LABELS[view.roomType] || view.roomType || '';

    renderSeatButton(view);
    renderSpectatorControls(view);
    renderDeckButtons(view);
    renderLogLink();
    renderOccupantList(view.occupants);
    // ★23 2-3: [ゲームを始める] は「開始できる状態」のときだけ出す。
    //   ★条件(全公開=1席以上 / 対戦=両席のデッキ読込 / 押せる人か)はサーバが判定して
    //   view.start.canBegin に載せている。クライアントで組み立て直すと判定が2箇所に分かれる
    //   (21a の「公開範囲の判定を2箇所に書かない」と同型の罠)。
    document.getElementById('btn-start')
        .classList.toggle('d-none', !(view.start && view.start.canBegin));
    // ★宣言は自席のぶんだけ(6-3・D4)。観戦者は席を持たないためサーバが弾く。
    //   ★25c: 全公開部屋の着席者は「操作中の席(下段)」として宣言する(反転に追随)
    declareSeat = view.viewerSeat
        ? (view.roomType === 'VERSUS' ? view.viewerSeat : bottomSeatId(view))
        : null;
}

/** 席を立つ / 席に着く(2-2)。文言は自席の有無だけで決まる。 */
function renderSeatButton(view) {
    const button = document.getElementById('btn-seat');
    if (view.viewerSeat) {
        // ★観戦を許可しない部屋では降りる先が無いため、席を立つ = 退室である(2-2)
        button.textContent = view.spectatorAllowed ? '席を立つ' : '席を立つ(退室)';
    } else {
        button.textContent = '席に着く';
    }
}

/**
 * 観戦者のトグル2つ(★21c 3-2)。
 *
 * ★2つのトグルは<b>行き先が違う</b>。混ぜてはならない。
 * <ul>
 *   <li>全見え/公開のみ — <b>サーバへ送る</b>。「全部送っておいてクライアントで隠す」
 *       形にすると、公開のみ視点の観戦者のブラウザに相手の手札が届いてしまい、
 *       3-3 が「カードオブジェクトを一切載せない」と決めた意味が消える</li>
 *   <li>上下反転 — <b>クライアントだけで完結する</b>。どちらを下に置くかは
 *       見る側の都合であり、盤面の事実ではない(10章)</li>
 * </ul>
 *
 * ★どちらもプレイヤーには出さない(3-1)。永久に押せないボタンを置かない、という
 * 21b の出し分けの方針(1-5)をそのまま適用している。
 */
function renderSpectatorControls(view) {
    const spectator = !view.viewerSeat;
    const viewBtn = document.getElementById('btn-spectator-view');
    const flipBtn = document.getElementById('btn-flip');
    viewBtn.classList.toggle('d-none', !spectator);
    // ★25c: 反転は観戦者に加えて<b>全公開部屋の着席者</b>にも出す(canFlipBoard)。
    //   一人回しの「操作する席の切替」ボタンである。対戦部屋の着席者には出さない(3-1)
    flipBtn.classList.toggle('d-none', !canFlipBoard(view));
    if (!canFlipBoard(view)) {
        // ★対戦部屋で席に着いたら反転は解除する。自席=下が崩れたままになるのを防ぐ(3-1)
        boardFlipped = false;
    } else {
        flipBtn.textContent = `${spectator ? '下段' : '操作'}: 席${bottomSeatId(view)}`;
    }
    if (!spectator) {
        return;
    }
    const current = view.spectatorView || 'PUBLIC_ONLY';
    viewBtn.dataset.value = current;
    viewBtn.textContent = `視点: ${SPECTATOR_VIEW_LABELS[current] || current}`;
}

/**
 * デッキ読込ボタンの出し分け(6-3・E3)。
 * ★対戦部屋では自席のぶんだけ出す。これは操作補助にすぎず、
 * 検証はサーバが行う(設計判断27)。全公開部屋は従来どおり両方出す。
 */
function renderDeckButtons(view) {
    const restricted = view.roomType === 'VERSUS';
    for (const seatId of ['A', 'B']) {
        document.getElementById('deck-label-' + seatId.toLowerCase())
            .classList.toggle('d-none', restricted && view.viewerSeat !== seatId);
    }
}

/**
 * ログ書出リンクに occupantId を付ける(★21a 1-12 の積み残しの解消)。
 * 対戦部屋では occupantId 無しの書出は 400 になる。誰として書き出すのか分からないまま
 * 完全ログを返すのが「ダウンロードだけ完全版」の裏口だからである(5-4)。
 */
function renderLogLink() {
    document.getElementById('btn-log').href =
        `/manual/api/rooms/${ROOM_ID}/log?occupantId=${encodeURIComponent(OCCUPANT_ID)}`;
}

// ---- 在室者リスト(2-3) ----

/** ヘッダに直接出すチップの数。これを超えたぶんは「+n」に畳む(2-3)。 */
const OCCUPANT_CHIP_LIMIT = 3;

/** 役割記号。席なら席ID、席が無ければ観戦(2-3)。 */
function occupantRoleMark(occupant) {
    return occupant.seatId ? occupant.seatId : '観';
}

/**
 * 在室者リスト(2-3)。★ヘッダ1行を維持するため、チップは数人ぶんだけ直接出す。
 * 全員ぶんの詳細はポップオーバーへ送る。
 */
function renderOccupantList(occupants) {
    const box = document.getElementById('occupant-list');
    box.innerHTML = '';
    const list = occupants || [];
    for (const occupant of list.slice(0, OCCUPANT_CHIP_LIMIT)) {
        const badge = document.createElement('span');
        const tone = occupant.connected ? 'text-bg-secondary' : 'text-bg-dark';
        badge.className = `badge ${tone}`;
        badge.textContent = `${occupant.displayName}[${occupantRoleMark(occupant)}]`
            + (occupant.self ? '(自分)' : '');
        box.appendChild(badge);
    }
    if (list.length > OCCUPANT_CHIP_LIMIT) {
        const more = document.createElement('span');
        more.className = 'badge text-bg-secondary manual-occupant-more';
        more.textContent = `+${list.length - OCCUPANT_CHIP_LIMIT}`;
        box.appendChild(more);
    }
    renderOccupantPopover(list);
}

/**
 * 在室者ポップオーバー(2-3)。名前・役割・観戦の視点・切断中の残り時間を出す。
 * ★観戦者の視点まで見せるのは設計書16 11-2 の確定事項である。
 * 「全見えの人が居る」ことが分かっていないと、プレイヤーは何を隠せているのか判断できない。
 */
function renderOccupantPopover(list) {
    const body = document.getElementById('occupant-popover-body');
    body.innerHTML = '';
    for (const occupant of list) {
        const row = document.createElement('tr');

        const name = document.createElement('td');
        name.textContent = occupant.displayName + (occupant.self ? '(自分)' : '');
        row.appendChild(name);

        const role = document.createElement('td');
        role.textContent = occupant.seatId ? `席${occupant.seatId}` : '観戦';
        row.appendChild(role);

        const view = document.createElement('td');
        // ★視点は観戦者だけが持つ(サーバが席つきの人には null を入れている)
        view.textContent = occupant.spectatorView
            ? SPECTATOR_VIEW_LABELS[occupant.spectatorView] || occupant.spectatorView
            : '';
        row.appendChild(view);

        const state = document.createElement('td');
        if (occupant.connected) {
            state.textContent = '';
        } else {
            state.className = 'text-warning';
            state.textContent = `切断中 あと${occupant.disconnectSecondsLeft || 0}秒`;
        }
        row.appendChild(state);

        body.appendChild(row);
    }
}

// ★20b 2-1: PHASE_LABELS / phaseLabel は表示を消したため削除した。
//   フェイズの enum 自体はサーバ側に残っている。

const ZONE_LABELS = {
    DECK: '山札', HAND: '手札', MANA: 'マナ', FIELD: 'ミニオン', WEAPON: 'ウェポン',
    TRASH: '墓地', LOST: '消滅', TABOO: '禁忌',
    // ★20b 2-4: REVEAL の表示名は「一時公開」から「公開」へ変更した(設計書1-2・2-3)。
    //   サーバ側 ManualZone の displayName も合わせてある。
    REVEAL: '公開', PLAY: 'プレイ中', PRIVATE: '確認',
};

/** 共有ゾーン(20b 3-1)。席に属さず view.shared から読む。 */
const SHARED_ZONES = new Set(['PLAY', 'REVEAL']);

/**
 * 相手上段(★Batch 21c。設計書4章。マスター指示による再構成)。
 *
 * <pre>
 *   [マナ簡略表示 (MP n)] [墓地] [消滅] …伸縮… [山n][禁n][確n][手n] [リーダー+ウェポン]
 * </pre>
 *
 * <h3>★なぜ並べ替えるのか — 「全ゾーンがアンカーを持つ」ことの保証(7-1)</h3>
 * 20c までの上段はチップ5つとリーダーだけであり、相手の<b>マナと手札</b>に対応する要素が
 * 画面に存在しなかった。ドラッグ軌跡の矢印は「ゾーン → 画面上の要素」の対応で描くため、
 * 要素が無いゾーンは矢印の端点にできない。4章の再構成は見た目の話に見えて、
 * 実際には 7章 の前提条件である。だから 21c は 1(この関数)→ 3(矢印)の順で行う。
 *
 * <h3>★情報の密度で3段階に分ける</h3>
 * <ul>
 *   <li><b>マナ</b> — 表向きは縮小カード画像。相手の使えるMPと文明構成は、
 *       対戦中に最も頻繁に確認する公開情報である</li>
 *   <li><b>墓地・消滅</b> — 最上段の縮小画像+枚数。「何が落ちたか」は
 *       一番上だけ分かれば足り、詳しくは帯で見る</li>
 *   <li><b>山・禁・確・手</b> — チップ(枚数のみ)。中身は原理的に見えないため、
 *       画像を置く意味が無い</li>
 * </ul>
 * ★「手n」をチップに足したのは 4章 の指示であるが、意味としても正しい。
 * 相手の手札枚数は 3-3 が公開情報と定めており、20c まで画面のどこにも出ていなかった。
 *
 * ★リーダーを右端に置くのは自席と同じ側であり、左右対称にはしない(確定事項Q4)。
 * ★この行は高さ148px以内に収める(4章)。マナの縮小画像を 40×56 に抑えているのはそのためで、
 * 行の高さを決めているのはリーダータイル(120px)のままである。
 */
function renderOpponentTop(view) {
    const el = document.getElementById('seat-opponent-top');
    el.innerHTML = '';
    // ★21b 3-1: 上段は「相手」であり、席Bとは限らない。実席は seat.id が持つ
    const seat = topSeat(view);
    const seatId = seat.id;

    // 左: マナ簡略表示。★flex で伸び、余った幅をここが吸ってチップ列を右へ押す
    el.appendChild(createOpponentMana(view, seat));

    // 左中: 墓地・消滅(簡略画像)
    for (const zoneName of OPPONENT_PILE_ZONES) {
        el.appendChild(createOpponentPile(seat, zoneName));
    }

    // 右: 枚数チップ4つ
    const chips = document.createElement('div');
    chips.className = 'd-flex gap-1 manual-opp-chips';
    for (const zoneName of OPPONENT_CHIP_ZONES) {
        chips.appendChild(createOpponentChip(seat, zoneName));
    }
    el.appendChild(chips);

    // 右端: リーダー+ウェポン合体タイル。
    // ★22 3-4: 相手側は合体タイルのまま(148px の高さ制約があり枠を増やせない)。
    //   自席かどうかを createLeaderTile に判定させず、呼び出し側がフラグを渡す
    el.appendChild(createLeaderTile(seat, { withWeapon: true }));
    cardLocation.set(seat.leader ? seat.leader.instanceId : null,
        { seatId, zone: 'LEADER' });

    // ★重ね表示は実測幅で決めるため、行の要素をすべて載せてから最後に適用する
    //   (自席マナの applyManaOverlap と同じ手順)
    applyOpponentManaOverlap();
}

/** 相手上段で簡略画像にするゾーン(4章)。★どちらも公開ゾーンなので帯が開く */
const OPPONENT_PILE_ZONES = ['TRASH', 'LOST'];

/** 相手上段で枚数チップにするゾーン(4章)。★対戦部屋ではすべて非公開である */
const OPPONENT_CHIP_ZONES = ['DECK', 'TABOO', 'PRIVATE', 'HAND'];

/**
 * 枚数チップ1つ(4章)。
 * ★枚数は必ず {@code zoneCount} から取る。対戦部屋では中身の配列が届かないため、
 * 配列を数えると 0 になり「嘘の枚数」を表示する(21b 1-4)。
 */
function createOpponentChip(seat, zoneName) {
    const chip = document.createElement('div');
    chip.className = 'zone-pile-mini manual-opp-chip';
    chip.dataset.seat = seat.id;
    chip.dataset.zone = zoneName;
    chip.title = ZONE_LABELS[zoneName];
    chip.textContent = `${ZONE_LABELS[zoneName][0]}${zoneCount(seat, zoneName)}`;
    // ★22 1-2: 左=先頭のカードを拡大 / 右=帯(一覧)を開く。
    //   非公開のときは左右どちらでも 21c 3-5 の明滅+トーストが返る
    chip.addEventListener('click', () => zoomTopOrDeny(chip, seat, zoneName, true));
    chip.addEventListener('contextmenu', (e) => {
        e.preventDefault();
        openZoneOrDeny(chip, seat, zoneName);
    });
    registerDropTarget(chip, seat.id, zoneName);
    registerZoneAnchor(chip, seat.id, zoneName);
    return chip;
}

/**
 * 墓地・消滅の簡略画像(4章)。最上段カードの縮小画像+枚数バッジ。
 * ★0枚は破線の空枠にする。画像が無いことと0枚であることを見分けられるようにするためである。
 */
function createOpponentPile(seat, zoneName) {
    const box = document.createElement('div');
    box.className = 'manual-opp-pile';
    box.dataset.seat = seat.id;
    box.dataset.zone = zoneName;
    box.title = ZONE_LABELS[zoneName];

    const face = document.createElement('div');
    face.className = 'manual-opp-face';
    const cards = seat.zones[zoneName] || [];
    const count = zoneCount(seat, zoneName);
    // ★公開パイルの最上段は末尾である(20a 2-1。山札とは逆)
    const top = cards.length > 0 ? cards[cards.length - 1] : null;
    if (top && !top.faceDown) {
        face.appendChild(cardFace(top, 'micro'));
    } else if (top) {
        face.appendChild(cardBackFace());
    } else if (count === 0) {
        face.classList.add('manual-opp-face-empty');
    } else {
        face.classList.add('manual-opp-face-blank');
        face.textContent = (top && top.name) || '?';
    }
    const badge = document.createElement('div');
    badge.className = 'manual-opp-count';
    badge.textContent = count;
    face.appendChild(badge);
    box.appendChild(face);

    const label = document.createElement('div');
    label.className = 'manual-opp-label';
    label.textContent = ZONE_LABELS[zoneName];
    box.appendChild(label);

    // ★22 1-2(マスター要望の本体): 左=一番上のカードを拡大 / 右=帯を開く
    box.addEventListener('click', () => zoomTopOrDeny(box, seat, zoneName, false));
    box.addEventListener('contextmenu', (e) => {
        e.preventDefault();
        openZoneOrDeny(box, seat, zoneName);
    });
    registerDropTarget(box, seat.id, zoneName);
    registerZoneAnchor(box, seat.id, zoneName);
    return box;
}

/**
 * 相手のマナ簡略表示(4章)。表向き=縮小カード画像の横並び、裏向き=裏面1枚+枚数バッジ。
 *
 * ★MPはサーバの計算値をそのまま出す(3-3)。クライアントでアンタップ枚数を数え直すと、
 * 観戦者(公開のみ)には裏向きマナのカードが届かないため必ずズレる。
 *
 * ★タップ状態は<b>減光</b>で示す(マスター確認済み)。回転させると外接矩形が伸び、
 * 148px の高さ制約(4章)を回転角の都合で守れなくなる。
 */
function createOpponentMana(view, seat) {
    const wrap = document.createElement('div');
    wrap.className = 'manual-opp-mana';
    wrap.dataset.seat = seat.id;
    wrap.dataset.zone = 'MANA';

    const manaCards = seat.zones.MANA || [];
    const faceUpCards = manaCards.filter((c) => !c.faceDown);
    const faceDownCount = seat.manaFaceDownCount === undefined
        ? manaCards.filter((c) => c.faceDown).length
        : seat.manaFaceDownCount;
    const split = faceDownManaSplit(seat, faceUpCards, faceDownCount);

    const label = document.createElement('div');
    label.className = 'manual-opp-label manual-opp-mana-label';
    label.textContent = `マナ ${zoneCount(seat, 'MANA')}(MP ${seat.mp})`;
    wrap.appendChild(label);

    const track = document.createElement('div');
    track.className = 'manual-opp-mana-track';
    for (const card of faceUpCards) {
        track.appendChild(createOpponentManaCard(card, seat.id));
        cardLocation.set(card.instanceId, { seatId: seat.id, zone: 'MANA' });
    }
    // ★22 2-7: 裏向きは「アンタップぶん」「タップぶん」の2枠に分ける。
    //   並び順は 表向き… → 裏アンタップ → 裏タップ。0枚の枠は出さない
    //   (0 の枠が並ぶと読みにくく、「何も無い」ことは枠が無いことで既に伝わる)
    if (split.untapped > 0) {
        track.appendChild(createOpponentManaBack(split.untapped, view.backImageId, false));
    }
    if (split.tapped > 0) {
        track.appendChild(createOpponentManaBack(split.tapped, view.backImageId, true));
    }
    wrap.appendChild(track);

    registerDropTarget(wrap, seat.id, 'MANA');
    registerZoneAnchor(wrap, seat.id, 'MANA');
    return wrap;
}

/**
 * 相手の裏向きマナのタップ / アンタップ内訳(★Batch 22 2-7)。
 *
 * <h3>★サーバは触らない。既に届いている値から引き算で出す</h3>
 * <pre>
 *   裏アンタップ = seat.mp − (表向きカードのうち tapped でない枚数)
 *   裏タップ     = manaFaceDownCount − 裏アンタップ
 * </pre>
 *
 * ★この式が成り立つのは、{@code seat.mp}({@code ManualSeat.availableMp})が
 * <b>マナゾーン全体のアンタップ枚数</b>であり、裏向きも数に入れているからである
 * (同メソッドの javadoc)。<b>availableMp を「表向きだけ」に変えるとここが壊れる。</b>
 *
 * ★設計判断28「同じ情報を2箇所に置かない」に沿う形でもある。サーバに
 * {@code manaFaceDownUntappedCount} を足すと {@code mp} と重複する情報が2つ届き、
 * 片方だけずれる余地が生まれる。<b>引ける値は引く。</b>
 *
 * ★情報公開の観点でも新しく漏れるものは無い。タップ状態は実物のカードが寝ているか
 * どうかであり盤面を見れば分かる公開情報で、MP を数値で公開している(3-3)時点で
 * アンタップ枚数は既に相手へ渡っている。
 *
 * ★防御的な丸め: サーバとクライアントの一時的なズレで負や総枚数超過になりうるので、
 * 0 と faceDownCount で丸める(嘘の数字を出すより丸めるほうがよい。21b 1-4 と同じ軸)。
 */
function faceDownManaSplit(seat, faceUpCards, faceDownCount) {
    const faceUpUntapped = faceUpCards.filter((c) => !c.tapped).length;
    const raw = (seat.mp === undefined || seat.mp === null ? 0 : seat.mp) - faceUpUntapped;
    const untapped = Math.max(0, Math.min(faceDownCount, raw));
    return { untapped, tapped: Math.max(0, faceDownCount - untapped) };
}

/**
 * 相手の表向きマナ1枚(★Batch 22 2-2 で文明色の簡略タイルへ変更。48×66)。
 *
 * ★自席(64×88)と絵の種類を揃える。21c はここをカード画像にしたが、自席と並べると
 * 同じものに見えなかった。40px 幅ではカード名が読めないため 48px へ広げている(2-3)。
 *
 * ★掴めるのは自席のマナだけだが、ここを表示専用にはしない — 相手のカードを動かせないのは
 * サーバの権限層(6-1)の仕事であり、掴み口は残しておく。
 */
function createOpponentManaCard(card, seatId) {
    const tile = document.createElement('div');
    tile.className = 'manual-opp-mana-card manual-opp-mana-face'
        + (card.tapped ? ' manual-opp-tapped' : '');
    tile.dataset.instanceId = card.instanceId;
    tile.draggable = true;
    tile.title = `${card.name || ''}${card.tapped ? '(タップ)' : ''}`;
    if (card.civilization) {
        applyCivFrame(tile, card.civilization);
    }
    const name = document.createElement('div');
    name.className = 'manual-opp-mana-name';
    name.textContent = card.name || '?';
    tile.appendChild(name);
    if (selected.has(card.instanceId)) {
        tile.classList.add('manual-tile-selected');
    }
    tile.addEventListener('dragstart', (e) => onDragStart(e, card, seatId, 'MANA'));
    // ★22 1-2: 左=拡大 / 右=タップ。行全体のクリックへは伝播させない
    tile.addEventListener('click', (e) => { e.stopPropagation(); onCardClick(e, card, seatId, 'MANA'); });
    tile.addEventListener('contextmenu', (e) => {
        e.stopPropagation();
        onCardContextMenu(e, card, seatId, 'MANA');
    });
    return tile;
}

/**
 * 相手の裏向きマナ(裏面画像1枚+枚数バッジ)。★何枚あるかは公開情報である(3-3)。
 *
 * ★2-2: 相手側は「1枚+枚数バッジ」のままでよい(マスター確認済み)。中身が届かないため
 * 枚数分並べても情報量は増えず、幅を食うだけである。<b>揃えるのは絵の種類であって
 * 枚数の数え方ではない。</b>
 *
 * @param tapped タップぶんの枠なら true。減光で示す(2-5 の相手側の記法)
 */
function createOpponentManaBack(count, backImageId, tapped) {
    const tile = document.createElement('div');
    tile.className = 'manual-opp-mana-card manual-opp-mana-back'
        + (tapped ? ' manual-opp-tapped manual-opp-mana-back-tapped' : '');
    tile.dataset.tapped = tapped ? 'true' : 'false';
    tile.title = `裏向き ${count}枚(${tapped ? 'タップ' : 'アンタップ'})`;
    tile.appendChild(cardBackFace());
    const badge = document.createElement('div');
    badge.className = 'manual-opp-count';
    badge.textContent = count;
    tile.appendChild(badge);
    return tile;
}

/**
 * 相手マナの重ね表示。★{@code applyManaOverlap} と同じ考え方の縮小版である(4章)。
 * 実測幅が要るため、DOM に載せた後の renderAll 側から呼ぶ。
 */
function applyOpponentManaOverlap() {
    const track = document.querySelector('#seat-opponent-top .manual-opp-mana-track');
    if (!track) return;
    const tiles = [...track.children];
    for (const tile of tiles) {
        tile.style.marginLeft = '';
        tile.style.zIndex = '';
    }
    if (tiles.length <= 1) return;
    const trackWidth = track.clientWidth;
    // ★22 2-3: タイルを 40×56 → 48×66 へ広げた(文明色タイルはカード名を読ませるため)。
    //   露出の下限もそれに合わせて広げる
    const tileWidth = 48;
    const minExposure = 18;
    const naturalWidth = tiles.length * tileWidth;
    if (naturalWidth <= trackWidth) return;
    const maxOverlap = tileWidth - minExposure;
    const perTileOverlap =
        Math.min(maxOverlap, (naturalWidth - trackWidth) / (tiles.length - 1));
    tiles.forEach((tile, i) => {
        if (i > 0) tile.style.marginLeft = `-${perTileOverlap}px`;
        tile.style.zIndex = String(i + 1);
    });
}

/** 相手のミニオン行(上段)。★席は seatOf が決める。'B' をリテラルで書かない */
function renderOpponentMinions(view) {
    const el = document.getElementById('seat-opponent-minions');
    el.innerHTML = '';
    const seat = topSeat(view);
    const fieldRow = document.createElement('div');
    // ★20c: 幅に応じてタイルを伸ばす指定は手動モード専用クラスへ入れる。
    //   .minion-row は通常モードと共有しているため定義を変えない。
    fieldRow.className = 'minion-row manual-minion-row';
    fieldRow.dataset.seat = seat.id;
    fieldRow.dataset.zone = 'FIELD';
    renderStackRow(fieldRow, seat, 'FIELD', 6); // ★2-9: 7→6
    el.appendChild(fieldRow);
    registerZoneAnchor(fieldRow, seat.id, 'FIELD');
}

/** 自分のミニオン行(下段) */
function renderSelfMinions(view) {
    const el = document.getElementById('seat-self-minions');
    el.innerHTML = '';
    const seat = bottomSeat(view);
    const fieldRow = document.createElement('div');
    fieldRow.className = 'minion-row manual-minion-row';
    fieldRow.dataset.seat = seat.id;
    fieldRow.dataset.zone = 'FIELD';
    renderStackRow(fieldRow, seat, 'FIELD', 6); // ★2-9: 7→6
    el.appendChild(fieldRow);
    registerZoneAnchor(fieldRow, seat.id, 'FIELD');
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
    // ★常に最低1枠は空きを見せる(ドロップ先として)。FIELDはminSlots(既定6)まで埋める
    const emptyCount = Math.max(minSlots - cards.length, 1);
    for (let i = 0; i < emptyCount; i++) {
        const slot = document.createElement('div');
        slot.className = 'tile-slot-empty';
        registerDropTarget(slot, seatView.id, zoneName);
        container.appendChild(slot);
    }
}

/**
 * センターライン(20b 2-3): 両ミニオン行の間の [プレイ中 | 公開]。
 *
 * ★どちらもプレイヤー間の共有ゾーンであり、席に属さない(20b 3-1)。
 * 「この列にあるカードは相手に見えている」という一貫したルールを画面に与えるための場所であり、
 * 対戦モードで相手に見せるカードが画面の中央にあるのが最も直感的である、という判断による。
 */
function renderCenterLine(view) {
    const el = document.getElementById('center-line');
    el.innerHTML = '';
    const shared = view.shared || {};
    el.appendChild(createCenterHalf('PLAY', shared.PLAY || []));
    el.appendChild(createCenterHalf('REVEAL', shared.REVEAL || []));
    // ★20c: 手札と同じく実測幅で決める。DOMに載せた後でなければ行幅が取れない
    for (const row of el.querySelectorAll('.manual-center-row')) {
        fitCardWidths(row, centerCardMaxWidth(), 6);
    }
}

/**
 * センターラインの片側。
 *
 * ★空のときは高さ24pxの細いドロップバー、1枚でも入れば約130pxのストリップへ自動展開する
 * (確定事項Q11)。常時130pxを占有すると、めったに使わないゾーンのために縦を1行ぶん
 * 失うことになる。伸縮はCSSのクラス1つ(`manual-center-open`)で表す。
 */
function createCenterHalf(zoneName, cards) {
    const half = document.createElement('div');
    half.className = 'manual-center-half' + (cards.length > 0 ? ' manual-center-open' : '');
    half.dataset.zone = zoneName;

    const label = document.createElement('div');
    label.className = 'manual-center-label';
    label.textContent = ZONE_LABELS[zoneName] + (cards.length > 0 ? ` ${cards.length}` : '');
    half.appendChild(label);

    if (cards.length > 0) {
        const row = document.createElement('div');
        row.className = 'manual-center-row';
        for (const card of cards) {
            row.appendChild(createHandCard(card, null, null, zoneName));
            // ★共有ゾーンのカードは席を持たない。seatId は null で索引に入れる
            cardLocation.set(card.instanceId, { seatId: null, zone: zoneName });
        }
        half.appendChild(row);
    }

    registerDropTarget(half, null, zoneName);
    // ★共有ゾーンは席を持たない(20b 3-1)。アンカーの鍵も席 null で登録する
    registerZoneAnchor(half, null, zoneName);
    return half;
}

/**
 * 右列のパイル群(20b 2-6)。上段[禁忌][山札][確認] / 下段[消滅][墓地]。
 *
 * ★公開(REVEAL)はセンターラインへ移ったためパイルではなくなった。
 * 代わりに新ゾーンの確認(PRIVATE)が入る。
 */
/**
 * 右列のパイル配置(★20d でマスター指示により確定)。
 *
 * ```
 *   [リーダー][禁忌][山札][確認]
 *             [消滅][墓地]
 * ```
 *
 * ★CSS Grid で行と列を明示する。flex の「行を2本並べる」方式だと、
 * リーダー枠だけ幅が違う(108px)ぶん下段が左へずれ、「禁忌の真下が消滅」という
 * 縦の対応関係が崩れる。位置に意味がある配置なので、位置を直接書ける Grid を使う。
 *
 * 相手のリーダーは相手上段に残してあるため、ここに来るのは自席のぶんだけである。
 */
const PILE_PLACEMENT = {
    TABOO: '1 / 2',
    DECK: '1 / 3',
    PRIVATE: '1 / 4',
    LOST: '2 / 2',   // 禁忌の下
    TRASH: '2 / 3',  // 山札の下
};

function renderPiles(view) {
    const el = document.getElementById('pile-grid');
    el.innerHTML = '';
    // ★21b 3-1: 右列のパイルは常に「自席」のぶんである(観戦者は A)
    const seat = bottomSeat(view);

    const slot = document.createElement('div');
    slot.className = 'manual-pile manual-leader-slot';
    slot.style.gridArea = '1 / 1';
    // ★22 3-4: 自席のリーダータイルは WEAPON のドロップ先ではなくなった。
    //   「ウェポンはウェポン枠に置く」という説明1つで足りる形にする(W2)
    slot.appendChild(createLeaderTile(seat, { withWeapon: false }));
    const label = document.createElement('div');
    label.className = 'manual-pile-label';
    label.textContent = `席${seat.id}のリーダー`;
    slot.appendChild(label);
    el.appendChild(slot);
    cardLocation.set(seat.leader ? seat.leader.instanceId : null,
        { seatId: seat.id, zone: 'LEADER' });

    // ★22 3章: リーダーの真下(2/1)の空きマスをウェポン置き場にする。
    //   2行目は既に消滅・墓地が占めているので、右列も盤面全体も縦は伸びない
    el.appendChild(createWeaponSlot(seat));

    for (const zoneName of Object.keys(PILE_PLACEMENT)) {
        const pile = createCardPile(
            seat.id, zoneName, seat.zones[zoneName] || [], view.backImageId);
        pile.style.gridArea = PILE_PLACEMENT[zoneName];
        el.appendChild(pile);
        registerZoneAnchor(pile, seat.id, zoneName);
    }
}

/**
 * 自席のウェポン枠(★Batch 22 3章)。`#pile-grid` の 2/1(リーダーの真下)。
 *
 * <h3>★44×60 の制約が消えるので、ATK と使用済はその場に出す</h3>
 * 20b の合体ミニタイルは 44×60 しかなく、数値も使用済も札も載せられないため
 * すべて {@link openWeaponModal} へ追い出されていた(20b 2-2)。
 * 独立した枠になったことでこの制約が消えたので、頻度の高い ATK と使用済を表に出す。
 * モーダルは札の編集と数値の直接入力のために残す。
 *
 * <h3>★自席では<b>この枠だけ</b>が WEAPON のドロップ先である(3-4・W2)</h3>
 * 席によってドロップ先が違うことになるが、受け入れる。場所と意味が1対1になり、
 * 「リーダーに落とすと装備される」という比喩を覚えなくてよくなるためである。
 *
 * <h3>★2枚以上は「異常が見える」形にする(20b 2-2 の方針を維持)</h3>
 * ウェポン1枚はゲームルール(総合ルール 2-2)だが、手動モードは判断を実装しないので
 * 2枚以上入ること自体は妨げず、枚数バッジを出して人間が気づけるようにする。
 */
function createWeaponSlot(seat) {
    const box = document.createElement('div');
    box.className = 'manual-pile manual-weapon-slot';
    box.dataset.seat = seat.id;
    box.dataset.zone = 'WEAPON';
    box.style.gridArea = '2 / 1';

    const weapons = seat.zones.WEAPON || [];
    const card = weapons.length > 0 ? weapons[0] : null;

    const face = document.createElement('div');
    face.className = 'manual-pile-face';
    if (card && !card.faceDown) {
        face.appendChild(cardFace(card, 'mini'));
    } else if (card) {
        face.appendChild(cardBackFace());
    } else {
        face.classList.add('manual-pile-blank', 'manual-weapon-slot-empty');
        face.textContent = '未装備';
    }
    if (weapons.length > 1) {
        const badge = document.createElement('div');
        badge.className = 'manual-pile-count manual-weapon-slot-badge';
        badge.textContent = weapons.length;
        face.appendChild(badge);
    }
    box.appendChild(face);

    if (card) {
        // ★4-3: ATK もボタン化する(枠+鉛筆)。当たり判定はチップ全体である
        const atkValue = document.createElement('span');
        atkValue.appendChild(document.createTextNode('ATK '));
        atkValue.appendChild(statSpan(card.attack, card.printedAttack));
        const atk = statButton(atkValue, () => openWeaponModal(card));
        atk.classList.add('manual-weapon-slot-atk');
        box.appendChild(atk);

        const used = document.createElement('div');
        used.className = 'manual-weapon-slot-used'
            + (card.used ? '' : ' manual-tile-used-off');
        used.textContent = card.used ? '使用済' : '未使用';
        used.title = 'クリックで使用済/未使用を切り替える';
        used.addEventListener('click', (e) => {
            e.stopPropagation();
            send('used', { cardIds: [card.instanceId] });
        });
        box.appendChild(used);

        box.draggable = true;
        box.addEventListener('dragstart', (e) => {
            // ★20a 3-1 と同じ形。専用ボタンの上から掴んだときはドラッグを開始しない
            //   (e.target ではなく、実際に指が置かれた位置の要素を見る)
            const origin = document.elementFromPoint(e.clientX, e.clientY);
            if (origin && origin.closest('.manual-stat-button, .manual-weapon-slot-used, button')) {
                e.preventDefault();
                return;
            }
            onDragStart(e, card, seat.id, 'WEAPON');
        });
        // ★22 1-2: 左=拡大 / 右=ウェポン操作モーダル
        box.addEventListener('click', (e) => {
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
        box.addEventListener('contextmenu', (e) => { e.preventDefault(); openWeaponModal(card); });
        if (selected.has(card.instanceId)) {
            box.classList.add('manual-tile-selected');
        }
        cardLocation.set(card.instanceId, { seatId: seat.id, zone: 'WEAPON' });
    } else {
        box.addEventListener('contextmenu', (e) => {
            e.preventDefault();
            showTransientNotice('ウェポンは未装備です');
        });
    }

    const label = document.createElement('div');
    label.className = 'manual-pile-label';
    label.textContent = ZONE_LABELS.WEAPON;
    box.appendChild(label);

    registerDropTarget(box, seat.id, 'WEAPON');
    // ★3-5: 自席の WEAPON のアンカーはこの枠へ移る。移し忘れると矢印のウェポン宛の
    //   端点が相手上段の合体タイルを指したままになる(21c 7-1)
    registerZoneAnchor(box, seat.id, 'WEAPON');
    return box;
}

/** ★非公開ゾーンの集合(2-6)。山札・禁忌は中身ではなく裏面画像を敷く */
const PRIVATE_PILE_ZONES = new Set(['DECK', 'TABOO']);

/**
 * カード型パイル(禁忌・山札・確認・消滅・墓地。20b 2-6)。
 *
 * ★見た目をカード画像にする。枚数と文字だけの箱では「現実のカードゲームに近い画面」
 * という本バッチの方針から外れるため、90×126のカード面に画像を敷き、枚数はその角に重ねる。
 * 非公開パイル(山札・禁忌)は裏面画像、公開パイル(確認・消滅・墓地)は一番上の表画像を使う。
 */
function createCardPile(seatId, zoneName, pile, backImageId) {
    const box = document.createElement('div');
    box.className = 'manual-pile';
    box.dataset.seat = seatId;
    box.dataset.zone = zoneName;

    const face = document.createElement('div');
    face.className = 'manual-pile-face';
    const hidden = PRIVATE_PILE_ZONES.has(zoneName);
    // ★公開パイルの最上段は末尾。山札(index 0 が最上段)とは逆である(20a 2-1)。
    //   ここは「見た目として何を敷くか」の話であり、山札は裏面なので取り違えは起きない。
    const top = pile.length > 0 ? pile[pile.length - 1] : null;
    if (pile.length === 0) {
        face.classList.add('manual-pile-blank');
    } else if (hidden || (top && top.faceDown)) {
        face.appendChild(cardBackFace());
    } else {
        face.appendChild(cardFace(top, 'mini'));
    }

    const count = document.createElement('div');
    count.className = 'manual-pile-count';
    count.textContent = pile.length;
    face.appendChild(count);
    box.appendChild(face);

    const header = document.createElement('div');
    header.className = 'manual-pile-label';
    header.textContent = ZONE_LABELS[zoneName];
    box.appendChild(header);

    registerDropTarget(box, seatId, zoneName);

    if (zoneName === 'DECK') {
        // ★22 1-3: 山札は 左=1枚ドロー / 右=全面表示 のまま。新規約の唯一の例外である。
        //   左を拡大にしても意味が無い(一番上は非公開であり、自分のものなら
        //   「次に引く1枚が見える」というルール上おかしい表示になる)。
        //   右の全面表示は既に「一覧を開く」であり、他のパイルの右クリックと同じ役割である。
        //   ドローはこのアプリで最も回数の多い操作の1つなので、手数を増やさない。
        // ★21c 3-5: 中身が届いていない席のパイルは操作できない。
        //   下段は「自席」だが、公開のみ視点の観戦者にとっては両席とも非公開である。
        //   相手上段のチップと同じ反応(明滅+トースト)に揃える
        box.addEventListener('click', () => {
            if (!isZoneVisible(seatOf(latestView, seatId), 'DECK')) {
                flashDenied(box);
                showToast('山札は非公開');
                return;
            }
            send('draw', { seat: seatId, count: 1 });
        });
        box.addEventListener('contextmenu', (e) => {
            e.preventDefault();
            if (!isZoneVisible(seatOf(latestView, seatId), 'DECK')) {
                flashDenied(box);
                showToast('山札は非公開');
                return;
            }
            openDeckFullscreen(seatId);
        });
        box.title = '左クリック: 1枚ドロー / 右クリック: 全面表示 / ドラッグ: 一番上の1枚を移動';

        // ★20a 2-1: 山札の一番上の1枚をドラッグの起点にする(A1)。
        //   最上段は zones.DECK の index 0(公開パイルの末尾とは逆。ManualGameService.drawCards
        //   が deck.remove(0) で引くのに合わせている)。空のときはドラッグを開始しない(A2)。
        //   複数選択中であっても常に1枚(非公開ゾーンのため「どの1枚か」を選べない)。
        //
        //   ★A4(実マウス検証での訂正、2回目): dragstart の e.target は「実際にドラッグ対象
        //   になった要素(= box 自身)」であり、実際に掴んだ場所の要素ではない
        //   (ブラウザは mousedown 位置から祖先方向へ draggable=true を探して box に
        //   行き着くため、e.target は最初から box になる。e.target.closest(...) では
        //   絶対に一致しない)。実際に指が置かれた場所を見るには、dragstart 時点の
        //   座標で document.elementFromPoint を使う必要がある。
        if (pile.length > 0) {
            box.draggable = true;
            box.addEventListener('dragstart', (e) => {
                const origin = document.elementFromPoint(e.clientX, e.clientY);
                if (origin && origin.closest('.zone-drop-mini, button')) {
                    e.preventDefault();
                    return;
                }
                onDragStart(e, pile[0], seatId, 'DECK');
            });
        } else {
            box.draggable = false;
        }

        // ★20b 2-6: シャッフルと「上へ/下へ」はパイル画像の直下に常時表示で添える。
        //   ホバー時のみ表示する案は、誤操作しやすく存在にも気づきにくいため退けた。
        //   右列は縦に余裕があり、隠す動機が無い。
        const shuffleBtn = document.createElement('button');
        shuffleBtn.className = 'btn btn-sm btn-outline-secondary w-100 mt-1 py-0';
        shuffleBtn.textContent = 'シャッフル';
        shuffleBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            send('shuffle', { seat: seatId });
        });
        box.appendChild(shuffleBtn);

        const dropRow = document.createElement('div');
        dropRow.className = 'd-flex gap-1 mt-1';
        const top1 = document.createElement('div');
        top1.className = 'zone-drop-mini';
        top1.textContent = '上へ';
        registerDropTarget(top1, seatId, 'DECK', 0);
        const bottom1 = document.createElement('div');
        bottom1.className = 'zone-drop-mini';
        bottom1.textContent = '下へ';
        registerDropTarget(bottom1, seatId, 'DECK', 999999);
        dropRow.appendChild(top1);
        dropRow.appendChild(bottom1);
        box.appendChild(dropRow);
    } else {
        // ★22 1-2: 左=一番上のカードを拡大 / 右=帯(一覧)を開く。18c とは逆になった。
        // ★21c 3-5: 中身が届いていないゾーンでは空の帯も拡大も出さず「非公開」を返す。
        //   下段は「自席」だが、公開のみ視点の観戦者にとっては両席とも非公開である
        box.addEventListener('click',
            () => zoomTopOrDeny(box, seatOf(latestView, seatId), zoneName, false));
        box.addEventListener('contextmenu', (e) => {
            e.preventDefault();
            openZoneOrDeny(box, seatOf(latestView, seatId), zoneName);
        });
        box.title = '左クリック: 一番上を拡大 / 右クリック: 一覧を開く';
    }

    return box;
}

/**
 * マナ行。
 *
 * ★20c: リーダー+ウェポン合体タイルを右列へ移したため、この行はマナのストリップだけになった。
 * タイルの大きさ(64×88)は据え置きのまま、MP表示をストリップのラベル側へ寄せ、
 * 行見出しの1行ぶんと余白を削って縦を詰めている(マスター確認済み)。
 */
function renderManaRow(view) {
    const el = document.getElementById('seat-self-mana-row');
    el.innerHTML = '';
    const seat = bottomSeat(view);

    const manaCards = seat.zones.MANA || [];
    const faceUpCards = manaCards.filter((c) => !c.faceDown);
    const faceDownCards = manaCards.filter((c) => c.faceDown);
    // ★観戦者(公開のみ)には裏向きマナのカードが届かない。枚数はサーバが別に送っている(3-3)
    const faceDownCount = seat.manaFaceDownCount === undefined
        ? faceDownCards.length
        : seat.manaFaceDownCount;
    // ★22 2-8: 合計は配列ではなく counts から取る(21b 1-4)。表向きは「合計 − 裏」で出す。
    //   公開のみ視点の観戦者が自席側を見るとき、裏向きのカードは配列に届かないため、
    //   配列を数えると嘘の枚数になる。
    const total = zoneCount(seat, 'MANA');
    const faceUpCount = Math.max(0, total - faceDownCount);

    // ★22 2-8: 行見出し。MP は「表」ラベルではなくここに置く。
    //   MP はマナゾーン<b>全体</b>のアンタップ枚数(ManualSeat.availableMp)であり、
    //   表向きストリップだけの値ではない。20c で表ラベルへ寄せたのは
    //   行見出しを1行削るためだったが、置き場所としては正しくなかった。
    const head = document.createElement('div');
    head.className = 'manual-mana-head small manual-count-label';
    head.id = 'mana-row-head';
    head.textContent = `マナ ${total}枚(表 ${faceUpCount} / 裏 ${faceDownCount}) MP ${seat.mp}`;
    el.appendChild(head);

    const wrap = document.createElement('div');
    wrap.className = 'mana-strips';
    wrap.appendChild(
        createManaStrip(`表 ${faceUpCount}枚`, faceUpCards, seat.id, false, view.backImageId));
    wrap.appendChild(
        createManaStrip(`裏 ${faceDownCount}枚`, faceDownCards, seat.id, true, view.backImageId));

    el.appendChild(wrap);
    registerZoneAnchor(wrap, seat.id, 'MANA');

    for (const card of manaCards) {
        cardLocation.set(card.instanceId, { seatId: seat.id, zone: 'MANA' });
    }

    // ★重ね表示は実測幅で計算するため、DOMに載ってから最後に適用する(2-3)
    applyManaOverlap(wrap);
}

/** マナのストリップ1つ(表 or 裏)。ストリップ全体がドロップ対象(設計書2-3) */
function createManaStrip(label, cards, seatId, faceDown, backImageId) {
    const strip = document.createElement('div');
    strip.className = 'mana-strip' + (faceDown ? ' mana-strip-down' : ' mana-strip-up');

    const label_ = document.createElement('div');
    label_.className = 'mana-strip-label small manual-count-label';
    label_.textContent = label;
    strip.appendChild(label_);

    const track = document.createElement('div');
    track.className = 'mana-strip-track';
    registerDropTarget(track, seatId, 'MANA', null, faceDown);

    for (const card of cards) {
        track.appendChild(createManaTile(card, seatId, backImageId));
    }

    strip.appendChild(track);
    return strip;
}

/**
 * 自席のマナのミニタイル(64×88)。
 *
 * <h3>★Batch 22 2-2: 表向き=文明色の簡略タイル / 裏向き=裏面のカード画像</h3>
 * 20c までは裏向きも灰色の簡略タイルであり、相手上段(裏面画像)と絵が揃っていなかった。
 * 「同じマナが席によって別のものに見える」状態を解消する。
 *
 * ★2-4: 裏向きを絵にしても<b>中身の確認手段は失われない</b>。
 * 総合ルール 2-9 は「裏向きマナの内容は持ち主がいつでも確認できる」と定めるが、
 * 1章の新規約により<b>左クリック1回で拡大</b>でき、拡大表示は常に表面画像を出す
 * (20a 2-4)。従来は右クリックだったので、むしろ確認は速くなっている。
 *
 * ★8章: {@code faceDown} は<b>絵の出し分けにだけ</b>使う。公開範囲を決めるのはゾーンであり
 * (21 設計書 3-4)、対戦部屋で相手の裏マナが漏れないのは
 * サーバがカードを載せないからである(21a 3-3)。描き方とは無関係である。
 */
function createManaTile(card, seatId, backImageId) {
    const chip = document.createElement('div');
    chip.className = 'mana-tile' + (card.tapped ? ' tapped' : '') + (card.faceDown ? ' face-down' : '');
    chip.dataset.instanceId = card.instanceId;
    chip.draggable = true;

    if (card.faceDown) {
        // ★裏面フェイス(Batch 25: 画像をやめた。相手上段と絵は揃ったまま)
        chip.classList.add('mana-tile-back');
        chip.appendChild(cardBackFace());
        chip.title = '裏向き(左クリックで中身を拡大)';
    } else {
        if (card.civilization) {
            applyCivFrame(chip, card.civilization);
        }
        const name = document.createElement('div');
        name.className = 'mana-tile-name';
        name.textContent = card.name || '';
        chip.appendChild(name);
        chip.title = card.name || '';
    }

    if (selected.has(card.instanceId)) {
        chip.classList.add('manual-tile-selected');
    }

    chip.addEventListener('dragstart', (e) => onDragStart(e, card, seatId, 'MANA'));
    // ★22 1-2: 左=拡大 / 右=タップ
    chip.addEventListener('click', (e) => onCardClick(e, card, seatId, 'MANA'));
    chip.addEventListener('contextmenu', (e) => onCardContextMenu(e, card, seatId, 'MANA'));
    return chip;
}

/**
 * マナストリップの重ね表示(設計書2-3)。「はみ出す場合のみ負のマージンを計算して
 * 均等に重ねる」ため、DOMに実際に載せた後の実測幅(clientWidth)を使う。
 * 1枚あたり最小約28pxは露出させる(タイル幅64pxに対し最大重なり36px)。
 */
function applyManaOverlap(wrap) {
    for (const track of wrap.querySelectorAll('.mana-strip-track')) {
        const tiles = [...track.children];
        for (const tile of tiles) {
            tile.style.marginLeft = '';
            tile.style.zIndex = '';
        }
        if (tiles.length <= 1) continue;
        const trackWidth = track.clientWidth;
        const tileWidth = 64;
        const minExposure = 28;
        const naturalWidth = tiles.length * tileWidth;
        if (naturalWidth <= trackWidth) continue;
        const maxOverlap = tileWidth - minExposure;
        const neededOverlapTotal = naturalWidth - trackWidth;
        const perTileOverlap = Math.min(maxOverlap, neededOverlapTotal / (tiles.length - 1));
        tiles.forEach((tile, i) => {
            if (i > 0) tile.style.marginLeft = `-${perTileOverlap}px`;
            tile.style.zIndex = String(i + 1);
        });
    }
}

/** 手札(下段=自席) */
function renderHand(view) {
    const el = document.getElementById('hand-row');
    el.innerHTML = '';
    const seat = bottomSeat(view);
    // ★25c: 行見出し(手札 n枚)は廃止した。薄い文字で読めないという指摘と、
    //   1行ぶんの縦を手札の拡大に回すためである。枚数は右列の宣言ボタン下に出す
    const countLine = document.getElementById('hand-count-line');
    if (countLine) {
        countLine.textContent = `手札 ${zoneCount(seat, 'HAND')}枚(席${seat.id})`;
    }

    const row = document.createElement('div');
    row.className = 'hand-row';
    row.dataset.seat = seat.id;
    row.dataset.zone = 'HAND';
    const cards = seat.zones.HAND || [];
    for (const card of cards) {
        row.appendChild(createHandCard(card, null, seat.id, 'HAND'));
        cardLocation.set(card.instanceId, { seatId: seat.id, zone: 'HAND' });
    }
    registerDropTarget(row, seat.id, 'HAND');
    registerZoneAnchor(row, seat.id, 'HAND');
    el.appendChild(row);
    // ★実測幅で決めるため、DOMに載せてから最後に適用する(マナの重ね表示と同じ手順)
    fitCardWidths(row, handCardMaxWidth(row), 8);
}

/**
 * 手札・センターラインのカード幅の上限(★20c)。
 *
 * ★上限を固定pxではなく<b>ウィンドウの高さ</b>から決める。
 * カードは縦長(幅の約1.4倍)であり、幅を広げるとそのぶん確実に縦を食う。
 * 横幅いっぱいに使う構成にした以上、上限を固定値で置くと
 * 「ワイドだが縦が短い画面」で手札が画面外へ落ちる。制約は縦のほうにあるため、
 * 縦を基準に決めるのが正しい。ミニオンのタイルがCSSで `min(180px, 16vh)` を
 * 上限にしているのと同じ考え方である。
 */
function handCardMaxWidth(row) {
    // ★25c(マスター指示): 手札は青線(区切り)から画面下端までを使い切る。
    //   行の実位置から残り高さを測り、5:7 のアスペクトで幅上限へ換算する。
    //   行がまだレイアウトされていない場合は従来の高さ比の式で近似する。
    const fallback = Math.max(60, Math.min(190, Math.round(window.innerHeight * 0.14)));
    if (!row || !row.isConnected) {
        return fallback;
    }
    const top = row.getBoundingClientRect().top;
    if (!top || top <= 0) {
        return fallback;
    }
    const available = window.innerHeight - top - 12;
    if (available < 84) {
        return fallback;
    }
    return Math.max(60, Math.min(190, Math.floor(available * 5 / 7)));
}
function centerCardMaxWidth() {
    return Math.max(45, Math.min(90, Math.round(window.innerHeight * 0.075)));
}

/**
 * 行に並んだカードの幅を、実測した行幅から決める(★20c)。
 *
 * ウィンドウ幅に追従する構成にしたため、幅は固定値では決められない。
 * 「入るなら上限いっぱいまで大きく、入らないなら詰める」を1本の式で表す。
 * 下限45pxは、それ以下だと画像が何のカードか判別できなくなるためである
 * (下限に張り付いた場合は行が溢れるが、溢れは人間が気づける。判別不能は気づけない)。
 */
function fitCardWidths(row, maxWidth, gap) {
    const cards = [...row.children].filter((el) => el.classList.contains('manual-hand-card'));
    if (cards.length === 0) {
        return;
    }
    const available = row.clientWidth - 8;
    const raw = Math.floor((available - gap * (cards.length - 1)) / cards.length);
    const width = Math.max(45, Math.min(maxWidth, raw));
    for (const card of cards) {
        card.style.width = width + 'px';
    }
}

/**
 * ログ(20b 2-5)。★既定は直近2行ぶんの高さで、クリックすると右列内でその場拡張する。
 * 別ウィンドウやモーダルにしないのは、ログを見ながら盤面を動かす使い方があるためである。
 * 展開は下方向で、宣言ボタン行を押し下げる(確定事項Q6)。
 */
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

/**
 * ★20c: 幅がウィンドウに追従するようになったため、リサイズで描き直す。
 * ミニオンのタイルはCSSのflexで伸縮するので追従は要らないが、手札とセンターラインの
 * カード幅だけはJSが実測して決めているため、ここで作り直す必要がある。
 * 連続発火を抑えるために1フレーム分まとめる。
 */
let resizeTimer = null;
window.addEventListener('resize', () => {
    if (resizeTimer !== null) {
        clearTimeout(resizeTimer);
    }
    resizeTimer = setTimeout(() => {
        resizeTimer = null;
        if (latestView) {
            renderAll(latestView);
        }
    }, 120);
});

document.getElementById('log-box').addEventListener('click', () => {
    const box = document.getElementById('log-box');
    box.classList.toggle('manual-log-collapsed');
    box.scrollTop = box.scrollHeight;
});

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
        applyCivFrame(tile, card.civilization);
        // ★25c(マスター指示): タイルをフェイスと同じ並びにする。
        //   頭=コスト+名前 / 胴=効果テキスト(タグは余白に重なる) /
        //   足=左⚔ATK・中央✎編集・右♥HP。数値は<b>現在値</b>のまま
        //   (印刷値との差分チップも statSpan がそのまま出す)
        tile.classList.add('manual-tile-face');

        const head = document.createElement('div');
        head.className = 'mtf-head';
        if (card.cost !== null && card.cost !== undefined) {
            const cost = document.createElement('span');
            cost.className = 'mtf-cost';
            cost.textContent = card.cost;
            head.appendChild(cost);
        }
        const name = document.createElement('div');
        name.className = 'manual-tile-name';
        name.textContent = card.name;
        head.appendChild(name);
        tile.appendChild(head);

        const body = document.createElement('div');
        body.className = 'mtf-body';
        const text = document.createElement('div');
        text.className = 'mtf-text';
        text.textContent = cardFaceText(card);
        body.appendChild(text);
        tile.appendChild(body);

        // ★22 4-3: ATK/HP は「押せば変えられる」形のまま(中央の✎が編集の入口)
        const foot = document.createElement('div');
        foot.className = 'mtf-foot';
        const atk = document.createElement('span');
        atk.className = 'mtf-atk';
        atk.appendChild(document.createTextNode('⚔'));
        atk.appendChild(statSpan(card.attack, card.printedAttack));
        foot.appendChild(atk);
        const stats = statButton('', () => openStatModal(card));
        stats.classList.add('manual-tile-stats', 'mtf-edit');
        foot.appendChild(stats);
        if (zone !== 'WEAPON' && card.hp !== null && card.hp !== undefined) {
            const hp = document.createElement('span');
            hp.className = 'mtf-hp';
            hp.appendChild(document.createTextNode('♥'));
            hp.appendChild(statSpan(card.hp, card.printedHp));
            foot.appendChild(hp);
        } else {
            foot.appendChild(document.createElement('span'));
        }
        tile.appendChild(foot);

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
    // ★22 1-2: 左=拡大 / 右=タップ(20c までと入れ替わっている)
    tile.addEventListener('click', (e) => onCardClick(e, card, seatId, zone));
    tile.addEventListener('contextmenu', (e) => onCardContextMenu(e, card, seatId, zone));
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

/**
 * リーダータイル(20b 2-2 の合体タイル)。
 *
 * <h3>★Batch 22 3-4: ウェポンを合体させるかどうかは<b>呼び出し側</b>が決める</h3>
 * 自席のウェポンは右列の独立した枠({@link createWeaponSlot})へ移った。
 * 相手上段は 148px の高さ制約(21 設計書4章)があり枠を増やせないので、
 * 合体タイルのままである。つまり<b>席によってウェポンの置き場が違う</b>。
 *
 * ★この関数に「自席かどうか」を判定させてはならない(21b で確立した規約:
 * 描画関数の中に {@code 'A'} / {@code 'B'} を書かない。上下は bottomSeat / topSeat が
 * 決め、席の役割は呼び出し側が知っている)。だから {@code withWeapon} を引数で受ける。
 *
 * <h3>★装備済みでも落とせる(旧・拒否規約の撤回)</h3>
 * 装備の有無でドロップの当たり判定が変わると人間に説明できない。
 * 古いウェポンの後始末はサーバが行う
 * ({@code ManualOperationService.replaceEquippedWeapon})。
 *
 * @param options {withWeapon} true のときだけ WEAPON のドロップ先・アンカー・
 *                ミニタイルを持つ。省略時は false(= ウェポンは別枠にある)
 */
function createLeaderTile(seat, options) {
    const withWeapon = !!(options && options.withWeapon);
    const tile = document.createElement('div');
    tile.className = 'leader-card manual-leader-tile';
    tile.dataset.seat = seat.id;
    if (withWeapon) {
        tile.dataset.zone = 'WEAPON';
        registerDropTarget(tile, seat.id, 'WEAPON');
        // ★21c 7-1: 合体タイルは WEAPON とリーダー(zone == null)の両方のアンカーである
        registerZoneAnchor(tile, seat.id, 'WEAPON');
    }
    // ★サーバは「リーダー」を zone == null で表す(ManualLogPlace の javadoc)。
    //   ウェポンを別枠へ移してもリーダーのアンカーはここに残る(3-5)
    registerZoneAnchor(tile, seat.id, null);
    if (!seat.leader) {
        tile.textContent = '(未読込)';
        if (withWeapon) {
            appendWeaponMini(tile, seat);
        }
        return tile;
    }
    const card = seat.leader;
    tile.dataset.instanceId = card.instanceId;

    // ★20d→25b: リーダーを文明の色で塗る。場のタイルと同じ applyCivFrame を使い、
    //   色と枠の決め方を1箇所に揃える(フェイスと同一パレット)。
    //   突合できていないリーダー(civilization が無い)は既定の灰色のままにする。
    if (card.civilization) {
        applyCivFrame(tile, card.civilization);
    }

    const name = document.createElement('div');
    name.className = 'manual-tile-name';
    name.textContent = card.name || 'リーダー';
    tile.appendChild(name);

    // ★22 4-3: LP は「押せば変えられる」ことが画面に書かれている形にする
    const lp = statButton('LP ' + seat.lp, () => openLpModal(seat.id, seat.lp));
    lp.classList.add('manual-tile-stats');
    tile.appendChild(lp);

    if (card.tapped) {
        tile.classList.add('manual-tile-tapped');
    }
    // ★22 1-2: 左=拡大 / 右=タップ。LPチップは 1-6 の「規約の外にある専用ボタン」であり、
    //   stopPropagation でここへは伝播しない
    tile.addEventListener('click', (e) => {
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
    tile.addEventListener('contextmenu', (e) => {
        e.preventDefault();
        send('tap', { cardIds: [card.instanceId] });
    });
    if (withWeapon) {
        appendWeaponMini(tile, seat);
    }
    return tile;
}

/**
 * 数値チップ(★Batch 22 4-2)。「押せると分かる」形に統一する。
 *
 * ★枠+薄い背景+ホバーで強調+鉛筆(✎)。20a 2-4 で {@code prompt()} をモーダル化した
 * ときに中身は良くなったが、<b>入口の見た目は文字のまま</b>だった。
 * 鉛筆は装飾であり、当たり判定はチップ全体である(小さい的を作らない)。
 *
 * ★4-3: 修正値チップ({@code .manual-stat-chip})はここを通さない。
 * あれは状態の表示であって操作ではなく、ボタン化すると
 * 「押せるもの」と「読むもの」が混ざる。
 */
function statButton(content, onClick) {
    const btn = document.createElement('div');
    btn.className = 'manual-stat-button';
    btn.title = 'クリックで編集';
    if (typeof content === 'string') {
        btn.appendChild(document.createTextNode(content));
    } else {
        btn.appendChild(content);
    }
    const pen = document.createElement('span');
    pen.className = 'manual-stat-pen';
    pen.textContent = '✎';
    pen.setAttribute('aria-hidden', 'true');
    btn.appendChild(pen);
    // ★1-6: 専用ボタンはカード本体のクリック規約の外にある。伝播を必ず止める
    btn.addEventListener('click', (e) => { e.stopPropagation(); onClick(); });
    btn.addEventListener('contextmenu', (e) => { e.preventDefault(); e.stopPropagation(); onClick(); });
    return btn;
}

/**
 * 装備中のウェポンを、リーダータイル右下に重なるミニタイル(44×60)として描く。
 *
 * <h3>★ミニタイルに載せるのは画像と枚数バッジだけである(マスター確認済み)</h3>
 * 44×60 に ATK・使用済・札をすべて詰めると、どれも押しにくい当たり判定になる。
 * 数値・使用済・札の編集は {@link openWeaponModal} に集約した。
 * なおウェポンに進化は無いため、進化バッジは持たない。
 *
 * <h3>★2枚以上は「異常が見える」形にする</h3>
 * ウェポン1枚はゲームルールである(総合ルール 2-2)。ただし手動モードは判断を実装しないため
 * ゾーンに2枚以上入ること自体は妨げず、枚数バッジを出して人間が気づけるようにする。
 * 表示するのは先頭(最前面)の1枚である。
 */
function appendWeaponMini(tile, seat) {
    const weapons = seat.zones.WEAPON || [];
    if (weapons.length === 0) {
        return;
    }
    const card = weapons[0];
    const mini = document.createElement('div');
    mini.className = 'manual-weapon-mini';
    mini.dataset.instanceId = card.instanceId;
    mini.draggable = true;
    mini.title = `${card.name || 'ウェポン'} / クリック=拡大 ダブルクリック=編集`;

    if (!card.faceDown) {
        mini.appendChild(cardFace(card, 'micro'));
    } else {
        mini.appendChild(cardBackFace());
    }
    if (weapons.length > 1) {
        const badge = document.createElement('div');
        badge.className = 'manual-weapon-mini-badge';
        badge.textContent = weapons.length;
        mini.appendChild(badge);
    }
    if (selected.has(card.instanceId)) {
        mini.classList.add('manual-tile-selected');
    }

    // ★リーダー本体のクリック(タップ)・LP・ドロップと衝突させないため、すべて伝播を止める
    mini.addEventListener('click', (e) => { e.stopPropagation(); setZoom(card); });
    mini.addEventListener('dblclick', (e) => { e.stopPropagation(); openWeaponModal(card); });
    mini.addEventListener('contextmenu', (e) => {
        e.preventDefault();
        e.stopPropagation();
        openWeaponModal(card);
    });
    mini.addEventListener('dragstart', (e) => {
        e.stopPropagation();
        onDragStart(e, card, seat.id, 'WEAPON');
    });
    tile.appendChild(mini);
    cardLocation.set(card.instanceId, { seatId: seat.id, zone: 'WEAPON' });
}

/**
 * 手札・センターラインのカード。画像を使う(設計書 4-1)。
 *
 * ★20b: センターライン(共有ゾーン)からも使うため、席とゾーンを引数で受け取る。
 * 共有ゾーンでは seatId が null になる。
 */
function createHandCard(card, width, seatId, zoneName) {
    // ★21b: 席を省略した呼び出しは無い。既定を 'A' に倒すと視点が固定されるため null にする
    const seat = seatId === undefined ? null : seatId;
    const zone = zoneName === undefined ? 'HAND' : zoneName;
    const wrap = document.createElement('div');
    wrap.className = 'manual-hand-card';
    // ★20c: width は null で呼ばれる。実際の幅は fitCardWidths が DOM 挿入後に決める
    if (width !== null && width !== undefined) {
        wrap.style.width = width + 'px';
    }
    wrap.dataset.instanceId = card.instanceId;
    wrap.draggable = true;

    if (!card.faceDown) {
        wrap.appendChild(cardFace(card, 'full'));
    } else {
        wrap.appendChild(cardBackFace());
    }
    if (selected.has(card.instanceId)) {
        wrap.classList.add('manual-tile-selected');
    }

    wrap.addEventListener('dragstart', (e) => onDragStart(e, card, seat, zone));
    // ★22 1-5: 手札・共有ゾーンはタップできない。無反応にせず右も拡大を返す
    wrap.addEventListener('click', (e) => onCardClick(e, card, seat, zone));
    wrap.addEventListener('contextmenu', (e) => onCardContextMenu(e, card, seat, zone));
    return wrap;
}

// ---------------------------------------------------------------
// 6) クリック規約(★Batch 22 1章で入れ替えた)
// ---------------------------------------------------------------

/**
 * ★Batch 22 1-1: 新しい原則。
 *
 * <pre>
 *   左クリック = 見る   (拡大表示)
 *   右クリック = 動かす (タップ / 一覧を開く)
 * </pre>
 *
 * 20c までは逆であった。カードゲームで最も回数が多い操作は「見る」であり
 * (効果テキストを読み直す・相手が何を出したか確かめる・裏向きマナの中身を思い出す)、
 * 回数の多い操作を主ボタンに置くのが素直である。
 *
 * ★1-4: 入れ替えるのは<b>素のクリックだけ</b>である。修飾キー付きは左のまま
 * (Shift+左=表裏 / Ctrl,⌘+左=複数選択)。修飾キーを右へ移すと
 * 「Shift を押しながら右クリック」という覚えにくい操作が生まれる。
 *
 * ★1-7: ゾーンを見る必要が無くなった。分岐は右クリック側
 * ({@link onCardContextMenu})が引き受ける。
 */
function onCardClick(e, card, seatId, zone) {
    if (e.shiftKey) {
        send('flip', { cardIds: [card.instanceId] });
        return;
    }
    if (e.ctrlKey || e.metaKey) {
        toggleSelect(card.instanceId);
        return;
    }
    setZoom(card);
}

/**
 * 素の右クリック(★Batch 22 1-2)。タップできるゾーンならタップ、それ以外は拡大。
 *
 * ★1-5「押しても何も起きない」を作らない。手札・センターライン・帯の中のカードには
 * タップの概念が無いが、無反応にはせず拡大を返す。規約の一貫性より
 * <b>押した結果が必ず返ること</b>を優先する(21 設計書 3-5 と同じ考え方)。
 *
 * ★1-7: {@code e.preventDefault()} は呼び出し側ではなくここで必ず行う。
 * 忘れるとタップのたびにブラウザのコンテキストメニューが出る。
 */
function onCardContextMenu(e, card, seatId, zone) {
    e.preventDefault();
    if (zone === 'HAND' || SHARED_ZONES.has(zone)) {
        setZoom(card);
        return;
    }
    send('tap', { cardIds: [card.instanceId] });
}

/**
 * パイル・チップの左クリック(★Batch 22 1-2)。一番上(または先頭)のカードを拡大する。
 *
 * ★中身が届いていないゾーンでは拡大もできない。21c 3-5 と同じ明滅+トーストを返す
 * (「空である」と「見えない」を同じ表示にしない)。判定材料は {@code zones} に
 * キーがあるかだけであり、公開範囲の定義をクライアントへ写さない。
 *
 * @param fromHead true なら配列の先頭が最上段(山札・チップ)、
 *                 false なら末尾が最上段(公開パイル。20a 2-1)
 */
function zoomTopOrDeny(el, seatView, zoneName, fromHead) {
    if (!isZoneVisible(seatView, zoneName)) {
        flashDenied(el);
        showToast(`${ZONE_LABELS[zoneName]}は非公開`);
        return;
    }
    const cards = seatView.zones[zoneName] || [];
    if (cards.length === 0) {
        showTransientNotice(ZONE_LABELS[zoneName] + 'は空です');
        return;
    }
    setZoom(fromHead ? cards[0] : cards[cards.length - 1]);
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
    renderZoom(card);
}

/**
 * ★2-4: 拡大表示は常に表面画像を出し、裏向きなら「裏向き」バッジを重ねる。
 * フェイズ1は全公開であり、持ち主が自分の裏向きマナ・山札上のカードを確認できる
 * べきである(裏面画像では確認の用をなさなかった)。フェイズ2では相手の非公開カードの
 * imageId 自体がビューに載らない設計(11-3)のため、この変更が情報漏えいの経路にはならない。
 */
function renderZoom(card) {
    const panel = document.getElementById('zoom-panel');
    panel.innerHTML = '';
    // ★裏向きでも表面フェイス+バッジ(拡大は「中身の確認」のための操作である)
    panel.appendChild(cardFace(card, 'large'));
    if (card.faceDown) {
        const badge = document.createElement('div');
        badge.className = 'manual-facedown-badge';
        badge.textContent = '裏向き';
        panel.appendChild(badge);
    }
}

// ---------------------------------------------------------------
// 8) ドラッグ&ドロップ(設計書 4-5)
// ---------------------------------------------------------------

/**
 * ★2-5(バグ修正): 帯を開いている間は manual-band-backdrop(全画面・z-index 1040)が
 * dragover/drop を遮断し、帯から盤面へのドラッグが機能していなかった。
 * <body> に manual-drag-active を付け、帯・バックドロップの pointer-events を切ることで
 * 着地点を復活させる。
 *
 * ★hotfix2: このクラス付与は dragstart ハンドラ内で同期的に行ってはならない。
 * `.manual-band` はドラッグ元カードの祖先であり、dragstart の処理中にその
 * pointer-events を変えると、Chromium はドラッグ操作自体を中断する
 * (dragstart の直後に dragend が来て、dragover も drop も一切発火しない)。
 * setTimeout(0) でドラッグ確立後まで遅らせることで、帯の背後(B席行など)への
 * ドロップも含めて正しく動作する。検証の詳細は batch19b-hotfix2-notes.md を参照。
 *
 * dragend は document で受ける(ドラッグ元が内側の <img> になる場合でも確実に届く)。
 * 付与前に dragend が来た場合に備え、clearTimeout で取り消す。
 */
function onDragStart(e, card, seatId, zone) {
    // ★21b: 対戦部屋の観戦者は全操作が不可である(6-1)。サーバは既に棄却するが、
    //   掴めてしまうと毎回エラーのトーストが出るだけになる。掴ませない側で止める。
    //   ★これは「判断」ではなく操作権限であり、検証はサーバが行う(設計判断27)。
    if (isSpectatorViewer()) {
        e.preventDefault();
        showTransientNotice('観戦中は盤面を操作できません');
        return;
    }
    // ★23 7-1: 開始シーケンス中は掴ませない。★これも操作補助にすぎず、
    //   実際に棄却しているのはサーバ(ManualPermissions.denyDuringStart)である。
    //   掴めてしまうと落とすたびにエラーのトーストが出るだけになるので、手前で止める。
    if (isStartLocked()) {
        e.preventDefault();
        showTransientNotice('ゲーム開始の手続き中は盤面を操作できません');
        return;
    }
    let ids;
    if (selected.has(card.instanceId) && selected.size > 1) {
        ids = [...selected];
    } else {
        ids = [card.instanceId];
    }
    e.dataTransfer.setData('text/plain', JSON.stringify({ cardIds: ids, seatId, zone }));
    e.dataTransfer.effectAllowed = 'move';

    // ★21c 7-2: ドラッグの開始を相手・観戦者へ知らせる(揮発)
    sendDragCueStart(card.instanceId);

    const timer = setTimeout(() => {
        document.body.classList.add('manual-drag-active');
    }, 0);
    document.addEventListener('dragend', () => {
        clearTimeout(timer);
        document.body.classList.remove('manual-drag-active');
        // ★drop の後にも dragend は必ず来る。消去をここ1箇所に集約する(7-2)
        sendDragCueEnd();
    }, { once: true });
}

/**
 * ドロップ先を1箇所登録する。
 * targetCard が渡された場合、そのタイルの上に落とすことになる(占有中の FIELD なら進化)。
 *
 * ★20b: 「装備済みのウェポン枠には落とせない」拒否規約は撤回した。
 * リーダータイル自体がウェポンのドロップ先になり、装備の有無で当たり判定が変わると
 * 人間に説明できないためである。古いウェポンの後始末はサーバが引き受ける。
 *
 * ★seatId は共有ゾーン(PLAY / REVEAL)のとき null を渡す。サーバは共有ゾーンへの移動で
 * toSeat を無視するため(20b 3-2)、クライアント側で席を作り出す必要は無い。
 */
function registerDropTarget(el, seatId, zoneName, toIndex, faceDown, targetCard) {
    el.addEventListener('dragover', (e) => {
        e.preventDefault();
        el.classList.add('manual-drop-hover');
        // ★21c 7-2: ホバー先が変わったときだけ送る(100msスロットルは送信側が持つ)。
        //   dragover は毎フレーム飛ぶため、ここで間引かないと通信量が跳ね上がる
        sendDragCueHover(seatId === undefined ? null : seatId, zoneName);
    });
    el.addEventListener('dragleave', () => {
        el.classList.remove('manual-drop-hover', 'manual-drop-reject');
    });
    el.addEventListener('drop', (e) => {
        e.preventDefault();
        // ★ドロップ対象の入れ子(山札パイル本体の中に「上へ/下へ」枠が別のドロップ対象として
        //   登録されている)でイベントが祖先まで伝播すると、1回のドロップで内側・外側
        //   両方のハンドラが発火し、moveが2回送信されてしまう(実マウス検証で発覚)。
        //   受け取った側で伝播を止め、最も内側の対象だけが処理する。
        e.stopPropagation();
        el.classList.remove('manual-drop-hover');
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
            toSeat: seatId === undefined ? null : seatId,
            toZone: zoneName,
            toIndex: toIndex === undefined ? null : toIndex,
            faceDown: faceDown === undefined ? null : faceDown,
        });
        selected.clear();
    });
}

// ---------------------------------------------------------------
// 8-2) ドラッグ軌跡の矢印(★Batch 21c。設計書 7章)
// ---------------------------------------------------------------
//
// ★★画素座標を中継しない(7-1)。ウィンドウ幅・カードの伸縮(20c)・上下反転(3-2)で
//   座標系は閲覧者ごとに違う。送るのは「ドラッグ元のゾーン+カード」と
//   「ホバー中のドロップ先ゾーン」という<b>論理アンカー</b>だけであり、
//   受信側が自分のレイアウト上の要素を引いて、その中心同士を結ぶ。
//
// ★★ログ・履歴・Undo に一切触れない揮発経路である(7-2)。
//   ドラッグ中の動きは「起きたこと」ではない。途中で手を離せば何も起きていない。
//
// ★視点フィルタはサーバの責務である(7-3)。見えないカードの識別子は
//   そもそも届かず、そのとき矢印の根はゾーンのアンカーになる。
//   クライアントに「受け取ったけど描かない」を任せると DevTools で中身が読める。

const SVG_NS = 'http://www.w3.org/2000/svg';

/** ホバー先の送信間隔(7-2)。 */
const CUE_THROTTLE_MS = 100;

/** 消し損ね対策の自動消去(7-2)。最終更新からこの時間で消す。 */
const CUE_TTL_MS = 5000;

/** 表示中の矢印。鍵は掴んでいる人の席(同時に2人が掴んでも混ざらない) */
const dragCues = new Map();

let cueDragCardId = null;
let cueDragging = false;
let cueHoverKey = null;
let cueHoverTimer = null;
let cueLastSentAt = 0;
let cueHighlighted = [];

/** ゾーン → アンカー要素の対応表の鍵。★共有ゾーンは席 null、リーダーはゾーン null である */
function anchorKey(seatId, zoneName) {
    return `${seatId || '-'}|${zoneName || 'LEADER'}`;
}

/**
 * アンカーを登録する(★7-1 の「ゾーンとアンカー要素の対応表」)。
 * ★描画関数が自分の作った要素をその場で登録する形にしてある。
 * 別途セレクタの一覧を持つと、レイアウトを変えたときに片方だけ直して壊れる。
 * 4章の相手上段の再構成で全ゾーンが要素を持つようになったため、
 * どの閲覧者の画面でも必ず引ける。
 */
function registerZoneAnchor(el, seatId, zoneName) {
    zoneAnchors.set(anchorKey(seatId, zoneName), el);
}

function anchorElement(place) {
    if (!place) return null;
    return zoneAnchors.get(anchorKey(place.seatId, place.zone)) || null;
}

// ---- 送信 ----

function sendDragCueStart(cardId) {
    cueDragging = true;
    cueDragCardId = cardId;
    cueHoverKey = null;
    cueLastSentAt = Date.now();
    send('dragcue', { cardId, toSeat: null, toZone: null, active: true });
}

function sendDragCueHover(seatId, zoneName) {
    if (!cueDragging) return;
    const key = anchorKey(seatId, zoneName);
    if (key === cueHoverKey) return;
    cueHoverKey = key;
    if (cueHoverTimer !== null) {
        clearTimeout(cueHoverTimer);
    }
    // ★スロットル。直前の送信から 100ms 経っていれば即送り、経っていなければ待つ
    const wait = Math.max(0, CUE_THROTTLE_MS - (Date.now() - cueLastSentAt));
    cueHoverTimer = setTimeout(() => {
        cueHoverTimer = null;
        if (!cueDragging) return;
        cueLastSentAt = Date.now();
        send('dragcue', {
            cardId: cueDragCardId, toSeat: seatId, toZone: zoneName, active: true,
        });
    }, wait);
}

function sendDragCueEnd() {
    if (!cueDragging) return;
    cueDragging = false;
    cueDragCardId = null;
    cueHoverKey = null;
    if (cueHoverTimer !== null) {
        clearTimeout(cueHoverTimer);
        cueHoverTimer = null;
    }
    send('dragcue', { cardId: null, toSeat: null, toZone: null, active: false });
}

// ---- 受信・描画 ----

/** サーバから届いた矢印1本を反映する。★盤面の再描画は起こさない(7-2) */
function applyDragCue(cue) {
    if (!cue) return;
    const key = cue.actorSeat || '-';
    if (cue.active) {
        dragCues.set(key, { cue, at: Date.now() });
    } else {
        dragCues.delete(key);
    }
    renderDragCues();
}

/** 消し損ね対策(7-2)。切断・タブ閉じで active:false が届かなくても必ず消える */
setInterval(() => {
    if (dragCues.size === 0) return;
    const now = Date.now();
    let changed = false;
    for (const [key, entry] of [...dragCues]) {
        if (now - entry.at > CUE_TTL_MS) {
            dragCues.delete(key);
            changed = true;
        }
    }
    if (changed) renderDragCues();
}, 1000);

function cueLayer() {
    let svg = document.getElementById('manual-cue-layer');
    if (svg) return svg;
    svg = document.createElementNS(SVG_NS, 'svg');
    svg.id = 'manual-cue-layer';
    svg.setAttribute('class', 'manual-cue-layer');
    const defs = document.createElementNS(SVG_NS, 'defs');
    const marker = document.createElementNS(SVG_NS, 'marker');
    marker.setAttribute('id', 'manual-cue-head');
    marker.setAttribute('viewBox', '0 0 10 10');
    marker.setAttribute('refX', '9');
    marker.setAttribute('refY', '5');
    marker.setAttribute('markerWidth', '6');
    marker.setAttribute('markerHeight', '6');
    marker.setAttribute('orient', 'auto-start-reverse');
    const head = document.createElementNS(SVG_NS, 'path');
    head.setAttribute('d', 'M0,0 L10,5 L0,10 z');
    head.setAttribute('fill', '#ffca28');
    marker.appendChild(head);
    defs.appendChild(marker);
    svg.appendChild(defs);
    document.body.appendChild(svg);
    return svg;
}

function centerOf(el) {
    const rect = el.getBoundingClientRect();
    return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
}

/**
 * 矢印の根になる要素。
 * ★カード識別子が届いていればその1枚を、届いていなければゾーンのアンカーを使う(7-3)。
 * 届かないのは「その閲覧者に見えないカード」のときであり、
 * 根がゾーン全体になることで<b>どの1枚かは漏れない</b>。
 */
function cueSourceElement(cue) {
    if (cue.cardId) {
        const el = document.querySelector(`[data-instance-id="${cue.cardId}"]`);
        if (el) return el;
    }
    return anchorElement(cue.from);
}

function renderDragCues() {
    for (const el of cueHighlighted) {
        el.classList.remove('manual-cue-from', 'manual-cue-to');
    }
    cueHighlighted = [];
    const svg = cueLayer();
    for (const node of [...svg.querySelectorAll('.manual-cue-line')]) {
        node.remove();
    }
    if (dragCues.size === 0) {
        svg.classList.add('d-none');
        return;
    }
    svg.classList.remove('d-none');
    svg.setAttribute('width', window.innerWidth);
    svg.setAttribute('height', window.innerHeight);
    svg.setAttribute('viewBox', `0 0 ${window.innerWidth} ${window.innerHeight}`);
    for (const entry of dragCues.values()) {
        drawDragCue(svg, entry.cue);
    }
}

/**
 * 矢印1本。★根と先を軽くハイライトし、その中心同士を半透明の線で結ぶ(7-4)。
 * ドロップ先がまだ決まっていない(どこにも重なっていない)ときは、
 * 根のハイライトだけを出す。線を引く先が無いためである。
 */
function drawDragCue(svg, cue) {
    const fromEl = cueSourceElement(cue);
    if (!fromEl) return;
    fromEl.classList.add('manual-cue-from');
    cueHighlighted.push(fromEl);

    const toEl = anchorElement(cue.to);
    if (!toEl) return;
    toEl.classList.add('manual-cue-to');
    cueHighlighted.push(toEl);

    const a = centerOf(fromEl);
    const b = centerOf(toEl);
    const line = document.createElementNS(SVG_NS, 'line');
    line.setAttribute('class', 'manual-cue-line');
    line.setAttribute('x1', a.x);
    line.setAttribute('y1', a.y);
    line.setAttribute('x2', b.x);
    line.setAttribute('y2', b.y);
    line.setAttribute('marker-end', 'url(#manual-cue-head)');
    line.dataset.actor = cue.actorSeat || '';
    svg.appendChild(line);

    // ★誰が動かしているかを添える。3人以上いる部屋で矢印が2本出たときに見分けられる
    if (cue.actorName) {
        const text = document.createElementNS(SVG_NS, 'text');
        text.setAttribute('class', 'manual-cue-line manual-cue-label');
        text.setAttribute('x', (a.x + b.x) / 2);
        text.setAttribute('y', (a.y + b.y) / 2 - 4);
        text.textContent = cue.actorName;
        svg.appendChild(text);
    }
}

// ---------------------------------------------------------------
// 9) 数値編集モーダル(5-3の3・4)
// ---------------------------------------------------------------

function openStatModal(card) {
    const modal = document.getElementById('stat-modal');
    const fields = document.getElementById('stat-modal-fields');
    document.getElementById('stat-modal-title').textContent = card.name + ' の数値';
    fields.innerHTML = '';

    // ★22 4-4: LPモーダルにあって数値モーダルに無いのは非対称である(20a 2-4 が
    //   LP を直したときの理由がそのまま当てはまる)。刻みが LP(-5/-1/+1/+5)より
    //   小さいのは、ミニオンの数値が5単位で動くことが稀なためである。
    fields.appendChild(statInput('ATK', card.attack, (value) => {
        send('stat', { cardId: card.instanceId, attack: value });
    }, true));
    if (card.hp !== null && card.hp !== undefined) {
        fields.appendChild(statInput('HP', card.hp, (value) => {
            send('stat', { cardId: card.instanceId, hp: value });
        }, true));
    }

    document.getElementById('stat-modal-reset').onclick = () => {
        send('stat-reset', { cardId: card.instanceId });
        modal.classList.add('d-none');
    };
    document.getElementById('stat-modal-close').onclick = () => modal.classList.add('d-none');
    modal.classList.remove('d-none');
}

// ---------------------------------------------------------------
// 9-2) LPモーダル(設計書 Batch20a 2-4)
// ---------------------------------------------------------------

/**
 * ★ATK/HPは type="number" のスピナーで既にクリック増減できていたが、LPだけが
 * prompt() による打ち直ししかできなかった(この非対称が要望の正体)。statInput を
 * そのまま流用してスピナーを得たうえで、まとまった値が動きやすいLPのために
 * -5/-1/+1/+5 のボタンも添える。ボタンは押しても閉じない(連続クリックが主眼)。
 */
function openLpModal(seatId, currentLp) {
    lpModalSeatId = seatId;
    const modal = document.getElementById('lp-modal');
    const fields = document.getElementById('lp-modal-fields');
    document.getElementById('lp-modal-title').textContent = '席' + seatId + ' のLP';
    fields.innerHTML = '';
    fields.appendChild(statInput('LP', currentLp, (value) => {
        send('lp', { seat: seatId, value });
    }));

    const delta = (amount) => send('lp', { seat: seatId, delta: amount });
    document.getElementById('lp-modal-minus5').onclick = () => delta(-5);
    document.getElementById('lp-modal-minus1').onclick = () => delta(-1);
    document.getElementById('lp-modal-plus1').onclick = () => delta(1);
    document.getElementById('lp-modal-plus5').onclick = () => delta(5);
    document.getElementById('lp-modal-close').onclick = () => {
        modal.classList.add('d-none');
        lpModalSeatId = null;
    };
    modal.classList.remove('d-none');
}

/** モーダルが開いている間、再配信されたビューの数値を差し替える。専用の再描画経路は作らない */
function refreshLpModal(view) {
    if (!lpModalSeatId) {
        return;
    }
    const modal = document.getElementById('lp-modal');
    if (modal.classList.contains('d-none')) {
        lpModalSeatId = null;
        return;
    }
    const seat = seatOf(view, lpModalSeatId);
    const input = document.querySelector('#lp-modal-fields input');
    if (input && document.activeElement !== input) {
        input.value = seat.lp;
    }
}

// ---------------------------------------------------------------
// 9-3) ウェポン操作モーダル(設計書 Batch20b 2-2)
// ---------------------------------------------------------------

/**
 * ★合体タイルのミニタイルは44×60しかなく、ATK・使用済・札を載せる余地が無い。
 * 操作をここへ集約し、ミニタイルは画像と枚数バッジだけに保つ(マスター確認済み)。
 * ATK入力欄は statInput をそのまま流用するため、ミニオンの数値編集と同じ
 * スピナーによるクリック増減が得られる。
 */
function openWeaponModal(card) {
    weaponModalCardId = card.instanceId;
    const modal = document.getElementById('weapon-modal');
    document.getElementById('weapon-modal-title').textContent =
        (card.name || 'ウェポン') + ' の操作';

    const fields = document.getElementById('weapon-modal-fields');
    fields.innerHTML = '';
    // ★22 4-4: ミニオンの数値モーダルと同じく ±1 を添える(体裁を揃える)
    fields.appendChild(statInput('ATK', card.attack, (value) => {
        send('stat', { cardId: card.instanceId, attack: value });
    }, true));

    const actions = document.getElementById('weapon-modal-actions');
    actions.innerHTML = '';
    const usedBtn = document.createElement('button');
    usedBtn.type = 'button';
    usedBtn.className = 'btn btn-sm ' + (card.used ? 'btn-warning' : 'btn-outline-light');
    usedBtn.textContent = card.used ? '使用済' : '未使用';
    usedBtn.addEventListener('click', () => send('used', { cardIds: [card.instanceId] }));
    actions.appendChild(usedBtn);

    const labelBtn = document.createElement('button');
    labelBtn.type = 'button';
    labelBtn.className = 'btn btn-sm btn-outline-light';
    labelBtn.textContent = '札を追加';
    labelBtn.addEventListener('click', () => openLabelModal(card));
    actions.appendChild(labelBtn);

    const labels = document.getElementById('weapon-modal-labels');
    labels.innerHTML = '';
    for (const label of (card.labels || [])) {
        const chip = document.createElement('span');
        chip.className = 'manual-label-chip';
        chip.textContent = label + ' ×';
        chip.addEventListener('click', () => send('label-remove', { cardId: card.instanceId, label }));
        labels.appendChild(chip);
    }

    document.getElementById('weapon-modal-reset').onclick = () => {
        send('stat-reset', { cardId: card.instanceId });
    };
    document.getElementById('weapon-modal-close').onclick = () => {
        modal.classList.add('d-none');
        weaponModalCardId = null;
    };
    modal.classList.remove('d-none');
}

/**
 * 再配信のたびにモーダルの中身を組み直す(LPモーダルと同じ考え方)。
 * ★使用済トグルや札の付け外しは押しても閉じない設計であり、押した結果が
 * その場で反映されないと、押せたのかどうかが分からなくなる。
 */
function refreshWeaponModal(view) {
    if (!weaponModalCardId) {
        return;
    }
    const modal = document.getElementById('weapon-modal');
    if (modal.classList.contains('d-none')) {
        weaponModalCardId = null;
        return;
    }
    const card = findCardByInstanceId(view.seatA.zones.WEAPON, weaponModalCardId)
        || findCardByInstanceId(view.seatB.zones.WEAPON, weaponModalCardId);
    if (!card) {
        // ★付け替えなどでウェポン枠から居なくなった。開いたままにする意味が無い
        modal.classList.add('d-none');
        weaponModalCardId = null;
        return;
    }
    openWeaponModal(card);
}

/**
 * 数値の入力欄。
 *
 * @param withDelta ★Batch 22 4-4。true のとき -1 / +1 のボタンを添える。
 *   サーバの {@code stat} は差分ではなく<b>絶対値</b>を受ける操作なので、ボタンは
 *   入力欄の現在値を ±1 して確定させる形にした。こうしておくと、押した結果が
 *   入力欄にもその場で出て「押せたのかどうか」が分かる(20b 2-2 と同じ理由)。
 *   サーバに差分の経路を足す案は退けた — このバッチは Java に触らない(X1)し、
 *   同じ操作に絶対値と差分の2経路ができると、どちらが正かが曖昧になる。
 */
function statInput(labelText, value, onCommit, withDelta) {
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

    if (withDelta) {
        const row = document.createElement('div');
        row.className = 'd-flex gap-1 mt-1 manual-stat-delta';
        for (const amount of [-1, 1]) {
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'btn btn-sm btn-outline-light py-0 px-1';
            btn.textContent = amount > 0 ? '+1' : '-1';
            btn.addEventListener('click', () => {
                const current = input.value === '' || isNaN(Number(input.value))
                    ? 0 : Number(input.value);
                const next = current + amount;
                input.value = next;
                onCommit(next);
            });
            row.appendChild(btn);
        }
        wrap.appendChild(row);
    }
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
// 11) ヘッダ操作(Undo/Redo・デッキ読み込み・宣言・メモ)
// ---------------------------------------------------------------
//
// ★20b 2-1: ターン数・フェイズの送信(turn / phase)はUIごと削除した。
//   サーバ側のハンドラとサービスメソッドは残してある。フェイズ2の対戦モードで
//   必要になったときに、UIだけを再設計して復活させられるようにするためである。

document.getElementById('btn-undo').addEventListener('click', () => send('undo', {}));
document.getElementById('btn-redo').addEventListener('click', () => send('redo', {}));

document.getElementById('btn-reset').addEventListener('click', () => {
    if (confirm('リセットして引き直す。よろしいですか?')) {
        send('reset', {});
    }
});

document.getElementById('btn-leave').addEventListener('click', () => {
    if (confirm('退室する。よろしいですか?')) {
        send('leave', {});
        forgetOccupant();
        location.href = '/';
    }
});

/**
 * 席を立つ / 席に着く(★21b 設計書 2-2)。
 *
 * ★A⇔Bの直接交換は作らない。座り直したいなら一度立つ。
 * 観戦を許可しない部屋では降りる先が無いため、席を立つ = 退室である。
 * この分岐はサーバ側(ManualWsController#seat)にもあり、こちらは
 * 「退室したのに盤面ページに留まる」を避けるための後始末にすぎない。
 */
document.getElementById('btn-seat').addEventListener('click', () => {
    if (!latestView) return;
    if (!latestView.viewerSeat) {
        openSeatChangeGate();
        return;
    }
    if (latestView.spectatorAllowed) {
        if (!confirm('席を立って観戦に移ります。よろしいですか?')) return;
        send('seat', { seat: null });
        return;
    }
    if (!confirm('この部屋は観戦できないため、席を立つと退室になります。よろしいですか?')) return;
    send('seat', { seat: null });
    forgetOccupant();
    location.href = '/';
});

/**
 * 観戦の視点切替(★21c 3-2)。★サーバへ送る。
 * サーバが以後のビューとログのフィルタを変え、その時点の全文を送り直す(5-5)。
 * クライアントは結果のビューを描くだけであり、自分では何も隠さない。
 */
document.getElementById('btn-spectator-view').addEventListener('click', () => {
    if (!latestView) return;
    const current = latestView.spectatorView || 'PUBLIC_ONLY';
    send('viewpoint', { spectatorView: current === 'ALL' ? 'PUBLIC_ONLY' : 'ALL' });
});

/**
 * 上下反転(★21c 3-2)。★サーバへは送らない。
 * どちらの席を下に置くかは見る側の都合であり、盤面の事実ではない(10章)。
 * 反映は `bottomSeatId()` が1つ条件を見るだけで済む。
 */
document.getElementById('btn-flip').addEventListener('click', () => {
    boardFlipped = !boardFlipped;
    if (latestView) renderAll(latestView);
});

/**
 * ゲームを始める(★23 6-1)。★21c の [先攻決め] は廃止した(3-4)。
 * 先攻を決める経路は1本でなければならない。押した後の段取りはサーバの状態機械が進める。
 */
document.getElementById('btn-start').addEventListener('click', () => {
    send('start-begin', {});
});

// 在室者ポップオーバー(2-3)。チップ列のクリックで開閉する
document.getElementById('occupant-list').addEventListener('click', () => {
    document.getElementById('occupant-popover').classList.toggle('d-none');
});
document.getElementById('occupant-popover-close').addEventListener('click', () => {
    document.getElementById('occupant-popover').classList.add('d-none');
});

// ★2-6: 操作説明モーダル
document.getElementById('btn-help').addEventListener('click', () => {
    document.getElementById('help-modal').classList.remove('d-none');
});
document.getElementById('help-modal-close').addEventListener('click', () => {
    document.getElementById('help-modal').classList.add('d-none');
});

document.getElementById('note-form').addEventListener('submit', (e) => {
    e.preventDefault();
    const input = document.getElementById('note-input');
    if (input.value.trim() !== '') {
        send('note', { text: input.value.trim() });
        input.value = '';
    }
});

/**
 * 宣言の対象席。★21b: 自席から決める(6-3・D4「宣言は自席のぶんのみ」)。
 * 20c までは 'A' 固定だった。renderHeader が毎回ビューの viewerSeat を入れ直す。
 * 席に着いていない観戦者は null になり、押しても何も送らない(サーバも弾く)。
 */
let declareSeat = null;
document.getElementById('declare-buttons').addEventListener('click', (e) => {
    const btn = e.target.closest('button[data-declaration]');
    if (!btn) return;
    if (!declareSeat) {
        showTransientNotice('席に着いていないため宣言できません');
        return;
    }
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
    } else if (activeOverlay.kind === 'mulligan') {
        // ★23 4-3: 開閉を決めるのは renderStartUi 側。ここは「開いていれば描き直す」だけ
        renderMulliganOverlay();
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
    // ★20b: 共有ゾーンは席ではなく view.shared から読む(3-2)。
    //   センターラインが常に中身を見せているため通常は開かないが、
    //   ドロップ先として使われる以上、帯からも到達できる状態を保っておく。
    const items = SHARED_ZONES.has(activeOverlay.zoneName)
        ? ((latestView.shared || {})[activeOverlay.zoneName] || [])
        : (seatOf(latestView, activeOverlay.seatId).zones[activeOverlay.zoneName] || []);
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
    const seatView = seatOf(latestView, activeOverlay.seatId);
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

    if (!card.faceDown) {
        wrap.appendChild(cardFace(card, 'mini'));
    } else {
        wrap.appendChild(cardBackFace());
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
    const seatView = seatOf(latestView, activeOverlay.seatId);
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
            note.textContent = '検索中は並べ替えを無効にします(ボタンでの移動はすべて使えます)'; // ★20a: 10個に増えたため列挙をやめた
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

    if (!card.faceDown) {
        row.appendChild(cardFace(card, 'micro'));
    } else {
        row.appendChild(cardBackFace());
    }

    const name = document.createElement('div');
    name.className = 'manual-deck-row-name';
    name.textContent = card.name || '(不明)';
    row.appendChild(name);

    // ★20a 2-2(B1・B2): 6つ追加して計10個。段組みは決め打ちせず、
    //   1つの flex-wrap 入れ物に並べた順で自然に折り返す(将来ゾーンが増えても崩れない)。
    //   マナの2つだけ faceDown を明示する(表=false / 裏=true)。残りは null を送り、
    //   表裏は ManualOperationService.move の正規化(2-3)に委ねる。
    const btns = document.createElement('div');
    btns.className = 'manual-deck-row-buttons';
    btns.appendChild(deckRowButton('一番上へ', () => sendDeckMove(card.instanceId, seatId, 'DECK', 0)));
    btns.appendChild(deckRowButton('一番下へ', () => sendDeckMove(card.instanceId, seatId, 'DECK', 999999)));
    btns.appendChild(deckRowButton('手札へ', () => sendDeckMove(card.instanceId, seatId, 'HAND', null)));
    btns.appendChild(deckRowButton('場へ', () => sendDeckMove(card.instanceId, seatId, 'FIELD', null)));
    btns.appendChild(deckRowButton('墓地へ', () => sendDeckMove(card.instanceId, seatId, 'TRASH', null)));
    btns.appendChild(deckRowButton('消滅へ', () => sendDeckMove(card.instanceId, seatId, 'LOST', null)));
    btns.appendChild(deckRowButton('禁忌へ', () => sendDeckMove(card.instanceId, seatId, 'TABOO', null)));
    // ★20b: REVEAL の表示名変更に追随。あわせて新ゾーン「確認」への1手を足した
    //   (2-4 の用途例「山札の上から3枚を見て1枚を手札に加える」が最も短い手順になる)。
    btns.appendChild(deckRowButton('公開へ', () => sendDeckMove(card.instanceId, seatId, 'REVEAL', null)));
    btns.appendChild(deckRowButton('確認へ', () => sendDeckMove(card.instanceId, seatId, 'PRIVATE', null)));
    btns.appendChild(deckRowButton('マナ(表)へ', () => sendDeckMove(card.instanceId, seatId, 'MANA', null, false)));
    btns.appendChild(deckRowButton('マナ(裏)へ', () => sendDeckMove(card.instanceId, seatId, 'MANA', null, true)));
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

function sendDeckMove(cardId, seatId, toZone, toIndex, faceDown) {
    send('move', {
        cardIds: [cardId], toSeat: seatId, toZone, toIndex,
        faceDown: faceDown === undefined ? null : faceDown,
    });
}

// ---------------------------------------------------------------
// 13) ゲーム開始シーケンス(★Batch 23。総合ルール 2-5)
// ---------------------------------------------------------------
//
// ★ここにあるものはすべて「操作補助」であり、検証ではない(設計判断27・23 設計書 7-1)。
//   開始中の盤面操作を実際に棄却しているのはサーバ
//   (ManualPermissions.denyDuringStart)であり、このモーダルを消しても操作は通らない。
//
// ★「自分は今何を押せるか」はサーバが view.start に載せている(23 設計書9章)。
//   フェーズ・部屋の種類・作成者席から押せる人を組み立て直すと、判定が2箇所に分かれる。
//   ここが見るのは canBegin / canChooseMethod / canChooseOrder / myMulliganSeats の
//   4つの真偽値(と席の一覧)だけである。

/** 開始シーケンス中で盤面を触れない状態か。★ドラッグの抑止だけに使う */
function isStartLocked() {
    return !!(latestView && latestView.start && latestView.start.locking);
}

function startView() {
    return (latestView && latestView.start) || {};
}

function toggleStartModal(id, show) {
    document.getElementById(id).classList.toggle('d-none', !show);
}

/** 開始シーケンスの画面。renderAll の最後に呼ぶ(モーダル・オーバーレイ・待機表示) */
function renderStartUi(view) {
    const start = view.start || {};

    // 1) 開始方法の3択(3-1)。★①の意味は部屋の種類で変わる
    const solo = view.roomType !== 'VERSUS';
    document.getElementById('start-method-dice').textContent =
        solo ? 'ランダムで決める' : 'ダイスで決める(20面)';
    document.getElementById('start-method-note').textContent = solo
        // ★ソロで選択モーダルをもう1枚出しても、同じ人が続けて2回押すだけになる(3-1)
        ? 'ランダムに選ぶと、勝った席がそのまま先攻になる。'
        : 'ダイスで勝った側が先攻・後攻の選択権を得る(同じ出目なら振り直す)。';
    // ★②③の「自分」がどの席を指すかはサーバが決める(start.subjectSeat)。
    //   全公開部屋でデッキが1つだけのときは<b>その席</b>になるため、
    //   「自分」と書いたままだとどちらが先攻になるのか読めない。席名を出す。
    const subject = start.subjectSeat;
    document.getElementById('start-method-first').textContent =
        subject ? `席${subject} が先攻をとる` : '自分が先攻をとる';
    document.getElementById('start-method-second').textContent =
        subject ? `席${subject} が後攻をとる(5枚+ピュア・エレメント)` : '自分が後攻をとる';
    toggleStartModal('start-method-modal', !!start.canChooseMethod);

    // 2) 先攻・後攻の選択(3-3)。ダイスで勝った席のプレイヤーだけに出る
    if (start.canChooseOrder) {
        document.getElementById('start-order-title').textContent =
            `席${start.orderChooser} が先攻・後攻を選ぶ`;
    }
    toggleStartModal('start-order-modal', !!start.canChooseOrder);

    // 3) マリガン(4-3)。専用オーバーレイの開閉を決める
    syncMulliganOverlay(start);

    // 4) 待機表示(7-3)。★自分が今押すものが無いときだけ出す。
    //    盤面が固まっている理由が画面に書かれていない状態を作らない(21 3-5)
    const busy = start.canChooseMethod || start.canChooseOrder
        || (start.myMulliganSeats || []).length > 0;
    const banner = document.getElementById('start-banner');
    banner.classList.toggle('d-none', !(start.locking && start.waiting && !busy));
    document.getElementById('start-banner-text').textContent = start.waiting || '';
}

// ---- 開始方法・先攻後攻のボタン ----

document.getElementById('start-method-modal').addEventListener('click', (e) => {
    const btn = e.target.closest('button[data-method]');
    if (!btn) return;
    send('start-method', { method: btn.dataset.method });
});

document.getElementById('start-order-first').addEventListener('click', () => {
    send('start-order', { takeFirst: true });
});
document.getElementById('start-order-second').addEventListener('click', () => {
    send('start-order', { takeFirst: false });
});

/**
 * ★7-2 の逃げ道。開始シーケンス中でも<b>リセットだけは通る</b>(サーバも棄却しない)。
 * 止まったまま抜けられない画面を作らないための1つ穴であり、
 * 開始まわりの3つの画面すべてに同じボタンを置いてある。
 */
for (const btn of document.querySelectorAll('.manual-start-reset')) {
    btn.addEventListener('click', () => {
        if (confirm('リセットして最初から。よろしいですか?')) {
            send('reset', {});
        }
    });
}

// ---- マリガン専用オーバーレイ(4-3) ----
//
// ★盤面の手札行を流用しない。22 のクリック規約は「左=見る / 右=動かす」であり、
//   マリガンは「左=選択」である。同じ操作に別の意味を与えると、モードによって
//   左クリックの意味が変わる画面になる。専用オーバーレイに閉じ込めれば
//   「そこは別の画面である」と見た目で分かる。
//   ★Ctrl+左の複数選択(22 1-4)は既にあるが、ここでは使わない。この画面では
//   選ぶこと自体が主目的であり、修飾キーを要求するほうが操作を増やす。
//   新しい選択操作を発明したのではなく、既存の選択の記法(黄枠)をそのまま使っている。

function openMulliganOverlay(seatId) {
    activeOverlay = { kind: 'mulligan', seatId, picked: new Set() };
    renderMulliganOverlay();
}

/**
 * オーバーレイの開閉。★描画そのものは refreshOverlay が行う(二重に描かない)。
 * 自分の担当席が無くなったら閉じ、まだ残っていれば次の席へ移る
 * (全公開部屋では1人が両席ぶんを順に確定する)。
 */
function syncMulliganOverlay(start) {
    const mine = start.myMulliganSeats || [];
    const open = activeOverlay && activeOverlay.kind === 'mulligan';
    if (start.phase !== 'MULLIGAN' || mine.length === 0) {
        if (open) closeOverlay();
        return;
    }
    if (!open || mine.indexOf(activeOverlay.seatId) < 0) {
        openMulliganOverlay(mine[0]);
    }
}

function renderMulliganOverlay() {
    if (!latestView) return;
    const seatId = activeOverlay.seatId;
    const seatView = seatOf(latestView, seatId);
    // ★中身が届かない視点でここが開くことは無い(自席の手札である)が、
    //   届かなければ空配列になるだけで壊れない(21b 1-4 と同じ守り方)
    const hand = (seatView.zones && seatView.zones.HAND) || [];

    const root = overlayRoot();
    root.innerHTML = '';

    const backdrop = document.createElement('div');
    backdrop.className = 'manual-mulligan-backdrop';
    // ★背景クリックでは閉じない。閉じても盤面は操作できず(7-1)、
    //   「開き直せない画面」になるだけである。抜けたいならリセットを押す(7-2)
    root.appendChild(backdrop);

    const box = document.createElement('div');
    box.className = 'manual-mulligan';

    const head = document.createElement('div');
    head.className = 'manual-mulligan-head';
    const title = document.createElement('strong');
    title.textContent = `席${seatId} のマリガン`;
    head.appendChild(title);
    const hint = document.createElement('span');
    hint.className = 'small text-muted';
    // ★このオーバーレイ内だけのローカル規約であることを明記する(4-3)
    hint.textContent = 'クリックで選択 / もう一度で解除 / 右クリックで拡大'
        + ' — 戻した枚数だけ引き直す。やり直しは1回だけ';
    head.appendChild(hint);
    box.appendChild(head);

    const body = document.createElement('div');
    body.className = 'manual-mulligan-body';

    const row = document.createElement('div');
    row.className = 'manual-mulligan-row';
    for (const card of hand) {
        row.appendChild(createMulliganCard(card, seatId));
    }
    if (hand.length === 0) {
        const empty = document.createElement('div');
        empty.className = 'text-muted small';
        empty.textContent = '手札がない(山札が尽きている)。そのまま確定してよい';
        row.appendChild(empty);
    }
    body.appendChild(row);

    // ★拡大はこのオーバーレイの中に出す(4-3)。右列の #zoom-panel はバックドロップの
    //   下にあり、開いている間は見えない。「押しても何も起きない」を作らない(22 1-5)。
    //   ★#zoom-panel にも同じカードを入れてある(createMulliganCard の setZoom)ので、
    //   閉じた後は通常どおり右列に残る。
    const preview = document.createElement('div');
    preview.className = 'manual-mulligan-preview';
    preview.id = 'mulligan-preview';
    if (activeOverlay.zoomCard) {
        preview.appendChild(cardFace(activeOverlay.zoomCard, 'large'));
    } else {
        const note = document.createElement('span');
        note.className = 'text-muted small';
        note.textContent = '右クリックで拡大';
        preview.appendChild(note);
    }
    body.appendChild(preview);
    box.appendChild(body);

    const foot = document.createElement('div');
    foot.className = 'manual-mulligan-foot';
    const picked = mulliganPicked(hand);
    const count = document.createElement('span');
    count.id = 'mulligan-count';
    count.className = 'small';
    count.textContent = `${picked.length}枚を戻す`;
    foot.appendChild(count);

    const confirm = document.createElement('button');
    confirm.id = 'mulligan-confirm';
    confirm.className = 'btn btn-sm btn-warning ms-auto';
    confirm.textContent = '確定';
    confirm.addEventListener('click', () => {
        // ★引く枚数は載せない。サーバが戻した枚数と同数を引く(4-4・設計判断27)
        send('mulligan', { seat: seatId, cardIds: mulliganPicked(hand) });
    });
    foot.appendChild(confirm);

    const reset = document.createElement('button');
    reset.className = 'btn btn-sm btn-outline-danger manual-start-reset';
    reset.textContent = 'リセットして最初から';
    reset.addEventListener('click', () => {
        if (window.confirm('リセットして最初から。よろしいですか?')) {
            send('reset', {});
        }
    });
    foot.appendChild(reset);

    box.appendChild(foot);
    root.appendChild(box);
}

/** 今の手札に実在する選択だけを返す。★配り直しで消えた instanceId を送らないため */
function mulliganPicked(hand) {
    return hand.filter((c) => activeOverlay.picked.has(c.instanceId))
        .map((c) => c.instanceId);
}

function createMulliganCard(card, seatId) {
    const el = document.createElement('div');
    el.className = 'manual-mulligan-card';
    el.dataset.instanceId = card.instanceId;
    if (activeOverlay.picked.has(card.instanceId)) {
        el.classList.add('manual-mulligan-picked');
    }
    el.appendChild(cardFace(card, 'full'));
    // ★左 = 選択(このオーバーレイ内のローカル規約。4-3)
    el.addEventListener('click', () => {
        if (activeOverlay.picked.has(card.instanceId)) {
            activeOverlay.picked.delete(card.instanceId);
        } else {
            activeOverlay.picked.add(card.instanceId);
        }
        renderMulliganOverlay();
    });
    // ★右 = 拡大。1つの画面に「選択」と「拡大」の両方が要るための割り当てである。
    //   preventDefault を忘れるとブラウザのメニューが出る(22 1-7)
    el.addEventListener('contextmenu', (e) => {
        e.preventDefault();
        activeOverlay.zoomCard = card;
        // ★右列の拡大パネルにも入れる。オーバーレイを閉じた後もそのまま残る
        setZoom(card);
        renderMulliganOverlay();
    });
    return el;
}
