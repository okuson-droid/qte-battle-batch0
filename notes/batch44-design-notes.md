# Batch 44 設計解説 — パイル・マナタイル・リーダー合体タイル(案1)

作成: 2026-08-16。レイアウト案(batch44-layout-proposal.md)の**案1をマスターが承認**、
B1・B2(Java 小)も同バッチで実施の裁定を受けての実装。

**★Java に手が入った(このシリーズで初)。**`PlayerView` に2項目、`GameViewBuilder` に対応する
組み立てを追加した。verify **514/514**(+7)。版数: **css v=44 / battle.js v=17**。

| ファイル | 内容 |
|---|---|
| `game/view/PlayerView.java` | ★`List<CardView> lost`(B1)と `String weaponCardId`(B2)を追加 |
| `game/view/GameViewBuilder.java` | ★上記2項目の組み立て(消滅は墓地と同じ公開情報・IDだけ足すのは裁定144) |
| `templates/battle.html` | 帯の再構成(リーダー合体タイル・パイル容器・手札裏面列)・ホバー要素 |
| `static/js/battle.js` | `renderPiles` / `pileEl` / `showZoneFaces` / マナタイル / `attachHover` / `weaponFaceData` |
| `static/css/battle.css` | `.auto-leader` / `.auto-pile*` / `.auto-back` / `.auto-mana-row` / `.auto-hover` |
| `verify/` | fixture に lost / weaponCardId・+7項目・autoshot 更新 |

## 1. 設計の要点

- **枚数バッジが従来のチップの id を引き継ぐ。** `opp-deck-count` 等の id はパイルの
  バッジ要素に移り、数字を書き込む既存コードは1行も変えていない(42・43 と同じ手法)。
  禁忌パイルは 43 の開閉トグルの id(`btn-taboo-toggle`)も引き継ぐ。
- **山札パイルは裏面+枚数だけ。**クリック経路も作らない(仕分け D1)。検証 44-2 が番人。
- **墓地・消滅の最上段 = ビューの末尾**(サーバは `.add()` で積む=末尾が最新。確認済み)。
  クリックで全量を**フェイスの一覧**(文字列モーダルの格上げ)。
- **マナタイル**: 手動モードの `.mana-tile` を基底に、文明色は cardId → card-library。
  タップ=回転・裏向き=裏面(自分の裏マナは title で中身が分かる。ビューが持ち主にだけ
  中身を送る、というサーバの既存フィルタに乗っただけ)。
  ★**`flex-wrap: nowrap` が肝**である。折り返しを許すと横に溢れず、重なり判定
  (`scrollWidth > clientWidth`)が永久に発火しない —— 2段に折れて縦を 70px 食い、
  43-1(1画面)が落ちて発覚した。
- **リーダー合体タイル**: 名前・LP・**効果の先頭2行(常時)**・ウェポン行。
  全文はホバー(350ms・B-1 のホバープレビュー初導入)と右クリック。
  ウェポン行の右クリックはウェポンの面(効果は `weaponCardId` → card-library。裁定144)。
- 1画面(1280×800)は維持。`.auto-leader` の `min-height` が content-box で膨れる罠を
  `box-sizing: border-box` で潰した。

## 2. 検証

+7項目: パイルの結線(最上段=末尾・バッジの id)/ **山札の非公開の番人** /
マナタイル(名前・回転・裏面・1行)/ 相手手札の裏面列 / リーダーのホバー(実マウス)/
ウェポンの B2 結線 / 墓地のフェイス一覧。
わざと壊す確認3通り(最上段を先頭から取る / 山札に面を出す / ウェポンを名前引きに戻す)
すべて意図どおり落ちた。

## 3. ★★確認手順(マスターの手元。今回は Java があるので1手多い)

1. Eclipse refresh(F5)→ **プロジェクトがコンパイルできること**(PlayerView / GameViewBuilder)。
2. **`mvn test`** — 既存テストは PlayerView に触れていないため通る見込みだが、
   Java を触った以上必ず回すこと(35〜38 のぶんが未実行ならまとめて)。
3. スーパーリロード(css v=44 / battle.js v=17)。
4. 盤面: パイル(山・墓・消・禁)/ マナの名前タイル / リーダーの効果2行 /
   相手手札の裏面列。墓地パイルをクリック → フェイスの一覧。
5. リーダーにマウスを乗せて全文プレビュー、ウェポン行を右クリックで効果。
6. ★消滅にカードが行く対戦をして、消滅パイルに面が出ること(B1 の実地確認)。

## 4. 積み残し

- 演出 fx 層・SFX の移植(次候補 Batch 45)→ 攻撃演出の語彙(46)。
- マナタイルの重なり幅は固定(-26px)。枚数に応じた可変幅(手動の applyManaOverlap 相当)は
  必要になってから。
- 観戦者(C1)は独立バッチのまま。
