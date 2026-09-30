// デモ用の簡易ルール。DOM にも演出にも依存しない。
// 本物の QTE ではここがサーバ(Java)であり、Batch 85a で「段(ログ行)＋出来事」を返すようになる。
// このデモはその形を先取りして、各操作が steps = [{ log, events }] を返す。
import { randomCard, cardById } from './cards.js';

export const PHASES = ['ドロー', 'アンタップ', 'マナチャージ', 'メイン', 'バトル', 'サブ', 'ターンエンド'];
export const MAX_BOARD = 6;
export const MAX_HAND = 9;
export const LP = 20;
export const other = (s) => (s === 'player' ? 'enemy' : 'player');
export const heroUid = (s) => `hero:${s}`;
const tag = (name) => `【${name}】`;

export class RuleError extends Error {}

export function createGame({ rng = Math.random } = {}) {
  const side = (s, name, ability) => ({
    leader: { uid: heroUid(s), name, hp: LP, maxHp: LP, ability },
    board: [], hand: [], mana: 0, tapped: 0, deck: 30, grave: 0, banish: 0, taboo: 1, charged: false,
  });
  return {
    rng, nextId: 1, turn: 'player', turnNo: 3, phase: 'メイン', over: false, winner: null,
    sides: {
      player: side('player', '傷痕の闘帝', '【起動：1】自分のリーダーに1ダメージ。そうしたら1枚ドローする'),
      enemy: side('enemy', '蒼海の賢者', '【起動：1】カードを1枚見て、山札の上か下に置く'),
    },
  };
}

const uid = (state) => `c${state.nextId++}`;
const mp = (sd) => sd.mana - sd.tapped;

/** 画面の数字(山札・墓地・マナなど)を、その出来事が起きた瞬間の値で持たせる */
function counts(state) {
  const c = {};
  for (const s of ['player', 'enemy']) {
    const sd = state.sides[s];
    c[s] = { deck: sd.deck, grave: sd.grave, banish: sd.banish, taboo: sd.taboo, hand: sd.hand.length, mana: sd.mana, mp: mp(sd), hp: sd.leader.hp };
  }
  return c;
}

/** 段を積む道具。step() がログ1行＝段1つを切る(裁定362 と同じ形) */
class Steps {
  constructor(state) { this.state = state; this.list = []; }
  step(log, ...events) {
    this.list.push({ log, events: events.map((e) => ({ ...e, counts: counts(this.state) })) });
    return this;
  }
}

/* ---------------- 参照 ---------------- */

export function find(state, id) {
  for (const s of ['player', 'enemy']) {
    const sd = state.sides[s];
    if (sd.leader.uid === id) return { entity: sd.leader, side: s, hero: true };
    const u = sd.board.find((x) => x.uid === id);
    if (u) return { entity: u, side: s, hero: false };
  }
  return null;
}

const handIndex = (sd, id) => {
  const i = sd.hand.findIndex((c) => c.uid === id);
  if (i < 0) throw new RuleError('手札にないカードです');
  return i;
};

export function attackReason(state, id) {
  const f = find(state, id);
  if (!f || f.hero) return '攻撃できません';
  if (state.over) return '対戦は終わっています';
  if (f.side !== state.turn) return '相手のターンです';
  if (state.phase !== 'バトル') return 'バトルフェイズで攻撃できます(「次のフェイズへ」)';
  if (f.entity.sick) return '出たターンは攻撃できません(【速攻】を除く)';
  if (f.entity.attacked) return 'このターンはもう攻撃しました';
  return null;
}
export const canAttack = (state, id) => attackReason(state, id) === null;

export function attackTargets(state, id) {
  const f = find(state, id);
  if (!f || f.hero) return [];
  const foe = state.sides[other(f.side)];
  return [...foe.board.map((u) => u.uid), foe.leader.uid];
}

