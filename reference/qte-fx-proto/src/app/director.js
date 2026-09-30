// 段(ログ行＋出来事)を受け取り、1段ずつ間を置いて再生する「再生器」。
// 84b のキューと再生器・入力ガード・盤面を沈める・累計時間の上限(畳み)を、演出つきで再現している。
import {
  createCardEl, createBackEl, renderHero, layoutHand, drawToHand, banner, clearBanner, sleep, dur, center,
  showDamage, showHeal, showBuff, playSummon, playAttack, playDeath, playBanish, bumpPile, flyTo,
  playCastIntro, spellEffects, shake, screenFlash, flashOn, popNumber, setHp, timing,
} from '../fx/index.js';
import { PHASES } from './rules.js';

const STEP_MIN_MS = 200; // 裁定363(段の長さの下限)

export class Director {
  constructor(stage, { fxOn, budget }) {
    this.stage = stage;
    this.fxOn = fxOn;
    this.budget = budget;
    this.els = new Map();
    this.lastHit = new Map();
    this.pendingSlot = null;
    this.logN = 0;
  }

  el(uid) { return this.els.get(uid) || null; }
  uidOf(el) { return el?.dataset.uid || null; }
  pile(side, name) { return this.stage.piles[side].querySelector(`[data-pile=${name}]`); }
  lastBack() { return this.stage.backs.lastElementChild; }

  /* ---------------- 最終状態を演出なしで描く ---------------- */

  mount(state) {
    const { stage } = this;
    this.els.clear();
    this.lastHit.clear();
    stage.particles.clear();
    clearBanner(stage);
    for (const side of ['player', 'enemy']) {
      const sd = state.sides[side];
      renderHero(stage.heroes[side], sd.leader);
      stage.heroes[side].querySelector('.hhp').classList.toggle('low', sd.leader.hp <= 5);
      this.els.set(sd.leader.uid, stage.heroes[side]);
      const row = stage.rows[side];
      row.replaceChildren();
      for (const u of sd.board) {
        const el = createCardEl(u.def, { uid: u.uid, atk: u.atk, hp: u.hp, maxHp: u.maxHp });
        row.appendChild(el);
        this.els.set(u.uid, el);
      }
    }
    stage.hand.replaceChildren();
    for (const c of state.sides.player.hand) {
      const el = createCardEl(c.def, { uid: c.uid });
      stage.hand.appendChild(el);
      this.els.set(c.uid, el);
    }
    layoutHand(stage);
    const c = {};
    for (const s of ['player', 'enemy']) {
      const sd = state.sides[s];
      c[s] = { deck: sd.deck, grave: sd.grave, banish: sd.banish, taboo: sd.taboo, hand: sd.hand.length, mana: sd.mana, mp: sd.mana - sd.tapped };
    }
    this.applyCounts(c, false);
    this.showPhase(state.turn, state.phase, state.turnNo);
  }

  /** 攻撃できる・使えるなどの印を付け直す */
  sync(state, q) {
    const myTurn = state.turn === 'player' && !state.over;
    for (const u of state.sides.player.board) {
      const el = this.el(u.uid);
      if (!el) continue;
      el.classList.toggle('ready', q.canAttack(u.uid));
      el.classList.toggle('exhausted', myTurn && u.attacked);
      el.classList.toggle('sick', u.sick);
    }
    for (const u of state.sides.enemy.board) this.el(u.uid)?.classList.remove('ready', 'exhausted', 'sick');
    for (const c of state.sides.player.hand) this.el(c.uid)?.classList.toggle('playable', q.canPlay(c.uid));
    this.stage.mana.player.classList.toggle('chargeable', q.canCharge());
    this.stage.root.classList.toggle('their-turn', !myTurn);
  }

  /* ---------------- 数字の反映 ---------------- */

  applyCounts(c, animate = true) {
    if (!c) return;
    for (const s of ['player', 'enemy']) {
      const k = c[s];
      for (const name of ['deck', 'grave', 'banish', 'taboo']) {
        const b = this.pile(s, name).querySelector('b');
        if (b.textContent !== String(k[name])) {
          b.textContent = k[name];
          if (animate && name !== 'deck') bumpPile(this.pile(s, name));
        }
      }
      this.stage.mp[s].textContent = k.mp;
      this.stage.mc[s].textContent = k.mana;
      this.syncMana(s, k.mana, k.mp);
    }
    this.syncBacks(c.enemy.hand, animate);
  }

  syncMana(side, mana, mp) {
    const row = this.stage.mana[side];
    while (row.children.length < mana) row.appendChild(createBackEl('mtile'));
    while (row.children.length > mana) row.lastElementChild.remove();
    [...row.children].forEach((t, i) => t.classList.toggle('tapped', i < mana - mp));
  }

  syncBacks(n, animate) {
    const box = this.stage.backs;
    while (box.children.length > n) box.lastElementChild.remove();
    while (box.children.length < n) {
      const b = createBackEl('hback');
      box.appendChild(b);
      if (animate) b.animate([{ transform: 'translate(160px,-20px) rotate(25deg)', opacity: 0 }, { transform: 'none', opacity: 1 }],
        { duration: dur(360), easing: 'cubic-bezier(.2,.8,.2,1)' });
    }
  }

