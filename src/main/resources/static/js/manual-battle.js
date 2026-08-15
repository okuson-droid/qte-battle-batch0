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
    // ★Batch 30: 名前からカードを引ける表も作る。ログの《名前》をリンクにするのに使う。
    //   同名のカードは存在しない前提だが、万一あっても最初の1枚で足りる
    //   (拡大に出すのは定義であり、盤面の個体ではない)。
    cardByNameMap = new Map();
    lib.cards.forEach((c) => {
        if (c.id) cardTextById.set(c.id, c.text || '');
        if (c.imageId) cardTextByImage.set(c.imageId, c.text || '');
        if (c.name && !cardByNameMap.has(c.name)) {
            cardByNameMap.set(c.name, c);
        }
    });
}

/** カード定義から作った表(★Batch 30)。ライブラリ未取得のあいだは null である */
let cardByNameMap = null;

/**
 * 名前からカードを引き、拡大パネルが描ける形({@code ManualCardView} 相当)にして返す。
 * ★盤面の個体ではなく<b>カードの定義</b>を返す。ログに出た時点の個体は既に
 * 別のゾーンへ動いている(あるいは消えている)ことがあり、そこを追うと嘘になる。
 * 拡大で見たいのは「そのカードが何か」であり、そのときの数値ではない。
 */
