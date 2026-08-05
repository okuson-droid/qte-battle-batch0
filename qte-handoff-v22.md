# QTE 対戦アプリ — 引き継ぎ書

最終更新: 2026-08-05(Batch 20b + 20c 完了。次はフェーズ2 UI詰め、またはVer.0.4対応 15c)
**★20b と 20c は同じ土台(batch20a)からの連続した変更であり、1つのzipで納品している。
分けて適用しないこと。**
**★手動モード フェーズ1(一人回し)は19aで完成済み。19b・20aで操作性を改善し、
20bで盤面レイアウトを再設計して共有ゾーンを導入した。**

**★新しいチャットを始めるときは 5章「チャット開始テンプレート」を使うこと。**

このファイルは、新しいチャットで作業を再開するための最小限の情報をまとめたものである。
ゲームルール・設計判断の詳細は `qte-project-reference.md`、カードの詳細は `qte-cards.json`。

系統が2つ並行している。**混ぜないこと。**

| 系統 | 前提ドキュメント |
|---|---|
| **手動モード**(17a〜20c・フェーズ1完成) | `batch16-manual-mode-design-v2_4.md`(唯一の正)+ `notes/batch20b-ui-design.md`(20bの唯一の正)+ `notes/batch20b-design-notes.md` + `notes/batch20c-design-notes.md` + `notes/batch20a-design.md` |
| **Ver.0.4 対応**(15c/15d/15e) | `notes/ver0.4-transcription-notes.md` + `notes/batch15a-design-notes.md` + `notes/batch15b-design-notes.md` |

---

## 0. 最初にやること

1. `qte-project-reference.md` を読む(ゲームルール・設計判断の唯一の正)。
   **★このファイルはプロジェクトナレッジ内で2026-07-21時点の記述のまま止まっている
   (3文明実装時点)。実際は6文明完成・Ver.0.4対応中であり、1章の状態記述が古い。
   ゲームルール自体(2章以降)は有効。発注者側での更新を推奨する。**
2. 作業に応じて上表の前提ドキュメントを読む。
3. ソースコードを取得する。**zipのアップロードは不要**。

   **★★20bで判明: `codeload.github.com` の zip URL は環境によっては 403 で落ちない。**
   その場合は git clone を使うこと。こちらは通る。

```bash
git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git
# 落ちない場合の従来手順(環境によっては 403):
# https://codeload.github.com/okuson-droid/qte-battle-batch0/zip/refs/heads/main
```

4. **★「反映済み」という記述を信じず、直近バッチで変更したはずの箇所を実際に読んで照合する。**
   台帳だけでなく**コードにも適用すること**。`git log` / `git diff HEAD~1` も併用すると速い。

## 20bの確認項目(次チャットで照合すること)

- `ManualZone.java` に `PLAY("プレイ中", true)` / `PRIVATE("確認", false)` があり、
  `REVEAL` が `("公開", true)` になっていること(表示名が「一時公開」から変わった)。
  enum が `shared` フラグを持つこと。
- `ManualGameState.java` に `sharedZones`・`cards(seatId, zone)`・`clearSharedZones()` があり、
  **`copy()` が `sharedZones` を深くコピーしていること**(ここが漏れると Undo が中央だけ
  巻き戻らない)。
- `ManualSeat.java` のコンストラクタが `if (!z.isShared())` で共有ゾーンのリストを作らず、
  `zone()` が共有ゾーンで `IllegalArgumentException` を投げること。
- `ManualOperationService.move` の先頭が
  `request.toZone().isShared() ? null : seatOf(request.toSeat())` であること。
  `FACE_UP_ON_ARRIVAL` に `PLAY`・`PRIVATE` が入っていること。
  `replaceEquippedWeapon` が存在し、`card.isFromTaboo()` で LOST / TRASH を分けること。
- `ManualCardInstance.java` に `fromTaboo` があり、`copy()` で引き継がれること。
  `ManualGameService.applyImport` の禁忌ループで `setFromTaboo(true)` していること。
- `ManualGameService` の `loadDeck` と `resetRoom` の両方で `clearSharedZones()` を
  呼んでいること。
