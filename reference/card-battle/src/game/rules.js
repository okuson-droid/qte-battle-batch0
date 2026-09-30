// ゲームのルール。DOM にも演出にも依存しない。
// 各操作は state を即座に更新し、「何が起きたか」をイベントの配列で返す。
// 画面側（view/director.js）はこのイベントを順に再生するだけでよい。
import { randomCard } from './cards.js';

export const MAX_BOARD = 5;
export const MAX_HAND = 7;
export const HERO_HP = 20;
export const SIDES = ['player', 'enemy'];
export const other = (side) => (side === 'player' ? 'enemy' : 'player');
export const heroUid = (side) => `hero:${side}`;

/** ルール違反の操作。画面側はこれを受けたら操作を取り消す */
export class RuleError extends Error {}

/**
 * @param {object} [o]
 * @param {() => number} [o.rng]  乱数（テストで固定するため差し替え可能）
 */
export function createGame({ rng = Math.random, heroHp = HERO_HP } = {}) {
  const side = (name, s) => ({ hero: { uid: heroUid(s), name, hp: heroHp, maxHp: heroHp }, board: [], hand: [] });
  return {
    rng,
    nextId: 1,
    turn: 'player',
    over: false,
    winner: null,
    sides: { player: side('あなた', 'player'), enemy: side('敵リーダー', 'enemy') },
  };
}

const newUid = (state) => `c${state.nextId++}`;

/* ---------------- 参照 ---------------- */

/** uid から盤面のミニオンまたはリーダーを探す */
export function findEntity(state, uid) {
  for (const s of SIDES) {
    const sd = state.sides[s];
    if (sd.hero.uid === uid) return { entity: sd.hero, side: s, hero: true };
    const unit = sd.board.find((u) => u.uid === uid);
    if (unit) return { entity: unit, side: s, hero: false };
  }
  return null;
}

export const boardFull = (state, side) => state.sides[side].board.length >= MAX_BOARD;

export function canAttack(state, uid) {
  const f = findEntity(state, uid);
  return !!f && !f.hero && !state.over && f.side === state.turn && !f.entity.exhausted && f.entity.atk > 0;
}

export function attackTargets(state, uid) {
  const f = findEntity(state, uid);
  if (!f || f.hero) return [];
  const foe = state.sides[other(f.side)];
  return [...foe.board.map((u) => u.uid), foe.hero.uid];
}

export function spellTargets(state, side, def) {
  const me = state.sides[side], foe = state.sides[other(side)];
  const units = (sd) => sd.board.map((u) => u.uid);
  if (def.target === 'enemy') return [...units(foe), foe.hero.uid];
  if (def.target === 'ally') return [...units(me), me.hero.uid];
  if (def.target === 'allyMinion') return units(me);
  return [];
}

/** 呪文を今使えるか（対象が必要なのに対象がいない場合は使えない） */
export function canCast(state, side, def) {
  return def.target === 'none' || spellTargets(state, side, def).length > 0;
}

const handCard = (state, side, uid) => {
  const i = state.sides[side].hand.findIndex((c) => c.uid === uid);
  if (i < 0) throw new RuleError('手札にないカードです');
  return i;
};

const assertTurn = (state, side) => {
  if (state.over) throw new RuleError('ゲームは終了しています');
  if (state.turn !== side) throw new RuleError('相手のターンです');
};

/* ---------------- 操作 ---------------- */

/** 1 枚引く。def を省略すると山札（ランダム）から */
export function drawCard(state, side, def = randomCard(state.rng)) {
  const hand = state.sides[side].hand;
  if (hand.length >= MAX_HAND) return [];
  const card = { uid: newUid(state), def };
  hand.push(card);
  return [{ type: 'draw', side, card: { ...card } }];
}

/**
 * ミニオンを盤面に出す。
 * @param {{uid?:string, def?:object}} source  手札のカード uid、または手札を持たない側（敵 AI）用の定義
 * @param {number} [index]  盤面の何番目に置くか
 */
export function playMinion(state, side, source, index = null) {
  assertTurn(state, side);
  if (boardFull(state, side)) throw new RuleError('盤面がいっぱいです');
  let def, uid, fromHand = null;
  if (source.uid) {
    const hand = state.sides[side].hand, i = handCard(state, side, source.uid);
    ({ def, uid } = hand[i]);
    if (def.kind !== 'minion') throw new RuleError('ミニオンではありません');
    hand.splice(i, 1);
    fromHand = uid;
  } else {
    def = source.def;
    uid = newUid(state);
  }
  const board = state.sides[side].board;
  const at = index == null ? board.length : Math.max(0, Math.min(index, board.length));
  const unit = { uid, def, side, atk: def.atk, hp: def.hp, maxHp: def.hp, exhausted: false };
  board.splice(at, 0, unit);
  return [{ type: 'summon', side, unit: { ...unit }, index: at, fromHand }];
}