function cardByName(name) {
    if (!cardByNameMap || !cardByNameMap.has(name)) {
        return null;
    }
    const c = cardByNameMap.get(name);
    return {
        instanceId: null,
        cardId: c.id,
        name: c.name,
        imageId: c.imageId,
        civilization: c.civilization,
        type: c.type,
        cost: c.cost,
        printedAttack: c.attack,
        printedHp: c.hp,
        attack: c.attack,
        hp: c.hp,
        tapped: false,
        faceDown: false,
        used: false,
        labels: [],
        stackSize: 1,
        materials: [],
    };
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
/**
 * 文明色。★★★Batch 39(レビュー B-2): <b>値はここに書かない。</b>
 * 正は battle.css の `:root` の `--civ-*` 1箇所である(そちらの注記を読むこと)。
 *
 * ★名前は「`--civ-` + 文明コードの小文字」で機械的に決まる。<b>対応表を持たない</b>——
 *   表を持てば、それが色の複製と同じだけ壊れる(文明が1つ増えたときに直す場所が2つになる)。
 * ★1度読んだら覚える。描画のたびに getComputedStyle を呼ぶと、盤面の再描画が重くなる
 *   (29 の「描画関数の中で幅を実測しない」と同じ理由である)。
 * ★<b>既定の色をここに書かない</b>。書いた瞬間、それが8つ目の複製になる
 *   (読めなかったときは空文字が返り、枠の色が付かないだけである)。
 */
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
    el.style.setProperty('--mc', card.civilization ? civColor(card.civilization) : '#5a5468');

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
// 0-1) 初回だけ操作説明を開く(★Batch 34 設計解説1章・レビュー A-3)
// ---------------------------------------------------------------
//
// ★足りなかったのは説明ではなく<b>導線</b>である。
//   ヘルプの中身(2-6 のモーダル)は 22 で書き直してあり内容は足りている。
//   無かったのは「初見の人がそれを開く理由」だけなので、開く回数を1回足す。
//   コーチマークは作らない(裁定16。通話前提では初見者は口頭で教われる)。
//
// ★★キーは<b>部屋ごとにしない</b>。occupantId(qte-manual-occupant-{roomId})が
//   部屋ごとなのは「どの席の誰か」が部屋ごとに違うからである。
//   一方「操作説明を読んだ」のは<b>人の性質</b>であって部屋の性質ではない。
//   部屋ごとにすると、部屋を作り直すたびに同じ説明が出る = 邪魔にしかならない。
//
// ★★localStorage が使えない環境(プライベートモードの一部・容量超過)では
//   例外を投げる。そこで落ちると<b>盤面ごと開かない</b>ので、読み書きとも握りつぶす。
//   握りつぶした結果は「毎回出る」または「一度も出ない」であり、どちらも
//   対戦の続行を妨げない。ヘルプの表示は、対戦より優先されるものではない。

const HELP_SEEN_STORAGE_KEY = 'qte-manual-help-seen';

function hasSeenHelp() {
    try {
        return localStorage.getItem(HELP_SEEN_STORAGE_KEY) === '1';
    } catch (e) {
        // 読めないなら「まだ見ていない」側に倒す。出しすぎるほうが、出ないより害が小さい
        return false;
    }
}

function markHelpSeen() {
    try {
        localStorage.setItem(HELP_SEEN_STORAGE_KEY, '1');
    } catch (e) {
        // 記録できないだけで、今回の表示は成立している
    }
}

/**
 * 初回入室なら操作説明を開く。
 *
 * ★呼ぶのは<b>席が決まったあと</b>である(1章の resolveOccupant の後)。
 * 席選択ゲート(z-index 1060)より操作説明(2000)のほうが手前に出るため、
 * ゲートの上にヘルプを重ねると「名前を入れる画面が説明に隠れる」ことになる。
 * ★入室に失敗した場合は呼ばれない。読むべきものは説明ではなく失敗の理由である。
 */
function openHelpIfFirstVisit() {
    if (hasSeenHelp()) return false;
    openHelpModal();
    return true;
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
    // ★Batch 36: 層から降ろす(0-3)。フォーカスは開く前の位置へ戻る
    popModalLayer(gateEl('seat-gate'));
    // ★Batch 33: ゲートの開閉は切断オーバーレイの出る条件でもある(1-3)
    updateOfflineLock();
}

/**
 * ゲートを層に積む(★Batch 36)。
 *
 * ★Esc は [キャンセル] を押すだけである。したがって<b>入室前のゲートでは効かない</b>
 * ——あそこには [キャンセル] が出ていない(出口が無いのが意図である。
 * 席を選ばずに盤面へ入れてはいけない)。昇格のゲート(観戦者 → 席)では効く。
 * 情報モーダルと同じ「出口があるものにだけ Esc」の規約である。
 */
function pushGateLayer() {
    const cancel = gateEl('seat-gate-cancel');
    pushModalLayer(gateEl('seat-gate'), {
        escape: () => { if (isShown(cancel)) cancel.click(); },
    });
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
    pushGateLayer();
    updateOfflineLock();
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
    pushGateLayer();
    updateOfflineLock();
}

/** 部屋が見つからない等、席選択そのものが成立しないとき。 */
function showGateFatal(message) {
    gateEl('seat-gate-room').textContent = '入れませんでした';
    gateEl('seat-gate-code').textContent = ROOM_ID;
    gateEl('seat-gate-type').textContent = '';
    gateEl('seat-gate-name-wrap').classList.add('d-none');
    gateEl('seat-gate-buttons').classList.add('d-none');
    gateEl('seat-gate-cancel').classList.add('d-none');
    // ★Batch 33: 入れなかった部屋のリンクをコピーさせない(共有しても相手も入れない)
    gateEl('seat-gate-copy').classList.add('d-none');
    showGateError(message);
    gateEl('seat-gate').classList.remove('d-none');
    pushGateLayer();
    updateOfflineLock();
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
// 0-3) モーダルの層(★Batch 36 設計解説1章・レビュー A-1)
// ---------------------------------------------------------------
//
// ★何が無かったか。34 hotfix で「出口はどこにあるか」は直したが、
//   <b>キーボードだけで出口へ到達できるか</b>は手つかずだった。
//   Esc は効かず、Tab はモーダルの裏の盤面へ抜け、閉じた後にフォーカスが
//   どこへ戻るかも決まっていなかった。
//
// ★★<b>層は1本のスタックで持つ</b>。
//   画面を覆うものは既に7種類ある(情報モーダル・確認モーダル・席選択ゲート・
//   帯・全面表示・マリガン・在室者ポップオーバー)。「今 Esc が誰のものか」を
//   それぞれが自分で判断すると、重なったときに<b>2つが同時に閉じる</b>。
//   開いた順に積み、いちばん上だけが Esc とフォーカスを握る。
//
// ★★<b>Esc は × と同じ資格しか持たない</b>(裁定35 の一般化)。
//   どちらも [閉じる] を {@code click()} するだけであり、閉じ方の本体は
//   相変わらず [閉じる] のハンドラ1箇所である。したがって
//   <b>[閉じる] を持たないモーダルでは Esc も効かない</b>——
//   開始シーケンスの2つ(start-method / start-order)がそれである(裁定34)。
//   出口の有無という1つの事実から、× と Esc の2つが同時に決まる。
//
// ★★<b>Esc は下の層へ落とさない</b>。いちばん上に出口が無ければ、そこで止まる。
//   落とすと「閉じられない画面が出ているのに、その裏のモーダルだけが閉じる」
//   という、画面に現れない変化が起きる。
//
// ★<b>トラップするのはモーダルだけである</b>(情報モーダル・確認モーダル・ゲート)。
//   帯・全面表示・マリガンは Esc の対象にはするが、フォーカスは閉じ込めない。
//   あれらは配信のたびに中身を作り直す({@code refreshOverlay})ため、
//   フォーカスを持った要素が再描画で消える。閉じ込めの維持がビューの更新と
//   競合し、「盤面が更新されるたびにフォーカスが飛ぶ」ほうが害が大きい。
//   Esc は document 上の1本なので、この競合とは無関係に効く。

/** 焦点を取れる要素。★disabled と tabindex="-1" は除く */
const FOCUSABLE_SELECTOR = 'a[href], button:not([disabled]), input:not([disabled]),'
    + ' select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

// { el, trap, escape, restore } を開いた順に積む。いちばん後ろが「今の主役」である
const modalStack = [];

/**
 * 画面に出ているか。
 * ★{@code offsetParent} は使えない。{@code .info-modal} は position: fixed であり、
 * 出ていても offsetParent が null になる。矩形の有無で見る。
 */
function isShown(el) {
    return !!el && el.isConnected && el.getClientRects().length > 0;
}

function focusablesIn(el) {
    return Array.from(el.querySelectorAll(FOCUSABLE_SELECTOR)).filter(isShown);
}

/**
 * 初期フォーカス。
 *
 * ★既定は「見出し帯の × を<b>除いた</b>最初の焦点可能要素」である。
 *   × は出口であって用件ではない。数値モーダルを開いた人の用件は数値の入力である。
 * ★用件が先頭に無いモーダルは {@code data-initial-focus} で名指しする。
 *   操作説明は本文に焦点可能な要素が [閉じる] しか無く、既定のままだと
 *   <b>開いた瞬間に最下部までスクロールする</b>(focus は要素を見える位置へ送る)。
 *   確認モーダルは、既定だと [実行] に載る。破壊的操作でそれをやってはいけない。
 */
function applyInitialFocus(el) {
    const hint = el.getAttribute('data-initial-focus');
    const named = hint ? el.querySelector(hint) : null;
    if (isShown(named)) {
        named.focus();
        return;
    }
    const list = focusablesIn(el);
    const body = list.filter((n) => !n.classList.contains('info-modal-x'));
    const target = body.length > 0 ? body[0] : list[0];
    if (target) target.focus();
}

/** 閉じたあとの戻り先。★消えている要素へは戻さない(戻せないなら body へ落とす) */
function restoreFocus(el) {
    if (isShown(el) && typeof el.focus === 'function') {
        el.focus();
        return;
    }
    if (document.activeElement && document.activeElement !== document.body) {
        document.activeElement.blur();
    }
}

/**
 * 層を積む。
 * @param el      画面を覆っている要素(トラップの範囲でもある)
 * @param options escape: Esc で呼ぶもの(無ければ Esc は効かない) /
 *                trap: フォーカスを閉じ込めるか(既定 true)
 */
function pushModalLayer(el, options) {
    const opts = options || {};
    const trap = opts.trap !== false;
    // ★二重に積まない。開いているモーダルを開き直すのは「同じ層の中身の入れ替え」であり、
    //   層が増える出来事ではない(数値モーダルを開いたまま別のカードを右クリックできる)
    const known = modalStack.find((layer) => layer.el === el);
    if (known) {
        // ★<b>フォーカスが中にあるうちは触らない</b>。開始シーケンスのモーダルは
        //   サーバの配信のたびに開き直すので(11-2 の toggleStartModal)、
        //   毎回フォーカスを当て直すと<b>入力中に先頭のボタンへ飛ぶ</b>。
        //   当て直すのは、中身の作り直しでフォーカスを持った要素が消えたときだけでよい。
        if (known.trap && !el.contains(document.activeElement)) applyInitialFocus(el);
        return;
    }
    modalStack.push({ el, trap, escape: opts.escape || null, restore: document.activeElement });
    if (trap) applyInitialFocus(el);
}

/**
 * 層を降ろす。
 * ★いちばん上でなければフォーカスを戻さない。サーバ由来で勝手に閉じるもの
 * (開始シーケンスのモーダル)が下から抜けることがあり、そのときに戻すと
 * <b>上に出ているモーダルからフォーカスを奪う</b>。
 */
function popModalLayer(el) {
    const index = modalStack.findIndex((layer) => layer.el === el);
    if (index < 0) return;
    const layer = modalStack.splice(index, 1)[0];
    if (index !== modalStack.length) return;
    if (layer.trap) restoreFocus(layer.restore);
}

function topModalLayer() {
    return modalStack.length > 0 ? modalStack[modalStack.length - 1] : null;
}

// ★Esc と Tab は<b>1つのハンドラ</b>で受ける。どちらも「いちばん上の層は誰か」を
//   最初に決めてから分岐するので、判断が2箇所に分かれると必ずずれる。
document.addEventListener('keydown', (e) => {
    const top = topModalLayer();
    if (!top) return;
    if (e.key === 'Escape') {
        // ★出口が無くても preventDefault する。ここで止めるのが「下へ落とさない」の実体である
        e.preventDefault();
        if (top.escape) top.escape();
        return;
    }
    if (e.key !== 'Tab' || !top.trap) return;
    const list = focusablesIn(top.el);
    if (list.length === 0) {
        e.preventDefault();
        return;
    }
    const first = list[0];
    const last = list[list.length - 1];
    const active = document.activeElement;
    const outside = !top.el.contains(active);
    if (e.shiftKey && (outside || active === first)) {
        e.preventDefault();
        last.focus();
    } else if (!e.shiftKey && (outside || active === last)) {
        e.preventDefault();
        first.focus();
    }
});

// ★Tab の折り返しだけでは足りない。裏の盤面を<b>クリック</b>したときや、
//   ブラウザが独自にフォーカスを移したときは keydown を通らない。網をもう1枚張る。
document.addEventListener('focusin', (e) => {
    const top = topModalLayer();
    if (!top || !top.trap || top.el.contains(e.target)) return;
    applyInitialFocus(top.el);
});

// ---- 情報モーダル(.info-modal)の出入り ----
//
// ★開閉を {@code classList} の直接操作から関数へ移した。層への出入りが
//   開閉と同じ1行に載っていないと、「開いたのに積まれていない」が静かに起きる。

/** [閉じる] ボタン。★34 hotfix の × と<b>同じ規約</b>(id が -close で終わる)で引く */
function infoModalCloseButton(el) {
    return el.querySelector('[id$="-close"]');
}

function openInfoModal(id) {
    const el = document.getElementById(id);
    if (!el) return null;
    el.classList.remove('d-none');
    const closeBtn = infoModalCloseButton(el);
    // ★Esc は [閉じる] を押すだけ。× とまったく同じ経路である(裁定35 の一般化)
    pushModalLayer(el, { escape: closeBtn ? () => closeBtn.click() : null });
    return el;
}

function closeInfoModal(id) {
    const el = typeof id === 'string' ? document.getElementById(id) : id;
    if (!el) return;
    el.classList.add('d-none');
    popModalLayer(el);
}

function isInfoModalOpen(id) {
    const el = document.getElementById(id);
    return !!el && !el.classList.contains('d-none');
}

// ---------------------------------------------------------------
// 0-4) 確認モーダル(★Batch 36 設計解説3章・レビュー A-1)
// ---------------------------------------------------------------
//
// ★素の {@code confirm()} を捨てた理由は3つある。
//   1. <b>OSの見た目</b>が出る。黒い盤面の上に白いダイアログが出て、
//      33〜34 で揃えたテーマがそこだけ崩れる。
//   2. <b>ボタンの文言を決められない</b>。[OK] / [キャンセル] しか出せないので、
//      何が起きるのかを問いの本文に全部書くことになる。
//   3. <b>JavaScript を止める</b>。止まっている間は STOMP の受信も描画も進まない。
//      通話しながら「ちょっと待って」と言われて手が止まる数十秒、
//      相手の操作がこちらの画面に一切反映されない。
//
// ★★<b>コールバックで受ける</b>(Promise ではない)。
//   置き換える6箇所は<b>すべて</b>「取り消したときは何もしない」である。
//   真偽値を返せば呼び出し側に必ず {@code if} が1つ増えるが、
//   使う分岐は片方しかない。既存の {@code if (confirm(...)) { ... }} と
//   1対1で対応する形が、いちばん読み替えが要らない。
//
// ★<b>問いは1つずつ</b>。開いている間の {@code askConfirm} は捨てる。
//   問いを重ねると、答えたのがどちらの問いなのか画面から分からなくなる。

let confirmPending = null;

/**
 * 破壊的操作の確認を出す。
 *
 * @param text    何が起きるか(1〜2文)
 * @param okLabel 実行ボタンの文言。★「はい」ではなく<b>動詞</b>を書く。
 *                問いを読み飛ばしてもボタンだけで何が起きるか分かるようにするため
 * @param onOk    実行したときに呼ぶもの
 * @return 問いを出したか(既に出ているときは false)
 */
function askConfirm(text, okLabel, onOk) {
    if (confirmPending) return false;
    confirmPending = onOk;
    document.getElementById('confirm-modal-text').textContent = text;
    document.getElementById('confirm-modal-ok').textContent = okLabel;
    openInfoModal('confirm-modal');
    return true;
}

function closeConfirmModal() {
    confirmPending = null;
    closeInfoModal('confirm-modal');
}

document.getElementById('confirm-modal-close').addEventListener('click', closeConfirmModal);
document.getElementById('confirm-modal-ok').addEventListener('click', () => {
    const run = confirmPending;
    // ★先に閉じる。実行が location.href への遷移でも、層とフォーカスの後片付けは済んでいる
    closeConfirmModal();
    // ★★Batch 37: 効果音の2つめの取り付け点である(0-5)。
    //   破壊的操作は差分が8件を超えることが多く(リセット・退室)、裁定8 によって
    //   <b>演出が丸ごと出ない</b>。押した手応えが画面に残らないので、音だけがその1手を語る。
    sfxPlay('commit');
    if (run) run();
});

/**
 * 「リセットして最初から」の確認。★開始シーケンスの3つの画面と
 * マリガンのオーバーレイで使う。文言を1箇所に置くための関数である(設計判断28)。
 */
function bindStartReset(button) {
    button.addEventListener('click', () => {
        askConfirm('盤面をリセットして最初からやり直す。Undo では戻せない。',
            'リセットする', () => send('reset', {}));
    });
}

// ---------------------------------------------------------------
// 0-5) 効果音(★Batch 37 設計解説2〜4章・レビュー S-2)
// ---------------------------------------------------------------
//
// ★★<b>発火点を1つも増やしていない</b>。音は既存の3箇所に相乗りする。
//   (a) 盤面の所作・LP・決着 …… {@link fxSpawn}(32a の演出と同じ差分の列を読む)
//   (b) 破壊的操作の実行 ……… 確認モーダルの [実行](0-4)
//   これで裁定67 が挙げた取り付け点(fxBuild のディスパッチ / fxBuildDeclare /
//   askConfirm)を全部覆える。fxBuildDeclare に個別の呼び出しは要らなかった——
//   決着は他の演出と<b>同じ effects の列</b>に kind='declare' として並んでいるためである。
//
// ★★<b>音は動きではない</b>。したがって {@code prefers-reduced-motion} を
//   音のゲートにしない。あの設定が言っているのは「画面を動かすな」であって
//   「静かにしろ」ではない。演出を止めている人にこそ、音は状態変化を伝える手段になる。
//   ゲートは2本に分かれる: 見た目は {@link fxAllowed}、音は {@link sfxReady} である。
//
// ★★<b>1回の配信で鳴らす音は1つである</b>(3-2)。手動モードの1配信は1操作であり、
//   1操作に対応する手応えは1つである。差分の件数だけ鳴らすと、音が語るのは
//   「何をしたか」ではなく「何件変わったか」になる。これは裁定8(差分が8件を超えたら
//   演出を出さない)や「演出は1手を語る道具である」と同じ考え方の音側である。
//
// ★★<b>音源はコードで合成する</b>(2-3)。音声ファイルを同梱しない。
//   理由は「失敗の広さ」(裁定27)である。ファイルにすると
//   読み込み失敗・404・キャッシュ版数という失敗経路が3つ増えるが、
//   合成にはどれも無い。あわせて、音の性格が<b>数値の表1つ</b>
//   ({@link SFX_SPECS})に出るので、実機で聞いた印象から直接調整できる。
//
// ★★<b>自動再生ポリシーの unlock は「最初のユーザー操作」で行う</b>(4章)。
//   席選択ゲートの決定を入口にすると素直に見えるが、<b>復帰はゲートを通らない</b>ので
//   その経路の人には二度と音が鳴らない。特定の操作を名指しすると、必ず
//   「名指ししていない経路」ができる。名指ししないのが1箇所に決める、の中身である。

/** 設定の保存先。★裁定31 と同じく<b>部屋ごとにしない</b>。音量は人の性質である */
const SFX_STORAGE_KEY = 'qte-manual-sound';

/** ★初期音量は控えめである(裁定67)。通話の音声と干渉させない */
const SFX_DEFAULT_VOLUME = 30;

/**
 * 音の仕様(★マスターが実機で聞いて調整するのはこの表だけである)。
 *
 * <ul>
 *   <li>{@code gain} は音どうしの相対バランス(0〜1)。実出力は gain × 音量スライダー/100</li>
 *   <li>{@code ms} は長さ。{@code from}/{@code to} は周波数の始点と終点(Hz)</li>
 *   <li>{@code tone} = 楽音 / {@code noise} = 帯域を絞った雑音 / {@code chord} = 和音</li>
 * </ul>
 *
 * ★8種である(裁定67 の「6〜8種」)。うち7種は盤面の差分から、
 * {@code commit} だけが確認モーダルの [実行] から鳴る。
 */
const SFX_SPECS = {
    // 紙を引き抜く感じ。上へ抜ける短い擦過音
    draw: { type: 'noise', ms: 150, from: 700, to: 2800, q: 5, gain: 0.55 },
    // 置く・動かす。低く短い打音
    place: { type: 'tone', ms: 110, from: 230, to: 110, wave: 'triangle', gain: 0.80 },
    // タップ / アンタップ。いちばん回数が多いので、いちばん軽くしてある
    tap: { type: 'tone', ms: 60, from: 660, to: 540, wave: 'square', gain: 0.30 },
    // めくる。下へ抜ける短い擦過音(draw と逆向きにして聞き分けられるようにした)
    flip: { type: 'noise', ms: 100, from: 2600, to: 800, q: 4, gain: 0.45 },
    // LPが減る。下降する2次的な唸り
    lpDown: { type: 'tone', ms: 280, from: 400, to: 140, wave: 'sawtooth', gain: 0.60 },
    // LPが増える。上昇させて減少と対にする
    lpUp: { type: 'tone', ms: 280, from: 330, to: 700, wave: 'triangle', gain: 0.50 },
    // 決着。★1試合に1回しか鳴らない音なので、唯一の和音にしてある
    decisive: { type: 'chord', ms: 900, notes: [392.0, 523.25, 659.25], wave: 'triangle', gain: 0.55 },
    // 破壊的操作の実行(リセット・退室・席を立つ)。★「押した」の手応えである
    commit: { type: 'tone', ms: 130, from: 300, to: 300, wave: 'square', gain: 0.45 },
    // ---- ★Batch 38: 開始シーケンスの3種(裁定77・81)。どれも1試合に数回しか鳴らない ----
    // 先後判定のダイス。★転がって止まる。唯一の rattle 型である
    dice: { type: 'rattle', ms: 620, hits: 5, from: 2600, to: 1100, q: 9, gain: 0.55 },
    // 初期ドローの配り。★draw より低く長い。1枚ではなく<b>ひと配り</b>の音である
    deal: { type: 'noise', ms: 300, from: 480, to: 1700, q: 2, gain: 0.60 },
    // マリガンの切り直し。★いちばん長い擦過音で「束をまとめて混ぜた」を語る
    shuffle: { type: 'noise', ms: 460, from: 1500, to: 850, q: 1.4, gain: 0.50 },
};

/**
 * 演出の種類 → 音。
 *
 * ★<b>音の語彙は演出の語彙より粗い</b>。13種類ある {@code fx.kind} を10の音に畳んでいる。
 * 移動・出現・消滅・スタックへの吸収は、耳から見ればどれも「カードが動いた」である。
 * ★{@code lp} だけはここに無い。増と減で意味が逆であり、表では決まらないためである
 * ({@link sfxNameFor})。
 * ★★Batch 38 の3種はここに<b>素直に載った</b>。儀式は差分ではないが、
 * {@code fxEffects} が作る列に同じ形で並ぶので、音の側からは区別が要らない(裁定68)。
 */
const SFX_FOR_KIND = {
    draw: 'draw',
    move: 'place',
    appear: 'place',
    vanish: 'place',
    sink: 'place',
    tap: 'tap',
    flip: 'flip',
    declare: 'decisive',
    // ★Batch 38: 儀式。マリガンだけは名前と音がずれている(語彙は粗くてよい・裁定72)
    dice: 'dice',
    deal: 'deal',
    mulligan: 'shuffle',
    // ★手動のシャッフルは名前も音も同じ。マリガンの中で鳴るのと同じ音である
    shuffle: 'shuffle',
};

/**
 * 1配信で1つだけ鳴らすときの優先順位(前ほど強い)。
 *
 * ★★<b>珍しい出来事ほど優先する。</b>頻度の高い音は「何が起きたか」を語らない。
 * 決着は1試合に1回、ダイスと配りも1回、マリガンは最大2回、LPの増減は数回、
 * ドローは毎ターン、タップと移動は毎手である。
 * ★{@code commit} はここに無い。あれは差分から来ないので競合しない。
 * ★★Batch 38: ソロのランダムは<b>ダイスと配りが同じ配信で起きる</b>。
 * そこで鳴るのはダイスだけであり、配りの音は鳴らない —— これは取りこぼしではなく、
 * 1配信1音(裁定70)がそういう決まりだからである。
 * ★★★儀式が上位に来るのは「珍しいから」であって「大事だから」ではない。
 * 順序の理由を1本に保つ(頻度)。理由が2本になると、次に足す音の位置が決まらなくなる。
 */
const SFX_PRIORITY = ['decisive', 'dice', 'deal', 'shuffle',
    'lpDown', 'lpUp', 'draw', 'flip', 'tap', 'place'];

/** 音量スライダーを動かしたときの試聴音。★いちばん耳につく音で合わせてもらう */
const SFX_PREVIEW = 'place';

/** unlock の入口。★特定の操作を名指ししない(上の章) */
const SFX_UNLOCK_EVENTS = ['pointerdown', 'keydown'];

let sfxCtx = null;
let sfxMaster = null;
let sfxNoise = null;
/** この環境に AudioContext が無い(または生成に失敗した)。パネルに理由を出すために持つ */
let sfxUnsupported = false;
let sfxSettings = loadSfxSettings();

function loadSfxSettings() {
    const fallback = { muted: false, volume: SFX_DEFAULT_VOLUME };
    try {
        // ★localStorage は例外を投げうる(0-1章と同じ理由で握りつぶす)。
        //   音の設定は、対戦の続行より優先されるものではない
        const raw = JSON.parse(localStorage.getItem(SFX_STORAGE_KEY) || 'null');
        if (!raw || typeof raw !== 'object') return fallback;
        const volume = Number(raw.volume);
        return {
            muted: raw.muted === true,
            volume: Number.isFinite(volume) ? Math.min(100, Math.max(0, Math.round(volume)))
                : SFX_DEFAULT_VOLUME,
        };
    } catch (e) {
        return fallback;
    }
}

function saveSfxSettings() {
    try {
        localStorage.setItem(SFX_STORAGE_KEY, JSON.stringify(sfxSettings));
    } catch (e) {
        // 記録できないだけで、今回の設定は効いている
    }
}

/**
 * 音を出せる状態か。
 * ★{@link fxAllowed} と<b>別のゲート</b>である(上の章)。
 * reduced-motion は見ない。見るのは「unlock 済みか」「ミュートでないか」だけである。
 */
function sfxReady() {
    return !!sfxCtx && !sfxSettings.muted && sfxSettings.volume > 0;
}

/** パネルに出す状態。★unlock 前に開くことは実際には起きない(パネルを開く操作自体が unlock する) */
function sfxStatusText() {
    if (sfxUnsupported) return 'この環境では音を出せない(ブラウザが対応していない)';
    if (!sfxCtx) return '画面をどこか一度クリックすると音が使えるようになる';
    if (sfxSettings.muted) return 'ミュート中';
    if (sfxSettings.volume === 0) return '音量が 0 になっている';
    return '音は有効である';
}

/**
 * ★★最初のユーザー操作で音を使えるようにする(4章)。
 *
 * ブラウザは、ユーザーの操作を経ていない音の再生を止める。
 * {@code AudioContext} の生成と {@code resume()} を<b>操作のハンドラの中</b>で行う必要があり、
 * ここが唯一その条件を満たす場所である。
 * ★入口を1つの操作に決めない理由は上の章のとおりである。
 */
function sfxUnlock() {
    if (sfxCtx || sfxUnsupported) return;
    const Ctor = window.AudioContext || window.webkitAudioContext;
    if (!Ctor) {
        sfxUnsupported = true;
        syncSoundPanel();
        return;
    }
    try {
        sfxCtx = new Ctor();
        sfxMaster = sfxCtx.createGain();
        sfxMaster.connect(sfxCtx.destination);
        applySfxVolume();
        if (sfxCtx.state === 'suspended') sfxCtx.resume();
    } catch (e) {
        sfxCtx = null;
        sfxMaster = null;
        sfxUnsupported = true;
    }
    syncSoundPanel();
}

function sfxUnlockFromGesture() {
    sfxUnlock();
    for (const type of SFX_UNLOCK_EVENTS) {
        document.removeEventListener(type, sfxUnlockFromGesture, true);
    }
}

for (const type of SFX_UNLOCK_EVENTS) {
    // ★capture で受ける。盤面のハンドラが stopPropagation しても unlock は通す
    document.addEventListener(type, sfxUnlockFromGesture, true);
}

function applySfxVolume() {
    if (!sfxMaster) return;
    sfxMaster.gain.value = sfxSettings.muted ? 0 : sfxSettings.volume / 100;
}

/** ホワイトノイズは1本だけ作って使い回す(擦過音の材料) */
function sfxNoiseBuffer() {
    if (sfxNoise) return sfxNoise;
    const frames = Math.floor(sfxCtx.sampleRate * 0.5);
    const buffer = sfxCtx.createBuffer(1, frames, sfxCtx.sampleRate);
    const data = buffer.getChannelData(0);
    for (let i = 0; i < frames; i++) {
        data[i] = Math.random() * 2 - 1;
    }
    sfxNoise = buffer;
    return buffer;
}

/**
 * 立ち上がりと減衰。★どの音も同じ形の包絡線を通す。
 * 減衰を指数にしているのは、直線だと切れ際に「プツ」と鳴るためである。
 */
function sfxEnvelope(at, seconds, gain) {
    const env = sfxCtx.createGain();
    env.gain.setValueAtTime(0.0001, at);
    env.gain.linearRampToValueAtTime(gain, at + 0.006);
    env.gain.exponentialRampToValueAtTime(0.0001, at + seconds);
    env.connect(sfxMaster);
    return env;
}

function sfxTone(at, seconds, wave, from, to, gain) {
    const osc = sfxCtx.createOscillator();
    osc.type = wave || 'triangle';
    osc.frequency.setValueAtTime(from, at);
    if (to !== from) {
        osc.frequency.exponentialRampToValueAtTime(Math.max(1, to), at + seconds);
    }
    osc.connect(sfxEnvelope(at, seconds, gain));
    osc.start(at);
    osc.stop(at + seconds);
}

function sfxNoiseSweep(at, seconds, from, to, q, gain) {
    const src = sfxCtx.createBufferSource();
    src.buffer = sfxNoiseBuffer();
    const band = sfxCtx.createBiquadFilter();
    band.type = 'bandpass';
    band.Q.value = q;
    band.frequency.setValueAtTime(from, at);
    band.frequency.exponentialRampToValueAtTime(Math.max(1, to), at + seconds);
    src.connect(band);
    band.connect(sfxEnvelope(at, seconds, gain));
    src.start(at);
    src.stop(at + seconds);
}

/**
 * ★★Batch 38: ダイスの転がり。短い雑音の粒を間隔を詰めながら並べ、最後の1粒だけ低く強くする。
 *
 * ★他の音と違い<b>粒が複数ある</b>。それでもこれは「1つの音」である ——
 * 裁定70 が数えているのは<b>出来事</b>であって音源ノードではない。
 * (だから検証も「何個ノードを作ったか」ではなく「鳴ったか」で見る。設計書7章)
 */
function sfxRattle(at, seconds, hits, from, to, q, gain) {
    const count = Math.max(2, hits || 4);
    const grain = 0.024;
    for (let i = 0; i < count; i++) {
        // ★間隔を等分ではなく後半ほど詰める。等分だと機械のノイズに聞こえる
        const ratio = Math.pow(i / (count - 1), 1.7);
        const last = i === count - 1;
        const hz = from + (to - from) * (i / (count - 1));
        sfxNoiseSweep(at + ratio * (seconds - grain), grain, hz, hz * 0.8, q,
            gain * (last ? 1 : 0.55));
    }
}

/**
 * 音を1つ鳴らす。
 * @return 実際に鳴らしたか(ミュート中・unlock 前は false)
 */
function sfxPlay(name) {
    const spec = SFX_SPECS[name];
    if (!spec || !sfxReady()) return false;
    try {
        // ★タブを裏へ回すと suspended へ落ちるブラウザがある。鳴らす直前に起こす
        if (sfxCtx.state === 'suspended') sfxCtx.resume();
        const at = sfxCtx.currentTime;
        const seconds = spec.ms / 1000;
        if (spec.type === 'noise') {
            sfxNoiseSweep(at, seconds, spec.from, spec.to, spec.q, spec.gain);
        } else if (spec.type === 'rattle') {
            sfxRattle(at, seconds, spec.hits, spec.from, spec.to, spec.q, spec.gain);
        } else if (spec.type === 'chord') {
            // ★和音は少しずつずらして重ねる。同時に立ち上げると1つの濁った音になる
            spec.notes.forEach((hz, i) => {
                sfxTone(at + i * 0.07, seconds - i * 0.07, spec.wave, hz, hz,
                    spec.gain / spec.notes.length);
            });
        } else {
            sfxTone(at, seconds, spec.wave, spec.from, spec.to, spec.gain);
        }
    } catch (e) {
        // ★音で対戦を止めない。鳴らせなかったことは盤面の正しさに影響しない
        return false;
    }
    return true;
}

/** 差分1件に対応する音の名前。★{@code lp} だけは表では決まらない(増と減で逆) */
function sfxNameFor(fx) {
    if (!fx) return null;
    if (fx.kind === 'lp') return fx.delta > 0 ? 'lpUp' : 'lpDown';
    return SFX_FOR_KIND[fx.kind] || null;
}

/**
 * ★★1配信で鳴らす1つを選ぶ(3-2)。優先順位は {@link SFX_PRIORITY} の1箇所にある。
 * @return 音の名前(鳴らすものが無ければ null)
 */
function sfxChoose(effects) {
    let best = null;
    let bestRank = SFX_PRIORITY.length;
    for (const fx of effects || []) {
        const name = sfxNameFor(fx);
        const rank = name ? SFX_PRIORITY.indexOf(name) : -1;
        if (rank >= 0 && rank < bestRank) {
            best = name;
            bestRank = rank;
        }
    }
    return best;
}

// ---- 設定パネル(★裁定67: 通話と音量が干渉するため必須である)----

function syncSoundPanel() {
    const mute = document.getElementById('sound-mute');
    const volume = document.getElementById('sound-volume');
    const status = document.getElementById('sound-modal-status');
    if (!mute || !volume || !status) return;
    mute.checked = sfxSettings.muted;
    volume.value = String(sfxSettings.volume);
    volume.disabled = sfxSettings.muted;
    document.getElementById('sound-volume-value').textContent = String(sfxSettings.volume);
    status.textContent = sfxStatusText();
    // ★色が意味を持つのは「鳴らない理由がある」ときだけである。
    //   正常時まで警告色にすると、色は何も言わなくなる
    status.classList.toggle('sound-status-warn', !sfxReady());
}

function openSoundModal() {
    syncSoundPanel();
    openInfoModal('sound-modal');
}

document.getElementById('btn-sound').addEventListener('click', openSoundModal);
document.getElementById('sound-modal-close').addEventListener('click', () => {
    closeInfoModal('sound-modal');
});

document.getElementById('sound-mute').addEventListener('change', (e) => {
    sfxSettings.muted = e.target.checked;
    saveSfxSettings();
    applySfxVolume();
    syncSoundPanel();
    // ★ミュートを解除したときだけ鳴らす。切ったのに音が出るのは矛盾である
    if (!sfxSettings.muted) sfxPlay(SFX_PREVIEW);
});

// ★値の反映は input(つまみを動かしている間)、試聴は change(離したとき)である。
//   input で鳴らすと、つまみを動かしている間じゅう音が連射される
document.getElementById('sound-volume').addEventListener('input', (e) => {
    sfxSettings.volume = Number(e.target.value);
    applySfxVolume();
    syncSoundPanel();
});
document.getElementById('sound-volume').addEventListener('change', () => {
    saveSfxSettings();
    sfxPlay(SFX_PREVIEW);
});

// ---------------------------------------------------------------
// 1) 接続
// ---------------------------------------------------------------

const wsProtocol = location.protocol === 'https:' ? 'wss' : 'ws';
/**
 * ★Batch 28: ハートビートを明示する。
 *
 * StompJs の既定も 10 秒だが、<b>実際に流れるかはサーバとの折衝で決まる</b>。
 * サーバ側が 0(無効)を返せば、クライアントが何を書いていてもハートビートは流れない。
 * 27 までサーバ({@code WebSocketConfig})にハートビート設定が無く、
 * Spring のシンプルブローカーは<b>既定で無効</b>だったため、この接続は
 * <b>操作していない間、完全な無通信</b>だった。サーバ側を有効にしたうえで、
 * こちら側の意図も式として残しておく(既定に頼ると、既定が変わったときに黙って壊れる)。
 */
const client = new StompJs.Client({
    brokerURL: `${wsProtocol}://${location.host}/ws`,
    reconnectDelay: 3000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
});

client.onConnect = () => {
    // ★Batch 33: 「初回の接続」と「再接続」を区別する。
    //   裏で復旧したことを黙って済ませない(28 の「無言をやめる」の続き)
    const reconnected = connectionEstablishedOnce;
    connectionEstablishedOnce = true;
    socketDown = false;
    offlinePeeking = false;
    setConnectionStatus('接続済み');
    updateOfflineLock();
    setConnBar(reconnected ? '再接続しました。盤面を同期しています' : null,
        'ok', CONN_BAR_MS);
    client.subscribe(`/topic/manual/${ROOM_ID}/view/${OCCUPANT_ID}`, onMessage);
    send('ready', {});
};

client.onWebSocketClose = () => {
    socketDown = true;
    setConnectionStatus('切断(再接続中...)', true);
    updateOfflineLock();
};
client.onStompError = (frame) => {
    // ★サーバがSTOMPレベルでエラーを返した場合。再接続では直らないことが多いので明示する
    socketDown = true;
    setConnectionStatus('サーバとの通信でエラー: ' + (frame.headers.message || '不明'), true);
    updateOfflineLock();
};

// ★カード定義の取得は接続と独立に始める(Batch 25)。失敗しても対戦は続けられる
loadCardLibrary();

/** occupantId が決まってから初めて STOMP 接続を始める(0章)。 */
resolveOccupant()
    .then((occupantId) => {
        OCCUPANT_ID = occupantId;
        client.activate();
        // ★Batch 34: 席が決まった直後、初回だけ操作説明を開く(0-1章)。
        //   接続の完了は待たない。説明を読むのに盤面は要らないし、
        //   待つと「接続が遅い日だけ出ない」という再現性の無い挙動になる
        openHelpIfFirstVisit();
    })
    .catch((e) => {
        setConnectionStatus('入室に失敗しました: ' + e.message);
        showGateFatal(e.message);
    });

/**
 * 接続状態の表示。★Batch 28: 異常時は色を変える。
 * 灰色の小さい文字のままだと、切れていることに人間が気づけない
 * (25c の「枚数ラベルが薄すぎて読めない」と同じ種類の問題である)。
 */
function setConnectionStatus(text, warn) {
    const el = document.getElementById('connection-status');
    el.textContent = text;
    el.classList.toggle('text-danger', !!warn);
    el.classList.toggle('fw-bold', !!warn);
    el.classList.toggle('text-muted', !warn);
}

// ---------------------------------------------------------------
// 1-2) 切断中のロック(★Batch 33 設計解説1章)
// ---------------------------------------------------------------

/**
 * ★★切断中に「操作したのに何も起きない」を作らない。
 *
 * <h3>直したこと</h3>
 * 32 までの {@code send()} は接続状態を一切見ておらず、切れたソケットへ
 * <b>無言で publish</b> していた。28 で「無言の {@code location.reload()}」を潰したのと
 * 同じ種類の欠陥がここに残っていた。しかも通話しながら遊ぶ前提では、
 * 「声は聞こえているのに盤面だけ届いていない」という<b>気づきにくい事故</b>になる。
 * 双方が数分気づかないまま進むと、盤面の食い違いは巻き戻して直せない。
 *
 * <h3>番人は send() であり、オーバーレイではない</h3>
 * オーバーレイは<b>宣言</b>である(いま何が起きているかを人間に伝える)。
 * 実際に操作を止めているのは {@code send()} のガード1箇所であり、
 * オーバーレイを畳んで盤面を覗いている間もガードは効いている。
 * 「見えなくすること」を安全装置にすると、覗き見の導線を足した瞬間に穴が開く。
 */
const CONN_BAR_MS = 3500;

let connectionEstablishedOnce = false;   // 一度でも接続できたか(=次は「再接続」)
let connectionFatal = false;             // 部屋消失。切断の案内より優先する
let offlinePeeking = false;              // 「盤面を確認する」でロックを畳んだ状態
let socketDown = false;                  // onWebSocketClose / onStompError の記録
let connBarTimer = null;

/**
 * 接続しているか。★接続の判定はこの1箇所だけが持つ。
 * {@code send()} もオーバーレイもここを見る(ハンドオフ3章「判定を2箇所に書かない」)。
 *
 * ★{@code client.connected} だけに頼らないのは、{@code onWebSocketClose} が呼ばれる
 * 時点でライブラリ内部のフラグが既に倒れている保証を<b>こちらが持てない</b>ためである。
 * 倒れていなければオーバーレイが出ず、しかも<b>音を立てずに</b>出ない。
 * 自分で観測した事実({@code socketDown})を and で重ねておく。
 */
function isConnected() {
    return client.connected === true && !socketDown;
}

function isGateVisible() {
    return !document.getElementById('seat-gate').classList.contains('d-none');
}

/**
 * 接続の帯。{@code text} が null なら隠す。{@code ms} を与えると自動で消える。
 * ★状態は「切断中(offline)」「復帰(ok)」「無し」の3つだけであり、
 * 要素は1つである。増やすなら kind を足すこと(要素を増やさない)。
 */
function setConnBar(text, kind, ms) {
    const bar = document.getElementById('manual-conn-bar');
    if (connBarTimer !== null) {
        clearTimeout(connBarTimer);
        connBarTimer = null;
    }
    bar.classList.remove('manual-conn-bar-offline', 'manual-conn-bar-ok');
    if (!text) {
        bar.textContent = '';
        bar.classList.add('d-none');
        return;
    }
    bar.textContent = text;
    bar.classList.add('manual-conn-bar-' + kind);
    bar.classList.remove('d-none');
    if (ms) {
        connBarTimer = setTimeout(() => {
            connBarTimer = null;
            setConnBar(null);
        }, ms);
    }
}

/**
 * 切断オーバーレイと接続の帯を、いまの状態から描き直す。
 * ★出す/出さないの判定をここ1箇所に閉じる。
 *
 * ★部屋消失({@code connectionFatal})のときは出さない。あちらは「戻らない」の案内であり、
 * 「戻るのを待っている」の案内を重ねると、人間はどちらを信じてよいか分からなくなる。
 * ★席選択ゲートが出ている間も出さない。まだ盤面に入っていない人に
 * 「盤面が操作できません」と言っても意味が無い。
 */
function updateOfflineLock() {
    const offline = !isConnected() && !connectionFatal && !isGateVisible();
    document.getElementById('manual-offline')
        .classList.toggle('d-none', !offline || offlinePeeking);
    if (offline) {
        setConnBar(offlinePeeking
            ? '切断中 — 操作は相手に届きません(再接続中)'
            : null, 'offline');
    }
    // ★接続が戻ったときの帯は onConnect が握る。ここでは消さない
    //   (updateOfflineLock は onConnect から<b>先に</b>呼ばれる)
}

/**
 * ★Batch 28: 部屋がサーバから消えていたときの扱い。
 *
 * <h3>直したこと</h3>
 * 27 まではここで無言の {@code location.reload()} を呼んでいた。
 * 対戦中に突然ページが再読み込みされて盤面が消えるため、
 * <b>人間には「ブラウザが落ちた」としか見えない</b>。実際にマスターから
 * 「たびたびブラウザが落ちる」という報告として上がってきた挙動である。
 *
 * <h3>なぜ黙って直さないのか</h3>
 * 部屋はメモリ上にしか無い(設計判断1)。サーバが再起動すれば部屋は本当に消えており、
 * 再読み込みしても<b>その対戦は戻らない</b>。戻らないものを黙って捨てるのが最悪であり、
 * 「何が起きたか」「今どうなっているか」を出したうえで、次の操作を人間に選ばせる。
 */
/**
 * ページの再読み込み。★1行の関数に切り出してあるのは検証のためである。
 * {@code location.reload} は上書きできないため、検証ハーネスから差し替えられる
 * 入口をここに1つ作る({@code latestView} を直接代入するのと同じ手口)。
 */
function reloadPage() {
    location.reload();
}

function showRoomLostFatal() {
    // ★Batch 33: 切断の案内より優先する。deactivate() は当然 onWebSocketClose を呼ぶが、
    //   「再接続を待ってください」と「この対戦は戻りません」を同時に出してはならない
    connectionFatal = true;
    client.deactivate();
    forgetOccupant();
    setConnBar(null);
    setConnectionStatus('部屋が失われました', true);
    showGateFatal('この部屋はサーバ上に存在しません。'
        + 'サーバの再起動や、長時間の無操作による切断が原因です。'
        + '部屋はサーバのメモリ上にしか無いため、この対戦の盤面は復元できません。');
    // ★showGateFatal はボタン列を隠す。入り直す導線だけを1つ出す
    const box = gateEl('seat-gate-buttons');
    box.innerHTML = '';
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'btn btn-primary w-100';
    btn.textContent = '入り直す';
    // ★このコンテナには席選択の委譲リスナーが付いている(data-seat の無いボタンを
    //   押すと「席に着かない」として送信されてしまう)。伝播を必ず止める
    btn.addEventListener('click', (e) => {
        e.stopPropagation();
        reloadPage();
    });
    box.appendChild(btn);
    box.classList.remove('d-none');
}

/**
 * サーバへ操作を送る。
 *
 * ★★Batch 33: 接続していないときは<b>publish しない</b>。
 * 死んだソケットへ投げても例外は出ず、人間には「押したのに何も起きない」としか
 * 見えない。これが 28 で潰し損ねた最後の「無言」である。
 *
 * ★{@code options.quiet} は矢印({@code dragcue})専用の逃がし口である。
 * 揮発メッセージは1回のドラッグで何十回も飛ぶので、これに毎回トーストを出すと
 * <b>本当の警告が埋もれる</b>。捨てること自体は同じで、黙って捨てるのは
 * 「元々ビューでもログでもないもの」に限る。
 *
 * @return 送ったかどうか(呼び出し側が分岐したくなった場合のため)
 */
function send(action, payload, options) {
    if (!isConnected()) {
        if (!(options && options.quiet)) {
            showToast('切断中 — 操作は相手に届きません');
            // ★ヘッダの接続表示を明滅させる。「どこを見れば状態が分かるか」を
            //   その場で指し示す(21c 3-5 の flashDenied と同じ役割)
            flashDenied(document.getElementById('connection-status'));
        }
        return false;
    }
    client.publish({
        destination: `/app/manual/${ROOM_ID}/${action}`,
        body: JSON.stringify({ occupantId: OCCUPANT_ID, ...payload }),
    });
    return true;
}

// ★「盤面を確認する」= オーバーレイだけ畳む。ガード(send)は効いたままである
document.getElementById('manual-offline-peek').addEventListener('click', () => {
    offlinePeeking = true;
    updateOfflineLock();
});

// ---------------------------------------------------------------
// 1-3) 部屋の共有(★Batch 33 設計解説2章)
// ---------------------------------------------------------------

/**
 * この部屋のURL。★組み立てはこの1箇所だけが知っている。
 * 経路({@code /manual/battle/{roomId}})はサーバの
 * {@code ManualLobbyController#battle} と対になっており、
 * 文字列を2箇所で作ると、片方だけ直したときに<b>黙って壊れたリンクを配る</b>。
 */
function roomShareUrl() {
    return `${location.origin}/manual/battle/${ROOM_ID}`;
}

/**
 * クリップボードへ書く。
 *
 * ★{@code navigator.clipboard} は<b>安全なコンテキスト(https / localhost)でしか
 * 使えない</b>。素の http で開発しているときに黙って何も起きないのが最悪なので、
 * 使えない場合と例外の場合の両方で、旧来の {@code execCommand('copy')} へ落とす。
 * ★成否は必ず呼び出し側へ返す。「コピーできなかった」を無言にしない。
 */
async function copyText(text) {
    try {
        if (navigator.clipboard && window.isSecureContext) {
            await navigator.clipboard.writeText(text);
            return true;
        }
    } catch (e) {
        // 下の代替へ落ちる(理由は問わない。人間に必要なのは成否だけである)
    }
    return copyTextFallback(text);
}

function copyTextFallback(text) {
    const area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    // ★画面外に置くが display:none にはしない。選択できない要素はコピーできない
    area.style.position = 'fixed';
    area.style.top = '-1000px';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    let ok = false;
    try {
        ok = document.execCommand('copy');
    } catch (e) {
        ok = false;
    }
    area.remove();
    return ok;
}

/**
 * コピーボタン1つの配線。★ボタンは3つ(ヘッダのID・ヘッダのリンク・ゲートのリンク)
 * あるが、押したときの振る舞いはここ1箇所にしかない。
 */
function bindCopyButton(el, label, getText) {
    if (!el) return;
    el.addEventListener('click', async (e) => {
        e.stopPropagation();
        const ok = await copyText(getText());
        showToast(ok ? `${label}をコピーしました` : `${label}をコピーできませんでした`);
        if (!ok) flashDenied(el);
    });
}

bindCopyButton(document.getElementById('btn-copy-id'), '部屋ID', () => ROOM_ID);
bindCopyButton(document.getElementById('btn-copy-link'), '部屋リンク', roomShareUrl);
bindCopyButton(document.getElementById('seat-gate-copy'), '部屋リンク', roomShareUrl);

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
            // ★猶予切れで席が空けられた・サーバが再起動して部屋ごと消えた等、
            //   サーバが在室者として認識できなかった場合。
            //   ★28: 無言の location.reload() をやめた(showRoomLostFatal の javadoc)。
            showRoomLostFatal();
            return;
        }
        showTransientError(msg.message);
        return;
    }
    // ★Batch 32a: 配信の反映は applyView に一本化した(8-2章)。
    //   差分を採るのは再描画の<b>前</b>でなければならないので、
    //   「latestView を入れて renderAll を呼ぶ」を2箇所に書けない形にしてある。
    applyView(msg.view);
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

