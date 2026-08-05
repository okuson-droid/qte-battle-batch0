# QTE 対戦アプリ — 引き継ぎ書

最終更新: 2026-08-05(Batch 21c 完了。**手動モード フェーズ2は 21a〜21c で完了**)
**★21c は画面のバッチだが、Java を4ファイル触った(先攻選択権のみ。マスター承認済み)。
理由は 0章と `notes/batch21c-design-notes.md` 1-5。**
**★`mvn test` が未実行である(サンドボックスから Maven Central へ到達できない)。
21c は Java 変更が21bより重い(JUnit 4件追加)。納品後、必ずマスターの手元で走らせること。**

**★新しいチャットを始めるときは 5章「チャット開始テンプレート」を使うこと。**

このファイルは、新しいチャットで作業を再開するための最小限の情報をまとめたものである。
ゲームルール・設計判断の詳細は `qte-project-reference.md`、カードの詳細は `qte-cards.json`。

系統が2つ並行している。**混ぜないこと。**

| 系統 | 前提ドキュメント |
|---|---|
| **手動モード フェーズ2**(21a〜21c・**完了**) | `notes/batch21-versus-ui-design.md`(**唯一の正**)+ `batch16-manual-mode-design-v2_4.md` + `notes/batch21a-design-notes.md` + `notes/batch21b-design-notes.md` + `notes/batch21c-design-notes.md` |
| **手動モード フェーズ1**(17a〜20c・完成) | `batch16-manual-mode-design-v2_4.md` + `notes/batch20b-ui-design.md` + `notes/batch20b-design-notes.md` + `notes/batch20c-design-notes.md` |
| **Ver.0.4 対応**(15c/15d/15e) | `notes/ver0.4-transcription-notes.md` + `notes/batch15a-design-notes.md` + `notes/batch15b-design-notes.md` |

---

## 0. 最初にやること

1. `qte-project-reference.md` を読む(ゲームルール・設計判断の唯一の正)。
   **★このファイルはプロジェクトナレッジ内で2026-07-21時点の記述のまま止まっている
   (3文明実装時点)。実際は6文明完成・Ver.0.4対応中であり、1章の状態記述が古い。
   ゲームルール自体(2章以降)は有効。発注者側での更新を推奨する。**
2. 作業に応じて上表の前提ドキュメントを読む。
3. ソースコードを取得する。**zipのアップロードは不要**。

   **★★`codeload.github.com` の zip URL は環境によっては 403 で落ちる。**
   その場合は git clone を使うこと。こちらは通る。

```bash
git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git
```

4. **★「反映済み」という記述を信じず、直近バッチで変更したはずの箇所を実際に読んで照合する。**
   `git log` / `git diff HEAD~1` も併用すると速い。

## 21c の確認項目(次チャットで一度だけ照合すること)

- `manual-battle.html` の `<link>` が `battle.css(v=19)`、`<script>` が
  `manual-battle.js(v=11)` になっていること。
- `manual-battle.html` のヘッダに `id="btn-spectator-view"` / `id="btn-flip"` /
  `id="btn-first-player"` の3つがあること(いずれも既定は `d-none`)。
- `manual-battle.js` に `renderOpponentTop` があり、その中で
  `createOpponentMana` / `createOpponentPile` / `createOpponentChip` を呼んでいること。
  ★`OPPONENT_CHIP_ZONES` が `['DECK','TABOO','PRIVATE','HAND']`、
  `OPPONENT_PILE_ZONES` が `['TRASH','LOST']` であること。
- `manual-battle.js` に `isZoneVisible` / `openZoneOrDeny` / `flashDenied` / `showToast`
  があり、`openZoneBand` を**直接**呼んでいる箇所が相手上段・右列パイルに残っていないこと。
- `manual-battle.js` に `registerZoneAnchor` / `anchorElement` / `applyDragCue` /
  `renderDragCues` / `sendDragCueStart` / `sendDragCueHover` / `sendDragCueEnd` があること。
  ★`onMessage` が `msg.type === 'CUE'` を**最初に**処理して return していること。
- `manual-battle.js` の `bottomSeatId` に `boardFlipped` の条件が入っており、
  **観戦者のときだけ**効くこと(`!view.viewerSeat &&` が付いている)。
  ★描画関数の中に `'A'` / `'B'` のリテラルが増えていないこと。
