// 相手の行動（デモ用の単純な AI）。ルール関数を呼んで state を進め、発生したイベントをまとめて返す。
import { SPELLS, randomMinion } from './cards.js';
import { playMinion, castSpell, attack, canAttack, spellTargets, canCast } from './rules.js';

const pickWith = (rng, list) => list[Math.floor(rng() * list.length)];
const spell = (id) => SPELLS.find((s) => s.id === id);

/** 敵のターンを最後まで進める（ターン終了は呼び出し側で行う） */
export function runEnemyTurn(state) {
  const { rng } = state, me = state.sides.enemy, foe = state.sides.player;
  const events = [];

  if (me.board.length < 4) events.push(...playMinion(state, 'enemy', { def: randomMinion(rng) }));

  if (!state.over && rng() < 0.6) {
    const def = foe.board.length >= 2 && rng() < 0.5 ? spell('chain') : spell('fireball');
    if (canCast(state, 'enemy', def)) {
      const target = def.target === 'none' ? null : pickWith(rng, spellTargets(state, 'enemy', def));
      events.push(...castSpell(state, 'enemy', { def }, target));
    }
  }

  for (const unit of [...me.board]) {
    if (state.over) break;
    if (!canAttack(state, unit.uid)) continue; // 呪文の反撃などで倒れていることがある
    const target = rng() < 0.35 || !foe.board.length ? foe.hero.uid : pickWith(rng, foe.board).uid;
    events.push(...attack(state, unit.uid, target));
  }
  return events;
}

