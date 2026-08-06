# QTE 対戦アプリ — 引き継ぎ書

最終更新: 2026-08-05(Batch 22 完了。**手動モードUIの詰め**)
**★22 は Java を1行も触っていない。** 変更は
`manual-battle.js` / `battle.css` / `manual-battle.html` / `verify/` の6ファイルのみ。
**★`mvn test` が未実行である(サンドボックスから Maven Central へ到達できない)。
22 に Java 変更は無いが、21c の Java 変更(4ファイル・JUnit 4件)が未検証のまま残っている。
納品後、必ずマスターの手元で走らせること(期待: `ManualVersusTest` が34件)。**

**★新しいチャットを始めるときは 5章「チャット開始テンプレート」を使うこと。**

このファイルは、新しいチャットで作業を再開するための最小限の情報をまとめたものである。
ゲームルール・設計判断の詳細は `qte-project-reference.md`、カードの詳細は `qte-cards.json`。

系統が2つ並行している。**混ぜないこと。**

| 系統 | 前提ドキュメント |
|---|---|
| **手動モード フェーズ2**(21a〜21c・**完了**) | `notes/batch21-versus-ui-design.md`(**唯一の正**)+ `batch16-manual-mode-design-v2_4.md` + `notes/batch21a-design-notes.md` + `notes/batch21b-design-notes.md` + `notes/batch21c-design-notes.md` |
| **手動モード 追加分**(22・**完了** / 23・未着手) | `notes/batch22-ui-refinement-design.md` + `notes/batch22-design-notes.md` / `notes/batch23-game-start-design.md` |
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

## 22 の確認項目(次チャットで一度だけ照合すること)

- `manual-battle.html` の `<link>` が `battle.css(v=20)`、`<script>` が
  `manual-battle.js(v=12)` になっていること。
  ★`verify/build_harness.py` の置換文字列も同じ版であること。
- `manual-battle.js` に `onCardContextMenu` / `zoomTopOrDeny` / `statButton` /
  `createWeaponSlot` / `faceDownManaSplit` の5つがあること。
- `onCardClick` が **3行(Shift / Ctrl / setZoom)**になっており、
  `zone === 'HAND'` の分岐が**残っていない**こと。
  ★ゾーンを見るのは `onCardContextMenu` 側である。
- `createLeaderTile(seat, options)` が **`options.withWeapon` を受け取る**こと。
  ★`renderOpponentTop` は `{ withWeapon: true }`、`renderPiles` は `{ withWeapon: false }` を渡す。
  **関数の中に「自席かどうか」の判定が無い**こと。
- `renderPiles` が `createWeaponSlot(seat)` を呼び、その中で
  `registerZoneAnchor(box, seat.id, 'WEAPON')` していること。
  ★`createLeaderTile` 側の WEAPON 登録は `withWeapon` の中にだけあること。
- `createManaTile(card, seatId, backImageId)` が3引数で、`faceDown` のとき
  `.mana-tile-back` + `<img>` を出すこと。
- `renderManaRow` が `#mana-row-head` を出し、
  文言が `マナ N枚(表 n / 裏 m) MP k` であること。合計は `zoneCount` から取ること。
- `createOpponentManaCard` が**画像ではなく文明色タイル+カード名**(`.manual-opp-mana-name`)
  を出すこと。`createOpponentManaBack(count, backImageId, tapped)` が3引数であること。
- `battle.css` の末尾に `.manual-stat-button` / `.manual-stat-pen` / `.manual-mana-head` /
  `.manual-weapon-slot` / `.manual-opp-mana-face` / `.manual-opp-mana-name` があること。
  **共有クラス(`.leader-card` / `.minion-row` / `.mana-chip` / `.mana-row`)が無変更であること。**
  ★`.mana-tile.mana-tile-back` は追加してよい(手動モード専用)。
- `manual-battle.html` の操作説明が「基本の操作」5行 + 「場所 × 左/右/ドラッグ」の表に
  なっていること(場所ごとの箇条書きに戻っていないこと)。
- **Java が1つも変更されていないこと**(`git diff --name-only` に `.java` が出ない)。
- `verify/verify.js` が **187項目**であること。

---

## 1. 次の作業の候補(優先順位順)

### ★Batch 23 — ゲーム開始シーケンス(次はこれ)

