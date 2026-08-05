# QTE 対戦アプリ — 引き継ぎ書

最終更新: 2026-08-05（Batch 20a 完了。次はフェーズ2 UI詰め、またはVer.0.4対応 15c）
**★手動モード フェーズ1（一人回し）は19aで完成済み。19b・hotfixはUI改善とその修正。
20aは日常操作の不便解消（山札直接移動・裏向き正規化・LP増減UI）。**

**★新しいチャットを始めるときは 5章「チャット開始テンプレート」を使うこと。**

このファイルは、新しいチャットで作業を再開するための最小限の情報をまとめたものである。
ゲームルール・設計判断の詳細は `qte-project-reference.md`、カードの詳細は `qte-cards.json`。

系統が2つ並行している。**混ぜないこと。**

| 系統 | 前提ドキュメント |
|---|---|
| **手動モード**（17a〜20a・フェーズ1完成＋日常操作改善） | `batch16-manual-mode-design-v2_4.md`（唯一の正）+ `notes/batch19b-ui-design.md`（v2.1・唯一の正）+ `notes/batch20a-design.md`（20aの唯一の正）+ `notes/batch20a-design-notes.md` |
| **Ver.0.4 対応**（15c/15d/15e） | `notes/ver0.4-transcription-notes.md` + `notes/batch15a-design-notes.md` + `notes/batch15b-design-notes.md` |

---

## 0. 最初にやること

1. `qte-project-reference.md` を読む（ゲームルール・設計判断の唯一の正）。
   **★このファイルはプロジェクトナレッジ内で2026-07-21時点の記述のまま止まっている
   （3文明実装時点）。実際は6文明完成・Ver.0.4対応中であり、1章の状態記述が古い。
   ゲームルール自体（2章以降）は有効。発注者側での更新を推奨する。**
2. 作業に応じて上表の前提ドキュメントを読む。
3. ソースコードを取得する。**zipのアップロードは不要**。

```
https://codeload.github.com/okuson-droid/qte-battle-batch0/zip/refs/heads/main
```

4. **★「反映済み」という記述を信じず、直近バッチで変更したはずの箇所を実際に読んで照合する。**
   台帳だけでなく**コードにも適用すること**。

## 20aの確認項目（次チャットで照合すること）

- `src/main/java/com/example/qte/manual/ManualOperationService.java` に
  `FACE_UP_ON_ARRIVAL`（FIELD/WEAPON/TRASH/LOST/REVEAL/HAND）と
  `normalizeFaceDown`/`applyFaceDownRule` が存在し、`move()` から呼ばれていること。
- `manual-battle.js` の `createCardPile` のDECK分岐で、`pile.length > 0` のときのみ
  `box.draggable = true` になり、`dragstart` 内で `document.elementFromPoint` を使って
  `.zone-drop-mini, button` の内側から始まった場合は `preventDefault()` していること。
  **★`draggable=false` を子要素に明示する方式ではない**（3-1で訂正済み。効かないため）。
- `createDeckRow` のボタンが10個（一番上へ/一番下へ/手札へ/場へ/墓地へ/消滅へ/禁忌へ/
  一時公開へ/マナ(表)へ/マナ(裏)へ）であること。`sendDeckMove` が `faceDown` 引数を
  取れること。
- `createLeaderTile` のLPクリックが `prompt()` ではなく `openLpModal(seat.id, seat.lp)`
  を呼んでいること。`manual-battle.html` に `#lp-modal` が存在すること。
- `registerDropTarget` の `drop` ハンドラに `e.stopPropagation()` があること
  （20a 3-2で修正した既存不具合。無いと山札パイルへのドロップが2重送信される）。
- `manual-battle.html` が `manual-battle.js(v=6)` / `battle.css(v=14)` を参照している
  こと。
- Javaファイルの変更は `ManualOperationService.java` の1ファイルのみであること
  （19a以降の他のJavaファイル一覧に増減が無いこと）。

---

## 1. 次の作業の候補（優先順位順）

### 手動モード — フェーズ1は完成済み・日常操作の不便も20aで解消

| バッチ | 種別 | 範囲 | 既存変更 | 状態 |
|---|---|---|---|---|
| ~~17a~~〜~~19a~~ | a系/b系 | データ変換〜切断復帰・仕上げ | 各種 | 完了 |
| ~~19b~~〜~~19b hotfix2~~ | b系/修正 | 盤面レイアウト再設計・ドラッグ不具合修正 | `battle.css`/`manual-battle.js`/`manual-battle.html` | 完了 |
| ~~20a~~ | a系 | 山札からの直接移動・裏向きの正規化・数値の増減UI | `ManualOperationService.java`ほか | **完了** |

### ★次の作業はフェーズ2 UI詰め、またはVer.0.4対応のどちらか

**フェーズ2（ソロ対戦・対戦・観戦）— UI詰めセッション**

着手前に UI 詰めのセッションをもう1回行うこと（設計書11章。相手側ゾーンの見せ方、
視点切替の UI、在室者リストの画面、席選択画面などが未設計）。20系バッチとして計画する。

**Ver.0.4 対応（手動モードとファイルが重ならない）**

