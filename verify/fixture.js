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
    occupants: [occupant('テスト', 'A', { self: true })],
    log: [
      { seq: 1, time: '10:00:00', text: 'ログ1' },
      { seq: 2, time: '10:00:01', text: 'ログ2' },
      { seq: 3, time: '10:00:02', text: 'ログ3' },
      { seq: 4, time: '10:00:03', text: 'ログ4' },
    ],
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

module.exports = { baseView, versusView, roomSummary, card, occupant, emptyZones, syncCounts };
