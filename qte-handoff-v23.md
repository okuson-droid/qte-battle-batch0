# QTE 対戦アプリ — 引き継ぎ書

最終更新: 2026-08-05(Batch 21a 完了。次は 21b:ロビー・席選択画面)
**★21a はサーバのみのバッチである。UI(HTML / CSS / JS)には1行も触れていない。**
**★`mvn test` が未実行である(サンドボックスから Maven Central へ到達できない)。
納品後、必ずマスターの手元で走らせること。**

**★新しいチャットを始めるときは 5章「チャット開始テンプレート」を使うこと。**

このファイルは、新しいチャットで作業を再開するための最小限の情報をまとめたものである。
ゲームルール・設計判断の詳細は `qte-project-reference.md`、カードの詳細は `qte-cards.json`。

系統が2つ並行している。**混ぜないこと。**

| 系統 | 前提ドキュメント |
|---|---|
| **手動モード フェーズ2**(21a〜21c) | `notes/batch21-versus-ui-design.md`(**唯一の正**)+ `batch16-manual-mode-design-v2_4.md` + `notes/batch21a-design-notes.md` |
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

   **★★`codeload.github.com` の zip URL は環境によっては 403 で落ちない。**
   その場合は git clone を使うこと。こちらは通る。

```bash
git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git
```

4. **★「反映済み」という記述を信じず、直近バッチで変更したはずの箇所を実際に読んで照合する。**
   `git log` / `git diff HEAD~1` も併用すると速い。

## 21a の確認項目(次チャットで一度だけ照合すること)

- `ManualZone.java` の各定数が3引数 `(表示名, shared, contentsPublic)` になっていること。
  **`MANA` の `contentsPublic` が `false`** であること(表向きだけ見える特例は
  `ManualViewBuilder` にある。ここを true にするとログから名前が漏れる)。
- `ManualViewpoint.java` があり、`canSeeZone(ownerSeat, zone)` が
  「全公開部屋 → 全見え観戦 → リーダー(zone==null)→ 共有/公開ゾーン → 自席」の順で
  判定していること。★MANA の特例が**入っていない**こと。
- `ManualCardInstance.java` に `placedBySeat` があり、**`copy()` で引き継がれている**こと。
- `ManualOccupant.java` が `seatId` を持ち、`getRole()` が席から導出されていること
  (`role` フィールドが**無い**こと)。
- `ManualRoom.java` が `ManualRoomOptions` を持ち、`takeSeat` / `standUp` /
  `occupantOfSeat` / `firstFreeSeat` / `emptyFor` があること。
  `addLog(ManualLogEvent)` と `addLog(String)` の2つがあること。
- `ManualHistory.java` に `forRoom(ManualRoomType)` / `lastActorSeat()` /
  `VERSUS_DEPTH` があり、`push` / `undo` / `redo` が `ManualSeatId` を受けること。
- `ManualLogEntry` が `(seq, at, ManualLogEvent event)` になっていること
  (`text` フィールドが**無い**こと)。
- `ManualLogRenderer` が1つだけ存在し、`ManualViewBuilder`(配信)と
  `ManualLobbyController#exportLog`(ダウンロード)の**両方**が注入していること。
  ★片方だけなら完全ログの裏口が残っている。
- `ManualOperationService` の全公開メソッドが `ManualActor` を受け、
  戻り値が `ManualLogEvent` であること。`ManualPermissions.require(...)` を
  各操作の先頭で呼んでいること。
- `ManualWsController` に `/seat` `/viewpoint` `/dragcue` の
  `@MessageMapping` があること。`dragcue` が `dispatch` を**通らない**こと。
- `ManualCleanupScheduler` が `roomManager.removeRoom(...)` を呼んでいること
  (19a 以来の「呼び出し元なし」が解消されていること)。
- `ManualLobbyController` に `GET /manual/api/rooms`(部屋一覧)があり、
  鍵つき部屋の `roomId` が **null** で返ること。
- `src/test/java/com/example/qte/ManualVersusTest.java` があり、30件のテストを持つこと。

---

## 1. 次の作業の候補(優先順位順)

### 手動モード フェーズ2