着手条件は満たされている。15c / 15d（Sonnet 5）、その後15e（基盤・Opus）、
全6文明168枚の整合チェック（Fable 5）の順。詳細は `qte-project-reference.md` 1-5・1-2、
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
  バックドロップ（`.manual-band-backdrop`）がドロップイベントを遮断しており、一度も
  機能していなかった（19bで修正）。「実装した」という記述と動作の実態が食い違った実例
  として記録する。
- **★★ドラッグの「起点から除外する」を `draggable=false` の子要素明示で実現しようと
  してはならない（20a 3-1で訂正）。** HTML5のドラッグは mousedown 位置から**祖先方向へ**
  `draggable=true` を探して実際にドラッグする要素を決める。子要素へ `draggable=false`
  を明示しても、それは「その子要素自身をドラッグ起点にしない」という意味に過ぎず、
  祖先が `draggable=true` であれば結局祖先がドラッグされる。除外を実現するには、
  祖先の `dragstart` ハンドラ内で `document.elementFromPoint(e.clientX, e.clientY)` に
  より実際に掴んだ場所を調べ、除外対象なら `preventDefault()` する（`e.target` では
  ダメ。`dragstart` の `target` は常に祖先＝実際にドラッグされる要素であり、
  実際に指を置いた子要素ではない）。
- **★★`registerDropTarget` で登録した要素を入れ子にすると、`drop` が二重発火する
  （20a 3-2で修正）。** 内側の要素で `drop` を処理しても、`stopPropagation()` が
  無ければ祖先の `drop` ハンドラまでイベントが伝播し、1回の操作で `move` 等が2回
  送信される。現在は `registerDropTarget` の `drop` ハンドラ先頭で
  `e.stopPropagation()` を行っている。将来、入れ子のドロップ対象を新設する場合は
  この前提（内側だけが処理する）を踏まえること。
- **数値はコードに埋まっていることがある。** 台帳が持つのは cost/attack/hp/keywords だけ。
- **実装済み文明リストの唯一の正は `DeckValidator.IMPLEMENTED`。** 手動モードはこの判定を
  一切使わない。
- **静的ファイルを変更したらキャッシュバスティングのバージョンをインクリメントする。**
  20aでは `manual-battle.js`（v=5→v=6）と `battle.css`（v=13→v=14）の両方を上げた。
- **カード登録は card ID が `qte-cards.json` に実在するか必ず照合する。**
- **手動モードのカードIDを Java にリテラルで書かない。**
- **★テストコードは機械チェックの網の外にある。** `src/test` の型エラー・未宣言メソッドは
  発注者の手元でしか出ない。`tools/check_structure.py` は `*Test.java` のJUnitメソッド名
  （日本語）を「未解決のメソッド呼び出し」と誤検出する既知のノイズがある。無視してよい。
- **★手動モードで「判断」を実装しない。** アプリが担うのは「同じ盤面を見ていることの保証」
  だけである（設計書5-1）。コスト支払い・戦闘解決・攻撃可否・フェイズ強制・勝敗判定・
  デッキ切れはすべて切る。20aの裏向き正規化は「表示状態の正規化」であり裁定ではない
  （設計書D2）。
- **★MPを直接増減する操作を作らない。** マナのアンタップ枚数からの派生値である。
- **★`battle.css` は通常モード（`battle.html`）と手動モード（`manual-battle.html`）の
  共有ファイルである。** `.leader-card` / `.minion-row` / `.mana-chip` / `.mana-row` は
  通常モードも使っているため、手動モード側のサイズ変更は必ず手動モード専用クラス
  （`.manual-leader-tile` 等）への追加で行い、共有クラスの定義自体は変えないこと。
- **★occupantId はサーバがページ生成時に発行しない（19a以降）。** クライアントの
  localStorage（キー `qte-manual-occupant-{roomId}`）が保持する。
- **★手動モードの部屋は自動的には消えない。** `ManualRoomManager#removeRoom` は
  用意されているが呼び出し元が無い（19a 積み残し）。長時間運用ではメモリに残り続ける。
- **★切断復帰の猶予は5分固定（`ManualCleanupScheduler.GRACE_PERIOD`）。**
- **★ゲームの正式名称は「クイン・タブーエレメント」である。**「クイン・タブーエレメンタル」
  は誤記（19bで8箇所を修正）。
- **★HTMLテンプレートを書き換えるときは、対応するJS描画関数が「入れ物への追記」を
  前提にしているか「入れ物ごと使う」ことを前提にしているかを、関数の中身を読んで
  確認してから書く。**（19b hotfix1の実例参照）
- **★ドラッグ&ドロップの検証に合成 `DragEvent`（`new DragEvent` + `dispatchEvent`）を
  使ってはならない。** 合成イベントはブラウザ本来のドラッグ機構（祖先探索によるsource
  node決定・中断判定・イベント伝播）を一切通らないため、この種の不具合を原理的に
  再現できない。必ず `page.mouse.down/move/up` による実操作で検証する
  （19b hotfix2・20a 3-1/3-2はいずれもこれを怠っていたら見落としていた）。