export function playReason(state, side, id) {
  const sd = state.sides[side], c = sd.hand.find((x) => x.uid === id);
  if (!c) return '手札にないカードです';
  if (state.over) return '対戦は終わっています';
  if (state.turn !== side) return '相手のターンです';
  if (state.phase !== 'メイン' && state.phase !== 'サブ') return 'メインフェイズかサブフェイズで使えます';
  if (c.def.cost > mp(sd)) return `MP が足りません(コスト${c.def.cost} / MP ${mp(sd)})`;
  if (c.def.kind === 'minion' && sd.board.length >= MAX_BOARD) return '場がいっぱいです';
  if (c.def.kind === 'spell' && c.def.target !== 'none' && !spellTargets(state, side, c.def).length) return '対象がいません';
  return null;
}

export function chargeReason(state, side) {
  if (state.over) return '対戦は終わっています';
  if (state.turn !== side) return '相手のターンです';
  if (state.phase !== 'マナチャージ' && state.phase !== 'メイン') return 'マナチャージフェイズかメインフェイズでチャージできます';
  if (state.sides[side].charged) return 'マナチャージは1ターンに1回です';
  return null;
}

export function spellTargets(state, side, def) {
  const me = state.sides[side], foe = state.sides[other(side)];
  if (def.target === 'enemyMinion') return foe.board.map((u) => u.uid);
  if (def.target === 'allyMinion') return me.board.map((u) => u.uid);
  if (def.target === 'anyMinion') return [...me.board, ...foe.board].map((u) => u.uid);
  return [];
}

/* ---------------- 操作 ---------------- */

export function charge(state, side, id) {
  const r = chargeReason(state, side);
  if (r) throw new RuleError(r);
  const sd = state.sides[side], i = handIndex(sd, id), card = sd.hand[i];
  sd.hand.splice(i, 1);
  sd.mana++;
  sd.charged = true;
  const out = new Steps(state);
  out.step(side === 'player' ? `${tag(card.def.name)}をマナにチャージ` : '相手がマナにチャージ', { type: 'charge', side, uid: id });
  return out.list;
}

export function play(state, side, id, targetId = null, index = null) {
  const r = playReason(state, side, id);
  if (r) throw new RuleError(r);
  const sd = state.sides[side], i = handIndex(sd, id), card = sd.hand[i], def = card.def;
  if (def.kind === 'spell' && def.target !== 'none' && !spellTargets(state, side, def).includes(targetId)) throw new RuleError('その対象は選べません');
  sd.hand.splice(i, 1);
  sd.tapped += def.cost;
  const out = new Steps(state);
  if (def.kind === 'minion') {
    const unit = { uid: uid(state), def, atk: def.atk, hp: def.hp, maxHp: def.hp, sick: !def.haste, attacked: false };
    const at = index == null ? sd.board.length : Math.min(index, sd.board.length);
    sd.board.splice(at, 0, unit);
    out.step(`${tag(def.name)}を召喚`, { type: 'pay', side }, { type: 'summon', side, unit: { ...unit }, index: at, fromHand: id });
    onSummon(state, side, unit, out);
  } else {
    castSpell(state, side, def, id, targetId, out);
  }
  checkEnd(state, out);
  return out.list;
}

function onSummon(state, side, unit, out) {
  const foe = state.sides[other(side)], me = state.sides[side];
  if (unit.def.onSummon === 'zap2' && foe.board.length) {
    const t = foe.board[Math.floor(state.rng() * foe.board.length)];
    t.hp -= 2;
    out.step(`${tag(unit.def.name)}の召喚時効果:${tag(t.def.name)}に2ダメージ`,
      { type: 'zap', from: unit.uid, target: t.uid, amount: 2, hp: t.hp, maxHp: t.maxHp });
    reap(state, out);
  }
  if (unit.def.onSummon === 'genesis') {
    const victims = [...me.board, ...foe.board].filter((u) => u.uid !== unit.uid);
    if (!victims.length) return;
    out.step(`${tag(unit.def.name)}の召喚時効果:ほかのミニオンをすべて破壊`, { type: 'quake', from: unit.uid });
    for (const v of victims) v.hp = Math.min(v.hp, 0), v.doomed = true;
    reap(state, out);
  }
}

