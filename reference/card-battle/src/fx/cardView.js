// カードとリーダーの DOM。見た目の生成と、数値表示の更新だけを受け持つ。
import { dur } from './core.js';

/** イラストの代わりの幾何学模様（n 角の星） */
function sigil(n, h) {
  const pts = [], R = 40, r = n <= 4 ? 18 : 17;
  for (let i = 0; i < n * 2; i++) {
    const a = -Math.PI / 2 + (i * Math.PI) / n, rr = i % 2 ? r : R;
    pts.push((50 + Math.cos(a) * rr).toFixed(1) + ',' + (50 + Math.sin(a) * rr).toFixed(1));
  }
  const c1 = `hsl(${h} 90% 80%)`, c2 = `hsl(${h} 90% 65% / .5)`;
  return `<svg viewBox="0 0 100 100"><circle cx="50" cy="50" r="46" fill="none" stroke="${c2}" stroke-width="2"/>
    <circle cx="50" cy="50" r="30" fill="none" stroke="${c2}" stroke-width="1.2" stroke-dasharray="3 4"/>
    <polygon points="${pts.join(' ')}" fill="hsl(${h} 80% 60% / .35)" stroke="${c1}" stroke-width="2.4" stroke-linejoin="round"/>
    <circle cx="50" cy="50" r="6" fill="${c1}"/></svg>`;
}

/**
 * カード定義から DOM を作る。
 * @param {object} def   カード定義（game/cards.js）
 * @param {object} [o]
 * @param {string} [o.uid]  盤面・手札で要素を引くための識別子
 * @param {number} [o.atk]  現在の攻撃力（省略時は定義値）
 * @param {number} [o.hp]   現在の体力
 * @param {number} [o.maxHp]
 */
export function createCardEl(def, o = {}) {
  const el = document.createElement('div');
  if (o.uid) el.dataset.uid = o.uid;
  el._def = def;
  if (def.kind === 'spell') {
    el.className = 'card spell';
    el.style.setProperty('--sh', def.hue);
    el.innerHTML = `<div class="art" style="--h:${def.hue}">${sigil(def.sig, def.hue)}</div>
      <div class="cost">${def.cost}</div><div class="name">${def.name}</div><div class="desc">${def.desc}</div>`;
    return el;
  }
  el.className = `card r-${def.rarity}`;
  el.innerHTML = `<div class="art" style="--h:${def.hue}">${sigil(def.sig, def.hue)}</div>
    <div class="cost">${def.cost}</div><div class="name">${def.name}</div><div class="tribe">${def.tribe}</div>
    <div class="stat atk"><span></span></div><div class="stat hp"><span></span></div>`;
  setStats(el, { atk: o.atk ?? def.atk, hp: o.hp ?? def.hp, maxHp: o.maxHp ?? def.hp, baseAtk: def.atk, baseHp: def.hp }, false);
  return el;
}

const HERO_SVG = `<svg viewBox="0 0 100 100"><path d="M50 8 L62 36 L92 38 L68 58 L76 90 L50 72 L24 90 L32 58 L8 38 L38 36 Z"
  fill="rgba(255,255,255,.12)" stroke="rgba(255,240,210,.7)" stroke-width="3" stroke-linejoin="round"/></svg>`;

/** リーダーの中身を描き直す（リセット時にも使う） */
export function renderHero(el, { uid, name, hp }) {
  el.dataset.uid = uid;
  el.innerHTML = `${HERO_SVG}<div class="hhp"><span>${hp}</span></div><div class="hname">${name}</div>`;
  el.style.opacity = '';
  el._hero = true;
}

const pop = (el) => el.animate([{ transform: 'scale(1.8)' }, { transform: 'scale(1)' }],
  { duration: dur(350), easing: 'cubic-bezier(.3,1.6,.5,1)' });

/** 体力の表示を更新する。animate で数字を弾ませる */
export function setHp(el, hp, maxHp, animate = true) {
  if (el._hero) {
    const span = el.querySelector('.hhp span');
    span.textContent = Math.max(0, hp);
    if (animate) pop(span.parentElement);
    return;
  }
  const box = el.querySelector('.hp');
  box.querySelector('span').textContent = Math.max(0, hp);
  box.classList.toggle('damaged', hp < maxHp);
  if (animate) pop(box);
}

/** 攻撃力・体力の表示を更新する。元の値より上がった数字は緑にする */
export function setStats(el, { atk, hp, maxHp, baseAtk = el._def.atk, baseHp = el._def.hp }, animate = true) {
  const a = el.querySelector('.atk'), h = el.querySelector('.hp');
  a.querySelector('span').textContent = atk;
  h.querySelector('span').textContent = Math.max(0, hp);
  a.classList.toggle('buffed', atk > baseAtk);
  h.classList.toggle('buffed', maxHp > baseHp && hp >= maxHp);
  h.classList.toggle('damaged', hp < maxHp);
  if (animate) { pop(a); pop(h); }
}
