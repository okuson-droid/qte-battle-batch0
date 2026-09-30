// 演出全体で共有する時間管理と小さな道具。
// ゲームのルールには一切依存しない。

/** 演出の再生速度。1 が標準、2 で倍速。 */
export const timing = {
  speed: 1,
  reduced: typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches,
};

export function setSpeed(speed) {
  timing.speed = speed;
  document.documentElement.style.setProperty('--t', (0.3 / speed) + 's');
}

/** 標準速度でのミリ秒を、現在の再生速度でのミリ秒に換算する */
export const dur = (ms) => ms / timing.speed;
export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, dur(ms)));

export const rand = (a, b) => a + Math.random() * (b - a);
export const pick = (list) => list[Math.floor(Math.random() * list.length)];
export const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
export const easeOut = (k) => 1 - (1 - k) ** 3;
export const center = (r) => ({ x: r.left + r.width / 2, y: r.top + r.height / 2 });

/** Web Animations API の薄いラッパー。終了状態を保持し、完了で解決する Promise を返す */
export function anim(el, frames, ms, easing = 'ease', extra = {}) {
  return el.animate(frames, { duration: dur(ms), easing, fill: 'forwards', ...extra }).finished;
}

/** 要素に残っている fill: forwards のアニメーションを消し、素の状態に戻す */
export function clearAnims(el) {
  el.getAnimations().forEach((a) => a.cancel());
}