- **★小さいドロップ対象（20×19px程度）へ実マウスでドラッグする検証は、補間ステップを
  粗くすると着地点を飛び越えて `dragover`/`drop` 自体が発生しないことがある（20a
  検証時に判明）。** 30ステップ程度に細かくし、最終位置到達後に短い待機を挟んでから
  `mouse.up()` すると安定する。
- **★観測結果を環境の制約だと決めつけないこと。** 19bの時点で `dragstart -> dragend` という
  中断の証拠は出ていたが、ヘッドレス環境の限界と解釈して合成イベントに逃げたため、
  2回の納品を無駄にした。20aでもA4の想定外れを実マウスで2回訂正している。
  「設計書に書いてある」ことと「ブラウザの実際の挙動」は別物であり、実マウス検証の
  結果が優先する。
- **★この環境にはPlaywright（Chromiumヘッドレス）が用意されており、実ファイル
  （`battle.css`/`manual-battle.js`）を読み込んだ検証用HTMLを作って、`StompJs` を
  スタブし送信内容を捕捉したうえで実マウス操作の一連の流れを検証できる。** 20aでは
  `client.publish` をスタブして送信された `move`/`lp`/`shuffle` 等のメッセージ内容を
  直接検証した。外部CDN（Bootstrap等）はこのサンドボックスのネットワーク制限で
  読み込めないため、検証用HTMLではCDN依存箇所をスタブするか影響のない形に調整する
  必要がある。

---

## 4. デリバリー形式

zip（変更・新規ファイルのみ。削除したファイルはzipに含めず、パスを明記して手元での削除を
依頼する）+ `batchNN-design-notes.md`（チートシート/設計根拠★/検証手順/理解確認Q&A/
次バッチ予告）+ `qte-handoff.md` 更新。

マシンチェックスクリプト群（`tools/check_all.py`, `tools/check_records.py`,
`tools/check_undeclared.py`, `tools/check_structure.py`）をパッケージング前に必ず実行する。
**DOM構造・ドラッグ&ドロップが絡む変更は、Playwright（Chromiumヘッドレス。このサンドボックス
に用意済み）で実ファイルを読み込んだ検証用HTMLを作り、`page.mouse` による実マウス操作で
確認すること（合成DragEventは使わない。19b hotfix2・20a 3-1/3-2参照）。**

---

## 5. チャット開始テンプレート

次の作業（フェーズ2 UI詰め、または Ver.0.4対応）に応じて、テンプレートの3行目以降を
書き換えて使うこと。20aの確認項目は次のチャットで一度だけ照合すればよい。

```
QTE Battle の開発を継続する。以下の手順で作業を始めてほしい。

1. プロジェクトナレッジ内の `qte-project-reference.md` を読む
   （ゲームルール・設計判断の唯一の正）。
2. プロジェクトナレッジ内の `qte-handoff-v21.md` を読む
   （直近の状態・次の作業・既知の落とし穴）。
3. [フェーズ2 UI詰めなら] `batch16-manual-mode-design-v2_4.md` 11章 +
   `notes/batch19a-design-notes.md` を読む。
   [Ver.0.4対応 15cなら] `notes/ver0.4-transcription-notes.md` +
   `notes/batch15a-design-notes.md` + `notes/batch15b-design-notes.md` を読む。
4. ソースコードを取得する（zipのアップロードは不要）。

https://codeload.github.com/okuson-droid/qte-battle-batch0/zip/refs/heads/main

5. ★取得したコードで Batch 20a が反映されているかを確認し、結果を報告すること。
   「反映済み」という記述を信じないこと。「20aの確認項目」（本ファイル0章）を
   実際に読んで照合する。

判断に迷う点や確認したいことはまとめて質問すること。1つずつ聞かない。
1バッチ1チャットの原則を守り、中断しないこと。
ドラッグ&ドロップが絡む変更を検証する場合は、必ず実マウス操作（page.mouse）で行うこと
（合成DragEventは使わない）。

呼び方は「クロエ」、こちらの呼び方は「マスター」で。会話は日本語カジュアル体でお願い。
ドキュメント類は通常文体（である調）で書くこと。
```

### 前提ドキュメントの選び方

| 次のバッチ | 前提ドキュメント |
|---|---|
| フェーズ2 UI詰め | `batch16-manual-mode-design-v2_4.md` 11章 + `notes/batch19a-design-notes.md` |
| 15c / 15d | `ver0.4-transcription-notes.md` + `batch15a-design-notes.md` + `batch15b-design-notes.md` |
| 15e（基盤） | `batch15b-design-notes.md` 6章 + `batch15a-design-notes.md` |
| 整合チェック | 全文明の設計解説 + `qte-cards.json` |

---

## 6. この先の予定

手動モードのフェーズ1（一人回し）は完成し、19bでUI改善、20aで日常操作の不便
（山札直接移動・裏向き正規化・LP増減UI）も解消した。次はフェーズ2（ソロ対戦・対戦・
観戦）のUI詰めセッション、または Ver.0.4 対応（15c以降）のどちらかへ進む。

**2系統はファイルが重ならない。** どちらを先に進めてもよいが、混ぜないこと。
