// 消滅演出(Batch 85 の Q2 = b で新設):足元に裂け目の魔法陣 → 色が抜けて白く透ける →
// 下から上へほどけて光の粒になる → 残った光が「消滅」の山へ吸い込まれる。
// 破壊(砕け散る)と見分けがつくよう、揺れと破片は使わず、静かに消える方向でまとめている。
import { timing, dur, rand, pick, center, anim, clearAnims, sleep } from './core.js';
import { fxAdd, screenFlash, snapshot, flip } from './stage.js';

/**
 * @param {object} stage
 * @param {HTMLElement} el      消滅するカード
 * @param {HTMLElement} [pile]  吸い込まれる先(右の列の「消滅」)
 */
export async function playBanish(stage, el, pile = null) {
  const r = el.getBoundingClientRect(), c = center(r), w = r.width, h = r.height;
  el.classList.remove('ready', 'selected', 'exhausted', 'charging', 'valid', 'locked');
  el.style.zIndex = 35;
  if (timing.reduced) {
    await anim(el, [{ opacity: 1 }, { opacity: 0 }], 300);
    removeFromRow(el);
    return;
  }
  const col = '275,90%,72%', pale = '220,60%,92%';

  // 1) 裂け目:平たい魔法陣が開き、周囲の光が吸い寄せられる
  fxAdd(stage, { type: 'rune', x: c.x, y: r.bottom - h * 0.08, r: w * 0.85, flat: 0.32, n: 5, c: col, life: 1500 });
  fxAdd(stage, { type: 'ring', x: c.x, y: r.bottom - h * 0.08, r0: w * 1.2, r1: w * 0.3, flat: 0.32, w: 5, c: col, life: 500 });
  for (let i = 0; i < 16; i++) {
    const a = rand(0, 6.283), d = rand(0.8, 1.4) * w;
    fxAdd(stage, { type: 'gather', sx: c.x + Math.cos(a) * d, sy: c.y + Math.sin(a) * d * 0.7, tx: c.x, ty: c.y,
      size: rand(1.5, 3), c: pick([col, pale]), life: rand(320, 460), delay: rand(0, 160) });
  }
  const veil = document.createElement('div');
  veil.className = 'flashov';
  veil.style.background = 'linear-gradient(0deg, hsla(275,100%,85%,.95), hsla(220,100%,96%,.9))';
  veil.style.mixBlendMode = 'screen';
  el.appendChild(veil);
  veil.animate([{ opacity: 0 }, { opacity: 0.9 }], { duration: dur(480), easing: 'ease-in', fill: 'forwards' });
  await anim(el, [{ transform: 'none' }, { transform: 'translateY(-6px) scale(1.04)' }], 480, 'ease-out');

  // 2) 下から上へほどける(clip-path で削りながら、削った縁から光の粒を放つ)
  const T = 900, t0 = performance.now();
  el.animate([
    { clipPath: 'inset(0 0 0% 0)', transform: 'translateY(-6px) scale(1.04)', opacity: 1 },
    { clipPath: 'inset(0 0 100% 0)', transform: 'translateY(-18px) scale(1.06)', opacity: 0.6 },
  ], { duration: dur(T), easing: 'cubic-bezier(.45,0,.7,1)', fill: 'forwards' });
  let longest = 0;
  for (let i = 0; i < 46; i++) {
    const k = i / 46, delay = k * T * 0.95;
    const y = r.bottom - 6 - h * k, x = r.left + rand(0.05, 0.95) * w;
    fxAdd(stage, { type: 'dot', add: true, x, y, vx: rand(-0.4, 0.4), vy: rand(-2.4, -0.9), drag: 0.985,
      size: rand(1.4, 3.2), c: pick([col, pale, '0,0%,100%']), life: rand(700, 1100), delay: dur(delay) });
    longest = Math.max(longest, delay);
  }
  fxAdd(stage, { type: 'beam', x: c.x, y: r.bottom, w: w * 0.55, c: col, life: T + 200 });
  await sleep(T);
  el.style.opacity = '0';

  // 3) 残った光が「消滅」の山へ飛ぶ
  if (pile) {
    const p = center(pile.getBoundingClientRect()), F = 460;
    fxAdd(stage, { type: 'proj', sx: c.x, sy: r.top, tx: p.x, ty: p.y, cx: (c.x + p.x) / 2, cy: Math.min(r.top, p.y) - 60,
      size: 14, c: col, life: F });
    await sleep(F);
    fxAdd(stage, { type: 'ring', x: p.x, y: p.y, r0: 4, r1: 46, w: 4, c: col, life: 380 });
    pile.animate([{ transform: 'scale(1.25)' }, { transform: 'scale(1)' }], { duration: dur(300), easing: 'cubic-bezier(.3,1.6,.5,1)' });
  }
  screenFlash(stage, 0.12, 200);
  clearAnims(el);
  removeFromRow(el);
}

function removeFromRow(el) {
  const row = el.parentElement;
  if (!row) return;
  const before = snapshot(row);
  el.remove();
  flip(row, before);
}

/** カードが墓地へ落ちたとき、山の数字を小さく弾ませる */
export function bumpPile(pile) {
  if (!pile) return;
  pile.animate([{ transform: 'scale(1.2)' }, { transform: 'scale(1)' }], { duration: dur(260), easing: 'cubic-bezier(.3,1.6,.5,1)' });
}

/**
 * 要素を別の場所へ飛ばす(マナチャージ・手札から墓地など)。旧位置に複製を置いて動かし、本物は即座に最終位置へ置く。
 * @param {DOMRect} from
 * @param {HTMLElement} toEl  到着先(最終状態で既に置かれている要素)
 * @param {HTMLElement} ghostEl  飛ばす見た目
 */
export async function flyTo(stage, from, toEl, ghostEl, ms = 380) {
  const to = toEl.getBoundingClientRect();
  Object.assign(ghostEl.style, { transformOrigin: '0 0', position: 'fixed', left: from.left + 'px', top: from.top + 'px', width: from.width + 'px', height: from.height + 'px', margin: 0, zIndex: 44 });
  stage.root.appendChild(ghostEl);
  toEl.style.visibility = 'hidden';
  const sx = to.width / from.width, sy = to.height / from.height;
  await anim(ghostEl, [
    { transform: 'none' },
    { transform: `translate(${(to.left - from.left) * 0.5}px,${(to.top - from.top) * 0.5 - 40}px) scale(${(1 + sx) / 2},${(1 + sy) / 2}) rotate(-8deg)`, offset: 0.5 },
    { transform: `translate(${to.left - from.left}px,${to.top - from.top}px) scale(${sx},${sy})` },
  ], ms, 'cubic-bezier(.3,.7,.3,1)');
  toEl.style.visibility = '';
  ghostEl.remove();
  toEl.animate([{ transform: 'scale(1.15)' }, { transform: 'none' }], { duration: dur(200), easing: 'ease-out' });
}
