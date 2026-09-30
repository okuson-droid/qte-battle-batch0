// 召喚演出：持ち上げ → 足元に魔法陣 → 溜め（レジェンドは暗転＋光柱）→ 叩きつけ → 衝撃波。
import { dur, rand, center, anim, clearAnims } from './core.js';
import { fxAdd, viewW, shake, screenFlash, flashOn, snapshot, flip } from './stage.js';

/**
 * @param {object} stage   createStage() の戻り値
 * @param {object} o
 * @param {HTMLElement} o.el      盤面に置くカード要素（新規作成済み・未配置）
 * @param {'player'|'enemy'} o.side
 * @param {number} [o.index]      盤面の何番目に置くか（省略時は右端）
 * @param {HTMLElement} [o.src]   飛び立つ元の要素（手札・ドラッグ中のカード）。省略時は画面外上から
 * @param {HTMLElement} [o.slot]  ドラッグで開けておいた隙間。あればそこへ置き換える
 */
export async function playSummon(stage, { el, side, index = null, src = null, slot = null }) {
  const row = stage.rows[side];
  const rarity = el._def.rarity, legend = rarity === 'legendary', rare = rarity === 'rare';
  let from = null, rot = 0, sc0 = 1;
  if (src) {
    from = src.getBoundingClientRect();
    rot = src._drag ? src._tilt : parseFloat(src.style.getPropertyValue('--r')) || 0;
    sc0 = src._drag ? src._scale : 1;
    src.remove();
  }
  const before = snapshot(row);
  if (slot && slot.parentElement === row) slot.replaceWith(el);
  else if (index != null && index < row.children.length) row.insertBefore(el, row.children[index]);
  else row.appendChild(el);
  flip(row, before, el);

  const to = el.getBoundingClientRect(), c = center(to);
  if (!from) from = { left: viewW(stage) / 2 - to.width / 2, top: -to.height * 1.6, width: to.width, height: to.height };
  const f = center(from), dx = f.x - c.x, dy = f.y - c.y;
  const hy = (side === 'player' ? -1 : 1) * to.height * 0.7;
  el.style.zIndex = 40;

  // 1) 浮き上がる
  const lift = anim(el, [
    { transform: `translate(${dx}px,${dy}px) rotate(${rot}deg) scale(${sc0})` },
    { transform: `translate(0px,${hy}px) rotate(0deg) scale(1.35)` },
  ], 460, 'cubic-bezier(.2,.9,.25,1)');
  const col = legend ? '45,100%,65%' : rare ? '210,100%,70%' : '160,70%,70%';
  fxAdd(stage, { type: 'rune', x: c.x, y: c.y + to.height * 0.1, r: to.width * 0.95, flat: 0.42, n: legend ? 8 : 6, c: col, life: legend ? 2200 : 1250 });
  await lift;

  // 2) 溜め
  if (legend) {
    stage.dim.animate([{ opacity: 0 }, { opacity: 1, offset: 0.2 }, { opacity: 1, offset: 0.75 }, { opacity: 0 }], { duration: dur(1500) });
    fxAdd(stage, { type: 'beam', x: c.x, y: c.y + to.height * 0.1, w: to.width * 0.75, c: '45,100%,70%', life: 900 });
    for (let i = 0; i < 26; i++) fxAdd(stage, { type: 'dot', add: true, x: c.x + rand(-1, 1) * to.width * 0.9, y: c.y + rand(0, 0.5) * to.height,
      vx: 0, vy: rand(-3, -1), size: rand(1.5, 3.5), c: '45,100%,75%', life: rand(600, 1000), delay: rand(0, 400) });
  }
  await anim(el, [
    { transform: `translate(0px,${hy}px) scale(1.35)` },
    { transform: `translate(0px,${hy * 1.15}px) scale(1.45)` },
  ], legend ? 620 : 200, 'ease-out');

  // 3) 叩きつける
  await anim(el, [
    { transform: `translate(0px,${hy * 1.15}px) scale(1.45)` },
    { transform: 'translate(0px,0px) scale(1)' },
  ], legend ? 150 : 170, 'cubic-bezier(.55,0,1,.45)');
  clearAnims(el);
  landImpact(stage, to, legend ? 2 : rare ? 1.35 : 1, legend ? '45,100%,70%' : rare ? '210,100%,75%' : '40,40%,85%');
  flashOn(el, '#fff', 380);
  await anim(el, [{ transform: 'scale(1.1,.88)' }, { transform: 'scale(.97,1.04)' }, { transform: 'scale(1)' }], 260, 'ease-out');
  clearAnims(el);
  el.style.zIndex = '';
}

/** 着地の衝撃：地面の衝撃波・砂煙・火花・揺れ */
function landImpact(stage, r, power, col) {
  const c = center(r), by = r.bottom - r.height * 0.05;
  fxAdd(stage, { type: 'ring', x: c.x, y: by, r0: r.width * 0.3, r1: r.width * 1.3 * power, flat: 0.35, w: 7, c: col, life: 520 });
  if (power > 1.2) fxAdd(stage, { type: 'ring', x: c.x, y: by, r0: r.width * 0.2, r1: r.width * 2.2 * power, flat: 0.35, w: 4, c: col, life: 800, delay: 60 });
  for (let i = 0; i < 16 * power; i++) {
    const x = c.x + rand(-0.5, 0.5) * r.width, s = Math.sign(x - c.x) || 1;
    fxAdd(stage, { type: 'dot', x, y: by + rand(-4, 4), vx: s * rand(1.5, 5) * power, vy: rand(-2, -0.3), drag: 0.93,
      size: rand(3, 7), grow: 2.2, a: 0.5, c: '35,15%,72%', life: rand(500, 900) });
  }
  for (let i = 0; i < 10 * power; i++) {
    const a = rand(Math.PI * 1.05, Math.PI * 1.95);
    fxAdd(stage, { type: 'spark', x: c.x + rand(-0.4, 0.4) * r.width, y: by, vx: Math.cos(a) * rand(3, 9), vy: Math.sin(a) * rand(3, 9),
      g: 0.25, len: 2.2, size: 2.5, c: col, life: rand(350, 650) });
  }
  if (power > 1.5) screenFlash(stage, 0.55, 350);
  shake(stage, 5 * power);
}