| バッチ | 種別 | 範囲 | 設計書 |
|---|---|---|---|
| **23** | **a系(Opus + 拡張思考)** | 先攻後攻の決定(20面ダイス/先攻/後攻)・初期ドロー(先攻4/後攻5)・マリガン・ピュア・エレメントの配布・ソロの「ゲームを始める」・開始中の操作ロック | `notes/batch23-game-start-design.md` |

**★確認事項は全確定済みであり、実装チャットでの追加確認は不要。**
**★a系なので Opus + 拡張思考で走らせること。**

★23 は 22 のクリック規約(**左=見る / 右=動かす**)を前提にマリガンの選択UIを設計する。
**Ctrl+左クリックの複数選択は既にある**ので、新しい選択操作を発明しないこと。
★先攻選択権(21c)は**ログに残すだけ**で盤面に触っていない。23 で「実際に先攻を設定する」
ときは、21c の `firstPlayer` を作り替えるのか別の操作を足すのかを先に決めること。

### 手動モード フェーズ2 + 追加分 — **完了**

| バッチ | 種別 | 範囲 | 状態 |
|---|---|---|---|
| ~~21a~~ | a系 | サーバ全部 | **完了** |
| ~~21b~~ | b系 | 入口の画面(ロビー・席選択・自席=下) | **完了** |
| ~~21c~~ | b系 | 盤面の仕上げ(相手上段・非公開・矢印・観戦トグル・先攻決め) | **完了** |
| ~~22~~ | b系 | UI8点(クリック規約・マナ表示・ウェポン枠・数値ボタン・操作説明) | **完了** |

**残るのは `mvn test` の実行と、手元での実対戦による確認である。**

### 手動モード フェーズ1 — 完成済み(17a〜20c)

**★盤面レイアウトは確定している。** 22 で動かしたのは
**リーダー下の空きマス1つ(ウェポン枠の新設)だけ**であり、
中央列・右列の列構成・センターライン・パイルの位置・相手上段の並びには手を入れていない。

### Ver.0.4 対応(手動モードとファイルが重ならない)

着手条件は満たされている。15c / 15d(Sonnet 5)、その後15e(基盤・Opus)、
全6文明168枚の整合チェック(Fable 5)の順。**23 の後になる。**

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
  `mvn test` が親POMを解決できず走らない(20b で判明・21a/21b/21c/22 で再確認)。**
  **納品後、マスターの手元で `mvn test` を必ず走らせてもらうこと。**
- **★★`codeload.github.com` の zip URL は 403 で落ちることがある(20bで判明)。**
  `git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git` は通る。
- **★Playwright は `/opt/pw-browsers` のブラウザを使う。**
  ★22 では `PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers` を環境変数で渡すだけで通った
  (グローバル導入済みの playwright と版が合っていた)。合わなければ 21c の手順
  (ホームへコピーして要求版へシンボリックリンク)に落ちる。`npx playwright install` は通らない。

### 手動モードの原則

- **★★手動モードで「判断」を実装しない — 例外はウェポンの付け替え1つだけ(20b)。**
  21a / 21b / 21c / 22 でも増やしていない。
  ★**視点フィルタと操作権限は「判断」ではない**(21a 設計解説 1-5)。
  ★**先攻選択権(21c)も「判断」ではない**。振っているのはダイスであって裁定ではない。
  ★クライアント側のボタンの出し分け・観戦者のドラッグ抑止は
  **操作補助**であり、検証はサーバに残っている(設計判断27)。
- **★★共有ゾーン(PLAY / REVEAL)は席に属さない。** `ManualCardRef.seatId()` が
  null になりうる。`seatId` を無条件に参照するコードを書かないこと。
- **★★スナップショットに新しいフィールドを足したら `ManualGameState.copy()` /
  `ManualCardInstance.copy()` を必ず直す。**
- **★★共有ゾーンの所有(`placedBySeat`)は set と clear を必ず対で書く(21a)。**
- **★★公開範囲の判定を2箇所に書かない(21a)。** 宣言は `ManualZone.contentsPublic`、
  判定は `ManualViewpoint.canSeeZone` の1つだけ。
  ★クライアントは `zones` にキーがあるかを見るだけにする(`isZoneVisible`)。
  ★22 は左クリック側にも同じ手当てを広げた(`zoomTopOrDeny`)。
- **★★`ManualSeat.availableMp()` は「マナゾーン全体のアンタップ枚数」であり、
  裏向きも数に入れている。この意味を変えてはならない。**
  ★22 の「相手の裏マナのタップ/アンタップ内訳」は `mp` からの引き算で出しており
  (`faceDownManaSplit`)、`availableMp()` を「表向きだけ」に変えると**静かに壊れる**
  (例外は出ず、数字だけがずれる)。
