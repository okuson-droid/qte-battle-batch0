# QTE 対戦アプリ — 引き継ぎ書

最終更新: 2026-08-06(Batch 23 完了。**ゲーム開始シーケンス**)
**★23 は Java の変更が最も重いバッチである**(新規4ファイル・変更11ファイル・JUnit 26件追加)。
**★★`mvn test` が未実行である(サンドボックスから Maven Central へ到達できない)。
21c の Java 変更(4ファイル)も未検証のまま残っている。
納品後、必ずマスターの手元で走らせること
(期待: `ManualStartSequenceTest` 26件 / `ManualVersusTest` 34件)。**

**★新しいチャットを始めるときは 5章「チャット開始テンプレート」を使うこと。**

このファイルは、新しいチャットで作業を再開するための最小限の情報をまとめたものである。
ゲームルール・設計判断の詳細は `qte-project-reference.md`、カードの詳細は `qte-cards.json`。

系統が2つ並行している。**混ぜないこと。**

| 系統 | 前提ドキュメント |
|---|---|
| **手動モード 追加分**(22・23・**完了**) | `notes/batch22-ui-refinement-design.md` + `notes/batch22-design-notes.md` / `notes/batch23-game-start-design.md` + `notes/batch23-design-notes.md` |
| **手動モード フェーズ2**(21a〜21c・完了) | `notes/batch21-versus-ui-design.md`(**唯一の正**)+ `batch16-manual-mode-design-v2_4.md` + `notes/batch21a〜21c-design-notes.md` |
| **手動モード フェーズ1**(17a〜20c・完成) | `batch16-manual-mode-design-v2_4.md` + `notes/batch20b-ui-design.md` + `notes/batch20b/20c-design-notes.md` |
| **Ver.0.4 対応**(15c/15d/15e) | `notes/ver0.4-transcription-notes.md` + `notes/batch15a-design-notes.md` + `notes/batch15b-design-notes.md` |

---

## 0. 最初にやること

1. `qte-project-reference.md` を読む(ゲームルール・設計判断の唯一の正)。
   **★このファイルはプロジェクトナレッジ内で2026-07-21時点の記述のまま止まっている
   (3文明実装時点)。実際は6文明完成・Ver.0.4対応中であり、1章の状態記述が古い。
   ★加えて 2-5「ゲーム開始前の処理」は Batch 23 で自動化された(手動モードの原則の
   例外として境界を引き直してある)。ゲームルール自体(2章以降)は有効。**
2. 作業に応じて上表の前提ドキュメントを読む。
3. ソースコードを取得する。**zipのアップロードは不要**。

   **★★`codeload.github.com` の zip URL は環境によっては 403 で落ちる。**
   その場合は git clone を使うこと。こちらは通る。

```bash
git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git
```

4. **★「反映済み」という記述を信じず、直近バッチで変更したはずの箇所を実際に読んで照合する。**
   `git log` / `git diff HEAD~1` も併用すると速い。

## 23 の確認項目(次チャットで一度だけ照合すること)

- `manual-battle.html` の `<link>` が `battle.css(v=21)`、`<script>` が
  `manual-battle.js(v=13)` になっていること。
  ★`verify/build_harness.py` の置換文字列も同じ版であること。
- **★新規の Java 4ファイルが実在すること。**
  `ManualStartPhase.java` / `ManualStartMethod.java` / `ManualStartService.java` /
  `view/ManualStartView.java`
- **★★21c の先攻決めが「跡形もなく」消えていること**(23 設計書 3-4・P14)。
  次の grep がすべて0件であること(コメント中の言及は除く)。
  `firstPlayer(` / `FIRST_PLAYER` (※`FIRST_PLAYER_HAND_SIZE` は別物) /
  `first-player` / `btn-first-player` / `DICE_SIDES = 6`
- **★`ManualGameService.rollDie(int sides)` は残っていること。**
  呼び出すのは `ManualStartService`(`DICE_SIDES = 20` を渡す)だけである。