  showPhase(side, phase, turnNo) {
    const who = side === 'player' ? 'あなたの番' : '相手の番';
    this.stage.turn.innerHTML = `ターン${turnNo} / <b>${phase}フェイズ</b>(${who})`;
    this.stage.turn.classList.toggle('opp', side !== 'player');
    this.stage.phases.querySelectorAll('li').forEach((li) => li.classList.toggle('on', li.dataset.phase === phase));
  }

  /* ---------------- ログ ---------------- */

  log(text, now = false) {
    const li = document.createElement('li');
    li.textContent = text;
    this.stage.log.querySelectorAll('li.now').forEach((x) => x.classList.remove('now'));
    if (now) li.classList.add('now');
    this.stage.log.appendChild(li);
    this.stage.log.scrollTop = this.stage.log.scrollHeight;
    this.stage.logLast.textContent = text;
    this.stage.logCount.textContent = ++this.logN + '件';
  }

  clearLog() {
    this.stage.log.replaceChildren();
    this.logN = 0;
    this.stage.logLast.textContent = '(ログなし)';
    this.stage.logCount.textContent = '0件';
  }

  /* ---------------- 再生器 ---------------- */

  /**
   * 1操作ぶんの段を再生する。演出オフなら再生せず最終状態を描く(84b 3章)。
   * 累計が上限を超えたら残りを畳み、畳んだことを帯で示す(裁定364・368)。
   */
  async run(steps, state) {
    const { stage } = this;
    stage.field.classList.add('auto-step-sink');
    stage.stepbar.hidden = true;
    const animate = this.fxOn() && !timing.reduced;
    const t0 = performance.now(), budget = this.budget();
    let i = 0;
    try {
      for (; i < steps.length; i++) {
        if (!animate) break;
        if (budget && performance.now() - t0 > budget) break;
        const s = steps[i], ts = performance.now();
        if (s.log) this.log(s.log, true);
        await this.playEvents(s.events);
        const left = s.log ? STEP_MIN_MS - (performance.now() - ts) : 0;
        if (left > 0) await sleep(left);
      }
      if (i < steps.length) {
        const rest = steps.slice(i).filter((s) => s.log);
        rest.forEach((s) => this.log(s.log));
        this.mount(state);
        const last = [...steps].reverse().flatMap((s) => s.events).find((e) => e.type === 'gameOver');
        if (last) this.onGameOver(last);
        if (animate && rest.length) {
          stage.stepbar.textContent = `残り${rest.length}段は演出を省いた(ログに全部残っている)`;
          stage.stepbar.hidden = false;
        }
      }
    } finally {
      stage.field.classList.remove('auto-step-sink');
      stage.log.querySelectorAll('li.now').forEach((x) => x.classList.remove('now'));
    }
  }

  async playEvents(events) {
    for (let i = 0; i < events.length; i++) {
      const e = events[i];
      if (e.type === 'death' || e.type === 'banish') {
        const batch = [];
        while (i < events.length && (events[i].type === 'death' || events[i].type === 'banish')) batch.push(events[i++]);
        i--;
        await Promise.all(batch.map((b) => (b.type === 'death' ? this.onDeath(b) : this.onBanish(b))));
        this.applyCounts(batch[batch.length - 1].counts);
        continue;
      }
      const h = this['on' + e.type[0].toUpperCase() + e.type.slice(1)];
      if (!h) throw new Error('再生できない出来事: ' + e.type);
      await h.call(this, e);
      if (e.type === 'pay') for (const s of ['player', 'enemy']) this.syncMana(s, e.counts[s].mana, e.counts[s].mp);
      else this.applyCounts(e.counts);
    }
  }

  /* ---------------- 出来事ごとの再生 ---------------- */

  async onPhase({ side, phase, turnNo }) { this.showPhase(side, phase, turnNo); }

  async onTurn({ side }) {
    await banner(this.stage, side === 'player' ? 'あなたのターン' : '相手のターン');
  }

  async onUntap({ side }) {
    this.stage.rows[side].querySelectorAll('.card').forEach((c) => c.classList.remove('exhausted', 'sick'));
  }

  async onPay() { /* マナが横になるのは applyCounts が行う */ }

  async onDraw({ side, card }) {
    if (side !== 'player' || !card) return;
    const el = createCardEl(card.def, { uid: card.uid });
    this.els.set(card.uid, el);
    drawToHand(this.stage, el);
    await sleep(160);
  }