- `battle.css` の末尾に `.manual-opp-mana` / `.manual-opp-pile` / `.manual-opp-chip` /
  `.manual-denied` / `.manual-toast` / `.manual-cue-layer` があること。
  **共有クラス(`.leader-card` / `.minion-row` / `.mana-chip` / `.mana-row`)が無変更であること。**
- `ManualLogKind` に `FIRST_PLAYER(true)` があること。
- `ManualGameService` に `rollDie(int)`、`ManualOperationService` に `firstPlayer(actor)`、
  `ManualWsController` に `@MessageMapping("/manual/{roomId}/first-player")` があること。
  ★`firstPlayer` が `denyOperate` で判定し、**席を引数に取らない**こと。
- `ManualVersusTest` の `@Test` が **34件**あること。
- `verify/build_harness.py` の Bootstrap 代替に
  `*, *::before, *::after { box-sizing: border-box; }` があること。
  ★これが無いと相手上段の高さがハーネスでだけ 159px になる(2章の罠)。
- `verify/verify.js` が **137項目**であること。

---

## 1. 次の作業の候補(優先順位順)

### 手動モード フェーズ2 — **完了(21a〜21c)**

| バッチ | 種別 | 範囲 | 状態 |
|---|---|---|---|
| ~~21 設計~~ | 設計 | 対戦・観戦UIの全確認事項の確定 | 完了(`notes/batch21-versus-ui-design.md`) |
| ~~21a~~ | a系 | サーバ全部 | **完了** |
| ~~21b~~ | b系 | 入口の画面(ロビー・席選択・自席=下) | **完了** |
| ~~21c~~ | b系 | 盤面の仕上げ(相手上段・非公開・矢印・観戦トグル・先攻決め) | **完了** |

**残るのは `mvn test` の実行と、手元での実対戦による確認である。**

### 手動モード フェーズ1 — 完成済み(17a〜20c)

**★手動モードの盤面レイアウトは確定した(マスター言明)。** 21c の相手上段の再構成で
最後の変更を入れた。以後、盤面レイアウトには手を入れない。

### Ver.0.4 対応(手動モードとファイルが重ならない)

着手条件は満たされている。15c / 15d(Sonnet 5)、その後15e(基盤・Opus)、
全6文明168枚の整合チェック(Fable 5)の順。**次はこれになる。**

---

## 2. コンテキスト効率・発注者とのやりとり

grep 優先でファイルを渡り歩き、`view` による全体読み込みは避ける。
チャットの中断は再取得ぶんコンテキストを余計に消費するため避ける。

判断に迷う点・確認したい点は**まとめて質問する。**1つずつ聞かない。
呼び方は「クロエ」、発注者の呼び方は「マスター」。会話は日本語カジュアル体。
ドキュメント類は通常文体(である調)。

---

## 3. 既知の落とし穴

### サンドボックスの制約

- **★★このサンドボックスからは Maven Central へ到達できない(403)。`mvn compile` /
  `mvn test` が親POMを解決できず走らない(20b で判明・21a/21b/21c で再確認)。**
  **納品後、マスターの手元で `mvn test` を必ず走らせてもらうこと。**
  ★21a では代替として、lombok を機械展開し Spring/Jackson/JUnit/AssertJ を
  スタブに置き換えた javac 型チェックを自作して通した。
  ただし DI やアノテーションの意味論は検証していないため、`mvn test` の代わりにはならない。
- **★★`codeload.github.com` の zip URL は 403 で落ちることがある(20bで判明)。**
  `git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git` は通る。
- **★Playwright は `/opt/pw-browsers` のブラウザを使う。`npm i playwright` の版が
  ずれると「Executable doesn't exist」で落ちる(21c で遭遇)。**
  `/opt/pw-browsers` をホームへコピーし、要求された版番号へシンボリックリンクを張り、
  `PLAYWRIGHT_BROWSERS_PATH` で指すのが速い。`npx playwright install` は通らない。

### 手動モードの原則

- **★★手動モードで「判断」を実装しない — 例外はウェポンの付け替え1つだけ(20b)。**
  21a / 21b / 21c でも増やしていない。
  ★**視点フィルタと操作権限は「判断」ではない**(21a 設計解説 1-5)。
  ★**先攻選択権(21c)も「判断」ではない**。振っているのはダイスであって裁定ではなく、
  盤面にもターンにも触らない(21c 設計解説 1-5)。
  ★クライアント側のボタンの出し分け・観戦者のドラッグ抑止は
  **操作補助**であり、検証はサーバに残っている(設計判断27)。