- **★★開始中の棄却が `ManualOperationService.apply` の先頭1行にあること。**
  `ManualPermissions.require(ManualPermissions.denyDuringStart(room));`
  ★`undo` / `redo` にも同じ行があること(あちらは `apply` を通らない)。
  ★**操作ごとに書き足されていない**こと(書き足されていたら 1-1 の構造が壊れている)。
- **★★開始フェーズが `ManualRoom` にあり `ManualGameState` に無いこと**(2-6)。
  `ManualGameState.copy()` / `ManualCardInstance.copy()` が**無変更**であること。
- **★`ManualGameService.resetRoom` と `loadDeck` の両方が `room.resetStart()` を呼ぶこと。**
  リセットでフェーズが `IDLE` へ戻らないのが最悪の状態である(11章)。
- **★`ManualRoom.creatorSeat` が席で持たれていること**(occupantId ではない。2-4)。
  `ManualLobbyController.createRoom` が `room.setCreatorSeat(seat)` を呼ぶこと。
- **★`application.properties` に `qte.manual.pure-element-id` があること。**
  Java にカードIDのリテラルが無いこと(`ManualStartService` は `@Value` で読む)。
- `ManualLogKind` に `START(true)` があること。`FIRST_PLAYER` が無いこと。
- `manual-battle.js` に `renderStartUi` / `isStartLocked` / `syncMulliganOverlay` /
  `renderMulliganOverlay` / `createMulliganCard` があること。
  ★`onDragStart` に `isStartLocked()` の枝があること。
- `battle.css` の末尾に `.manual-start-banner` / `.manual-mulligan-*` があること。
  **共有クラス(`.leader-card` / `.minion-row` / `.mana-chip` / `.mana-row`)が無変更であること。**
- `verify/verify.js` が **216項目**であること。
- **★`mvn test` が通ること**(サンドボックスでは走っていない。6章)。

---

## 1. 次の作業の候補(優先順位順)

### ★Ver.0.4 対応(次はこれ)

15c / 15d(Sonnet 5)→ 15e(基盤・Opus)→ 全6文明168枚の整合チェック(Fable 5)の順。
**手動モード(17a〜23)とはファイルが重ならない。混ぜないこと。**

### 手動モード — **完了**

| バッチ | 種別 | 範囲 | 状態 |
|---|---|---|---|
| ~~17a〜20c~~ | — | フェーズ1(盤面・操作13項目・帯・レイアウト) | **完了** |
| ~~21a〜21c~~ | — | フェーズ2(対戦部屋・視点フィルタ・権限・矢印) | **完了** |
| ~~22~~ | b系 | UI8点(クリック規約・マナ表示・ウェポン枠・数値ボタン・操作説明) | **完了** |
| ~~23~~ | **a系** | ゲーム開始シーケンス(先攻後攻・初期ドロー・マリガン・ピュア配布・操作ロック) | **完了** |

**残るのは `mvn test` の実行と、手元での実対戦による確認である。**

★**盤面レイアウトは確定している。** 23 は盤面の縦を1pxも増やしていない
(開始まわりの画面はすべて `position: fixed` のオーバーレイである)。

### ★23 でマスターの判断待ちが1件ある

**[ゲームを始める] を対戦部屋にも出した。** 設計書 6-1 は「全公開部屋のみ表示」と
していたが、自動でモーダルを出すにはクライアントの自動送信かサーバの暗黙遷移が必要で、
どちらも副作用が大きいと判断した(23 設計解説 1-8・Q6)。
**このままでよいかの確認が要る。**

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
  `mvn test` が親POM(`spring-boot-starter-parent:4.0.1`)を解決できず走らない
  (20b で判明・21a/21b/21c/22/23 で再確認)。**
  **納品後、マスターの手元で `mvn test` を必ず走らせてもらうこと。**
  ★`javac -proc:none` による**構文走査**まではできる(型解決は不可)。
  23 ではこれで構文エラー0を確認している。
- **★★`codeload.github.com` の zip URL は 403 で落ちることがある(20bで判明)。**
  `git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git` は通る。
- **★Playwright は `PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers` を環境変数で渡せば通る**
  (22・23 で確認)。`npx playwright install` は通らない。

### 手動モードの原則

