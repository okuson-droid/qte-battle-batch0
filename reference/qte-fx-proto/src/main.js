// プロトタイプの組み立て:操作(input) → ルール(rules:本物ではサーバ) → 段 → 再生器(director)。
import { createStage, sleep } from './fx/index.js';
import { Director } from './app/director.js';
import { Input } from './app/input.js';
import { cardById } from './app/cards.js';
import {
  createGame, seed, drawCard, play, attack, charge, nextPhase, endTurn, RuleError,
  canAttack, attackReason, attackTargets, playReason, chargeReason, spellTargets, MAX_BOARD,
} from './app/rules.js';
import { enemyTurn } from './app/ai.js';

const stage = createStage(document.querySelector('#game'));

stage.demo.innerHTML = `
  <label title="オフにすると段を再生せず、最終状態だけを描く(84b 3章)"><input type="checkbox" id="optFx" checked> 演出</label>
  <label title="1操作あたりの総再生時間の上限(裁定364)。超えた残りは畳む">上限
    <select id="optBudget"><option value="4000">4秒(84b の現行値)</option><option value="8000" selected>8秒</option><option value="0">なし</option></select></label>
  <button type="button" id="optDraw">1枚引く</button>
  <button type="button" id="optReset">最初から</button>`;
const $ = (id) => document.getElementById(id);

let fxOn = true, budget = 8000;
const director = new Director(stage, { fxOn: () => fxOn, budget: () => budget });

let state = null, busy = false, toastTimer = 0;
const handDef = (uid) => state.sides.player.hand.find((c) => c.uid === uid)?.def;

const query = {
  canAct: () => !busy && !state.over && state.turn === 'player',
  canAttack: (uid) => !busy && canAttack(state, uid),
  attackReason: (uid) => attackReason(state, uid),
  attackTargets: (uid) => attackTargets(state, uid),
  playReason: (uid) => playReason(state, 'player', uid),
  canPlay: (uid) => !playReason(state, 'player', uid),
  spellTargets: (uid) => spellTargets(state, 'player', handDef(uid)),
  chargeReason: () => chargeReason(state, 'player'),
  canCharge: () => !chargeReason(state, 'player'),
  boardFull: () => state.sides.player.board.length >= MAX_BOARD,
};

function toast(msg) {
  stage.toast.textContent = msg;
  stage.toast.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { stage.toast.hidden = true; }, 1800);
}

const input = new Input(stage, director, query, {
  onPlayMinion: (uid, index) => act(() => play(state, 'player', uid, null, index)),
  onCast: (uid, target) => act(() => play(state, 'player', uid, target)),
  onAttack: (a, t) => act(() => attack(state, a, t)),
  onCharge: (uid) => act(() => charge(state, 'player', uid)),
  onReject: (why) => why && toast(why),
});

function refresh() {
  director.sync(state, query);
  const mine = query.canAct();
  stage.btnNext.disabled = !mine;
  stage.btnEnd.disabled = !mine;
}

/** ルールを実行し、返ってきた段を再生する(84b: 入力ガードは送信の口 = ここに置く) */
async function act(rule) {
  if (busy || state.over) return;
  let steps;
  try {
    steps = rule();
  } catch (err) {
    if (!(err instanceof RuleError)) throw err;
    input.restore();
    toast(err.message);
    return;
  }
  input.settle();
  await replay(steps);
  if (state.turn === 'enemy' && !state.over) await runEnemy();
}

async function replay(steps) {
  busy = true;
  refresh();
  try {
    await director.run(steps, state);
  } finally {
    busy = false;
    refresh();
  }
}

/** 相手の手番も、自分の操作と同じ再生器・同じ強さで再生する(Q3) */
async function runEnemy() {
  for (const steps of enemyTurn(state)) {
    await replay(steps);
    await sleep(fxOn ? 380 : 60);
  }
}

function newGame() {
  input.reset();
  state = createGame();
  seed(state);
  director.clearLog();
  director.mount(state);
  busy = false;
  stage.stepbar.hidden = true;
  const opening = ['s-fire-1', 'm-wind-1', 's-wind-1', 's-dark-2', 's-dark-1', 'm-earth-2', 's-water-1', 's-earth-1'];
  act(() => opening.flatMap((id) => drawCard(state, 'player', null, cardById(id))));
}

stage.btnNext.addEventListener('click', () => { input.reset(); act(() => nextPhase(state, 'player')); });
stage.btnEnd.addEventListener('click', () => { input.reset(); act(() => endTurn(state, 'player')); });
$('optFx').addEventListener('change', (e) => { fxOn = e.target.checked; });
$('optBudget').addEventListener('change', (e) => { budget = +e.target.value; });
$('optDraw').addEventListener('click', () => { if (query.canAct()) act(() => drawCard(state, 'player')); });
$('optReset').addEventListener('click', () => { if (!busy) newGame(); });

newGame();
