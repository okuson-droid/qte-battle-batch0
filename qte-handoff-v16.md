# QTE 対戦アプリ — 引き継ぎ書

最終更新: 2026-08-04(Batch 19a 完了。入口の作り替え・リセット・ログ書き出し・切断復帰・仕上げ)
**★手動モード フェーズ1(一人回し)はこのバッチで完成した。**

**★新しいチャットを始めるときは 5章「チャット開始テンプレート」を使うこと。**

このファイルは、新しいチャットで作業を再開するための最小限の情報をまとめたものである。
ゲームルール・設計判断の詳細は `qte-project-reference.md`、カードの詳細は `qte-cards.json`。

系統が2つ並行している。**混ぜないこと。**

| 系統 | 前提ドキュメント |
|---|---|
| **手動モード**(17a〜19a・完成) | `batch16-manual-mode-design-v2_4.md`(唯一の正) + `notes/batch19a-design-notes.md` |
| **Ver.0.4 対応**(15c/15d/15e) | `notes/ver0.4-transcription-notes.md` + `notes/batch15a-design-notes.md` + `notes/batch15b-design-notes.md` |

---

## 0. 最初にやること

1. `qte-project-reference.md` を読む(ゲームルール・設計判断の唯一の正)。
2. 作業に応じて上表の前提ドキュメントを読む。
3. ソースコードを取得する。**zipのアップロードは不要**。

```
https://codeload.github.com/okuson-droid/qte-battle-batch0/zip/refs/heads/main
```

4. **★「反映済み」という記述を信じず、直近バッチで変更したはずの箇所を実際に読んで照合する。**
   台帳だけでなく**コードにも適用すること**。

## 19aの確認項目(次チャットで照合すること)

- `src/main/java/com/example/qte/manual/web/ManualBattleController.java` と
  `ManualOpsWsController.java`、`src/main/resources/templates/manual-deck-check.html` が
  **存在しないこと**(19aで削除した3ファイル。zipには含めず、納品時に手元での削除を依頼した)。
- `src/main/java/com/example/qte/manual/web/ManualWsController.java` に
  `leave` / `reset` の `@MessageMapping` が両方あること。
- `src/main/java/com/example/qte/web/LobbyController.java` の `@GetMapping("/")` が
  `"manual-lobby"` を返し、`@GetMapping("/auto")` が既存の `lobby` メソッドであること。
- `src/main/resources/templates/manual-lobby.html` が存在すること。
- `src/main/resources/static/js/manual-battle.js` に `resolveOccupant` 関数があり、
  `OCCUPANT_ID` がテンプレートからではなく **`let` で宣言され非同期に埋まる**形に
  なっていること(サーバ発行のoccupantIdをテンプレートへ埋め込む古い方式に戻っていないか)。
- `manual-battle.html` が `manual-battle.js(v=3)` を参照していること(v=2 のままではないか)。
- `QteBattleApplication.java` に `@EnableScheduling` があること。

---

## 1. 次の作業の候補(優先順位順)

### 手動モード(フェーズ1 = 一人回し)— ★完成

| バッチ | 種別 | 範囲 | 既存変更 | 状態 |
|---|---|---|---|---|
| ~~17a~~ | a系 | データ変換・カードマスタ・確認画面 | なし | 完了 |
| ~~17b~~ | a系 | 状態モデル・部屋・在室者・個別宛先配信・デッキ取り込み | なし | 完了 |
| ~~18a~~ | a系 | 操作13項目・進化スタックの分解・Undo | なし | 完了 |
| ~~18b~~ | b系 | 盤面の画面(タイル・手札・拡大画像・操作規約) | `battle.css` | 完了 |
| ~~18c~~ | b系 | ゾーンを開く画面(帯・全面・検索・スタック帯) | `battle.css` | 完了 |
| ~~19a~~ | b系 | 入口の作り替え・リセット・ログ書き出し・切断復帰・仕上げ | `LobbyController` 他 | **完了** |

### フェーズ2(ソロ対戦・対戦・観戦)— 次の候補

**着手前に UI 詰めのセッションをもう1回行うこと**(設計書11章。相手側ゾーンの見せ方、
視点切替の UI、在室者リストの画面、席選択画面などが未設計)。20系バッチとして計画する。

### Ver.0.4 対応(手動モードとファイルが重ならない)

**★着手条件は満たされている。** 15c / 15d(Sonnet 5)、その後15e(基盤・Opus)、
全6文明168枚の整合チェック(Fable 5)の順。詳細は `qte-project-reference.md` 1-5・1-2、
`notes/batch15b-design-notes.md` 6章を参照。

**この2つ(フェーズ2 UI詰め / Ver.0.4対応)はどちらを先に進めてもよい。**

---

## 2. コンテキスト効率・発注者とのやりとり

grep 優先でファイルを渡り歩き、`view` による全体読み込みは避ける。
チャットの中断は再取得ぶんコンテキストを余計に消費するため避ける。

判断に迷う点・確認したい点は**まとめて質問する。**1つずつ聞かない。
呼び方は「クロエ」、発注者の呼び方は「マスター」。会話は日本語カジュアル体。

---

## 3. 既知の落とし穴

