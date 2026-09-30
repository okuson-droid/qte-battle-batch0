// 数値が変わったときの反応（ダメージ・回復・強化）。攻撃や呪文の着弾時に呼ぶ。
import { dur } from './core.js';
import { flashOn, popNumber } from './stage.js';
import { setHp, setStats } from './cardView.js';

export function showDamage(stage, el, amount, hp, maxHp) {
  if (amount <= 0) return;
  setHp(el, hp, maxHp);
  flashOn(el, '#ff2a1a', 450, 0.55);
  popNumber(stage, el, '-' + amount);
}

export function showHeal(stage, el, amount, hp, maxHp) {
  setHp(el, hp, maxHp);
  flashOn(el, 'hsl(100 90% 80%)', 500, 0.7);
  popNumber(stage, el, '+' + amount, 'heal');
}

export function showBuff(stage, el, { atk, hp, maxHp, value }) {
  setStats(el, { atk, hp, maxHp });
  flashOn(el, '#ffe9a0', 420, 0.85);
  popNumber(stage, el, `+${value}/+${value}`, 'buff');
}

/** 対象を着弾方向へ少し押し込む */
export function knockback(el, ux, uy) {
  el.animate([{ transform: 'none' }, { transform: `translate(${ux * 14}px,${uy * 14}px) rotate(${ux * 4}deg)` },
    { transform: `translate(${-ux * 5}px,${-uy * 5}px)` }, { transform: 'none' }], { duration: dur(320), easing: 'ease-out' });
}