- `ManualGameView` に `shared` 項があり、`ManualViewBuilder.buildSeat` が
  `if (z.isShared()) continue;` で席から共有ゾーンを除いていること。
- `manual-battle.html` に `#center-line` / `#pile-grid` / `#weapon-modal` があり、
  `#seat-self-leader-row` と ターン/フェイズ行が**無い**こと。
  `manual-battle.js(v=8)` / `battle.css(v=16)` を参照していること(★20cで v=7/v=15 から更新)。
- `manual-battle.js` に `renderCenterLine` / `renderPiles` / `appendWeaponMini` /
  `openWeaponModal` があり、`renderLeaderRow` と `flashReject` と `PHASE_LABELS` が
  **存在しない**こと。
- `registerDropTarget` から `zoneName === 'WEAPON' && targetCard` の拒否分岐が
  **消えている**こと(装備済みでも落とせる)。
- `verify/` 一式(`build_harness.py` / `fixture.js` / `verify.js` / `shot.js`)があること。
  `node verify/verify.js` が **36/36** パスすること。

## 20cの確認項目(20bと同時に照合すること)

- `manual-battle.html` の `#manual-root` に `max-width` が**無い**こと。中央列が
  `flex:1 1 0; min-width:0`、右列が `width:400px` であること。
- 右列の先頭が `[#zoom-panel][ログ列]` の横並びであり、ログ列に**高さの固定が無い**こと
  (固定すると `log-box` が押し縮められ、クリック拡張が効かなくなる)。
- `battle.css` に `.manual-minion-row > .manual-tile` の
  `flex: 1 1 0; max-width: min(180px, 16vh)` があること。★上限が固定pxに戻っていないこと
  (固定pxだとワイド画面で中央に死に幅が出る)。
- `manual-battle.js` に `fitCardWidths` / `handCardMaxWidth` / `centerCardMaxWidth` があり、
  `createHandCard` が `width === null` を許すこと。
- `renderManaRow` にリーダータイルの生成が**無く**、`renderPiles` の末尾に
  `.manual-leader-slot` を追加していること。
- `window.addEventListener('resize', ...)` によるデバウンス再描画があること。
- `build_harness.py` の Bootstrap 代替に `.flex-column` が含まれること
  (20cでこれが無くハーネスだけ壊れた)。

---

## 1. 次の作業の候補(優先順位順)

### 手動モード — フェーズ1は完成済み

| バッチ | 種別 | 範囲 | 状態 |
|---|---|---|---|
| ~~17a~~〜~~19a~~ | a系/b系 | データ変換〜切断復帰・仕上げ | 完了 |
| ~~19b~~〜~~19b hotfix2~~ | b系/修正 | 盤面レイアウト再設計・ドラッグ不具合修正 | 完了 |
| ~~20a~~ | a系 | 山札からの直接移動・裏向きの正規化・LP増減UI | 完了 |
| ~~20b~~ | a系 | 盤面レイアウト再設計v2・新ゾーン・共有ゾーン | **完了** |
| ~~20c~~ | b系 | 全幅レイアウトへの再構成(Javaは無変更) | **完了** |

### ★次の作業はフェーズ2 UI詰め、またはVer.0.4対応のどちらか

**フェーズ2(ソロ対戦・対戦・観戦)— UI詰めセッション**

着手前に UI 詰めのセッションをもう1回行うこと(設計書16 11章。相手側ゾーンの見せ方、
視点切替の UI、在室者リストの画面、席選択画面などが未設計)。
**★20bで作った共有ゾーンと `PRIVATE` の公開範囲(`notes/batch20b-ui-design.md` 6-1)が
そのまま土台になる。** 21系バッチとして計画する。

**Ver.0.4 対応(手動モードとファイルが重ならない)**

着手条件は満たされている。15c / 15d(Sonnet 5)、その後15e(基盤・Opus)、
全6文明168枚の整合チェック(Fable 5)の順。詳細は `qte-project-reference.md` 1-5・1-2、
`notes/batch15b-design-notes.md` 6章を参照。

**この2つはどちらを先に進めてもよい。**

---

## 2. コンテキスト効率・発注者とのやりとり