- **★★ログの構造化は配信とダウンロードを同時に変える(21a)。**
- **★新しいログ種別を足すときは `plain` の分類を必ず決める(21a)。**
- **★MPを直接増減する操作を作らない。** マナのアンタップ枚数からの派生値である。
- **★実装済み文明リストの唯一の正は `DeckValidator.IMPLEMENTED`。** 手動モードは使わない。
- **カード登録は card ID が `qte-cards.json` に実在するか必ず照合する。**
- **手動モードのカードIDを Java にリテラルで書かない。**

### UI の落とし穴

- **静的ファイルを変更したらキャッシュバスティングのバージョンをインクリメントする。**
  現在 `manual-battle.js?v=12` / `battle.css?v=20`(22 で 11→12 / 19→20)。
  ★`verify/build_harness.py` の置換文字列も同時に直すこと(版がずれるとハーネスが作れない)。
- **★★★描画の席と送信の席を混ぜない(21b で最重要だった罠)。**
  画面の上下は `bottomSeatId()` / `topSeatId()` が決め、
  `cardLocation` / `registerDropTarget` / 送信ペイロード / **矢印のホバー先**には
  **実席**(`seatView.id`)を渡す。
  **描画関数の中に `'A'` / `'B'` をリテラルで書かない。**
  ★22 の `createLeaderTile(seat, { withWeapon })` も同じ規約に従っている。
  **席によって描き分けたくなったら、判定ではなく引数で渡す。**
- **★★クリック規約(22 1章)。左=見る / 右=動かす。**
  ```
  左クリック = 拡大表示
  右クリック = タップ / 一覧を開く
  Shift + 左 = 表裏      Ctrl(⌘)+ 左 = 複数選択
  ```
  **例外は山札だけ**(左=1枚ドロー / 右=全面表示)。
  ★**新しく `contextmenu` を張る箇所では `e.preventDefault()` を必ず呼ぶ。**
  忘れるとタップのたびにブラウザのメニューが出る。
  ★**タップできないゾーン(手札・共有ゾーン・帯の中)でも右クリックは拡大を返す。**
  無反応にしない(21 設計書 3-5 と同じ考え方)。
- **★★カードの上に載っている専用ボタンは規約の外にある(22 1-6)。**
  数値チップ(`.manual-stat-button`)・進化バッジ・札チップ・使用済バッジ・
  山札の [シャッフル][上へ][下へ]。すべて左クリックで、`stopPropagation` する。
  ★**検証で `.manual-tile` の中央をクリックすると数値チップに当たる**(22 2章の罠)。
  カード本体を押したいときは `.manual-tile-name` を狙うこと。
- **★★対戦部屋では非公開ゾーンが `zones` にキーごと現れない(21a)。**
  枚数は `zoneCount(seatView, zoneName)` を使う(`counts` を優先する)。
- **★★非公開ゾーンのクリックで空の帯を開かない / 空の拡大を出さない。**
  `openZoneOrDeny`(右)と `zoomTopOrDeny`(左)を通すこと。
- **★★カードの大きさの上限は「幅」ではなく「画面の高さ」から決める(20c)。**
- **★相手上段は高さ148px以内(21 設計書4章)。現在140px。** 行の高さを決めているのは
  リーダータイル(120px)である。★22 でマナ・墓地・消滅のタイルを 40×56 → **48×66** へ
  広げたが、ラベル込みで84pxであり120pxを超えない。**要素を足すときは実測すること。**
- **★盤面全体の高さは1280×950で現在 821px(上限900px級)。**
  ★22 でマナ行の見出しを1行足したぶん 804 → 821 になった。ウェポン枠は右列の既存の行に
  収まったため縦に1pxも足していない。
- **★★flex アイテムの既定は `min-width: auto` であり、内容の最小幅より縮まない。**
  可変幅の列には必ず `min-width: 0` を書くこと。
- **★★flex の親に高さを固定すると、中身の height 指定は効かなくなる(20c)。**
- **★★検証ハーネスの Bootstrap 代替に漏れがあると「ハーネスでだけ壊れる」(20c・21c・22)。**
  テンプレートで使ったユーティリティクラスは `verify/build_harness.py` の代替にも足すこと。
  ★21c で `box-sizing: border-box`、22 で `.table-bordered` / `thead` / `.fw-bold` / `.ps-4`
  の欠落が判明した。
  ★**厳しい寸法の検証項目を書いて初めて「どこが似ていないか」が分かる。**
