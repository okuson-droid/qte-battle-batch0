// canvas 1 枚で描くパーティクル。
// 重い処理（光彩・グラデーション）は事前に画像へ焼き、毎フレームは drawImage と単純な図形だけにする。
import { timing, rand, pick, easeOut } from './core.js';

export class Particles {
  constructor(canvas) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.list = [];
    this.width = 0;
    this.height = 0;
    this.dirty = false;
    this.last = 0;
    this.runeCache = new Map();
    this.glowCache = new Map();
    this.resize();
    addEventListener('resize', () => this.resize());
    requestAnimationFrame((t) => this.tick(t));
  }

  resize() {
    // 全画面 canvas は解像度に比例して重くなるため、倍率に上限を設ける
    const dpr = Math.min(1.5, window.devicePixelRatio || 1);
    this.width = this.canvas.clientWidth;
    this.height = this.canvas.clientHeight;
    this.canvas.width = this.width * dpr;
    this.canvas.height = this.height * dpr;
    this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }

  /** パーティクルを 1 つ追加する。type は DRAW のキー */
  add(p) {
    this.list.push(Object.assign({ t: 0, delay: 0 }, p));
  }

  clear() {
    this.list.length = 0;
  }

  tick(ts) {
    const dt = Math.min(40, ts - (this.last || ts)) * timing.speed;
    this.last = ts;
    const { ctx } = this;
    if (!this.list.length) {
      if (this.dirty) { ctx.clearRect(0, 0, this.width, this.height); this.dirty = false; }
      requestAnimationFrame((t) => this.tick(t));
      return;
    }
    this.dirty = true;
    ctx.clearRect(0, 0, this.width, this.height);
    for (let i = this.list.length - 1; i >= 0; i--) {
      const p = this.list[i];
      if (p.delay > 0) { p.delay -= dt; continue; }
      p.t += dt;
      const k = p.t / p.life;
      if (k >= 1) { this.list.splice(i, 1); continue; }
      DRAW[p.type](this, p, k, dt);
    }
    ctx.globalCompositeOperation = 'source-over';
    ctx.globalAlpha = 1;
    requestAnimationFrame((t) => this.tick(t));
  }

  /** 魔法陣の画像（光彩込みで一度だけ描く） */
  runeSprite(c, n) {
    const key = c + '/' + n;
    if (this.runeCache.has(key)) return this.runeCache.get(key);
    const R = 128, size = Math.ceil(R * 2 * RUNE_PAD), oc = document.createElement('canvas');
    oc.width = oc.height = size;
    const g = oc.getContext('2d');
    g.translate(size / 2, size / 2);
    g.strokeStyle = `hsla(${c},1)`; g.shadowColor = `hsla(${c},1)`; g.shadowBlur = 18;
    g.lineWidth = 6; g.beginPath(); g.arc(0, 0, R, 0, 7); g.stroke();
    g.lineWidth = 3; g.beginPath(); g.arc(0, 0, R * 0.8, 0, 7); g.stroke();
    g.beginPath();
    for (let i = 0; i <= n; i++) {
      const t = (i * 4 * Math.PI) / n;
      g[i ? 'lineTo' : 'moveTo'](Math.cos(t) * R * 0.8, Math.sin(t) * R * 0.8);
    }
    g.stroke();
    g.beginPath();
    for (let i = 0; i < 24; i++) {
      const t = (i / 24) * 6.283, e = i % 3 ? 1.06 : 1.12;
      g.moveTo(Math.cos(t) * R, Math.sin(t) * R);
      g.lineTo(Math.cos(t) * R * e, Math.sin(t) * R * e);
    }
    g.stroke();
    this.runeCache.set(key, oc);
    return oc;
  }

  /** 光球の画像 */
  glowSprite(c) {
    if (this.glowCache.has(c)) return this.glowCache.get(c);
    const oc = document.createElement('canvas');
    oc.width = oc.height = 64;
    const g = oc.getContext('2d'), gr = g.createRadialGradient(32, 32, 0, 32, 32, 32);
    gr.addColorStop(0, 'rgba(255,255,255,1)');
    gr.addColorStop(0.25, `hsla(${c},.9)`);
    gr.addColorStop(1, `hsla(${c},0)`);
    g.fillStyle = gr; g.fillRect(0, 0, 64, 64);
    this.glowCache.set(c, oc);
    return oc;
  }
}

const RUNE_PAD = 1.35;

