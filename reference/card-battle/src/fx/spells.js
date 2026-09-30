// 呪文演出：カードを詠唱位置へ → 魔法陣と光の収束 → カードが光にほどける → 効果ごとの演出。
import { dur, rand, pick, center, anim, sleep } from './core.js';
import { rays } from './particles.js';
import { fxAdd, viewW, viewH, shake, screenFlash, flashOn } from './stage.js';
import { createCardEl } from './cardView.js';

/**
 * 詠唱（全呪文共通の前半）。
 * @param {object} stage
 * @param {object} def     呪文のカード定義
 * @param {'player'|'enemy'} side
 * @param {HTMLElement} [src]  手札・ドラッグ中のカード。省略時は画面外上から
 * @returns {Promise<{x:number,y:number}>} 詠唱位置（効果の発射点）
 */
export async function playCastIntro(stage, def, side, src = null) {
  const col = `${def.hue},100%,68%`;
  const probe = createCardEl(def);
  stage.cast.appendChild(probe);
  const w = probe.offsetWidth, h = probe.offsetHeight;
  const cx = viewW(stage) / 2, cy = viewH(stage) * (side === 'player' ? 0.56 : 0.36);
  Object.assign(probe.style, { left: cx - w / 2 + 'px', top: cy - h / 2 + 'px' });
  let from, rot = 0, sc0 = 1;
  if (src) {
    from = src.getBoundingClientRect();
    sc0 = src._drag ? src._scale : 1;
    rot = src._drag ? src._tilt : parseFloat(src.style.getPropertyValue('--r')) || 0;
    src.remove();
  } else from = { left: cx - w / 2, top: -h * 1.6, width: w, height: h };
  const f = center(from);
  await anim(probe, [
    { transform: `translate(${f.x - cx}px,${f.y - cy}px) rotate(${rot}deg) scale(${sc0})` },
    { transform: 'translate(0,0) rotate(0deg) scale(1.5)' },
  ], 420, 'cubic-bezier(.2,.9,.25,1)');

  // 詠唱：背後に正面向きの魔法陣、光の粒がらせんに集まる
  fxAdd(stage, { type: 'rune', x: cx, y: cy, r: w * 1.25, flat: 1, n: def.sig > 4 ? def.sig : 6, c: col, life: 1300 });
  fxAdd(stage, { type: 'rune', x: cx, y: cy, r: w * 0.8, flat: 1, n: 3, c: '0,0%,90%', life: 1100, delay: 120 });
  for (let i = 0; i < 26; i++) fxAdd(stage, { type: 'orbit', x: cx, y: cy, a0: rand(0, 6.283), w: rand(0.006, 0.012) * (i % 2 ? 1 : -1),
    r0: w * rand(1.2, 1.7), r1: w * 0.1, flat: 1, rise: 0, size: rand(5, 9), c: col, life: rand(550, 800), delay: rand(0, 250) });
  const hold = probe.animate([{ transform: 'scale(1.5)' }, { transform: 'translateY(-6px) scale(1.54)' }, { transform: 'scale(1.5)' }],
    { duration: dur(620), easing: 'ease-in-out', fill: 'forwards' });
  const veil = document.createElement('div');
  veil.className = 'flashov';
  veil.style.background = `radial-gradient(circle, #fff, hsl(${def.hue} 100% 70%))`;
  probe.appendChild(veil);
  veil.animate([{ opacity: 0 }, { opacity: 1 }], { duration: dur(620), easing: 'ease-in', fill: 'forwards' });
  await hold.finished;

  // カードが光にほどける
  probe.animate([{ transform: 'scale(1.5)', opacity: 1 }, { transform: 'scale(.2)', opacity: 0 }],
    { duration: dur(220), easing: 'cubic-bezier(.6,0,1,1)', fill: 'forwards' }).finished.then(() => probe.remove());
  fxAdd(stage, { type: 'burst', x: cx, y: cy, glow: w * 0.9, c: col, life: 300, rays: rays(12, 0.6 * w, 1.1 * w) });
  fxAdd(stage, { type: 'ring', x: cx, y: cy, r0: 10, r1: w * 1.6, w: 5, c: col, life: 420 });
  await sleep(160);
  return { x: cx, y: cy };
}

/**
 * 効果ごとの演出。どれも「着弾の瞬間」に onHit(対象の番号) を呼ぶので、そこで数値を反映する。
 * @type {Record<string, (stage:object, o:{from:{x:number,y:number}, targets:HTMLElement[], def:object, onHit:(i:number)=>void}) => Promise<void>>}
 */
