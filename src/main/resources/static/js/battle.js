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
 * ★★Batch 66: 自分が誰であるか。
 *
 * 65 まではサーバがページ生成時に埋め込む const だった。66 からは
 * localStorage(復帰)か席選択画面(初回)が決めるので、決まるまで null である。
 * ★観戦者の id もここに入る —— 配信の宛先はプレイヤーと同じ形だからである。
 */
let PLAYER_ID = null;

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
 * ★★★Batch 70: <b>確定を待っている操作</b>(裁定319・321・323)。
 *
 * <h2>69 までは禁忌専用だった</h2>
 *
 * この変数は 43 から {@code tabooPay}(禁忌コストの支払い)という名前で、
 * 禁忌カードだけが「マナを選ぶ → 対象を選ぶ → 送る」という2段の道を通っていた。
 * ★裁定319 が「クリックからのプレイには<b>必ず</b>確認を挟む」と決め、
 *   裁定323 が「マナチャージも、クリックなら確認を挟む」と決めたので、
 *   <b>確定を待つ状態が3種類</b>になった。
 * ★★<b>3つを別々の変数にしなかった。</b>確定・キャンセルの導線が3組に増えると、
 *   「今何を待っているのか」の判定が3箇所に散る(裁定130)。
 *   種類は {@code kind} で分け、器は1つにしてある。
 *
 * <h2>形</h2>
 *
 * <pre>
 * { kind: 'PLAY' | 'TABOO' | 'CHARGE',
 *   cost,            // 選ばせるマナの枚数(CHARGE は 0)
 *   picked: [],      // 選んだマナの位置
 *   warn,            // 出しておく警告(裁定317。null なら無い)
 *   card,            // 面と名前を出すためのカード(CHARGE でも入る)
 *   handIndex,       // PLAY / CHARGE
 *   tabooIndex,      // TABOO
 *   action, specs, extra, evolutionFlow }   // 確定したあとに進む先(PLAY / TABOO)
 * </pre>
 *
 * ★<b>ドラッグからのプレイはここを通らない</b>(裁定321: 落とす行為が確認を兼ねる)——
 *   ただし裁定317 の警告(裏向きマナが墓地送りになる)だけは例外で、
 *   そのときは<b>自動の払い方をあらかじめ選んだ状態</b>でここへ入る。
 *   警告と手動選択を別の器にしないのは、どちらも「確定を待っている」からである。
 */
let manaPay = null;

/**
 * ★Batch 52: 進化召喚の素材選択(裁定154)。
 *
 * 通常の対象選択(pending)とは別の段である —— 素材は「効果の対象」ではなく
 * <b>召喚の一部</b>であり、コストを払う前に決まっていなければならない。
 * 順序は<b>素材 → 対象 → 送信</b>で、素材が決まった時点で beginSelection に渡す。
 *
 * ★<b>素材にできるかの判定はこちらに無い。</b>サーバが候補の instanceId を
 * card.evolutionMaterialIds に載せて送ってくるので、この画面はその一覧に
 * 入っているかどうかしか見ない(裁定163: 同じ規則を2つの言語に置かない)。
 * { action, handIndex, specs, extra, card, picked: [instanceId...] }
 */
let evolution = null;

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
    // ★★Batch 71: 「初回の接続」と「再接続」を区別する(33 の 1-5 を写した)。
    //   初回に「再接続しました」と出したら、それは<b>嘘の宣言</b>である。
    //   宣言は事実に一致していなければ、無いほうがましである(32b のターンの帯と同じ理屈)。
    const reconnected = connectionEstablishedOnce;
    connectionEstablishedOnce = true;
    socketDown = false;
    offlinePeeking = false;
    setConnectionStatus('接続済み');
    updateOfflineLock();
    setConnBar(reconnected ? '再接続しました。盤面を同期しています' : null,
        'ok', CONN_BAR_MS);
    client.subscribe(`/topic/room/${ROOM_ID}/player/${PLAYER_ID}`, onMessage);
    send('ready', {});
};

client.onWebSocketClose = () => {
    socketDown = true;
    setConnectionStatus('切断(再接続中...)');
    updateOfflineLock();
};

// ★Batch 71: サーバが STOMP 水準でエラーを返した場合。再接続では直らないことが多い。
//   ★手動モードと違い、通常モードは 66 まで onStompError を<b>1つも持っていなかった</b> ——
//     つまりサーバ側のエラーは画面に何も出ないまま捨てられていた。
client.onStompError = (frame) => {
    socketDown = true;
    setConnectionStatus('サーバとの通信でエラー: ' + (frame.headers.message || '不明'));
    updateOfflineLock();
};