- **★★手動モードで「判断」を実装しない — 例外は2つだけである。**
  1. ウェポンの付け替え(20b)
  2. **★ゲーム開始前の処理(23。総合ルール 2-5 だけ)**
  ★**2-6(ターン進行)には踏み込んでいない。** ターンもフェイズも人間が進める。
  勝敗・コスト・デッキ切れの判断は1つも増えていない。
  **この境界を越える要望が来たら、そのときに改めて設計すること。**
  ★視点フィルタと操作権限は「判断」ではない(21a 1-5)。
  ★先攻後攻の決定も「判断」ではない。振っているのはダイスであって裁定ではない。
- **★★共有ゾーン(PLAY / REVEAL)は席に属さない。** `ManualCardRef.seatId()` が
  null になりうる。`seatId` を無条件に参照するコードを書かないこと。
- **★★スナップショットに新しいフィールドを足したら `ManualGameState.copy()` /
  `ManualCardInstance.copy()` を必ず直す。**
  ★**逆に「巻き戻ってはならないもの」は `ManualRoom` に置く**(23 2-6)。
  現在の住人はログ・履歴・**開始フェーズ / 先攻席 / 作成者席 / マリガンの確定状況**である。
- **★★共有ゾーンの所有(`placedBySeat`)は set と clear を必ず対で書く(21a)。**
- **★★公開範囲の判定を2箇所に書かない(21a)。** 宣言は `ManualZone.contentsPublic`、
  判定は `ManualViewpoint.canSeeZone` の1つだけ。
  ★**同じ形で「押せるか」も2箇所に書かない**(23 9章)。判定は `ManualPermissions` が持ち、
  ビュー(`ManualStartView`)は**結果**だけを運ぶ。クライアントは真偽値を見るだけにする。
- **★★`ManualSeat.availableMp()` は「マナゾーン全体のアンタップ枚数」であり、
  裏向きも数に入れている。この意味を変えてはならない**(22 の `faceDownManaSplit` が依存)。
- **★★1回の操作の型は `ManualOperationService.apply` が握る(17b)。**
  ★23 の「開始中の棄却」もここ1行で済んでいる(23 1-1)。
  **止める/止めないの線は、判定ではなく呼び出し経路に対応している。**
  - `apply` — 盤面を変える。履歴に積む。**開始中は棄却される**
  - `applyDirect` — メモ・宣言・Undo/Redo。積まない。**開始中も通る**
    (★Undo/Redo だけは個別に `denyDuringStart` を呼んでいる)
  - `execute` — 在室・席・リセット。**開始中も通る**(★リセットは逃げ道である)
- **★★リセットは絶対に止めない(23 7-2)。** 止まったまま抜けられない画面を作らない。
  開始まわりの3画面すべてに [リセットして最初から] を置いてある。
- **★★ログの構造化は配信とダウンロードを同時に変える(21a)。**
- **★新しいログ種別を足すときは `plain` の分類を必ず決める(21a)。**
  ★`START`(23)は `plain = true` だが、**マリガンで戻したカード名は本文に入れない**。
  種別の分類では守れないマスクであり、本文を組み立てる側の責務である。
- **★MPを直接増減する操作を作らない。**
- **★実装済み文明リストの唯一の正は `DeckValidator.IMPLEMENTED`。**
- **カード登録は card ID が `qte-cards.json` に実在するか必ず照合する。**
- **★★総合ルールの「物理的な所作」をそのまま写さない(23 で確定した読み方)。**
  総合ルール 2-5 の4は【ピュア・エレメント】を「**裏向きで**渡す」と書いているが、
  **表向きで渡す**のが正しい(マスター裁定 2026-08-06)。
  実物で裏向きにするのは<b>相手に見せないため</b>であって、受け取った本人が
  中身を見られない状態にするためではない。手札は持ち主しか見ないゾーンであり
  (対戦部屋では相手にカードオブジェクトがそもそも届かない。21 設計書 3-3)、
  **裏向きにしても情報が1ビットも変わらない**。裏向きにすると手札行が
  「灰色の箱に(裏向き)」になり、持ち主にも何のカードか分からない実害だけが残る。
  ★**所作が何を保証するために存在するのかを先に取り出すこと。**
  保証したい性質が別の仕組み(ゾーンの公開範囲)で既に成立しているなら、所作は写さない。
  ★これは 21 設計書 3-4「手札の `faceDown` に『相手へ公開』の意味は無い」の適用例である。