- **★★共有ゾーン(PLAY / REVEAL)は席に属さない。** `ManualCardRef.seatId()` が
  null になりうる。`seatId` を無条件に参照するコードを書かないこと。
  ★21c のアンカーの鍵も「席 null = 共有 / ゾーン null = リーダー」で畳んである。
- **★★スナップショットに新しいフィールドを足したら `ManualGameState.copy()` /
  `ManualCardInstance.copy()` を必ず直す。** 複製から漏れたものは Undo で巻き戻らない。
- **★★共有ゾーンの所有(`placedBySeat`)は set と clear を必ず対で書く(21a)。**
- **★★公開範囲の判定を2箇所に書かない(21a)。** 宣言は `ManualZone.contentsPublic`、
  判定は `ManualViewpoint.canSeeZone` の1つだけ。
  ★クライアントは `zones` にキーがあるかを見るだけにする(21c の `isZoneVisible`)。
  「対戦部屋か」「相手席か」を組み立て直すと、定義がクライアントにも書かれてしまう。
- **★★ログの構造化は配信とダウンロードを同時に変える(21a)。**
- **★新しいログ種別を足すときは `plain` の分類を必ず決める(21a)。**
  分類を忘れて plain 側に置くと、非公開ゾーンのカード名がそのまま全員へ配られる。
  ★`ManualLogRenderer` の `switch` には `default` がある(定数追加でコンパイルは壊れない)。
- **★MPを直接増減する操作を作らない。** マナのアンタップ枚数からの派生値である。
- **★実装済み文明リストの唯一の正は `DeckValidator.IMPLEMENTED`。** 手動モードは使わない。
- **カード登録は card ID が `qte-cards.json` に実在するか必ず照合する。**
- **手動モードのカードIDを Java にリテラルで書かない。**

### UI の落とし穴

- **静的ファイルを変更したらキャッシュバスティングのバージョンをインクリメントする。**
  現在 `manual-battle.js?v=11` / `battle.css?v=19`(21c で 10→11 / 18→19)。
  ★`verify/build_harness.py` の置換文字列も同時に直すこと(版がずれるとハーネスが作れない)。
- **★★★描画の席と送信の席を混ぜない(21b で最重要だった罠)。**
  画面の上下は `bottomSeatId()` / `topSeatId()` が決め、
  `cardLocation` / `registerDropTarget` / 送信ペイロード / **矢印のホバー先**には
  **実席**(`seatView.id`)を渡す。
  ★21c の上下反転トグルも `bottomSeatId()` に条件を1つ足す形で入れてある。
  **描画関数の中に `'A'` / `'B'` をリテラルで書かない。**
- **★★対戦部屋では非公開ゾーンが `zones` にキーごと現れない(21a)。**
  枚数は `zoneCount(seatView, zoneName)` を使う(`counts` を優先する)。
  配列を数えると 0 になり、嘘の枚数を表示する。
- **★★非公開ゾーンのクリックで空の帯を開かない(21c 3-5)。**
  「空である」と「見えない」が同じ表示になる。`openZoneOrDeny` を通すこと。
- **★★カードの大きさの上限は「幅」ではなく「画面の高さ」から決める(20c)。**
  ミニオンは `max-width: min(180px, 16vh)`、手札は `window.innerHeight * 0.105`。
- **★相手上段は高さ148px以内(21 設計書4章)。現在140px。** 行の高さを決めているのは
  リーダータイル(120px)である。相手上段に要素を足すときは120pxを超えさせないこと。
- **★★flex アイテムの既定は `min-width: auto` であり、内容の最小幅より縮まない。**
  可変幅の列には必ず `min-width: 0` を書くこと。
- **★★flex の親に高さを固定すると、中身の height 指定は効かなくなる(20c)。**
- **★★検証ハーネスの Bootstrap 代替に漏れがあると「ハーネスでだけ壊れる」(20c・21c)。**
  テンプレートで使ったユーティリティクラスは `verify/build_harness.py` の代替にも足すこと。
  ★21c で `box-sizing: border-box` の欠落が判明した(`min-height` に padding と border が
  加算され、相手上段がハーネスでだけ 159px になっていた)。
  ★**厳しい寸法の検証項目を書いて初めて「どこが似ていないか」が分かる。**
- **★★検証ハーネスが本物とズレていると「壊れていても PASS する」(21b)。**
  スクリプト直下の `let` は `window` のプロパティにならない。
  `verify/verify.js` の `render()` は `window.latestView` と内部の `latestView` の
  **両方**へ代入している。片方だけに戻さないこと。