| バッチ | 種別 | 範囲 | 状態 |
|---|---|---|---|
| ~~21 設計~~ | 設計 | 対戦・観戦UIの全確認事項の確定 | 完了(`notes/batch21-versus-ui-design.md`) |
| ~~21a~~ | a系 | **サーバ全部**(部屋種別・一覧API・掃除・視点フィルタ・ログ構造化・権限・placedBySeat・視点切替・矢印中継・JUnit) | **完了** |
| **21b** | b系 | **入口の画面** — ロビー(一覧・作成フォーム)・席選択画面・在室者リストのポップオーバー・席を立つ/昇格・自席=下の描画改修 | **次はこれ** |
| 21c | b系 | 盤面の仕上げ — 相手上段の再構成(設計書4章)・非公開チップのフィードバック・矢印の描画・観戦トグル・verify拡張 | 未着手 |

### 手動モード フェーズ1 — 完成済み(17a〜20c)

**★手動モードの盤面レイアウトは確定した(マスター言明)。** フェーズ2で新しい画面を
足す場合を除き、盤面レイアウトには手を入れない。

### Ver.0.4 対応(手動モードとファイルが重ならない)

着手条件は満たされている。15c / 15d(Sonnet 5)、その後15e(基盤・Opus)、
全6文明168枚の整合チェック(Fable 5)の順。

**2系統はどちらを先に進めてもよいが、混ぜないこと。**

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
  `mvn test` が親POMを解決できず走らない(20bで判明・21aで再確認)。**
  **納品後、マスターの手元で `mvn test` を必ず走らせてもらうこと。**
  ★21a では代替として、lombok を機械展開し Spring/Jackson/JUnit/AssertJ を
  スタブに置き換えた javac 型チェックを自作して通した(manual パッケージ エラー0件)。
  ただし DI やアノテーションの意味論は検証していないため、`mvn test` の代わりにはならない。
- **★★`codeload.github.com` の zip URL は 403 で落ちないことがある(20bで判明)。**
  `git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git` は通る。

### 手動モードの原則

- **★★手動モードで「判断」を実装しない — 例外はウェポンの付け替え1つだけ(20b)。**
  21a でも増やしていない。
  ★**視点フィルタと操作権限は「判断」ではない**(21a 設計解説 1-5)。
  「情報保護と盤面同一性の保証」であり、5-1 の原則の外に置く。
- **★★共有ゾーン(PLAY / REVEAL)は席に属さない。** `ManualCardRef.seatId()` が
  null になりうる。`seatId` を無条件に参照するコードを書かないこと。
  21a で新設した `ManualLogPlace` / `ManualLogCard` / `ManualViewpoint` も同じ前提で書いてある。
- **★★スナップショットに新しいフィールドを足したら `ManualGameState.copy()` /
  `ManualCardInstance.copy()` を必ず直す。** 複製から漏れたものは Undo で巻き戻らない。
  20b の `sharedZones`、21a の `placedBySeat` が該当。
- **★★共有ゾーンの所有(`placedBySeat`)は set と clear を必ず対で書く(21a)。**
  入るときだけ書いて出るときを忘れると、手札へ戻したカードに所有が残り、
  次に別の席が使えなくなる。素材(進化スタックの下段)にも付けること。
- **★★公開範囲の判定を2箇所に書かない(21a)。** 宣言は `ManualZone.contentsPublic`、
  判定は `ManualViewpoint.canSeeZone` の1つだけ。盤面ビュー・ログのマスク・
  矢印の起点フィルタの3経路がこれを通る。片方だけ直すと
  「盤面からは隠したのにログには名前が出る」形で漏れる。
- **★★ログの構造化は配信とダウンロードを同時に変える(21a)。**
  両方が同じ `ManualLogRenderer` を通る。片方だけだと完全ログの裏口が残る。
- **★MPを直接増減する操作を作らない。** マナのアンタップ枚数からの派生値である。
- **★実装済み文明リストの唯一の正は `DeckValidator.IMPLEMENTED`。** 手動モードはこの判定を
  一切使わない。
- **カード登録は card ID が `qte-cards.json` に実在するか必ず照合する。**
- **手動モードのカードIDを Java にリテラルで書かない。**

### UI の落とし穴(21b / 21c で効く)

- **静的ファイルを変更したらキャッシュバスティングのバージョンをインクリメントする。**
  現在 `manual-battle.js?v=9` / `battle.css?v=17`。**21a では上げていない**(UI無変更)。