function castSpell(state, side, def, cardUid, targetId, out) {
  const me = state.sides[side], foe = state.sides[other(side)];
  let targets = [], results = [];
  if (def.effect === 'fireball') {
    const t = find(state, targetId).entity;
    t.hp -= def.value;
    targets = [t.uid];
    results = [{ kind: 'damage', uid: t.uid, amount: def.value, hp: t.hp, maxHp: t.maxHp }];
  } else if (def.effect === 'chain' && def.hitsLeader) {
    foe.leader.hp -= def.value;
    targets = [foe.leader.uid];
    results = [{ kind: 'damage', uid: foe.leader.uid, amount: def.value, hp: foe.leader.hp, maxHp: LP }];
  } else if (def.effect === 'chain') {
    for (const u of foe.board) {
      u.hp -= def.value;
      targets.push(u.uid);
      results.push({ kind: 'damage', uid: u.uid, amount: def.value, hp: u.hp, maxHp: u.maxHp });
    }
  } else if (def.effect === 'heal') {
    const before = me.leader.hp;
    me.leader.hp = Math.min(LP, me.leader.hp + def.value);
    targets = [me.leader.uid];
    results = [{ kind: 'heal', uid: me.leader.uid, amount: me.leader.hp - before || def.value, hp: me.leader.hp, maxHp: LP }];
  } else if (def.effect === 'buff') {
    const t = find(state, targetId).entity;
    t.atk += def.value; t.hp += def.value; t.maxHp += def.value;
    targets = [t.uid];
    results = [{ kind: 'buff', uid: t.uid, atk: t.atk, hp: t.hp, maxHp: t.maxHp, value: def.value }];
  } else if (def.effect === 'curse') {
    const t = find(state, targetId).entity;
    targets = [t.uid];
    results = [{ kind: 'mark', uid: t.uid }];
    if (def.banish) t.banished = true; else t.doomed = true;
  }
  me.grave++;
  out.step(`${tag(def.name)}を使用`, { type: 'pay', side }, { type: 'cast', side, def, fromHand: cardUid, targets, results });
  reap(state, out);
}

/** HP が 0 以下・破壊予定・消滅予定のミニオンを場から取り除き、段を切る */
function reap(state, out) {
  const dead = [], gone = [];
  for (const s of ['player', 'enemy']) {
    const sd = state.sides[s];
    sd.board = sd.board.filter((u) => {
      if (u.banished) { sd.banish++; gone.push({ u, s }); return false; }
      if (u.hp <= 0 || u.doomed) { sd.grave++; dead.push({ u, s }); return false; }
      return true;
    });
  }
  if (gone.length) out.step(gone.map(({ u }) => `${tag(u.def.name)}は消滅した`).join(' / '),
    ...gone.map(({ u, s }) => ({ type: 'banish', uid: u.uid, side: s })));
  if (dead.length) out.step(dead.map(({ u }) => `${tag(u.def.name)}は破壊された`).join(' / '),
    ...dead.map(({ u, s }) => ({ type: 'death', uid: u.uid, side: s })));
}

function checkEnd(state, out) {
  const dead = ['player', 'enemy'].filter((s) => state.sides[s].leader.hp <= 0);
  if (!dead.length || state.over) return;
  state.over = true;
  state.winner = dead.length === 2 ? 'draw' : other(dead[0]);
  out.step(`${dead.map((s) => tag(state.sides[s].leader.name)).join('と')}の LP が 0 になった`,
    ...dead.map((s) => ({ type: 'death', uid: heroUid(s), side: s, hero: true })));
  out.step(state.winner === 'player' ? 'あなたの勝利' : state.winner === 'enemy' ? 'あなたの敗北' : '引き分け',
    { type: 'gameOver', winner: state.winner });
}

