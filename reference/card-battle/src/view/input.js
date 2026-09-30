// 操作：ドラッグ（召喚・攻撃・呪文）、タップ、長押しの拡大表示。
// ルールは知らない。「何ができるか」は query に問い合わせ、「何をしたいか」は callbacks で伝える。
import { center, clamp, dur } from '../fx/core.js';
import { layoutHand, returnToHand, snapshot, flip } from '../fx/index.js';

const DRAG_START = 8;   // これ以上動いたらドラッグとみなす（px）
const LONG_PRESS = 450; // 長押しで拡大表示するまでの時間（ms）
const HIT_PAD = 16;     // 対象の少し外側でも当たりにする遊び（px）

/**
 * @typedef {object} InputQuery
 * @property {() => boolean} canAct                 いま操作を受け付けるか
 * @property {(uid:string) => boolean} canAttack
 * @property {(uid:string) => string[]} attackTargets
 * @property {(uid:string) => string[]} spellTargets   対象が要らない呪文なら []
 * @property {() => boolean} boardFull
 *
 * @typedef {object} InputCallbacks
 * @property {(uid:string, index:number|null) => void} onPlayMinion
 * @property {(attackerUid:string, targetUid:string) => void} onAttack
 * @property {(uid:string, targetUid:string|null) => void} onCast
 */
export class Input {
  /**
   * @param {object} stage
   * @param {import('./director.js').Director} director
   * @param {InputQuery} query
   * @param {InputCallbacks} callbacks
   */
  constructor(stage, director, query, callbacks) {
    Object.assign(this, { stage, director, query, cb: callbacks });
    this.aim = null;       // 照準中の状態 { fromEl, valid, locked }
    this.selected = null;  // タップで選んだ攻撃役
    this.picked = null;    // タップで選んだ呪文カード
    this.ptr = null;       // 押している指の状態
    this.drag = null;      // 手札から持ち上げたカード
    this.slot = null;      // 盤面に開けた置き場所
    this.lifted = null;    // 最後に手札から離れたカード（ルールに断られたとき戻すため）
    this.suppressUntil = 0;
    const root = stage.root;
    root.addEventListener('pointerdown', (e) => this.onDown(e));
    root.addEventListener('pointermove', (e) => this.onMove(e));
    root.addEventListener('pointerup', (e) => this.onUp(e, false));
    root.addEventListener('pointercancel', (e) => this.onUp(e, true));
    root.addEventListener('click', (e) => this.onClick(e));
  }

  /* ---------------- 共通 ---------------- */

  uid(el) { return this.director.uidOf(el); }
  els(uids) { return uids.map((u) => this.director.el(u)).filter(Boolean); }
  isHand(el) { return el?.parentElement === this.stage.hand; }
  isMine(el) { return el?.parentElement === this.stage.rows.player; }
  handTop() { return this.stage.hand.getBoundingClientRect().top; }

  /** 操作の途中状態をすべて取り消す（リセット時など） */
  reset() {
    this.select(null);
    this.pickSpell(null);
    if (this.drag) { cancelAnimationFrame(this.drag.raf); this.drag = null; }
    this.stage.drag.replaceChildren();
    this.closeSlot();
    this.ptr = null;
    this.lifted = null;
  }

  /** ルールが操作を受け付けたら呼ぶ（持ち上げたカードの記録を捨てる） */
  settle() {
    this.lifted = null;
  }

  /** ルールに操作を断られたとき、持ち上げていたカードを手札に戻す */
  restore() {
    const l = this.lifted;
    this.lifted = null;
    this.closeSlot();
    this.director.pendingSlot = null;
    if (l && l.el.isConnected && !this.isHand(l.el)) { l.el._drag = false; returnToHand(this.stage, l.el, l.index, l); }
  }

  /* ---------------- 照準（矢印・吸い付き・レティクル） ---------------- */

  startAim(fromEl, validEls, kind) {
    this.endAim();
    this.aim = { fromEl, valid: validEls, locked: null };
    this.stage.root.dataset.aim = kind;
    this.stage.root.classList.add('aiming');
    validEls.forEach((v) => v.classList.add('valid'));
  }

  endAim() {
    if (!this.aim) return;
    this.aim.valid.forEach((v) => v.classList.remove('valid'));
    this.lockOn(null);
    this.aim = null;
    delete this.stage.root.dataset.aim;
    this.stage.root.classList.remove('aiming');
    this.stage.aimPath.style.display = 'none';
  }