export const spellEffects = {
  /** 放物線を描く火球 → 爆発 */
  async fireball(stage, { from, targets: [tgt], onHit }) {
    const t = center(tgt.getBoundingClientRect()), T = 520;
    fxAdd(stage, { type: 'proj', sx: from.x, sy: from.y, tx: t.x, ty: t.y, cx: (from.x + t.x) / 2 + rand(-80, 80),
      cy: Math.min(from.y, t.y) - 120, size: 26, c: '28,100%,60%', life: T });
    await sleep(T);
    fxAdd(stage, { type: 'burst', x: t.x, y: t.y, glow: 110, c: '30,100%,65%', life: 380, rays: rays(16, 70, 140) });
    fxAdd(stage, { type: 'ring', x: t.x, y: t.y, r0: 10, r1: 150, w: 8, c: '20,100%,60%', life: 480 });
    for (let i = 0; i < 40; i++) {
      const a = rand(0, 6.283), v = rand(3, 11);
      fxAdd(stage, { type: 'dot', add: true, x: t.x, y: t.y, vx: Math.cos(a) * v, vy: Math.sin(a) * v, drag: 0.9, size: rand(4, 10),
        c: pick(['28,100%,60%', '8,100%,55%', '45,100%,70%']), life: rand(300, 600) });
    }
    for (let i = 0; i < 10; i++) fxAdd(stage, { type: 'dot', x: t.x + rand(-30, 30), y: t.y + rand(-20, 20), vx: rand(-0.8, 0.8), vy: rand(-1.6, -0.4),
      drag: 0.97, size: rand(7, 12), grow: 1.5, a: 0.35, c: '20,10%,30%', life: rand(800, 1200), delay: 120 });
    screenFlash(stage, 0.35, 260);
    shake(stage, 11, 380);
    flashOn(tgt, '#fff', 250, 0.95);
    onHit(0, t);
    await sleep(350);
  },

  /** 詠唱位置 → 1 体目 → 2 体目 … と飛び移る稲妻 */
  async chain(stage, { from, targets, def, onHit }) {
    const col = `${def.hue},100%,68%`;
    let prev = [from.x, from.y];
    for (let i = 0; i < targets.length; i++) {
      const t = targets[i], c = center(t.getBoundingClientRect()), pt = [c.x, c.y];
      fxAdd(stage, { type: 'bolt', pts: [prev, pt], c: col, life: 420 });
      fxAdd(stage, { type: 'burst', x: c.x, y: c.y, glow: 70, c: col, life: 260, rays: rays(10, 40, 80) });
      for (let j = 0; j < 14; j++) {
        const a = rand(0, 6.283), v = rand(3, 9);
        fxAdd(stage, { type: 'spark', x: c.x, y: c.y, vx: Math.cos(a) * v, vy: Math.sin(a) * v, g: 0.2, len: 2, size: 2.2, c: pick([col, '0,0%,100%']), life: rand(250, 450) });
      }
      flashOn(t, '#dff4ff', 220, 0.9);
      shake(stage, 6, 220);
      onHit(i, c);
      prev = pt;
      await sleep(130);
    }
    screenFlash(stage, 0.25, 200);
    await sleep(300);
  },

  /** 降り注ぐ光の柱と昇る光の粒 */
  async heal(stage, { targets: [tgt], onHit }) {
    const r = tgt.getBoundingClientRect(), c = center(r);
    fxAdd(stage, { type: 'beam', x: c.x, y: r.bottom, w: r.width * 0.8, c: '100,90%,70%', life: 900 });
    fxAdd(stage, { type: 'rune', x: c.x, y: r.bottom - r.height * 0.05, r: r.width * 0.9, flat: 0.35, n: 8, c: '100,90%,70%', life: 1100 });
    for (let i = 0; i < 30; i++) fxAdd(stage, { type: 'dot', add: true, x: c.x + rand(-0.6, 0.6) * r.width, y: r.bottom - rand(0, 0.5) * r.height,
      vx: rand(-0.2, 0.2), vy: rand(-3, -1.2), drag: 0.99, size: rand(1.5, 3.5), c: pick(['100,90%,75%', '55,100%,80%']), life: rand(700, 1100), delay: rand(100, 600) });
    await sleep(380);
    fxAdd(stage, { type: 'ring', x: c.x, y: c.y, r0: 10, r1: r.width * 1.1, w: 5, c: '100,90%,75%', life: 500 });
    onHit(0, c);
    await sleep(450);
  },

  /** らせんに昇る金色の光 → 弾けて強化 */
  async buff(stage, { targets: [tgt], onHit }) {
    const r = tgt.getBoundingClientRect(), c = center(r);
    for (let i = 0; i < 22; i++) fxAdd(stage, { type: 'orbit', x: c.x, y: r.bottom - r.height * 0.1, a0: (i / 22) * 6.283, w: 0.009,
      r0: r.width * 0.85, r1: r.width * 0.55, flat: 0.38, rise: r.height * 1.05, size: rand(6, 10), c: '45,100%,65%', life: 900, delay: i * 12 });
    fxAdd(stage, { type: 'rune', x: c.x, y: r.bottom - r.height * 0.05, r: r.width * 0.9, flat: 0.35, n: 4, c: '45,100%,65%', life: 1100 });
    await anim(tgt, [{ transform: 'none' }, { transform: 'translateY(-8px) scale(1.05)', offset: 0.75 }, { transform: 'translateY(0) scale(1.14)' }], 800, 'ease-in');
    fxAdd(stage, { type: 'burst', x: c.x, y: c.y, glow: r.width * 0.9, c: '45,100%,70%', life: 320, rays: rays(14, 0.6 * r.width, r.width) });
    fxAdd(stage, { type: 'ring', x: c.x, y: c.y, r0: 10, r1: r.width * 1.3, w: 6, c: '45,100%,70%', life: 450 });
    shake(stage, 4, 200);
    onHit(0, c);
    await anim(tgt, [{ transform: 'scale(1.14)' }, { transform: 'scale(1)' }], 300, 'cubic-bezier(.3,1.5,.5,1)');
    tgt.getAnimations().forEach((a) => a.cancel());
  },
};