/** 攻撃。お互いの攻撃力ぶんダメージを与え合う（リーダーは反撃しない） */
export function attack(state, attackerUid, targetUid) {
  const a = findEntity(state, attackerUid);
  if (!a) throw new RuleError('攻撃するミニオンがいません');
  assertTurn(state, a.side);
  if (!canAttack(state, attackerUid)) throw new RuleError('このミニオンは攻撃できません');
  if (!attackTargets(state, attackerUid).includes(targetUid)) throw new RuleError('その対象は攻撃できません');
  const t = findEntity(state, targetUid), att = a.entity, tgt = t.entity;
  att.exhausted = true;
  const hits = [damage(tgt, att.atk)];
  if (!t.hero && tgt.atk > 0) hits.push(damage(att, tgt.atk));
  return [{ type: 'attack', attacker: att.uid, target: tgt.uid, power: att.atk, hits }, ...resolveDeaths(state)];
}

function damage(entity, amount) {
  entity.hp -= amount;
  return { uid: entity.uid, amount, hp: entity.hp, maxHp: entity.maxHp };
}

/**
 * 呪文を使う。
 * @param {{uid?:string, def?:object}} source
 * @param {string|null} targetUid  対象（対象なしの呪文は null）
 */
export function castSpell(state, side, source, targetUid = null) {
  assertTurn(state, side);
  let def, fromHand = null;
  if (source.uid) {
    const hand = state.sides[side].hand, i = handCard(state, side, source.uid);
    def = hand[i].def;
    if (def.kind !== 'spell') throw new RuleError('呪文ではありません');
    if (def.target !== 'none' && !spellTargets(state, side, def).includes(targetUid)) throw new RuleError('その対象は選べません');
    hand.splice(i, 1);
    fromHand = source.uid;
  } else {
    def = source.def;
  }
  const foe = state.sides[other(side)];
  let results;
  switch (def.effect) {
    case 'fireball':
      results = [{ kind: 'damage', ...damage(findEntity(state, targetUid).entity, def.value) }];
      break;
    case 'chain': {
      const victims = foe.board.length ? [...foe.board] : [foe.hero];
      results = victims.map((v) => ({ kind: 'damage', ...damage(v, def.value) }));
      break;
    }
    case 'heal': {
      const e = findEntity(state, targetUid).entity, before = e.hp;
      e.hp = Math.min(e.maxHp, e.hp + def.value);
      results = [{ kind: 'heal', uid: e.uid, amount: e.hp - before, hp: e.hp, maxHp: e.maxHp }];
      break;
    }
    case 'buff': {
      const e = findEntity(state, targetUid).entity;
      e.atk += def.value; e.hp += def.value; e.maxHp += def.value;
      results = [{ kind: 'buff', uid: e.uid, value: def.value, atk: e.atk, hp: e.hp, maxHp: e.maxHp }];
      break;
    }
    default:
      throw new RuleError(`未実装の呪文: ${def.effect}`);
  }
  return [{ type: 'spell', side, def, fromHand, targets: results.map((r) => r.uid), results }, ...resolveDeaths(state)];
}

/** 体力 0 以下を盤面から取り除き、リーダーが倒れていればゲーム終了 */
function resolveDeaths(state) {
  const events = [];
  for (const s of SIDES) {
    const sd = state.sides[s];
    for (const u of sd.board.filter((x) => x.hp <= 0)) events.push({ type: 'death', uid: u.uid, side: s });
    sd.board = sd.board.filter((x) => x.hp > 0);
  }
  const fallen = SIDES.filter((s) => state.sides[s].hero.hp <= 0);
  if (fallen.length && !state.over) {
    fallen.forEach((s) => events.push({ type: 'death', uid: heroUid(s), side: s, hero: true }));
    state.over = true;
    state.winner = fallen.length === 2 ? null : other(fallen[0]);
    events.push({ type: 'gameOver', winner: state.winner });
  }
  return events;
}

/** ターン終了。次の手番の側のミニオンを攻撃可能に戻し、プレイヤーの手番ならカードを 1 枚引く */
export function endTurn(state) {
  if (state.over) throw new RuleError('ゲームは終了しています');
  state.turn = other(state.turn);
  state.sides[state.turn].board.forEach((u) => { u.exhausted = false; });
  const events = [{ type: 'turn', side: state.turn }];
  if (state.turn === 'player') events.push(...drawCard(state, 'player'));
  return events;
}