- **★★手動モードのカードIDを Java にリテラルで書かない。**
  ★23 のピュア・エレメントは `application.properties` の
  `qte.manual.pure-element-id` から読む。**名前での台帳検索も同じ違反である**
  (名前をリテラルで書くのはIDを書くのと本質的に同じ)。
  ★**設定が無くても起動を失敗させない。**配布だけをスキップし、起動ログに警告を出す。

### UI の落とし穴

- **静的ファイルを変更したらキャッシュバスティングのバージョンをインクリメントする。**
  現在 `manual-battle.js?v=13` / `battle.css?v=21`(23 で 12→13 / 20→21)。
  ★`verify/build_harness.py` の置換文字列も同時に直すこと。
- **★★★描画の席と送信の席を混ぜない(21b で最重要だった罠)。**
  画面の上下は `bottomSeatId()` / `topSeatId()` が決め、
  `cardLocation` / `registerDropTarget` / 送信ペイロード / 矢印のホバー先には**実席**を渡す。
  **描画関数の中に `'A'` / `'B'` をリテラルで書かない。席で描き分けたくなったら引数で渡す。**
- **★★クリック規約(22 1章)。左=見る / 右=動かす。**
  ```
  左クリック = 拡大表示     右クリック = タップ / 一覧を開く
  Shift + 左 = 表裏         Ctrl(⌘)+ 左 = 複数選択
  ```
  **例外は山札だけ**(左=1枚ドロー / 右=全面表示)。
  ★**マリガンオーバーレイの中だけは 左=選択 / 右=拡大** である(23 4-3)。
  盤面の規約と衝突するため**専用オーバーレイに閉じ込めてある**。
  盤面の手札行で左を「選択」に流用してはならない。
  ★**新しく `contextmenu` を張る箇所では `e.preventDefault()` を必ず呼ぶ。**
  ★**タップできないゾーンでも右クリックは拡大を返す。**無反応にしない。
- **★★オーバーレイが右列を覆うと、右列に出す拡大は見えない(23 で踏んだ)。**
  マリガンオーバーレイのバックドロップは z-index 1950 であり、
  `#zoom-panel`(右列)を覆う。オーバーレイの中にも拡大枠を置くこと。
- **★★カードの上に載っている専用ボタンは規約の外にある(22 1-6)。**
  ★**検証で `.manual-tile` の中央をクリックすると数値チップに当たる**。
  カード本体を押したいときは `.manual-tile-name` を狙うこと。
- **★★対戦部屋では非公開ゾーンが `zones` にキーごと現れない(21a)。**
  枚数は `zoneCount(seatView, zoneName)` を使う。
- **★★非公開ゾーンのクリックで空の帯を開かない / 空の拡大を出さない。**
  `openZoneOrDeny`(右)と `zoomTopOrDeny`(左)を通すこと。
- **★★カードの大きさの上限は「幅」ではなく「画面の高さ」から決める(20c)。**
  ★23 のマリガンのカードも `min(160px, 17vh)` である。
- **★相手上段は高さ148px以内(21 設計書4章)。現在140px。**
- **★盤面全体の高さは1280×950で現在 821px(上限900px級)。**
  ★23 は1pxも増やしていない(開始まわりは全部 `position: fixed`)。
- **★固定表示をヘッダに重ねない(23 で踏んだ)。** 待機バナーを `top: 8px` に置いたら
  [リセット] と在室者チップを覆った。逃げ道そのものが押せなくなる。現在 `top: 46px`。
- **★★flex アイテムの既定は `min-width: auto`。可変幅の列には `min-width: 0` を書く。**
- **★★flex の親に高さを固定すると、中身の height 指定は効かなくなる(20c)。**
- **★★検証ハーネスの Bootstrap 代替に漏れがあると「ハーネスでだけ壊れる」(20c・21c・22・23)。**
  テンプレートで使ったユーティリティは `verify/build_harness.py` の代替にも足すこと。