- **★★`page.evaluate` に Promise を返す関数を渡さない(21b)。**
  `{ f(x); }` と包んで戻り値を捨てる。
- **★★ドラッグの「起点から除外する」を `draggable=false` の子要素明示で実現しては
  ならない(20a 3-1)。** `dragstart` 内で `document.elementFromPoint()` を見て
  `preventDefault()` する(`e.target` ではダメ)。
- **★★`registerDropTarget` で登録した要素を入れ子にすると `drop` が二重発火する(20a 3-2)。**
  現在は `drop` ハンドラ先頭で `e.stopPropagation()` している。
- **★★ドラッグ&ドロップの検証に合成 `DragEvent` を使ってはならない。**
  必ず `page.mouse.down/move/up` による実操作で検証する。補間は30ステップ程度に細かくし、
  最終位置到達後に短い待機を挟んでから `mouse.up()` する(20a)。
- **★★送信メッセージを件数で確かめる検証は、揮発メッセージ(`dragcue`)を除いて数える(21c)。**
  `verify/verify.js` の `boardMessages()` を通すこと。除いた側も別に検証すること。
- **★矢印(CUE)はログ・履歴・Undo に触れない(21c 7-2)。**
  `onMessage` は `type === 'CUE'` を最初に処理して抜け、`latestView` を書き換えない。
  オーバーレイには `pointer-events: none` が必須(無いとドロップできなくなる)。
- **★`battle.css` は通常モードと手動モードの共有ファイルである。**
  `.leader-card` / `.minion-row` / `.mana-chip` / `.mana-row` は通常モードも使っている。
  手動モード側の変更は必ず手動モード専用クラス(`.manual-` 接頭辞)への追加で行い、
  共有クラスの定義自体は変えないこと。
- **★HTMLテンプレートを書き換えるときは、対応するJS描画関数が「入れ物への追記」を
  前提にしているか「入れ物ごと使う」ことを前提にしているかを、関数の中身を読んで
  確認してから書く。**
- **★観測結果を環境の制約だと決めつけないこと。** 実マウス検証の結果が優先する。
- **★検証ハーネスは `verify/` にリポジトリ管理してある(20bで新設)。**
  `python3 verify/build_harness.py && node verify/verify.js` で **137項目**が走る。
  **新しいUIを足したらここに検証項目を追加すること。**
  ★`build_harness.py` は盤面(`harness.html`)とロビー(`harness-lobby.html`)の2つを作る。

### その他

- **★occupantId はサーバがページ生成時に発行しない(19a以降)。** クライアントの
  localStorage(キー `qte-manual-occupant-{roomId}`)が保持する。
- **★切断復帰の猶予は5分固定**(`ManualCleanupScheduler.GRACE_PERIOD`)。
  ★21a で `ManualViewBuilder` にも表示用の同じ値がある。片方だけ変えないこと。
- **★手動モードの無人部屋は5分で自動削除される(21a)。**
- **★ゲームの正式名称は「クイン・タブーエレメント」である。**
- **★テストコードは機械チェックの網の外にある。** `tools/check_structure.py` は
  `*Test.java` のJUnitメソッド名(日本語)を誤検出する既知のノイズがある。
  `check_structure.py` は `src/main/java` を指定して走らせる。
- **`tools/check_records.py` の不一致3件は既存の既知の誤検出である**
  (`GameActions.java:775` / `GameService.java:1144` / `CardEffectRegistry.java:748`)。
  manual パッケージは0件。

---

## 4. デリバリー形式

zip(変更・新規ファイルのみ。削除したファイルはzipに含めず、パスを明記して手元での削除を
依頼する)+ `batchNN-design-notes.md`(チートシート/設計根拠★/検証手順/理解確認Q&A/
次バッチ予告)+ ハンドオフ更新。
**★ドキュメント類はチャットにファイルとして添付する**(プロジェクトナレッジへの
書き込みだけだと開けないことがある)。

マシンチェックスクリプト群をパッケージング前に必ず実行する。

```bash
python3 tools/check_structure.py src/main/java                       # ★最優先
python3 tools/check_all.py .                                         # 項目 1・3・5・6
python3 tools/check_records.py src/main/java                         # 項目 4
python3 tools/check_undeclared.py src/main/resources/static/js/*.js   # 項目 8
node --check src/main/resources/static/js/manual-battle.js           # 項目 7
python3 verify/build_harness.py && node verify/verify.js             # DOM・実マウス検証
```