  targetAt(x, y) {
    return this.aim.valid.find((t) => {
      if (!t.isConnected) return false;
      const r = t.getBoundingClientRect();
      return x > r.left - HIT_PAD && x < r.right + HIT_PAD && y > r.top - HIT_PAD && y < r.bottom + HIT_PAD;
    }) || null;
  }

  lockOn(t) {
    const aim = this.aim, ret = this.stage.reticle;
    if (!aim || aim.locked === t) return;
    aim.locked?.classList.remove('locked');
    aim.locked = t;
    if (!t) { ret.style.display = 'none'; return; }
    t.classList.add('locked');
    const r = t.getBoundingClientRect(), sz = Math.max(r.width, r.height) * 1.2;
    Object.assign(ret.style, { display: 'block', left: r.left + r.width / 2 - sz / 2 + 'px', top: r.top + r.height / 2 - sz / 2 + 'px', width: sz + 'px', height: sz + 'px' });
    ret.animate([{ transform: 'scale(1.7)', opacity: 0 }, { transform: 'scale(1)', opacity: 1 }], { duration: dur(200), easing: 'cubic-bezier(.2,.9,.3,1.25)' });
  }

  updateAim(x, y) {
    if (!this.aim) return;
    const t = this.targetAt(x, y);
    this.lockOn(t);
    let ex = x, ey = y;
    if (t) ({ x: ex, y: ey } = center(t.getBoundingClientRect())); // 吸い付き：矢印の先を対象の中心へ
    const c = center(this.aim.fromEl.getBoundingClientRect());
    const bend = Math.min(120, Math.hypot(ex - c.x, ey - c.y) * 0.35);
    const path = this.stage.aimPath;
    path.setAttribute('d', `M${c.x},${c.y} Q${(c.x + ex) / 2},${Math.min(c.y, ey) - bend} ${ex},${ey}`);
    path.style.display = '';
  }

  spellAim(el) {
    const targets = this.els(this.query.spellTargets(this.uid(el)));
    return { targets, kind: el._def.target === 'enemy' ? 'attack' : 'help' };
  }

  /* ---------------- タップ操作 ---------------- */

  select(el) {
    if (this.picked) this.pickSpell(null);
    this.selected?.classList.remove('selected');
    this.selected = el;
    this.endAim();
    if (el) {
      el.classList.add('selected');
      this.startAim(el, this.els(this.query.attackTargets(this.uid(el))), 'attack');
    }
  }

  pickSpell(el) {
    this.picked?.classList.remove('picked');
    this.picked = el;
    this.endAim();
    if (el) {
      el.classList.add('picked');
      const { targets, kind } = this.spellAim(el);
      this.startAim(el, targets, kind);
    }
  }

  onClick(e) {
    if (performance.now() < this.suppressUntil || !this.query.canAct()) return;
    const el = e.target.closest('.card,.hero');
    if (!el) { this.select(null); return; }
    if (this.picked) {
      const card = this.picked, ok = el.classList.contains('valid');
      this.pickSpell(null);
      if (ok) { this.lift(card); this.cb.onCast(this.uid(card), this.uid(el)); return; }
      if (el === card || !this.isHand(el)) return;
    }
    if (this.isHand(el)) {
      this.select(null);
      const def = el._def, uid = this.uid(el);
      if (def.kind !== 'spell') {
        if (this.query.boardFull()) { nope(el); return; }
        this.lift(el); this.cb.onPlayMinion(uid, null); return;
      }
      if (def.target === 'none') { this.lift(el); this.cb.onCast(uid, null); return; }
      if (!this.query.spellTargets(uid).length) { nope(el); return; }
      this.pickSpell(el);
      return;
    }
    if (this.isMine(el)) {
      if (el === this.selected || !this.query.canAttack(this.uid(el))) this.select(null);
      else this.select(el);
      return;
    }
    if (this.selected && el.classList.contains('valid')) {
      const att = this.selected;
      this.select(null);
      this.cb.onAttack(this.uid(att), this.uid(el));
      return;
    }
    this.select(null);
  }

  /** 手札から出ていくカードの元の位置を覚えておく */
  lift(el, extra = {}) {
    this.lifted = { el, index: [...this.stage.hand.children].indexOf(el), ...extra };
  }

  /* ---------------- ドラッグ操作 ---------------- */

  onDown(e) {
    if (!this.query.canAct() || e.button > 0 || this.ptr) return;
    const el = e.target.closest('.card,.hero');
    this.ptr = { id: e.pointerId, x0: e.clientX, y0: e.clientY, x: e.clientX, y: e.clientY, el, mode: null };
    if (el?.classList.contains('card')) {
      this.ptr.lp = setTimeout(() => {
        if (this.ptr && !this.ptr.mode) { this.ptr.mode = 'inspect'; this.showInspect(el); }
      }, LONG_PRESS);
    }
  }