- **★★検証ハーネスが本物とズレていると「壊れていても PASS する」(21b)。**
  `verify/verify.js` の `render()` は `window.latestView` と内部の `latestView` の
  **両方**へ代入している。片方だけに戻さないこと。
- **★★「起きてほしくないこと」だけを検証すると、何も保証されない(22 2章)。**
  「tap が送られない」だけでは**何も起きなかった**ときと区別が付かない。
  `zoomedImage()` で**何が拡大されたか**まで確かめること。
  ★ドロップ先を減らす変更も同じで、「送られないこと」を明示的に検証しないと
  二重に受けていても通ってしまう。
- **★★`page.evaluate` に Promise を返す関数を渡さない(21b)。**
- **★★ドラッグの「起点から除外する」を `draggable=false` の子要素明示で実現しては
  ならない(20a 3-1)。** `dragstart` 内で `document.elementFromPoint()` を見て
  `preventDefault()` する(`e.target` ではダメ)。★22 のウェポン枠も同じ形である。
- **★★`registerDropTarget` で登録した要素を入れ子にすると `drop` が二重発火する(20a 3-2)。**
- **★★ドラッグ&ドロップの検証に合成 `DragEvent` を使ってはならない。**
  必ず `page.mouse.down/move/up` による実操作で検証する。
- **★★送信メッセージを件数で確かめる検証は、揮発メッセージ(`dragcue`)を除いて数える(21c)。**
  `verify/verify.js` の `boardMessages()` を通すこと。
- **★矢印(CUE)はログ・履歴・Undo に触れない(21c 7-2)。**
  オーバーレイには `pointer-events: none` が必須。
  ★**ゾーンの置き場所を変えたら `registerZoneAnchor` の登録先も移す**(22 でウェポンを移した)。
  移し忘れると矢印の端点が古い場所を指したままになる。
  ★アンカーは `anchorElement({seatId, zone})` の戻り値そのものを検証すると原因が絞れる。
- **★`battle.css` は通常モードと手動モードの共有ファイルである。**
  `.leader-card` / `.minion-row` / `.mana-chip` / `.mana-row` は通常モードも使っている。
  手動モード側の変更は `.manual-` 接頭辞の新規クラスへの追加で行うこと。
  ★**`.mana-tile` / `.mana-strip` 系は手動モード専用**なので変更してよい
  (名前が `.mana-chip` / `.mana-row` と紛らわしいので取り違えないこと)。
- **★オーバーレイの id は `manual-overlay-root`、トーストは `#manual-toast`。**
  トーストは**出しっぱなしで `d-none` を付け外しする**実装なので、
  「今出ている」を数えるには `:not(.d-none)` が要る(22 2章)。
- **★HTMLテンプレートを書き換えるときは、対応するJS描画関数が「入れ物への追記」を
  前提にしているか「入れ物ごと使う」ことを前提にしているかを確認してから書く。**
- **★観測結果を環境の制約だと決めつけないこと。** 実マウス検証の結果が優先する。
- **★検証ハーネスは `verify/` にリポジトリ管理してある(20bで新設)。**
  `python3 verify/build_harness.py && node verify/verify.js` で **187項目**が走る。
  **新しいUIを足したらここに検証項目を追加すること。**
  ★`build_harness.py` は盤面(`harness.html`)とロビー(`harness-lobby.html`)の2つを作る。
  ★`verify/shot.js` は目視用のスクリーンショットを撮る(`W`/`H`/`OUT` で調整できる)。

### その他

- **★occupantId はサーバがページ生成時に発行しない(19a以降)。** クライアントの
  localStorage(キー `qte-manual-occupant-{roomId}`)が保持する。
- **★切断復帰の猶予は5分固定**(`ManualCleanupScheduler.GRACE_PERIOD`)。
  ★21a で `ManualViewBuilder` にも表示用の同じ値がある。片方だけ変えないこと。
