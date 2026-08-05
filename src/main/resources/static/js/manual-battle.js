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
let selected = new Set();
let cardLocation = new Map();
let pinnedZoom = null;
let OCCUPANT_ID = null;
let lpModalSeatId = null;
let weaponModalCardId = null;

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

/** 新規入室する。名前は省略可(設計書 6-3)で、サーバ側の既定「プレイヤー」に任せてよい。 */
async function joinAsNewOccupant() {
    const name = prompt('名前を入力してください(省略できます)', '') || '';
    const res = await fetch(`/manual/api/rooms/${ROOM_ID}/occupants`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ displayName: name.trim() || null }),
    });
    if (!res.ok) {
        throw new Error((await res.json()).message || '入室できませんでした');
    }
    const data = await res.json();
    saveOccupant(data.occupantId, data.displayName);
    return data.occupantId;
}

/** occupantId を確定させてから解決する。localStorage にあればそれを使い、無ければ新規入室する。 */
async function resolveOccupant() {
    const saved = loadSavedOccupant();
    if (saved) {
        return saved.occupantId;
    }
    return joinAsNewOccupant();
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

/** occupantId が決まってから初めて STOMP 接続を始める(0章)。 */
resolveOccupant()
    .then((occupantId) => {
        OCCUPANT_ID = occupantId;
        client.activate();
    })
    .catch((e) => {
        setConnectionStatus('入室に失敗しました: ' + e.message);
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
}

/**
 * ★20b 2-1: ターン数・フェイズ名の表示は削除した。人間が数えるほうが早く、
 * 縦の3行(約100px)を1行へ縮めるための最大の削り代だったためである。
 * サーバ側の turn / phase 操作は休眠コードとして残してある(フェイズ2で再導入しうる)。
 */
function renderHeader(view) {
    document.getElementById('btn-undo').disabled = !view.canUndo;
    document.getElementById('btn-redo').disabled = !view.canRedo;
    renderOccupantList(view.occupants);
}

/** 在室者リスト(設計書 6-3・11-2)。名前・接続状態・自分自身の目印を並べる。 */
function renderOccupantList(occupants) {
    const box = document.getElementById('occupant-list');
    box.innerHTML = '';
    for (const occupant of occupants || []) {
        const badge = document.createElement('span');
        const tone = occupant.connected ? 'text-bg-secondary' : 'text-bg-dark';
        badge.className = `badge ${tone}`;
        badge.textContent = occupant.displayName
            + (occupant.self ? '(自分)' : '')
            + (occupant.connected ? '' : ' ・切断中');
        box.appendChild(badge);
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
 * B席上段(20b 2-8): 左にミニチップ [山][墓][消][禁][確]、右端にリーダー+ウェポン合体タイル。
 *
 * ★「公」(REVEAL)はチップから外した。共有ゾーンになりセンターラインが表示を兼ねるためである。
 * ★リーダーを右端に置くのは自席と同じ側であり、左右対称にはしない(確定事項Q4)。
 * 目線が左右に往復しないほうが速い。
 */
function renderOpponentTop(view) {
    const el = document.getElementById('seat-opponent-top');
    el.innerHTML = '';
    const seat = view.seatB;

    const bar = document.createElement('div');
    bar.className = 'd-flex gap-1';
    for (const zoneName of ['DECK', 'TRASH', 'LOST', 'TABOO', 'PRIVATE']) {
        const count = (seat.zones[zoneName] || []).length;
        const chip = document.createElement('div');
        chip.className = 'zone-pile-mini';
        chip.title = ZONE_LABELS[zoneName];
        chip.textContent = `${ZONE_LABELS[zoneName][0]}${count}`;
        chip.addEventListener('click', () => openZoneBand('B', zoneName));
        registerDropTarget(chip, 'B', zoneName);
        bar.appendChild(chip);
    }
    el.appendChild(bar);

    const leaderTile = createLeaderTile(seat);
    leaderTile.classList.add('ms-auto');
    el.appendChild(leaderTile);
    cardLocation.set(seat.leader ? seat.leader.instanceId : null, { seatId: 'B', zone: 'LEADER' });
}

/** Bミニオン行(折り返し解消。設計書0の指摘2) */
function renderOpponentMinions(view) {
    const el = document.getElementById('seat-opponent-minions');
    el.innerHTML = '';
    const fieldRow = document.createElement('div');
    fieldRow.className = 'minion-row';
    fieldRow.dataset.seat = 'B';
    fieldRow.dataset.zone = 'FIELD';
    renderStackRow(fieldRow, view.seatB, 'FIELD', 6); // ★2-9: 7→6
    el.appendChild(fieldRow);
}

/** Aミニオン行 */
function renderSelfMinions(view) {
    const el = document.getElementById('seat-self-minions');
    el.innerHTML = '';
    const fieldRow = document.createElement('div');
    fieldRow.className = 'minion-row';
    fieldRow.dataset.seat = 'A';
    fieldRow.dataset.zone = 'FIELD';
    renderStackRow(fieldRow, view.seatA, 'FIELD', 6); // ★2-9: 7→6
    el.appendChild(fieldRow);
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
        // 手札と同じ考え方で、溢れるときだけ幅を詰める(1枚あたり最小45px)
        const width = cards.length > 4 ? Math.max(45, Math.floor(380 / cards.length)) : 90;
        for (const card of cards) {
            row.appendChild(createHandCard(card, width, null, zoneName));
            // ★共有ゾーンのカードは席を持たない。seatId は null で索引に入れる
            cardLocation.set(card.instanceId, { seatId: null, zone: zoneName });
        }
        half.appendChild(row);
    }

    registerDropTarget(half, null, zoneName);
    return half;
}

/**
 * 右列のパイル群(20b 2-6)。上段[禁忌][山札][確認] / 下段[消滅][墓地]。
 *
 * ★公開(REVEAL)はセンターラインへ移ったためパイルではなくなった。
 * 代わりに新ゾーンの確認(PRIVATE)が入る。
 */
function renderPiles(view) {
    const el = document.getElementById('pile-grid');
    el.innerHTML = '';
    const seat = view.seatA;
    for (const zoneNames of [['TABOO', 'DECK', 'PRIVATE'], ['LOST', 'TRASH']]) {
        const row = document.createElement('div');
        row.className = 'manual-pile-row';
        for (const zoneName of zoneNames) {
            row.appendChild(
                createCardPile('A', zoneName, seat.zones[zoneName] || [], view.backImageId));
        }
        el.appendChild(row);
    }
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
    const imageId = pile.length === 0 ? null : (hidden ? backImageId : (top ? top.imageId : null));
    if (imageId) {
        const img = document.createElement('img');
        img.src = `/cards/${imageId}.png`;
        img.loading = 'lazy';
        face.appendChild(img);
    } else {
        face.classList.add('manual-pile-blank');
        face.textContent = pile.length === 0 ? '' : ((top && top.name) || '(画像なし)');
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
        box.addEventListener('click', () => send('draw', { seat: seatId, count: 1 }));
        box.addEventListener('contextmenu', (e) => { e.preventDefault(); openDeckFullscreen(seatId); });
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
        // ★18c: 左クリックで帯を開く(4-6)。最上段の拡大は右クリックへ寄せた
        // (場のカードの右クリック規約と揃える。マスター確認済み)。
        box.addEventListener('click', () => openZoneBand(seatId, zoneName));
        box.addEventListener('contextmenu', (e) => {
            e.preventDefault();
            if (pile.length > 0) {
                setZoom(pile[pile.length - 1]);
            } else {
                showTransientNotice(ZONE_LABELS[zoneName] + 'は空です');
            }
        });
    }

    return box;
}

/**
 * マナ行(20b 1-2 の6)。★リーダー行を廃止したため、リーダー+ウェポン合体タイルは
 * この行の右端へ移した。相手側と同じ側(右)に置く(確定事項Q4)。
 */
function renderManaRow(view) {
    const el = document.getElementById('seat-self-mana-row');
    el.innerHTML = '';
    const seat = view.seatA;

    const outer = document.createElement('div');
    outer.className = 'manual-mana-outer';

    const left = document.createElement('div');
    left.className = 'manual-mana-left';

    const header = document.createElement('div');
    header.className = 'small text-muted mb-1';
    header.textContent = `マナ MP ${seat.mp}`;
    left.appendChild(header);

    const wrap = document.createElement('div');
    wrap.className = 'mana-strips';

    const manaCards = seat.zones.MANA || [];
    const faceUpCards = manaCards.filter((c) => !c.faceDown);
    const faceDownCards = manaCards.filter((c) => c.faceDown);

    wrap.appendChild(createManaStrip('表', faceUpCards, 'A', false));
    wrap.appendChild(createManaStrip('裏', faceDownCards, 'A', true));

    left.appendChild(wrap);
    outer.appendChild(left);
    outer.appendChild(createLeaderTile(seat));
    el.appendChild(outer);
    cardLocation.set(seat.leader ? seat.leader.instanceId : null, { seatId: 'A', zone: 'LEADER' });

    for (const card of manaCards) {
        cardLocation.set(card.instanceId, { seatId: seat.id, zone: 'MANA' });
    }

    // ★重ね表示は実測幅で計算するため、DOMに載ってから最後に適用する(2-3)
    applyManaOverlap(wrap);
}

/** マナのストリップ1つ(表 or 裏)。ストリップ全体がドロップ対象(設計書2-3) */
function createManaStrip(label, cards, seatId, faceDown) {
    const strip = document.createElement('div');
    strip.className = 'mana-strip' + (faceDown ? ' mana-strip-down' : ' mana-strip-up');

    const label_ = document.createElement('div');
    label_.className = 'mana-strip-label small text-muted';
    label_.textContent = label;
    strip.appendChild(label_);

    const track = document.createElement('div');
    track.className = 'mana-strip-track';
    registerDropTarget(track, seatId, 'MANA', null, faceDown);

    for (const card of cards) {
        track.appendChild(createManaTile(card, seatId));
    }

    strip.appendChild(track);
    return strip;
}

/** マナのミニタイル(64×88。文明色+カード名。設計書2-3) */
function createManaTile(card, seatId) {
    const chip = document.createElement('div');
    chip.className = 'mana-tile' + (card.tapped ? ' tapped' : '') + (card.faceDown ? ' face-down' : '');
    chip.dataset.instanceId = card.instanceId;
    chip.draggable = true;

    if (card.civilization && !card.faceDown) {
        const bg = civColor(card.civilization);
        chip.style.background = bg;
        chip.style.color = textColorFor(bg);
    }

    const name = document.createElement('div');
    name.className = 'mana-tile-name';
    name.textContent = card.faceDown ? '' : (card.name || '');
    chip.appendChild(name);
    chip.title = card.faceDown ? '裏向き' : (card.name || '');

    if (selected.has(card.instanceId)) {
        chip.classList.add('manual-tile-selected');
    }

    chip.addEventListener('dragstart', (e) => onDragStart(e, card, seatId, 'MANA'));
    chip.addEventListener('click', (e) => onCardClick(e, card, seatId, 'MANA'));
    chip.addEventListener('contextmenu', (e) => { e.preventDefault(); setZoom(card); });
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
        row.appendChild(createHandCard(card, width, 'A', 'HAND'));
        cardLocation.set(card.instanceId, { seatId: 'A', zone: 'HAND' });
    }
    registerDropTarget(row, 'A', 'HAND');
    el.appendChild(row);
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

/**
 * リーダー+ウェポン合体タイル(20b 2-2)。
 *
 * <h3>★リーダータイル自体が WEAPON ゾーンのドロップ先である</h3>
 * 「リーダーにカードを落とす=装備」という一文で説明できる形にした。
 * 従来の110px幅のウェポン専用スロットは廃止し、装備中のウェポンは
 * タイル右下に重なるミニタイルとして表示する。
 *
 * <h3>★装備済みでも落とせる(旧・拒否規約の撤回)</h3>
 * 装備の有無でドロップの当たり判定が変わると人間に説明できない。
 * 古いウェポンの後始末はサーバが行う
 * ({@code ManualOperationService.replaceEquippedWeapon})。
 */
function createLeaderTile(seat) {
    const tile = document.createElement('div');
    tile.className = 'leader-card manual-leader-tile';
    tile.dataset.seat = seat.id;
    tile.dataset.zone = 'WEAPON';
    registerDropTarget(tile, seat.id, 'WEAPON');
    if (!seat.leader) {
        tile.textContent = '(未読込)';
        appendWeaponMini(tile, seat);
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
        openLpModal(seat.id, seat.lp); // ★20a 2-4: prompt() を廃止しモーダル化
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
    appendWeaponMini(tile, seat);
    return tile;
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

    if (card.imageId && !card.faceDown) {
        const img = document.createElement('img');
        img.src = `/cards/${card.imageId}.png`;
        img.loading = 'lazy';
        mini.appendChild(img);
    } else {
        mini.classList.add('manual-weapon-mini-blank');
        mini.textContent = card.faceDown ? '裏' : (card.name || '?');
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
    const seat = seatId === undefined ? 'A' : seatId;
    const zone = zoneName === undefined ? 'HAND' : zoneName;
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

    wrap.addEventListener('dragstart', (e) => onDragStart(e, card, seat, zone));
    wrap.addEventListener('click', (e) => onCardClick(e, card, seat, zone));
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
    // ★20b: センターライン(共有ゾーン)のカードも手札と同じく拡大にする。
    //   置いてある札を確認するのが主な用途であり、タップの対象ではない。
    if (zone === 'HAND' || SHARED_ZONES.has(zone)) {
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
    if (card.imageId) {
        const img = document.createElement('img');
        img.src = `/cards/${card.imageId}.png`;
        img.style.maxWidth = '100%';
        img.style.maxHeight = '100%';
        panel.appendChild(img);
        if (card.faceDown) {
            const badge = document.createElement('div');
            badge.className = 'manual-facedown-badge';
            badge.textContent = '裏向き';
            panel.appendChild(badge);
        }
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
    let ids;
    if (selected.has(card.instanceId) && selected.size > 1) {
        ids = [...selected];
    } else {
        ids = [card.instanceId];
    }
    e.dataTransfer.setData('text/plain', JSON.stringify({ cardIds: ids, seatId, zone }));
    e.dataTransfer.effectAllowed = 'move';

    const timer = setTimeout(() => {
        document.body.classList.add('manual-drag-active');
    }, 0);
    document.addEventListener('dragend', () => {
        clearTimeout(timer);
        document.body.classList.remove('manual-drag-active');
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
    const seat = lpModalSeatId === 'A' ? view.seatA : view.seatB;
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
    fields.appendChild(statInput('ATK', card.attack, (value) => {
        send('stat', { cardId: card.instanceId, attack: value });
    }));

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
    // ★20b: 共有ゾーンは席ではなく view.shared から読む(3-2)。
    //   センターラインが常に中身を見せているため通常は開かないが、
    //   ドロップ先として使われる以上、帯からも到達できる状態を保っておく。
    const items = SHARED_ZONES.has(activeOverlay.zoneName)
        ? ((latestView.shared || {})[activeOverlay.zoneName] || [])
        : ((activeOverlay.seatId === 'A' ? latestView.seatA : latestView.seatB)
            .zones[activeOverlay.zoneName] || []);
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
