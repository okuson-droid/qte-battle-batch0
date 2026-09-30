// カードとリーダー欄の DOM。見た目の生成と、数値表示の更新だけを受け持つ。
// QTE 通常モードのカード面(文明色の枠・コスト・名前・種別・本文・⚔/♥)を簡略に写したもの。
import { dur } from './core.js';

export const CIV = {
  FIRE: { name: '火', hue: 0 }, WATER: { name: '水', hue: 212 }, WIND: { name: '風', hue: 140 },
  LIGHT: { name: '光', hue: 44 }, DARK: { name: '闇', hue: 268 }, EARTH: { name: '土', hue: 326 },
};
const TYPE_LABEL = { minion: 'ミニオン', spell: 'スペル' };

/** イラストの代わりの幾何学模様(n 角の星) */
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
 * @param {object} def
 * @param {object} [o]  uid / atk / hp / maxHp
 */
export function createCardEl(def, o = {}) {
  const el = document.createElement('div');
  if (o.uid) el.dataset.uid = o.uid;
  el._def = def;
  el.className = `card ${def.kind}`;
  el.style.setProperty('--h', def.hue);
  el.style.setProperty('--sh', def.hue);
  const stats = def.kind === 'minion'
    ? `<div class="stat atk"><span></span></div><div class="stat hp"><span></span></div>` : '';
  el.innerHTML = `<div class="head"><div class="cost">${def.cost}</div><div class="name">${def.name}</div></div>
    <div class="art">${sigil(def.sig, def.hue)}</div>
    <div class="type">${TYPE_LABEL[def.kind]}・${CIV[def.civ].name}</div>
    <div class="text">${def.text || ''}</div>${stats}`;
  if (def.kind === 'minion') {
    setStats(el, { atk: o.atk ?? def.atk, hp: o.hp ?? def.hp, maxHp: o.maxHp ?? def.hp, baseAtk: def.atk, baseHp: def.hp }, false);
  }
  return el;
}

/** 裏向きのカード(相手の手札・マナ) */
export function createBackEl(cls = 'back') {
  const el = document.createElement('div');
  el.className = cls;
  return el;
}

/** リーダー欄の中身を描き直す */
export function renderHero(el, { uid, name, hp, ability }) {
  el.dataset.uid = uid;
  el.querySelector('.lname').textContent = name;
  el.querySelector('.hhp span').textContent = hp;
  el.querySelector('.lability').textContent = ability;
  el.style.opacity = '';
  el._hero = true;
}

const pop = (el) => el.animate([{ transform: 'scale(1.8)' }, { transform: 'scale(1)' }],
  { duration: dur(350), easing: 'cubic-bezier(.3,1.6,.5,1)' });

/** 体力(リーダーは LP)の表示を更新する */
export function setHp(el, hp, maxHp, animate = true) {
  if (el._hero) {
    const box = el.querySelector('.hhp');
    box.querySelector('span').textContent = Math.max(0, hp);
    box.classList.toggle('low', hp <= 5);
    if (animate) pop(box);
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
