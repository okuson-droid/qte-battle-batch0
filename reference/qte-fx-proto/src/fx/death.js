// 破壊演出：ひびが走る → 割れ目から光が漏れて震える → 本物のカードを破片に切り分けて飛ばす → 余韻（魂・焦げ跡）。
import { timing, dur, rand, pick, clamp, anim, clearAnims, sleep } from './core.js';
import { rays } from './particles.js';
import { fxAdd, shake, screenFlash, snapshot, flip } from './stage.js';

/**
 * 着弾点 P から放射状に亀裂を入れ、内側・外側の二重リングで破片ポリゴンを作る。
 * 座標はカードのボーダーボックス左上を原点とするピクセル値。
 */
function crackGeometry(w, h, P, n) {
  const step = (2 * Math.PI) / n, a0 = rand(0, step);
  const angs = Array.from({ length: n }, (_, i) => a0 + i * step + rand(-0.25, 0.25) * step);
  const ref = angs[0], rel = (a) => (((a - ref) % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI);
  const rays_ = angs.map((a) => {
    const dx = Math.cos(a), dy = Math.sin(a);
    const t = Math.min(dx > 0 ? (w - P.x) / dx : dx < 0 ? -P.x / dx : Infinity,
                       dy > 0 ? (h - P.y) / dy : dy < 0 ? -P.y / dy : Infinity);
    const at = (f, j) => [P.x + dx * t * f - dy * j, P.y + dy * t * f + dx * j];
    return [[P.x, P.y], at(rand(0.28, 0.42), rand(-0.04, 0.04) * w), at(rand(0.6, 0.78), rand(-0.05, 0.05) * w), [P.x + dx * t, P.y + dy * t]];
  });
  const corners = [[0, 0], [w, 0], [w, h], [0, h]].map((c) => ({ c, r: rel(Math.atan2(c[1] - P.y, c[0] - P.x)) }));
  const polys = [];
  for (let i = 0; i < n; i++) {
    const A = rays_[i], B = rays_[(i + 1) % n], lo = angs[i] - ref, hi = i === n - 1 ? 2 * Math.PI : angs[i + 1] - ref;
    const cs = corners.filter((o) => o.r > lo && o.r < hi).sort((x, y) => x.r - y.r).map((o) => o.c);
    polys.push([A[0], A[1], B[1]]);
    polys.push([A[1], A[2], A[3], ...cs, B[3], B[2], B[1]]);
  }
  const lines = [...rays_, ...rays_.map((r, i) => [r[1], rays_[(i + 1) % n][1]])];
  return { polys, lines, rayCount: n };
}

function crackSvg(geo, w, h, hue, bw) {
  const NS = 'http://www.w3.org/2000/svg', svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('class', 'cracks');
  svg.setAttribute('viewBox', `0 0 ${w} ${h}`);
  Object.assign(svg.style, { left: -bw + 'px', top: -bw + 'px', width: w + 'px', height: h + 'px' });
  geo.lines.forEach((pts, i) => {
    for (const [stroke, width] of [[`hsla(${hue},100%,62%,.6)`, 4.5], [`hsl(${hue},100%,94%)`, 1.4]]) {
      const pl = document.createElementNS(NS, 'polyline');
      pl.setAttribute('points', pts.map((p) => p.join(',')).join(' '));
      Object.assign(pl.style, { fill: 'none', stroke, strokeWidth: width, strokeLinecap: 'round', strokeLinejoin: 'round' });
      pl.dataset.i = i;
      svg.appendChild(pl);
    }
  });
  return svg;
}

/**
 * @param {object} stage
 * @param {HTMLElement} el          破壊されるカード / リーダー
 * @param {object} [o]
 * @param {{x:number,y:number}} [o.hit]  ひびの起点（画面座標）。省略時はカード中央付近
 * @param {boolean} [o.remove]      終わったら盤面から取り除き、残りを詰める（既定: カードなら true）
 */
export async function playDeath(stage, el, { hit = null, remove = !el._hero } = {}) {
  const hero = !!el._hero;
  const hue = el._def?.hue ?? (el.dataset.side === 'enemy' ? 355 : 210), col = `${hue},100%,70%`;
  el.classList.remove('ready', 'selected', 'exhausted', 'charging', 'valid', 'locked');
  el.style.zIndex = 35;
  const r = el.getBoundingClientRect(), w = el.offsetWidth, h = el.offsetHeight, bw = el.clientLeft;
  const P = hit ? { x: clamp(hit.x - r.left, w * 0.22, w * 0.78), y: clamp(hit.y - r.top, h * 0.2, h * 0.8) }
                : { x: w * rand(0.4, 0.6), y: h * rand(0.35, 0.55) };
  const px = r.left + P.x, py = r.top + P.y;

  if (timing.reduced) {
    await anim(el, [{ opacity: 1 }, { opacity: 0 }], 300);
    el.style.opacity = '0';
    if (remove) removeFromRow(el);
    return;
  }

  // 1) ひびと、割れ目から漏れる光
  const geo = crackGeometry(w, h, P, hero ? 10 : 8);
  el.appendChild(crackSvg(geo, w, h, hue, bw));
  el.querySelectorAll('.cracks polyline').forEach((pl) => {
    const L = pl.getTotalLength(), ring = +pl.dataset.i >= geo.rayCount;
    pl.style.strokeDasharray = L;
    pl.animate([{ strokeDashoffset: L }, { strokeDashoffset: 0 }],
      { duration: dur(ring ? 140 : 240), delay: dur(ring ? 200 + rand(0, 80) : rand(0, 70)), easing: 'cubic-bezier(.3,.7,.4,1)', fill: 'both' });
  });
  const glow = document.createElement('div');
  glow.className = 'flashov dglow';
  glow.style.background = `radial-gradient(circle at ${P.x - bw}px ${P.y - bw}px, hsla(${hue},100%,80%,.95), hsla(${hue},100%,55%,.35) 45%, hsla(${hue},100%,50%,0) 80%)`;
  glow.style.mixBlendMode = 'screen';
  el.appendChild(glow);
  glow.animate([{ opacity: 0 }, { opacity: 0.25, offset: 0.3 }, { opacity: 1 }], { duration: dur(520), easing: 'ease-in', fill: 'forwards' });
  fxAdd(stage, { type: 'burst', x: px, y: py, glow: w * 0.35, c: col, life: 260, rays: rays(8, 0.3 * w, 0.6 * w) });
  for (let i = 0; i < 18; i++) {
    const a = rand(0, 6.283), d = rand(0.7, 1.3) * w;
    fxAdd(stage, { type: 'gather', sx: px + Math.cos(a) * d, sy: py + Math.sin(a) * d, tx: px, ty: py, size: rand(1.5, 3), c: col, life: rand(300, 420), delay: rand(80, 200) });
  }
  if (hero) stage.dim.animate([{ opacity: 0 }, { opacity: 0.8, offset: 0.5 }, { opacity: 0 }], { duration: dur(1300) });
  const tremble = [];
  for (let i = 0; i <= 10; i++) {
    const m = (i / 10) * 4;
    tremble.push({ transform: `translate(${rand(-m, m)}px,${rand(-m, m)}px) scale(${1 + (0.06 * i) / 10})` });
  }
  await anim(el, tremble, 520, 'linear');

  // 2) 砕け散る：本物のカードを破片の形に切り抜いたクローンを飛ばす
  const layer = document.createElement('div');
  layer.className = 'shatter';
  Object.assign(layer.style, { left: r.left + 'px', top: r.top + 'px', width: w + 'px', height: h + 'px' });
  const base = el.cloneNode(true);
  base.style.cssText = `width:${w}px;height:${h}px;box-shadow:none`;
  base.querySelector('.dglow').style.opacity = 0.7;
  const cs = getComputedStyle(el);
  for (const v of ['--hc1', '--hc2', '--cw', '--ch']) base.style.setProperty(v, cs.getPropertyValue(v));
  let longest = 0;
  for (const poly of geo.polys) {
    const sh = base.cloneNode(true);
    const cx = poly.reduce((s, p) => s + p[0], 0) / poly.length, cy = poly.reduce((s, p) => s + p[1], 0) / poly.length;
    sh.style.clipPath = `polygon(${poly.map((p) => `${p[0].toFixed(1)}px ${p[1].toFixed(1)}px`).join(',')})`;
    sh.style.transformOrigin = `${cx}px ${cy}px`;
    layer.appendChild(sh);
    let dx = cx - P.x, dy = cy - P.y;
    const d = Math.hypot(dx, dy) || 1; dx /= d; dy /= d;
    const near = 1.35 - Math.min(1, d / w); // 着弾点に近い破片ほど強く飛ぶ
    const spd = w * (hero ? 1.5 : 1.05) * rand(0.7, 1.25) * near, up = -h * rand(0.15, 0.45), G = h * rand(1.3, 1.9);
    const rx = rand(-420, 420), rz = rand(-260, 260), T = rand(950, 1300);
    const kf = [];
    for (let i = 0; i <= 10; i++) {
      const k = i / 10, e = 1 - (1 - k) ** 2;
      // 3D 回転は GPU のない端末で重いため、scaleX の反転で裏返りを擬似的に表す（2D 変換のみ）
      const sc = 1.06 - 0.3 * k, flipX = Math.cos((rx * e * Math.PI) / 180);
      kf.push({ transform: `translate(${dx * spd * e}px,${dy * spd * e + up * e + G * k * k}px) rotate(${rz * e}deg) scale(${(flipX * sc).toFixed(3)},${sc.toFixed(3)})`,
        opacity: k < 0.55 ? 1 : 1 - (k - 0.55) / 0.45 });
    }
    sh.animate(kf, { duration: dur(T), easing: 'linear', fill: 'forwards' });
    longest = Math.max(longest, T);
  }
  stage.root.appendChild(layer);
  el.style.opacity = '0';
  clearAnims(el);
  setTimeout(() => layer.remove(), dur(longest) + 50);

  // 3) 余韻
  const P2 = hero ? 1.8 : 1;
  screenFlash(stage, hero ? 0.6 : 0.3, hero ? 500 : 220);
  fxAdd(stage, { type: 'burst', x: px, y: py, glow: w * 0.9 * P2, c: col, life: 380, rays: rays(16, 0.8 * w * P2, 1.5 * w * P2) });
  fxAdd(stage, { type: 'ring', x: px, y: py, r0: 6, r1: w * 1.4 * P2, w: 7, c: '0,0%,95%', life: 420 });
  fxAdd(stage, { type: 'ring', x: px, y: py, r0: 6, r1: w * 2 * P2, w: 4, c: col, life: 650, delay: 70 });
  for (let i = 0; i < 26 * P2; i++) {
    const a = rand(0, 6.283), v = rand(3, 10) * P2;
    fxAdd(stage, { type: 'spark', x: px, y: py, vx: Math.cos(a) * v, vy: Math.sin(a) * v, g: 0.22, len: 2.2, size: 2.5, c: pick([col, '45,100%,80%', '0,0%,100%']), life: rand(350, 700) });
  }
  for (let i = 0; i < 22 * P2; i++) fxAdd(stage, { type: 'dot', add: true, x: r.left + rand(0.1, 0.9) * w, y: r.top + rand(0.2, 0.9) * h,
    vx: rand(-0.6, 0.6), vy: rand(-2.6, -0.8), drag: 0.985, size: rand(1.2, 2.6), c: pick([col, '38,100%,70%']), life: rand(900, 1500), delay: rand(0, 250) });
  for (let i = 0; i < 9; i++) fxAdd(stage, { type: 'dot', x: px + rand(-0.4, 0.4) * w, y: py + rand(-0.3, 0.3) * h,
    vx: rand(-0.8, 0.8), vy: rand(-1.4, -0.4), drag: 0.97, size: rand(6, 11), grow: 1.6, a: 0.35, c: '260,8%,35%', life: rand(800, 1200) });
  fxAdd(stage, { type: 'wisp', x0: r.left + w / 2, y0: r.top + h / 2, rise: h * 1.2, sway: w * 0.18, size: w * 0.32, c: col, life: 1400, delay: 160 });
  const scorch = document.createElement('div');
  scorch.className = 'scorch';
  Object.assign(scorch.style, { left: r.left - w * 0.25 + 'px', top: r.top + h * 0.2 + 'px', width: w * 1.5 + 'px', height: h * 0.75 + 'px' });
  stage.root.appendChild(scorch);
  scorch.animate([{ opacity: 0, transform: 'scale(.5)' }, { opacity: 1, transform: 'scale(1)', offset: 0.12 }, { opacity: 0, transform: 'scale(1.1)' }],
    { duration: dur(1800), easing: 'ease-out' }).finished.then(() => scorch.remove());
  shake(stage, hero ? 14 : 8, hero ? 500 : 340);

  await sleep(hero ? 300 : 420);
  if (remove) removeFromRow(el);
}

function removeFromRow(el) {
  const row = el.parentElement;
  if (!row) return;
  const before = snapshot(row);
  el.remove();
  flip(row, before);
}