grep 優先でファイルを渡り歩き、`view` による全体読み込みは避ける。
チャットの中断は再取得ぶんコンテキストを余計に消費するため避ける。

判断に迷う点・確認したい点は**まとめて質問する。**1つずつ聞かない。
呼び方は「クロエ」、発注者の呼び方は「マスター」。会話は日本語カジュアル体。

---

## 3. 既知の落とし穴

- **★「反映済み」という記述を信じない。** 台帳だけでなくコードも実データで照合する。
  18cの design-notes が「帯から盤面へ直接ドラッグできる」と記載して納品したが、実際には
  バックドロップがドロップイベントを遮断しており、一度も機能していなかった(19bで修正)。
- **★★このサンドボックスからは Maven Central へ到達できない(403)。`mvn compile` /
  `mvn test` が親POMを解決できず走らない(20bで判明)。** Java の担保は
  `tools/check_structure.py` と目視とテストコードだけになる。
  **納品後、マスターの手元で `mvn test` を必ず走らせてもらうこと。**
- **★★`codeload.github.com` の zip URL は 403 で落ちないことがある(20bで判明)。**
  `git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git` は通る。
- **★★手動モードで「判断」を実装しない — ただし1箇所だけ例外がある(20b)。**
  ウェポンの付け替え(`ManualOperationService.replaceEquippedWeapon`)は、アプリが
  行き先を決める唯一の処理である。リーダータイルをドロップ先にした帰結として
  マスターの判断で導入した。**自動化した以上、行き先の正しさ(禁忌由来は墓地ではなく
  消滅)まで実装の責任になる。** 同種の自動化を足すときは、付随する正しさの責任を
  必ずセットで数えること。
- **★★共有ゾーン(PLAY / REVEAL)は席に属さない。** `ManualCardRef.seatId()` が null に
  なりうる。`seatId` を無条件に参照するコードを書かないこと(`BOARD_ORDER`・`detach`・
  `describeOrigin` の3箇所が実際に踏んだ)。
- **★★スナップショットに新しいフィールドを足したら `ManualGameState.copy()` を必ず直す。**
  複製から漏れたものは Undo で巻き戻らない。20bの `sharedZones` が該当。
- **★★ドラッグの「起点から除外する」を `draggable=false` の子要素明示で実現しては
  ならない(20a 3-1)。** HTML5のドラッグは mousedown 位置から祖先方向へ `draggable=true`
  を探す。除外には `dragstart` 内で `document.elementFromPoint(e.clientX, e.clientY)` を
  見て `preventDefault()` する(`e.target` ではダメ)。
- **★★`registerDropTarget` で登録した要素を入れ子にすると `drop` が二重発火する(20a 3-2)。**
  現在は `drop` ハンドラ先頭で `e.stopPropagation()` している。入れ子のドロップ対象を
  新設する場合はこの前提(内側だけが処理する)を踏まえること。
- **数値はコードに埋まっていることがある。** 台帳が持つのは cost/attack/hp/keywords だけ。
- **実装済み文明リストの唯一の正は `DeckValidator.IMPLEMENTED`。** 手動モードはこの判定を
  一切使わない。
- **静的ファイルを変更したらキャッシュバスティングのバージョンをインクリメントする。**
  20bで v=7 / v=15、20cで **v=8 / v=16** まで上げてある。
- **★★カードの大きさの上限は「幅」ではなく「画面の高さ」から決める(20c)。**
  カードは縦長であり、幅を広げるとそのぶん確実に縦を食う。横幅追従の構成では、
  固定pxの上限を置くと「横に広いが縦が短い画面」で手札が画面外へ落ちる。
  ミニオンは `max-width: min(180px, 16vh)`、手札は `window.innerHeight * 0.105` で決めている。
- **★★flex アイテムの既定は `min-width: auto` であり、内容の最小幅より縮まない。**
  可変幅の列には必ず `min-width: 0` を書くこと。無いと固定幅の隣の列が画面外へ押し出される。
- **★★flex の親に高さを固定すると、中身の height 指定は効かなくなる(20c)。**
  ログを包む列に `height:286px` を置いたところ、`log-box` が押し縮められて
  クリック拡張が一切効かなくなった。伸縮させたい子を持つ親の高さは固定しない。
