/**
 * 検証用のビュー(ManualGameView の JSON 相当)を組み立てる。
 * ★フィールド名はサーバの record に合わせてある(ManualGameView / ManualSeatView /
 *   ManualCardView)。ここがずれると検証が「動いているつもり」になる。
 */

const ZONES = ['DECK', 'HAND', 'MANA', 'FIELD', 'WEAPON', 'TRASH', 'LOST', 'TABOO', 'PRIVATE'];

function card(id, name, overrides = {}) {
  return {
    instanceId: id,
    cardId: 'c-' + id,
    name,
    imageId: 'img-' + id,
    civilization: 'WATER',
    type: 'MINION',
    cost: 3,
    printedAttack: 2,
    printedHp: 3,
    attack: 2,
    hp: 3,
    tapped: false,
    faceDown: false,
    used: false,
    labels: [],
    stackSize: 1,
    materials: [],
    ...overrides,
  };
}

function emptyZones() {
  const zones = {};
  for (const z of ZONES) zones[z] = [];
  return zones;
}

function seat(id) {
  return {
    id,
    lp: 20,
    mp: 0,
    deckLoaded: true,
    deckName: 'テストデッキ',
    leader: card(id + '-leader', 'リーダー' + id, { type: 'LEADER', attack: null, hp: null }),
    zones: emptyZones(),
    // ★Batch 21a で足った項目。counts は全ゾーンぶん常に届く(3-3)
    counts: emptyCounts(),
    manaFaceDownCount: 0,
  };
}

function emptyCounts() {
  const counts = {};
  for (const z of ZONES) counts[z] = 0;
  return counts;
}

/**
 * ★zones に置いた中身から counts を作り直す(21b)。
 * サーバは counts を必ず全ゾーンぶん送るため、fixture でもズレないよう最後に揃える。
 */
function syncCounts(seatView) {
  for (const z of ZONES) {
    seatView.counts[z] = (seatView.zones[z] || []).length;
  }
  seatView.manaFaceDownCount = (seatView.zones.MANA || []).filter((c) => c.faceDown).length;
  return seatView;
}

function occupant(displayName, seatId, overrides = {}) {
  return {
    displayName,
    role: seatId ? 'PLAYER' : 'SPECTATOR',
    seatId,
    spectatorView: seatId ? null : 'PUBLIC_ONLY',
    connected: true,
    disconnectSecondsLeft: null,
    self: false,
    ...overrides,
  };
}

/**
 * 開始シーケンスのビュー(★Batch 23。ManualStartView 相当)。
 * ★既定は IDLE(何も起きていない状態)である。個々の検証が必要なぶんだけ上書きする。
 */
function startState(overrides = {}) {
  return {
    phase: 'IDLE',
    locking: false,
    firstSeat: null,
    orderChooser: null,
    // ★23 hotfix: 「自分が先攻をとる」の「自分」が指す席。サーバが決める
    //   (全公開部屋でデッキが1つだけならその席になる)
    subjectSeat: 'A',
    canBegin: false,
    canChooseMethod: false,
    canChooseOrder: false,
    mulliganSeats: [],
    mulliganDone: [],
    myMulliganSeats: [],
    waiting: null,
    pureElement: true,
    ...overrides,
  };
}

function baseView() {
  const view = {
    roomId: 'TESTRM',
    occupantId: 'occ-test',
    backImageId: 'back',
    // ★21a で足った部屋・視点の項目。既定は 20c までと同じ全公開部屋・席A
    roomType: 'OPEN',
    roomName: 'テスト部屋',
    spectatorAllowed: true,
    viewerSeat: 'A',
    viewerRole: 'PLAYER',
    spectatorView: null,
    turnNumber: 1,
    phase: 'DRAW',
    seatA: seat('A'),
    seatB: seat('B'),
    shared: { PLAY: [], REVEAL: [] },
    // ★Batch 23: 開始シーケンス。サーバが「自分は今何を押せるか」まで載せる(設計書9章)
    start: startState(),
    occupants: [occupant('テスト', 'A', { self: true })],
    log: [
      { seq: 1, time: '10:00:00', text: 'ログ1' },
      { seq: 2, time: '10:00:01', text: 'ログ2' },
      { seq: 3, time: '10:00:02', text: 'ログ3' },
      { seq: 4, time: '10:00:03', text: 'ログ4' },
    ],
    // ★Batch 35: 勝敗宣言(ManualDeclarationView 相当)。既定は「まだ決着していない」
    declarations: [],
    // ★Batch 38: 儀式(ManualRiteView 相当)。
    //   既定は空。宣言と同じく<b>seq が増えたときだけ</b>再生される
    rites: [],
    canUndo: true,
    canRedo: false,
  };
  view.seatA.zones.HAND = [card('h1', '手札1'), card('h2', '手札2')];
  view.seatA.zones.DECK = [card('d1', '山札の一番上'), card('d2', '山札2'), card('d3', '山札3')];
  view.seatA.zones.TRASH = [card('t1', '墓地1')];
  view.seatA.zones.TABOO = [card('tb1', '禁忌1')];
  view.seatA.zones.FIELD = [card('f1', '場1')];
  view.seatB.zones.DECK = [card('bd1', 'B山札1')];
  syncCounts(view.seatA);
  syncCounts(view.seatB);
  return view;
}