/** 2 点間をギザギザに分割した稲妻の折れ線 */
export function jag(pts) {
  const out = [pts[0]];
  for (let i = 1; i < pts.length; i++) {
    const [x0, y0] = pts[i - 1], [x1, y1] = pts[i];
    const L = Math.hypot(x1 - x0, y1 - y0), n = Math.max(4, (L / 22) | 0);
    const nx = -(y1 - y0) / L, ny = (x1 - x0) / L;
    for (let j = 1; j < n; j++) {
      const t = j / n, off = rand(-1, 1) * L * 0.07 * Math.sin(Math.PI * t);
      out.push([x0 + (x1 - x0) * t + nx * off, y0 + (y1 - y0) * t + ny * off]);
    }
    out.push(pts[i]);
  }
  return out;
}

// 色は "色相,彩度%,明度%" の文字列で受け取り、hsla() に透明度を足して使う
const DRAW = {
  dot(P, p, k, dt) {
    const { ctx } = P, f = dt / 16;
    p.vx *= p.drag ?? 1;
    p.vy = p.vy * (p.drag ?? 1) + (p.g || 0) * f;
    p.x += p.vx * f; p.y += p.vy * f;
    const s = p.size * (p.grow ? 1 + k * p.grow : 1 - k * 0.6);
    ctx.globalCompositeOperation = p.add ? 'lighter' : 'source-over';
    ctx.fillStyle = `hsla(${p.c},${(1 - k) * (p.a ?? 1)})`;
    ctx.beginPath(); ctx.arc(p.x, p.y, s, 0, 7); ctx.fill();
  },
  spark(P, p, k, dt) {
    const { ctx } = P, f = dt / 16;
    p.vx *= 0.96; p.vy = p.vy * 0.96 + (p.g || 0) * f;
    p.x += p.vx * f; p.y += p.vy * f;
    ctx.globalCompositeOperation = 'lighter';
    ctx.strokeStyle = `hsla(${p.c},${1 - k})`;
    ctx.lineWidth = p.size * (1 - k) + 0.5; ctx.lineCap = 'round';
    ctx.beginPath(); ctx.moveTo(p.x, p.y); ctx.lineTo(p.x - p.vx * p.len, p.y - p.vy * p.len); ctx.stroke();
  },
  ring(P, p, k) {
    const { ctx } = P, r = p.r0 + (p.r1 - p.r0) * easeOut(k);
    ctx.globalCompositeOperation = 'lighter';
    ctx.strokeStyle = `hsla(${p.c},${1 - k})`; ctx.lineWidth = p.w * (1 - k) + 0.5;
    ctx.beginPath(); ctx.ellipse(p.x, p.y, r, r * (p.flat || 1), 0, 0, 7); ctx.stroke();
  },
  rune(P, p, k) {
    const { ctx } = P;
    const a = k < 0.2 ? k / 0.2 : k > 0.75 ? (1 - k) / 0.25 : 1;
    const r = p.r * (0.6 + 0.4 * easeOut(Math.min(1, k * 2.5)));
    const img = P.runeSprite(p.c, p.n || 6), sz = r * 2 * RUNE_PAD;
    ctx.globalCompositeOperation = 'lighter'; ctx.globalAlpha = a;
    ctx.save(); ctx.translate(p.x, p.y); ctx.scale(1, p.flat); ctx.rotate(p.t * 0.0025);
    ctx.drawImage(img, -sz / 2, -sz / 2, sz, sz);
    ctx.restore(); ctx.globalAlpha = 1;
  },
  gather(P, p, k) {
    const { ctx } = P, e = k * k;
    const x = p.sx + (p.tx - p.sx) * e, y = p.sy + (p.ty - p.sy) * e;
    ctx.globalCompositeOperation = 'lighter';
    ctx.fillStyle = `hsla(${p.c},${0.25 + 0.75 * k})`;
    ctx.beginPath(); ctx.arc(x, y, p.size * (1 - 0.5 * k), 0, 7); ctx.fill();
  },
  wisp(P, p, k) {
    const { ctx } = P;
    const x = p.x0 + Math.sin(k * 9) * p.sway * k, y = p.y0 - p.rise * easeOut(k);
    const a = k < 0.15 ? k / 0.15 : 1 - (k - 0.15) / 0.85, s = p.size * (1 - 0.45 * k);
    ctx.globalCompositeOperation = 'lighter'; ctx.globalAlpha = a;
    ctx.drawImage(P.glowSprite(p.c), x - s, y - s, s * 2, s * 2); ctx.globalAlpha = 1;
    if (Math.random() < 0.7) P.list.push({ type: 'dot', add: true, x: x + rand(-4, 4), y: y + rand(-4, 4),
      vx: rand(-0.3, 0.3), vy: rand(0.2, 0.7), size: rand(1.2, 2.8), c: p.c, a, life: rand(300, 550), t: 0, delay: 0 });
  },
  proj(P, p, k) {
    const { ctx } = P;
    const e = k * k * (1.6 - 0.6 * k), u = 1 - e;
    const x = u * u * p.sx + 2 * u * e * p.cx + e * e * p.tx;
    const y = u * u * p.sy + 2 * u * e * p.cy + e * e * p.ty;
    ctx.globalCompositeOperation = 'lighter';
    ctx.drawImage(P.glowSprite(p.c), x - p.size, y - p.size, p.size * 2, p.size * 2);
    ctx.drawImage(P.glowSprite('50,100%,92%'), x - p.size * 0.45, y - p.size * 0.45, p.size * 0.9, p.size * 0.9);
    for (let i = 0; i < 3; i++) P.list.push({ type: 'dot', add: true, x: x + rand(-5, 5), y: y + rand(-5, 5),
      vx: rand(-0.6, 0.6), vy: rand(-1.2, 0), drag: 0.95, size: rand(3, 7),
      c: pick([p.c, '8,100%,55%', '42,100%,65%']), life: rand(220, 420), t: 0, delay: 0 });
  },
  bolt(P, p, k) {
    const { ctx } = P;
    // 経路を数十 ms ごとに引き直して明滅させる
    if (!p.path || p.t - p.last > 45) { p.last = p.t; p.path = jag(p.pts); }
    const a = k < 0.1 ? 1 : (1 - k) ** 1.5;
    ctx.globalCompositeOperation = 'lighter'; ctx.lineJoin = 'round'; ctx.lineCap = 'round';
    for (const [w, col] of [[10, `hsla(${p.c},${0.25 * a})`], [4, `hsla(${p.c},${0.85 * a})`], [1.6, `rgba(255,255,255,${a})`]]) {
      ctx.lineWidth = w; ctx.strokeStyle = col; ctx.beginPath();
      p.path.forEach(([x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y)));
      ctx.stroke();
    }
  },
  orbit(P, p, k) {
    const { ctx } = P;
    const ang = p.a0 + p.w * p.t, r = p.r0 + (p.r1 - p.r0) * k;
    const x = p.x + Math.cos(ang) * r, y = p.y + Math.sin(ang) * r * p.flat - p.rise * k;
    const a = k < 0.15 ? k / 0.15 : 1 - (k - 0.15) / 0.85;
    ctx.globalCompositeOperation = 'lighter'; ctx.globalAlpha = a;
    ctx.drawImage(P.glowSprite(p.c), x - p.size, y - p.size, p.size * 2, p.size * 2);
    ctx.globalAlpha = 1;
  },
  beam(P, p, k) {
    const { ctx } = P, w = p.w * Math.sin(Math.PI * k);
    ctx.globalCompositeOperation = 'lighter';
    const g = ctx.createLinearGradient(p.x - w, 0, p.x + w, 0);
    g.addColorStop(0, `hsla(${p.c},0)`); g.addColorStop(0.5, `hsla(${p.c},.9)`); g.addColorStop(1, `hsla(${p.c},0)`);
    ctx.fillStyle = g; ctx.fillRect(p.x - w, 0, w * 2, p.y);
    ctx.fillStyle = `rgba(255,255,255,${0.8 * Math.sin(Math.PI * k)})`;
    ctx.fillRect(p.x - w * 0.12, 0, w * 0.24, p.y);
  },
  burst(P, p, k) {
    const { ctx } = P;
    ctx.globalCompositeOperation = 'lighter';
    ctx.strokeStyle = `hsla(${p.c},${1 - k})`; ctx.lineCap = 'round';
    p.rays.forEach(([ang, len]) => {
      const L = len * easeOut(k), s = L * 0.45;
      ctx.lineWidth = 5 * (1 - k) + 0.5; ctx.beginPath();
      ctx.moveTo(p.x + Math.cos(ang) * s, p.y + Math.sin(ang) * s);
      ctx.lineTo(p.x + Math.cos(ang) * L, p.y + Math.sin(ang) * L); ctx.stroke();
    });
    const gr = ctx.createRadialGradient(p.x, p.y, 0, p.x, p.y, p.glow);
    gr.addColorStop(0, `rgba(255,255,240,${0.9 * (1 - k)})`); gr.addColorStop(1, 'rgba(255,200,120,0)');
    ctx.fillStyle = gr; ctx.beginPath(); ctx.arc(p.x, p.y, p.glow, 0, 7); ctx.fill();
  },
  streak(P, p, k) {
    const { ctx } = P;
    ctx.globalCompositeOperation = 'lighter';
    ctx.strokeStyle = `rgba(255,240,220,${0.7 * (1 - k)})`; ctx.lineWidth = p.w * (1 - k) + 0.5; ctx.lineCap = 'round';
    ctx.beginPath(); ctx.moveTo(p.x1, p.y1); ctx.lineTo(p.x2, p.y2); ctx.stroke();
  },
};

/** ランダムな方向の光線を n 本作る（burst 用） */
export const rays = (n, min, max) => Array.from({ length: n }, () => [rand(0, 6.283), rand(min, max)]);