- **★手動モードの無人部屋は5分で自動削除される(21a)。**
- **★ゲームの正式名称は「クイン・タブーエレメント」である。**
- **★テストコードは機械チェックの網の外にある。** `tools/check_structure.py` は
  `src/main/java` を指定して走らせる。
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
```

**DOM構造・ドラッグ&ドロップが絡む変更は `verify/` の実マウス検証を通すこと
(合成DragEventは使わない)。**

---

## 5. チャット開始テンプレート

### ★A. Batch 23(次はこれ)

`notes/batch23-game-start-design.md` の12章にあるテンプレートをそのまま使う。
**★a系なので Opus + 拡張思考で走らせること。**
テンプレート中のハンドオフ名を **`claude/qte-handoff-v26.md`** に、
確認項目を**本書0章の「22 の確認項目」**に読み替えること。

### B. Ver.0.4 対応(23 の後)

```
QTE Battle の開発を継続する。Batch 15c(Ver.0.4 対応)を行う。
※b系バッチである(Sonnet 5・拡張思考は不要)。

1. プロジェクトナレッジ内の `qte-project-reference.md` を読む
   (ゲームルール・設計判断の唯一の正)。
2. プロジェクトナレッジ内の `claude/qte-handoff-v26.md` を読む
   (直近の状態・既知の落とし穴)。3章「既知の落とし穴」は必読。
3. プロジェクトナレッジ内の `notes/ver0.4-transcription-notes.md` と
   `notes/batch15a-design-notes.md` / `notes/batch15b-design-notes.md` を読む。
   ★手動モード(17a〜22)とはファイルが重ならない。混ぜないこと。
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

### C. 手動モードの手直しが必要になったとき

上記 B の 3 を
`notes/batch21-versus-ui-design.md`(フェーズ2の唯一の正)+
`notes/batch22-ui-refinement-design.md`(UI規約の唯一の正)+
`notes/batch22-design-notes.md`(直近の実装解説)+
`batch16-manual-mode-design-v2_4.md` に差し替える。

---

## 6. 22 完了時点の積み残し

- **★`mvn test` が未実行である。** 22 に Java 変更は無いが、
  **21c の Java 変更(`ManualLogKind` / `ManualGameService` / `ManualOperationService` /
  `ManualWsController` + テスト1ファイル)のコンパイル確認が取れていない**。
  手元で必ず走らせること。期待は `ManualVersusTest` が34件通ること。
- **★入室前の席選択で「切断中」を表示できない**(21b から継続)。
  `RoomSummary` が接続状態を持たない。座れないこと自体は正しく無効化されるので実害は小さい。
  直すなら `RoomSummary` に真偽値を2つ足す。
- **拡大画像パネルが 204px 幅のまま**(右列は 400px)。21c・22 とも触らない判断
  (マスター確認済み)。広げるならログを拡大画像の下へ移すことになり、
  20c で決めた右列の配置が変わる。
- **ミニオン行の頭打ち**(20c から継続)。タイル上限が `min(180px, 16vh)` のため、
  1920幅では左右に余白が残り中央寄せになる。
  ★これは意図した挙動でもある(上限が無いとワイドモニタでタイルが縦にも巨大化する)。
- **★相手上段のマナ名が 8px である(22 で新規)。** 48px 幅にカード名を3行まで
  入れているため字が小さい。実対戦で読みづらければ、名前を1〜2行に減らして字を
  大きくするのが先の手当てである(タイルをさらに広げると 148px の高さ制約に当たる)。
- **★マナ行の見出しで盤面が 804 → 821px になった(22)。** 設計書 2-8 は
  「ストリップのラベルを削って相殺する」としていたが、ラベルは20cの時点で既に1行であり
  削り代が無かった。上限900pxに対して79pxの余裕があるため、行を窮屈にする対処は取っていない。
- `qte-project-reference.md` の1章(実装状況)が2026-07-21時点のまま古い。

### 解消済み(22 で片付いたもの)

- ~~左クリックがタップで、拡大が右クリックだった~~ → 入れ替えた(1章)
- ~~相手の墓地・消滅の一番上を一覧の前に拡大できない~~ → 左クリックで拡大(G5)
- ~~マナの絵が自席と相手でちぐはぐ~~ → 表=文明色タイル / 裏=裏面画像に統一(2-2)
- ~~相手の裏マナの内訳(何枚使えるか)が読めない~~ → `mp` からの派生値で2枠に分けた(2-7)
- ~~自席のマナの表・裏・合計の枚数が出ていない~~ → 行見出しに出した(2-8)
- ~~ウェポンが44×60のミニタイルで、数値も使用済もモーダル送りだった~~ → 独立枠(3章)
- ~~LP・ATK/HP が「押せる」と画面に書かれていない~~ → 枠+鉛筆(4章)
- ~~操作説明から新しい規約が読み取れない~~ → 原則1行 + 場所×左/右/ドラッグの表(5章)
