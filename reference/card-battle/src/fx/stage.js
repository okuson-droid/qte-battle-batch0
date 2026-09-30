// 盤面の DOM 骨格と、画面全体にかかる演出（揺れ・閃光・数字・帯テロップ・並び替え）。
import { timing, dur, rand, center } from './core.js';
import { Particles } from './particles.js';

const SKELETON = `
  <div class="cb-field">
    <div class="cb-board"></div>
    <div class="heroArea top"><div class="hero" data-side="enemy"></div></div>
    <div class="row" data-side="enemy"></div>
    <div class="row" data-side="player"></div>
    <div class="heroArea"><div class="hero" data-side="player"></div></div>
    <div class="cb-hand"></div>
  </div>
  <div class="cb-dim"></div>
  <div class="cb-cast"></div>
  <canvas class="cb-fx"></canvas>
  <div class="cb-flash"></div>
  <svg class="cb-aim"><defs>
    <marker id="cb-ah" viewBox="0 0 10 10" refX="5" refY="5" markerWidth="4.5" markerHeight="4.5" orient="auto-start-reverse">
      <path d="M0,0 L10,5 L0,10 L3,5 z" style="fill:var(--aimc)"/></marker></defs>
    <path class="line" d="" marker-end="url(#cb-ah)" style="display:none"/>
  </svg>
  <div class="cb-banner"></div>
  <div class="cb-drag"></div>
  <div class="cb-reticle"><div class="ring"></div></div>
  <div class="cb-inspect"></div>`;

/**
 * root 要素の中に盤面を組み立て、演出が使う要素への参照をまとめて返す。
 * @param {HTMLElement} root
 */
export function createStage(root) {
  root.classList.add('cb-stage');
  root.insertAdjacentHTML('afterbegin', SKELETON);
  const q = (sel) => root.querySelector(sel);
  const stage = {
    root,
    field: q('.cb-field'),
    rows: { player: q('.row[data-side=player]'), enemy: q('.row[data-side=enemy]') },
    heroes: { player: q('.hero[data-side=player]'), enemy: q('.hero[data-side=enemy]') },
    hand: q('.cb-hand'),
    dim: q('.cb-dim'),
    cast: q('.cb-cast'),
    flash: q('.cb-flash'),
    aimPath: q('.cb-aim path.line'),
    banner: q('.cb-banner'),
    drag: q('.cb-drag'),
    reticle: q('.cb-reticle'),
    inspect: q('.cb-inspect'),
    particles: null,
  };
  stage.particles = new Particles(q('.cb-fx'));
  return stage;
}

export const fxAdd = (stage, p) => stage.particles.add(p);
export const viewW = (stage) => stage.particles.width;
export const viewH = (stage) => stage.particles.height;

/** 画面揺れ。揺れに弱い人向けの設定では何もしない */
export function shake(stage, mag, ms = 320) {
  if (timing.reduced) return;
  const frames = [];
  for (let i = 0; i < 8; i++) {
    const m = mag * (1 - i / 8);
    frames.push({ transform: `translate(${rand(-m, m)}px,${rand(-m, m)}px)` });
  }
  frames.push({ transform: 'none' });
  stage.field.animate(frames, { duration: dur(ms), easing: 'linear' });
}

/** 画面全体の白い閃光（透明度だけを動かすので軽い） */
export function screenFlash(stage, a, ms) {
  if (timing.reduced) return;
  stage.flash.animate([{ opacity: a }, { opacity: 0 }], { duration: dur(ms), easing: 'ease-out' });
}

/** 要素の上に色の膜を重ねて一瞬光らせる */
export function flashOn(el, color, ms = 300, a = 0.9) {
  const o = document.createElement('div');
  o.className = 'flashov';
  o.style.background = color;
  el.appendChild(o);
  o.animate([{ opacity: a }, { opacity: 0 }], { duration: dur(ms), easing: 'ease-out' }).finished.then(() => o.remove());
}

/** ダメージや回復の数字を要素の上に弾ませる。cls は '' / 'heal' / 'buff' */
export function popNumber(stage, el, text, cls = '') {
  const c = center(el.getBoundingClientRect());
  const n = document.createElement('div');
  n.className = 'num ' + cls;
  n.textContent = text;
  n.style.left = c.x + 'px';
  n.style.top = c.y + 'px';
  stage.root.appendChild(n);
  n.animate([
    { transform: 'translate(-50%,-50%) scale(.2)', opacity: 1 },
    { transform: 'translate(-50%,-50%) scale(1.5)', opacity: 1, offset: 0.18 },
    { transform: 'translate(-50%,-60%) scale(1)', opacity: 1, offset: 0.35 },
    { transform: 'translate(-50%,-140%) scale(.9)', opacity: 0 },
  ], { duration: dur(1000), easing: 'ease-out' }).finished.then(() => n.remove());
}

/** 画面中央の帯テロップ。persist で出したままにする */
export function banner(stage, text, { hold = 650, persist = false } = {}) {
  const b = stage.banner;
  b.textContent = text;
  b.getAnimations().forEach((a) => a.cancel());
  return b.animate([
    { opacity: 0, transform: 'translateY(-50%) scaleY(.2)', letterSpacing: '.6em' },
    { opacity: 1, transform: 'translateY(-50%) scaleY(1)', letterSpacing: '.12em', offset: 0.25 },
    { opacity: 1, transform: 'translateY(-50%) scaleY(1)', letterSpacing: '.16em', offset: persist ? 1 : 0.75 },
    ...(persist ? [] : [{ opacity: 0, transform: 'translateY(-50%) scaleY(.6)', letterSpacing: '.3em' }]),
  ], { duration: dur(persist ? 500 : hold + 500), easing: 'ease-out', fill: 'forwards' }).finished;
}

export function clearBanner(stage) {
  stage.banner.getAnimations().forEach((a) => a.cancel());
}

/* 並び替えを滑らかに見せる FLIP：変更前の位置を記録 → DOM を変更 → 差分から元の位置へ戻して動かす */
export const snapshot = (row) => new Map([...row.children].map((c) => [c, c.getBoundingClientRect()]));
export function flip(row, before, skip) {
  for (const c of row.children) {
    if (c === skip || !before.has(c)) continue;
    const dx = before.get(c).left - c.getBoundingClientRect().left;
    if (Math.abs(dx) > 0.5) c.animate([{ transform: `translateX(${dx}px)` }, { transform: 'none' }],
      { duration: dur(320), easing: 'cubic-bezier(.2,.8,.2,1)' });
  }
}