- **★★カードの大きさの上限は「幅」ではなく「画面の高さ」から決める(20c)。**
  ミニオンは `max-width: min(180px, 16vh)`、手札は `window.innerHeight * 0.105`。
- **★★flex アイテムの既定は `min-width: auto` であり、内容の最小幅より縮まない。**
  可変幅の列には必ず `min-width: 0` を書くこと。
- **★★flex の親に高さを固定すると、中身の height 指定は効かなくなる(20c)。**
- **★★検証ハーネスの Bootstrap 代替に漏れがあると「ハーネスでだけ壊れる」(20c)。**
  テンプレートで使ったユーティリティクラスは `verify/build_harness.py` の代替にも足すこと。
- **★★ドラッグの「起点から除外する」を `draggable=false` の子要素明示で実現しては
  ならない(20a 3-1)。** `dragstart` 内で `document.elementFromPoint()` を見て
  `preventDefault()` する(`e.target` ではダメ)。
- **★★`registerDropTarget` で登録した要素を入れ子にすると `drop` が二重発火する(20a 3-2)。**
  現在は `drop` ハンドラ先頭で `e.stopPropagation()` している。
- **★★ドラッグ&ドロップの検証に合成 `DragEvent` を使ってはならない。**
  必ず `page.mouse.down/move/up` による実操作で検証する。補間は30ステップ程度に細かくし、
  最終位置到達後に短い待機を挟んでから `mouse.up()` する(20a)。
- **★`battle.css` は通常モードと手動モードの共有ファイルである。**
  `.leader-card` / `.minion-row` / `.mana-chip` / `.mana-row` は通常モードも使っている。
  手動モード側のサイズ変更は必ず手動モード専用クラス(`.manual-leader-tile` 等)への
  追加で行い、共有クラスの定義自体は変えないこと。
- **★HTMLテンプレートを書き換えるときは、対応するJS描画関数が「入れ物への追記」を
  前提にしているか「入れ物ごと使う」ことを前提にしているかを、関数の中身を読んで
  確認してから書く。**
- **★観測結果を環境の制約だと決めつけないこと。**「設計書に書いてある」ことと
  「ブラウザの実際の挙動」は別物であり、実マウス検証の結果が優先する。
- **★検証ハーネスは `verify/` にリポジトリ管理してある(20bで新設)。**
  `python3 verify/build_harness.py && node verify/verify.js` で 42項目が走る。
  **新しいUIを足したらここに検証項目を追加すること。**

### その他

- **★occupantId はサーバがページ生成時に発行しない(19a以降)。** クライアントの
  localStorage(キー `qte-manual-occupant-{roomId}`)が保持する。
- **★切断復帰の猶予は5分固定**(`ManualCleanupScheduler.GRACE_PERIOD`)。
  ★21a で `ManualViewBuilder` にも表示用の同じ値がある。片方だけ変えないこと。
- **★手動モードの無人部屋は5分で自動削除される(21a で追加)。**
  19a 以来の「`removeRoom` の呼び出し元が無い」は解消済み。
- **★ゲームの正式名称は「クイン・タブーエレメント」である。**「クイン・タブーエレメンタル」
  は誤記(19bで8箇所を修正)。
- **★テストコードは機械チェックの網の外にある。** `tools/check_structure.py` は
  `*Test.java` のJUnitメソッド名(日本語)を「未解決のメソッド呼び出し」と誤検出する
  既知のノイズがある。無視してよい。`check_structure.py` は `src/main/java` を指定して走らせる。
- **`tools/check_records.py` の不一致3件は既存の既知の誤検出である**
  (`GameActions.java:775` / `GameService.java:1144` / `CardEffectRegistry.java:748`)。
  manual パッケージは0件。

---

## 4. デリバリー形式

zip(変更・新規ファイルのみ。削除したファイルはzipに含めず、パスを明記して手元での削除を
依頼する)+ `batchNN-design-notes.md`(チートシート/設計根拠★/検証手順/理解確認Q&A/
次バッチ予告)+ ハンドオフ更新。

マシンチェックスクリプト群をパッケージング前に必ず実行する。