- **★「反映済み」という記述を信じない。** 台帳だけでなくコードも実データで照合する。
- **数値はコードに埋まっていることがある。** 台帳が持つのは cost/attack/hp/keywords だけ。
- **実装済み文明リストの唯一の正は `DeckValidator.IMPLEMENTED`。** 手動モードはこの判定を
  一切使わない。
- **静的ファイルを変更したらキャッシュバスティングのバージョンをインクリメントする。**
  ただし手動モードのカード画像には不要。19aでは `manual-battle.js` のみ v=2→v=3。
  `battle.css` は無変更のため v=12 のまま。
- **カード登録は card ID が `qte-cards.json` に実在するか必ず照合する。**
- **手動モードのカードIDを Java にリテラルで書かない。**
- **★テストコードは機械チェックの網の外にある。** `src/test` の型エラー・未宣言メソッドは
  発注者の手元でしか出ない。
- **★手動モードで「判断」を実装しない。** アプリが担うのは「同じ盤面を見ていることの保証」
  だけである(設計書5-1)。コスト支払い・戦闘解決・攻撃可否・フェイズ強制・勝敗判定・
  デッキ切れはすべて切る。
- **★MPを直接増減する操作を作らない。** マナのアンタップ枚数からの派生値である。
- **★帯・全面表示からのドラッグは、instanceId さえ渡せばサーバの `move` が
  ゾーン直下でも進化スタックの素材でも透過的に見つけてくれる。** クライアント側で
  「これは素材だから」という特別扱いを増やさないこと(18c 2-6参照)。
- **★occupantId はもうサーバがページ生成時に発行しない(19a)。** クライアントの
  localStorage(キー `qte-manual-occupant-{roomId}`)が保持する。テンプレートへ
  `${occupantId}` を埋め込む古い方式に戻さないこと。
- **★手動モードの部屋は自動的には消えない。** `ManualRoomManager#removeRoom` は
  用意されているが呼び出し元が無い(19a 積み残し3章)。長時間運用ではメモリに残り続ける。
- **★切断復帰の猶予は5分固定(`ManualCleanupScheduler.GRACE_PERIOD`)。** 変更する場合は
  設計書6-3の裁定を確認してから。

---

## 4. デリバリー形式

zip(変更・新規ファイルのみ。削除したファイルはzipに含めず、パスを明記して手元での削除を依頼する)
+ `batchNN-design-notes.md`(チートシート/設計根拠★/検証手順/理解確認Q&A/次バッチ予告)
+ `qte-handoff.md` 更新。

マシンチェックスクリプト群(`tools/check_all.py`, `tools/check_records.py`,
`tools/check_undeclared.py`, `tools/check_structure.py`)をパッケージング前に必ず実行する。
19a はこれに加えて `node --check` を全JSファイルへ実行した(mvn が使えない環境だったため、
javac によるコンパイル確認の代わりに機械チェック全種を厳密に通した)。

---

## 5. チャット開始テンプレート

次の作業(フェーズ2 UI詰め、または Ver.0.4対応)に応じて、テンプレートの3行目以降を
書き換えて使うこと。手動モードのフェーズ1は完成したため、19a向けの確認項目は次のチャット
以降は不要(このファイルの「19aの確認項目」は次チャットで一度だけ照合すればよい)。

```
QTE Battle の開発を継続する。以下の手順で作業を始めてほしい。

1. プロジェクトナレッジ内の `qte-project-reference.md` を読む
   (ゲームルール・設計判断の唯一の正)。
2. プロジェクトナレッジ内の `qte-handoff-v16.md` を読む
   (直近の状態・次の作業・既知の落とし穴)。
3. ソースコードを取得する(zipのアップロードは不要)。

https://codeload.github.com/okuson-droid/qte-battle-batch0/zip/refs/heads/main

4. ★取得したコードで Batch 19a が反映されているかを確認し、結果を報告すること。
   「反映済み」という記述を信じないこと。「19aの確認項目」(本ファイル0章)を実際に読んで照合する。
   特に、削除したはずの3ファイル(ManualBattleController.java・ManualOpsWsController.java・
   manual-deck-check.html)が本当に存在しないかを確認すること。

次の作業は [フェーズ2のUI詰めセッション / Ver.0.4対応 15c] のどちらか(要相談)。

判断に迷う点や確認したいことはまとめて質問すること。1つずつ聞かない。
1バッチ1チャットの原則を守り、中断しないこと。

呼び方は「クロエ」、こちらの呼び方は「マスター」で。会話は日本語カジュアル体でお願い。
ドキュメント類は通常文体(である調)で書くこと。
```

### 前提ドキュメントの選び方

| 次のバッチ | 前提ドキュメント |
|---|---|
| フェーズ2 UI詰め | `batch16-manual-mode-design-v2_4.md` 11章 + `notes/batch19a-design-notes.md` |
| 15c / 15d | `ver0.4-transcription-notes.md` + `batch15a-design-notes.md` + `batch15b-design-notes.md` |
| 15e(基盤) | `batch15b-design-notes.md` 6章 + `batch15a-design-notes.md` |
| 整合チェック | 全文明の設計解説 + `qte-cards.json` |

---

## 6. この先の予定

手動モードのフェーズ1(一人回し)は完成した。次はフェーズ2(ソロ対戦・対戦・観戦)の
UI詰めセッション、または Ver.0.4 対応(15c以降)のどちらかへ進む。

**2系統はファイルが重ならない。** どちらを先に進めてもよいが、混ぜないこと。
