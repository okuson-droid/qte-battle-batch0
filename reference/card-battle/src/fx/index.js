// 演出モジュールの公開窓口。アプリからはここだけを import する。
// どの関数もゲームのルールを知らない。「どの要素を・どう動かすか」だけを受け取る。

export { timing, setSpeed, sleep } from './core.js';
export { createStage, banner, clearBanner, shake, screenFlash, flashOn, popNumber, snapshot, flip } from './stage.js';
export { createCardEl, renderHero, setHp, setStats } from './cardView.js';
export { layoutHand, drawToHand, returnToHand } from './hand.js';
export { showDamage, showHeal, showBuff } from './feedback.js';
export { playSummon } from './summon.js';
export { playAttack } from './attack.js';
export { playDeath } from './death.js';
export { playCastIntro, spellEffects } from './spells.js';