  async onCharge({ side, uid, counts }) {
    const k = counts[side];
    let from, ghost = createBackEl('mtile ghost');
    if (side === 'player') {
      const el = this.el(uid);
      from = el.getBoundingClientRect();
      await el.animate([{ transform: el.style.transform || 'none' }, { transform: `${el.style.transform || ''} scaleX(0)` }],
        { duration: dur(140), fill: 'forwards' }).finished;
      el.remove();
      this.els.delete(uid);
      layoutHand(this.stage);
    } else {
      const b = this.lastBack();
      from = b.getBoundingClientRect();
      b.remove();
    }
    this.syncMana(side, k.mana, k.mp);
    await flyTo(this.stage, from, this.stage.mana[side].lastElementChild, ghost, 420);
  }

  async onSummon({ side, unit, index, fromHand }) {
    const el = createCardEl(unit.def, { uid: unit.uid, atk: unit.atk, hp: unit.hp, maxHp: unit.maxHp });
    const src = side === 'player' ? this.el(fromHand) : this.lastBack();
    const slot = side === 'player' ? this.pendingSlot : null;
    this.pendingSlot = null;
    if (side === 'player') this.els.delete(fromHand);
    const done = playSummon(this.stage, { el, side, index, src, slot });
    layoutHand(this.stage);
    this.els.set(unit.uid, el);
    await done;
  }

  async onZap({ from, target, amount, hp, maxHp }) {
    const a = this.el(from), t = this.el(target);
    const ac = center(a.getBoundingClientRect()), tc = center(t.getBoundingClientRect());
    this.stage.particles.add({ type: 'bolt', pts: [[ac.x, ac.y], [tc.x, tc.y]], c: '140,90%,70%', life: 420 });
    this.stage.particles.add({ type: 'ring', x: tc.x, y: tc.y, r0: 6, r1: 70, w: 5, c: '140,90%,75%', life: 380 });
    shake(this.stage, 5, 220);
    showDamage(this.stage, t, amount, hp, maxHp);
    this.lastHit.set(target, tc);
    await sleep(520);
  }

  async onQuake({ from }) {
    const a = this.el(from), r = a.getBoundingClientRect(), c = center(r);
    this.stage.dim.animate([{ opacity: 0 }, { opacity: 0.8, offset: 0.3 }, { opacity: 0 }], { duration: dur(1100) });
    this.stage.particles.add({ type: 'rune', x: c.x, y: c.y, r: r.width * 1.6, flat: 0.5, n: 8, c: '326,90%,70%', life: 1100 });
    for (let i = 0; i < 3; i++) this.stage.particles.add({ type: 'ring', x: c.x, y: c.y, r0: 20, r1: 900, flat: 0.45, w: 10, c: '326,90%,72%', life: 800, delay: i * 140 });
    await sleep(380);
    screenFlash(this.stage, 0.5, 380);
    shake(this.stage, 16, 520);
    this.stage.rows.player.querySelectorAll('.card').forEach((x) => x !== a && flashOn(x, '#ffd0f0', 380));
    this.stage.rows.enemy.querySelectorAll('.card').forEach((x) => x !== a && flashOn(x, '#ffd0f0', 380));
    await sleep(420);
  }

  async onCast({ side, def, fromHand, targets, results }) {
    const src = side === 'player' ? this.el(fromHand) : this.lastBack();
    const intro = playCastIntro(this.stage, def, side, src);
    layoutHand(this.stage);
    if (side === 'player') this.els.delete(fromHand);
    const from = await intro;
    const effect = spellEffects[def.effect];
    await effect(this.stage, {
      from, def,
      targets: targets.map((u) => this.el(u)),
      onHit: (i, point) => {
        const r = results[i], el = this.el(r.uid);
        if (r.kind === 'damage') showDamage(this.stage, el, r.amount, r.hp, r.maxHp);
        else if (r.kind === 'heal') showHeal(this.stage, el, r.amount, r.hp, r.maxHp);
        else if (r.kind === 'buff') showBuff(this.stage, el, r);
        if (point) this.lastHit.set(r.uid, point);
      },
    });
  }

  async onAttack({ attacker, target, power, hits }) {
    const att = this.el(attacker), tgt = this.el(target);
    const lane = att.closest('.q-lane');
    lane?.classList.add('fx-top');
    await playAttack(this.stage, att, tgt, {
      power,
      counter: hits.some((h) => h.uid === attacker),
      onImpact: (point) => {
        for (const h of hits) {
          showDamage(this.stage, this.el(h.uid), h.amount, h.hp, h.maxHp);
          this.lastHit.set(h.uid, point);
        }
      },
    });
    lane?.classList.remove('fx-top');
  }

  async onDeath({ uid, hero }) {
    const el = this.el(uid);
    if (!el) return;
    await playDeath(this.stage, el, { hit: this.lastHit.get(uid), remove: !hero });
    if (!hero) this.els.delete(uid);
    this.lastHit.delete(uid);
  }

  async onBanish({ uid, side }) {
    const el = this.el(uid);
    if (!el) return;
    await playBanish(this.stage, el, this.pile(side, 'banish'));
    this.els.delete(uid);
  }

  async onGameOver({ winner }) {
    banner(this.stage, winner === 'player' ? 'VICTORY' : winner === 'enemy' ? 'DEFEAT' : 'DRAW', { persist: true });
  }
}

export { PHASES };