// ★Batch 25b: パレットはカードフェイスと同一の1系統に統一した。
//   タイルは明色ベタ塗り+コントラスト計算(textColorFor)をやめ、
//   フェイスと同じ暗色トーン(.mcard-frame)+明色文字で描く。
// ★★Batch 39: その1系統の<b>値</b>も battle.css の :root へ移した(0章の civColor)。
//   ここに関数は残っていない——フェイスとタイルが同じ関数を呼ぶ形は 25b のままである。

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

/**
 * ★Batch 29: 計測フェーズへの申し送り。
 * 各描画関数は「あとで実測してほしい要素」をここへ置くだけにし、
 * 実際の読み取りは {@link measurePhase} が1回だけ行う。
 */
const pendingMeasure = { manaWrap: null, handRow: null, opponentMana: false, centerRows: [] };

/**
 * ★Batch 29(描画の軽量化): 実測をまとめて1回にする。
 *
 * <h3>何が遅かったか</h3>
 * 27 までは、描画関数がそれぞれ「DOMに載せた直後に幅を実測する」形だった
 * (`applyOpponentManaOverlap` / `applyManaOverlap` / `handCardMaxWidth` + `fitCardWidths`)。
 * ブラウザは<b>書き込みのあとに読み取りが来るたびにレイアウトを確定させる</b>ため、
 * 1回の再描画で強制同期レイアウトが何度も走っていた。
 * 実測では renderAll 49.8ms のうち、各関数の合計を引いた<b>約20msがこの待ち</b>だった
 * (28 設計解説1-3)。
 *
 * <h3>直し方</h3>
 * 「全部書く」→「全部読む」の2相に分ける。読み取りが1箇所に集まれば、
 * レイアウトの確定も1回で済む。各描画関数は要素を {@link pendingMeasure} へ置くだけにした。
 *
 * ★矢印({@code renderDragCues})も要素の中心を実測するので、この相の最後に置く。
 */
function measurePhase() {
    if (pendingMeasure.opponentMana) {
        applyOpponentManaOverlap();
    }
    if (pendingMeasure.manaWrap) {
        applyManaOverlap(pendingMeasure.manaWrap);
    }
    if (pendingMeasure.handRow) {
        fitCardWidths(pendingMeasure.handRow, handCardMaxWidth(pendingMeasure.handRow), 8);
    }
    for (const row of pendingMeasure.centerRows) {
        fitCenterCards(row);
    }
    // ★21c 7-1: アンカー要素を作り直したので、表示中の矢印を引き直す
    renderDragCues();
    // ★Batch 32a: 演出のスポーンもこの read 相に同居させる(8-2章 fxSpawn の javadoc)。
    //   新しい位置を読むのはここが最後であり、書く相と読む相を混ぜないという
    //   29 の規約をそのまま守る
    fxSpawn();
}