/**
 * 対戦部屋のビュー(★Batch 21b。設計書 3-3)。
 *
 * ★相手席の非公開ゾーンは zones に<b>キーごと現れない</b>。
 * 「空配列を送る」のではなくキーを作らないのが 21a の「届かないものは漏れない」の形であり、
 * fixture もそれを正確に再現していなければ、count-only の描画を検証したことにならない。
 *
 * @param viewerSeat 閲覧者の席。null なら観戦者
 */
function versusView(viewerSeat = 'A') {
  const view = baseView();
  view.roomType = 'VERSUS';
  view.roomName = 'テスト対戦';
  view.viewerSeat = viewerSeat;
  view.viewerRole = viewerSeat ? 'PLAYER' : 'SPECTATOR';
  view.spectatorView = viewerSeat ? null : 'PUBLIC_ONLY';
  view.canRedo = false;
  view.occupants = [
    occupant('あかり', 'A', { self: viewerSeat === 'A' }),
    occupant('ばんり', 'B', { self: viewerSeat === 'B' }),
    occupant('みるひと', null, { self: viewerSeat === null }),
  ];

  // 相手席(閲覧者以外)の非公開ゾーンを落とす。counts は残す
  for (const seatView of [view.seatA, view.seatB]) {
    if (seatView.id === viewerSeat) continue;
    seatView.zones.HAND = [card(seatView.id + 'h', '見えないはず')];
    seatView.zones.DECK = [card(seatView.id + 'd1', '見えないはず'),
      card(seatView.id + 'd2', '見えないはず')];
    seatView.zones.TABOO = [card(seatView.id + 'tb', '見えないはず')];
    seatView.zones.PRIVATE = [];
    syncCounts(seatView);
    // ★ここでキーを消す。counts だけが残る
    delete seatView.zones.HAND;
    delete seatView.zones.DECK;
    delete seatView.zones.TABOO;
    delete seatView.zones.PRIVATE;
    seatView.zones.MANA = [];
    seatView.counts.MANA = 3;
    seatView.manaFaceDownCount = 3;
  }
  return view;
}

/** 部屋一覧 API の1件(RoomSummary 相当)。席選択ゲートの入力になる */
function roomSummary(overrides = {}) {
  return {
    roomId: 'TESTRM',
    roomName: 'テスト対戦',
    type: 'VERSUS',
    spectatorAllowed: true,
    locked: false,
    seatAName: null,
    seatBName: null,
    spectatorCount: 0,
    ...overrides,
  };
}

/**
 * 勝敗宣言1件(★Batch 35。ManualDeclarationView 相当)。
 *
 * ★{@code label} はサーバの {@code ManualDeclaration.getDisplayName()} と同じ文字である。
 * fixture が勝手な文言を持つと「クライアントが label を使っているか」を検証できない
 * (自前の表を使っていても通ってしまう)。
 *
 * ★{@code seq} は<b>配ったログ行のどれか</b>を指すこと。サーバは同じ範囲から作る(2-3)。
 */
const DECLARATION_LABELS = { WIN: '勝利', LOSE: '敗北', DRAW: '引き分け', CONCEDE: '投了' };

function declaration(seq, seat, kind = 'WIN') {
  return { seq, seat, declaration: kind, label: DECLARATION_LABELS[kind] };
}

