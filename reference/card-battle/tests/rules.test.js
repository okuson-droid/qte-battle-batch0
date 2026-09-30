// ルールの単体テスト（DOM 不要）。実行: node --test tests/
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { MINIONS, SPELLS } from '../src/game/cards.js';
import {
  createGame, drawCard, playMinion, attack, castSpell, endTurn, RuleError, MAX_BOARD, heroUid,
} from '../src/game/rules.js';
import { runEnemyTurn } from '../src/game/ai.js';

const def = (id) => [...MINIONS, ...SPELLS].find((d) => d.id === id);
/** 決まった値を順に返す乱数（テストを再現可能にする） */
const seq = (...xs) => { let i = 0; return () => xs[i++ % xs.length]; };

function withHand(...ids) {
  const s = createGame({ rng: seq(0.5) });
  const uids = ids.map((id) => drawCard(s, 'player', def(id))[0].card.uid);
  return { s, uids };
}

test('手札のミニオンを指定位置に出すと summon イベントが返る', () => {
  const { s, uids } = withHand('wolf', 'archer');
  playMinion(s, 'player', { uid: uids[0] });
  const ev = playMinion(s, 'player', { uid: uids[1] }, 0);
  assert.equal(ev[0].type, 'summon');
  assert.equal(ev[0].index, 0);
  assert.equal(ev[0].fromHand, uids[1]);
  assert.deepEqual(s.sides.player.board.map((u) => u.def.id), ['archer', 'wolf']);
  assert.equal(s.sides.player.hand.length, 0);
});

test('盤面が満員なら RuleError で断られ、手札は減らない', () => {
  const { s, uids } = withHand(...Array(MAX_BOARD + 1).fill('beetle'));
  for (let i = 0; i < MAX_BOARD; i++) playMinion(s, 'player', { uid: uids[i] });
  assert.throws(() => playMinion(s, 'player', { uid: uids[MAX_BOARD] }), RuleError);
  assert.equal(s.sides.player.hand.length, 1);
});

test('攻撃はお互いにダメージを与え、倒れた側に death イベントが出る', () => {
  const { s, uids } = withHand('swordsman'); // 4/3
  playMinion(s, 'player', { uid: uids[0] });
  s.turn = 'enemy';
  playMinion(s, 'enemy', { def: def('wolf') }); // 3/2
  s.turn = 'player';
  const [att, tgt] = [s.sides.player.board[0], s.sides.enemy.board[0]];
  const ev = attack(s, att.uid, tgt.uid);
  assert.equal(ev[0].type, 'attack');
  assert.deepEqual(ev[0].hits.map((h) => [h.uid, h.hp]), [[tgt.uid, -2], [att.uid, 0]]);
  assert.deepEqual(ev.filter((e) => e.type === 'death').map((e) => e.uid).sort(), [att.uid, tgt.uid].sort());
  assert.equal(s.sides.player.board.length, 0);
});

test('攻撃済みのミニオンはもう一度攻撃できない', () => {
  const { s, uids } = withHand('sentinel');
  playMinion(s, 'player', { uid: uids[0] });
  const u = s.sides.player.board[0];
  attack(s, u.uid, heroUid('enemy'));
  assert.throws(() => attack(s, u.uid, heroUid('enemy')), RuleError);
});

test('雷鳴の連鎖は敵ミニオン全体に当たり、いなければリーダーに当たる', () => {
  const { s, uids } = withHand('chain', 'chain');
  let ev = castSpell(s, 'player', { uid: uids[0] });
  assert.deepEqual(ev[0].targets, [heroUid('enemy')]);
  s.turn = 'enemy';
  playMinion(s, 'enemy', { def: def('sentinel') });
  playMinion(s, 'enemy', { def: def('beetle') });
  s.turn = 'player';
  ev = castSpell(s, 'player', { uid: uids[1] });
  assert.equal(ev[0].results.length, 2);
  assert.ok(ev.some((e) => e.type === 'death')); // 甲虫（体力1）は倒れる
});

test('回復は最大体力を超えず、強化は最大体力ごと上がる', () => {
  const { s, uids } = withHand('heal', 'archer', 'buff');
  s.sides.player.hero.hp = 18;
  const heal = castSpell(s, 'player', { uid: uids[0] }, heroUid('player'))[0].results[0];
  assert.deepEqual([heal.amount, heal.hp], [2, 20]);
  playMinion(s, 'player', { uid: uids[1] });
  const unit = s.sides.player.board[0];
  const buff = castSpell(s, 'player', { uid: uids[2] }, unit.uid)[0].results[0];
  assert.deepEqual([buff.atk, buff.hp, buff.maxHp], [4, 5, 5]);
});

test('対象の要る呪文に不正な対象を渡すと断られる', () => {
  const { s, uids } = withHand('fireball');
  assert.throws(() => castSpell(s, 'player', { uid: uids[0] }, heroUid('player')), RuleError);
});

test('リーダーの体力が 0 になるとゲーム終了', () => {
  const { s, uids } = withHand('fireball');
  s.sides.enemy.hero.hp = 3;
  const ev = castSpell(s, 'player', { uid: uids[0] }, heroUid('enemy'));
  assert.equal(ev.at(-1).type, 'gameOver');
  assert.equal(s.winner, 'player');
  assert.throws(() => endTurn(s), RuleError);
});

test('敵 AI のターンは例外を出さずに最後まで進む（多数回）', () => {
  for (let n = 0; n < 300; n++) {
    const s = createGame();
    for (let turn = 0; turn < 30 && !s.over; turn++) {
      endTurn(s);
      runEnemyTurn(s);
      if (!s.over) endTurn(s);
    }
    assert.ok(s.sides.enemy.board.length <= MAX_BOARD);
  }
});