- **★★検証ハーネスが本物とズレていると「壊れていても PASS する」(21b)。**
  `render()` は `window.latestView` と内部の `latestView` の**両方**へ代入すること。
  ★**`verify/shot.js` も同じである**(23 で判明。片方だけだとオーバーレイが写らない)。
- **★★★「起きてほしくないこと」だけを検証すると、何も保証されない(22 2章・23 2章)。**
  ★23 で再発した。**開始中にドラッグしても送られない**という検証が、
  実はドロップ先を外していて「開始後も送られない」状態で PASS していた。
  → **ロックの検証は必ず「解除後に同じ操作が通ること」と対にする。**
- **★★`page.evaluate` に Promise を返す関数を渡さない(21b)。**
- **★★ドラッグの「起点から除外する」は `dragstart` 内で `document.elementFromPoint()` を
  見て `preventDefault()` する(`e.target` ではダメ。20a 3-1)。**
- **★★`registerDropTarget` で登録した要素を入れ子にすると `drop` が二重発火する(20a 3-2)。**
- **★★ドラッグ&ドロップの検証に合成 `DragEvent` を使ってはならない。**
  必ず `page.mouse.down/move/up` による実操作で検証する。
- **★★送信メッセージを件数で確かめる検証は `boardMessages()` を通す(21c)。**
- **★矢印(CUE)はログ・履歴・Undo に触れない(21c 7-2)。**
  ★**ゾーンの置き場所を変えたら `registerZoneAnchor` の登録先も移す**(22)。
- **★`battle.css` は通常モードと手動モードの共有ファイルである。**
  `.leader-card` / `.minion-row` / `.mana-chip` / `.mana-row` は通常モードも使っている。
  ★`.mana-tile` / `.mana-strip` 系は手動モード専用なので変更してよい(取り違えないこと)。
- **★オーバーレイの id は `manual-overlay-root`、トーストは `#manual-toast`。**
  トーストは**出しっぱなしで `d-none` を付け外しする**実装なので、
  「今出ている」を数えるには `:not(.d-none)` が要る(22 2章)。
- **★観測結果を環境の制約だと決めつけないこと。** 実マウス検証の結果が優先する。
- **★検証ハーネスは `verify/` にリポジトリ管理してある(20bで新設)。**
  `python3 verify/build_harness.py && node verify/verify.js` で **216項目**が走る。
  **新しいUIを足したらここに検証項目を追加すること。**
  ★`verify/shot.js` は目視用のスクリーンショットを撮る
  (`W`/`H`/`OUT` に加え、23 から `START=method|order|mulligan|banner|begin` で
  開始シーケンスの各画面を撮れる)。

### その他

- **★occupantId はサーバがページ生成時に発行しない(19a以降)。**
  クライアントの localStorage(キー `qte-manual-occupant-{roomId}`)が保持する。
- **★切断復帰の猶予は5分固定**(`ManualCleanupScheduler.GRACE_PERIOD`)。
  ★21a で `ManualViewBuilder` にも表示用の同じ値がある。片方だけ変えないこと。
- **★手動モードの無人部屋は5分で自動削除される(21a)。**
- **★ゲームの正式名称は「クイン・タブーエレメント」である。**
- **★テストコードは機械チェックの網の外にある。** `tools/check_structure.py` は
  `src/main/java` を指定して走らせる。
- **★`tools/check_structure.py` は `List.<String>of()` を誤検出する**(23 で判明)。
  明示的な型ウィットネスの直前が `>` であり、bare call に見えるためである。
  一時変数に受ける形へ書き直すこと(チェックを緩めない)。
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
PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers \
  python3 verify/build_harness.py && node verify/verify.js           # DOM・実マウス検証
# ★型解決はできないが、構文だけは走査できる(23 から)
javac -proc:none -d /tmp/jc $(find src/main/java src/test/java -name '*.java') 2>&1 \
  | grep error: | grep -viE "cannot find symbol|package .* does not exist"
