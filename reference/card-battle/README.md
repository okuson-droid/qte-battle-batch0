# カードバトル演出モジュール

ブラウザで動くカードゲーム向けの演出（召喚・攻撃・破壊・呪文）と、ドラッグ操作一式である。
依存ライブラリはなく、素の JavaScript（ES モジュール）で書かれている。

## 動かし方

- すぐに見たい場合は `dist/card_battle_demo.html` をブラウザで開く。1 ファイルにまとめたデモである。
- ソースのまま動かす場合は、`index.html` を HTTP サーバー経由で開く（ES モジュールは `file://` では読み込めない）。
  例: `npx serve .` や VS Code の Live Server。
- ルールのテストは `npm test`（Node.js 20 以上。DOM は不要）。
- `dist/` を作り直すには `npm install` のあと `npm run build`。

## 構成

```
src/
  game/          ルール（DOM を一切使わない）
    cards.js       カード定義
    rules.js       状態と操作。操作するとイベントの配列を返す
    ai.js          相手の行動（デモ用）
  fx/            演出（ルールを一切知らない）
    index.js       公開窓口。アプリからはここだけを import する
    stage.js       盤面の DOM 骨格、画面揺れ・閃光・数字・帯テロップ
    particles.js   canvas のパーティクル
    cardView.js    カード・リーダーの DOM と数値表示
    hand.js        手札の扇形配置・ドロー・手札へ戻す動き
    summon.js / attack.js / death.js / spells.js   各演出
    feedback.js    ダメージ・回復・強化の数値反応
  view/
    director.js    イベントを受け取り、演出を順に再生する（ルールと演出の接点）
    input.js       ドラッグ・タップ・長押しの操作
  main.js        デモの組み立て
  style.css      盤面とカードの見た目
tests/rules.test.js
```

依存の向きは `main.js → view/ → fx/` と `main.js → game/` の一方向である。
`fx/` と `game/` は互いを参照しない。

## 処理の流れ

```
操作（input.js） → ルール（rules.js）が state を更新しイベントを返す → director.js がイベントを順に再生
```

ルールは瞬時に結果を確定させ、画面はその結果を後から追いかけて再生する。
このため、ルール側を別の実装（サーバー、Java など）に差し替えても、同じ形式のイベントを渡せば演出はそのまま使える。

## イベントの形式

`director.play(events)` に渡す配列の要素。`uid` はカード・ミニオンの識別子、リーダーは `hero:player` / `hero:enemy`。

- `draw`：`side`, `card: {uid, def}`。手札に滑り込む（相手側は表示しない）。
- `summon`：`side`, `unit: {uid, def, atk, hp, maxHp}`, `index`, `fromHand`。召喚演出。
- `attack`：`attacker`, `target`, `power`, `hits: [{uid, amount, hp, maxHp}]`。攻撃演出。着弾の瞬間に `hits` を表示へ反映する。
- `spell`：`side`, `def`, `fromHand`, `targets`, `results: [{kind, uid, ...}]`。詠唱と効果。`kind` は `damage` / `heal` / `buff`。
- `death`：`uid`, `side`, `hero?`。破壊演出。連続する `death` は同時に再生する。
- `turn`：`side`。「あなたのターン」などの帯テロップ。
- `gameOver`：`winner`。VICTORY / DEFEAT。

## 自分のアプリへの組み込み

```js
import { createStage } from './src/fx/index.js';
import { Director } from './src/view/director.js';
import { Input } from './src/view/input.js';

const stage = createStage(document.querySelector('#game')); // 大きさは親要素が決める
const director = new Director(stage);
director.mount(state);                 // 現在の状態を演出なしで描く

const input = new Input(stage, director, {
  canAct: () => !busy,                 // いま操作を受け付けるか
  canAttack: (uid) => ...,
  attackTargets: (uid) => [...uid],
  spellTargets: (cardUid) => [...uid], // 対象が要らない呪文は []
  boardFull: () => ...,
}, {
  onPlayMinion: (cardUid, index) => ..., // ルールを実行 → イベントを director.play へ
  onAttack: (attackerUid, targetUid) => ...,
  onCast: (cardUid, targetUid) => ...,
});
```

- ルールが操作を断ったときは `input.restore()` を呼ぶと、持ち上げていたカードが手札に戻る。受け付けたときは `input.settle()` を呼ぶ。
- 再生が終わったら `director.sync(state)` で「攻撃可能」などの見た目を付け直す。
- 演出だけを個別に使うこともできる（`playSummon`, `playAttack`, `playDeath`, `playCastIntro` と `spellEffects`）。引数は DOM 要素と数値だけである。

### 新しい呪文を足すとき

1. `game/cards.js` の `SPELLS` に定義を足す（`effect` に効果名）。
2. `game/rules.js` の `castSpell` に効果の処理を足し、`results` を返す。
3. `fx/spells.js` の `spellEffects` に同じ名前の演出を足す。着弾の瞬間に `onHit(i, 座標)` を呼ぶ。

## 注意点

- 座標を使う演出レイヤー（パーティクル、破片、矢印など）は `position: fixed` で画面座標に置いている。
  盤面の祖先要素に `transform` や `filter` を付けると位置がずれるため、付けないこと。
- カードの大きさは盤面の幅から決まる（コンテナクエリ単位 `cqw`）。
- OS の「視差効果を減らす」設定が有効なときは、画面揺れ・全画面の閃光・破片の演出を省略する。
- 演出の速さは `setSpeed(倍率)` で変えられる。