**DOM構造・ドラッグ&ドロップが絡む変更は `verify/` の実マウス検証を通すこと
(合成DragEventは使わない)。**

---

## 5. チャット開始テンプレート

### A. Ver.0.4 対応(次の本命)

```
QTE Battle の開発を継続する。Batch 15c(Ver.0.4 対応)を行う。
※b系バッチである(Sonnet 5・拡張思考は不要)。

1. プロジェクトナレッジ内の `qte-project-reference.md` を読む
   (ゲームルール・設計判断の唯一の正)。
2. プロジェクトナレッジ内の `qte-handoff-v25.md` を読む
   (直近の状態・既知の落とし穴)。3章「既知の落とし穴」は必読。
3. プロジェクトナレッジ内の `notes/ver0.4-transcription-notes.md` と
   `notes/batch15a-design-notes.md` / `notes/batch15b-design-notes.md` を読む。
   ★手動モード(17a〜21c)とはファイルが重ならない。混ぜないこと。
4. ソースコードを取得する(zipのアップロードは不要)。
   ★codeload の zip URL は 403 で落ちることがある。その場合は git clone を使う。

git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git

5. ★取得したコードに Batch 21c が反映されているかを確認し、結果を報告すること。
   「反映済み」という記述を信じないこと。ハンドオフ0章の「21cの確認項目」を
   実際に読んで照合する。反映されていない場合はそこで止めて報告すること。

制約:
- このサンドボックスから Maven Central へは到達できない。`mvn test` は納品後に
  こちらの手元で走らせるので、必ず依頼すること。
  機械チェック(tools/ と verify/)は実行すること。
- 納品形式はハンドオフ4章のとおり。
  ★ドキュメント類はチャットにファイルとして添付すること。
- 判断に迷う点や確認したいことはまとめて質問すること。1つずつ聞かない。
- 1バッチ1チャットの原則を守り、中断しないこと。

呼び方は「クロエ」、こちらの呼び方は「マスター」で。会話は日本語カジュアル体でお願い。
ドキュメント類は通常文体(である調)で書くこと。
```

### B. 手動モードの手直しが必要になったとき

上記の 3 を
`notes/batch21-versus-ui-design.md`(フェーズ2の唯一の正)+
`notes/batch21c-design-notes.md`(直近の実装解説)+
`batch16-manual-mode-design-v2_4.md` に差し替える。

---

## 6. 21c 完了時点の積み残し

- **`mvn test` が未実行である**(サンドボックスから Maven Central へ到達できないため)。
  21c の Java 変更は4ファイル(`ManualLogKind` / `ManualGameService` /
  `ManualOperationService` / `ManualWsController`)+ テスト1ファイルであり、
  **コンパイル確認が取れていない**。手元で必ず走らせること。
  期待は `ManualVersusTest` が34件通ること。
- **★入室前の席選択で「切断中」を表示できない**(21b から継続)。
  `RoomSummary` が在席者名しか持たず、接続状態を持たないため。
  入室後のゲート(昇格)ではビューから出せている。
  座れないこと自体は正しく無効化されるので実害は小さい。
  直すなら `RoomSummary` に真偽値を2つ足す(一覧の表示にも使える)。
- **拡大画像パネルが 204px 幅のまま**(右列は 400px)。21c では触らない判断
  (マスター確認済み)。広げるならログを拡大画像の下へ移すことになり、
  20c で決めた右列の配置が変わる。
- **ミニオン行の頭打ち**(20c から継続)。タイル上限が `min(180px, 16vh)` のため、
  1920幅では左右に余白が残り中央寄せになる。
  ★これは意図した挙動でもある(上限が無いとワイドモニタでタイルが縦にも巨大化し、
  盤面が画面高さに収まらなくなる)。変えるなら「行の左右にどれだけ余白を許すか」を
  先に決める必要がある。
- `qte-project-reference.md` の1章(実装状況)が2026-07-21時点のまま古い。

### 解消済み(21c で片付いたもの)

- ~~相手上段は 20c のまま(チップ5つ+リーダー)~~ → 4章のとおり再構成した
- ~~相手の非公開チップをクリックすると空の帯が開く~~ → 3-5 のフィードバックを実装した
- ~~先攻選択権ボタン(6-3・E4)~~ → 専用イベント種別で実装した(マスター判断)
- ~~超ワイド画面(1920以上)での相手上段の左右の離れ~~ → マナ簡略表示が余った幅を吸う
