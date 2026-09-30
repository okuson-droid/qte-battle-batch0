// ルールが返したイベント列を、演出モジュールで順に再生する「演出係」。
// ゲームのルールと演出をつなぐのはこのファイルだけ。
import {
  createCardEl, renderHero, layoutHand, drawToHand, banner, clearBanner, sleep,
  showDamage, showHeal, showBuff, playSummon, playAttack, playDeath, playCastIntro, spellEffects,
} from '../fx/index.js';

export class Director {
  /** @param {object} stage  createStage() の戻り値 */
  constructor(stage) {
    this.stage = stage;
    /** uid → 画面上の要素（手札・盤面・リーダー） */
    this.els = new Map();
    /** uid → 最後に攻撃が当たった画面座標（破壊演出のひびの起点に使う） */
    this.lastHit = new Map();
    /** ドラッグで盤面に開けた隙間。召喚時にそこへ置く（view/input.js が設定する） */
    this.pendingSlot = null;
  }

  el(uid) {
    return this.els.get(uid) || null;
  }

  uidOf(el) {
    return el?.dataset.uid || null;
  }

  /** 現在の state をアニメーションなしで描き直す（開始時・リセット時） */
  mount(state) {
    const { stage } = this;
    this.els.clear();
    this.lastHit.clear();
    stage.particles.clear();
    clearBanner(stage);
    for (const side of ['player', 'enemy']) {
      const sd = state.sides[side];
      renderHero(stage.heroes[side], sd.hero);
      this.els.set(sd.hero.uid, stage.heroes[side]);
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
    this.sync(state);
  }

  /** 攻撃可能かどうかなど、状態に応じた見た目のクラスを付け直す */
  sync(state) {
    for (const u of state.sides.player.board) {
      const el = this.el(u.uid);
      if (!el) continue;
      const myTurn = state.turn === 'player' && !state.over;
      el.classList.toggle('ready', myTurn && !u.exhausted);
      el.classList.toggle('exhausted', myTurn && u.exhausted);
    }
    for (const u of state.sides.enemy.board) this.el(u.uid)?.classList.remove('ready', 'exhausted');
  }

  /** イベント列を順に再生する。連続する破壊イベントは同時に再生する */
  async play(events) {
    for (let i = 0; i < events.length; i++) {
      if (events[i].type === 'death') {
        const batch = [];
        while (i < events.length && events[i].type === 'death') batch.push(events[i++]);
        i--;
        await Promise.all(batch.map((e) => this.onDeath(e)));
        continue;
      }
      const handler = this['on' + events[i].type[0].toUpperCase() + events[i].type.slice(1)];
      if (!handler) throw new Error(`再生できないイベント: ${events[i].type}`);
      await handler.call(this, events[i]);
    }
  }

  /* ---------------- 各イベントの再生 ---------------- */

  async onDraw({ side, card }) {
    if (side !== 'player') return; // 相手の手札は表示しない
    const el = createCardEl(card.def, { uid: card.uid });
    this.els.set(card.uid, el);
    drawToHand(this.stage, el);
    await sleep(120);
  }

  async onSummon({ side, unit, index, fromHand }) {
    const el = createCardEl(unit.def, { uid: unit.uid, atk: unit.atk, hp: unit.hp, maxHp: unit.maxHp });
    const src = fromHand ? this.el(fromHand) : null;
    const slot = side === 'player' ? this.pendingSlot : null;
    this.pendingSlot = null;
    const done = playSummon(this.stage, { el, side, index, src, slot });
    layoutHand(this.stage); // 手札から抜けた穴をすぐ詰める
    this.els.set(unit.uid, el);
    await done;
  }

  async onAttack({ attacker, target, power, hits }) {
    const att = this.el(attacker), tgt = this.el(target);
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
  }

  async onSpell({ side, def, fromHand, targets, results }) {
    const src = fromHand ? this.el(fromHand) : null;
    const intro = playCastIntro(this.stage, def, side, src);
    layoutHand(this.stage);
    if (fromHand) this.els.delete(fromHand);
    const from = await intro;
    const effect = spellEffects[def.effect];
    if (!effect) throw new Error(`演出が未定義の呪文: ${def.effect}`);
    await effect(this.stage, {
      from, def,
      targets: targets.map((uid) => this.el(uid)),
      onHit: (i, point) => {
        const r = results[i], el = this.el(r.uid);
        if (r.kind === 'damage') showDamage(this.stage, el, r.amount, r.hp, r.maxHp);
        else if (r.kind === 'heal') showHeal(this.stage, el, r.amount, r.hp, r.maxHp);
        else if (r.kind === 'buff') showBuff(this.stage, el, r);
        if (point) this.lastHit.set(r.uid, point);
      },
    });
  }

  async onDeath({ uid }) {
    const el = this.el(uid);
    if (!el) return;
    await playDeath(this.stage, el, { hit: this.lastHit.get(uid) });
    if (!el._hero) this.els.delete(uid);
    this.lastHit.delete(uid);
  }

  async onTurn({ side }) {
    await banner(this.stage, side === 'player' ? 'あなたのターン' : '相手のターン');
  }

  async onGameOver({ winner }) {
    banner(this.stage, winner === 'player' ? 'VICTORY' : winner === 'enemy' ? 'DEFEAT' : 'DRAW', { persist: true });
  }
}