// ★★Batch 66: 接続の開始は「0) 在席」の末尾へ移した(startViewerSession)。
//   ここに置くと、まだ評価されていない const(OCCUPANT_STORAGE_KEY)を掴んで
//   ReferenceError になる —— 詳しくは 0 章の末尾に書いた。

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
    // ★★★Batch 72: 確認モーダルがいちばん上である(z-index 1965)。
    //   ★Esc を下の層へ落とさない —— 36 の裁定49 を通常モードでも守る
    if (isAutoConfirmOpen()) {
        e.preventDefault();
        closeAutoConfirm();
        return;
    }
    // ★★Batch 72: 席を変えるためのゲートには<b>出口が在る</b>([やめる])ので Esc も効く。
    //   ★入室前の席選択(JOIN)には出口が無い(裁定34)。同じ要素だが別の状態である
    if (seatGateMode === 'CHANGE'
            && !document.getElementById('seat-gate').classList.contains('d-none')) {
        e.preventDefault();
        closeSeatChangeGate();
        return;
    }
    const overlay = document.getElementById('auto-zoom');
    if (overlay && !overlay.classList.contains('d-none')) {
        e.preventDefault();
        closeZoom();
        return;
    }
    // ★Batch 62: 音の設定も Esc で閉じる(手動モードと同じ流儀)
    const soundModal = document.getElementById('sound-modal');
    if (soundModal && !soundModal.classList.contains('d-none')) {
        e.preventDefault();
        closeSoundModal();
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
// ★★★1-2) 切断中のロック(Batch 71)
// ---------------------------------------------------------------
//
// ★手動モードの 33(1章)を通常モードへ写したものである。
//   65 が挙げた盤面の穴のうち、69 も 70 も意図的に残していた最後の1つがこれである。
//
// ★★★<b>番人は send() であって、オーバーレイではない。</b>
//   「切断中は操作できない」を作る方法は2つある ——
//     (a) 画面を覆って触らせない(オーバーレイ)
//     (b) 送信の口で弾く(send() のガード)
//   (a) だけで済ませると、覗き見の導線を1つ足した瞬間に穴が開く。
//   そこで (b) を番人とし、(a) は<b>宣言</b>に格下げしてある。
//
// ★★<b>70 で実害が増えた。</b>手札からの操作が2つの入口になり、
//   確定待ち(manaPay)・進化素材の選択・割り込みの選択という
//   <b>ローカルにしか無い状態</b>を抱えるようになった。
//   送れなかったのにそれらを畳むと、「[確定]を押したのに何も起きず、
//   しかも選んだマナまで消えた」になる。
//   → <b>send() は送ったかどうかを返し、呼び出し側は送れなかったら畳まない</b>(4-2)。
//
// ★★<b>部屋消失(手動モードの connectionFatal)にあたるものは作っていない。</b>
//   通常モードには showRoomLostFatal が無く(66 が作らなかった)、
//   <b>その旗を立てる人が1人も居ない</b>。使い手の無い状態変数は、
//   70 が見つけた `.mana-chip` と同じもの(書いてあるのに効いていない器)になる。
//   ★作らなかったことは notes/batch71-design-notes.md と落とし穴に書き残してある(裁定196)。

const CONN_BAR_MS = 3500;

let connectionEstablishedOnce = false;   // 一度でも接続できたか(=次は「再接続」)
let offlinePeeking = false;              // 「盤面を確認する」でロックを畳んだ状態
let socketDown = false;                  // onWebSocketClose / onStompError の記録
let connBarTimer = null;

/**
 * 接続しているか。★接続の判定はこの1箇所だけが持つ(裁定130)。
 * {@code send()} もオーバーレイもここを見る。
 *
 * ★{@code client.connected} だけに頼らないのは、{@code onWebSocketClose} が呼ばれる
 * 時点でライブラリ内部のフラグが既に倒れている保証を<b>こちらが持てない</b>ためである
 * (StompJS の実装詳細である)。倒れていなければオーバーレイが出ず、
 * しかも<b>音を立てずに</b>出ない。自分で観測した事実({@code socketDown})を and で重ねる。
 */
function isConnected() {
    return client.connected === true && !socketDown;
}

/**
 * 席選択ゲート(#seat-gate)がどちらの意味で開いているか(★Batch 72)。
 *
 * - {@code 'JOIN'} …… <b>入室前</b>。playerId をまだ持っていない。決めるのは HTTP の受付 API
 * - {@code 'CHANGE'} …… <b>入室後</b>。観戦者が空席に着く。決めるのは WebSocket の {@code seat}
 *
 * ★★<b>ここに置いてある理由</b>: {@code isGateVisible()} がこの値を読むのに、
 * あの関数は「0) 在席」より<b>前</b>の章に居る。0章に置くと、
 * 66 が実際に踏んだ一時的死角(TDZ)を作りうる ——
 * <b>関数宣言は巻き上がるので呼べてしまい、中身だけが死ぬ。</b>
 * 読む側と同じ章に置けば、その順序を将来にわたって考えなくてよい。
 */
let seatGateMode = 'JOIN';

/**
 * <b>入室前の</b>席選択のゲートが出ているか。
 *
 * ★★★<b>Batch 72: モードを見るようになった。</b>72 で同じ要素(#seat-gate)が
 * 「観戦者が席に着く」ためにも開くようになった —— そちらは<b>入室後</b>である。
 * ★判定を「要素が出ているか」だけにしておくと、席を選んでいる最中に相手が落ちても
 * <b>切断の案内が出ない</b>。71 がデッキゲートについて下した判断
 * (入室後のゲートでは案内を出す)と<b>まったく同じ理由</b>であり、
 * ここはその判断を延長しただけである。
 *
 * ★★<b>デッキ読み込みのゲート(#deck-gate)は含めない</b>(マスター確認)。
 * 手動モードが席選択ゲートを除いているのは「まだ盤面に入っていない人に
 * 『盤面が操作できません』と言っても意味が無い」からである。
 * デッキゲートは<b>入室後</b>であり、しかも読み込みの最後に {@code send('ready')} を撃つ ——
 * 切断中はそれが届かないので、ファイルを選んでも
 * <b>「相手のデッキ待ち」の顔のまま黙って止まる</b>。これはいちばん分かりにくい形である。
 */
function isGateVisible() {
    return seatGateMode === 'JOIN' && !gateEl('seat-gate').classList.contains('d-none');
}

/**
 * 接続の帯。{@code text} が null なら隠す。{@code ms} を与えると自動で消える。
 * ★状態は「切断中(offline)」「復帰(ok)」「無し」の3つだけであり、<b>要素は1つ</b>である。
 * 増やすなら kind を足すこと(要素を増やさない)。
 */
function setConnBar(text, kind, ms) {
    const bar = document.getElementById('auto-conn-bar');
    if (connBarTimer !== null) {
        clearTimeout(connBarTimer);
        connBarTimer = null;
    }
    bar.classList.remove('auto-conn-bar-offline', 'auto-conn-bar-ok');
    if (!text) {
        bar.textContent = '';
        bar.classList.add('d-none');
        return;
    }
    bar.textContent = text;
    bar.classList.add('auto-conn-bar-' + kind);
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
 * ★<b>出す/出さないの判定をここ1箇所に閉じる</b>(重ね順で解決しない)。
 */
function updateOfflineLock() {
    const offline = !isConnected() && !isGateVisible();
    document.getElementById('auto-offline')
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
 * 拒否の合図(★Batch 71)。手動モードの {@code flashDenied} と同じ役割である。
 *
 * ★★<b>トーストは作らなかった</b>(マスター確認)。
 * ガードが火を噴く場面では、宣言(オーバーレイか帯)が<b>既に出ている</b> ——
 * 覆っているならオーバーレイが、畳んでいるなら帯が、切断中であることを言っている。
 * 足りないのは「<b>いま押したそれ</b>が弾かれた」だけなので、明滅で指し示せば足りる。
 * ★器を1つ増やさない判断である(70 の「確定待ちの器を1つにした」と同じ筋)。
 */
function flashDenied(el) {
    if (!el) return;
    el.classList.remove('auto-denied');
    void el.offsetWidth;
    el.classList.add('auto-denied');
    setTimeout(() => el.classList.remove('auto-denied'), 700);
}

// ★「盤面を確認する」= オーバーレイだけ畳む。ガード(send)は効いたままである
document.getElementById('auto-offline-peek').addEventListener('click', () => {
    offlinePeeking = true;
    updateOfflineLock();
});

// ---------------------------------------------------------------
// ★★★0) 在席(Batch 66)
// ---------------------------------------------------------------
//
// ★手動モードの manual-battle.js「0) 在室」を通常モードへ写したものである。
//   写した理由は 21b と同じ ——<b>盤面ページの直リンクで来た人にも、
//   必ず席選択を通させたい</b>。分岐で守ると、経路が増えるたびに漏れる。
//
// ★★<b>通常モードにはゲートが2枚ある。</b>
//   1枚目が席選択(手動モードと同じ)、2枚目が<b>デッキの読み込み</b>である。
//   後者は手動モードに無い —— あちらは盤面の入れ物なので、デッキが無くても遊べる。
//   通常モードはサーバが山札を配るので、デッキが無いと試合そのものが作れない。
//
// ★★★<b>復帰の鍵は localStorage である。</b>65 まで、通常モードの playerId は
//   URL のクエリに載っていた(/rooms/{id}/play?playerId=...)。あの形は
//   <b>URL を共有すると相手の席として入れてしまう</b>うえ、URL を失うと戻れなかった。

const OCCUPANT_STORAGE_KEY = `qte-auto-occupant-${ROOM_ID}`;

let gateResolve = null;

function gateEl(id) {
    return document.getElementById(id);
}

function loadSavedOccupant() {
    try {
        const raw = localStorage.getItem(OCCUPANT_STORAGE_KEY);
        if (!raw) return null;
        const parsed = JSON.parse(raw);
        return parsed && parsed.playerId ? parsed : null;
    } catch (e) {
        return null;
    }
}

function saveOccupant(playerId, displayName) {
    localStorage.setItem(OCCUPANT_STORAGE_KEY, JSON.stringify({ playerId, displayName }));
}

/**
 * 在席の記録を捨てる(★Batch 72・退室)。
 *
 * ★★<b>サーバが退室を受理してから呼ぶ</b>(onMessage の LEFT)。
 * 手動モードは {@code send('leave'); forgetOccupant(); location.href='/';} と
 * 送って即座に消すが、あちらの退室は<b>失敗しない</b>。
 * 通常モードの退室は対戦中の着席者に断られる —— 先に消すと、
 * <b>断られたのに戻れない</b>端末ができる(設計解説 4-3)。
 *
 * ★消せなくても遷移そのものは進める。localStorage が使えない状況で
 * 部屋に閉じ込めるほうが害が大きい。
 */
function forgetOccupant() {
    try {
        localStorage.removeItem(OCCUPANT_STORAGE_KEY);
    } catch (e) {
        console.error('在席の記録を消せなかった', e);
    }
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

/**
 * 席ボタンの状態を1箇所で決める。
 *
 * ★埋まっている席のボタンは無効化し、在席者名を出す。
 * 「押せるが失敗する」より「押せないと分かる」ほうが速い。
 * 無効の理由は dataset.occupied に残す —— setGateBusy が通信中の一時的な無効化と
 * 取り違えて、埋まっている席まで有効に戻してしまわないようにするためである。
 *
 * ★★<b>対戦が始まった部屋では両席とも押せない</b>(通常モードだけの条件)。
 * 手動モードには「始まる」という状態が無いのでこの分岐も無い。
 */
function applyGateSeats(summary) {
    const names = { A: summary.seatAName, B: summary.seatBName };
    for (const seatId of ['A', 'B']) {
        const button = gateEl('seat-gate-' + seatId.toLowerCase());
        const name = names[seatId];
        const occupied = !!name || summary.started;
        button.dataset.occupied = occupied ? 'true' : 'false';
        button.disabled = occupied;
        button.textContent = name
            ? `席${seatId}: ${name}`
            : (summary.started ? `席${seatId}(対戦中)` : `席${seatId}に座る`);
    }
    const spectate = gateEl('seat-gate-spectate');
    // ★観戦を許さない部屋では観戦ボタンを出さない。届く宛先が存在しないためである
    spectate.classList.toggle('d-none', !summary.spectatorAllowed);
    spectate.dataset.occupied = 'false';
}

/** 入室前の席選択。決着(入室成功)まで盤面は描かない。 */
function openJoinGate(summary) {
    gateEl('seat-gate-room').textContent = summary.roomName || '(名称未設定)';
    gateEl('seat-gate-code').textContent = ROOM_ID;
    gateEl('seat-gate-name-wrap').classList.remove('d-none');
    gateEl('seat-gate-buttons').classList.remove('d-none');
    clearGateError();
    applyGateSeats(summary);
    gateEl('seat-gate').classList.remove('d-none');
    updateOfflineLock();   // ★Batch 71: ゲートが出ている間は切断の案内を出さない
    return new Promise((resolve) => { gateResolve = resolve; });
}

/** 部屋が見つからない等、席選択そのものが成立しないとき。 */
function showGateFatal(message) {
    gateEl('seat-gate-room').textContent = '入れませんでした';
    gateEl('seat-gate-code').textContent = ROOM_ID;
    gateEl('seat-gate-name-wrap').classList.add('d-none');
    gateEl('seat-gate-buttons').classList.add('d-none');
    showGateError(message);
    gateEl('seat-gate').classList.remove('d-none');
    updateOfflineLock();   // ★Batch 71: 「入れませんでした」と「再接続を待って」を重ねない
}

/**
 * 観戦者が席に着くためにゲートを開く(★Batch 72)。
 *
 * ★★<b>器を新しく作っていない。</b>#seat-gate は「どちらの席が空いているか」を
 * 出す画面としてもう在り、埋まりも「対戦中は座れない」も {@code applyGateSeats} が
 * 1本で決めている —— 足したのは<b>モード</b>だけである
 * (「器が無いと思ったら、まず在るかどうかを見る」・65 の教訓)。
 *
 * ★名前欄は隠す。入室のときに名前は決まっており、
 * ここで変えられると「席を移ると名前も変わる」という別の話が混ざる。
 */
function openSeatChangeGate() {
    const room = latestView && latestView.room;
    if (!room) return;
    seatGateMode = 'CHANGE';
    gateEl('seat-gate-room').textContent = '席に着く';
    gateEl('seat-gate-code').textContent = ROOM_ID;
    gateEl('seat-gate-name-wrap').classList.add('d-none');
    gateEl('seat-gate-buttons').classList.remove('d-none');
    clearGateError();
    applyGateSeats({
        seatAName: room.seatA ? room.seatA.name : null,
        seatBName: room.seatB ? room.seatB.name : null,
        started: latestView.status !== 'WAITING',
        spectatorAllowed: room.spectatorAllowed,
    });
    // ★「観戦する」ボタンは、入室後は「やめる」である —— もう観戦しているのだから、
    //   ここで押して起きることは「席に着くのをやめる」でしかない
    gateEl('seat-gate-spectate').textContent = 'やめる(観戦を続ける)';
    gateEl('seat-gate').classList.remove('d-none');
    // ★★71 の判定はモードを見る(isGateVisible)。CHANGE のあいだは切断の案内を出す
    updateOfflineLock();
}

/** 席替えのゲートを閉じ、入室前の姿へ戻す(★Batch 72) */
function closeSeatChangeGate() {
    seatGateMode = 'JOIN';
    gateEl('seat-gate').classList.add('d-none');
    gateEl('seat-gate-name-wrap').classList.remove('d-none');
    gateEl('seat-gate-spectate').textContent = '観戦する';
    updateOfflineLock();
}

gateEl('seat-gate-buttons').addEventListener('click', async (e) => {
    const button = e.target.closest('button');
    if (!button || button.disabled) return;
    // ★観戦ボタンには data-seat が無い。null が「席に着かない」を表す
    const seat = button.dataset.seat || null;
    // ★★★Batch 72: 入室後(CHANGE)は HTTP の受付を通らない。
    //   既に playerId を持っているので、決めるのは WebSocket の seat である。
    if (seatGateMode === 'CHANGE') {
        closeSeatChangeGate();
        if (seat === null) return;   // [やめる]
        // ★★★設計判断49: 送れなかったら畳まない。先に閉じてから送り、失敗したら開き直す。
        //   ★<b>拒否の理由はゲートの中に出す。</b>send() の明滅はヘッダの帯と接続表示に
        //     当たるが、このゲートは z-index 1060 で<b>ヘッダを覆っている</b> ——
        //     71 が用意した合図がここでは1つも見えない。
        //   ★★<b>合図の器を新しく作らない。</b>ゲートは自分のエラー欄を持っている
        //     (#seat-gate-error)。覆っている側が言えばよい
        if (!send('seat', { seat })) {
            openSeatChangeGate();
            showGateError('サーバとの接続が切れています。復帰してからもう一度お試しください。');
        }
        return;
    }
    const name = gateEl('seat-gate-name').value.trim();
    // ★サーバも同じ検証をする(GameRoom.join)。ここは往復を1回省く操作補助である
    if (seat !== null && name === '') {
        showGateError('席に着くには名前が必要です');
        return;
    }
    clearGateError();
    setGateBusy(true);
    try {
        const res = await fetch(`/auto/api/rooms/${ROOM_ID}/occupants`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ displayName: name || null, seat, spectate: seat === null }),
        });
        if (!res.ok) throw new Error((await res.json()).message || '入室できませんでした');
        const data = await res.json();
        saveOccupant(data.playerId, data.displayName);
        gateEl('seat-gate').classList.add('d-none');
        updateOfflineLock();   // ★Batch 71: 盤面に入った。ここから先は切断の案内を出す
        gateResolve(data.playerId);
    } catch (err) {
        showGateError(err.message);
        setGateBusy(false);
    }
});

/** playerId を確定させてから解決する。localStorage にあればそれを使い、無ければ席選択へ。 */
async function resolveViewer() {
    const saved = loadSavedOccupant();
    if (saved) {
        // ★復帰は席選択を経ない。同じ人が同じ席へ戻るだけだからである
        return saved.playerId;
    }
    const res = await fetch(`/auto/api/rooms/${ROOM_ID}`);
    if (!res.ok) {
        throw new Error((await res.json()).message || '部屋が見つかりません');
    }
    return openJoinGate(await res.json());
}

// ---------------------------------------------------------------
// ★★★0-2) デッキ読み込みのゲート(Batch 66)
// ---------------------------------------------------------------
//
// ★★<b>プリセット(おまかせ)が退役したので、デッキはここで必ず読む。</b>
//   65 まではロビーのフォームが受け取っており、選ばなければ文明ごとの
//   プリセットデッキが配られていた。66 はその配布をやめた(マスター指示)。
//
// ★<b>相手待ちの画面も兼ねる。</b>両席がデッキを読み終えて初めて試合が生成される
//   (GameRoom.bothReady)。片方だけ読み終えて沈黙するのが、いちばん分かりにくい。

/** 席に着いている人か。★観戦者は席が null である */
function viewerSeat() {
    return latestView && latestView.room ? latestView.room.viewerSeat : null;
}

function viewerIsSpectator() {
    return !!(latestView && latestView.room && latestView.room.viewerSpectator);
}

function seatSummaryLine(view) {
    const room = view.room;
    if (!room) return '';
    const line = (label, seat) => {
        if (!seat || !seat.name) return `${label}: 空き`;
        return `${label}: ${seat.name}${seat.deckLoaded ? '(デッキ読込済)' : '(デッキ待ち)'}`;
    };
    return line('席A', room.seatA) + ' / ' + line('席B', room.seatB);
}

/**
 * デッキゲートの出し入れ。★render から毎回呼ばれる(状態はビューが持つ)。
 *
 * ★<b>観戦者には出さない。</b>観戦者にデッキは無い。
 * ★<b>試合が始まったら出さない。</b>山札はもう配られており、載せ替えても効かない
 *   (サーバも断る。LobbyController.importDeck)。
 */
function renderDeckGate(view) {
    const gate = gateEl('deck-gate');
    const waiting = view.status === 'WAITING' && !viewerIsSpectator() && !!viewerSeat();
    gate.classList.toggle('d-none', !waiting);
    if (!waiting) return;
    gateEl('deck-gate-code').textContent = view.roomId;
    gateEl('deck-gate-seat').textContent = '席' + viewerSeat();
    gateEl('deck-gate-seats').textContent = seatSummaryLine(view);
    const mine = viewerSeat() === 'A' ? view.room.seatA : view.room.seatB;
    const file = gateEl('deck-gate-file');
    if (mine && mine.deckLoaded) {
        file.classList.add('d-none');
        gateEl('deck-gate-status').textContent =
            '読み込み済みです。相手がデッキを読み込むと対戦が始まります。';
    } else {
        file.classList.remove('d-none');
        if (!file.dataset.busy) {
            gateEl('deck-gate-status').textContent =
                'デッキメーカーで保存したデッキファイル(.json)を選んでください。';
        }
    }
}

function showDeckGateError(message) {
    const box = gateEl('deck-gate-error');
    box.textContent = message;
    box.classList.remove('d-none');
}

gateEl('deck-gate-file').addEventListener('change', (e) => {
    const input = e.target;
    const file = input.files && input.files[0];
    if (!file) return;
    gateEl('deck-gate-error').classList.add('d-none');
    input.dataset.busy = '1';
    gateEl('deck-gate-status').textContent = '読み込み中...';
    const reader = new FileReader();
    reader.onload = async () => {
        try {
            // ★中身の検査はサーバがする(DeckFileReader → DeckValidator)。
            //   ここで枚数を数えると、規則が2箇所に割れる(裁定130)
            const res = await fetch(
                `/auto/api/rooms/${ROOM_ID}/deck?playerId=${encodeURIComponent(PLAYER_ID)}`,
                { method: 'POST', headers: { 'Content-Type': 'application/json' },
                  body: reader.result });
            if (!res.ok) throw new Error((await res.json()).message || 'デッキを読み込めませんでした');
            const data = await res.json();
            gateEl('deck-gate-status').textContent =
                `${data.deckName} / ${data.leaderName} / メイン${data.mainCount}枚・禁忌${data.tabooCount}枚`;
            // ★配信を待たずに自分で閉じない。サーバのビューが「読み込み済み」を
            //   運んでくるまで開けておく —— 画面が自分で状態を決めると、
            //   失敗した読み込みでも閉じてしまう
            send('ready', {});
        } catch (err) {
            showDeckGateError(err.message);
            gateEl('deck-gate-status').textContent =
                'デッキメーカーで保存したデッキファイル(.json)を選んでください。';
        } finally {
            delete input.dataset.busy;
            input.value = '';
        }
    };
    reader.readAsText(file);
});

// ---------------------------------------------------------------
// ★★★0-3) 接続の開始(Batch 66)
// ---------------------------------------------------------------
//
// ★★<b>ここに置く理由がある。</b>65 までは PLAYER_ID をサーバがページ生成時に
//   埋め込んでいたので、スクリプトの先頭(1章)で activate() してよかった。
//   66 からは localStorage か席選択画面が決めるので、決まるまで待つ。
//
// ★★★<b>66 の作業中に踏んだ穴</b>: この呼び出しを 1章(接続)にそのまま残したところ、
//   resolveViewer が <b>まだ評価されていない const</b>(OCCUPANT_STORAGE_KEY)を掴んで
//   ReferenceError を投げた。JavaScript の const は「宣言より前では触れない」
//   (一時的死角)からである。関数宣言は巻き上がるので<b>呼べてしまう</b> ——
//   呼べるのに中身が死んでいる、といういちばん見えにくい形になる。
//
// ★★<b>そして下の catch がそれを飲み込んだ。</b>画面には「入れませんでした」と出るので、
//   <b>部屋が無いときと区別が付かない</b>。実際 verify は盤面の全項目が
//   一斉に落ちる形でしか教えてくれなかった(ゲートが盤面を覆ってクリックを吸うため)。
//   ★<b>受け止める catch は、書いた本人の間違いも受け止める。</b>
//     だから受け止めた中身は捨てずに console にも出す。
function startViewerSession() {
    resolveViewer()
        .then((viewerId) => {
            PLAYER_ID = viewerId;
            client.activate();
        })
        .catch((e) => {
            // ★握りつぶさない。原因が「部屋が無い」なのか「こちらの間違い」なのかは、
            //   出しておかないと次の人には区別できない
            console.error('在席の解決に失敗した', e);
            showGateFatal(e.message || 'この部屋に入れませんでした');
        });
}

startViewerSession();

// ===============================================================
// ★★★1-5) 効果音(Batch 62・裁定283〜289)
//
// ★★<b>通常モードには 61 まで音が1つも無かった。</b>手動モードには 37・38 で
//   11種の音があり、そちらは<b>盤面の差分</b>を材料にしていた。
//   通常モードには差分層が無く、{@code render(view)} が毎回まるごと描き直すだけである。
//
// ★★★<b>差分の採取層をこちらにも作る</b>(裁定287 = A)。
//   {@code send()} に音を載せる道(B)もあったが、それは
//   「通常モードにいずれ演出を入れる」ときに<b>捨てることになる投資</b>である。
//   裁定68 が守ろうとしていたのは「発火点を増やさないこと」であって
//   「安く済ませること」ではない。
//   ★<b>ただし今回作るのは差分の採取までである</b> ——
//   fx 層(ゴースト・遷移)は作らない。音はここが出す列を読むだけであり、
//   演出を乗せる土台は次のバッチに残る。
//
// ★★★<b>取り付け点は {@link onMessage} である。{@code render()} ではない。</b>
//   {@code render(latestView)} は<b>画面の操作のたびにも呼ばれる</b>(15箇所)。
//   あそこで差分を採ると「配信」と「再描画」を区別できない。
//   <b>サーバから来た出来事の入口は {@code onMessage} の1箇所だけ</b>であり、
//   手動モードで {@code fxSpawn} が「盤面で起きたことは全部1つの列に集まっている」を
//   利用したのと、これは同じ形の利用である。
//
// ★★<b>差分に現れない出来事は {@code send()} から鳴らす</b>(2箇所目)。
//   {@code attack} は「攻撃した」という出来事が view の差分に現れない ——
//   現れるのはHPの減少や消滅という<b>結果</b>だけである。
//   ★これは手動モードの {@code commit}(確認モーダルの [実行])とまったく同じ形であり、
//   裁定68 の「取り付けは2箇所」がそのまま通常モードでも成り立っている。
//
// ★★★<b>ここから下は manual-battle.js の複製である</b>(裁定289 = a)。
//   共有の JS ファイルにしなかったのは、版数の系統が5つ目になるためである
//   (音声ファイルで4つ目になった。裁定111 が共有 JS を退けたのと同じ理由)。
//   ★★<b>片方だけ直さないこと。</b>番人が verify にあり、
//   「両モードに共通して載っている音は同じ内容である」を測っている。
// ===============================================================

/** ★設定の保存先。★★手動モードと<b>共有する</b>(裁定289)。音量は人の性質である(裁定31) */
const SFX_STORAGE_KEY = 'qte-manual-sound';

/** ★初期音量は控えめである(裁定67)。通話の音声と干渉させない */
const SFX_DEFAULT_VOLUME = 30;

/** ★音声ファイルの置き場所と版数(裁定284)。★JS の版数(?v=)とは別の数字である */
const SFX_BASE = '/sounds/';
const SFX_VERSION = 1;

/**
 * 音の仕様。★ファイルの対応表である(合成の数値表ではない)。
 *
 * ★★<b>9種である。手動モードの11種と同じものではない。</b>
 * この表は「<b>このモードで鳴る音</b>」の一覧であり、鳴らない音を載せると表が嘘をつく。
 * <ul>
 *   <li>手動モードにあって<b>ここに無い</b>もの: {@code flip} / {@code dice} / {@code deal}
 *       —— 通常モードに「めくり」「先後のダイス」「ひと配り」という出来事が無いためである
 *       (先後は {@code chooseOrder} の選択であって振るものではない。
 *       初期ドローは手札が増えるので {@code draw} が語る)</li>
 *   <li>ここにあって<b>手動モードに無い</b>もの: {@code attack}(裁定288)</li>
 * </ul>
 * ★共通する音は<b>ファイル名も gain も手動モードと同じ</b>でなければならない(番人あり)。
 * ★出典・ライセンスは {@code static/sounds/CREDITS.md} にある(裁定285)。
 */
const SFX_SPECS = {
    // ドロー(0.60秒)
    draw: { files: ['card-slide-1.mp3'], gain: 0.55 },
    // 場に出る・場を去る・マナに置く。★毎手鳴るので散らす(裁定286)
    place: {
        files: ['card-place-1.mp3', 'card-place-2.mp3', 'card-place-3.mp3',
            'card-place-4.mp3'],
        gain: 0.80,
    },
    // タップ。★いちばん回数が多いので、いちばん軽く・いちばん多く散らす
    tap: {
        files: ['select_001.mp3', 'select_002.mp3', 'select_007.mp3', 'select_008.mp3'],
        gain: 0.30,
    },
    // ★★LPが減る(0.26秒)。★{@code error_} を当ててはいけない —— LPが減るのは失敗ではない
    lpDown: { files: ['minimize_001.mp3'], gain: 0.60 },
    // LPが増える(0.26秒)。★上の対である
    lpUp: { files: ['maximize_001.mp3'], gain: 0.50 },
    // 決着(1.00秒)。★1試合に1回しか鳴らない
    decisive: { files: ['jingles_PIZZI01.mp3'], gain: 0.55 },
    // ★ターンの受け渡し。★差分に現れないので send() から鳴らす
    commit: { files: ['confirmation_001.mp3'], gain: 0.45 },
    // マリガンの確定。★手動モードと同じ音である
    shuffle: { files: ['card-shuffle.mp3'], gain: 0.50 },
    // ★★Batch 62: 攻撃(裁定288)。★通常モードにしかない音である。
    //   通常モードは<b>攻撃で勝敗が決まるゲーム</b>であり、攻撃が「置く」と同じ音では
    //   盤面でいちばん起きることが語られない。★{@code leader-attack} は別音にしない(裁定72)
    attack: { files: ['impactMetal_medium_000.mp3'], gain: 0.65 },
};

/**
 * 差分の種類 → 音。★手動モードの表と<b>共通部分は同じ</b>でなければならない(番人あり)。
 * ★{@code lp} だけはここに無い。増と減で意味が逆であり、表では決まらないためである。
 */
const SFX_FOR_KIND = {
    draw: 'draw',
    appear: 'place',
    vanish: 'place',
    tap: 'tap',
    declare: 'decisive',
    mulligan: 'shuffle',
};

/**
 * 1配信で1つだけ鳴らすときの優先順位(前ほど強い)。
 * ★★<b>珍しい出来事ほど優先する</b>(裁定71)。理由は「珍しさ」1本である。
 * ★{@code commit} と {@code attack} はここに無い。差分から来ないので競合しない
 * (手動モードの {@code commit} と同じ扱いである)。
 */
const SFX_PRIORITY = ['decisive', 'shuffle', 'lpDown', 'lpUp', 'draw', 'tap', 'place'];

/** 音量スライダーを動かしたときの試聴音 */
const SFX_PREVIEW = 'place';

/** unlock の入口。★特定の操作を名指ししない(裁定73) */
const SFX_UNLOCK_EVENTS = ['pointerdown', 'keydown'];

/** ★1配信で採る差分の上限(裁定8 の通常モード版)。超えたら何も鳴らさない */
const SFX_DIFF_LIMIT = 8;

let sfxCtx = null;
let sfxMaster = null;
/** 取得済みの原本。★取得(fetch)と復号(decode)を分ける理由は manual-battle.js に書いてある */
const sfxRaw = {};
/** 復号済み。★鳴らすときに見るのはこちらだけである */
const sfxBuffers = {};
/** 読み込みに失敗したファイル。★件数を状態行に出すために持つ(裁定283) */
const sfxFailed = [];
/** 直前に選んだ位置。★散らす音で2連続の同一を避けるために持つ */
const sfxLastIndex = {};
let sfxUnsupported = false;
let sfxSettings = loadSfxSettings();

function loadSfxSettings() {
    const fallback = { muted: false, volume: SFX_DEFAULT_VOLUME };
    try {
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

/** 音を出せる状態か。★読み込み失敗はここに<b>含めない</b>(他の音は鳴るため) */
function sfxReady() {
    return !!sfxCtx && !sfxSettings.muted && sfxSettings.volume > 0;
}

/** パネルに出す状態。★上から順に「1つも鳴らない理由」であり、読み込み失敗だけが最後に来る */
function sfxStatusText() {
    if (sfxUnsupported) return 'この環境では音を出せない(ブラウザが対応していない)';
    if (!sfxCtx) return '画面をどこか一度クリックすると音が使えるようになる';
    if (sfxSettings.muted) return 'ミュート中';
    if (sfxSettings.volume === 0) return '音量が 0 になっている';
    if (sfxFailed.length > 0) {
        return `音の一部を読み込めなかった(${sfxFailed.length}件)。その音だけが鳴らない`;
    }
    return '音は有効である';
}

/** 状態行を警告色にするか(裁定76)。★sfxReady と同じではない(読み込み失敗を含む) */
function sfxStatusWarn() {
    return !sfxReady() || sfxFailed.length > 0;
}

/** ★★最初のユーザー操作で音を使えるようにする(裁定73) */
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
        sfxDecodeAll();
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

function sfxUrl(file) {
    return `${SFX_BASE}${file}?v=${SFX_VERSION}`;
}

/** 1ファイルを取得する。★失敗しても投げない。音は対戦を止める理由にならない */
function sfxFetch(name, file) {
    fetch(sfxUrl(file))
        .then((res) => (res.ok ? res.arrayBuffer()
            : Promise.reject(new Error(String(res.status)))))
        .then((raw) => {
            (sfxRaw[name] = sfxRaw[name] || []).push(raw);
            if (sfxCtx) sfxDecode(name);
        })
        .catch(() => {
            sfxFailed.push(file);
            syncSoundPanel();
        });
}

/** ★ページを開いた時点で全部取りに行く(裁定283 の (c))。★鳴らす直前に取りに行かない */
function sfxPreload() {
    for (const name of Object.keys(SFX_SPECS)) {
        for (const file of SFX_SPECS[name].files) sfxFetch(name, file);
    }
}

/** 取得済みの原本を復号する。★AudioContext が要るので unlock 後にしか動かない */
function sfxDecode(name) {
    const raws = sfxRaw[name];
    if (!sfxCtx || !raws || raws.length === 0) return;
    sfxRaw[name] = [];
    for (const raw of raws) {
        try {
            sfxCtx.decodeAudioData(raw)
                .then((buffer) => {
                    (sfxBuffers[name] = sfxBuffers[name] || []).push(buffer);
                })
                .catch(() => {
                    sfxFailed.push(name);
                    syncSoundPanel();
                });
        } catch (e) {
            sfxFailed.push(name);
        }
    }
}

function sfxDecodeAll() {
    for (const name of Object.keys(SFX_SPECS)) sfxDecode(name);
}

/** 鳴らす1本を選ぶ。★2連続で同じものを鳴らさない(裁定286) */
function sfxPickBuffer(name) {
    const list = sfxBuffers[name];
    if (!list || list.length === 0) return null;
    if (list.length === 1) return list[0];
    let i = Math.floor(Math.random() * list.length);
    if (i === sfxLastIndex[name]) i = (i + 1) % list.length;
    sfxLastIndex[name] = i;
    return list[i];
}

/**
 * 音を1つ鳴らす。
 * @return 実際に鳴らしたか(ミュート中・unlock 前・★読み込めていない音は false)
 */
function sfxPlay(name) {
    const spec = SFX_SPECS[name];
    if (!spec || !sfxReady()) return false;
    const buffer = sfxPickBuffer(name);
    // ★★読み込めなかった音は<b>鳴らない</b>(裁定283)。合成へは戻らない
    if (!buffer) return false;
    try {
        if (sfxCtx.state === 'suspended') sfxCtx.resume();
        const src = sfxCtx.createBufferSource();
        src.buffer = buffer;
        const level = sfxCtx.createGain();
        level.gain.value = spec.gain;
        src.connect(level);
        level.connect(sfxMaster);
        src.start();
    } catch (e) {
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

/** ★★1配信で鳴らす1つを選ぶ(裁定70)。優先順位は SFX_PRIORITY の1箇所にある */
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

// ---- ★★差分の採取(裁定287 = A・範囲は採取まで)----

/**
 * 1つの席の差分を採る。
 *
 * ★★<b>手動モードのように全ゾーンのカードを instanceId で追うことはしない。</b>
 * 通常モードのビューは、場のミニオンだけが {@code instanceId} を持ち、
 * 手札・マナ・墓地は<b>枚数と中身の列</b>で来る。追えるものだけを追い、
 * 残りは<b>枚数の増減</b>で採る —— 音が要るのは「何が起きたか」であって
 * 「どのカードがどこへ動いたか」ではない(裁定72: 音の語彙は粗くてよい)。
 *
 * ★<b>相手の席も同じように採る。</b>差分層は誰の手かを区別しない ——
 * 手動モードも差分由来なので相手の手で鳴っている。
 */
function fxDiffSeat(list, before, after) {
    if (!before || !after) return;
    // LP。★増と減で音が変わる唯一のもの
    if (before.lp !== after.lp) list.push({ kind: 'lp', delta: after.lp - before.lp });
    // 手札が増えた = ドロー。★減ったほうは場・墓地の側が語る
    if (after.handCount > before.handCount) list.push({ kind: 'draw' });
    // マナが増えた = 置いた
    if (after.totalMana > before.totalMana) list.push({ kind: 'appear' });
    // 場のミニオン。★instanceId を持つ唯一のゾーンである
    const was = new Map((before.minions || []).map((m) => [m.instanceId, m]));
    const now = new Map((after.minions || []).map((m) => [m.instanceId, m]));
    for (const [id, m] of now) {
        const old = was.get(id);
        if (!old) {
            list.push({ kind: 'appear' });
        } else if (old.tapped !== m.tapped) {
            // ★居場所が変わっていないミニオンだけ状態を見る(手動モード 32b と同じ切り分け)
            list.push({ kind: 'tap' });
        }
    }
    for (const id of was.keys()) {
        if (!now.has(id)) list.push({ kind: 'vanish' });
    }
}

/**
 * 1配信ぶんの差分を採る。
 * ★★<b>これは音のためだけの層ではない。</b>演出を乗せるならここが材料になる(裁定287)。
 */
function fxDiff(prev, next) {
    const list = [];
    if (!prev || !next) return list;
    fxDiffSeat(list, prev.you, next.you);
    fxDiffSeat(list, prev.opponent, next.opponent);
    // ★決着。★status が FINISHED へ変わった配信でだけ出す(再配信で二度は出さない)
    if (prev.status !== 'FINISHED' && next.status === 'FINISHED') {
        list.push({ kind: 'declare' });
    }
    // ★マリガンが終わった
    if (prev.mulligan && !next.mulligan) list.push({ kind: 'mulligan' });
    return list;
}

/**
 * ★★配信1つにつき音を1つ鳴らす(裁定70)。
 * ★差分が上限を超えたら何も鳴らさない(裁定8 の通常モード版) ——
 * 1手で盤面が大きく動いた配信は、音では語れない。
 */
function sfxForDelivery(prev, next) {
    if (!sfxReady()) return;
    const list = fxDiff(prev, next);
    if (list.length === 0 || list.length > SFX_DIFF_LIMIT) return;
    const name = sfxChoose(list);
    if (name) sfxPlay(name);
}

// ---- 設定パネル(★手動モードの複製である。裁定289)----

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
    // ★色が意味を持つのは「鳴らない理由がある」ときだけである(裁定76)
    status.classList.toggle('sound-status-warn', sfxStatusWarn());
}

function openSoundModal() {
    syncSoundPanel();
    document.getElementById('sound-modal').classList.remove('d-none');
}

function closeSoundModal() {
    document.getElementById('sound-modal').classList.add('d-none');
}

document.getElementById('sound-mute').addEventListener('change', (e) => {
    sfxSettings.muted = e.target.checked;
    saveSfxSettings();
    applySfxVolume();
    syncSoundPanel();
    // ★ミュートを解除したときだけ鳴らす。切ったのに音が出るのは矛盾である
    if (!sfxSettings.muted) sfxPlay(SFX_PREVIEW);
});

// ★値の反映は input(動かしている間)、試聴は change(離したとき)である
document.getElementById('sound-volume').addEventListener('input', (e) => {
    sfxSettings.volume = Number(e.target.value);
    applySfxVolume();
    syncSoundPanel();
});
document.getElementById('sound-volume').addEventListener('change', () => {
    saveSfxSettings();
    sfxPlay(SFX_PREVIEW);
});

sfxPreload();

// ---------------------------------------------------------------
// 2) 送信
// ---------------------------------------------------------------

/**
 * サーバへ操作を送る。
 *
 * ★★★Batch 71: 接続していないときは<b>publish しない</b>。
 * 死んだソケットへ投げても例外は出ず、ログにも出ない。人間には
 * 「押したのに何も起きない」としか見えない —— しかも通話しながら遊ぶ前提では、
 * 「声は聞こえているのに盤面だけ届いていない」という<b>気づきにくい事故</b>になる。
 * これは手動モードが 33 で潰したものであり、通常モードには 70 まで残っていた。
 *
 * ★★<b>ここが番人である。</b>オーバーレイは宣言にすぎない(1-2章)。
 * 畳んで盤面を覗いている間も、このガードは効いている。
 *
 * ★★<b>揮発メッセージが無いので quiet の逃がし口を作っていない。</b>
 * 手動モードの {@code dragcue}(1回のドラッグで何十回も飛ぶ矢印)にあたるものは
 * 通常モードに1つも無い —— 送っているのは13箇所すべて<b>盤面を動かす操作</b>である。
 * ★これは「まずは付けないでおく」ではなく<b>今そういう送信が存在しない</b>という事実である。
 * 揮発の送信を足す日が来たら、そのときに逃がし口も足すこと。
 *
 * @return 送ったかどうか。★<b>呼び出し側はこれを見て「畳むかどうか」を決める</b>(4-2)。
 */
function send(action, payload) {
    if (!isConnected()) {
        // ★宣言(オーバーレイか帯)は既に出ている。ここで足すのは
        //   「いま押したそれが弾かれた」だけである(flashDenied の項を参照)
        flashDenied(document.getElementById('auto-conn-bar'));
        flashDenied(document.getElementById('connection-status'));
        return false;
    }
    // ★★Batch 62: 音の取り付け点の2つ目(裁定68・287)。
    //   ここは<b>ユーザーの操作が全部通る1箇所</b>である
    //   ★Batch 71: ガードの<b>後ろ</b>である。送っていない操作に音を鳴らすと、
    //     手応えだけが返って「届いた」と誤解させる(28 の「無言をやめる」の裏返し)
    sfxForAction(action);
    client.publish({
        destination: `/app/room/${ROOM_ID}/${action}`,
        body: JSON.stringify({ playerId: PLAYER_ID, ...payload }),
    });
    return true;
}

/**
 * 操作の手応え(★Batch 62)。
 *
 * ★★<b>差分に現れない操作だけを鳴らす。</b>
 * カードのプレイ・召喚・マナチャージは、配信の差分に {@code appear} として現れるので
 * ここでは鳴らさない —— 鳴らすと<b>1つの操作で2音</b>になり、
 * 裁定70 が守っているもの(1つの出来事に1つの音)が崩れる。
 *
 * ★{@code attack} が差分に現れないのは、view に載るのが
 * HPの減少・消滅という<b>結果</b>だけだからである。「攻撃した」はどこにも書かれていない。
 * ★{@code end-turn} も同じである。フェイズが変わるだけで、盤面には何も起きない。
 */
function sfxForAction(action) {
    if (action === 'attack' || action === 'leader-attack') {
        sfxPlay('attack');
    } else if (action === 'end-turn') {
        sfxPlay('commit');
    }
}

function chooseOrder(goFirst) { send('choose-order', { goFirst }); }
function nextPhase() { send('next-phase', {}); }
function endTurn() { send('end-turn', {}); }

// ---------------------------------------------------------------
// ★★★2-2) 確認モーダル(Batch 72)
// ---------------------------------------------------------------
//
// ★★<b>裁定53 を通常モードでも守る。</b>36 が素の confirm() を捨てた理由は3つあった ——
//   (1) OS の見た目が黒い盤面の上に出る (2) ボタンの文言を決められない
//   (3) ★★★<b>JavaScript を止める</b>。止まっている間は STOMP の受信も描画も進まない。
//   通話しながら遊ぶ前提(裁定16)では (3) が効く。
//
// ★★<b>通常モードには素の confirm() が7箇所ある</b>(【賢魂】・特殊召喚・強化の宣言)。
//   72 はそれを直していない —— あれは「どちらの姿で使うか」という<b>宣言</b>であって、
//   ここで作っているもの(取り返しのつかない操作の確認)とは層が違う。
//   ★★ただし理由(3)は宣言にも効く。<b>片肺として書き残す</b>(71 の教訓)。
//
// ★★<b>CSS は共有した。</b>z-index 1965 の規則は battle.css に 36 から在り、
//   セレクタに .auto-confirm を並べただけである(71 の 4-2 と同じ)。
//   ★1965 は<b>切断オーバーレイ(1970)より下</b>である ——
//     切断中は「操作が相手に届かない」のほうが上位の情報であり、
//     その上に確認を重ねると「実行しても何も起きない問い」を最前面に出すことになる(裁定56)。
//
// ★<b>音は鳴らさない。</b>手動モードは確認の [実行] を音の取り付け点にしているが(37 の 0-5)、
//   通常モードの取り付け点は send() と onMessage の2つだけである(裁定68・287)。
//   ★3つ目を増やすと、62 が「配信と再描画を区別する」ために引いた線が1本増える。

let autoConfirmPending = null;

/**
 * 取り返しのつかない操作の確認(★Batch 72)。
 *
 * ★<b>コールバックで受ける</b>(裁定54)。使う分岐は「実行する」側しかないので、
 * 真偽値や Promise を返しても呼び出し側に if が1つ増えるだけである。
 * ★<b>ボタンには動詞を書く</b>(裁定55)。「はい」ではなく「投了する」。
 * ★<b>問いは1つずつ</b> —— 開いている間の askConfirm は捨てる。
 */
function askConfirm(text, okLabel, onOk) {
    if (autoConfirmPending) return false;
    autoConfirmPending = onOk;
    document.getElementById('auto-confirm-text').textContent = text;
    document.getElementById('auto-confirm-ok').textContent = okLabel;
    document.getElementById('auto-confirm').classList.remove('d-none');
    // ★初期フォーカスは [キャンセル] である(裁定52)。
    //   <b>破壊的操作の [実行] に初期フォーカスを載せてはいけない。</b>
    document.getElementById('auto-confirm-close').focus();
    return true;
}

function closeAutoConfirm() {
    autoConfirmPending = null;
    document.getElementById('auto-confirm').classList.add('d-none');
}

function isAutoConfirmOpen() {
    return !document.getElementById('auto-confirm').classList.contains('d-none');
}

document.getElementById('auto-confirm-close').addEventListener('click', closeAutoConfirm);
document.getElementById('auto-confirm-ok').addEventListener('click', () => {
    const run = autoConfirmPending;
    // ★先に閉じる。実行が location.href への遷移でも、後片付けは済んでいる
    closeAutoConfirm();
    if (run) run();
});

// ---------------------------------------------------------------
// ★★★2-3) 試合の出入り(Batch 72): 席・退室・投了・再戦
// ---------------------------------------------------------------
//
// ★★<b>どれも send() のガードの後ろに居る</b>(71)。切断中に押しても何も飛ばない。
//   ★<b>ローカルに畳むものが1つも無い</b>ので、設計判断49 の「戻す」処理は要らない ——
//     確認モーダルは閉じるが、ボタンはそこに在り、押し直せる。
//     ★「保つ」と「止まらせない」でいえば、こちらは初めから何も抱えていない。
//   ★例外は席替えのゲートだけで、あちらは開き直す(0章)。
//
// ★★<b>押せるかどうかの判定はビューが決める</b>(renderRoomControls)。
//   ★<b>それは操作補助であって、守りではない</b>(設計判断27) ——
//     断るのはサーバ(GameRoom / GameService)であり、画面はボタンを隠すだけである。

/** 席を立って観戦へ降りる(★Batch 72)。★観戦できない部屋ではボタンが出ない */
function standUpFromSeat() {
    askConfirm('席を立って観戦に移る。席は空き、以後は盤面を操作できない。',
        '席を立つ', () => send('seat', { seat: null }));
}

/** 席の操作(★Batch 72)。★<b>文言は自席の有無だけで決まる</b>(手動モードと同じ形) */
function toggleSeat() {
    if (!latestView || !latestView.room) return;
    if (latestView.room.viewerSeat) {
        standUpFromSeat();
        return;
    }
    openSeatChangeGate();
}

/**
 * 投了(★Batch 72)。★<b>いつでも押せる</b> ——
 * 相手のターン中でも、割り込み待ちでも、マリガン中でも通る。
 * 詰まったときの逃げ道は、詰まりの原因になっている規則に左右されてはいけない。
 */
function concede() {
    askConfirm('投了する。この対戦は相手の勝ちになり、やり直せない。',
        '投了する', () => send('concede', {}));
}

/**
 * 退室(★Batch 72)。
 *
 * ★★<b>ここでは localStorage を消さないし、遷移もしない。</b>
 * サーバが受理したことを {@code onMessage} の {@code LEFT} で受けてから行う ——
 * 通常モードの退室は<b>断られうる</b>(対戦中の着席者)ので、
 * 手動モードの「送って即遷移」を写すと、断られた端末が席を持ったまま戻れなくなる。
 */
function leaveRoom() {
    askConfirm('この部屋から退室する。席は空き、盤面はこの端末から見えなくなる。',
        '退室する', () => send('leave', {}));
}

// ★再戦の3手。★<b>確認を挟むのは [応じる] だけである</b> ——
//   申し込みは旗を立てるだけ、辞退は旗を倒すだけで、どちらも取り返しがつく。
//   応じると<b>相手の見ている盤面まで消える</b>ので、そこだけ確認する。
function offerRematch() { send('rematch', { action: 'OFFER' }); }
function declineRematch() { send('rematch', { action: 'DECLINE' }); }

function acceptRematch() {
    askConfirm('再戦に応じる。この対戦の盤面とログは消え、両者がデッキを読み込み直す。',
        '再戦する', () => send('rematch', { action: 'ACCEPT' }));
}

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
    // ★Batch 52: 進化素材の選択中は場だけを触らせる(手札は次の段で使う)
    if (evolution) {
        showMessage('先に進化素材を選んでください(やめるならキャンセル)');
        return;
    }
    // 対象選択中の手札クリックは「対象として選ぶ」操作になる
    if (pending) {
        pickHandCard(index);
        return;
    }
    if (latestView.phase === 'MANA_CHARGE') {
        // ★★★Batch 70(裁定323): マナチャージも確認を挟む。
        //   66 まではここが「即座に charge-mana を送る」1行だった ——
        //   マナチャージは<b>1ターン1回で手札へ戻らない</b>ので、取り返しがつかない。
        //   ★ドラッグには確認を挟まない(裁定321・323)。あちらは落とす行為が確認である
        beginManaPayment({ kind: 'CHARGE', cost: 0, card: latestView.you.hand[index],
            handIndex: index });
        return;
    }
    if (latestView.phase !== 'MAIN' && latestView.phase !== 'SUB') {
        showMessage('カードを使えるのはマナチャージ/メイン/サブフェイズです');
        return;
    }
    const card = latestView.you.hand[index];

    // ★Batch 54:【賢魂：n】を持つカードは2つの姿を持つ(裁定152)。
    // どちらで使うかは<b>プレイヤーの宣言</b>なので、まずそれを尋ねる ——
    // 進化素材の選択より前でなければならない(賢魂として使うなら素材は要らない)。
    // ★サーバが soulCost を送ってきたときだけ導線を出す。
    //   テキストを解析して自分で判断しない(規則はクライアントに置かない。裁定234)
    // ★★Batch 70: これは<b>確認ではなく「どちらの姿で使うか」の宣言</b>である。
    //   裁定321 が挟むなと言っているのは前者であり、後者は入口を問わず要る
    //   (ドラッグでは落とし先が宣言になる。裁定318)
    if (card.soulCost != null) {
        if (confirm(soulPrompt(card))) {
            beginPlayFromHand(index, card, 'play-soul', card.soulTargets, {}, false);
            return;
        }
    }

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
    const extra = enhanced ? { enhanced: true } : { enhanced: false };
    beginPlayFromHand(index, card, action, specs, extra, card.type === 'EVOLUTION');
}

/**
 * ★★★Batch 70(裁定319): クリックからのプレイは、必ずマナの選択と確定を通る。
 *
 * ★<b>置く場所を禁忌と同じにした。</b>禁忌は 43 から
 * 「クリック → マナを選ぶ → 対象を選ぶ → 送る」であり、
 * 指摘5 の「クリック後に支払うマナを手動で選んで確定ボタンを押したらプレイされる」も同じ並びである。
 * ★2つの入口(クリック / ドラッグ)で<b>払い方だけが違い、あとの道は同じ</b>になる。
 */
function beginPlayFromHand(handIndex, card, action, specs, extra, evolutionFlow) {
    beginManaPayment({
        kind: 'PLAY', cost: playCostOf(card, action, extra), card,
        handIndex, action, specs, extra, evolutionFlow,
    });
}

/**
 * このプレイで払うマナの枚数。
 * ★<b>コストの計算はしていない</b> —— 実効コストも賢魂のコストも特殊召喚の MP も、
 *   サーバが計算してビューに載せた値をそのまま読むだけである(裁定234)。
 */
function playCostOf(card, action, extra) {
    if (action === 'play-soul') {
        return card.soulEffectiveCost != null ? card.soulEffectiveCost : card.soulCost;
    }
    if (action === 'special-summon') {
        return card.specialSummonMpCost || 0;
    }
    const base = card.effectiveCost != null ? card.effectiveCost : card.cost;
    return base + ((extra && extra.enhanced) ? card.enhancedCost : 0);
}

/**
 * ★Batch 54:【賢魂】の確認ダイアログの文言。
 * 実効コストが n と違うとき(コスト軽減・増加)は両方を出す ——
 * 押す前に何マナ払うかが分かるようにするためである。
 */
function soulPrompt(card) {
    const eff = card.soulEffectiveCost != null ? card.soulEffectiveCost : card.soulCost;
    const cost = eff === card.soulCost ? `コスト${card.soulCost}`
        : `コスト${card.soulCost} → 実効${eff}`;
    return `【賢魂：${card.soulCost}】${card.soulText || ''}\n\n`
        + `OK = スペルとして使う(${cost}) / キャンセル = ミニオンとして出す`;
}

/**
 * ★Batch 52: 進化素材の選択を始める。
 * 候補が足りなければ始めずに理由を出す —— サーバも同じ理由で弾く(マスター裁定 D3)。
 */
function beginEvolutionSelection(action, handIndex, specs, extra, card) {
    const candidates = card.evolutionMaterialIds || [];
    if (candidates.length < card.evolutionMin) {
        showMessage('進化素材が足りません(必要: ' + card.evolutionText + ')');
        return;
    }
    evolution = { action, handIndex, specs, extra, card, picked: [] };
    render(latestView);
}

/** 素材を1体選ぶ/外す。上限に達したら自動で次の段へ進む */
function pickEvolutionMaterial(instanceId) {
    if (!(evolution.card.evolutionMaterialIds || []).includes(instanceId)) {
        showMessage('このミニオンは素材にできません(条件: ' + evolution.card.evolutionText + ')');
        return;
    }
    const at = evolution.picked.indexOf(instanceId);
    if (at >= 0) {
        evolution.picked.splice(at, 1);
        render(latestView);
        return;
    }
    evolution.picked.push(instanceId);
    if (evolution.picked.length >= evolution.card.evolutionMax) {
        confirmEvolutionSelection();
        return;
    }
    render(latestView);
}

/** 今選んでいる素材で確定し、対象選択(あれば)へ進む */
function confirmEvolutionSelection() {
    if (!evolution || evolution.picked.length < evolution.card.evolutionMin) return;
    const { action, handIndex, specs, extra, picked } = evolution;
    // ★★Batch 71: 先に畳んでから進み、<b>送れなかったら元へ戻す</b>(4-2)。
    //   ★抱えたまま進めないのは confirmManaPayment と同じ理由である ——
    //     beginSelection は途中で render() を呼ぶので、案内が素材選びに戻ってしまう。
    const held = evolution;
    evolution = null;
    if (!beginSelection(action, handIndex, specs,
        Object.assign({}, extra, { materialIds: picked }))) {
        evolution = held;
        render(latestView);
    }
}

/**
 * 対象選択を開始する。要求がなければ即送信。
 *
 * ★★Batch 71: <b>受け付けたかどうかを返す。</b>
 * 要求が無い形は「開始 = 送信」なので、切断中は<b>始まってすらいない</b> ——
 * 呼び出し側がそれを知らないと、確定待ちや進化素材の選択を
 * <b>何も起きていないのに畳んでしまう</b>(4-2)。
 * ★要求がある形は必ず true である(送信はまだ先の commitRequirement で起きる)。
 */
function beginSelection(action, handIndex, specs, extra) {
    if (!specs || specs.length === 0) {
        return send(action, buildActionPayload(handIndex, [], extra));
    }
    pending = {
        action, handIndex, specs, extra,
        collected: [],
        current: { handIndexes: [], minionIds: [], manaIndexes: [], trashIndexes: [], weaponSides: [] },
    };
    render(latestView);
    maybeOpenTrashPicker();
    return true;
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
 * 絞り込みのための文明の取り出し(★Batch 67)。
 *
 * ★<b>MinionView は文明を運ばない。</b>手札のカードビュー(CardView)は運ぶが、
 * 場のミニオンは運ばない —— これは 45 からの意図した設計であり、
 * 足りない属性は card-library(autoLibrary)から引くと決まっている(設計判断28)。
 * 《ツイン・ストライク》で「場のミニオンの文明」を初めて問うことになったが、
 * <b>ビューを太らせずに済んだのは、引く口が既に在ったからである</b>。
 *
 * ★autoLibrary はこのファイルの後方で宣言された const だが、この関数が呼ばれるのは
 * 対象選択中(=スクリプトの評価がとうに終わった後)なので一時的死角には入らない。
 */
function civilizationOfTarget(card, minion) {
    if (card) {
        return card.civilization;
    }
    if (!minion) {
        return null;
    }
    const lib = autoLibrary.get(minion.cardId);
    return lib ? lib.civilization : null;
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
            // ★★★Batch 74(裁定341): 進化ミニオンもミニオンである(裁定310)。
            // 73 まで、ここだけが 'MINION' の一致で書かれており、
            // すぐ下の NON_MINION_CARD(★Batch 67)とは判定が食い違っていた
            case 'MINION_CARD':
                ok = !!card && (card.type === 'MINION' || card.type === 'EVOLUTION'); break;
            case 'HP_5_OR_LESS':
                ok = minion ? minion.currentHp <= 5 : (card.hp != null && card.hp <= 5); break;
            case 'COST_4_OR_LESS':
                ok = (minion || card).cost != null && (minion || card).cost <= 4; break;
            case 'COST_3_OR_LESS':
                ok = (minion || card).cost != null && (minion || card).cost <= 3; break;
            case 'SPELL_CARD':
                ok = card && card.type === 'SPELL'; break;
            // ★Batch 67: 文明の3値は civilizationOfTarget に揃えた。
            // 66 までは card しか見ておらず、場のミニオンでは必ず false になっていた
            // (神の福音・ギガマウス・バイトは手札からしか選ばないので症状は出ていない)。
            case 'LIGHT_CIVILIZATION':
                ok = civilizationOfTarget(card, minion) === 'LIGHT'; break;
            case 'WATER_CIVILIZATION':
                ok = civilizationOfTarget(card, minion) === 'WATER'; break;
            case 'WIND_CIVILIZATION':
                ok = civilizationOfTarget(card, minion) === 'WIND'; break; // ★Batch 67(ツイン・ストライク)
            case 'NON_MINION_CARD':
                // ★Batch 67(禁忌の墓地利用)。進化ミニオンもミニオンである(裁定 2-1)
                ok = !!card && card.type !== 'MINION' && card.type !== 'EVOLUTION'; break;
            case 'COST_7_OR_LESS':
                ok = (minion || card).cost != null && (minion || card).cost <= 7; break;
            case 'HIGHEST_ATTACK_OPPONENT': {
                const maxAtk = Math.max(0, ...latestView.opponent.minions.map(m => m.attack));
                ok = !!minion && minion.attack === maxAtk;
                break;
            }
            case 'IGNORES_STEALTH':
                ok = true; break; // 絞り込みではなく潜伏チェックの上書き指示。pickMinion側で見る
            case 'EVOLUTION_MINION':
                ok = !!minion && minion.evolution; break; // ★Batch 52(機神兵長茶爺)
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
        // ★★★Batch 71: 送れたときだけ畳む(4-2)。
        //   ★<b>送れなかったら最後の要求を巻き戻す。</b>「選び終えた状態」のまま残すと、
        //     もう一度撃つ入口がどこにも無い(要求は全部埋まっており、
        //     再送のきっかけになる操作が存在しない)—— 死に止まりを作らない。
        //   ★プレイそのものは畳まない。巻き戻るのは<b>最後の1要求ぶん</b>だけであり、
        //     払うマナ・進化素材・ここまでに選んだ対象は残る。
        if (!send(action, buildActionPayload(handIndex, collected, extra))) {
            pending.collected.pop();
            render(latestView);
            maybeOpenTrashPicker();
            return;
        }
        pending = null;
        hideModal();
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
    if (evolution) {
        confirmEvolutionSelection();
        return;
    }
    const req = currentRequirement();
    if (!req || !req.upTo) return;
    commitRequirement();
}

function cancelSelection() {
    pending = null;
    manaPay = null;   // ★Batch 70: 確定待ち(プレイ・禁忌・マナチャージ)もここで降りる
    evolution = null;
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
    // ★Batch 60: 墓地から出すカード自身は、そのカードの対象には選べない。
    // 出どころ(trashIndex)は extra に載っているので、それと同じ位置なら断る。
    // サーバの GameService.requireTrashSourceNotTargeted が同じ判定をやり直す
    if (pending.extra && pending.extra.trashIndex === index) {
        showMessage('墓地から出すカード自身は対象に選べません');
        return;
    }
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
        // ★★★Batch 74(裁定341): 進化ミニオンもミニオンである(裁定310)。
        // 73 まではここで進化を落としており、《黄泉の召喚主》で進化を召喚できなかった
        if (mode === 'summon' && card.type !== 'MINION' && card.type !== 'EVOLUTION') return;
        const picked = pending && pending.current.trashIndexes.includes(index);
        const cost = card.effectiveCost != null ? card.effectiveCost : card.cost;
        const kind = card.type === 'SPELL' ? 'スペル'
            : card.type === 'WEAPON' ? 'ウェポン'
            : card.type === 'EVOLUTION' ? '進化ミニオン' : 'ミニオン';
        const label = `${card.name} (${kind}${cost != null ? ' コスト' + cost : ''})`;
        rows.push({ index, label, picked, card });
    });
    showModalRows(title, rows, mode);
}

/**
 * 墓地からの召喚(《黄泉の召喚主》)を始める。★Batch 60(裁定278(c))。
 *
 * 59 までは trashIndex を送るだけだったが、【召喚時】が対象を要求するミニオンも
 * ここから出せるようになった。手札から召喚するときと段取りはまったく同じである ——
 * 対象要求(card.targets)はサーバが墓地の面にも添えてくれているので、
 * この画面は beginSelection にそのまま渡すだけでよい(要求が無ければ即送信される)。
 */
function beginGraveSummon(trashIndex, card) {
    hideModal();
    // ★★★Batch 74(裁定341): 墓地から進化ミニオンを召喚できるようになった。
    //   <b>これは「召喚」なので、素材は宣言のときに選ぶ</b> ——
    //   手札からの進化召喚・墓地からの【特殊召喚】とまったく同じ段取りである。
    //   (効果による「出す」だけが、割り込みで素材を問う形になっている)
    if (card.type === 'EVOLUTION') {
        beginEvolutionSelection('summon-from-grave', null, card.targets, { trashIndex }, card);
        return;
    }
    beginSelection('summon-from-grave', null, card.targets, { trashIndex });
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
                    beginGraveSummon(row.index, row.card);
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
 * 支払い中(manaPay の TABOO)は render() が強制的に開く —— マナを選ぶ間、
 * どの禁忌カードを使おうとしているかが見えていないと操作にならない。
 */
let tabooOpen = false;
function toggleTabooRow() {
    tabooOpen = !tabooOpen;
    syncTabooRow();
}
function syncTabooRow() {
    if (manaPay && manaPay.kind === 'TABOO') tabooOpen = true;
    document.getElementById('taboo-strip').classList.toggle('d-none', !tabooOpen);
    document.getElementById('btn-taboo-toggle')
        .classList.toggle('auto-chip-active', tabooOpen);
}

function onTabooCardClick(index) {
    if (hasPendingChoice()) return;
    if (pending || manaPay || !latestView || !latestView.myTurn) return;
    if (latestView.phase !== 'MAIN') {
        showMessage('禁忌カードはメインフェイズにのみ使用できます');
        return;
    }
    const card = latestView.you.taboo[index];
    // ★Batch 54: 禁忌デッキからも【賢魂】として使える(マスター裁定 A6)。
    // ★<b>退けるマナは n 枚</b>である —— 賢魂として使うならコストは n だからである。
    //   禁忌の支払いはコスト軽減を受けない(マナ枚数で払う)ので、印刷値の n をそのまま使う
    let action = 'play-taboo';
    let cost = card.cost;
    let specs = card.targets;
    if (card.soulCost != null && confirm(soulPrompt(card))) {
        action = 'play-taboo-soul';
        cost = card.soulCost;
        specs = card.soulTargets;
    }
    // 支払いに使えるマナ(ピュア・エレメントの一時マナは禁忌コストに使えない)
    const payable = latestView.you.manaZone.filter(m => !m.temporary).length;
    if (payable < cost) {
        showMessage(`禁忌コストの支払いに使えるマナが足りません(必要${cost}枚)`);
        return;
    }
    // ★★Batch 70(裁定319): コスト0でも自動で確定しない。
    //   「選ぶ余地が無いから自動でよい」は<b>効果の解決中の選択</b>についての流儀であって、
    //   プレイそのものを始めてよいかという層には効かない(69 の見立てはここで外れた)
    beginManaPayment({ kind: 'TABOO', cost, card, tabooIndex: index, action, specs });
}

/**
 * ★★★Batch 70: 確定待ちに入る唯一の口(裁定319・321・323)。
 * ★<b>コストが0でも、候補が1通りしか無くても、ここで止まる。</b>
 */
function beginManaPayment(spec) {
    manaPay = Object.assign({ picked: [], warn: null, cost: 0 }, spec);
    render(latestView);
}

/** このマナを今の支払いに充てられるか。★種類ごとの違いはこの1箇所だけである */
function payCandidate(mana) {
    if (!manaPay) return false;
    if (manaPay.kind === 'TABOO') return !mana.temporary;   // 一時マナは禁忌に使えない
    if (manaPay.kind === 'PLAY') return !mana.tapped;       // 通常の支払いはタップである
    return false;                                           // CHARGE は選ぶマナが無い
}

function pickPayMana(index) {
    if (!manaPay || manaPay.cost === 0) return;
    const mana = latestView.you.manaZone[index];
    if (!payCandidate(mana)) {
        showMessage(manaPay.kind === 'TABOO'
            ? '【ピュア・エレメント】は禁忌のコストにできません'
            : 'タップ済みのマナは支払いに使えません');
        return;
    }
    const at = manaPay.picked.indexOf(index);
    if (at >= 0) {
        manaPay.picked.splice(at, 1);   // ★もう一度押せば外れる(選び直せない確定は作らない)
    } else {
        if (manaPay.picked.length >= manaPay.cost) {
            showMessage(`払うマナは${manaPay.cost}枚です(外してから選び直してください)`);
            return;
        }
        manaPay.picked.push(index);
    }
    render(latestView);
}

/**
 * ★★★Batch 70: 確定(裁定319・323)。
 * ★<b>ここが「プレイを始めてよい」の唯一の門である。</b>
 *   通ったあとは 69 までと同じ道(進化素材 → 対象選択 → 送信)に合流する。
 */
function confirmManaPayment() {
    if (!manaPay) return;
    if (manaPay.picked.length !== manaPay.cost) {
        showMessage(`払うマナを${manaPay.cost}枚選んでください(${manaPay.picked.length}/${manaPay.cost})`);
        return;
    }
    // ★★★Batch 71: <b>受け付けられたときだけ確定待ちを畳む。</b>
    //   69 まではここが「畳んでから送る」だったので、切断中に[確定]を押すと
    //   何も起きないうえに<b>選んだマナまで消えていた</b> ——
    //   70 が入口を2つにして確定待ちを作ったぶん、切断の実害がここに集まっている。
    //   ★保っておけば、再接続したあとにもう一度[確定]を押すだけで出せる。
    const pay = manaPay;
    // ★★<b>先に畳んでから進む。</b>次の段(beginSelection)は途中で render() を呼ぶので、
    //   確定待ちを抱えたまま進むと<b>案内文が確定待ちのものに戻ってしまう</b>
    //   (「対象を選んでください」ではなく「払うマナを選んでください」が出る)。
    //   ★★送れなかったときは<b>元どおり戻す</b> —— これが 4-2 の「保つ」である。
    manaPay = null;
    if (pay.kind === 'CHARGE') {
        if (!send('charge-mana', { handIndex: pay.handIndex })) return restoreManaPayment(pay);
        return;
    }
    if (pay.kind === 'TABOO') {
        if (!beginSelection(pay.action || 'play-taboo', null, pay.specs,
            { tabooIndex: pay.tabooIndex, manaIndexes: pay.picked })) return restoreManaPayment(pay);
        return;
    }
    const extra = Object.assign({}, pay.extra || {}, { manaIndexes: pay.picked });
    if (pay.evolutionFlow) {
        // ★進化素材の選択は<b>送信を伴わない</b>ので、ここは必ず先へ進む
        //   (送るのは素材を選び終えたあとの confirmEvolutionSelection である)
        beginEvolutionSelection(pay.action, pay.handIndex, pay.specs, extra, pay.card);
        return;
    }
    if (!beginSelection(pay.action, pay.handIndex, pay.specs, extra)) return restoreManaPayment(pay);
}

/**
 * ★Batch 71: 送れなかった確定待ちを元へ戻す(4-2)。
 * ★<b>戻し方を1箇所に置く。</b>confirmManaPayment には出口が4つあり、
 * 戻す処理を出口ごとに書くと、次に出口が増えたときに片方だけ古いままになる(裁定130)。
 */
function restoreManaPayment(pay) {
    manaPay = pay;
    render(latestView);
}

function cancelManaPayment() {
    manaPay = null;
    render(latestView);
}

// ---------------------------------------------------------------
// ★★★Batch 70: 手札・禁忌からのドラッグ&ドロップ(指摘3〜5・裁定318〜323)
//
// ★★<b>入口は2つになった。</b>クリック(確認つき・裁定319)とドラッグ(確認なし・裁定321)で、
//   <b>違うのは払い方だけ</b>である —— 落としたあとの道(進化素材 → 対象選択 → 送信)は同じ。
//
// ★★★<b>ドラッグ中に render() を呼ばない。</b>render() は #my-hand を作り直すので、
//   掴んでいる要素そのものが DOM から消え、ブラウザがドラッグを中断する
//   (手動モードの 19b hotfix2 が「ドラッグ中に祖先の pointer-events を変えると
//    dragstart の直後に dragend が来る」で踏んだのと同じ性質の事故である)。
//   だから見た目の更新は<b>クラスの付け外しだけ</b>で行う。
//
// ★★★<b>落とした場所は e.target では決まらない</b>(20a の A4)。
//   dragstart の e.target は「指を置いた要素」ではなく、ブラウザが祖先方向へ探して
//   見つけた draggable=true の要素である。進化の素材のように
//   「どのカードの上に落ちたか」が要る場面は document.elementFromPoint で取る。
// ---------------------------------------------------------------

/** 掴んでいるもの。{ from: 'HAND'|'TABOO', index, card, zones: [...], hoverZone } */
let dragging = null;

/** ドロップ先の器。★id と役割の対応はここ1箇所だけが持つ(裁定130) */
const DROP_ZONES = [
    { zone: 'FIELD', id: 'my-minions' },     // ミニオン・進化(裁定318)
    { zone: 'SPELL', id: 'spell-drop' },     // スペル・【賢魂】(裁定318・320)
    { zone: 'LEADER', id: 'my-leader' },     // ウェポン(裁定318)
    { zone: 'MANA', id: 'my-mana-row' },     // マナチャージ(裁定323)
];

/** 裁定318: 落とし先は<b>種別で決まる</b>。種別に合わない落とし先は存在しないのと同じである */
function dropZoneOfType(type) {
    if (type === 'WEAPON') return 'LEADER';
    if (type === 'SPELL') return 'SPELL';
    return 'FIELD';   // MINION / EVOLUTION
}

/**
 * 手札のカードが今どう使えるか。
 * ★<b>手札の光り(createHandCardEl)とドロップ先の判定が同じここを読む</b> ——
 *   2箇所に書くと「光っているのに落とせない」が黙って生まれる(裁定130)。
 */
function handCardPlayability(card, view) {
    const cost = card.effectiveCost != null ? card.effectiveCost : card.cost;
    const affordable = cost <= view.you.availableMp;
    // ★Batch 54: 賢魂として使えるなら、ミニオンとして払えなくても使える(裁定152)
    const soulAffordable = card.soulCost != null
        && (card.soulEffectiveCost != null ? card.soulEffectiveCost : card.soulCost)
            <= view.you.availableMp;
    const charge = !!view.myTurn && view.phase === 'MANA_CHARGE';
    const main = !!view.myTurn && view.phase === 'MAIN' && card.type !== 'LEADER'
        && (affordable || card.canSpecialSummon || soulAffordable);
    const sub = !!view.myTurn && view.phase === 'SUB'
        && (card.type === 'SPELL' ? affordable : soulAffordable);
    return { affordable, soulAffordable, charge, main, sub, playable: charge || main || sub };
}

/** マナチャージができる状態か。★判定は 43 から在るこの1本を呼ぶ(裁定130) */
function canChargeMana(view) {
    return !!view.myTurn && view.phase === 'MANA_CHARGE'
        && !view.you.manaCharged && view.you.totalMana < 15;
}

/**
 * このカードを落とせる場所の一覧(裁定318・320・322・323)。
 * ★空なら掴ませない —— 落とせない場所しか無いのに掴めると、離すたびに何も起きない。
 */
function dropZonesFor(card, from, view) {
    if (!view || !view.you || !view.myTurn || view.mulligan) return [];
    if (pending || evolution || manaPay || hasPendingChoice()) return [];
    if (view.phase === 'MANA_CHARGE') {
        // ★裁定323 + 総合ルール「手札から1枚」。禁忌デッキは手札ではない
        return (from === 'HAND' && canChargeMana(view)) ? ['MANA'] : [];
    }
    const zones = [];
    if (from === 'TABOO') {
        if (view.phase !== 'MAIN') return [];   // 3-3: 禁忌はメインフェイズのみ
        const payable = view.you.manaZone.filter(m => !m.temporary).length;
        if (card.soulCost != null && card.soulCost <= payable) zones.push('SPELL');
        if (card.cost <= payable) zones.push(dropZoneOfType(card.type));
        return [...new Set(zones)];
    }
    const p = handCardPlayability(card, view);
    if (p.soulAffordable && (view.phase === 'MAIN' || view.phase === 'SUB')) zones.push('SPELL');
    if (view.phase === 'MAIN' && card.type !== 'LEADER'
            && (p.affordable || card.canSpecialSummon)) {
        zones.push(dropZoneOfType(card.type));
    }
    if (view.phase === 'SUB' && card.type === 'SPELL' && p.affordable) zones.push('SPELL');
    return [...new Set(zones)];
}

/** 今つかんでいるカードを、今指している落とし先で使ったときのコスト */
function draggingCost() {
    if (!dragging) return 0;
    const card = dragging.card;
    const asSoul = dragging.hoverZone === 'SPELL' && card.soulCost != null && card.type !== 'SPELL';
    if (dragging.from === 'TABOO') {
        // 禁忌はコスト軽減を受けない(マナの枚数で払う)ので印刷値を使う
        return asSoul ? card.soulCost : card.cost;
    }
    if (asSoul) {
        return card.soulEffectiveCost != null ? card.soulEffectiveCost : card.soulCost;
    }
    return card.effectiveCost != null ? card.effectiveCost : card.cost;
}

/**
 * これから自動で払われるマナの位置(裁定315〜317)。
 * ★★<b>順序はサーバから来たものをそのまま使う。</b>クライアントは
 *   「先頭から n 件」しか知らない —— 払い方の規則を書き写すと、
 *   サーバの払い方が変わった日に<b>強調表示だけが黙って嘘になる</b>(67 の教訓・写し)。
 */
function plannedManaIndexes() {
    if (!dragging || !latestView || !latestView.you) return [];
    // ★マナチャージにコストは無い(裁定323)。
    //   ★<b>指がまだどこにも乗っていないときも見る</b> —— マナチャージフェイズは
    //     落とし先がマナゾーンしか無いので、掴んだ瞬間から光らせてはいけない
    if (dragging.hoverZone === 'MANA'
            || dragging.zones.every(z => z === 'MANA')) {
        return [];
    }
    const you = latestView.you;
    const order = dragging.from === 'TABOO' ? (you.tabooPayOrder || []) : (you.manaPayOrder || []);
    return order.slice(0, draggingCost());
}

/**
 * 裁定317 の警告。禁忌の支払いで<b>裏向きのマナが墓地送りになる</b>なら真。
 * ★順序はサーバから来た tabooPayOrder であり、表裏はビューの公開情報である ——
 *   「どれを払うか」も「裏向きなら墓地へ行くか」もこちらでは決めていない。
 */
function tabooPayBurns(cost) {
    const you = latestView && latestView.you;
    if (!you) return false;
    return (you.tabooPayOrder || []).slice(0, cost)
        .some(i => you.manaZone[i] && !you.manaZone[i].faceUp);
}

/** ドラッグを始められるなら draggable にして、掴んだときの手当てを付ける */
function attachDrag(el, from, index, card) {
    const zones = dropZonesFor(card, from, latestView);
    if (zones.length === 0) {
        el.draggable = false;
        return;
    }
    el.draggable = true;
    el.addEventListener('dragstart', (e) => {
        dragging = { from, index, card, zones, hoverZone: null };
        // ★中身は使わない(同じページの中の話である)が、空だとドラッグが成立しないブラウザがある
        e.dataTransfer.setData('text/plain', from + ':' + index);
        e.dataTransfer.effectAllowed = 'move';
        el.classList.add('auto-dragging');
        markDropZones();
        refreshPlannedMana();
    });
    el.addEventListener('dragend', () => endDrag());
}

/** 落とせる場所に印を付ける。★render() を通さない(ドラッグ中に DOM を作り直さない) */
function markDropZones() {
    DROP_ZONES.forEach(({ zone, id }) => {
        const el = document.getElementById(id);
        if (!el) return;
        el.classList.toggle('auto-drop-ready', !!dragging && dragging.zones.includes(zone));
        if (!dragging) el.classList.remove('auto-drop-over');
    });
}

/** 「これから払われるマナ」の印を付け直す。★同じ理由で render() を通さない */
function refreshPlannedMana() {
    const planned = plannedManaIndexes();
    document.querySelectorAll('#my-mana-row .mana-tile').forEach((tile, i) => {
        tile.classList.toggle('auto-pay-planned', planned.includes(i));
    });
}

function endDrag() {
    dragging = null;
    markDropZones();
    document.querySelectorAll('.auto-card.auto-dragging')
        .forEach(el => el.classList.remove('auto-dragging'));
    document.querySelectorAll('#my-mana-row .mana-tile.auto-pay-planned')
        .forEach(el => el.classList.remove('auto-pay-planned'));
}

/**
 * ドロップ先を1つ登録する。★<b>ページの読み込み時に1回だけ</b>呼ぶ ——
 * この4つの器は描画で作り直されない(作り直されるのは中身だけである)。
 */
function registerDropZone(zone, id) {
    const el = document.getElementById(id);
    if (!el) return;
    el.addEventListener('dragover', (e) => {
        if (!dragging || !dragging.zones.includes(zone)) return;
        e.preventDefault();
        if (dragging.hoverZone !== zone) {
            dragging.hoverZone = zone;
            refreshPlannedMana();   // ★賢魂かミニオンかで払う枚数が変わる(裁定318)
        }
        el.classList.add('auto-drop-over');
    });
    el.addEventListener('dragleave', () => el.classList.remove('auto-drop-over'));
    el.addEventListener('drop', (e) => {
        if (!dragging || !dragging.zones.includes(zone)) return;
        e.preventDefault();
        e.stopPropagation();
        // ★落ちた場所は座標から取る(20a の A4)。進化の素材はこれでしか分からない
        const under = document.elementFromPoint(e.clientX, e.clientY);
        const card = under && under.closest ? under.closest('#my-minions .auto-card') : null;
        const droppedOn = card ? card.dataset.instanceId : null;
        const dropped = dragging;
        endDrag();
        playByDrop(dropped, zone, droppedOn);
    });
}

DROP_ZONES.forEach(({ zone, id }) => registerDropZone(zone, id));

/**
 * ★★★Batch 70: 落としたものを実際にプレイする(裁定318・321・322)。
 *
 * ★<b>確認は挟まない</b>(裁定321) —— ただし裁定317 の警告だけは例外で必ず出す。
 * ★<b>「どちらの姿で使うか」は確認ではない。</b>【賢魂】は落とし先で決まり(裁定318)、
 *   特殊召喚と強化使用は落としたあとに尋ねる —— どちらも
 *   「本当にやるか」ではなく「どの規則で使うか」の問いだからである。
 */
function playByDrop(d, zone, droppedOnInstanceId) {
    const card = d.card;
    if (zone === 'MANA') {
        send('charge-mana', { handIndex: d.index });   // 裁定323: ドラッグに確認は無い
        return;
    }
    const asSoul = zone === 'SPELL' && card.soulCost != null && card.type !== 'SPELL';
    if (d.from === 'TABOO') {
        const action = asSoul ? 'play-taboo-soul' : 'play-taboo';
        const specs = asSoul ? card.soulTargets : card.targets;
        const cost = asSoul ? card.soulCost : card.cost;
        if (tabooPayBurns(cost)) {
            // ★★裁定317: 裏向きのマナは墓地へ行き、二度と戻らない。
            //   ★確認の器を新しく作らない —— 自動の払い方をあらかじめ選んだ状態で
            //     確定待ちへ入れる。人はそのまま[確定]でも、選び直してもよい
            beginManaPayment({
                kind: 'TABOO', cost, card, tabooIndex: d.index, action, specs,
                picked: (latestView.you.tabooPayOrder || []).slice(0, cost),
                warn: '裏向きのマナが墓地へ送られます(マナが永久に減ります)',
            });
            return;
        }
        // ★指定を空で送る。サーバが ManaPayment.tabooOrder の順に払う(裁定317)
        beginSelection(action, null, specs, { tabooIndex: d.index, manaIndexes: [] });
        return;
    }
    // ★★<b>指定を空で明示して送る。</b>「指定が無い=自動で払う」という約束を
    //   通信の形にも残しておく —— 省略と空を混ぜると、あとから読む人が
    //   「送り忘れ」と「自動でよい」を区別できない(裁定315・316)
    if (asSoul) {
        beginSelection('play-soul', d.index, card.soulTargets, { manaIndexes: [] });
        return;
    }
    let action = 'play-card';
    let specs = card.targets;
    let enhanced = false;
    if (card.canSpecialSummon && latestView.phase === 'MAIN'
            && confirm(card.specialSummonText + '\n\nOK = 特殊召喚 / キャンセル = 通常プレイ')) {
        action = 'special-summon';
        specs = card.specialTargets;
    } else if (card.enhancedCost > 0) {
        enhanced = confirm(card.enhancedText
            + `\n\nOK = 追加コスト+${card.enhancedCost}を払う / キャンセル = 通常使用`);
    }
    const extra = { enhanced, manaIndexes: [] };
    if (card.type === 'EVOLUTION') {
        // ★★裁定322: <b>落とした先が1体目の素材</b>であり、残りは今までどおり問い合わせる。
        //   ★52 が作った素材選択の器をそのまま使う(足す物が無い)
        if (!droppedOnInstanceId
                || !(card.evolutionMaterialIds || []).includes(droppedOnInstanceId)) {
            showMessage('進化は素材にできるミニオンの上に落としてください(条件: '
                + card.evolutionText + ')');
            return;
        }
        beginEvolutionSelection(action, d.index, specs, extra, card);
        if (evolution) pickEvolutionMaterial(droppedOnInstanceId);
        return;
    }
    beginSelection(action, d.index, specs, extra);
}

function useLeaderAbility() {
    if (hasPendingChoice()) return;
    const ability = latestView && latestView.you && latestView.you.leaderAbility;
    if (!ability || !ability.usable || pending) return;
    beginSelection('leader-ability', null, ability.targets);
}

// ★Batch 71: どちらも「送れたときだけ選択を捨てる」(4-2)。
//   マリガンで選んだ枚数は、切断中に消えると<b>もう一度全部選び直し</b>になる
function submitMulligan() {
    if (!send('mulligan', { handIndexes: mulliganPicks })) return;
    mulliganPicks = [];
}

function keepHand() {
    if (!send('mulligan', { handIndexes: [] })) return;
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
            // ★Batch 70: 「置けるか」の判定は canChargeMana 1本に寄せた(裁定130)。
            //   ドロップ先を光らせる側と、自動進行が待つ側が同じ式を読む
            return canChargeMana(view) && you.hand.length > 0;
        case 'MAIN': {
            const abilityUsable = you.leaderAbility && you.leaderAbility.usable;
            if (you.cannotUseCards) return abilityUsable; // 起動能力はカードの使用ではない
            const soulCostOf = c => (c.soulEffectiveCost != null ? c.soulEffectiveCost : c.soulCost);
            const playable = you.hand.some(c => c.type !== 'LEADER'
                && (costOf(c) <= you.availableMp || c.canSpecialSummon
                    || (c.soulCost != null && soulCostOf(c) <= you.availableMp)));
            // 禁忌はMPではなくマナ枚数で支払う(一時マナは使えない)
            const payableMana = you.manaZone.filter(m => !m.temporary).length;
            // ★Batch 54: 禁忌の賢魂は n 枚で払える(マスター裁定 A6)
            const tabooPlayable = (you.taboo || []).some(c => c.cost <= payableMana
                || (c.soulCost != null && c.soulCost <= payableMana));
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
    showZoneFaces(`${p.displayName}の墓地(${p.trashCount}枚)`, p.trash || [], isSelf);
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
    // ★Batch 52: 進化素材の選択中は、自分の場のクリックが素材の指定になる
    if (evolution) {
        pickEvolutionMaterial(instanceId);
        return;
    }
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

/** カードIDから名前を引く。未取得・未知IDはIDをそのまま出す(壊さない、の系列) */
function libName(cardId) {
    const entry = autoLibrary.get(cardId);
    return entry && entry.name ? entry.name : cardId;
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
        const topFace = () => faceDataFromCardView(opts.top);
        attachZoom(el, topFace);
        // ★★★Batch 70(指摘1): パイルの一番上にもホバープレビューを出す。
        //   ★<b>出す位置は動かさない。</b>実測で、面は x:748〜980 に出て
        //     右列(x:988〜)とは重ならない —— 69 の A-3 が心配した
        //     「パイル自身を覆う」は起きなかった(先に測って分かったことである)
        attachHover(el, topFace);
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
        onClick: () => showZoneFaces(`${who}の墓地(${p.trashCount}枚)`, p.trash, isSelf),
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
 * ★★★Batch 69: パイルの枚数バッジに数を書き込む<b>唯一の口</b>。
 *
 * 44 は「数字を書き込む既存のコードは1行も変えずに、書き込み先だけが移る」と書いて、
 * <b>書き込みを8箇所に散らしたまま</b>にした。65 が挙げた「0枚でも金色で目立つ」を
 * 直すには「0なら暗くする」という規則が要るが、それを8箇所に書けば
 * <b>7箇所直して1箇所忘れる</b>形の事故がいつでも起きる(裁定130)。
 * → 69 で書き込みをこの1本に通した。<b>44 の設計を1つだけ変えている。</b>
 *
 * ★<b>0 の判定を「文字列が '0' か」で書かない。</b>サーバは数を送るが、
 *   初期表示の '-' も同じ欄に入る。数として読んで 0 のときだけ暗くする
 *   ('-' は NaN なので暗くならない —— それでよい。まだ何も届いていない状態である)。
 */
function setPileCount(id, count) {
    const el = document.getElementById(id);
    if (!el) return;
    el.textContent = count;
    el.classList.toggle('auto-pile-count-zero', Number(count) === 0);
}

/**
 * ゾーンの中身をフェイスの一覧で出す(墓地・消滅)。
 * 33 までの「名前の文字列」を面に格上げした。右クリックで1枚ずつ拡大できる。
 */
function showZoneFaces(title, cards, graveSummon) {
    document.getElementById('info-modal-title').textContent = title;
    const content = document.getElementById('info-modal-content');
    content.innerHTML = '';
    if (!cards || cards.length === 0) {
        content.textContent = '(なし)';
    } else {
        const grid = document.createElement('div');
        grid.className = 'auto-zone-grid';
        cards.forEach((card, index) => {
            const holder = document.createElement('div');
            holder.className = 'auto-zone-card';
            holder.appendChild(cardFace(faceDataFromCardView(card), 'mini'));
            const zoneFace = () => faceDataFromCardView(card);
            attachZoom(holder, zoneFace);
            // ★★★Batch 70(指摘1): ゾーン一覧(墓地・消滅)にもホバープレビューを出す。
            //   ★z-index はホバー(1400)> モーダル(1000)なので上に出る。
            //     ただし実測で<b>モーダル本体(x:383〜897)と面(x:748〜980)が重なる</b>ので、
            //     モーダルが開いている間は左端へ逃がす(attachHover の中で決める)
            attachHover(holder, zoneFace);
            // ★Batch 53: 墓地からの【特殊召喚】(《サモナーポップ・エンラ》)。
            // 「今それができるか」を知っているのはサーバだけなので、
            // この画面は canSpecialSummonFromGrave をそのまま信じてボタンを出す
            // (印(Batch 47)・進化素材の候補(裁定234)と同じ考え方である)
            if (graveSummon && card.canSpecialSummonFromGrave) {
                const btn = document.createElement('button');
                btn.type = 'button';
                btn.className = 'btn btn-sm btn-warning w-100 mt-1';
                btn.textContent = '★特殊召喚';
                btn.onclick = () => beginGraveSpecialSummon(index, card);
                holder.appendChild(btn);
            }
            grid.appendChild(holder);
        });
        content.appendChild(grid);
    }
    document.getElementById('info-modal').classList.remove('d-none');
}

/**
 * ★Batch 53: 墓地からの【特殊召喚】を始める。
 * 手札からの特殊召喚と違うのは<b>出どころ(trashIndex)だけ</b>で、
 * 進化なら素材、対象があれば対象、という段取りはまったく同じである。
 */
function beginGraveSpecialSummon(trashIndex, card) {
    if (!latestView || !latestView.myTurn || latestView.phase !== 'MAIN') {
        showMessage('墓地からの特殊召喚はメインフェイズにのみ行えます');
        return;
    }
    if (!confirm(card.specialSummonText + '\n\nOK = 墓地から特殊召喚する')) {
        return;
    }
    hideModal();
    const action = 'special-summon-from-grave';
    const specs = card.specialTargets;
    const extra = { trashIndex };
    if (card.type === 'EVOLUTION') {
        beginEvolutionSelection(action, null, specs, extra, card);
        return;
    }
    beginSelection(action, null, specs, extra);
}

// ---------------------------------------------------------------
// ホバープレビュー(Batch 44・B-1)
//
// ★★★Batch 69: 44 は「まずはリーダーから」と書いて<b>そこで止まっていた</b>。
//   65 が穴として挙げたのはこの止まりである —— 器(#auto-hover / .auto-hover)は
//   44 が作りきっており、69 が足したのは<b>呼び出し2箇所</b>(場のミニオンと手札)だけである。
//   ★これは 65 の教訓「『番人が無い』と思ったら、まず在るかどうかを見る」の
//     器についての形である(67 の《ツイン・ストライク》と同じ)。
//
// ★★<b>対象を選んでいる最中は出さない</b>(69・マスター確認)。
//   .auto-hover は right:300px / top:64px の<b>固定位置</b>に出るので、
//   場のミニオンにホバーするとプレビューが<b>盤面の上に重なる</b>。
//   68 で【召喚時】の対象を盤面から選ぶ場面が15枚ぶん増えたため、
//   選んでいる最中に大きな面が降りてくると<b>候補が隠れる</b>。
//   pointer-events:none なのでクリックは通るが、<b>見えないものは押せない</b>。
// ---------------------------------------------------------------

let hoverTimer = null;

/**
 * ホバープレビューを出してよいか。★判定はこの1箇所だけが持つ(裁定130)。
 *
 * ★<b>「選んでいる最中」は5つある。</b>どれも<b>盤面か手札をクリックさせている</b>状態である。
 *   宣言時の対象指定(pending)/ 進化素材(evolution)/ ★支払いの確定待ち(manaPay)/
 *   マリガン / ★★<b>割り込み(hasPendingChoice)</b>。
 * ★★★<b>5つ目を書き忘れていた。</b>68 が【召喚時】の対象を割り込みへ移したので、
 *   マスターが心配した「対象を選ぶときに候補が隠れる」は<b>いちばん割り込みで起きる</b> ——
 *   なのに最初の実装は宣言時の pending しか見ていなかった。
 *   ★割り込み中かどうかの判定は {@link hasPendingChoice} が既に持っている。
 *     同じ式を書き写さずに<b>呼ぶ</b>(裁定130)。
 */
function hoverBlocked() {
    return !!pending || !!evolution || !!manaPay || hasPendingChoice()
        || !!(latestView && latestView.mulligan);
}

function hideHover() {
    clearTimeout(hoverTimer);
    document.getElementById('auto-hover').classList.add('d-none');
}

function attachHover(el, dataFn) {
    el.onmouseenter = () => {
        clearTimeout(hoverTimer);
        if (hoverBlocked()) return;
        hoverTimer = setTimeout(() => {
            // ★350ms 待っているあいだに問い合わせが来ることがある。
            //   入口だけで見ると、<b>手を止めているのに面が降りてくる</b>
            if (hoverBlocked()) return;
            const holder = document.getElementById('auto-hover-card');
            holder.innerHTML = '';
            holder.appendChild(cardFace(dataFn(), 'large'));
            const hover = document.getElementById('auto-hover');
            // ★★★Batch 70(指摘1): ゾーン一覧はモーダルの中に在る。
            //   実測でモーダル本体(x:383〜897)と面(x:748〜980)が重なるので、
            //   モーダルが開いている間だけ左端へ逃がす。
            //   ★<b>判定はここ1箇所だけが持つ</b>(裁定130)——
            //     呼び出し側に位置を決めさせると、呼び出しの数だけ規則が散る
            hover.classList.toggle('auto-hover-left', modalOpen());
            hover.classList.remove('d-none');
        }, 350);
    };
    el.onmouseleave = hideHover;
}

/** 情報モーダル(墓地・消滅・マナ・リーダー能力の一覧)が開いているか */
function modalOpen() {
    return !document.getElementById('info-modal').classList.contains('d-none');
}

// ---------------------------------------------------------------
// ★★Batch 65: マナ行の重なり(設計解説 1〜3章)
//
// 45 は「はみ出したら固定の -26px で重ねる」という簡易版だった。3つ壊れていた。
//   (1) タップ済(回転)のタイルは外接が横 80px になり、その 8px が左隣へ食い込む。
//       CSS には .tapped { margin-left: 14px } という下駄が在ったのに、
//       重なり側のセレクタのほうが詳細度で勝つため<b>効いていなかった</b>。
//   (2) 重なりが枚数に関係なく固定なので、15枚すべてタップ済だと 50px はみ出し、
//       右列(リーダー・パイル)に重なった。実測で確認した。
//   (3) 重なりが均等でなく、読みたい<b>表向きの名前</b>のほうが潰れていた
//       (実測: 表向きの露出 33px / タップ済 65px)。
//
// ★これは手動モードが Batch 34 hotfix で既に直した症状と<b>同じ根</b>である。
//   あちら(applyManaOverlap)は「占有幅はタイルごとに違う」「露出の下限を守る」
//   「必要なぶんだけ均等に重ねる」の3つで解いている。65 はその規則を通常モードにも当てる。
//   ★同じ規則が2箇所に在ること自体は裁定130 が許す形である —— 一致を番人が見張る。
//     verify 65系がこちら側の露出下限と非はみ出しを実物の DOM で測る。
// ---------------------------------------------------------------

/** マナタイルの寸法。★CSS の .auto-mana-row .mana-tile と対である(片方だけ変えないこと) */
const AUTO_MANA_TILE_WIDTH = 64;
const AUTO_MANA_TILE_HEIGHT = 80;
/** タイルの間に空けたい幅。45 までは CSS の margin-left が持っていた値である */
const AUTO_MANA_GAP = 4;
/**
 * 1枚あたり露出させたい幅。★手動モードの MANA_MIN_EXPOSURE / MANA_HARD_EXPOSURE と同じ値。
 * まず 28px の露出で詰め、それで足りなければ 14px まで譲る。
 * ★名前は左寄せなので、露出しているのは<b>名前の先頭</b>である(z-index を昇順に振るため)。
 */
const AUTO_MANA_MIN_EXPOSURE = 28;
const AUTO_MANA_HARD_EXPOSURE = 14;

/**
 * マナ行の重なりを計算して当てる。★margin は<b>ここだけ</b>が書く。
 *
 * 45 は CSS(3本のセレクタ)と JS(クラスの付け外し)の両方が margin を決めていて、
 * 詳細度でどちらが勝つかが挙動を決めていた。65 は CSS から margin を全部外し、
 * 規則をこの関数1本にした(設計判断28: 同じ役割のものを2つの形で持たない)。
 *
 * ★<b>占有幅は回転で変わる。</b>要素の幅は常に 64px だが、90度回した見た目の外接は
 *   縦の 80px になる。左右に (80-64)/2 = 8px ずつはみ出すので、その下駄を margin で確保する。
 * ★<b>覆われるのは左のタイルである。</b>z-index を昇順に振るので、タイル i の見える幅は
 *   「i 自身の占有幅 + 間隔 - 重なり」になる。上限を取るのはこの<b>覆われる側</b>
 *   (0 〜 n-2)であり、手動モードの実装は覆う側(1 〜 n-1)で取っている ——
 *   均一な行では同じ値になるが、タップ済と非タップが混ざる行では1枚ぶんずれる。
 *   ★手動モードのずれは notes/qte-pitfalls.md に記録した(65 では触らない)。
 */
function applyAutoManaOverlap(row) {
    const tiles = [...row.children];
    for (const tile of tiles) {
        tile.style.marginLeft = '';
        tile.style.marginRight = '';
        tile.style.zIndex = '';
    }
    if (tiles.length === 0) return;
    const pad = (AUTO_MANA_TILE_HEIGHT - AUTO_MANA_TILE_WIDTH) / 2;
    const tapped = tiles.map(t => t.classList.contains('tapped'));
    const footprint = (i) => (tapped[i] ? AUTO_MANA_TILE_HEIGHT : AUTO_MANA_TILE_WIDTH);
    let naturalWidth = AUTO_MANA_GAP * (tiles.length - 1);
    tiles.forEach((tile, i) => { naturalWidth += footprint(i); });
    // ★clientWidth は padding を含む。比べる相手は「中身が置ける幅」である(34 hotfix と同じ)
    const cs = window.getComputedStyle(row);
    const trackWidth = row.clientWidth
        - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
    // 覆われるのは 0 〜 n-2 のタイルである
    const overlapCapAt = (exposure) => Math.min(
        ...tiles.slice(0, -1).map((t, k) => footprint(k) + AUTO_MANA_GAP - exposure));
    const needed = tiles.length <= 1 || naturalWidth <= trackWidth
        ? 0
        : (naturalWidth - trackWidth) / (tiles.length - 1);
    let perTileOverlap = Math.min(needed, overlapCapAt(AUTO_MANA_MIN_EXPOSURE));
    if (needed > perTileOverlap) {
        perTileOverlap = Math.min(needed, overlapCapAt(AUTO_MANA_HARD_EXPOSURE));
    }
    tiles.forEach((tile, i) => {
        const base = tapped[i] ? pad : 0;
        tile.style.marginLeft = `${base + (i > 0 ? AUTO_MANA_GAP - perTileOverlap : 0)}px`;
        tile.style.marginRight = `${base}px`;
        tile.style.zIndex = String(i + 1);
    });
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
        manaPay = null;
        render(latestView);
        return;
    }
    // ★★★Batch 72: 退室が受理された。<b>ここまで来て初めて</b>記録を消して遷移する。
    //   ★手動モードは送った時点で消して遷移するが、あちらの退室は失敗しない。
    //     通常モードは対戦中の着席者を断るので、断られたときは
    //     ERROR が届いて<b>この行を通らない</b>(設計解説 4-3)。
    if (message.type === 'LEFT') {
        forgetOccupant();
        location.href = '/auto';
        return;
    }
    // ★★★Batch 62: 音の取り付け点の1つ目(裁定287)。
    //   ★<b>ここが「配信」の唯一の入口である。</b>render(latestView) は画面の操作でも
    //   走るので、あちらで差分を採ると「配信」と「再描画」を区別できない。
    //   ★差し替え前の latestView が「前回の盤面」そのものである
    sfxForDelivery(latestView, message.view);
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
    manaPay = null;
    render(latestView);
}

function render(view) {
    if (!view) return;
    // ★★Batch 69: 対象を選んでいる最中はホバープレビューを閉じる。
    //   面が出たあとに問い合わせが来る経路があるため、<b>出す側の判定だけでは足りない</b>
    //   (attachHover のガードは「これから出す」を止める。ここは「もう出ている」を消す)。
    if (hoverBlocked()) hideHover();
    renderHeader(view);
    renderPhaseTrack(view);
    renderControls(view);
    // ★★★Batch 72: 席・退室・投了(ヘッダ)と、決着の面(右列の中段)。
    //   ★<b>view.you より前に呼ぶ。</b>受付の時間帯(盤面がまだ無い)にも
    //     席と退室のボタンは要る —— 下の early return より後ろに置くと、
    //     <b>いちばん席を動かしたい時間帯にボタンが出ない</b>
    renderRoomControls(view);
    renderResult(view);
    renderSelection();
    renderMulligan(view);
    renderLog(view.log);
    renderDeckGate(view);
    if (!view.you) {
        showMessage(waitingMessage(view));
        return;
    }
    renderOpponent(view.opponent, view);
    renderSelf(view.you, view);
    renderPendingChoice(view);
    renderPlayingCard(view);
    syncTabooRow();

    if (view.status === 'FINISHED') {
        showMessage('対戦終了: ' + view.winnerName + ' の勝利');
    }
    maybeAutoAdvance(view);
}

/**
 * ★★★Batch 72: 試合の出入りのボタン(ヘッダ)。
 *
 * <h2>どれがいつ出るか</h2>
 * <pre>
 *   | 状態                | 席を立つ | 席に着く | 退室   | 投了 |
 *   |---------------------|---------|---------|--------|------|
 *   | WAITING (盤面なし)   | ○(観戦可)| ○(空席) | ○     | ×   |
 *   | SETUP / PLAYING     | ×      | ×      | 観戦者のみ | ○ |
 *   | FINISHED            | ×      | ×      | ○     | ×   |
 * </pre>
 *
 * ★<b>席を動かせるのは盤面が無い間だけ</b>である。66 が「席を立てない」と書いた理由
 * (席は {@code GameState} の2人と1対1)は、<b>盤面が在るあいだにしか掛かっていない</b>。
 * ★決着後に抜けたい人は<b>退室</b>する —— 席は空くが盤面は残るので、
 * 残ったほうは決着した盤面を読み続けられる。
 *
 * <h2>★「盤面が在るか」の見分け方</h2>
 * {@code view.status === 'WAITING'} が「{@code GameState} がまだ無い」と同じ意味である。
 * ★{@code GameService.startIfBothReady} は {@code SETUP} を<b>盤面を部屋に載せる前に</b>
 * 立てるので、WAITING の盤面が配信される瞬間は存在しない。
 * ★この一致は {@code Batch72SeatTest} が見張る —— 順序が入れ替わると
 * <b>ここの分岐が黙って1つ増える</b>(WAITING なのに盤面が在る)。
 *
 * ★★<b>これは操作補助であって、守りではない</b>(設計判断27)。
 * 断るのはサーバである({@code GameRoom} / {@code GameService})。
 */
function renderRoomControls(view) {
    const seatBtn = document.getElementById('btn-seat');
    const concedeBtn = document.getElementById('btn-concede');
    const leaveBtn = document.getElementById('btn-leave');
    const room = view.room;
    if (!room) {
        for (const b of [seatBtn, concedeBtn, leaveBtn]) b.classList.add('d-none');
        return;
    }
    const seated = !!room.viewerSeat;
    const board = view.status !== 'WAITING';
    const finished = view.status === 'FINISHED';
    const freeSeat = !(room.seatA && room.seatA.name) || !(room.seatB && room.seatB.name);

    let seatLabel = null;
    if (!board && seated && room.spectatorAllowed) {
        seatLabel = '席を立つ';
    } else if (!board && !seated && freeSeat) {
        seatLabel = '席に着く';
    }
    seatBtn.classList.toggle('d-none', seatLabel === null);
    if (seatLabel !== null) seatBtn.textContent = seatLabel;

    concedeBtn.classList.toggle('d-none', !(seated && board && !finished));
    // ★対戦中の着席者だけが退室できない(先に投了する)。観戦者はいつでも抜けられる
    leaveBtn.classList.toggle('d-none', seated && board && !finished);
}

/**
 * ★★★Batch 72: 決着の面(右列の中段)。
 *
 * <h2>なぜオーバーレイにしないのか</h2>
 * 71 の切断オーバーレイは「操作しても届かない」を宣言するために盤面を覆う。
 * <b>決着は違う。</b>終わった盤面は<b>まだ読まれている</b> ——
 * 何がどう決まったのかを見返す時間が要る(手動モードの裁定44「決着後も盤面をロックしない」)。
 * ★覆うと、再戦の返事をするために盤面を消さなければならなくなる。
 *
 * <p>★置き場所は実測で決めた。右列の中段は 294px あり、決着時に使われているのは
 * 72px(案内欄33px + ログのバー33px)だけである。
 * ★この面は<b>いちばん背の高い顔(承諾待ち)で 93px</b> であり、溢れない ——
 * 一致は verify 72-6 が実測で見張る(値は書かない・設計判断41)。
 *
 * <h2>顔は4つある</h2>
 * <ol>
 * <li>観戦者 …… 結果と、申し込みが在ればその事実だけ。ボタンは出ない</li>
 * <li>着席・申し込み無し …… [再戦を申し込む](相手が席に居るときだけ)</li>
 * <li>着席・自分が申し込んだ …… 待っている旨。ボタンは出ない</li>
 * <li>着席・相手が申し込んだ …… [応じる] [断る]</li>
 * </ol>
 */
function renderResult(view) {
    const el = document.getElementById('auto-result');
    const on = view.status === 'FINISHED';
    el.classList.toggle('d-none', !on);
    if (!on) return;

    const room = view.room;
    document.getElementById('auto-result-winner').textContent =
        (view.winnerName || '?') + ' の勝利';
    const note = document.getElementById('auto-result-note');
    const offerBtn = document.getElementById('btn-rematch-offer');
    const acceptBtn = document.getElementById('btn-rematch-accept');
    const declineBtn = document.getElementById('btn-rematch-decline');
    for (const b of [offerBtn, acceptBtn, declineBtn]) b.classList.add('d-none');

    const seated = !!(room && room.viewerSeat);
    const offerSeat = room ? room.rematchOfferedBySeat : null;
    const offerName = room ? room.rematchOfferedByName : null;
    const bothSeated = !!(room && room.seatA && room.seatA.name
        && room.seatB && room.seatB.name);

    if (!seated) {
        // ★観戦者。誰が申し込んだかは部屋の公開情報である(見えてよい)
        note.textContent = offerSeat ? `${offerName} が再戦を申し込んでいます。` : '';
        return;
    }
    if (offerSeat === null || offerSeat === undefined) {
        if (bothSeated) {
            note.textContent = '';
            offerBtn.classList.remove('d-none');
        } else {
            note.textContent = '相手が席に居ないため、再戦を申し込めません。';
        }
        return;
    }
    if (offerSeat === room.viewerSeat) {
        note.textContent = '再戦を申し込みました。相手の返事を待っています。';
        return;
    }
    note.textContent = `${offerName} が再戦を申し込んでいます。`;
    acceptBtn.classList.remove('d-none');
    declineBtn.classList.remove('d-none');
}

/**
 * ★★★Batch 70(指摘2): 「今プレイしているカード」を出す。
 *
 * <h2>ホバープレビューとは別物である</h2>
 *
 * 69 は「対象を選んでいる最中はホバープレビューを出さない」と決めた。
 * 指摘2 が求めているのは<b>その裏側</b>である ——
 * 出さないのは「手を乗せた先」の面であって、
 * 「今プレイしているカードそのもの」は出しっぱなしにしてほしい。
 *
 * ★★<b>だから器を分けた</b>(#auto-playing)。#auto-hover を使い回すと
 *   「乗せた先で入れ替わる」性質を引き継ぎ、<b>手を動かした瞬間に消える</b>。
 *
 * <h2>出どころ</h2>
 *
 * カードIDはサーバが問い合わせに添えてくる({@code pendingChoice.sourceCardId})。
 * ★<b>クライアントは「直前に自分が送ったカード」を覚えていない。</b>
 *   割り込みは相手のターンにも来る(【破壊時】など)ので、
 *   覚えていた値は<b>そのとき別のカードを指す</b>。
 *
 * ★面は card-library から引く(裁定144)—— ビューはIDしか運ばない。
 * ★取得前・未知IDのときは出さない(壊さない、の系列)。
 */
function renderPlayingCard(view) {
    const el = document.getElementById('auto-playing');
    const choice = view.you && view.you.pendingChoice;
    const lib = choice && choice.sourceCardId ? autoLibrary.get(choice.sourceCardId) : null;
    el.classList.toggle('d-none', !lib);
    if (!lib) return;
    const holder = document.getElementById('auto-playing-card');
    holder.innerHTML = '';
    holder.appendChild(cardFace({
        name: lib.name, type: lib.type, civilization: lib.civilization,
        cost: lib.cost, attack: lib.attack, hp: lib.hp,
        keywords: [], text: lib.text,
    }, 'large'));
}

/**
 * 割り込み選択(a9): 効果の解決中にサーバが問い合わせてきた選択のUI。
 * 手札・場・墓地・公開領域のいずれからでも、候補の並び順の位置を選んで送り返す。
 * 既存の対象選択(pending)とは別物(あちらは使用宣言時、こちらは解決の途中)。
 *
 * ★Batch 64: kind に CONFIRM(はい/いいえ)が増え、queued(待っている件数)が届くようになった。
 * どちらも送受信の形は変わっていない —— CONFIRM は「候補1件の選択」であり、
 * [はい] は index 0 を送り、[いいえ] は空を送るだけである。
 */
function renderPendingChoice(view) {
    const area = document.getElementById('reveal-area');
    const choice = view.you && view.you.pendingChoice;
    area.classList.toggle('d-none', !choice);
    const row = document.getElementById('reveal-cards');
    const promptEl = document.getElementById('reveal-prompt');
    row.innerHTML = '';
    if (!choice) return;
    // ★複数の問い合わせが並んでいるときは、残り件数を添える(1件だけのときは出さない)
    promptEl.textContent = choice.queued > 1
        ? `${choice.prompt}(この後あと${choice.queued - 1}件)`
        : choice.prompt;
    if (choice.kind === 'CONFIRM') {
        renderConfirmChoice(row);
        document.getElementById('btn-confirm-choice').classList.add('d-none');
        return;
    }
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

/**
 * 「〜してもよい」の はい/いいえ(★Batch 64)。
 * ★どちらも押せば即送信する —— 取り消せる操作ではないが、二択に確認を重ねる意味は無い(裁定113)。
 */
function renderConfirmChoice(row) {
    // ★分割代入で書かないのは tools/check_undeclared.py が引数の分割代入を
    //   「未宣言の識別子」と読むためである(道具に合わせて書き方を1つ選ぶ。裁定114 の親戚)
    const options = [
        { label: 'はい', cls: 'btn-warning', indexes: [0] },
        { label: 'いいえ', cls: 'btn-outline-secondary', indexes: [] }
    ];
    options.forEach(function (opt) {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'btn btn-sm ' + opt.cls;
        btn.textContent = opt.label;
        btn.onclick = () => {
            // ★Batch 71: 送れたときだけ選択を捨てる(4-2)
            if (!send('resolve-choice', { chosenIndexes: opt.indexes })) return;
            choicePicks = [];
        };
        row.appendChild(btn);
    });
}

/** 候補のトグル(複数選択)または即確定(単一選択) */
function toggleChoicePick(index, choice) {
    if (choice.max === 1 && choice.min === 1) {
        // 1つだけ選ぶ: クリックで即送信
        // ★Batch 71: 送れたときだけ選択を捨てる(4-2)
        if (!send('resolve-choice', { chosenIndexes: [index] })) return;
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
    // ★Batch 71: 送れたときだけ選択を捨てる(4-2)。
    //   ★割り込みは<b>相手のターンにも来る</b>。切断中に選んだものが消えると、
    //     再接続後に候補の一覧を読み直すところからやり直しになる
    if (!send('resolve-choice', { chosenIndexes: choicePicks })) return;
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

/**
 * 盤面がまだ無いときに出す一言(★Batch 66)。
 *
 * ★65 までは「相手の入室を待っています。部屋コードを伝えてください」の1通りだった。
 * 66 は待つ理由が3つある —— 席が埋まっていない / デッキが揃っていない / 観戦者である。
 * <b>理由が違うのに同じ文言を出すと、何を待っているのか分からない。</b>
 */
function waitingMessage(view) {
    const room = view.room;
    if (!room) return '対戦の準備を待っています。';
    if (room.viewerSpectator) {
        return '観戦中です。両者がデッキを読み込むと対戦が始まります。';
    }
    const seated = (room.seatA && room.seatA.name ? 1 : 0) + (room.seatB && room.seatB.name ? 1 : 0);
    if (seated < 2) {
        return '相手の入室を待っています。部屋コードを伝えてください: ' + view.roomId;
    }
    return '両者のデッキが揃うと対戦が始まります: ' + seatSummaryLine(view);
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

/**
 * ★★★Batch 69: ターンの7フェイズ。<b>正は Java の TurnPhase である</b>(総合ルール 2-6)。
 *
 * ★ここに書き写しているのは<b>見た目のための並び</b>であって、規則ではない。
 *   規則(どの順で進むか・いつ何ができるか)はサーバだけが持つ。
 * ★★ただし<b>書き写しは黙って離れていく</b>(67 の教訓・写し)。
 *   フェイズが増えても、この配列は自分では増えない ——
 *   一致は verify 69-5 が TurnPhase.java を読んで突き合わせる(裁定130)。
 */
const AUTO_PHASES = [
    { phase: 'DRAW', label: 'ドロー' },
    { phase: 'UNTAP', label: 'アンタップ' },
    { phase: 'MANA_CHARGE', label: 'マナチャージ' },
    { phase: 'MAIN', label: 'メイン' },
    { phase: 'BATTLE', label: 'バトル' },
    { phase: 'SUB', label: 'サブ' },
    { phase: 'END', label: 'ターンエンド' },
];

/**
 * フェイズの進行表(右列の空白・65 が挙げた穴)。
 *
 * ★<b>ヘッダの文字列と同じ情報を2箇所に出しているのではない。</b>
 *   ヘッダは「今どこか」の1点、この表は「7つのうちのどこか」という<b>位置</b>である。
 *   通常モードはサーバがフェイズを進めるので、次に何が来るかが読めることに価値がある。
 * ★<b>操作はここに置かない。</b>[次のフェイズへ] は #turn-controls が持つ ——
 *   同じ操作の入口を2つ作ると、押せる条件の判定が2箇所に増える(裁定130)。
 */
function renderPhaseTrack(view) {
    const track = document.getElementById('phase-track');
    if (!track) return;
    if (view.status !== 'PLAYING') {
        track.classList.add('d-none');
        track.innerHTML = '';
        return;
    }
    track.classList.remove('d-none');
    track.classList.toggle('auto-phase-theirs', !view.myTurn);
    track.innerHTML = '';
    const head = document.createElement('div');
    head.className = 'auto-phase-head';
    head.textContent = `ターン${view.turnNumber} / ` + (view.myTurn ? 'あなたの番' : '相手の番');
    track.appendChild(head);
    // ★見つからないときは -1 のままにする。全部「これから」に見えるが、
    //   <b>どれかを勝手に「今」にするよりよい</b>(嘘の位置を出さない)
    const now = AUTO_PHASES.findIndex(p => p.phase === view.phase);
    AUTO_PHASES.forEach((p, i) => {
        const item = document.createElement('div');
        item.className = 'auto-phase-item '
            + (i === now ? 'auto-phase-now' : (now >= 0 && i < now ? 'auto-phase-done' : 'auto-phase-todo'));
        const no = document.createElement('span');
        no.className = 'auto-phase-no';
        no.textContent = String(i + 1);
        const name = document.createElement('span');
        name.className = 'auto-phase-name';
        name.textContent = p.label;
        item.appendChild(no);
        item.appendChild(name);
        track.appendChild(item);
    });
}

function renderControls(view) {
    // ★★Batch 66: 観戦者には操作の導線を出さない。
    //   ★<b>これは見た目の話であって、守りではない</b> —— 観戦者は playerId を持たないので、
    //     画面を書き換えて操作を送っても GameState が知らない id として弾かれる。
    //   自動進行は「自分の番に押せる操作が無いとき次のフェイズへ進む」機能であり、
    //   自分の番を持たない観戦者には意味が無い。
    document.getElementById('btn-auto-mode')
        .classList.toggle('d-none', !!(view.room && view.room.viewerSpectator));
    document.getElementById('choose-order-area').classList.toggle('d-none', !view.chooseOrder);
    const controls = document.getElementById('turn-controls');
    const choosing = !!(view.you && view.you.pendingChoice);
    controls.classList.toggle('d-none', !(view.status === 'PLAYING' && view.myTurn) || choosing);
    // 墓地からの召喚は【黄泉の召喚主】のサブフェイズ限定(常在能力)
    const graveSummon = view.status === 'PLAYING' && view.myTurn && view.phase === 'SUB'
        && view.you.leaderCardId === 'QTE-M-DARK-15';
    document.getElementById('btn-summon-grave').classList.toggle('d-none', !graveSummon);
}

/**
 * ★★★Batch 70: 確定待ちの案内文。★<b>3つの種類で1本にしてある</b>(裁定130)。
 * 裁定317 の警告(裏向きマナが墓地送りになる)はここに混ぜず、<b>先頭に立てる</b> ——
 * 取り返しのつかない支払いは、枚数の案内と同じ扱いにしてはいけない。
 */
function manaPayPrompt() {
    const name = manaPay.card ? `【${manaPay.card.name}】` : '';
    const warn = manaPay.warn ? '⚠ ' + manaPay.warn + ' / ' : '';
    if (manaPay.kind === 'CHARGE') {
        return `${warn}${name}をマナゾーンに置きます(このターンはもう置けません)。よろしければ[確定]`;
    }
    if (manaPay.kind === 'TABOO') {
        return `${warn}${name}の禁忌コストに充てるマナを選んでください`
            + `(${manaPay.picked.length}/${manaPay.cost}) 表向き=裏向きにする / 裏向き=墓地へ送る`;
    }
    if (manaPay.cost === 0) {
        return `${warn}${name}をプレイします(コスト0)。よろしければ[確定]`;
    }
    return `${warn}${name}に払うマナを選んでください(${manaPay.picked.length}/${manaPay.cost})`;
}

function renderSelection() {
    const area = document.getElementById('selection-area');
    const skipBtn = document.getElementById('btn-skip-target');
    const payBtn = document.getElementById('btn-confirm-pay');
    payBtn.classList.add('d-none');
    if (manaPay) {
        // ★★★Batch 70(裁定319・321・323): 確定待ち。3つの種類が同じ導線に出る
        area.classList.remove('d-none');
        document.getElementById('selection-prompt').textContent = manaPayPrompt();
        skipBtn.classList.add('d-none');
        document.getElementById('btn-confirm-target').classList.add('d-none');
        document.getElementById('btn-open-trash').classList.add('d-none');
        payBtn.classList.remove('d-none');
        payBtn.disabled = manaPay.picked.length !== manaPay.cost;
        return;
    }
    if (evolution) {
        // ★Batch 52: 進化素材の選択(対象選択の手前の段)
        const card = evolution.card;
        area.classList.remove('d-none');
        const range = card.evolutionMin === card.evolutionMax
            ? String(card.evolutionMax) : card.evolutionMin + '〜' + card.evolutionMax;
        document.getElementById('selection-prompt').textContent =
            `【${card.name}】の進化素材を選んでください(${evolution.picked.length}/${range}) `
            + card.evolutionText;
        skipBtn.classList.add('d-none');
        // ★数が決まっているカードは上限で自動確定するので、確定ボタンは
        //   「1体以上」のように幅のあるカードでしか要らない
        document.getElementById('btn-confirm-target').classList.toggle('d-none',
            card.evolutionMin === card.evolutionMax
                || evolution.picked.length < card.evolutionMin);
        document.getElementById('btn-open-trash').classList.add('d-none');
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
    setPileCount('opp-deck-count', opp.deckCount);
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
    setPileCount('opp-trash-count', opp.trashCount);
    setPileCount('opp-lost-count', opp.lostCount);
    setPileCount('opp-taboo-count', opp.tabooCount);
    document.getElementById('opp-deck-name').textContent = opp.deckName;

    // ★45: 相手のマナもタイル(マスター指摘: 表向きの中身が分からないのは問題)。
    //   表向きは名前・文明色つき。裏向きは裏面で、中身はそもそも届いていない
    const oppManaRow = document.getElementById('opp-mana-row');
    oppManaRow.innerHTML = '';
    opp.manaZone.forEach(mana => oppManaRow.appendChild(buildManaTile(mana)));
    // ★★Batch 65: 重なりは applyAutoManaOverlap 1本が決める(45 のクラス切り替えは退役)
    applyAutoManaOverlap(oppManaRow);

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
    setPileCount('my-deck-count', you.deckCount);
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
    setPileCount('my-trash-count', you.trashCount);
    setPileCount('my-lost-count', you.lostCount);
    setPileCount('my-taboo-count', you.tabooCount);
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
    you.manaZone.forEach((mana, index) => {
        const tile = buildManaTile(mana);
        if (manaPay) {
            // ★★Batch 70: 禁忌も通常のプレイも同じ光りである(裁定319)。
            //   どのマナが候補かの違いは payCandidate 1箇所だけが持つ
            if (payCandidate(mana)) {
                tile.classList.add('auto-pay-candidate');
                tile.onclick = () => pickPayMana(index);
            }
            if (manaPay.picked.includes(index)) tile.classList.add('auto-pay-picked');
        } else if (dragging && plannedManaIndexes().includes(index)) {
            // ★★★Batch 70(裁定315〜317): ドラッグ中に「これから払われるマナ」を光らせる。
            //   ★順序はサーバから来た manaPayOrder / tabooPayOrder をそのまま使う ——
            //     払い方の規則をこちらに書き写さない(67 の教訓・写し)
            tile.classList.add('auto-pay-planned');
        } else if (manaReq && manaReq.kind === 'MANA') {
            tile.classList.add('mana-selectable');
            if (pending.current.manaIndexes.includes(index)) tile.classList.add('mana-picked');
            tile.onclick = () => pickMana(index);
        }
        manaRow.appendChild(tile);
    });
    // ★★Batch 65: 45 の「固定 -26px で重ねる簡易版」は退役した。必要なぶんだけ均等に重ね、
    //   露出の下限を守り、回転の外接を勘定に入れる(手動モードの 34 hotfix と同じ規則)
    applyAutoManaOverlap(manaRow);

    const req = currentRequirement();
    const row = document.getElementById('my-minions');
    row.innerHTML = '';
    you.minions.forEach(minion => {
        const el = createMinionEl(minion);
        if (evolution) {
            // ★Batch 52: 素材にできる候補だけを光らせる(判定はサーバが送った一覧のみ)
            if ((evolution.card.evolutionMaterialIds || []).includes(minion.instanceId)) {
                el.classList.add('attack-target');
                el.onclick = () => onMyMinionClick(minion.instanceId);
            } else {
                el.classList.add('exhausted');
            }
            if (evolution.picked.includes(minion.instanceId)) {
                el.classList.add('selected-attacker');
            }
        } else if (req && req.kind === 'MINION' && req.side !== 'OPPONENT') {
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
    if (you.hand === null || you.hand === undefined) {
        // ★★Batch 66: 観戦者には下段の手札の<b>中身が届かない</b>(相手として見えるぶんだけ)。
        //   そこを空のままにすると「手札0枚」に見えるので、相手の帯と同じ裏面の列を出す。
        //   ★枚数は handCount から採る —— 中身が無いことと枚数が分からないことは別である。
        for (let i = 0; i < you.handCount; i++) {
            const b = document.createElement('div');
            b.className = 'auto-back';
            fillCardBack(b);
            hand.appendChild(b);
        }
    } else {
        you.hand.forEach((card, index) => {
            const el = createHandCardEl(card, index, view);
            el.onclick = () => onHandCardClick(index);
            hand.appendChild(el);
        });
    }

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
    // ★Batch 54: 禁忌の【賢魂】は n 枚で払える(マスター裁定 A6)
    const cheapest = card.soulCost != null ? Math.min(card.cost, card.soulCost) : card.cost;
    if (!pending && !manaPay && view.myTurn && view.phase === 'MAIN' && payable >= cheapest) {
        el.classList.add('playable');
    }
    if (manaPay && manaPay.kind === 'TABOO' && manaPay.tabooIndex === index) {
        el.classList.add('selected-attacker');
    }
    el.appendChild(cardFace(faceDataFromCardView(card), 'full'));
    const badges = newBadgeBox();
    if (card.soulCost != null) addBadge(badges, '★賢魂:' + card.soulCost);
    addUnimplementedBadge(badges, card);
    attachBadges(el, badges);
    const tabooFace = () => faceDataFromCardView(card);
    attachZoom(el, tabooFace);
    // ★★★Batch 70(指摘1): 禁忌の帯にもホバープレビューを出す。
    //   ★69 は手札に1行足したとき<b>隣のこの関数には足さなかった</b> ——
    //     クラス(.auto-card-hand)まで同じなのに、作る関数が別だったからである。
    //     69 の教訓「途中」の再演であり、44 が2箇所で止まったのとまったく同じ形である
    attachHover(el, tabooFace);
    // ★★★Batch 70(裁定317・318): 禁忌もドラッグできる
    attachDrag(el, 'TABOO', index, card);
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
    // ★★Batch 70(裁定322): 進化を素材の上に落とすとき、<b>どのミニオンの上か</b>を
    //   座標から引くのに使う(dragstart の e.target では取れない・20a の A4)
    el.dataset.instanceId = minion.instanceId;
    if (minion.tapped) el.classList.add('tapped-minion');
    el.appendChild(cardFace(faceDataFromMinion(minion), 'mini'));
    // ★一時状態は面に混ぜず、バッジで上に重ねる(manual の .manual-tapped-badge と同じ考え方)
    const badges = newBadgeBox();
    if (minion.frozen) addBadge(badges, '❄凍結');
    if (minion.tapped) addBadge(badges, '⟳');
    if (minion.canUseAbility) addBadge(badges, '⚡能力');
    // ★Batch 52: 進化の下にある枚数。中身は右クリックの拡大で読める(裁定142)
    const underCount = (minion.underCardIds || []).length;
    if (underCount > 0) addBadge(badges, '下' + underCount);
    addUnimplementedBadge(badges, minion);
    attachBadges(el, badges);
    // ★拡大は「効果テキストを読む」ためのもの。abilityText(起動能力)があれば添える
    // ★★Batch 69: 拡大とホバープレビューは<b>同じ面</b>を出す。
    //   データを作る式を2つ書くと、片方だけに【起動】や「下:」が付く形で黙って離れていく。
    const minionFace = () => {
        const data = faceDataFromMinion(minion);
        if (minion.abilityText && !(data.text || '').includes(minion.abilityText)) {
            data.text = (data.text ? data.text + '\n' : '') + minion.abilityText;
        }
        // ★Batch 52: 下にあるカードは相手のものも公開情報である(素材は場に出ていた)
        if (underCount > 0) {
            data.text = (data.text ? data.text + '\n' : '')
                + '下: ' + minion.underCardIds.map(libName).join(' / ');
        }
        return data;
    };
    attachZoom(el, minionFace);
    // ★★Batch 69: 場のミニオンのホバープレビュー(44 の器を呼ぶ・65 が挙げた穴)。
    //   ★両席に付ける —— 場のミニオンは相手のものも公開情報である。
    attachHover(el, minionFace);
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
                    // ★★★Batch 74(裁定341): 進化ミニオンもミニオンである(裁定310)
                    case 'MINION_CARD': return card.type === 'MINION' || card.type === 'EVOLUTION';
                    case 'HP_5_OR_LESS': return card.hp != null && card.hp <= 5;
                    case 'COST_4_OR_LESS': return card.cost != null && card.cost <= 4;
                    case 'COST_3_OR_LESS': return card.cost != null && card.cost <= 3;
                    case 'COST_7_OR_LESS': return card.cost != null && card.cost <= 7;
                    case 'LIGHT_CIVILIZATION': return card.civilization === 'LIGHT';
                    case 'WATER_CIVILIZATION': return card.civilization === 'WATER';
                    // ★Batch 67。手札から風文明を選ばせるカードは今のところ無いが、
                    // Filter を足したら battle.js の2箇所とも足すのが規約である(裁定195)
                    case 'WIND_CIVILIZATION': return card.civilization === 'WIND';
                    case 'NON_MINION_CARD': return card.type !== 'MINION' && card.type !== 'EVOLUTION';
                    default: return true;
                }
            });
        if (selectable) el.classList.add('attack-target');
        if (pending.current.handIndexes.includes(index)
            || pending.collected.some(s => s.handIndexes.includes(index))) {
            el.classList.add('selected-attacker');
        }
        if (index === pending.handIndex) el.classList.add('exhausted');
    } else if (!pending && !evolution) {
        // ★★Batch 70: 判定は handCardPlayability 1本に移した(裁定130)。
        //   ドラッグの落とし先の判定(dropZonesFor)が<b>同じここを読む</b> ——
        //   2箇所に書くと「光っているのに落とせない」が黙って生まれる
        if (handCardPlayability(card, view).playable) el.classList.add('playable');
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
    if (card.soulCost != null) addBadge(badges, '★賢魂:' + card.soulCost);
    addUnimplementedBadge(badges, card);
    attachBadges(el, badges);
    const handFace = () => faceDataFromCardView(card);
    attachZoom(el, handFace);
    // ★★★Batch 70(指摘3): 手札からのドラッグ。落とせる先が1つも無ければ掴ませない
    attachDrag(el, 'HAND', index, card);
    // ★★Batch 69: 手札のホバープレビュー(44 の器を呼ぶ・65 が挙げた穴)。
    //   ★手札は既に :hover で 6px 持ち上がるが、あれは「どれを指しているか」の印であり、
    //     <b>効果テキストは読めない</b>。読むには右クリックで拡大するしかなかった。
    attachHover(el, handFace);
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
