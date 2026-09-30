// 相手の行動(デモ用)。1操作ずつ steps を返す。本物の QTE では相手は人間であり、ここは配信に相当する。
import { charge, play, attack, nextPhase, endTurn, playReason, spellTargets, canAttack, find } from './rules.js';

const byAtk = (state, ids) => ids.map((id) => find(state, id).entity).sort((a, b) => b.atk - a.atk);

function chooseSpellTarget(state, def) {
  if (def.kind === 'minion') return { ok: true, target: null };
  const ids = spellTargets(state, 'enemy', def);
  if (def.target === 'none') return { ok: true, target: null };
  if (!ids.length) return { ok: false };
  const units = byAtk(state, ids);
  if (def.effect === 'fireball') {
    const kill = units.find((u) => u.hp <= def.value);
    return { ok: true, target: (kill || units[0]).uid };
  }
  return { ok: true, target: units[0].uid };
}

function worth(state, def) {
  const me = state.sides.enemy, foe = state.sides.player;
  if (def.kind === 'minion') return 10 + def.cost;
  if (def.effect === 'heal') return me.leader.hp <= 14 ? 12 : -1;
  if (def.effect === 'chain' && !def.hitsLeader) return foe.board.length >= 2 ? 14 : -1;
  if (def.effect === 'buff') return me.board.length ? 6 : -1;
  return 8;
}

/** 相手のターンの操作を、1つずつ実行して steps を返す */
export function* enemyTurn(state) {
  const sd = state.sides.enemy;
  if (sd.hand.length > 2) {
    const cheap = [...sd.hand].sort((a, b) => a.def.cost - b.def.cost)[0];
    yield charge(state, 'enemy', cheap.uid);
  }
  yield nextPhase(state, 'enemy'); // → メイン
  for (let guard = 0; guard < 6 && !state.over; guard++) {
    const cands = sd.hand
      .filter((c) => !playReason(state, 'enemy', c.uid))
      .map((c) => ({ c, w: worth(state, c.def), t: chooseSpellTarget(state, c.def) }))
      .filter((x) => x.w > 0 && x.t.ok)
      .sort((a, b) => b.w - a.w);
    if (!cands.length) break;
    const { c, t } = cands[0];
    yield play(state, 'enemy', c.uid, t.target);
  }
  if (state.over) return;
  yield nextPhase(state, 'enemy'); // → バトル
  for (const u of [...sd.board]) {
    if (state.over) return;
    if (!sd.board.includes(u) || !canAttack(state, u.uid)) continue;
    const foe = state.sides.player.board;
    const kill = foe.filter((f) => f.hp <= u.atk && f.atk < u.hp).sort((a, b) => b.atk - a.atk)[0];
    yield attack(state, u.uid, kill ? kill.uid : state.sides.player.leader.uid);
  }
  if (state.over) return;
  yield endTurn(state, 'enemy');
}