```bash
python3 tools/check_structure.py src/main/java                       # ★最優先
python3 tools/check_all.py .                                         # 項目 1・3・5・6
python3 tools/check_records.py src/main/java                         # 項目 4
python3 tools/check_undeclared.py src/main/resources/static/js/*.js  # 項目 8
node --check src/main/resources/static/js/manual-battle.js           # 項目 7
python3 verify/build_harness.py && node verify/verify.js             # DOM・実マウス検証
```

**DOM構造・ドラッグ&ドロップが絡む変更は `verify/` の実マウス検証を通すこと
(合成DragEventは使わない)。**

---

## 5. チャット開始テンプレート

```
QTE Battle の開発を継続する。Batch 21b(フェーズ2: 入口の画面)を行う。
※b系バッチである(Sonnet 5・拡張思考不要)。

1. プロジェクトナレッジ内の `qte-project-reference.md` を読む
   (ゲームルール・設計判断の唯一の正)。
2. プロジェクトナレッジ内の `qte-handoff-v23.md` を読む
   (直近の状態・既知の落とし穴)。3章「既知の落とし穴」は必読。
3. プロジェクトナレッジ内の `notes/batch21-versus-ui-design.md` を読む
   (★Batch 21 の唯一の正。確認事項は全確定済みであり、追加確認は不要)。
   特に 1-3(ロビー)・2章(入室と席)・3-1(自席=下)・10章(実装時の落とし穴)。
   併せて `notes/batch21a-design-notes.md` の 0章(チートシート)と
   5章(21bへの申し送り)を読む。サーバのAPIはすべて 21a で揃っている。
4. ソースコードを取得する(zipのアップロードは不要)。
   ★codeload の zip URL は 403 で落ちないことがある。その場合は git clone を使う。

git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git

5. ★取得したコードで Batch 21a が反映されているかを確認し、結果を報告すること。
   「反映済み」という記述を信じないこと。ハンドオフ0章の「21aの確認項目」を
   実際に読んで照合する。

21b の範囲(設計書9章): 入口の画面 — ロビー(部屋一覧・部屋作成フォーム)・
席選択画面・在室者リストのポップオーバー・席を立つ/昇格・自席=下の描画改修。

制約:
- 盤面レイアウトは確定済み。変更しない(相手上段の再構成は21cの担当)。
- 描画の「A=下」固定を外すとき、`cardLocation` / `registerDropTarget` に渡す席は
  ★実席のまま保つこと。入れ替えは表示位置だけにする(設計書10章)。
- 静的ファイルを変更したらキャッシュバスティングの v を上げること
  (現在 js v=9 / css v=17)。
- ドラッグ&ドロップが絡む検証は必ず実マウス操作(page.mouse)で行う。
  `verify/` に検証ハーネスがある。新しい画面の検証項目を追加すること。
- このサンドボックスから Maven Central へは到達できない。`mvn test` は納品後に
  こちらの手元で走らせるので、必ず依頼すること。機械チェック(tools/)は実行すること。
- 納品形式はハンドオフ4章のとおり。
- 判断に迷う点や確認したいことはまとめて質問すること。1つずつ聞かない。
- 1バッチ1チャットの原則を守り、中断しないこと。

呼び方は「クロエ」、こちらの呼び方は「マスター」で。会話は日本語カジュアル体でお願い。
ドキュメント類は通常文体(である調)で書くこと。
```

---

## 6. 21a 完了時点の積み残し

- **`mvn test` が未実行である**(サンドボックスから Maven Central へ到達できないため)。
  `ManualVersusTest` の30件を含め、手元で必ず走らせること。
- **`manual-battle.html` のログ書出リンクに `occupantId` が付いていない。**
  対戦部屋では 400 になる。21c で付けること
  (対戦部屋は 21b までUIから作れないため、現時点で実害は無い)。
- 21a の API を使う画面がまだ無い(21b / 21c で作る)。
  現時点で対戦部屋を作れるのは curl などの API 直叩きだけである。
- 拡大画像パネルは 204px 幅のまま(右列は 400px)。もう少し大きくできる。
- 超ワイド画面(1920以上)での相手上段の左右の離れ、ミニオン行の頭打ちは 21c で扱う。
- `qte-project-reference.md` の1章(実装状況)が2026-07-21時点のまま古い。
