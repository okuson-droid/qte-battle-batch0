// デモの組み立て：ルール（game/）・演出（fx/）・演出係（view/director.js）・操作（view/input.js）をつなぐ。
// 自分のアプリに組み込むときは、このファイルを手本に「操作 → ルール → イベント → 再生」の流れを作る。
import { createStage, setSpeed } from './fx/index.js';
import { Director } from './view/director.js';
import { Input } from './view/input.js';
import { MINIONS, SPELLS } from './game/cards.js';
import {
  createGame, drawCard, playMinion, attack, castSpell, endTurn, RuleError,
  canAttack, attackTargets, spellTargets, boardFull,
} from './game/rules.js';
import { runEnemyTurn } from './game/ai.js';

const $ = (sel) => document.querySelector(sel);
const stage = createStage($('#game'));
const director = new Director(stage);
const endTurnBtn = $('#endTurn');

let state = null;
let busy = false;

const handDef = (uid) => state.sides.player.hand.find((c) => c.uid === uid)?.def;

const input = new Input(stage, director, {
  canAct: () => !busy && !state.over && state.turn === 'player',
  canAttack: (uid) => canAttack(state, uid),
  attackTargets: (uid) => attackTargets(state, uid),
  spellTargets: (uid) => spellTargets(state, 'player', handDef(uid)),
  boardFull: () => boardFull(state, 'player'),
}, {
  onPlayMinion: (uid, index) => act(() => playMinion(state, 'player', { uid }, index)),
  onAttack: (att, tgt) => act(() => attack(state, att, tgt)),
  onCast: (uid, target) => act(() => castSpell(state, 'player', { uid }, target)),
});

/**
 * ルールを実行し、返ってきたイベントを再生する。
 * ルールに断られた（RuleError）ときは、持ち上げていたカードを手札に戻す。
 */
async function act(rule) {
  if (busy || state.over) return;
  let events;
  try {
    events = rule();
  } catch (err) {
    if (!(err instanceof RuleError)) throw err;
    input.restore();
    console.info(err.message);
    return;
  }
  input.settle();
  busy = true;
  endTurnBtn.disabled = true;
  try {
    await director.play(events);
  } finally {
    director.sync(state);
    busy = false;
    endTurnBtn.disabled = state.over;
  }
}

function newGame() {
  input.reset();
  state = createGame();
  // デモ用の初期配置：相手の盤面に 3 体を置いた状態から始める（演出なし）
  const pick = (list) => list[Math.floor(Math.random() * list.length)];
  const commons = MINIONS.filter((d) => d.rarity !== 'legendary');
  for (let i = 0; i < 3; i++) {
    const def = pick(commons);
    state.sides.enemy.board.push({ uid: `e${i}`, def, side: 'enemy', atk: def.atk, hp: def.hp, maxHp: def.hp, exhausted: false });
  }
  director.mount(state);
  busy = false;
  endTurnBtn.disabled = false;
  // 最初の手札は演出つきで配る（レジェンドと呪文を必ず含める）
  const byId = (id) => [...MINIONS, ...SPELLS].find((d) => d.id === id);
  const opening = [pick(commons), byId('swordsman'), pick(MINIONS.filter((d) => d.rarity === 'legendary')), byId('fireball'), byId('chain'), byId('buff')];
  act(() => opening.flatMap((def) => drawCard(state, 'player', def)));
}

endTurnBtn.addEventListener('click', () => {
  input.reset();
  act(() => {
    const events = [...endTurn(state), ...runEnemyTurn(state)];
    if (!state.over) events.push(...endTurn(state));
    return events;
  });
});
$('#bDraw').addEventListener('click', () => act(() => drawCard(state, 'player')));
$('#bReset').addEventListener('click', newGame);
$('#speed').addEventListener('input', (e) => {
  setSpeed(+e.target.value);
  $('#spv').textContent = e.target.value + 'x';
});

newGame();