function renderAll(view) {
    faceBackImageId = view.backImageId || faceBackImageId;
    cardLocation = new Map();
    zoneAnchors = new Map();
    pendingMeasure.manaWrap = null;
    pendingMeasure.handRow = null;
    pendingMeasure.opponentMana = false;
    pendingMeasure.centerRows = [];
    renderHeader(view);
    renderOpponentTop(view);
    renderOpponentMinions(view);
    renderCenterLine(view);
    renderSelfMinions(view);
    renderManaRow(view);
    renderHand(view);
    renderPiles(view);
    renderLog(view.log, view.logTotal, view.declarations);
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
    // ★Batch 29: 実測はここ1箇所だけで行う(measurePhase の javadoc)
    measurePhase();
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

// ---------------------------------------------------------------
// ツールチップ(★Batch 34 設計解説2章・レビュー A-3)
// ---------------------------------------------------------------
//
// ★何のために付けるのか。**「教える側が名称を言えるようにする」**である。
//   通話しながら遊ぶ前提では、初見者はモーダルを読むより先に口頭で教わる(裁定16)。
//   そのとき困るのは操作の難しさではなく「その左下の…灰色の…枠」という<b>指し方</b>である。
//   カード名とゾーン名がポインタの下に出れば、教える側はそれを読み上げればよい。
//
// ★ついでに操作も書く。この盤面の規約(左=見る / 右=動かす)は<b>業界慣習と逆</b>であり
//   (22 で意図的に反転した)、慣習からの類推が効かない。ヘルプの表に書いてあるだけでは、
//   ヘルプを閉じた瞬間に参照できなくなる。
//
// ★★文言はここ1箇所に集める。これまでは title の文字列が 15 箇所へ直接書かれており、
//   規約を変えたときに全部を直せる保証が無かった(設計判断28 の「同じ情報を2箇所に置かない」)。
//   ★新しいタイルを作ったら、文字列ではなく <b>TITLE_HINTS の組み合わせ</b>で書くこと。
//
// ★★title を出さない要素もある。fx層(演出のゴースト)には付けない。
//   数百ミリ秒で消えるものにツールチップは出ないし、実要素と二重に出る危険がある。

const TITLE_HINTS = {
    zoom: '左=拡大',
    zoomTop: '左=一番上を拡大',
    draw: '左=1枚ドロー',
    tap: '右=タップ/アンタップ',
    openZone: '右=一覧',
    deckFullscreen: '右=全面表示',
    zoomRight: '右=拡大',
    dragMove: 'ドラッグ=移動',
    dragTop: 'ドラッグ=一番上の1枚',
    evolve: '重ねると進化',
    manaStrip: '表/裏のストリップに落とすと向きが決まる',
    flip: 'Shift+クリック=裏返す',
    multi: 'Ctrl/⌘+クリック=複数選択',
    hidden: '対戦部屋では非公開',
};

/**
 * 「名前 — 操作 / 操作」形式の title を組み立てる。
 *
 * ★名前を<b>先頭</b>に置く。ツールチップは幅で切られることがあり、
 * 切られて困るのは操作の続きではなく名前のほうである。
 * ★空の hint は捨てる。呼び出し側が条件式で {@code null} を渡せるようにするためで、
 * これが無いと呼び出し側に if が増え、文言がまたバラける。
 *
 * @param {string} name  カード名・ゾーン名など。空なら操作だけの title になる
 * @param {...(string|null|undefined|false)} hints TITLE_HINTS の値
 */
function tileTitle(name, ...hints) {
    const ops = hints.filter(Boolean).join(' / ');
    const label = (name || '').trim();
    if (!label) return ops;
    return ops ? `${label} — ${ops}` : label;
}

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

    // ★Batch 29: 実測は renderAll 末尾の計測フェーズへ移した(measurePhase の javadoc)
    pendingMeasure.opponentMana = true;
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
    // ★Batch 34: 1文字の見出し([山][禁][確][手])だけでは名前を言えない。
    //   相手側であること・枚数・非公開になりうることまで title で言えるようにする
    chip.title = tileTitle(`相手の${ZONE_LABELS[zoneName]}(${zoneCount(seat, zoneName)}枚)`,
        TITLE_HINTS.zoomTop, TITLE_HINTS.openZone, TITLE_HINTS.hidden);
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

    const face = document.createElement('div');
    face.className = 'manual-opp-face';
    const cards = seat.zones[zoneName] || [];
    const count = zoneCount(seat, zoneName);
    // ★Batch 34: 枚数まで含めて言えるようにする。数えるのは counts 由来の count である
    box.title = tileTitle(`相手の${ZONE_LABELS[zoneName]}(${count}枚)`,
        TITLE_HINTS.zoomTop, TITLE_HINTS.openZone);
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
    tile.title = tileTitle(`相手のマナ: ${card.name || '(不明)'}`,
        card.tapped ? 'タップ済み' : null);
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
    tile.title = tileTitle(`相手の裏向きマナ ${count}枚`,
        tapped ? 'タップ済み' : 'アンタップ');
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
    // ★Batch 30: 実測は renderAll 末尾の計測フェーズへ移した(29 の measurePhase)。
    //   ここで clientWidth を読むと、書き込みの途中でレイアウトが確定してしまう
    //   (29 で他の3箇所は移したが、ここだけ残っていた)
    pendingMeasure.centerRows = [...el.querySelectorAll('.manual-center-row')];
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
            // ★Batch 30: カードと行き先ボタンを縦に組んだ小さな箱にする。
            //   ボタンをカードの中に入れるとクリック規約(左=拡大)と当たり判定が競合する
            const box = document.createElement('div');
            box.className = 'manual-center-card';
            box.appendChild(createHandCard(card, null, null, zoneName));
            box.appendChild(createCenterSendButtons(card));
            row.appendChild(box);
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
 * 共有ゾーンのカードを片付けるボタン(★Batch 30・マスター指示)。
 *
 * <h3>なぜ足すのか</h3>
 * プレイ中(PLAY)へ置くのはほとんどスペルであり、使い終わったら墓地へ行く。
 * 毎回ドラッグさせるのは手数が多い。<b>ドラッグは従来どおり残す</b>。
 * これは選択肢を1つ増やすだけである。
 *
 * <h3>★行き先を2つ並べる</h3>
 * 「墓地へ」だけにしない。禁忌デッキ由来のカードは総合ルール 2-3 で
 * 場を離れると消滅へ行く。どちらへ送るかは<b>裁定</b>であり、
 * 手動モードは判断を実装しない(設計書16 5-1)。
 * アプリが決めずに人間が選ぶ形にすれば、原則を曲げずに手数だけ減らせる。
 *
 * <h3>★送り先の席は「置いた人」である</h3>
 * 共有ゾーンのカードは席に属さないが、{@code placedBySeat} が誰が置いたかを持つ(21 6-2)。
 * 自分の墓地へ送るのではなく<b>置いた人の墓地</b>へ返す。
 * 相手が置いたスペルを自分の墓地に入れると、墓地の枚数を参照するカードの計算が狂う。
 * 所有が失われている場合(古い部屋など)だけ、操作している自席へ落とす。
 */
function createCenterSendButtons(card) {
    const row = document.createElement('div');
    row.className = 'manual-center-send';
    const owner = card.placedBySeat || (latestView ? bottomSeat(latestView).id : null);
    for (const dest of [
        { zone: 'TRASH', label: '墓地へ', cls: 'manual-send-trash' },
        { zone: 'LOST', label: '消滅へ', cls: 'manual-send-lost' },
    ]) {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = dest.cls;
        btn.textContent = dest.label;
        btn.title = tileTitle(`${ZONE_LABELS[dest.zone]}(席${owner})へ送る`);
        // ★カード本体のクリック規約(左=拡大)の外にある専用ボタンである。伝播を必ず止める
        btn.addEventListener('click', (e) => {
            e.stopPropagation();
            send('move', {
                cardIds: [card.instanceId], toSeat: owner, toZone: dest.zone,
                toIndex: null, faceDown: null,
            });
        });
        btn.addEventListener('contextmenu', (e) => { e.preventDefault(); e.stopPropagation(); });
        row.appendChild(btn);
    }
    return row;
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
    // ★26 2章: 自席のリーダーだけフェイス化する(効果テキストを盤面で読めるようにする)。
    //   相手上段は 148px 制約のためテキストなしのまま(マスター裁定)
    slot.appendChild(createLeaderTile(seat, { withWeapon: false, face: true }));
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

    // ★Batch 30(マスター指示): すべてアンタップ。確認パイルの真下(グリッド 2/4)が
    //   もともと空いていたので、盤面を1pxも高くせずに置ける
    el.appendChild(createUntapAllSlot(seat));

    for (const zoneName of Object.keys(PILE_PLACEMENT)) {
        // ★Batch 29: 枚数は必ず counts から取る。山札は中身が「最上段の1枚」しか
        //   届かなくなったため、配列の長さを枚数として使うと常に1と出る
        const pile = createCardPile(
            seat.id, zoneName, seat.zones[zoneName] || [], view.backImageId,
            zoneCount(seat, zoneName));
        pile.style.gridArea = PILE_PLACEMENT[zoneName];
        el.appendChild(pile);
        registerZoneAnchor(pile, seat.id, zoneName);
    }
}

/**
 * 「すべてアンタップ」(★Batch 30・マスター指示)。
 *
 * <h3>★これは判断ではない</h3>
 * 設計書16 5-1 が禁じているのは<b>裁定</b>(コスト支払い・戦闘解決・勝敗判定)である。
 * ここでやるのは「今タップされているものを、まとめてアンタップする」という
 * <b>機械作業の一括化</b>であり、1枚ずつ右クリックするのと結果が1つも変わらない。
 * ターンの開始ごとに10回以上の同じ操作を人間にさせる理由が無い。
 *
 * <h3>★サーバに新しい操作を足していない</h3>
 * {@code tap} / {@code used} は既に「明示値」を受け付ける
 * ({@code request.value()} が null ならトグル、あればその値。18a)。
 * したがって <b>value: false を渡すだけ</b>で冪等な一括アンタップになる。
 * トグルで実現しようとすると「今タップされているものだけを選んで送る」ことになり、
 * 送信の直前に盤面が変わると一部が逆に倒れる。値を明示すれば競合しても結果は同じである。
 *
 * <h3>対象(マスター確定)</h3>
 * 自席のマナ・場のミニオン・リーダーのタップ、およびウェポンの「使用済」。
 * ウェポンを含めるのは、アンタップフェイズが「このターンの行動権を戻す」処理であり、
 * 使用済フラグも同じ意味を持つためである。
 * ★相手席には触らない。代行操作は個別にやるものであり、一括で相手の盤面を戻せるボタンは
 * 事故のときに取り返しがつかない。
 */
function createUntapAllSlot(seat) {
    const slot = document.createElement('div');
    slot.className = 'manual-untap-slot';

    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'manual-untap-btn';
    btn.textContent = 'すべて\nアンタップ';
    btn.style.whiteSpace = 'pre-line';
    btn.title = tileTitle('すべてアンタップ',
        '自席のマナ・場のミニオン・リーダーのタップと、ウェポンの使用済を戻す');
    btn.addEventListener('click', () => {
        const tapIds = [];
        for (const zoneName of ['MANA', 'FIELD']) {
            for (const card of seat.zones[zoneName] || []) {
                if (card.tapped) tapIds.push(card.instanceId);
            }
        }
        if (seat.leader && seat.leader.tapped) {
            tapIds.push(seat.leader.instanceId);
        }
        const usedIds = [];
        for (const card of seat.zones.WEAPON || []) {
            if (card.used) usedIds.push(card.instanceId);
        }
        if (tapIds.length === 0 && usedIds.length === 0) {
            showToast('アンタップするものがありません');
            return;
        }
        // ★value を明示する(トグルではない)。javadoc の「冪等」の理由
        if (tapIds.length > 0) {
            send('tap', { cardIds: tapIds, value: false });
        }
        if (usedIds.length > 0) {
            send('used', { cardIds: usedIds, value: false });
        }
    });
    slot.appendChild(btn);

    const note = document.createElement('div');
    note.className = 'manual-untap-note';
    note.textContent = 'マナ・場・リーダー・ウェポン';
    slot.appendChild(note);
    return slot;
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
        // ★Batch 26 3章: ウェポン枠もテキストを出す('full')。装備中のウェポンは
        //   盤面に出しっぱなしになるカードであり、効果を読めない理由が無い
        face.appendChild(cardFace(card, 'full'));
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
        used.title = tileTitle('ウェポンの使用済', '左=使用済/未使用を切り替える');
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
 * ★Batch 26 1章: パイル面をそのまま掴んで「一番上の1枚」を動かせるゾーン。
 *
 * 20a では山札だけがドラッグ可能で、墓地・消滅・確認は右クリックで帯を開くしか
 * 動かす手段が無かった。画面上は同じ「カードが敷かれた面」に見えるため、
 * 掴めるものと掴めないものが混在していること自体が説明できない。
 *
 * ★禁忌({@code TABOO})は対象外である。裏面が敷かれた非公開パイルであり、
 * 「見えているカード」が存在しない(= どの1枚を掴んだのか人間が言えない)。
 * 山札も非公開だが、こちらは「次に引く1枚」という一意な意味が既にある。
 */
const DRAGGABLE_PILE_ZONES = new Set(['DECK', 'TRASH', 'LOST', 'PRIVATE']);

/**
 * パイルの「一番上の1枚」。★取り方がゾーンによって逆である(20a 2-1)。
 *
 * 山札は index 0 が最上段({@code ManualGameService.drawCards} が
 * {@code deck.remove(0)} で引くのに合わせている)。
 * 公開パイル(墓地・消滅・確認)の最上段は<b>末尾</b>であり、
 * {@link createCardPile} が面に敷いているカードもこちらである。
 * 取り違えると「見えているカードと違う1枚が動く」という最悪の壊れ方をする。
 */
function pileTopCard(zoneName, pile) {
    if (!pile || pile.length === 0) {
        return null;
    }
    return zoneName === 'DECK' ? pile[0] : pile[pile.length - 1];
}

/**
 * カード型パイル(禁忌・山札・確認・消滅・墓地。20b 2-6)。
 *
 * ★見た目をカード画像にする。枚数と文字だけの箱では「現実のカードゲームに近い画面」
 * という本バッチの方針から外れるため、90×126のカード面に画像を敷き、枚数はその角に重ねる。
 * 非公開パイル(山札・禁忌)は裏面画像、公開パイル(確認・消滅・墓地)は一番上の表画像を使う。
 *
 * ★Batch 26 3章: 表向きの最上段は 'full' フェイス(名前+種別+テキスト+印刷値)で描く。
 * バリアントは増やさず、入れ物側のCSS({@code .manual-pile-face})で文字を1段落とす。
 */
function createCardPile(seatId, zoneName, pile, backImageId, count) {
    const box = document.createElement('div');
    box.className = 'manual-pile';
    box.dataset.seat = seatId;
    box.dataset.zone = zoneName;

    // ★Batch 29: 枚数は counts 由来の値を使う。届いた配列の長さではない。
    //   山札は「最上段の1枚」だけが届くため、配列を数えると常に1になる
    //   (中身は全面表示を開いたときに /manual/api/rooms/{id}/zone から取る)。
    const total = count === undefined || count === null ? pile.length : count;

    const face = document.createElement('div');
    face.className = 'manual-pile-face';
    const hidden = PRIVATE_PILE_ZONES.has(zoneName);
    // ★一番上の1枚。ゾーンによって先頭/末尾が逆である(pileTopCard の javadoc)。
    //   面に敷くカードとドラッグで動くカードを<b>同じ式</b>から取ることで、
    //   「見えているカードと違う1枚が動く」壊れ方を構造的に起こせなくする(26 1章)。
    const top = pileTopCard(zoneName, pile);
    if (total === 0) {
        face.classList.add('manual-pile-blank');
    } else if (hidden || (top && top.faceDown) || !top) {
        face.appendChild(cardBackFace());
    } else {
        face.appendChild(cardFace(top, 'full'));
    }

    const countBadge = document.createElement('div');
    countBadge.className = 'manual-pile-count';
    countBadge.textContent = total;
    face.appendChild(countBadge);
    box.appendChild(face);

    const header = document.createElement('div');
    header.className = 'manual-pile-label';
    header.textContent = ZONE_LABELS[zoneName];
    box.appendChild(header);

    registerDropTarget(box, seatId, zoneName);

    // ★Batch 26 1章: パイル面のドラッグ = 一番上の1枚。
    //   20a では山札だけの機能だったが、墓地・消滅・確認へ一般化した。
    //   ゾーンごとに違うのは「どのカードが一番上か」だけなので、分岐は pileTopCard に閉じる。
    //
    //   ★20a 2-1(A1/A2): 空のパイルはドラッグを開始しない。複数選択中でも常に1枚である
    //   (「どの1枚か」を面の上で選べないため、選択集合を持ち込むと意図しないカードが動く)。
    //
    //   ★A4(実マウス検証での訂正、2回目): dragstart の e.target は「実際にドラッグ対象に
    //   なった要素(= box 自身)」であり、実際に掴んだ場所の要素ではない
    //   (ブラウザは mousedown 位置から祖先方向へ draggable=true を探して box に行き着くため、
    //   e.target は最初から box になる。e.target.closest(...) では絶対に一致しない)。
    //   実際に指が置かれた場所を見るには dragstart 時点の座標で document.elementFromPoint を使う。
    if (DRAGGABLE_PILE_ZONES.has(zoneName) && pile.length > 0) {
        const dragCard = pileTopCard(zoneName, pile);
        box.draggable = true;
        box.addEventListener('dragstart', (e) => {
            const origin = document.elementFromPoint(e.clientX, e.clientY);
            if (origin && origin.closest('.zone-drop-mini, button')) {
                e.preventDefault();
                return;
            }
            onDragStart(e, dragCard, seatId, zoneName);
        });
    } else {
        box.draggable = false;
    }

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
        box.title = tileTitle(`山札(${total}枚)`,
            TITLE_HINTS.draw, TITLE_HINTS.deckFullscreen, TITLE_HINTS.dragTop);

        // ★ドラッグ(一番上の1枚)の登録は上の共通ブロックへ移した(26 1章)。

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
        box.title = tileTitle(`${ZONE_LABELS[zoneName]}(${total}枚)`,
            TITLE_HINTS.zoomTop, TITLE_HINTS.openZone,
            DRAGGABLE_PILE_ZONES.has(zoneName) ? TITLE_HINTS.dragTop : null);
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

    // ★Batch 29: 実測はここで行わない。renderAll 末尾の計測フェーズにまとめる
    //   (measurePhase の javadoc)。要素だけ覚えておく
    pendingMeasure.manaWrap = wrap;
}

/** マナのストリップ1つ(表 or 裏)。ストリップ全体がドロップ対象(設計書2-3) */
function createManaStrip(label, cards, seatId, faceDown, backImageId) {
    const strip = document.createElement('div');
    strip.className = 'mana-strip' + (faceDown ? ' mana-strip-down' : ' mana-strip-up');
    // ★★Batch 34 hotfix: 幅は枚数に比例させる(CSS の 6:4 固定をやめた)。
    //   ★下駄(MANA_STRIP_BASE_GROW)を履かせるのは、0枚の側を潰さないためである。
    //     マナのストリップは<b>ドロップ先</b>でもあり、幅0にすると
    //     「表向きに置く」操作そのものが画面から消える。
    strip.style.flexGrow = String(MANA_STRIP_BASE_GROW + cards.length);

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
        chip.title = tileTitle('マナ(裏向き)',
            TITLE_HINTS.zoom, TITLE_HINTS.tap, TITLE_HINTS.dragMove,
            TITLE_HINTS.flip, TITLE_HINTS.multi);
    } else {
        if (card.civilization) {
            applyCivFrame(chip, card.civilization);
        }
        const name = document.createElement('div');
        name.className = 'mana-tile-name';
        name.textContent = card.name || '';
        chip.appendChild(name);
        chip.title = tileTitle(`${card.name || 'マナ'}(マナ)`,
            TITLE_HINTS.zoom, TITLE_HINTS.tap, TITLE_HINTS.dragMove,
            TITLE_HINTS.flip, TITLE_HINTS.multi);
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
 */
/** マナタイルの寸法。★CSS の .mana-tile と対である(片方だけ変えないこと) */
const MANA_TILE_WIDTH = 64;
const MANA_TILE_HEIGHT = 88;
/**
 * 1枚あたり露出させたい幅。★Batch 34 hotfix: 1つの定数から2つへ増やした。
 *
 * 従来は 28px の1つだけで、入らないときは<b>諦めてはみ出していた</b>。
 * はみ出した先が右列(ウェポン枠)であり、重なった部分は押せなくなる。
 * 「読みやすさ」と「隣を侵食しないこと」が衝突したとき、優先されるのは後者である。
 * 読みにくいマナは読み直せるが、押せないウェポンは操作できない。
 */
const MANA_MIN_EXPOSURE = 28;
/** ★これ以上は詰めない下限。ここまで詰めても入らないときはトラック側が横スクロールする */
const MANA_HARD_EXPOSURE = 14;
/**
 * 表/裏ストリップの幅の下駄。★枚数0の側を潰さないための値である。
 * 幅は「下駄 + 枚数」の比で配る(createManaStrip)。3 はタイル約1.8枚ぶんにあたる。
 */
const MANA_STRIP_BASE_GROW = 3;

/**
 * マナの重ね表示。★Batch 30: 回転したタップ済タイルのぶんの幅を確保する。
 *
 * <h3>なぜ確保が要るのか</h3>
 * 30 でマナのタップ表現を回転({@code rotate(90deg)})へ戻した。
 * {@code transform} は<b>レイアウトに影響しない</b>ため、レイアウト上は 64px のままだが
 * 見た目の外接は 88px になる。差の 24px を確保しないと、実測で
 * <b>9箇所中8箇所で隣のタイルに食い込む</b>(26 で回転をやめた理由の1つがこれである)。
 *
 * 左右へ 12px ずつのマージンで確保し、幅の見積り(naturalWidth)でも
 * タップ済は 88px として数える。これで「入るなら重ならない、入らないなら詰める」という
 * 元の1本の式がそのまま成り立つ。
 */
function applyManaOverlap(wrap) {
    const pad = (MANA_TILE_HEIGHT - MANA_TILE_WIDTH) / 2;
    for (const track of wrap.querySelectorAll('.mana-strip-track')) {
        const tiles = [...track.children];
        for (const tile of tiles) {
            tile.style.marginLeft = '';
            tile.style.marginRight = '';
            tile.style.zIndex = '';
        }
        if (tiles.length === 0) continue;
        const tapped = tiles.map((t) => t.classList.contains('tapped'));
        // ★回転したタイルは外接が MANA_TILE_HEIGHT(88px)になる
        const footprint = (i) => (tapped[i] ? MANA_TILE_HEIGHT : MANA_TILE_WIDTH);
        let naturalWidth = 0;
        tiles.forEach((tile, i) => { naturalWidth += footprint(i); });
        // ★clientWidth は padding を<b>含む</b>。トラックには padding: 1px 3px があるので、
        //   そのまま使うと左右6pxぶん多く見積もり、その6pxが枠の外へ出る。
        //   「はみ出さない」を目標にする以上、比べる相手は<b>中身が置ける幅</b>である。
        const cs = window.getComputedStyle(track);
        const trackWidth = track.clientWidth
            - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);

        // ★★Batch 34 hotfix: 重なりの上限を<b>そのタイル自身の占有幅</b>から引く。
        //   従来は常に MANA_TILE_WIDTH(64)基準で上限 36px としていた。
        //   ところがタップ済のタイルは占有幅が 88px なので、36px しか重ねられないと
        //   1枚あたり 52px も食う。15枚すべてタップ済だと 816px 必要になり、
        //   裏ストリップ(実測 323px)を<b>約 480px はみ出して</b>右列を覆っていた。
        //   基準をタイルごとにすると、タップ済は 88 - 28 = 60px まで重ねられる。
        const overlapCapAt = (exposure) => Math.min(
            ...tiles.slice(1).map((t, k) => footprint(k + 1) - exposure));
        const needed = tiles.length <= 1 || naturalWidth <= trackWidth
            ? 0
            : (naturalWidth - trackWidth) / (tiles.length - 1);
        // ★まず 28px の露出で詰め、それで足りなければ 14px まで譲る。
        //   「入らないなら詰める」の一段深い版であり、判断が増えたわけではない。
        let perTileOverlap = Math.min(needed, overlapCapAt(MANA_MIN_EXPOSURE));
        if (needed > perTileOverlap) {
            perTileOverlap = Math.min(needed, overlapCapAt(MANA_HARD_EXPOSURE));
        }
        tiles.forEach((tile, i) => {
            const base = tapped[i] ? pad : 0;
            tile.style.marginLeft = `${base - (i > 0 ? perTileOverlap : 0)}px`;
            tile.style.marginRight = `${base}px`;
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
    // ★Batch 29: 実測は renderAll 末尾の計測フェーズへ移した(measurePhase の javadoc)
    pendingMeasure.handRow = row;
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
    // ★★Batch 26 4章(不具合修正): 文書相対の top で測る。
    //   getBoundingClientRect().top は<b>ビューポート相対</b>である。ページがスクロール
    //   していると実位置より小さく測れ、「残り高さ」が過大になって手札が肥大する
    //   (逆方向にも起きる)。再描画のたびにそのときのスクロール位置で幅が決まるため、
    //   ビュー更新を伴う最頻の操作であるタップ/アンタップで「サイズが崩れる」と観測された。
    //   盤面はビューポート内に収める設計なので、基準は「文書の先頭からの位置」が正しい。
    // ★★Batch 30(不具合修正): 測る基準を<b>行そのもの</b>から<b>入れ物(#hand-row)</b>へ変えた。
    //
    //   25c 以来、行の上端から画面下端までを「使える高さ」としていた。しかし行の外側には
    //   入れ物の上下パディングがあり、そのぶんだけ手札が画面外へはみ出していた
    //   (実測: プレイ中ゾーンにカードを置いた状態で手札の下端が 953px。ビューポートは 950px)。
    //
    //   ★行の「下の余白」をその場で測る形も試したが誤りである。計測の時点では
    //   カードにまだ幅が入っておらず、行の高さが最終値ではないため、
    //   そこから引いた値は毎回ずれる。<b>入れ物の上端とパディング</b>は
    //   カードの大きさに依存しないので、基準として安定している。
    const parent = row.parentElement || row;
    const box = parent.getBoundingClientRect();
    const top = box.top + window.scrollY;
    if (!top || top <= 0) {
        return fallback;
    }
    const style = window.getComputedStyle(parent);
    const padding = (parseFloat(style.paddingTop) || 0) + (parseFloat(style.paddingBottom) || 0);
    const available = window.innerHeight - top - padding - 12;
    if (available < 84) {
        return fallback;
    }
    return Math.max(60, Math.min(190, Math.floor(available * 5 / 7)));
}
/**
 * センターライン(プレイ中・公開)のカード幅の上限。
 *
 * ★Batch 30: 上限を 90px → 118px へ広げた(マスター指示)。
 * 見出しを帯の左へ寄せて横並びにしたことで、見出しが占めていた縦の1行が空いた。
 * プレイ中ゾーンには普通1〜2枚しか置かないので、幅を使い切れずに余っていた。
 */
function centerCardMaxWidth() {
    return Math.max(45, Math.min(106, Math.round(window.innerHeight * 0.108)));
}

/** 行き先ボタンの列が占める横幅(★Batch 30)。CSS の .manual-center-send と対である */
const CENTER_SEND_WIDTH = 46;

/**
 * センターラインの札の幅を決める(★Batch 30)。
 *
 * ★手札({@link fitCardWidths})と別の関数にしたのは、札が
 * 「カード + 行き先ボタン列」の箱に包まれており、幅を当てる相手が箱の中にあるためである。
 * 共通化して分岐を1つ増やすより、短い関数を2つ置くほうが読める。
 */
function fitCenterCards(row) {
    const boxes = [...row.children].filter((el) => el.classList.contains('manual-center-card'));
    if (boxes.length === 0) {
        return;
    }
    const gap = 6;
    const available = row.clientWidth - 4;
    const raw = Math.floor((available - gap * (boxes.length - 1)) / boxes.length)
        - CENTER_SEND_WIDTH;
    const width = Math.max(45, Math.min(centerCardMaxWidth(), raw));
    for (const box of boxes) {
        const card = box.querySelector('.manual-hand-card');
        if (card) {
            card.style.width = width + 'px';
        }
    }
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
/** 画面に残すログ行の上限(★Batch 29)。これを超えたぶんは古いほうから捨てる。 */
const LOG_DOM_MAX = 300;

/**
 * ログ1行を組み立てる。★Batch 30: カード名をクリックで拡大できるようにする。
 *
 * <h3>★サーバは1文字も変えていない</h3>
 * {@code ManualLogRenderer} は既にカード名を <b>{@code 《名前》}</b> で囲んで出している。
 * つまり「どこがカード名か」は本文の中に構造として残っている。
 * ログを構造化して配る(ManualLogView にカードの配列を足す)案もあったが、
 * 配信が太るうえ、レンダラと配信の2箇所が名前の切り出し方を知ることになる。
 * <b>既にある印を読む</b>ほうが安い。
 *
 * <h3>★引けた名前だけをリンクにする</h3>
 * カード定義({@code /manual/api/card-library})に無い名前はただの文字として残す。
 * 対戦部屋でマスクされた行や、突合できていないカードがそれにあたる。
 * 「押せそうなのに何も起きない」を作らないため、引けたものだけを押せる形にする。
 */
function appendLogText(line, text) {
    const pattern = /《([^》]{1,40})》/g;
    let last = 0;
    let match = pattern.exec(text);
    while (match !== null) {
        if (match.index > last) {
            line.appendChild(document.createTextNode(text.slice(last, match.index)));
        }
        const card = cardByName(match[1]);
        if (card) {
            const link = document.createElement('span');
            link.className = 'manual-log-card';
            link.textContent = match[0];
            link.title = tileTitle('ログ中のカード', TITLE_HINTS.zoom);
            link.addEventListener('click', (e) => { e.stopPropagation(); setZoom(card); });
            line.appendChild(link);
        } else {
            line.appendChild(document.createTextNode(match[0]));
        }
        last = match.index + match[0].length;
        match = pattern.exec(text);
    }
    if (last < text.length) {
        line.appendChild(document.createTextNode(text.slice(last)));
    }
}

/** 最後に描いたログの通し番号。null は「まだ何も描いていない」(★Batch 29) */
let logLastSeq = null;

/**
 * ★Batch 29(描画の軽量化): ログを差分追記にする。
 *
 * <h3>何が遅かったか</h3>
 * 27 までは配信のたびに {@code box.innerHTML = ''} して全行を作り直していた。
 * 実測で renderAll 49.8ms のうち <b>16.6ms がこの関数</b>であり、
 * しかも<b>行数に比例して増える</b>(28 設計解説1-3)。
 * 実際に増えるのは末尾の1行だけなのに、毎回全部を捨てて作り直していた。
 *
 * <h3>直し方</h3>
 * ログは追記専用で通し番号が単調増加する(Undo でも巻き戻らない。設計書16 5-5)。
 * したがって「最後に描いた seq より後だけ足す」で足りる。
 *
 * ★全消し再構築が要るのは次の3つだけである。
 * <ol>
 *   <li>初回(まだ何も描いていない)</li>
 *   <li><b>取りこぼし</b> — 届いた先頭の seq が、描いてある最後の seq + 1 より大きい。
 *       再接続や、29 で入れた末尾60行の制限で古い行が届かなくなった場合に起きる</li>
 *   <li>巻き戻り — 届いた末尾の seq が描いてある最後より小さい(別の部屋を描くなど)</li>
 * </ol>
 * この3つを見落とすと「行が飛ぶ」「二重に出る」という、静かで気づきにくい壊れ方をする。
 *
 * <h3>★Batch 35: 決着行の強調</h3>
 * 強調する行は {@code declarations} の {@code seq} で指す。行そのものを調べて
 * 「決着っぽい文か」を判定しない——それは 21a が捨てた「文字列から意味を復元する」ことである。
 * ★<b>差分追記と両立する</b>: 宣言の行と declarations は<b>同じ配信</b>に乗って届くので
 * (サーバが同じ範囲から作る。2-3)、追記のその場で印を当てられる。
 * 作り直しのときも全行を回すので、印は同じ1行にだけ復元される。
 *
 * @param entries      届いたログ(★末尾60行だけである。ManualViewBuilder.LOG_TAIL)
 * @param total        サーバが持っているログの総行数。省略が起きていることの案内に使う
 * @param declarations ★Batch 35: 届いたログの中の勝敗宣言。決着行の強調に使う
 */
function renderLog(entries, total, declarations) {
    const box = document.getElementById('log-box');
    const list = entries || [];
    const decisive = new Set((declarations || []).map((d) => d.seq));
    const first = list.length > 0 ? list[0].seq : null;
    const last = list.length > 0 ? list[list.length - 1].seq : null;
    const mustRebuild = logLastSeq === null
        || list.length === 0
        || first > logLastSeq + 1
        || last < logLastSeq;

    if (mustRebuild) {
        box.innerHTML = '';
        logLastSeq = null;
    }

    let appended = 0;
    for (const e of list) {
        if (logLastSeq !== null && e.seq <= logLastSeq) {
            continue;
        }
        const line = document.createElement('div');
        line.dataset.seq = e.seq;
        if (decisive.has(e.seq)) {
            line.className = 'manual-log-decisive';
        }
        appendLogText(line, `[${e.time}] ${e.text}`);
        box.appendChild(line);
        logLastSeq = e.seq;
        appended++;
    }
    // ★DOMを無制限に伸ばさない。古い行は画面から捨てる(サーバは全行を持ったままである)。
    //   ★数えるのは行だけである。先頭の省略案内(data-seq を持たない)を消さない
    const lines = [...box.querySelectorAll('div[data-seq]')];
    for (let i = 0; i < lines.length - LOG_DOM_MAX; i++) {
        box.removeChild(lines[i]);
    }

    renderLogOmittedNote(box, total);

    // ★scrollHeight の読み取りはレイアウトを確定させる。足した時だけにする
    if (appended > 0) {
        box.scrollTop = box.scrollHeight;
    }
}

/**
 * 「以前のログは省略されている」ことの案内(★Batch 29)。
 * ★案内を出すのは、配信が末尾60行に絞られたことを人間が知らないと
 * 「古いログが消えた」と受け取ってしまうためである。実際には消えていない
 * (サーバは全行を保持し、[ログ書出]は全文を返す)。
 */
function renderLogOmittedNote(box, total) {
    const shown = box.querySelectorAll('div[data-seq]').length;
    const omitted = total === undefined || total === null ? 0 : Math.max(0, total - shown);
    let note = document.getElementById('log-omitted-note');
    if (omitted === 0) {
        if (note) note.remove();
        return;
    }
    if (!note) {
        note = document.createElement('div');
        note.id = 'log-omitted-note';
        note.className = 'small fst-italic';
    }
    note.textContent = `— 以前の ${omitted} 行は [ログ書出] から —`;
    // ★常に先頭に置く。追記で下へ伸びても、案内は「ここより上が省略」を指し続ける
    if (box.firstElementChild !== note) {
        box.insertBefore(note, box.firstElementChild);
    }
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
/**
 * タップ状態のバッジ(★Batch 26 4章)。
 *
 * <h3>なぜ回転をやめたか</h3>
 * 20b からタップは {@code transform: rotate(90deg)} で表していた。
 * 25c でタイルを縦長のフェイス(122×144 相当)にしたため、回転後の外接矩形は
 * 144×122 になり<b>隣のタイルに視覚的に重なる</b>。transform はレイアウトに影響しないので
 * 崩れはしないが、重なりと「テキストが横倒しで読めない」の2つが同時に起きる。
 *
 * 相手上段は 21c 4章の時点で既に減光({@code .manual-opp-tapped})で表しており、
 * 減光+バッジへ揃えることで<b>盤面の上下で同じ記法</b>になる(マスター裁定 2026-08-06)。
 * マナタイルは枚数が多く文字を足すと読めなくなるため、減光のみでバッジは持たない。
 */
function tappedBadge() {
    const badge = document.createElement('div');
    badge.className = 'manual-tapped-badge';
    badge.textContent = 'タップ';
    badge.setAttribute('aria-hidden', 'true');
    return badge;
}

function createFieldTile(card, seatId, zone) {
    const tile = document.createElement('div');
    tile.className = 'manual-tile';
    tile.dataset.instanceId = card.instanceId;
    tile.draggable = true;
    // ★Batch 34(2章): 名前と操作を title に出す。
    //   ★タップ状態も書く。場のタイルのタップ表現は<b>減光</b>であり(30 の非対称)、
    //     マナの回転と違って「暗いだけ」に見えるので、文字で言えるほうがよい。
    tile.title = tileTitle(
        card.faceDown ? `${ZONE_LABELS[zone] || '場'}のカード(裏向き)`
                      : `${card.name || '(不明)'}${card.tapped ? '(タップ済み)' : ''}`,
        TITLE_HINTS.zoom, TITLE_HINTS.tap,
        `${TITLE_HINTS.dragMove}(${TITLE_HINTS.evolve})`,
        TITLE_HINTS.flip, TITLE_HINTS.multi);

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
        tile.appendChild(tappedBadge());
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

/**
 * 数値1つの表示(現在値)。★Batch 26 5章で差分チップを廃止した。
 *
 * <h3>なぜ廃止したか</h3>
 * 22 で入れた差分チップ({@code .manual-stat-chip})は、現在値の隣に {@code +2} / {@code -1}
 * の小箱を並べる形だった。ATK と HP の<b>両方</b>が印刷値と違うとき、
 * フェイスタイルの足(⚔n [+2] ✎ ♥m [-1])が幅を超えて表示が崩れる。
 * 25c でタイルをフェイス化して足が固定高の帯になったため、崩れが目に見える形で出た。
 *
 * <h3>代わりに何を出すか</h3>
 * 出すのは「現在値が印刷値と違う」という<b>事実だけ</b>である。数値を白+下線にし、
 * {@code title} に印刷値を入れる。差分の値そのものは、幅を1文字も使わずに
 * ホバー(title)と ✎ のモーダル(printed が出ている)から取れる。
 * 増減の方向(バフ/デバフ)の色分けも行わない。足の幅に収まることを最優先とする。
 *
 * ★戻り値は常に単一の span である(22 までは差分ありのとき wrap を返していた)。
 * 呼び出し側は変更していない。
 */
function statSpan(current, printed) {
    const span = document.createElement('span');
    span.textContent = current === null || current === undefined ? '-' : current;
    if (printed !== null && printed !== undefined && current !== null && current !== undefined
            && current !== printed) {
        span.classList.add('manual-stat-changed');
        span.title = tileTitle(`印刷値 ${printed}`);
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
 * <h3>★Batch 26 2章: フェイス化するかどうかも<b>呼び出し側</b>が決める</h3>
 * 場のミニオン(25c の {@code .manual-tile-face})と同じ3段構成
 * (頭=♛+名前 / 胴=効果テキスト / 足=LPボタン)にすると、リーダーの起動能力が
 * 盤面のまま読める。ただしこれは<b>自席のみ</b>である。相手上段は 148px の高さ制約が
 * あり、テキストを積む縦が無い(マスター裁定 2026-08-06)。
 * ここでも「自席かどうか」をこの関数に判定させない。{@code face} を引数で受ける。
 *
 * @param options {withWeapon} true のときだけ WEAPON のドロップ先・アンカー・
 *                ミニタイルを持つ。省略時は false(= ウェポンは別枠にある)
 * @param options {face} true のとき3段のフェイス構成にする(自席のみ。26 2章)
 */
/**
 * リーダータイルのLP増減ボタン(★Batch 30)。
 *
 * ★サーバは {@code delta} を既に受け付ける({@code ManualOpRequest.Lp})。
 * 直接指定({@code value})と増減({@code delta})の<b>どちらか一方だけ</b>を送ること
 * (両方送るとサーバが例外を投げる。20a 2-4)。
 */
function lpStepButton(label, delta, seat) {
    const btn = document.createElement('div');
    btn.className = 'manual-lp-step';
    btn.textContent = label;
    btn.title = tileTitle(`LPを${delta > 0 ? '1増やす' : '1減らす'}`);
    // ★1-6: カード本体のクリック規約(左=拡大 / 右=タップ)の外にある専用ボタンである
    btn.addEventListener('click', (e) => {
        e.stopPropagation();
        send('lp', { seat: seat.id, delta });
    });
    btn.addEventListener('contextmenu', (e) => { e.preventDefault(); e.stopPropagation(); });
    return btn;
}

function createLeaderTile(seat, options) {
    const withWeapon = !!(options && options.withWeapon);
    const face = !!(options && options.face);
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
    // ★Batch 34(2章): リーダーは「リーダー」としか呼びようが無い位置にあるが、
    //   カード名で呼べるほうが教えやすい。ウェポン合体タイルはその旨も添える
    tile.title = tileTitle(
        `${card.name || 'リーダー'}(席${seat.id}のリーダー)${card.tapped ? '(タップ済み)' : ''}`,
        TITLE_HINTS.zoom, TITLE_HINTS.tap,
        withWeapon ? 'ウェポンをここへ落とすと装備' : null);

    // ★20d→25b: リーダーを文明の色で塗る。場のタイルと同じ applyCivFrame を使い、
    //   色と枠の決め方を1箇所に揃える(フェイスと同一パレット)。
    //   突合できていないリーダー(civilization が無い)は既定の灰色のままにする。
    if (card.civilization) {
        applyCivFrame(tile, card.civilization);
    }

    const name = document.createElement('div');
    name.className = 'manual-tile-name';
    name.textContent = card.name || 'リーダー';

    // ★22 4-3: LP は「押せば変えられる」ことが画面に書かれている形にする
    const lp = statButton('LP ' + seat.lp, () => openLpModal(seat.id, seat.lp));
    lp.classList.add('manual-tile-stats');

    if (face) {
        // ★26 2章: 場のミニオンと同じ3段構成。CSS も .mtf-* をそのまま共有する
        //   (見た目の系統を1つに保つ。リーダー専用の記法を増やさない)。
        //   頭のコスト六角形の位置には ♛ を置く。リーダーにコストは無い(裁定: 0固定)ため、
        //   数字を出すと「0コストで出せるカード」に読めてしまう。
        tile.classList.add('manual-tile-face', 'manual-leader-face');
        const head = document.createElement('div');
        head.className = 'mtf-head';
        const crown = document.createElement('span');
        crown.className = 'mtf-cost mtf-crown';
        crown.textContent = '♛';
        crown.setAttribute('aria-hidden', 'true');
        head.appendChild(crown);
        head.appendChild(name);
        tile.appendChild(head);

        const body = document.createElement('div');
        body.className = 'mtf-body';
        const text = document.createElement('div');
        text.className = 'mtf-text';
        text.textContent = cardFaceText(card);
        body.appendChild(text);
        tile.appendChild(body);

        const foot = document.createElement('div');
        foot.className = 'mtf-foot';
        // ★Batch 30(マスター指示): LPは盤面から直接1ずつ増減できるようにする。
        //   ダメージは1点刻みで動くことが多く、そのたびにモーダルを開いて閉じるのは手数である。
        //   ★中央のLPチップは従来どおりモーダルの入口として残す(直接入力はそちら)。
        const lpRow = document.createElement('div');
        lpRow.className = 'manual-lp-row';
        lpRow.appendChild(lpStepButton('−', -1, seat));
        lpRow.appendChild(lp);
        lpRow.appendChild(lpStepButton('＋', 1, seat));
        foot.appendChild(lpRow);
        tile.appendChild(foot);
    } else {
        tile.appendChild(name);
        tile.appendChild(lp);
    }

    if (card.tapped) {
        tile.classList.add('manual-tile-tapped');
        tile.appendChild(tappedBadge());
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
 * ★4-3: 印刷値との差(現在は {@code .manual-stat-changed} の下線)はここを通さない。
 * あれは状態の表示であって操作ではなく、ボタン化すると
 * 「押せるもの」と「読むもの」が混ざる。
 */
function statButton(content, onClick) {
    const btn = document.createElement('div');
    btn.className = 'manual-stat-button';
    btn.title = tileTitle('数値を編集', '左=編集モーダルを開く');
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
    mini.title = tileTitle(`${card.name || 'ウェポン'}(装備中のウェポン)`,
        TITLE_HINTS.zoom, 'ダブルクリック=編集');

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

    // ★Batch 34: 名前と操作を title に出す(2章)。裏向きは名前を漏らさない。
    //   ★<b>見えていないものを title で教えない</b>。ツールチップは公開範囲の外にある
    //   仕組みではなく、画面に出ているものの読み上げにすぎない。
    wrap.title = tileTitle(
        card.faceDown ? `${ZONE_LABELS[zone] || '手札'}のカード(裏向き)`
                      : `${card.name || '(不明)'}(${ZONE_LABELS[zone] || '手札'})`,
        TITLE_HINTS.zoom, TITLE_HINTS.zoomRight, TITLE_HINTS.dragMove,
        TITLE_HINTS.flip, TITLE_HINTS.multi);

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
    // ★Batch 33: 矢印は揮発メッセージなので、切断中は黙って捨てる(send の javadoc)
    send('dragcue', { cardId, toSeat: null, toZone: null, active: true }, { quiet: true });
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
        }, { quiet: true });
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
    send('dragcue', { cardId: null, toSeat: null, toZone: null, active: false }, { quiet: true });
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
// 8-2) エフェクト層(★Batch 32a。設計書 notes/batch32-effects-design.md 1〜2章)
//
// ★★<b>全消し再描画のまま演出を成立させる</b>。動く要素を盤面DOMの外
//   (position: fixed のオーバーレイ)に置けば、下で renderAll が全消し再構築しても
//   演出は生き続ける。ドラッグ軌跡(21c の #manual-cue-layer)が既にこの形である。
//
// ★★<b>イベントはビューの差分で検出する</b>(設計書 1-1)。サーバは1行も変えていない。
//   (a) 視点フィルタが構造的に効く。差分は「自分に届いたビュー」にしか基づかないので、
//       見えないカードは差分に現れない。相手の手札の中身が演出で漏れることは原理的に無い。
//   (b) 相手・観戦者の画面にも同じ演出が出る(各自が自分のビューの差分から独立に描く)。
//   (c) ★<b>判断を実装しない原則</b>(設計書16 5-1)を破りようがない。差分が語れるのは
//       「どこからどこへ動いたか」「いくつ増減したか」という観測事実だけであり、
//       「破壊されたから墓地へ」という解釈はどこにも入り込まない。
//
// ★ログを演出の材料にしない(32 の裁定)。配信されるログは描画済みテキストであり
//   書式に依存した検出は書式を直した瞬間に黙って壊れる。
//   ★★Batch 35 の勝敗の帯もこの裁定を破っていない。読むのはログの本文ではなく
//     ビューの declarations(構造)であり、検出は他と同じ「前後のビューの比較」である。
//     宣言は盤面に一切触らない操作なので、盤面の差分には現れない——だから
//     <b>サーバ側に観測できる形を1つ足した</b>(設計書 2-2/2-3)。
//     「材料にしてよいのは、書式ではなく構造である」がこの裁定の一般形である。
// ---------------------------------------------------------------

/** 同時に走らせる演出の上限(設計書 2-3)。超えたぶんは演出なしで即着地させる */
const FX_LIMIT = 8;

/**
 * 1回の配信で扱う差分の上限。
 * ★これを超える差分は「1回の操作」ではなく<b>盤面の総入れ替え</b>(リセット・
 * 開始シーケンスの配り直し・すべてアンタップ)である。演出は1手を語る道具なので、
 * 総入れ替えのときは黙って何も出さないほうが正しい(設計書 2-3)。
 */
const FX_BULK_LIMIT = FX_LIMIT;

const FX_MOVE_MS = 220;
const FX_DRAW_MS = 260;
const FX_FADE_MS = 180;
const FX_LP_MS = 700;
/** ★Batch 32b: 状態系・節目系の時間。★演出の時間はこの1箇所にまとめる(32a と同じ規約) */
const FX_TAP_MS = 160;
const FX_FLIP_HALF_MS = 110;
const FX_SINK_MS = 180;
/**
 * ★Batch 35: 勝敗の帯。32b のターン帯(900ms)より長く出す。
 * ターンは1試合に何十回も出る合図だったが、決着は1回しか出ない。
 * 目を上げるまでの猶予がいる。
 */
const FX_DECLARE_MS = 2200;

/**
 * ★★Batch 38: 儀式の時間(設計書3章)。
 *
 * ★演出の時間定数は1箇所にまとめる、という 32a の規約に載っている。
 * 儀式は「1手」ではなく<b>段取り</b>なので、他の演出より長くてよい ——
 * ただし待たされるのは人であり、上限({@link FX_RITE_HOLD_MAX_MS})を必ず置く。
 */
const FX_RITE_DICE_MS = 900;
/** 山札が混ざる間。★配りの前置きであり、マリガンでは戻したあとに置く */
const FX_RITE_SHUFFLE_MS = 260;
/** 1枚が飛ぶ時間 */
const FX_RITE_FLIGHT_MS = 240;
/** 次の1枚までの間。★枚数に比例して伸びるのはここだけである */
const FX_RITE_STEP_MS = 70;
/**
 * ★★開始シーケンスの画面を待たせてよい上限(4章)。
 * 儀式が長引いても、ここを過ぎれば必ず選択画面が開く。
 * <b>時間で明ける</b>という性質が、行き止まりを構造的に作らない保証になっている。
 */
const FX_RITE_HOLD_MAX_MS = 2400;

/** 儀式の演出の種類。★{@link SFX_FOR_KIND} に載っているものと同じ並びである */
const FX_RITE_KINDS = ['dice', 'deal', 'mulligan', 'shuffle'];

/**
 * ★儀式の飛翔の出発点が<b>ゾーンではない</b>ことを表す印(★38 追補)。
 * 【ピュア・エレメント】はデッキの外から渡される。山札から出すのは嘘になる。
 */
const FX_RITE_CENTER = '@CENTER';

/** ピュア・エレメントを出すまでの間。★引き直しが着いてから渡す */
const FX_RITE_PURE_GAP_MS = 120;

/**
 * 混ざる所作で舞う枚数と、その散らばり(★38 追補)。x / y / 傾き(度)。
 *
 * ★<b>散らばった状態から束へ戻る</b>1段の動きである。「混ぜて揃えた」が1回で読める。
 * ★傾きを入れているのは、ゴーストが山札のタイルと同じ寸法だからである ——
 * 平行にずらしただけでは「少し大きい札束」にしか見えず、混ざったように読めない。
 * ★動かすのは transform だけである(32a の規約)。
 */
const FX_SHUFFLE_SCATTER = [[34, -20, -8], [-30, -9, 7], [20, 17, -4]];

/**
 * 演出の有効フラグ(設計書 2-8)。
 * ★検証の「わざと壊す」入口であり、将来の設定UIの取り付け点でもある
 * (設定UIそのものは 32a では作らない)。
 */
let fxEnabled = true;

/** 直前の配信ビュー。★参照を1世代持つだけである(コピーはしない。設計書 2-2) */
let prevView = null;

/** renderAll の前に採った差分と旧位置。measurePhase の末尾で使い切って null に戻す */
let pendingFx = null;

/** 走行中の演出。鍵ごとに1つだけ持ち、新しい演出が古いものを即座に置き換える */
const fxRunning = new Map();

/**
 * 演出を出してよいか。
 * ★{@code prefers-reduced-motion} は CSS と JS の両方で止める(設計書 2-8)。
 * CSS だけだと DOM は作られ続け、JS だけだと将来 CSS で足した演出が漏れる。
 */
function fxAllowed() {
    if (!fxEnabled) {
        return false;
    }
    if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
        return false;
    }
    return true;
}

/**
 * 差分を採る必要があるか(★Batch 37)。
 * ★見た目と音は<b>別のゲート</b>だが、材料(ビューの差分)は共通である。
 * どちらか一方でも使うなら採る。
 */
function fxDiffNeeded() {
    return fxAllowed() || sfxReady();
}

/** ★1配信につき1音だけ鳴らす(0-5 の 3-2)。選び方は {@link sfxChoose} の1箇所にある */
function sfxPlayForEffects(effects) {
    const name = sfxChoose(effects);
    if (name) sfxPlay(name);
}

function fxLayer() {
    let el = document.getElementById('manual-fx-layer');
    if (el) {
        return el;
    }
    el = document.createElement('div');
    el.id = 'manual-fx-layer';
    el.className = 'manual-fx-layer';
    document.body.appendChild(el);
    return el;
}

// ---- 差分検出(設計書 2-2)----

function fxPlace(seatId, zone) {
    return { seatId: seatId, zone: zone };
}

/**
 * ★進化スタックの素材は<b>束の持ち主と同じ場所に居る</b>ものとして畳む。
 * 畳まないと、進化するたびに素材が「消滅」に見える(27 の「状態にはあるが
 * 画面のどこからも触れない」の裏返しで、こちらは「在るのに消えたと描く」誤りである)。
 */
function fxCollect(index, list, seatId, zoneName) {
    for (const c of list || []) {
        if (!c || !c.instanceId) {
            continue;
        }
        index.set(c.instanceId, { seatId: seatId, zone: zoneName, card: c });
        if (c.materials && c.materials.length) {
            fxCollect(index, c.materials, seatId, zoneName);
        }
    }
}

/** instanceId -> 居場所。★届いたビューにあるものだけが入る(見えないカードは現れない) */
function fxIndex(view) {
    const index = new Map();
    for (const seat of [view.seatA, view.seatB]) {
        if (!seat) {
            continue;
        }
        // ★リーダーのゾーンは null である(サーバの ManualLogPlace と同じ表現)
        if (seat.leader && seat.leader.instanceId) {
            index.set(seat.leader.instanceId,
                { seatId: seat.id, zone: null, card: seat.leader });
        }
        const zones = seat.zones || {};
        for (const zoneName of Object.keys(zones)) {
            fxCollect(index, zones[zoneName], seat.id, zoneName);
        }
    }
    const shared = view.shared || {};
    for (const zoneName of Object.keys(shared)) {
        // ★共有ゾーン(PLAY / REVEAL)は席に属さない。seatId は null である
        fxCollect(index, shared[zoneName], null, zoneName);
    }
    return index;
}

/**
 * ★★<b>「窓」になっているゾーン</b>(枚数 > 届いた配列の長さ)。
 *
 * Batch 29 で山札は<b>最上段の1枚しか配らなくなった</b>。相手のマナも表向きだけが届く。
 * このようなゾーンでは、届く配列の出入りが<b>実際の出入りとは一致しない</b>。
 * 例: 1枚ドローすると、山札の新しい最上段が「初めて届いたカード」として現れる。
 * これを出現の演出にすると、ドローのたびに山札の上で意味の無いフェードインが出る。
 * 逆にシャッフルすれば、届いていた最上段が「消滅」に見える。
 *
 * したがって<b>窓のゾーンでは出現・消滅を出さない</b>。移動(両端が分かる)は出してよい。
 * これは「観測できないことは演出にしない」という 1-1 の原則そのものである。
 */
function fxWindowedZones(view) {
    const keys = new Set();
    for (const seat of [view.seatA, view.seatB]) {
        if (!seat || !seat.zones) {
            continue;
        }
        for (const zoneName of Object.keys(seat.zones)) {
            if (zoneCount(seat, zoneName) > (seat.zones[zoneName] || []).length) {
                keys.add(anchorKey(seat.id, zoneName));
            }
        }
    }
    return keys;
}

/**
 * ★ドローの検出(設計書 2-2)。
 *
 * ★<b>これは裁定ではない</b>。「山札の枚数が減り、同じ席の手札の枚数が増えた」という
 * 観測に付けた名前である。ルール上のドローかどうかは判断しない。
 *
 * 自席のドローは山札の最上段が届いているので<b>移動として識別できる</b>。
 * 相手のドローは手札が非公開なので<b>枚数しか分からない</b>——そのときは
 * 識別子を持たない演出になり、飛ぶのは裏面1枚である(それが事実の全部である)。
 */
function fxDetectDraw(out, prevSeat, nextSeat) {
    const drawn = Math.min(
        zoneCount(prevSeat, 'DECK') - zoneCount(nextSeat, 'DECK'),
        zoneCount(nextSeat, 'HAND') - zoneCount(prevSeat, 'HAND'));
    if (drawn <= 0) {
        return;
    }
    let named = 0;
    for (let i = out.moved.length - 1; i >= 0 && named < drawn; i--) {
        const m = out.moved[i];
        if (m.from.zone !== 'DECK' || m.to.zone !== 'HAND') {
            continue;
        }
        if (m.from.seatId !== nextSeat.id || m.to.seatId !== nextSeat.id) {
            continue;
        }
        out.drew.push({ id: m.id, seatId: nextSeat.id, from: m.from, to: m.to, card: m.card });
        out.moved.splice(i, 1);
        named++;
    }
    for (let i = named; i < drawn; i++) {
        out.drew.push({
            id: null,
            seatId: nextSeat.id,
            from: fxPlace(nextSeat.id, 'DECK'),
            to: fxPlace(nextSeat.id, 'HAND'),
            card: null,
        });
    }
}

/**
 * ★前後のビューを instanceId で突合して、起きたことを<b>観測の語彙</b>で返す。
 * DOM には一切触らない純オブジェクト比較であり、検証がこの1本を直接叩ける
 * (設計書 2-2 の末尾)。
 *
 * <h3>★★Batch 38: 出口が2つある(設計書1章)</h3>
 * 開始シーケンスの間は<b>儀式だけを載せて帰る</b>。総入れ替えを差分で語らないという
 * 32 設計書 2-3 の判断は1文字も緩めていない —— 緩める代わりに、
 * <b>差分の網に最初からかかっていない入口</b>({@code out.rite})を1本足した。
 * ★裁定11(節目の帯は上限の特例にしない)にも触れていない。
 * 儀式は上限({@link FX_BULK_LIMIT})に特例を作るのではなく、
 * <b>数えられる件数が最初から1〜2件しかない</b>だけである。
 */
function diffViews(prev, next) {
    const out = {
        moved: [], appeared: [], vanished: [], drew: [], lpChanged: [],
        // ★Batch 32b: その場で変わる系(状態)と、盤面全体の節目
        // ★Batch 35: turnAdvanced は退役した(裁定17)。節目は「決着」だけになった
        tapChanged: [], flipped: [], stackGrew: [], declared: null,
        // ★★Batch 38: 開始シーケンスの儀式。これだけは差分から来ない
        rite: null,
    };
    if (!prev || !next) {
        return out;
    }
    // ★★儀式は差分より<b>先</b>に採る。ここで差分を打ち切るためである
    out.rite = fxNewRite(prev, next);
    // ★★★<b>1つの配信に語り手は1人である</b>(38 追補・裁定93)。
    //   儀式が語る配信では差分を採らない。手動のシャッフルは山札の最上段が
    //   入れ替わることがあり、放っておくと「シャッフルした」と「1枚消えて1枚出た」を
    //   同時に語ってしまう。後者はシャッフルの<b>結果の一部</b>であって別の出来事ではない。
    //   ★開始シーケンス中に差分を採らないのは 32 設計書 2-3 のとおりである(こちらは据え置き)。
    if (out.rite || fxStartLocking(prev) || fxStartLocking(next)) {
        return out;
    }
    const before = fxIndex(prev);
    const after = fxIndex(next);
    const windowBefore = fxWindowedZones(prev);
    const windowAfter = fxWindowedZones(next);

    for (const [id, now] of after) {
        const was = before.get(id);
        if (!was) {
            if (!windowAfter.has(anchorKey(now.seatId, now.zone))) {
                out.appeared.push({ id: id, to: fxPlace(now.seatId, now.zone), card: now.card });
            }
            continue;
        }
        if (was.seatId !== now.seatId || was.zone !== now.zone) {
            out.moved.push({
                id: id,
                from: fxPlace(was.seatId, was.zone),
                to: fxPlace(now.seatId, now.zone),
                card: now.card,
            });
            continue;
        }
        // ★★Batch 32b: 状態系は<b>居場所が変わっていないカードだけ</b>を見る。
        //   動いたカードはゴーストが既に語っており、同じ1枚に2つの演出を重ねると
        //   「何が起きたか」がかえって読めなくなる。
        //   語彙の切り分けとしても素直である——移動系は「どこからどこへ」、
        //   状態系は「その場で何が変わったか」を語る。
        const at = fxPlace(now.seatId, now.zone);
        if (was.card.tapped !== now.card.tapped) {
            out.tapChanged.push({ id: id, at: at, tapped: now.card.tapped });
        }
        if (was.card.faceDown !== now.card.faceDown) {
            out.flipped.push({ id: id, at: at, from: was.card, to: now.card });
        }
        // ★増加だけを見る(解体は素材が場へ出るので moved が語る。27 の不変条件)
        if ((now.card.stackSize || 1) > (was.card.stackSize || 1)) {
            out.stackGrew.push({ id: id, at: at });
        }
    }
    for (const [id, was] of before) {
        if (after.has(id) || windowBefore.has(anchorKey(was.seatId, was.zone))) {
            continue;
        }
        out.vanished.push({ id: id, from: fxPlace(was.seatId, was.zone), card: was.card });
    }

    for (const key of ['seatA', 'seatB']) {
        const p = prev[key];
        const n = next[key];
        if (!p || !n) {
            continue;
        }
        fxDetectDraw(out, p, n);
        if (p.lp !== n.lp) {
            out.lpChanged.push({ seatId: n.id, delta: n.lp - p.lp, lp: n.lp });
        }
    }

    // ★★Batch 35: 決着の合図は<b>新しい宣言が届いたときだけ</b>出す(設計書 3-2)。
    //   32b のターン帯が「増加したときだけ」だったのと同じ形であり、検出機構ごと転用している。
    //   ★宣言はログの通し番号を持つ。番号が増えたときだけ、というのは
    //   「同じ宣言が再配信されても二度は出さない」という意味である
    //   (再接続の resync・別の操作による配信でも declarations は同じものが載り続ける)。
    //   ★ログは追記専用で seq が単調増加する(29)。だから比較は番号の大小で足りる。
    const wasDeclared = fxLatestDeclaration(prev);
    const nowDeclared = fxLatestDeclaration(next);
    if (nowDeclared && (!wasDeclared || nowDeclared.seq > wasDeclared.seq)) {
        out.declared = nowDeclared;
    }
    return out;
}

/**
 * ★ビューに載っている宣言のうち<b>いちばん新しいもの</b>を返す(Batch 35)。
 *
 * サーバは配ったログ行の範囲から宣言を拾って {@code declarations} に並べる(2-3)ので、
 * 並びはログと同じ昇順である。ここで並べ替えないのは、順序の正をサーバ1箇所に置くためである。
 */
function fxLatestDeclaration(view) {
    const list = view && view.declarations;
    return list && list.length > 0 ? list[list.length - 1] : null;
}

/** ★ビューに載っている儀式のうちいちばん新しいもの(★Batch 38)。並べ替えない理由は上と同じ */
function fxLatestRite(view) {
    const list = view && view.rites;
    return list && list.length > 0 ? list[list.length - 1] : null;
}

/**
 * ★★新しく届いた儀式(★Batch 38 設計書 2-3)。
 *
 * 判定の形は 35 の決着とまったく同じ ——<b>通し番号が増えたときだけ</b>である。
 * 再接続の resync でも、別の操作による配信でも、{@code rites} には同じものが載り続ける。
 * ★以後 {@code seq} は使わない。使うのは中身だけなので、ここで剥がして返す。
 *
 * @return {@code ManualLogRite} 相当のオブジェクト(新しいものが無ければ null)
 */
function fxNewRite(prev, next) {
    const was = fxLatestRite(prev);
    const now = fxLatestRite(next);
    if (!now || !now.rite || (was && now.seq <= was.seq)) {
        return null;
    }
    return now.rite;
}

/** 差分を「1つずつ独立に走る演出」の平たい列にする。鍵は instanceId で一意にする */
function fxEffects(diff) {
    const list = [];
    let anon = 0;
    for (const m of diff.moved) {
        list.push({ kind: 'move', key: 'move:' + m.id, id: m.id, from: m.from, to: m.to, card: m.card });
    }
    for (const d of diff.drew) {
        if (!d.id) {
            anon++;
        }
        list.push({
            kind: 'draw',
            key: 'draw:' + (d.id || (d.seatId + '#' + anon)),
            id: d.id, from: d.from, to: d.to, card: d.card,
        });
    }
    for (const v of diff.vanished) {
        list.push({ kind: 'vanish', key: 'vanish:' + v.id, id: v.id, from: v.from, card: v.card });
    }
    for (const a of diff.appeared) {
        list.push({ kind: 'appear', key: 'appear:' + a.id, id: a.id, to: a.to, card: a.card });
    }
    for (const l of diff.lpChanged) {
        list.push({ kind: 'lp', key: 'lp:' + l.seatId, seatId: l.seatId, delta: l.delta });
    }
    // ★Batch 32b: 状態系・節目系
    for (const t of diff.tapChanged) {
        list.push({ kind: 'tap', key: 'tap:' + t.id, id: t.id, at: t.at, tapped: t.tapped });
    }
    for (const f of diff.flipped) {
        list.push({ kind: 'flip', key: 'flip:' + f.id, id: f.id, at: f.at, from: f.from, to: f.to });
    }
    for (const s of diff.stackGrew) {
        list.push({ kind: 'sink', key: 'sink:' + s.id, id: s.id, at: s.at });
    }
    if (diff.declared) {
        // ★鍵は席にもカードにも属さない1つだけ。連続で宣言しても帯は1本しか走らない
        //   (32b のターン帯から引き継いだ性質である)
        list.push({ kind: 'declare', key: 'declare', declaration: diff.declared });
    }
    // ★★Batch 38: 開始シーケンスの儀式。<b>ここへ並べるのが要点である</b> ——
    //   演出も音も既存の列を読むだけになり、発火点が1つも増えない(裁定68)。
    //   ★出し分けはサーバが埋めた欄で決まる。クライアント側に推測が入らない
    //   (「dealt が空だからダイスだけ」のような読み方をしない)
    if (diff.rite) {
        const rite = diff.rite;
        if (rite.diceA !== null && rite.diceA !== undefined) {
            list.push({ kind: 'dice', key: 'dice', rite: rite });
        }
        if (rite.kind === 'DEAL') {
            list.push({ kind: 'deal', key: 'deal', rite: rite });
        }
        if (rite.kind === 'MULLIGAN') {
            list.push({ kind: 'mulligan', key: 'mulligan', rite: rite });
        }
        if (rite.kind === 'SHUFFLE') {
            list.push({ kind: 'shuffle', key: 'shuffle', rite: rite });
        }
    }
    return list;
}

// ---- 位置の採取(read)----

function fxRectOf(el) {
    if (!el) {
        return null;
    }
    const r = el.getBoundingClientRect();
    if (!r.width || !r.height) {
        return null;
    }
    return { left: r.left, top: r.top, width: r.width, height: r.height };
}

function fxCardElement(instanceId) {
    if (!instanceId) {
        return null;
    }
    return document.querySelector(`[data-instance-id="${instanceId}"]`);
}

/**
 * ★カードの実要素が引ければそれを、引けなければゾーンのアンカーを使う。
 * 引けないのはパイルの中(最上段以外)や非公開ゾーンのときであり、
 * ゾーン全体を根にすることで<b>どの1枚かは漏れない</b>(21c 7-3 と同じ考え方)。
 */
function fxSourceRect(instanceId, place) {
    const rect = fxRectOf(fxCardElement(instanceId));
    return rect || fxRectOf(anchorElement(place));
}

/** ★renderAll の<b>前</b>に呼ぶ。書き込み前のきれいなDOMに対する read である(29 の相分離) */
function fxCaptureOrigins(effects) {
    const origins = new Map();
    for (const fx of effects) {
        if (fx.kind !== 'move' && fx.kind !== 'draw' && fx.kind !== 'vanish') {
            continue;
        }
        // ★ドローの根は必ず山札のアンカーにする(設計書 2-2 の「山札アンカー→手札位置」)
        const rect = fxSourceRect(fx.kind === 'draw' ? null : fx.id, fx.from);
        if (rect) {
            origins.set(fx.key, rect);
        }
    }
    return origins;
}

// ---- ゴーストの組み立て(設計書 2-3)----

/**
 * ★見た目は既存のフェイス関数で作る。ゴースト専用の見た目を作らない
 * (見た目の正は1箇所、という 25 以来の方針の延長)。
 * ★★裏面は {@link cardBackFace} を使う。裏面は imageId を持たない(26)ので、
 * 「非公開情報を運ばない」性質が<b>構造的に</b>保たれる。
 */
function fxGhostBody(card, rect) {
    if (!card || card.faceDown || !card.name) {
        return cardBackFace();
    }
    return cardFace(card, rect.width >= 64 ? 'full' : 'mini');
}

function fxGhost(rect, card) {
    const el = document.createElement('div');
    el.className = 'manual-fx-ghost';
    el.style.left = rect.left + 'px';
    el.style.top = rect.top + 'px';
    el.style.width = rect.width + 'px';
    el.style.height = rect.height + 'px';
    el.appendChild(fxGhostBody(card, rect));
    return el;
}

/**
 * 走行中の演出として登録する。★終了は<b>終了イベントとタイムアウトの二重保険</b>で
 * 必ず来る(片方だけだと、値が変わらず transition が発火しなかったときに
 * ゴーストが残る)。
 *
 * <h3>★Batch 32b: 位置引数をオプションにまとめた</h3>
 * 32a では {@code keep} / {@code endEvent} の2つを位置引数で受けていたが、
 * 32b で「終わったときに元へ戻す処理」({@code onStop})が要るようになった。
 * 位置引数を3つ並べると呼び出し側が読めなくなるため、オプションにまとめてある。
 *
 * @param opts.keep    真なら要素をDOMから外さない(<b>実要素</b>に当てる演出のため)
 * @param opts.event   終了イベント名。既定は {@code transitionend}。
 *                     ★{@code null} を渡すとイベントを購読せず<b>タイマーだけ</b>で閉じる。
 *                     flip のような多段演出は、途中の段の transitionend で
 *                     閉じてしまってはならない(32b 2-3)
 * @param opts.onStop  終了時に呼ぶ後始末。★<b>実要素へ当てたクラスを必ずここで剥がす</b>。
 *                     途中で次の配信が来て要素が捨てられても、剥がす相手が
 *                     捨てられた要素になるだけで壊れない
 */
function fxRegister(key, el, ms, play, opts) {
    fxStop(key);
    const options = opts || {};
    const entry = {
        el: el,
        keep: !!options.keep,
        event: options.event === undefined ? 'transitionend' : options.event,
        onStop: options.onStop || null,
        timer: null,
        done: null,
    };
    // ★イベントは子要素からも上がってくる(バッジのアニメーション等)。
    //   自分自身のぶんでなければ終了と見なさない
    entry.done = (e) => {
        if (e && e.target && e.target !== el) {
            return;
        }
        fxStop(key);
    };
    entry.timer = setTimeout(() => fxStop(key), ms + 140);
    if (entry.event) {
        el.addEventListener(entry.event, entry.done);
    }
    fxRunning.set(key, entry);
    return play;
}

function fxStop(key) {
    const entry = fxRunning.get(key);
    if (!entry) {
        return;
    }
    fxRunning.delete(key);
    clearTimeout(entry.timer);
    if (entry.event) {
        entry.el.removeEventListener(entry.event, entry.done);
    }
    if (entry.onStop) {
        entry.onStop();
    }
    if (!entry.keep && entry.el.parentNode) {
        entry.el.parentNode.removeChild(entry.el);
    }
}

/** 演出の着地点。★その場で変わる系(tap / flip / sink)は {@code at} に居場所を持つ */
function fxTargetRect(fx) {
    const rect = fxRectOf(fxCardElement(fx.id));
    return rect || fxRectOf(anchorElement(fx.to || fx.at));
}

/** 飛ぶ系(move / draw)。★動かすのは transform と opacity だけである */
function fxBuildFlight(fx, origin, layer) {
    const target = fxTargetRect(fx);
    if (!target) {
        return null;
    }
    const dx = target.left - origin.left;
    const dy = target.top - origin.top;
    if (Math.abs(dx) < 2 && Math.abs(dy) < 2) {
        return null;   // 見た目が動かないなら演出しない
    }
    // ★相手のドローは識別子が無い。card を渡さないことで裏面ゴーストに落ちる
    const ghost = fxGhost(origin, fx.id ? fx.card : null);
    ghost.dataset.fxKind = fx.kind;
    layer.appendChild(ghost);
    const ms = fx.kind === 'draw' ? FX_DRAW_MS : FX_MOVE_MS;
    return fxRegister(fx.key, ghost, ms, () => {
        // transform は全区間、opacity は最後だけ(着地の瞬間に本物へ受け渡す)
        ghost.style.transitionDuration = ms + 'ms, 110ms';
        ghost.style.transitionDelay = '0ms, ' + Math.max(0, ms - 110) + 'ms';
        ghost.style.transform = `translate(${dx}px, ${dy}px)`;
        ghost.style.opacity = '0';
    });
}

function fxBuildVanish(fx, origin, layer) {
    const ghost = fxGhost(origin, fx.card);
    ghost.dataset.fxKind = 'vanish';
    layer.appendChild(ghost);
    return fxRegister(fx.key, ghost, FX_FADE_MS, () => {
        ghost.style.transitionDuration = FX_FADE_MS + 'ms, ' + FX_FADE_MS + 'ms';
        ghost.style.transform = 'scale(0.86)';
        ghost.style.opacity = '0';
    });
}

/**
 * ★出現は<b>実要素</b>のフェードインで行う(設計書 2-4)。ゴーストを作らない。
 * 途中で次の配信が来て再構築されても「最終状態で即時表示」に落ちるだけで、壊れ方が無い。
 * ★CSS 側は transition ではなく animation である(battle.css の該当箇所の理由を参照)。
 */
function fxBuildAppear(fx) {
    const el = fxCardElement(fx.id);
    if (!el) {
        return null;
    }
    el.classList.add('manual-fx-enter');
    return fxRegister(fx.key, el, FX_FADE_MS, () => { /* animation は付与だけで走る */ }, {
        keep: true,
        event: 'animationend',
        onStop: () => el.classList.remove('manual-fx-enter'),
    });
}

function fxBuildLp(fx, layer) {
    const rect = fxRectOf(anchorElement(fxPlace(fx.seatId, null)));
    if (!rect) {
        return null;
    }
    const pop = document.createElement('div');
    pop.className = 'manual-fx-lp '
        + (fx.delta > 0 ? 'manual-fx-lp-up' : 'manual-fx-lp-down');
    // ★符号は「増えたか減ったか」だけを語る。回復か被弾かは判断しない
    pop.textContent = (fx.delta > 0 ? '+' : '−') + Math.abs(fx.delta);
    pop.dataset.fxSeat = fx.seatId;
    pop.style.left = (rect.left + rect.width / 2) + 'px';
    pop.style.top = (rect.top + rect.height * 0.35) + 'px';
    layer.appendChild(pop);
    return fxRegister(fx.key, pop, FX_LP_MS, () => {
        pop.style.transitionDuration = FX_LP_MS + 'ms, ' + FX_LP_MS + 'ms';
        pop.style.transform = 'translate(-50%, -28px)';
        pop.style.opacity = '0';
    });
}

// ---- ★Batch 32b: その場で変わる系(状態)----

/**
 * ★★タップ表現の<b>非対称は維持する</b>(26 / 30 の判断)。
 *
 * <pre>
 *   自席のマナ(.mana-tile)         → 回転 rotate(90deg)
 *   相手マナの簡略タイル             → 減光 .manual-opp-tapped
 *   場のタイル・リーダー             → 減光 + バッジ .manual-tile-tapped
 * </pre>
 *
 * どちらの表現かは<b>要素が既に持っているクラス</b>で決める。
 * 「マナなら回転」という判断をここで書き直すと、表現の正が2箇所になる。
 */
function fxTapStateClass(el) {
    if (el.classList.contains('mana-tile')) {
        return 'tapped';
    }
    if (el.classList.contains('manual-opp-mana-card')) {
        return 'manual-opp-tapped';
    }
    return 'manual-tile-tapped';
}

/**
 * ★★タップ / アンタップのトランジション(設計書 2-4「次フレーム切替」)。
 *
 * <h3>なぜ 32a まで一瞬で切り替わっていたのか</h3>
 * {@code renderAll} は毎回DOMを作り直す。<b>作られた時点で最終状態のクラスが付いている</b>ため、
 * CSS の transition は発火しない(初期値には遷移が無い)。
 *
 * <h3>直し方</h3>
 * 実要素の状態クラスを<b>いったん旧状態へ戻し</b>、レイアウトを確定させてから最終状態へ戻す。
 * すると新しい要素の上で transition が走る。要素の使い回し(=盤面DOMの差分更新)は要らない。
 * レイアウトの確定は {@link fxSpawn} が層ごと1回だけ行う。
 *
 * ★<b>遷移そのものは {@code .manual-fx-tap} が持つ</b>。盤面のカード全部に
 * {@code transition-property} を生やすと、32a の出現フェード(animation)や
 * 将来の演出まで巻き込む(32a 2-9)。走っている要素にだけ付けて、終わったら剥がす。
 *
 * ★{@code applyManaOverlap} は既に<b>最終状態で</b>幅を確保し終えている
 * ({@link measurePhase} の順序)。ここで一時的にクラスを外してもマージンは
 * インラインで最終値のままであり、レイアウトは動かない(設計書 2-4)。
 */
function fxBuildTap(fx) {
    const el = fxCardElement(fx.id);
    if (!el) {
        return null;   // パイルの中・非公開ゾーンなど、画面に出ていないカード
    }
    const stateClass = fxTapStateClass(el);
    const settle = () => {
        el.classList.remove('manual-fx-tap');
        el.classList.toggle(stateClass, fx.tapped);
    };
    // ★★<b>遷移のクラスは最終状態と一緒に当てる</b>。旧状態と一緒に当ててはならない。
    //   旧状態と一緒に当てると、確定の瞬間に「最終状態 → 旧状態」という<b>逆向きの遷移</b>が
    //   走り出す。その直後に最終状態へ戻すと、CSS Transitions の規定により
    //   「走行中の遷移の現在値が新しい最終値と等しい」場合は<b>遷移を取り消して
    //   新しい遷移を始めない</b>。結果、クラスは全部正しく付いているのに
    //   <b>何も動かない</b>——音を立てない壊れ方をする(検証項目53で踏んだ)。
    el.classList.toggle(stateClass, !fx.tapped);   // ★旧状態へ戻す(遷移はまだ付けない)
    return fxRegister(fx.key, el, FX_TAP_MS, () => {
        el.classList.add('manual-fx-tap');
        el.classList.toggle(stateClass, fx.tapped);
    }, { keep: true, onStop: settle });
}

/**
 * ★表裏のめくり(設計書 2-4)。2段階で行う。
 *
 * <pre>
 *   前半 110ms: <b>旧い面</b>のゴーストが rotateY(0deg → 90deg) で立つ
 *   後半 110ms: 中身を<b>新しい面</b>へ差し替え、rotateY(-90deg → 0deg) で倒れる
 * </pre>
 *
 * ★実タイルは前半・後半のあいだ透明にしておく({@code .manual-fx-hidden})。
 * ゴーストが真横(90deg)を向いた瞬間に下から新しい面が覗くと、めくりに見えない。
 * ★透明にするのは {@code opacity} である({@code visibility} だと当たり判定が消え、
 * 演出中だけカードが押せなくなる)。
 * ★クラスを剥がすのは {@code onStop} の仕事である。途中で次の配信が来て要素が
 * 作り直されても、剥がす相手が<b>捨てられた要素</b>になるだけで盤面は壊れない。
 *
 * ★情報漏れは構造的に起きない。前半は<b>旧ビューのカード</b>、後半は
 * <b>新ビューのカード</b>をそのまま {@link fxGhostBody} に渡すだけであり、
 * 裏向きなら {@link cardBackFace}(imageId を持たない)に落ちる。
 */
function fxBuildFlip(fx, layer) {
    const rect = fxTargetRect(fx);
    if (!rect) {
        return null;
    }
    const el = fxCardElement(fx.id);
    const ghost = fxGhost(rect, fx.from);
    ghost.dataset.fxKind = 'flip';
    ghost.dataset.fxPhase = '1';
    ghost.style.transform = 'perspective(800px) rotateY(0deg)';
    layer.appendChild(ghost);
    if (el) {
        el.classList.add('manual-fx-hidden');
    }
    let half = null;
    // ★終了イベントを購読しない。前半の transitionend で閉じてしまうためである
    return fxRegister(fx.key, ghost, FX_FLIP_HALF_MS * 2, () => {
        ghost.style.transitionDuration = FX_FLIP_HALF_MS + 'ms, 0ms';
        ghost.style.transform = 'perspective(800px) rotateY(90deg)';
        half = setTimeout(() => {
            ghost.dataset.fxPhase = '2';
            ghost.innerHTML = '';
            ghost.appendChild(fxGhostBody(fx.to, rect));
            ghost.style.transitionDuration = '0ms, 0ms';
            ghost.style.transform = 'perspective(800px) rotateY(-90deg)';
            void ghost.offsetWidth;
            ghost.style.transitionDuration = FX_FLIP_HALF_MS + 'ms, 0ms';
            ghost.style.transform = 'perspective(800px) rotateY(0deg)';
        }, FX_FLIP_HALF_MS);
    }, {
        event: null,
        onStop: () => {
            clearTimeout(half);
            if (el) {
                el.classList.remove('manual-fx-hidden');
            }
        },
    });
}

// ---- ★Batch 32b: 節目系 ----

/**
 * ★進化スタックが伸びたときの沈み込みパルス(設計書 2-7)。
 *
 * 素材が束へ入る「動き」は、素材が別のゾーンから来たなら {@code moved} のゴーストが
 * 既に語っている。ここで足すのは<b>到着したタイル側の反応</b>だけである。
 * 27 の「素材の消失」問題系に触る面を増やさないため、専用の大掛かりな演出は作らない。
 *
 * ★同じ席の場から場への進化は居場所が変わらないので {@code moved} が出ない。
 * そのときはこのパルスが唯一の合図になる。
 */
function fxBuildSink(fx) {
    const el = fxCardElement(fx.id);
    if (!el) {
        return null;
    }
    el.classList.add('manual-fx-sink');
    return fxRegister(fx.key, el, FX_SINK_MS, () => { /* animation は付与だけで走る */ }, {
        keep: true,
        event: 'animationend',
        onStop: () => el.classList.remove('manual-fx-sink'),
    });
}

/**
 * ★★決着の合図(★Batch 35 設計書3章)。盤面中央に帯を出して消す。
 *
 * <h3>32b のターン帯をそのまま引き継いでいる</h3>
 * 位置決め・出し方・消し方・fx層の座席・鍵の作り方は 32b の {@code fxBuildTurn} と同じである
 * (裁定17「帯の描画機構は勝敗の帯へ転用する」)。変えたのは<b>何を合図するか</b>と、
 * <b>宣言の種類で色が変わること</b>、そして出ている時間だけである。
 *
 * ★20b でターン表示を削った理由は「縦100pxの<b>常設行</b>」だった。
 * これは一過性のオーバーレイでレイアウトを1pxも消費しないため、当時の判断と矛盾しない。
 * ★帯の文字もコントラストの機械判定の対象に入れてある(30/31 の1本の条件)。
 *
 * ★<b>盤面は止めない</b>(裁定16 の軽量版)。帯は数秒で消え、そのあとも操作できる。
 * 決着したあとに盤面を並べ直して見せ合う使い方があり、通話ではそれをしながら喋る。
 */
function fxBuildDeclare(fx, layer) {
    const rect = fxRectOf(document.getElementById('center-line'))
        || fxRectOf(document.getElementById('manual-root'));
    if (!rect) {
        return null;
    }
    const declared = fx.declaration;
    const band = document.createElement('div');
    // ★色の出し分けは<b>列挙値</b>で行う。表示名で分岐すると、文言を直した瞬間に色が消える
    band.className = 'manual-fx-declare manual-fx-declare-'
        + String(declared.declaration || '').toLowerCase();
    // ★★文言はサーバの label をそのまま使う(設計判断28)。
    //   「勝利」「敗北」の対応表をクライアントにもう1枚作らない。
    //   席の書き方だけがここの仕事であり、ログの「席A の 勝利を宣言した」と揃えてある。
    band.textContent = declared.seat
        ? '席' + declared.seat + ' の' + declared.label
        : declared.label;
    band.dataset.fxDeclare = String(declared.declaration || '');
    band.style.left = (rect.left + rect.width / 2) + 'px';
    band.style.top = (rect.top + rect.height / 2) + 'px';
    layer.appendChild(band);
    return fxRegister(fx.key, band, FX_DECLARE_MS, () => {
        // ★出たまま少し留めてから消える。transform は動かさず opacity だけ遅らせる
        band.style.transitionDuration = '0ms, 380ms';
        band.style.transitionDelay = '0ms, ' + (FX_DECLARE_MS - 380) + 'ms';
        band.style.opacity = '0';
    });
}

// ---- ★★Batch 38: 開始シーケンスの儀式 ----
//
// ★★<b>ここは差分ではない</b>。運ばれてくるのは席と枚数だけであり、
//   どのカードかは構造上そもそも持てない(ManualRiteDeal の javadoc)。
//   ★これは制限ではなく<b>この演出の性質</b>である。開始の配り直しでは
//   instanceId が全部作り直されるので、同一性を追っても意味が無い。
//   一方、枚数(先攻4 / 後攻5)は総合ルール 2-5 そのものであり、意味しかない。
//   ★おかげで<b>相手席の配りも演出できる</b>。手札は「窓」のゾーンで中身が届かない
//   (裁定7)が、枚数は元から公開情報である。

/**
 * ★デッキの外から来るカードの出発点(★38 追補)。
 * 中央(センターライン)に、<b>着地点と同じ寸法で</b>置く。
 * ★中央の要素の寸法をそのまま使うと横長の帯になってしまう ——
 * 出発点は「場所」であって「その要素」ではない。
 */
function fxRiteCenterRect(to) {
    const center = fxRectOf(document.getElementById('center-line'))
        || fxRectOf(document.getElementById('manual-root'));
    if (!center || !to) {
        return null;
    }
    return {
        left: center.left + center.width / 2 - to.width / 2,
        top: center.top + center.height / 2 - to.height / 2,
        width: to.width,
        height: to.height,
    };
}

/** 儀式の1本の飛翔にかかる時間。★枚数に比例して伸びるのはここだけである */
function fxRiteSpan(count) {
    return count > 0 ? FX_RITE_FLIGHT_MS + (count - 1) * FX_RITE_STEP_MS : 0;
}

/**
 * 儀式を「席 × 向き × 枚数 × いつ始めるか」の並びにほどく。
 *
 * ★★ダイスを伴う配り(ソロのランダム)は、ダイスの帯が終わってから飛ばす。
 * 同じ配信で起きた2つの出来事だが、<b>順序があるものは順序どおりに見せる</b>。
 */
function fxRiteLegs(fx) {
    const rite = fx.rite || {};
    // ★ダイスの帯と重ならないようにずらす。ずらす量は帯の時間そのものである
    const base = fx.kind !== 'dice' && rite.diceA !== null && rite.diceA !== undefined
        ? FX_RITE_DICE_MS : 0;
    const legs = [];
    let end = base;
    for (const d of rite.dealt || []) {
        if (fx.kind === 'shuffle') {
            // ★★手動のシャッフル(38 追補)。<b>1枚も動かない</b>ので飛翔は無く、
            //   混ざる所作だけが出る。それがこの操作で起きたことの全部である
            legs.push({ seat: d.seat, from: null, to: null, count: 0, at: base, shuffleAt: base });
            end = Math.max(end, base + FX_RITE_SHUFFLE_MS);
        } else if (fx.kind === 'mulligan') {
            const backSpan = fxRiteSpan(d.back);
            if (d.back > 0) {
                legs.push({ seat: d.seat, from: 'HAND', to: 'DECK', count: d.back, at: base });
            }
            if (d.drew > 0) {
                // ★戻し終わってから混ぜ、混ぜ終わってから引く。段取りの順である
                legs.push({
                    seat: d.seat, from: 'DECK', to: 'HAND', count: d.drew,
                    at: base + backSpan + FX_RITE_SHUFFLE_MS, shuffleAt: base + backSpan,
                });
                end = Math.max(end, base + backSpan + FX_RITE_SHUFFLE_MS + fxRiteSpan(d.drew));
            } else {
                legs.push({
                    seat: d.seat, from: null, to: null, count: 0,
                    at: base + backSpan, shuffleAt: base + backSpan,
                });
                end = Math.max(end, base + backSpan + FX_RITE_SHUFFLE_MS);
            }
        } else if (d.drew > 0) {
            // ★配りは「混ぜてから配る」。席ごとの飛翔は<b>同時に</b>走らせる
            //   (順番に配ると、席が2つあるだけで待ち時間が倍になる)
            legs.push({
                seat: d.seat, from: 'DECK', to: 'HAND', count: d.drew,
                at: base + FX_RITE_SHUFFLE_MS, shuffleAt: base,
            });
            end = Math.max(end, base + FX_RITE_SHUFFLE_MS + fxRiteSpan(d.drew));
        }
    }
    // ★★【ピュア・エレメント】(38 追補・マスター裁定 Q1 = b)。
    //   マリガンが両席とも確定した配信でだけ入る。<b>山札からではなく中央から</b>飛ぶ ——
    //   あれはデッキの外から渡されるカードであり、山札から出すのは嘘になる。
    //   ★音は増やさない。1配信1音(裁定70)であり、この配信の主役はマリガンである
    if (rite.pureSeat) {
        legs.push({
            seat: rite.pureSeat, from: FX_RITE_CENTER, to: 'HAND', count: 1,
            at: end + FX_RITE_PURE_GAP_MS,
        });
    }
    return legs;
}

/** 儀式1件が終わるまでの時間。★開始画面をどれだけ待たせるかもこの1本で決まる(4章) */
function fxRiteDuration(fx) {
    if (fx.kind === 'dice') {
        return FX_RITE_DICE_MS;
    }
    let end = 0;
    for (const leg of fxRiteLegs(fx)) {
        end = Math.max(end, leg.at + fxRiteSpan(leg.count));
        // ★混ざる所作にも長さがある。★これを忘れると、飛翔が無い儀式
        //   (手動のシャッフル・0枚のマリガン)の長さが 0 になり、演出が出た瞬間に消える
        if (leg.shuffleAt !== undefined) {
            end = Math.max(end, leg.shuffleAt + FX_RITE_SHUFFLE_MS);
        }
    }
    return end;
}

/** 一連の演出のうち、儀式が要求する待ち時間。★{@link applyView} が保留の長さに使う */
function fxRiteHoldMs(effects) {
    let ms = 0;
    for (const fx of effects || []) {
        if (FX_RITE_KINDS.indexOf(fx.kind) >= 0) {
            ms = Math.max(ms, fxRiteDuration(fx));
        }
    }
    return ms;
}

/**
 * ★★先後判定のダイス(設計書 3-1)。
 *
 * 帯の位置・消し方は 35 の決着の帯と同じ座席・同じ書き方である。
 * 違うのは<b>中身が3つの部品でできている</b>ことだけで、出目2つと結果の文になっている。
 *
 * ★★<b>見えている内容は最初からDOMにある</b>。段階的に現れるのは CSS の
 * animation-delay であって、JS がテキストを書き換えるのではない。
 * 「待ってから測る」検証にしないためであり、31 以来の
 * 「測れる決めごとのほうを測る」に沿っている。
 * ★★文言はサーバの {@code label} をそのまま使う。対戦部屋のダイスが与えるのは
 * <b>先攻ではなく選択権</b>であり、その書き分けをクライアントに写すと条件が2箇所に分かれる。
 */
function fxBuildDice(fx, layer) {
    const rect = fxRectOf(document.getElementById('center-line'))
        || fxRectOf(document.getElementById('manual-root'));
    if (!rect) {
        return null;
    }
    const rite = fx.rite;
    const band = document.createElement('div');
    band.className = 'manual-fx-dice';
    band.dataset.fxDice = String(rite.winner || '');
    for (const seatId of ['A', 'B']) {
        const chip = document.createElement('span');
        chip.className = 'manual-fx-dice-chip'
            + (rite.winner === seatId ? ' manual-fx-dice-win' : '');
        chip.textContent = '席' + seatId + ' ' + (seatId === 'A' ? rite.diceA : rite.diceB);
        band.appendChild(chip);
    }
    const note = document.createElement('span');
    note.className = 'manual-fx-dice-note';
    note.textContent = rite.label || '';
    band.appendChild(note);
    band.style.left = (rect.left + rect.width / 2) + 'px';
    band.style.top = (rect.top + rect.height / 2) + 'px';
    layer.appendChild(band);
    return fxRegister(fx.key, band, FX_RITE_DICE_MS, () => {
        band.style.transitionDuration = '0ms, 260ms';
        band.style.transitionDelay = '0ms, ' + (FX_RITE_DICE_MS - 260) + 'ms';
        band.style.opacity = '0';
    }, { event: null });
}

/**
 * ★★シャッフルと配り / マリガン(設計書 3-2・3-3)。
 *
 * <h3>★儀式は「1つの演出」として登録する</h3>
 * ゴーストが9枚あっても {@link fxRegister} に積むのは<b>入れ物1つ</b>である。
 * こうしておくと {@link FX_LIMIT}(同時8本)の勘定でも儀式は1本と数えられ、
 * 裁定11 が嫌った「上限の特例」を作らずに済む。
 * 後片付けも入れ物ごと1回で終わる。
 *
 * <h3>★ゴーストは最初に全部作り、飛ばす時刻だけをずらす</h3>
 * 途中で作ると、その1枚だけ<b>開始状態が確定していない</b>まま終了状態を当てることになり、
 * 遷移が発火しない(32a の {@code offsetHeight} の idiom が効くのは、
 * 確定の時点でDOMに在るものだけである)。
 * 飛ぶ前のゴーストは山札(または手札)の上に重なっているので、見た目には現れない。
 */
function fxBuildRiteFlights(fx, layer) {
    const legs = fxRiteLegs(fx);
    if (legs.length === 0) {
        return null;
    }
    const box = document.createElement('div');
    box.className = 'manual-fx-rite';
    layer.appendChild(box);

    const flights = [];
    const shakes = [];
    for (const leg of legs) {
        if (leg.shuffleAt !== undefined) {
            const pile = anchorElement(fxPlace(leg.seat, 'DECK'));
            const pileRect = fxRectOf(pile);
            if (pile) {
                shakes.push({ el: pile, at: leg.shuffleAt });
            }
            // ★★混ざる所作は「揺れ」だけでは弱い。散らばった数枚が束へ戻る絵を重ねる。
            //   ★手動のシャッフルは<b>これしか出ない</b>ので、揺れだけでは
            //   押したことに気づけない(38 追補)。マリガンの中の混ざる所作も同じ絵にしてある
            if (pileRect) {
                for (const offset of FX_SHUFFLE_SCATTER) {
                    const ghost = fxGhost(pileRect, null);
                    ghost.dataset.fxKind = fx.kind;
                    // ★★混ざる所作のゴーストは<b>員数ではない</b>。飛翔と同じ印を付けると
                    //   「何枚動いたか」を数える検証が舞う枚数まで数えてしまう
                    ghost.dataset.fxPhase = 'shuffle';
                    // ★散らばった位置を<b>作った時点で</b>当てる。transition-duration は
                    //   既定が 0ms なので、ここは動かずに置かれるだけである
                    ghost.style.transform = 'translate(' + offset[0] + 'px, ' + offset[1]
                        + 'px) rotate(' + offset[2] + 'deg)';
                    ghost.style.opacity = '0.7';
                    box.appendChild(ghost);
                    flights.push({ el: ghost, at: leg.shuffleAt, dx: 0, dy: 0,
                        ms: FX_RITE_SHUFFLE_MS });
                }
            }
        }
        if (leg.count <= 0) {
            continue;
        }
        // ★出発点はゾーンのアンカー。ただしピュア・エレメントだけは中央から来る(38 追補)
        const to = fxRectOf(anchorElement(fxPlace(leg.seat, leg.to)));
        const from = leg.from === FX_RITE_CENTER
            ? fxRiteCenterRect(to)
            : fxRectOf(anchorElement(fxPlace(leg.seat, leg.from)));
        if (!from || !to) {
            continue;   // ★引けない席は演出しない(推測で描かない。32a と同じ)
        }
        for (let i = 0; i < leg.count; i++) {
            // ★裏面ゴーストである。card を渡さないので中身を持ちようがない
            const ghost = fxGhost(from, null);
            ghost.dataset.fxKind = fx.kind;
            ghost.dataset.fxPhase = 'flight';
            box.appendChild(ghost);
            flights.push({
                el: ghost, at: leg.at + i * FX_RITE_STEP_MS,
                dx: to.left - from.left, dy: to.top - from.top,
            });
        }
    }
    if (flights.length === 0 && shakes.length === 0) {
        box.remove();
        return null;
    }

    const timers = [];
    const shaken = [];
    const launch = (f) => {
        const ms = f.ms || FX_RITE_FLIGHT_MS;
        f.el.style.transitionDuration = ms + 'ms, 110ms';
        f.el.style.transitionDelay = '0ms, ' + Math.max(0, ms - 110) + 'ms';
        f.el.style.transform = 'translate(' + f.dx + 'px, ' + f.dy + 'px)';
        f.el.style.opacity = '0';
    };
    const shake = (s) => {
        s.el.classList.add('manual-fx-shuffle');
        shaken.push(s.el);
    };
    return fxRegister(fx.key, box, fxRiteDuration(fx), () => {
        for (const s of shakes) {
            if (s.at <= 0) {
                shake(s);
            } else {
                timers.push(setTimeout(() => shake(s), s.at));
            }
        }
        for (const f of flights) {
            // ★0ms のものは同期で当てる。setTimeout(…, 0) に落とすと、
            //   配信直後に観測する検証から1ティックぶん見えなくなる
            if (f.at <= 0) {
                launch(f);
            } else {
                timers.push(setTimeout(() => launch(f), f.at));
            }
        }
    }, {
        event: null,
        // ★★実要素へ当てたクラスは必ず剥がす(32b の規約)。予約も全部取り消す
        onStop: () => {
            for (const t of timers) {
                clearTimeout(t);
            }
            for (const el of shaken) {
                el.classList.remove('manual-fx-shuffle');
            }
        },
    });
}

function fxBuild(fx, origin, layer) {
    if (fx.kind === 'dice') {
        return fxBuildDice(fx, layer);
    }
    if (fx.kind === 'deal' || fx.kind === 'mulligan' || fx.kind === 'shuffle') {
        return fxBuildRiteFlights(fx, layer);
    }
    if (fx.kind === 'lp') {
        return fxBuildLp(fx, layer);
    }
    if (fx.kind === 'appear') {
        return fxBuildAppear(fx);
    }
    if (fx.kind === 'tap') {
        return fxBuildTap(fx);
    }
    if (fx.kind === 'flip') {
        return fxBuildFlip(fx, layer);
    }
    if (fx.kind === 'sink') {
        return fxBuildSink(fx);
    }
    if (fx.kind === 'declare') {
        return fxBuildDeclare(fx, layer);
    }
    if (!origin) {
        return null;   // 旧位置が採れなかったものは演出しない(推測で描かない)
    }
    if (fx.kind === 'vanish') {
        return fxBuildVanish(fx, origin, layer);
    }
    return fxBuildFlight(fx, origin, layer);
}

/**
 * ★{@link measurePhase} の末尾で呼ぶ(29 の read 相に同居させる)。
 *
 * ★強制同期レイアウトは<b>1回だけ</b>である。全部の開始状態をDOMに載せてから
 * {@code void layer.offsetWidth} で確定させ、そのあと終了状態を当てる。
 * {@link flashDenied} と同じ idiom であり、rAF を待たないので
 * <b>検証が配信直後にそのまま観測できる</b>(待ち時間に依存する検証にしない)。
 */
function fxSpawn() {
    const pending = pendingFx;
    pendingFx = null;
    if (!pending) {
        return;
    }
    // ★★Batch 37: 効果音の1つめの取り付け点である(0-5)。<b>ここ1箇所で
    //   ドロー・配置・タップ・めくり・LP増減・決着の6種すべてを覆う</b>——
    //   決着も他の演出と同じ effects の列に kind='declare' として並んでいるためである。
    //   ★★Batch 38 のシャッフル・ダイス・配りも<b>1行も足さずにここから鳴っている</b>。
    //     儀式を fxEffects の列に並べたことの配当である(裁定68)。
    //   ★★見た目のゲート(fxAllowed)より<b>前</b>に置く。音は動きではないので
    //     prefers-reduced-motion では止めない(0-5 の章)。
    sfxPlayForEffects(pending.effects);
    if (!fxAllowed()) {
        return;
    }
    const layer = fxLayer();
    const plays = [];
    for (const fx of pending.effects) {
        if (fxRunning.size >= FX_LIMIT) {
            break;   // 追いつけない演出は捨ててよい(盤面の正は下のDOMである)
        }
        const play = fxBuild(fx, pending.origins.get(fx.key), layer);
        if (play) {
            plays.push(play);
        }
    }
    if (plays.length === 0) {
        return;
    }
    // ★★開始状態をここで<b>確定</b>させてから、まとめて終了状態を当てる。
    //   これをしないとブラウザから見て開始状態が存在せず、遷移が発火しない
    //   (32a からの idiom。rAF を待たないので検証が配信直後に観測できる)。
    //
    // ★Batch 32b で読む相手を fx層からルート要素へ変えた。32a が動かすのは
    //   ゴースト(fx層の中)だけだったが、32b の状態系は<b>盤面の実要素</b>
    //   (#manual-root の中)に当たる。実測では fx層を読んでも文書全体が確定するが、
    //   それは強制同期レイアウトの実装上の性質に寄りかかった書き方であり、
    //   <b>なぜそれで足りるのかがコードから読めない</b>。確定させたいものを含む要素を
    //   読む——ルート要素の高さはページ全体のレイアウトを要求する。
    // ★強制同期レイアウトが1回だけであることは変わらない(29 の相分離)。
    void document.documentElement.offsetHeight;
    for (const play of plays) {
        play();
    }
}

/** 開始シーケンスの最中か(設計書 2-3)。この間は盤面が「総入れ替え」される */
function fxStartLocking(view) {
    return !!(view && view.start && view.start.locking);
}

// ---- ★★Batch 38: 儀式のあいだ開始シーケンスの画面を待たせる(設計書4章)----
//
// ★★<b>なぜ待たせるのか。</b>fx層は z-index 1030 であり、開始モーダル(1950/1960)と
//   マリガンのオーバーレイ(1950)より<b>下</b>にある。配りの儀式が走る配信では
//   同じ瞬間にマリガンの画面が開くので、待たせなければ儀式は1pxも見えない。
//   ★fx層を持ち上げる案は採らなかった。z-index の並び(33 で1本に決めたもの)を
//   演出の都合で崩すと、次に何かを重ねる人が読む順序が消える。
//   <b>層の順序ではなく時間で解く</b>ほうが、決めごとが増えない。
//
// ★★<b>行き止まりを作らない。</b>保留は時刻で明ける。儀式の組み立てが失敗しても、
//   ゴーストが1枚も出なくても、{@link FX_RITE_HOLD_MAX_MS} を待てば必ず画面が開く。
//   ★閉じるほうは保留しない(「儀式のあいだ閉じられない画面」を作らない)。

let riteHoldUntil = 0;
let riteHoldTimer = null;

/** 開始シーケンスの画面をいま保留中か */
function riteHolding() {
    return riteHoldUntil > Date.now();
}

function riteHold(ms) {
    const wait = Math.min(FX_RITE_HOLD_MAX_MS, Math.max(0, ms || 0));
    if (wait <= 0) {
        return;
    }
    riteHoldUntil = Math.max(riteHoldUntil, Date.now() + wait);
    if (riteHoldTimer !== null) {
        clearTimeout(riteHoldTimer);
    }
    riteHoldTimer = setTimeout(() => {
        riteHoldTimer = null;
        // ★保留していた画面をここで開く。配信を待たずに開くのは、
        //   儀式のあとに配信が来る保証がどこにも無いためである
        if (latestView) {
            renderStartUi(latestView);
        }
    }, Math.max(0, riteHoldUntil - Date.now()) + 20);
}

/**
 * ★★配信1件を反映する<b>唯一の入口</b>(設計書 2-2 の流れ)。
 *
 * <pre>
 *   (1) diff      prevView と今回のビューを突合する(DOMに触らない)
 *   (2) capture   差分に該当するカードだけ、旧DOMから位置を採る(read)
 *   (3) renderAll 従来どおり全消し再描画する(write)
 *   (4) fxSpawn   measurePhase の末尾で新位置を読み、fx層へゴーストを出す(read)
 * </pre>
 *
 * ★Undo で差分が逆向きに出るのは<b>抑制しない</b>(設計書 1-2)。
 * 逆向きの移動は実際に起きた状態変化の正確な描画であり、
 * 何が取り消されたかが見えるのはむしろ利益である。
 *
 * <h3>★★Batch 38: 開始シーケンスの除外が<b>ここから消えた</b></h3>
 * 除外そのものは無くなっていない —— {@link diffViews} の中へ移した。
 * 移したことで「開始シーケンス中でも儀式だけは通る」が1箇所で言えるようになり、
 * ここは「差分を採るか」だけを見る形に戻っている。
 * ★上限({@link FX_BULK_LIMIT})はそのまま全体にかかる。儀式は1〜2件しか作らないので
 * 引っかからないが、<b>特例で外しているのではない</b>(裁定11)。
 */
function applyView(view) {
    let effects = null;
    // ★★Batch 37: 差分を採る条件に「音が使えるか」を足した。演出を切っている人
    //   (prefers-reduced-motion)にも音は鳴るので、見た目のゲートだけで差分の計算を
    //   飛ばすと<b>音が道連れで消える</b>
    if (fxDiffNeeded() && prevView) {
        effects = fxEffects(diffViews(prevView, view));
        if (effects.length === 0 || effects.length > FX_BULK_LIMIT) {
            effects = null;
        }
    }
    // ★★Batch 38: 儀式を出す配信では、開始シーケンスの画面を儀式が終わるまで待たせる。
    //   ★見た目を出さないとき(prefers-reduced-motion / fxEnabled = false)は待たせない。
    //     待つ理由が無いのに待たせると、操作が遅れるだけになる ——
    //     音だけの人にとって儀式は「一瞬で終わるもの」である
    if (effects && fxAllowed()) {
        riteHold(fxRiteHoldMs(effects));
    }
    // ★旧位置の採取は<b>見た目のためだけ</b>のものである。音しか出さないときは読まない
    pendingFx = effects
        ? { effects: effects, origins: fxAllowed() ? fxCaptureOrigins(effects) : new Map() }
        : null;
    prevView = view;
    latestView = view;
    renderAll(view);
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
        closeInfoModal(modal);
    };
    document.getElementById('stat-modal-close').onclick = () => closeInfoModal(modal);
    openInfoModal('stat-modal');
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

    // ★Batch 30(マスター指示): ±5 は廃止した。±1 は盤面のリーダータイルへ出したので、
    //   モーダルの役割は「大きく動かす/直接打ち込む」に絞られる。それは数値入力欄の仕事であり、
    //   5刻みのボタンは中途半端な位置にある。★モーダルの ±1 は残す
    //   (モーダルを開いたまま連打する使い方があり、開き直させる理由が無い)。
    const delta = (amount) => send('lp', { seat: seatId, delta: amount });
    document.getElementById('lp-modal-minus1').onclick = () => delta(-1);
    document.getElementById('lp-modal-plus1').onclick = () => delta(1);
    document.getElementById('lp-modal-close').onclick = () => {
        closeInfoModal(modal);
        lpModalSeatId = null;
    };
    openInfoModal('lp-modal');
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
        closeInfoModal(modal);
        weaponModalCardId = null;
    };
    openInfoModal('weapon-modal');
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
        closeInfoModal(modal);
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
    document.getElementById('label-modal-close').onclick = () => closeInfoModal(modal);
    openInfoModal('label-modal');
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

// ★Batch 36: 素の confirm() をやめた(0-4)。文言は「何が起きるか」を書き、
//   ボタンには動詞を置く。[OK] しか出せない confirm() では書けなかったものである。
document.getElementById('btn-reset').addEventListener('click', () => {
    askConfirm('盤面をリセットして引き直す。Undo では戻せない。',
        'リセットする', () => send('reset', {}));
});

document.getElementById('btn-leave').addEventListener('click', () => {
    askConfirm('この部屋から退室する。席は空き、盤面はこの端末から見えなくなる。',
        '退室する', () => {
            send('leave', {});
            forgetOccupant();
            location.href = '/';
        });
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
        askConfirm('席を立って観戦に移る。席は空き、以後は盤面を操作できない。',
            '席を立つ', () => send('seat', { seat: null }));
        return;
    }
    askConfirm('この部屋は観戦できないため、席を立つと退室になる。',
        '席を立って退室する', () => {
            send('seat', { seat: null });
            forgetOccupant();
            location.href = '/';
        });
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
//
// ★Batch 36: Esc の対象に加えた。ただし<b>トラップはしない</b>(0-3)。
//   ポップオーバーは画面を覆っていない。裏を触れる物の中へフォーカスを
//   閉じ込めるのは、見た目と挙動が食い違う。
function closeOccupantPopover() {
    const pop = document.getElementById('occupant-popover');
    pop.classList.add('d-none');
    popModalLayer(pop);
}

document.getElementById('occupant-list').addEventListener('click', () => {
    const pop = document.getElementById('occupant-popover');
    if (pop.classList.contains('d-none')) {
        pop.classList.remove('d-none');
        pushModalLayer(pop, { trap: false, escape: closeOccupantPopover });
    } else {
        closeOccupantPopover();
    }
});
document.getElementById('occupant-popover-close').addEventListener('click', closeOccupantPopover);

// ---------------------------------------------------------------
// モーダルの × ボタン(★Batch 34 hotfix。マスター指摘)
// ---------------------------------------------------------------
//
// ★何が問題だったか。閉じる手段が本文の<b>いちばん下</b>にしかなく、
//   操作説明のように長いモーダルではスクロールしないと出口が見えなかった。
//   34 で初回に自動で開くようにしたので、これは「初めて見る画面から出られない」に直結する。
//
// ★★× は<b>[閉じる] を押す</b>だけにする。独自に `d-none` を付けない。
//   LPモーダルは `lpModalSeatId` を、ウェポンモーダルは `weaponModalCardId` を
//   閉じるときに捨てている。× 側に「閉じる処理」を書き写すと、
//   後片付けが2箇所になり、片方だけ直したときに<b>閉じたのに参照が残る</b>。
//   閉じ方を1つに保つのが目的であって、ボタンを増やすのが目的ではない。
//
// ★[閉じる] のハンドラはモーダルを開くたびに `.onclick` で貼り替えられる。
//   だからここで「そのときのハンドラ」を掴んではいけない。
//   毎回 `click()` を投げれば、常に最新のハンドラが走る。
//
// ★開始シーケンスの2つ(start-method / start-order)には × を付けていない。
//   あれは出口が「リセットして最初から」しか無いのが意図であり(23 設計書 7-2)、
//   サーバ側の状態を残したまま画面だけ閉じられるようにしてはいけない。
//
// ★★Batch 36: <b>Esc も同じ資格しか持たない</b>(0-3)。× と Esc は
//   どちらも [閉じる] を click() するだけであり、閉じ方の本体は1箇所のままである。
//   したがって「× が無いモーダルでは Esc も効かない」が自動的に成り立つ。
//   出口の有無という1つの事実から、2つの入口が同時に決まっている。
for (const x of document.querySelectorAll('.info-modal-x')) {
    const closeId = x.id.replace(/-x$/, '-close');
    const closeBtn = document.getElementById(closeId);
    if (!closeBtn) continue;
    x.addEventListener('click', () => closeBtn.click());
}

// ★2-6: 操作説明モーダル
function openHelpModal() {
    openInfoModal('help-modal');
    // ★開いた時点で「見た」とみなす。閉じるのを待たない。
    //   閉じずにタブを落とした人へ次も出すのは、親切ではなく<b>同じ邪魔の繰り返し</b>である
    markHelpSeen();
}

document.getElementById('btn-help').addEventListener('click', openHelpModal);
document.getElementById('help-modal-close').addEventListener('click', () => {
    closeInfoModal('help-modal');
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
        // ★Batch 29: 中身は配信に載っていないので取り直す。応答が来たら
        //   fetchDeckContents 側が描き直すため、ここでは今の内容のまま置いておく
        //   (先に描き直すと、届くまでの一瞬だけ「読み込み中」に戻ってちらつく)。
        fetchDeckContents();
    } else if (activeOverlay.kind === 'mulligan') {
        // ★23 4-3: 開閉を決めるのは renderStartUi 側。ここは「開いていれば描き直す」だけ
        renderMulliganOverlay();
    }
}

function closeOverlay() {
    activeOverlay = null;
    const root = document.getElementById('manual-overlay-root');
    if (root) {
        popModalLayer(root);
        root.remove();
    }
}

/**
 * Esc でオーバーレイを閉じる(★Batch 36)。
 *
 * ★★マリガンだけは閉じない。あれは開始シーケンスであり、
 * 出口は「リセットして最初から」しか無い(裁定34)。
 * 画面だけ閉じられるとサーバ側の状態が残り、盤面が固まったまま理由が消える。
 */
function escapeOverlay() {
    if (activeOverlay && activeOverlay.kind === 'mulligan') return;
    closeOverlay();
}

function overlayRoot() {
    let root = document.getElementById('manual-overlay-root');
    if (!root) {
        root = document.createElement('div');
        root.id = 'manual-overlay-root';
        document.body.appendChild(root);
        // ★トラップはしない(0-3)。帯と全面表示は配信のたびに中身を作り直すため、
        //   フォーカスの閉じ込めがビューの更新と競合する
        pushModalLayer(root, { trap: false, escape: escapeOverlay });
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
        breakdown: typeBreakdown(items),
        seatId: activeOverlay.seatId,
        zoneName: activeOverlay.zoneName,
        items,
        showSearch,
    });
}

/**
 * 種別ごとの枚数(★Batch 30・マスター指示)。
 *
 * ★進化ミニオン({@code EVOLUTION})はミニオンに合算する。
 * 総合ルール上どちらもミニオンであり、墓地を数える目的
 * (闇文明のカードが墓地の枚数を参照する)では区別する意味が無い。
 * 種別が分からないカード(突合できていない)は数に入れず、合計との差で見える形にする。
 */
function typeBreakdown(items) {
    const count = { MINION: 0, SPELL: 0, WEAPON: 0 };
    for (const card of items || []) {
        if (card.type === 'MINION' || card.type === 'EVOLUTION') {
            count.MINION++;
        } else if (count[card.type] !== undefined) {
            count[card.type]++;
        }
    }
    return `ミニオン ${count.MINION} / スペル ${count.SPELL} / ウェポン ${count.WEAPON}`;
}

// ---- 帯: 進化スタック(+nバッジ) ----

function openEvolutionBand(card, seatId) {
    activeOverlay = { kind: 'evolution', seatId, evolutionCardId: card.instanceId };
    renderEvolutionBand();
}

/**
 * 束の最上段を探す。★Batch 27: FIELD / WEAPON だけでなく<b>その席の全ゾーン</b>を見る。
 *
 * 進化スタックは FIELD にしか存在しない(サーバの不変条件。ManualOperationService.unstack)
 * が、ここを FIELD 決め打ちにしておくと、万一その不変条件が破れたときに
 * <b>カードが画面から消えたまま取り出せない</b>という最悪の壊れ方に戻る。
 * 探索は席の zones を舐めるだけであり、安い保険である。
 */
function findStackTop(seatView, instanceId) {
    for (const zoneName of Object.keys(seatView.zones || {})) {
        const found = findCardByInstanceId(seatView.zones[zoneName], instanceId);
        if (found) {
            return found;
        }
    }
    return null;
}

function renderEvolutionBand() {
    if (!latestView) return;
    const seatView = seatOf(latestView, activeOverlay.seatId);
    const top = findStackTop(seatView, activeOverlay.evolutionCardId);
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
function renderBandDom({ title, breakdown, seatId, zoneName, items, showSearch }) {
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
    if (breakdown) {
        // ★Batch 30: 総枚数だけでなく種別の内訳も出す(墓地を数える読みに要る)
        const sub = document.createElement('span');
        sub.className = 'manual-band-breakdown';
        sub.textContent = breakdown;
        titleSpan.appendChild(sub);
    }
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
    // ★Batch 27: 帯にも +n バッジを出す。盤面のタイルにしかバッジが無かったため、
    //   束が FIELD 以外にあると素材を開く入口がどこにも無かった(不具合の一因)。
    //   サーバ側の解体でこの状態は起きなくなったが、「カードが画面から消える」経路は
    //   1つも残さない。押すと進化スタックの帯に切り替わる。
    if (card.stackSize > 1) {
        const badge = document.createElement('div');
        badge.className = 'manual-tile-badge manual-band-badge';
        badge.textContent = '+' + (card.stackSize - 1);
        badge.title = tileTitle('進化スタック', '左=中身を開く');
        badge.addEventListener('click', (e) => {
            e.stopPropagation();
            openEvolutionBand(card, seatId);
        });
        wrap.appendChild(badge);
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
    activeOverlay = { kind: 'deck', seatId, searchQuery: '', cards: null, error: null };
    renderDeckFullscreen();
    fetchDeckContents();
}

/** 直近のゾーン取得の通し番号(★Batch 29)。遅れて届いた応答を捨てるために使う */
let zoneFetchSeq = 0;

/**
 * ★Batch 29: 山札の中身を取りに行く。
 *
 * <h3>なぜ配信で受け取らないのか</h3>
 * 山札は盤面ではパイル1枚ぶんしか描かれないのに、27 までは30枚ぶんのカードが
 * <b>毎操作・全員へ</b>流れていた(盤面26KBのうち約10KB。28 設計解説1-5)。
 * 中身が要るのはこの全面表示を開いている間だけなので、そのときだけ取りに来る。
 *
 * <h3>★遅れて届いた応答は捨てる</h3>
 * 全面表示を開いている間は、配信が届くたびに取り直す(並べ替えやシャッフルで
 * 中身が変わるため)。ネットワークの都合で応答の順序は入れ替わりうるので、
 * 通し番号が最新でないものは無視する。これを書かないと、
 * <b>古い並びが新しい並びを上書きする</b>という、再現しにくい壊れ方をする。
 * 席が一致することも確かめる(開き直した直後の応答を弾く)。
 */
async function fetchDeckContents() {
    if (!activeOverlay || activeOverlay.kind !== 'deck') {
        return;
    }
    const seq = ++zoneFetchSeq;
    const seatId = activeOverlay.seatId;
    const params = new URLSearchParams({ seat: seatId, zone: 'DECK' });
    if (OCCUPANT_ID) {
        params.set('occupantId', OCCUPANT_ID);
    }
    try {
        const res = await fetch(`/manual/api/rooms/${ROOM_ID}/zone?${params}`);
        if (!res.ok) {
            throw new Error((await res.json()).message || '山札を取得できませんでした');
        }
        const body = await res.json();
        if (seq !== zoneFetchSeq || !activeOverlay || activeOverlay.kind !== 'deck'
                || activeOverlay.seatId !== body.seat) {
            return;
        }
        activeOverlay.cards = body.cards;
        activeOverlay.error = null;
    } catch (err) {
        if (seq !== zoneFetchSeq || !activeOverlay || activeOverlay.kind !== 'deck') {
            return;
        }
        activeOverlay.error = err.message;
    }
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
    // ★Batch 29: 中身は配信ではなく /manual/api/rooms/{id}/zone から来る。
    //   null は「まだ届いていない」であり、空配列(山札が空)とは意味が違う。
    //   枚数の見出しは counts から取る(取得を待たずに正しい数が出る)。
    const deck = activeOverlay.cards || [];
    const loading = activeOverlay.cards === null && !activeOverlay.error;
    const total = zoneCount(seatView, 'DECK');

    const root = overlayRoot();
    root.innerHTML = '';

    const screen = document.createElement('div');
    screen.className = 'manual-fullscreen';

    const header = document.createElement('div');
    header.className = 'manual-fullscreen-header';
    const title = document.createElement('span');
    title.textContent = `山札(${total}枚) — 席${activeOverlay.seatId}`;
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
        // ★取得中・取得失敗は「空の山札」と区別して出す(29)
        if (loading || activeOverlay.error) {
            const note = document.createElement('div');
            note.className = 'small p-2' + (activeOverlay.error ? ' text-danger' : ' text-muted');
            note.id = 'deck-fullscreen-status';
            note.textContent = activeOverlay.error
                ? '山札を取得できませんでした: ' + activeOverlay.error
                : '山札を読み込んでいます...';
            list.appendChild(note);
            return;
        }
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

/**
 * 開始シーケンスのモーダルの開閉。
 *
 * ★★Batch 36: ここは<b>サーバの状態が開閉を決める</b>唯一のモーダルである。
 * 人が閉じるのではなく、配信のたびに開いたり閉じたりする。だから層への
 * 出し入れも配信のたびに要る。{@code openInfoModal} は二重に積まないので、
 * 開いている間は毎回呼んでも層は1つのままである(0-3)。
 * ★このモーダルには [閉じる] が無いので、Esc も効かない(裁定34)。
 * 出口は「リセットして最初から」だけである。
 */
function toggleStartModal(id, show) {
    if (show) {
        openInfoModal(id);
    } else {
        closeInfoModal(id);
    }
}

/** 開始シーケンスの画面。renderAll の最後に呼ぶ(モーダル・オーバーレイ・待機表示) */
function renderStartUi(view) {
    const start = view.start || {};
    // ★★Batch 38: 儀式の最中は開始シーケンスの画面を<b>開かない</b>(38 設計書4章)。
    //   モーダルもオーバーレイも fx層より上にあり、開いていると儀式が見えない。
    //   ★閉じるほうは保留しない。保留するのは「開く」だけである
    const holding = riteHolding();

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
    toggleStartModal('start-method-modal', !holding && !!start.canChooseMethod);

    // 2) 先攻・後攻の選択(3-3)。ダイスで勝った席のプレイヤーだけに出る
    if (start.canChooseOrder) {
        document.getElementById('start-order-title').textContent =
            `席${start.orderChooser} が先攻・後攻を選ぶ`;
    }
    toggleStartModal('start-order-modal', !holding && !!start.canChooseOrder);

    // 3) マリガン(4-3)。専用オーバーレイの開閉を決める
    syncMulliganOverlay(start, holding);

    // 4) 待機表示(7-3)。★自分が今押すものが無いときだけ出す。
    //    盤面が固まっている理由が画面に書かれていない状態を作らない(21 3-5)
    //    ★★Batch 38: 儀式のあいだは出さない。<b>盤面が固まっている理由は儀式そのもの</b>
    //      であり、目の前で起きていることの説明を重ねて出す必要は無い
    const busy = start.canChooseMethod || start.canChooseOrder
        || (start.myMulliganSeats || []).length > 0;
    const banner = document.getElementById('start-banner');
    banner.classList.toggle('d-none', holding || !(start.locking && start.waiting && !busy));
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
// ★Batch 36: 文言と確認は bindStartReset(0-4)へ移した。
//   マリガンのオーバーレイが動的に作る同じボタンと<b>同じ1箇所</b>を通す。
for (const btn of document.querySelectorAll('.manual-start-reset')) {
    bindStartReset(btn);
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
function syncMulliganOverlay(start, holding) {
    // ★★Batch 38: 儀式のあいだは開かない。閉じるほうは通す(38 設計書4章)
    const mine = holding ? [] : (start.myMulliganSeats || []);
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

    // ★Batch 36: 変数名を confirmBtn に変えた。0-4 で askConfirm を入れたので、
    //   ここで confirm という名前を使うと「素の confirm()」と読み違える
    const confirmBtn = document.createElement('button');
    confirmBtn.id = 'mulligan-confirm';
    confirmBtn.className = 'btn btn-sm btn-warning ms-auto';
    confirmBtn.textContent = '確定';
    confirmBtn.addEventListener('click', () => {
        // ★引く枚数は載せない。サーバが戻した枚数と同数を引く(4-4・設計判断27)
        send('mulligan', { seat: seatId, cardIds: mulliganPicked(hand) });
    });
    foot.appendChild(confirmBtn);

    const reset = document.createElement('button');
    reset.className = 'btn btn-sm btn-outline-danger manual-start-reset';
    reset.textContent = 'リセットして最初から';
    bindStartReset(reset);
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