- **★★検証ハーネスの Bootstrap 代替に漏れがあると「ハーネスでだけ壊れる」(20c)。**
  `.flex-column` が無く、ログ幅が16pxとして測定された。テンプレートで使った
  ユーティリティクラスは `build_harness.py` の代替にも必ず足すこと。
- **カード登録は card ID が `qte-cards.json` に実在するか必ず照合する。**
- **手動モードのカードIDを Java にリテラルで書かない。**
- **★テストコードは機械チェックの網の外にある。** `tools/check_structure.py` は
  `*Test.java` のJUnitメソッド名(日本語)を「未解決のメソッド呼び出し」と誤検出する
  既知のノイズがある。無視してよい。
- **★MPを直接増減する操作を作らない。** マナのアンタップ枚数からの派生値である。
- **★`battle.css` は通常モード(`battle.html`)と手動モード(`manual-battle.html`)の
  共有ファイルである。** `.leader-card` / `.minion-row` / `.mana-chip` / `.mana-row` は
  通常モードも使っているため、手動モード側のサイズ変更は必ず手動モード専用クラス
  (`.manual-leader-tile` 等)への追加で行い、共有クラスの定義自体は変えないこと。
- **★occupantId はサーバがページ生成時に発行しない(19a以降)。** クライアントの
  localStorage(キー `qte-manual-occupant-{roomId}`)が保持する。
- **★手動モードの部屋は自動的には消えない。** `ManualRoomManager#removeRoom` は
  用意されているが呼び出し元が無い(19a 積み残し)。長時間運用ではメモリに残り続ける。
- **★切断復帰の猶予は5分固定(`ManualCleanupScheduler.GRACE_PERIOD`)。**
- **★ゲームの正式名称は「クイン・タブーエレメント」である。**「クイン・タブーエレメンタル」
  は誤記(19bで8箇所を修正)。現在ソース中の出現は0件で、残っているのは
  「誤記である」と説明している文書だけである。
- **★HTMLテンプレートを書き換えるときは、対応するJS描画関数が「入れ物への追記」を
  前提にしているか「入れ物ごと使う」ことを前提にしているかを、関数の中身を読んで
  確認してから書く。**(19b hotfix1の実例参照)
- **★ドラッグ&ドロップの検証に合成 `DragEvent` を使ってはならない。**
  必ず `page.mouse.down/move/up` による実操作で検証する。
- **★小さいドロップ対象へ実マウスでドラッグする検証は、補間ステップを粗くすると
  着地点を飛び越えて `dragover`/`drop` 自体が発生しないことがある(20a)。**
  30ステップ程度に細かくし、最終位置到達後に短い待機を挟んでから `mouse.up()` する。
- **★観測結果を環境の制約だと決めつけないこと。**「設計書に書いてある」ことと
  「ブラウザの実際の挙動」は別物であり、実マウス検証の結果が優先する。
- **★検証ハーネスは `verify/` にリポジトリ管理してある(20bで新設)。**
  `python3 verify/build_harness.py && node verify/verify.js` で、実ファイルの
  `battle.css` / `manual-battle.js` を読み込んだ実マウス検証が33項目走る。
  外部CDN(Bootstrap / StompJs)はネットワーク制限で読めないため、Bootstrapは
  `.d-none` 等を最小限のスタイルで代替し、StompJs は送信内容を `window.__sent` へ
  捕捉するスタブに差し替えている。**新しいUIを足したらここに検証項目を追加すること。**

---

## 4. デリバリー形式

zip(変更・新規ファイルのみ。削除したファイルはzipに含めず、パスを明記して手元での削除を
依頼する)+ `batchNN-design-notes.md`(チートシート/設計根拠★/検証手順/理解確認Q&A/
次バッチ予告)+ `qte-handoff.md` 更新。

マシンチェックスクリプト群(`tools/check_all.py`, `tools/check_records.py`,
`tools/check_undeclared.py`, `tools/check_structure.py`)をパッケージング前に必ず実行する。
**DOM構造・ドラッグ&ドロップが絡む変更は `verify/` の実マウス検証を通すこと
(合成DragEventは使わない)。**