  onMove(e) {
    const p = this.ptr;
    if (!p || e.pointerId !== p.id) { this.updateAim(e.clientX, e.clientY); return; }
    p.x = e.clientX; p.y = e.clientY;
    if (!p.mode && Math.hypot(p.x - p.x0, p.y - p.y0) > DRAG_START) {
      clearTimeout(p.lp);
      const el = p.el;
      if (this.query.canAct() && this.isHand(el)) { this.beginCardDrag(el, p.x, p.y); p.mode = 'card'; }
      else if (this.query.canAct() && this.isMine(el) && this.query.canAttack(this.uid(el))) { this.select(el); p.mode = 'attack'; }
      else p.mode = 'none';
      try { this.stage.root.setPointerCapture(e.pointerId); } catch (_) { /* 既に離されている */ }
    }
    if (p.mode === 'card') this.moveCardDrag(p.x, p.y);
    else if (p.mode !== 'inspect') this.updateAim(p.x, p.y);
  }

  onUp(e, cancelled) {
    const p = this.ptr;
    if (!p || e.pointerId !== p.id) return;
    this.ptr = null;
    clearTimeout(p.lp);
    if (p.mode) this.suppressUntil = performance.now() + 350; // 直後の click を無視
    if (p.mode === 'inspect') this.hideInspect();
    else if (p.mode === 'card') this.endCardDrag(p.x, p.y, cancelled);
    else if (p.mode === 'attack') {
      const t = !cancelled && this.aim?.locked, att = this.selected;
      this.select(null);
      if (t) this.cb.onAttack(this.uid(att), this.uid(t));
    }
  }

  beginCardDrag(el, px, py) {
    this.select(null);
    const r = el.getBoundingClientRect(), c = center(r), index = [...this.stage.hand.children].indexOf(el);
    const rot = parseFloat(el.style.getPropertyValue('--r')) || 0;
    el.remove();
    layoutHand(this.stage);
    el.style.zIndex = '';
    el._drag = true;
    this.stage.drag.appendChild(el);
    const def = el._def;
    this.drag = {
      el, index, w: el.offsetWidth, h: el.offsetHeight,
      x: c.x, y: c.y, tx: px, ty: py, lastX: c.x, tilt: rot, scale: 1.15, ts: 1.15,
      holding: false, slotIdx: -1, targeted: def.kind === 'spell' && def.target !== 'none',
    };
    this.placeDrag();
    this.drag.raf = requestAnimationFrame(() => this.dragLoop());
  }

  placeDrag() {
    const g = this.drag;
    g.el.style.transform = `translate(${g.x - g.w / 2}px,${g.y - g.h / 2}px) rotate(${g.tilt}deg) scale(${g.scale})`;
  }

  /** 指に少し遅れて追従し、横方向の速さに応じて傾く（慣性のある持ち心地） */
  dragLoop() {
    const g = this.drag;
    if (!g) return;
    const tx = g.holding ? g.hx : g.tx, ty = g.holding ? g.hy : g.ty;
    g.x += (tx - g.x) * 0.42;
    g.y += (ty - g.y) * 0.42;
    const vx = g.x - g.lastX;
    g.lastX = g.x;
    g.tilt += ((g.holding ? 0 : clamp(vx * 1.5, -24, 24)) - g.tilt) * 0.22;
    g.scale += (g.ts - g.scale) * 0.25;
    this.placeDrag();
    g.raf = requestAnimationFrame(() => this.dragLoop());
  }

  moveCardDrag(x, y) {
    const g = this.drag, def = g.el._def, root = this.stage.root, inBoard = y < this.handTop() - 8;
    g.tx = x;
    g.ty = y - g.h * 0.18; // 指でカードが隠れないよう少し上に持つ
    if (def.kind !== 'spell') {
      const full = this.query.boardFull();
      root.classList.toggle('dropzone', inBoard && !full);
      g.el.classList.toggle('nope', inBoard && full);
      g.ts = inBoard ? 1 : 1.15;
      if (inBoard && !full) this.openSlot(this.slotIndexAt(x));
      else this.closeSlot();
    } else if (!g.targeted) {
      root.classList.toggle('castzone', inBoard);
      g.el.classList.toggle('armed', inBoard);
      g.ts = inBoard ? 1.25 : 1.15;
    } else {
      if (inBoard && !g.holding) {
        const { targets, kind } = this.spellAim(g.el);
        if (!targets.length) { g.el.classList.add('nope'); return; }
        // 上へ持ち上げたら、カードはその場で構えて矢印で狙う形に切り替える
        g.holding = true;
        g.hx = this.stage.particles.width / 2 + g.w * 1.25; // リーダーと重ならない位置
        g.hy = this.handTop() - g.h * 0.45;
        g.ts = 0.9;
        this.startAim(g.el, targets, kind);
      } else if (g.holding && y > this.handTop() + 12) {
        g.holding = false; g.ts = 1.15; this.endAim();
      }
      if (!inBoard) g.el.classList.remove('nope');
      if (g.holding) this.updateAim(x, y);
    }
  }