export function attack(state, attId, tgtId) {
  const r = attackReason(state, attId);
  if (r) throw new RuleError(r);
  if (!attackTargets(state, attId).includes(tgtId)) throw new RuleError('その対象は攻撃できません');
  const a = find(state, attId).entity, t = find(state, tgtId);
  a.attacked = true;
  const hits = [];
  t.entity.hp -= a.atk;
  hits.push({ uid: t.entity.uid, amount: a.atk, hp: t.entity.hp, maxHp: t.entity.maxHp });
  if (!t.hero && t.entity.atk > 0) {
    a.hp -= t.entity.atk;
    hits.push({ uid: a.uid, amount: t.entity.atk, hp: a.hp, maxHp: a.maxHp });
  }
  const out = new Steps(state);
  out.step(`${tag(a.def.name)}が${tag(t.hero ? t.entity.name : t.entity.def.name)}を攻撃`,
    { type: 'attack', attacker: a.uid, target: t.entity.uid, power: a.atk, hits });
  reap(state, out);
  checkEnd(state, out);
  return out.list;
}

export function nextPhase(state, side) {
  if (state.over) throw new RuleError('対戦は終わっています');
  if (state.turn !== side) throw new RuleError('相手のターンです');
  const i = PHASES.indexOf(state.phase);
  if (state.phase === 'サブ' || state.phase === 'ターンエンド') return endTurn(state, side);
  state.phase = PHASES[i + 1];
  const out = new Steps(state);
  out.step(null, { type: 'phase', side, phase: state.phase, turnNo: state.turnNo });
  return out.list;
}

export function endTurn(state, side) {
  if (state.over) throw new RuleError('対戦は終わっています');
  if (state.turn !== side) throw new RuleError('相手のターンです');
  const out = new Steps(state);
  state.phase = 'ターンエンド';
  out.step(null, { type: 'phase', side, phase: state.phase, turnNo: state.turnNo });
  const next = other(side), sd = state.sides[next];
  state.turn = next;
  state.turnNo++;
  state.phase = 'ドロー';
  out.step(`ターン${state.turnNo} 開始(${next === 'player' ? 'あなた' : '相手'}の番)`,
    { type: 'turn', side: next, turnNo: state.turnNo }, { type: 'phase', side: next, phase: 'ドロー', turnNo: state.turnNo });
  drawCard(state, next, out);
  state.phase = 'アンタップ';
  sd.tapped = 0;
  sd.charged = false;
  for (const u of sd.board) { u.sick = false; u.attacked = false; }
  out.step(null, { type: 'phase', side: next, phase: 'アンタップ', turnNo: state.turnNo }, { type: 'untap', side: next });
  state.phase = 'マナチャージ';
  out.step(null, { type: 'phase', side: next, phase: 'マナチャージ', turnNo: state.turnNo });
  return out.list;
}

/** 1枚引く(デモ用の操作としても使う) */
export function drawCard(state, side, out = null, def = null) {
  const own = !out;
  out ||= new Steps(state);
  const sd = state.sides[side];
  if (sd.hand.length >= MAX_HAND || sd.deck <= 0) return own ? out.list : undefined;
  const card = { uid: uid(state), def: def || randomCard(state.rng) };
  sd.hand.push(card);
  sd.deck--;
  out.step(side === 'player' ? `${tag(card.def.name)}を引いた` : '相手がカードを1枚引いた',
    { type: 'draw', side, card: side === 'player' ? { ...card } : null });
  return own ? out.list : undefined;
}

/** 開始時の盤面(演出なしで置く) */
export function seed(state) {
  const put = (s, id, extra = {}) => {
    const def = cardById(id);
    state.sides[s].board.push({ uid: uid(state), def, atk: def.atk, hp: def.hp, maxHp: def.hp, sick: false, attacked: false, ...extra });
  };
  put('enemy', 'm-water-1');
  put('enemy', 'm-dark-1');
  put('enemy', 'm-earth-1');
  put('player', 'm-fire-1');
  put('player', 'm-light-1');
  const p = state.sides.player, e = state.sides.enemy;
  p.mana = 8; e.mana = 6;
  for (const id of ['m-fire-2', 'm-wind-1', 's-fire-1', 'm-water-1', 's-dark-1']) e.hand.push({ uid: uid(state), def: cardById(id) });
  p.deck = 24; e.deck = 25; p.grave = 3; e.grave = 4;
  p.leader.hp = 20; e.leader.hp = 18;
}