```

**DOM構造・ドラッグ&ドロップが絡む変更は `verify/` の実マウス検証を通すこと
(合成DragEventは使わない)。**

---

## 5. チャット開始テンプレート

### ★A. Ver.0.4 対応(次はこれ)

```
QTE Battle の開発を継続する。Batch 15c(Ver.0.4 対応)を行う。
※b系バッチである(Sonnet 5・拡張思考は不要)。

1. プロジェクトナレッジ内の `qte-project-reference.md` を読む
   (ゲームルール・設計判断の唯一の正)。
2. プロジェクトナレッジ内の `claude/qte-handoff-v27.md` を読む
   (直近の状態・既知の落とし穴)。3章「既知の落とし穴」は必読。
3. プロジェクトナレッジ内の `notes/ver0.4-transcription-notes.md` と
   `notes/batch15a-design-notes.md` / `notes/batch15b-design-notes.md` を読む。
   ★手動モード(17a〜23)とはファイルが重ならない。混ぜないこと。
4. ソースコードを取得する(zipのアップロードは不要)。
   ★codeload の zip URL は 403 で落ちることがある。その場合は git clone を使う。

git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git

5. ★取得したコードに直近バッチが反映されているかを確認し、結果を報告すること。
   「反映済み」という記述を信じないこと。ハンドオフ0章の確認項目を
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

上記 A の 3 を
`notes/batch21-versus-ui-design.md`(フェーズ2の唯一の正)+
`notes/batch22-ui-refinement-design.md`(UI規約の唯一の正)+
`notes/batch23-game-start-design.md`(開始シーケンスの唯一の正)+
`notes/batch23-design-notes.md`(直近の実装解説)+
`batch16-manual-mode-design-v2_4.md` に差し替える。

---

## 6. 23 完了時点の積み残し

- **★★`mvn test` が未実行である(最重要)。**
  23 は Java の変更が最も重いバッチであり(新規4ファイル・変更11ファイル)、
  **21c の Java 変更も未検証のまま残っている**。手元で必ず走らせること。
  期待は `ManualStartSequenceTest` **26件** + `ManualVersusTest` **34件**。
- **★[ゲームを始める] を対戦部屋にも出したこと(23 設計解説 1-8・Q6)。**
  設計書 6-1 は「全公開部屋のみ表示」としていた。**マスターの判断待ち。**
- **★入室前の席選択で「切断中」を表示できない**(21b から継続)。
  `RoomSummary` が接続状態を持たない。座れないこと自体は正しく無効化される。
- **拡大画像パネルが 204px 幅のまま**(右列は 400px)。21c・22・23 とも触らない判断。
- **ミニオン行の頭打ち**(20c から継続)。★意図した挙動でもある。
- **★相手上段のマナ名が 8px である**(22 から継続)。
- **`qte-project-reference.md` の1章(実装状況)が 2026-07-21 時点のまま古い。**
  ★加えて **2-5 が Batch 23 で自動化された**ことを記録しておくとよい。
  現状のリファレンスからは「手動モードは開始処理も人間がやる」としか読めない。

### 解消済み(23 で片付いたもの)

- ~~先攻後攻がログに残るだけで盤面に反映されない(21c)~~ → 実際に先攻席を決め、
  初期ドローの枚数まで反映するようにした(3章・4章)
- ~~先攻を決める経路が2つある~~ → 21c のボタンとエンドポイントを廃止し1本にした(3-4)
- ~~マリガンが手作業(手札を山札へドラッグして引き直す)~~ → 専用オーバーレイで選び、
  サーバが「戻す→シャッフル→同数ドロー」を1操作で行う(4章)
- ~~ピュア・エレメントを人間が探して配っていた~~ → 後攻へ自動配布(5章)。
  ★**表向きで渡す**(マスター裁定 2026-08-06。下の「ルールの読み替え」を参照)
- ~~初期ドローが両席とも4枚だった~~ → 先攻4 / 後攻5(総合ルール 2-5)
- ~~開始準備中に相手が盤面を触れてしまう~~ → サーバで棄却。リセットだけ通す(7章)