/**
 * 儀式1件(★Batch 38。ManualRiteView 相当)。
 *
 * ★中身の形は `ManualLogRite` に合わせてある。ここがずれると
 * 「動いているつもり」の検証になる(このファイル冒頭の注意)。
 * ★{@code label} はサーバが作る文言である。fixture が勝手な文言を持つと、
 * クライアントが label を使っているかを検証できない(35 の declaration と同じ理由)。
 *
 * @param seq  配ったログ行のどれかを指す通し番号
 * @param kind 'DICE' / 'DEAL' / 'MULLIGAN' / 'SHUFFLE'
 */
function rite(seq, kind, overrides = {}) {
  return {
    seq,
    rite: {
      kind,
      diceA: null,
      diceB: null,
      winner: null,
      label: null,
      dealt: [],
      // ★Batch 38 追補: ピュア・エレメントが渡った席(マリガン完了時だけ入る)
      pureSeat: null,
      ...overrides,
    },
  };
}

/** 山札のシャッフル(★38 追補)。★員数は 0 / 0 で、運ぶのは席だけである */
function shuffleRite(seq, seat = 'A') {
  return rite(seq, 'SHUFFLE', { dealt: [{ seat, back: 0, drew: 0 }] });
}

/** 初期ドローの儀式(先攻4 / 後攻5)。★総合ルール 2-5 の枚数そのものである */
function dealRite(seq, firstSeat = 'A', overrides = {}) {
  return rite(seq, 'DEAL', {
    dealt: [
      { seat: 'A', back: 0, drew: firstSeat === 'A' ? 4 : 5 },
      { seat: 'B', back: 0, drew: firstSeat === 'B' ? 4 : 5 },
    ],
    ...overrides,
  });
}

module.exports = {
  baseView, versusView, roomSummary, card, occupant, emptyZones, syncCounts, startState,
  declaration, rite, dealRite, shuffleRite,
};

// ---------------------------------------------------------------------------
// ★★Batch 42: 通常モード(自動モード)のビュー。
//   フィールド名はサーバの record(GameView / PlayerView / CardView / MinionView)に
//   合わせてある。ここがずれると検証が「動いているつもり」になる(冒頭の注意と同じ)。
// ---------------------------------------------------------------------------

function autoCard(cardId, name, overrides = {}) {
  return {
    cardId, name,
    type: 'MINION', civilization: 'FIRE',
    cost: 3, effectiveCost: null, attack: 2, hp: 3,
    keywords: [], text: '',
    targets: [], canSpecialSummon: false, specialTargets: [],
    specialSummonText: null, combinedTotal: 0, enhancedCost: 0, enhancedText: null,
    // ★Batch 47: 「効果未実装」の印。判定はサーバが済ませて真偽値だけを送る
    effectUnimplemented: false,
    ...overrides,
  };
}

function autoMinion(instanceId, name, overrides = {}) {
  return {
    instanceId, cardId: 'QTE-M-FIRE-6', name,
    attack: 2, currentHp: 3, maxHp: 3, keywords: [],
    canAttackMinion: false, canAttackLeader: false,
    frozen: false, tapped: false, canUseAbility: false, abilityText: null,
    effectUnimplemented: false,
    ...overrides,
  };
}

function autoPlayer(overrides = {}) {
  return {
    displayName: 'テスト', leaderName: '傷痕の闘帝', leaderCardId: 'QTE-M-FIRE-15',
    lp: 20, deckCount: 30, handCount: 0, hand: [],
    availableMp: 5, totalMana: 5, manaZone: [],
    minions: [], trashCount: 0, trashCardNames: [], trash: [],
    lostCount: 0, lostCardNames: [], lost: [], tabooCount: 0, taboo: [],
    manaCharged: false, cannotUseCards: false, mulliganDone: true,
    leaderText: '【起動：1】自分のリーダーに1ダメージ。そうしたら1枚ドローする',
    deckName: 'テストデッキ', weaponName: null, weaponCardId: null, weaponAttack: null,
    leaderCanAttack: false, leaderFrozen: false,
    leaderAbility: null, revealedCards: [], pendingChoice: null,
    ...overrides,
  };
}

function autoView(overrides = {}) {
  return {
    roomId: 'TESTRM', status: 'PLAYING', turnNumber: 1,
    phase: 'MAIN', phaseDisplay: 'メイン',
    myTurn: true, chooseOrder: false, mulligan: false, winnerName: null,
    you: autoPlayer(), opponent: autoPlayer({ displayName: 'あいて' }),
    log: [],
    ...overrides,
  };
}

module.exports.autoCard = autoCard;
module.exports.autoMinion = autoMinion;
module.exports.autoPlayer = autoPlayer;
module.exports.autoView = autoView;
