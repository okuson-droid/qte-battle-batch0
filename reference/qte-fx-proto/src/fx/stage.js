// 盤面の DOM 骨格と、画面全体にかかる演出（揺れ・閃光・数字・帯テロップ・並び替え）。
import { timing, dur, rand, center } from './core.js';
import { Particles } from './particles.js';

const PHASES = ['ドロー', 'アンタップ', 'マナチャージ', 'メイン', 'バトル', 'サブ', 'ターンエンド'];

const leaderHtml = (side) => `
  <div class="hero leader" data-side="${side}">
    <div class="lname"></div>
    <div class="hhp"><span></span></div>
    <div class="lability"></div>
    <div class="lweapon">⚔ なし</div>
  </div>`;
const pilesHtml = (side) => `
  <div class="piles" data-side="${side}">
    <div class="pile" data-pile="deck"><div class="pbox back"></div><b>0</b><i>山札</i></div>
    <div class="pile" data-pile="grave"><div class="pbox"></div><b>0</b><i>墓地</i></div>
    <div class="pile" data-pile="banish"><div class="pbox"></div><b>0</b><i>消滅</i></div>
    <div class="pile" data-pile="taboo"><div class="pbox back"></div><b>0</b><i>禁忌</i></div>
  </div>`;

const SKELETON = `
  <div class="cb-field">
    <header class="q-top">
      <div class="q-room">部屋 <b>DEMO01</b><span class="q-conn">演出プロトタイプ</span></div>
      <div class="q-demo"></div>
      <div class="q-turn"></div>
    </header>
    <section class="q-left">
      <div class="q-zone opp">
        <div class="q-chips"><span class="chip">手札</span><div class="backs"></div>
          <span class="chip">MP <b class="mp" data-side="enemy">0</b> / マナ <b class="mc" data-side="enemy">0</b></span></div>
        <div class="mana-row" data-side="enemy"></div>
      </div>
      <div class="q-lane opp"><div class="row" data-side="enemy"></div></div>
      <div class="q-lane me"><div class="row" data-side="player"></div><div class="castzone"><span>スペル<br>【賢魂】<br>をここへ</span></div></div>
      <div class="q-zone me">
        <div class="q-chips"><span class="chip">MP <b class="mp" data-side="player">0</b> / マナ <b class="mc" data-side="player">0</b></span>
          <span class="chip plain">手札をここへドラッグ → マナチャージ(1ターン1回)</span></div>
        <div class="mana-row" data-side="player"></div>
      </div>
      <div class="q-handlabel">手札(ドラッグで使用 / クリックでも可 / 長押し・右クリックで拡大)</div>
      <div class="cb-hand"></div>
    </section>
    <aside class="q-right">
      ${leaderHtml('enemy')}
      ${pilesHtml('enemy')}
      <div class="q-btns"><button type="button" class="btn-next">次のフェイズへ</button><button type="button" class="btn-end">ターン終了</button></div>
      <div class="q-log"><div class="q-logbar"><span class="last">(ログなし)</span><span class="chip cnt">0件</span></div><ol class="q-loglist"></ol></div>
      <ol class="q-phases">${PHASES.map((p, i) => `<li data-phase="${p}"><i>${i + 1}</i>${p}</li>`).join('')}</ol>
      ${pilesHtml('player')}
      ${leaderHtml('player')}
    </aside>
    <div class="q-stepbar" hidden></div>
    <div class="q-toast" hidden></div>
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

export { PHASES };

/**
 * root 要素の中に QTE 通常モードの盤面を組み立て、演出が使う要素への参照をまとめて返す。
 * @param {HTMLElement} root
 */
export function createStage(root) {
  root.classList.add('cb-stage');
  root.insertAdjacentHTML('afterbegin', SKELETON);
  const q = (sel) => root.querySelector(sel);
  const bySide = (sel) => ({ player: q(`${sel}[data-side=player]`), enemy: q(`${sel}[data-side=enemy]`) });
  const stage = {
    root,
    field: q('.cb-field'),
    board: q('.q-left'),
    rows: bySide('.row'),
    heroes: bySide('.hero'),
    mana: bySide('.mana-row'),
    piles: bySide('.piles'),
    mp: bySide('.mp'),
    mc: bySide('.mc'),
    backs: q('.backs'),
    hand: q('.cb-hand'),
    castzone: q('.castzone'),
    turn: q('.q-turn'),
    demo: q('.q-demo'),
    phases: q('.q-phases'),
    log: q('.q-loglist'),
    logLast: q('.q-logbar .last'),
    logCount: q('.q-logbar .cnt'),
    btnNext: q('.btn-next'),
    btnEnd: q('.btn-end'),
    stepbar: q('.q-stepbar'),
    toast: q('.q-toast'),
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