---

## 5. チャット開始テンプレート

次の作業(フェーズ2 UI詰め、または Ver.0.4対応)に応じて、テンプレートの3行目以降を
書き換えて使うこと。20bの確認項目は次のチャットで一度だけ照合すればよい。

```
QTE Battle の開発を継続する。以下の手順で作業を始めてほしい。

1. プロジェクトナレッジ内の `qte-project-reference.md` を読む
   (ゲームルール・設計判断の唯一の正)。
2. プロジェクトナレッジ内の `qte-handoff-v22.md` を読む
   (直近の状態・次の作業・既知の落とし穴)。
3. [フェーズ2 UI詰めなら] `batch16-manual-mode-design-v2_4.md` 11章 +
   `notes/batch20b-ui-design.md` 6-1 + `notes/batch20b-design-notes.md` +
   `notes/batch20c-design-notes.md` を読む。
   [Ver.0.4対応 15cなら] `notes/ver0.4-transcription-notes.md` +
   `notes/batch15a-design-notes.md` + `notes/batch15b-design-notes.md` を読む。
4. ソースコードを取得する(zipのアップロードは不要)。
   ★codeload の zip URL は 403 で落ちないことがある。その場合は git clone を使う。

git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git

5. ★取得したコードで Batch 20b と 20c が反映されているかを確認し、結果を報告すること。
   「反映済み」という記述を信じないこと。「20b/20cの確認項目」(本ファイル0章)を
   実際に読んで照合する。

判断に迷う点や確認したいことはまとめて質問すること。1つずつ聞かない。
1バッチ1チャットの原則を守り、中断しないこと。
ドラッグ&ドロップが絡む変更を検証する場合は、必ず実マウス操作(page.mouse)で行うこと
(合成DragEventは使わない)。`verify/` に検証ハーネスが用意してある。

呼び方は「クロエ」、こちらの呼び方は「マスター」で。会話は日本語カジュアル体でお願い。
ドキュメント類は通常文体(である調)で書くこと。
```

### 前提ドキュメントの選び方

| 次のバッチ | 前提ドキュメント |
|---|---|
| フェーズ2 UI詰め | `batch16-manual-mode-design-v2_4.md` 11章 + `notes/batch20b-ui-design.md` 6-1 |
| 15c / 15d | `ver0.4-transcription-notes.md` + `batch15a-design-notes.md` + `batch15b-design-notes.md` |
| 15e(基盤) | `batch15b-design-notes.md` 6章 + `batch15a-design-notes.md` |
| 整合チェック | 全文明の設計解説 + `qte-cards.json` |

---

## 6. この先の予定

手動モードのフェーズ1(一人回し)は完成し、19b・20a・20bで盤面と操作性を整えた。
20bで導入した共有ゾーン(PLAY / REVEAL)と `PRIVATE` は、そのままフェーズ2の
公開範囲設計の土台になる。次はフェーズ2(ソロ対戦・対戦・観戦)のUI詰めセッション、
または Ver.0.4 対応(15c以降)のどちらかへ進む。

**2系統はファイルが重ならない。** どちらを先に進めてもよいが、混ぜないこと。

---

## 7. 20b / 20c 完了時点の積み残し

- **`mvn test` が未実行である**(サンドボックスから Maven Central へ到達できないため)。
  追加した8件のテストを含め、手元で必ず走らせること。
- `ManualRoomManager#removeRoom` の呼び出し元が無い(19a からの持ち越し)。
- `PRIVATE` の公開範囲(相手には枚数のみ)は未実装。ゾーンフィルタの責務であり、
  フェーズ2で扱う。
- 拡大画像パネルは 204px 幅のまま(右列は 400px)。もう少し大きくできる。
- 超ワイド画面(1920以上)では、相手上段のチップ列(左端)とリーダー(右端)が大きく離れる。
  左寄せに変える選択肢がある(20c 5章)。
- 超ワイド画面ではミニオン行が上限で頭打ちになり、中央列に余りが出る(1920で約240px)。
  中央寄せにして意図した配置に見えるようにしてあるが、詰めるなら次バッチで。
