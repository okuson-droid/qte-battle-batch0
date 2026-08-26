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
 * 禁忌コストの支払い進行状態。
 * { tabooIndex, cost, manaIndexes: [], specs: [対象要求...] }
 * 禁忌は「コスト支払い(マナ選択) → 対象選択 → 送信」の2段階になるため、
 * 前段をこの変数、後段を既存のpendingが担当する。
 */
let tabooPay = null;

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
    setConnectionStatus('接続済み');
    client.subscribe(`/topic/room/${ROOM_ID}/player/${PLAYER_ID}`, onMessage);
    send('ready', {});
};

client.onWebSocketClose = () => setConnectionStatus('切断(再接続中...)');

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
}

gateEl('seat-gate-buttons').addEventListener('click', async (e) => {
    const button = e.target.closest('button');
    if (!button || button.disabled) return;
    // ★観戦ボタンには data-seat が無い。null が「席に着かない」を表す
    const seat = button.dataset.seat || null;
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

function send(action, payload) {
    // ★★Batch 62: 音の取り付け点の2つ目(裁定68・287)。
    //   ここは<b>ユーザーの操作が全部通る1箇所</b>である
    sfxForAction(action);
    client.publish({
        destination: `/app/room/${ROOM_ID}/${action}`,
        body: JSON.stringify({ playerId: PLAYER_ID, ...payload }),
    });
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
        send('charge-mana', { handIndex: index });
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
    if (card.soulCost != null) {
        if (confirm(soulPrompt(card))) {
            beginSelection('play-soul', index, card.soulTargets, {});
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
    // ★Batch 52: 進化ミニオンは、対象選択より先に素材を決める
    if (card.type === 'EVOLUTION') {
        beginEvolutionSelection(action, index, specs, extra, card);
        return;
    }
    beginSelection(action, index, specs, extra);
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
    evolution = null;
    beginSelection(action, handIndex, specs, Object.assign({}, extra, { materialIds: picked }));
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
            case 'WATER_CIVILIZATION':
                ok = card && card.civilization === 'WATER'; break;
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
    tabooPay = null;
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
        if (mode === 'summon' && card.type !== 'MINION') return;
        const picked = pending && pending.current.trashIndexes.includes(index);
        const cost = card.effectiveCost != null ? card.effectiveCost : card.cost;
        const label = `${card.name} (${card.type === 'SPELL' ? 'スペル' : card.type === 'WEAPON' ? 'ウェポン' : 'ミニオン'}${cost != null ? ' コスト' + cost : ''})`;
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
    tabooPay = { tabooIndex: index, cost, manaIndexes: [], specs, action };
    if (cost === 0) {
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
    const { tabooIndex, manaIndexes, specs, action } = tabooPay;
    tabooPay = null;
    beginSelection(action || 'play-taboo', null, specs, { tabooIndex, manaIndexes });
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
            attachZoom(holder, () => faceDataFromCardView(card));
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
        tabooPay = null;
        render(latestView);
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
    renderDeckGate(view);
    if (!view.you) {
        showMessage(waitingMessage(view));
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
            send('resolve-choice', { chosenIndexes: opt.indexes });
            choicePicks = [];
        };
        row.appendChild(btn);
    });
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
    if (!pending && !tabooPay && view.myTurn && view.phase === 'MAIN' && payable >= cheapest) {
        el.classList.add('playable');
    }
    if (tabooPay && tabooPay.tabooIndex === index) el.classList.add('selected-attacker');
    el.appendChild(cardFace(faceDataFromCardView(card), 'full'));
    const badges = newBadgeBox();
    if (card.soulCost != null) addBadge(badges, '★賢魂:' + card.soulCost);
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
    // ★Batch 52: 進化の下にある枚数。中身は右クリックの拡大で読める(裁定142)
    const underCount = (minion.underCardIds || []).length;
    if (underCount > 0) addBadge(badges, '下' + underCount);
    addUnimplementedBadge(badges, minion);
    attachBadges(el, badges);
    // ★拡大は「効果テキストを読む」ためのもの。abilityText(起動能力)があれば添える
    attachZoom(el, () => {
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
                    case 'WATER_CIVILIZATION': return card.civilization === 'WATER';
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
        const cost = card.effectiveCost != null ? card.effectiveCost : card.cost;
        const affordable = cost <= view.you.availableMp;
        // ★Batch 54: 賢魂として使えるなら、ミニオンとして払えなくても光らせる。
        // 賢魂はスペルの使用なのでサブフェイズでも使える(裁定152・マスター裁定 A4)
        const soulAffordable = card.soulCost != null
            && (card.soulEffectiveCost != null ? card.soulEffectiveCost : card.soulCost)
                <= view.you.availableMp;
        // ミニオン・スペル・ウェポンはメインフェイズにプレイ可能(ウェポンの光り漏れバグを修正)
        const playable = view.myTurn && (
            view.phase === 'MANA_CHARGE' ||
            (view.phase === 'MAIN' && card.type !== 'LEADER'
                && (affordable || card.canSpecialSummon || soulAffordable)) ||
            (view.phase === 'SUB' && (card.type === 'SPELL' ? affordable : soulAffordable)));
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
    if (card.soulCost != null) addBadge(badges, '★賢魂:' + card.soulCost);
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
