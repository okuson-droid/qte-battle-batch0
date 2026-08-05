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
  };
}

function baseView() {
  const view = {
    roomId: 'TESTRM',
    occupantId: 'occ-test',
    backImageId: 'back',
    turnNumber: 1,
    phase: 'DRAW',
    seatA: seat('A'),
    seatB: seat('B'),
    shared: { PLAY: [], REVEAL: [] },
    occupants: [{ displayName: 'テスト', role: 'PLAYER_A', connected: true, self: true }],
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
  return view;
}

module.exports = { baseView, card, emptyZones };
