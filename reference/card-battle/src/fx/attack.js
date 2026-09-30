// 攻撃演出：溜め → 突進（残像）→ 着弾（ヒットストップ・閃光・火花）→ 戻る。
import { rand, pick, center, anim, clearAnims, sleep } from './core.js';
import { rays } from './particles.js';
import { fxAdd, shake, screenFlash, flashOn } from './stage.js';
import { knockback } from './feedback.js';

/**
 * @param {object} stage
 * @param {HTMLElement} att   攻撃するカード
 * @param {HTMLElement} tgt   攻撃されるカード / リーダー
 * @param {object} o
 * @param {number} o.power            演出の強さの目安（攻撃力）
 * @param {boolean} [o.counter]       反撃ダメージがあるか（攻撃側も光らせる）
 * @param {(hit:{x:number,y:number}) => void} o.onImpact  着弾の瞬間に呼ばれる。ここで数値を反映する
 */
export async function playAttack(stage, att, tgt, { power, counter = false, onImpact }) {
  const a = att.getBoundingClientRect(), t = tgt.getBoundingClientRect();
  const ac = center(a), tc = center(t);
  const dx = tc.x - ac.x, dy = tc.y - ac.y, dist = Math.hypot(dx, dy), ux = dx / dist, uy = dy / dist;
  const travel = Math.max(0, dist - (a.height * 0.5 + t.height * 0.45));
  const tx = ux * travel, ty = uy * travel, tilt = ux * 12;
  att.style.zIndex = 40;
  att.classList.add('charging');

  // 1) 溜め
  await anim(att, [
    { transform: 'translate(0,0) scale(1)' },
    { transform: `translate(${-ux * 22}px,${-uy * 22}px) scale(1.14) rotate(${-tilt * 0.6}deg)` },
  ], 300, 'cubic-bezier(.2,.8,.3,1)');

  // 2) 突進と残像
  for (let i = 0; i < 9; i++) {
    const s = i / 9, ox = rand(-0.35, 0.35) * a.width;
    const x = ac.x + ux * travel * s - uy * ox, y = ac.y + uy * travel * s + ux * ox;
    fxAdd(stage, { type: 'streak', x1: x, y1: y, x2: x - ux * a.height * 0.6, y2: y - uy * a.height * 0.6, w: 3, life: 260, delay: s * 120 });
  }
  await anim(att, [
    { transform: `translate(${-ux * 22}px,${-uy * 22}px) scale(1.14) rotate(${-tilt * 0.6}deg)` },
    { transform: `translate(${tx}px,${ty}px) scale(1.14) rotate(${tilt}deg)` },
  ], 150, 'cubic-bezier(.7,0,1,.6)');

  // 3) 着弾
  const hit = { x: ac.x + tx + ux * a.height * 0.55, y: ac.y + ty + uy * a.height * 0.55 };
  hitFx(stage, hit, ux, uy, power);
  flashOn(tgt, '#fff', 200, 0.95);
  if (counter) flashOn(att, '#fff', 200, 0.7);
  await sleep(80); // ヒットストップ
  knockback(tgt, ux, uy);
  onImpact?.(hit);

  // 4) 戻る
  att.classList.remove('charging');
  await anim(att, [
    { transform: `translate(${tx}px,${ty}px) scale(1.14) rotate(${tilt}deg)` },
    { transform: `translate(${tx * 0.85}px,${ty * 0.85}px) scale(1.08) rotate(${tilt * 0.3}deg)`, offset: 0.2 },
    { transform: 'translate(0,0) scale(1) rotate(0deg)' },
  ], 420, 'cubic-bezier(.2,.8,.2,1)');
  clearAnims(att);
  att.style.zIndex = '';
}

function hitFx(stage, { x, y }, ux, uy, power) {
  const p = Math.min(2, 0.8 + power * 0.15), base = Math.atan2(uy, ux);
  fxAdd(stage, { type: 'burst', x, y, glow: 60 * p, c: '40,100%,75%', life: 320, rays: rays(12, 40 * p, 95 * p) });
  fxAdd(stage, { type: 'ring', x, y, r0: 8, r1: 70 * p, w: 6, c: '30,100%,70%', life: 380 });
  for (let i = 0; i < 22 * p; i++) {
    const a = base + rand(-1.3, 1.3), v = rand(4, 12) * p;
    fxAdd(stage, { type: 'spark', x, y, vx: Math.cos(a) * v, vy: Math.sin(a) * v, g: 0.3, len: 2.4, size: 3,
      c: pick(['35,100%,70%', '15,100%,65%', '50,100%,85%']), life: rand(300, 600) });
  }
  if (power >= 5) screenFlash(stage, 0.45, 260);
  shake(stage, 3 + power * 1.4, 300);
}