  endCardDrag(x, y, cancelled) {
    const g = this.drag;
    this.drag = null;
    cancelAnimationFrame(g.raf);
    const el = g.el, def = el._def, uid = this.uid(el), inBoard = !cancelled && y < this.handTop() - 8;
    this.stage.root.classList.remove('castzone', 'dropzone');
    el.classList.remove('nope', 'armed');
    el._tilt = g.tilt;
    el._scale = g.scale;
    const lifted = { el, index: g.index, tilt: g.tilt, scale: g.scale };
    if (def.kind !== 'spell') {
      if (inBoard && this.slot) {
        this.lifted = lifted;
        this.director.pendingSlot = this.slot;
        this.slot = null;
        this.cb.onPlayMinion(uid, g.slotIdx);
        return;
      }
      this.closeSlot();
    } else if (!g.targeted) {
      if (inBoard) { this.lifted = lifted; this.cb.onCast(uid, null); return; }
    } else {
      const t = !cancelled && g.holding && this.aim?.locked;
      this.endAim();
      if (t) { this.lifted = lifted; this.cb.onCast(uid, this.uid(t)); return; }
    }
    el._drag = false;
    returnToHand(this.stage, el, g.index, lifted);
  }

  /* ---------------- 盤面の置き場所 ---------------- */

  cardsInRow() { return [...this.stage.rows.player.querySelectorAll('.card')]; }

  slotIndexAt(x) {
    return this.cardsInRow().filter((c) => { const r = c.getBoundingClientRect(); return x > r.left + r.width / 2; }).length;
  }

  openSlot(i) {
    if (this.slot && this.drag.slotIdx === i) return;
    const row = this.stage.rows.player, before = snapshot(row), fresh = !this.slot;
    if (fresh) { this.slot = document.createElement('div'); this.slot.className = 'slot'; }
    row.insertBefore(this.slot, this.cardsInRow()[i] || null);
    this.drag.slotIdx = i;
    flip(row, before, this.slot);
    if (fresh) this.slot.animate([{ opacity: 0, transform: 'scale(.6)' }, { opacity: 1, transform: 'none' }], { duration: dur(180), easing: 'ease-out' });
  }

  closeSlot() {
    if (!this.slot) return;
    const row = this.stage.rows.player, before = snapshot(row);
    this.slot.remove();
    this.slot = null;
    if (this.drag) this.drag.slotIdx = -1;
    flip(row, before);
  }

  /* ---------------- 長押しの拡大表示 ---------------- */

  showInspect(el) {
    const box = this.stage.inspect, c = el.cloneNode(true);
    c.className = c.className.replace(/\b(ready|selected|exhausted|picked|valid|locked|charging|nope|armed)\b/g, '');
    c.style.cssText = el.style.getPropertyValue('--sh') ? `--sh:${el.style.getPropertyValue('--sh')}` : '';
    c.querySelectorAll('.flashov,.cracks').forEach((n) => n.remove());
    box.replaceChildren(c);
    box.style.display = 'grid';
    box.animate([{ opacity: 0 }, { opacity: 1 }], { duration: dur(160) });
    c.animate([{ transform: 'scale(.6) translateY(30px)', opacity: 0 }, { transform: 'none', opacity: 1 }], { duration: dur(220), easing: 'cubic-bezier(.2,.9,.3,1.1)' });
  }

  hideInspect() {
    const box = this.stage.inspect;
    box.animate([{ opacity: 1 }, { opacity: 0 }], { duration: dur(120) }).finished.then(() => { box.style.display = 'none'; box.replaceChildren(); });
  }
}

function nope(el) {
  el.animate([{ translate: '0 0' }, { translate: '-6px 0' }, { translate: '6px 0' }, { translate: '0 0' }], { duration: 250 });
}
