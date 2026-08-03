# QTE 対戦アプリ — 引き継ぎ書

最終更新: 2026-08-04 (Batch 17a 完了。手動モードのカードデータ基盤235枚)
次の作業: **Batch 17b(a系・Opus + 拡張思考) — 手動モードの状態モデル・部屋・在室者・WebSocket 経路・デッキ取り込み**

**★新しいチャットを始めるときは 5章「チャット開始テンプレート」を使うこと。**

このファイルは、新しいチャットで作業を再開するための最小限の情報をまとめたものである。
ゲームルール・設計判断の詳細は `qte-project-reference.md`、カードの詳細は `qte-cards.json`。

系統が2つ並行している。**混ぜないこと。**

| 系統 | 前提ドキュメント |
|---|---|
| **手動モード**(17a〜19a) | `batch16-manual-mode-design-v2_2.md`(唯一の正) + `notes/batch17a-design-notes.md` |
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
   台帳だけでなく**コードにも適用すること**。15a では台帳が push されていない事故が起き、
   16 と 17a では **15b の Java 4ファイルが push されていない**ことを2回続けて検出している。
5. この環境では **Maven ビルドができない**(Maven Central が到達不可)。納品前の検証は `tools/` の
   機械チェックで行い、型エラーは発注者の手元のビルドで拾う。

---

## 1. 現在の状態

| 項目 | 状態 |
|---|---|
| 完了バッチ | Batch 0〜13c + Ver.0.4転記 + 15a + 15b + 16(設計) + **17a** |
| **★リポジトリの状態** | **15b の Java 4ファイルが未 push。台帳とそれ以外は最新** |
| 台帳の状態 | Ver.0.4反映済み(52枚更新)。2つの台帳は内容一致・確認待ち0件 |
| 効果の実装状況 | 15a で5枚 + 15b で16枚 = 21枚完了。残り31枚(うちL004は基盤待ち) |
| 実装済み文明 | 水28 / 火28 / 闇28 / 光28 / 風28 / 土28 = **168枚(全6文明完成)** |
| 転記済み総数 | 169枚(6文明×28枚 + 文明なし1) |
| **手動モードのカード** | **235枚**(CSV 234 + ピュア・エレメント1)。`manual-cards.json` |
| 公開URL | https://qte-battle-batch0.onrender.com/ |
| 静的ファイルのバージョン | `battle.js(v=13)` / `battle.css(v=10)`(**17a は静的ファイル無変更**)<br>`deck-builder.js(v=10)` / `deck-builder.css(v=10)` |
| 選択可能なリーダー | 全6文明12体(通常モード) |

### 直近の内容(前チャット: Batch 17a — 手動モードのカードデータ基盤)

**既存ファイルを1行も変更していない。すべて新規ファイルである。**
詳細は `notes/batch17a-design-notes.md` を参照。

- CSV 6本(Shift_JIS・234行・9列)から `src/main/resources/cards/manual-cards.json` を生成した。
  **種別内訳は設計書1-2の表と完全一致。突合の食い違いは0件。**
  LEADER 18 / MINION 119 / EVOLUTION 18 / SPELL 61(60 + ピュア・エレメント) / WEAPON 19 = 235。
- **変換は1回きりのオフライン処理**であり、生成物をコミットする(`tools/convert_manual_cards.py`)。
  実行時に Shift_JIS を読まない(Docker のロケール差で本番でだけ壊れる形を避ける)。
- `com.example.qte.manual` に `ManualCardMaster` / `ManualCardRepository` /
  `ManualCardType` / `ManualCivilization` を新設した。
- 確認画面 `/manual/cards` を追加した(目視確認専用。作り込んでいない)。

### ★★ 17a から送った作業

| 項目 | 送り先 |
|---|---|
| **ピュア・エレメントの画像IDの目視確認** | 発注者。`/manual/cards` の「なし文明」を見る。<br>誤っていれば `tools/convert_manual_cards.py` の `PURE_ELEMENT` を直して再変換 |
| **Batch 15b の push** | 発注者。15c 着手前に必須 |

### ★既知の限界(要確認・未対応)

- **★`CardMasterLoadTest.台帳の全カードが読み込まれる()` が赤い。**
  `hasSize(72)` に対して台帳は169枚である。Batch 3 頃の値のまま更新されていない。
  17a は既存ファイル変更禁止のため触っていない。**次に `src/test` を触るバッチで直すこと。**
- **`tools/check_all.py` の項目3 は手動モードIDを偽陽性にする。**
  Java 中の `"QTE-…"` を台帳の実在IDと照合するため、`"QTE-M-NONE-01"` を書くと
  `QTE-M` が「台帳に無いID」として報告される。
  **手動モードのカードは文明・種別など性質で引き、IDを Java にリテラルで書かないこと。**
  書く必要が出たら先に除外規則を入れる。
- **`tools/check_records.py` の `TARGETS` が固定リスト**であり、新規 record を見ない。
  手動モードの record が増える 17b の後にまとめて追加するのが効率的である。
- **★画像IDは不透明なキーである。** 設計書1-6 は「ファイル名 = 画像ファイルの SHA256」と
  述べているが、**244枚のうち7枚で成立しない**(PNG の CRC は全枚数正常で、画像は健全)。
  `sha256(file) == filename` を検査に使ってはならない。
  確かめるのは「その名前のファイルが存在するか」だけである。
- **カードIDに CSV の行番号を使っている。** CSV の行を挿入・削除すると以降のIDがずれる。
  **カードは末尾に足すこと。**
- 裏面画像 `75ee790b…` のみ 1063×1480(表面は 500×700)。CSS で吸収する方針。
- CSV に対応しない余剰画像が8枚ある。旧版であり、配信されないため削除不要。

---

## 2. 次の作業の候補(優先順位順)

### 手動モード(フェーズ1 = 一人回し)

| バッチ | 種別 | 範囲 | 既存変更 | 状態 |
|---|---|---|---|---|
| ~~17a~~ | a系 | データ変換・カードマスタ・確認画面 | なし | **完了** |
| **17b** | **a系** | **状態モデル・部屋・在室者・個別宛先配信・デッキ取り込み** | **なし** | **次** |
| 18a | a系 | 操作の実装(設計書5-3の13項目)・スタック分解・Undo | なし | 未着手 |
| 18b | b系 | 盤面の画面(タイル・手札・拡大画像・操作規約) | `battle.css` | 未着手 |
| 18c | b系 | ゾーンを開く画面(帯・全面・検索・スタック帯) | なし | 未着手 |
| 19a | b系 | 入口の作り替え・リセット・ログ書き出し・切断復帰・仕上げ | `LobbyController` | 未着手 |

**全バッチを通じて既存ファイルの変更は `LobbyController` と `battle.css` の2つだけである。**

### Ver.0.4 対応(手動モードとファイルが重ならない)

1. **★Batch 15b の成果物を GitHub に push する。15c 着手前に必須。**
2. **Batch 15c / 15d(Sonnet 5)。** Ver.0.4 の残り30枚の効果を文明ごとに反映する。

   | バッチ | 文明 | 枚数 | 対象ID | 状態 |
   |---|---|---|---|---|
   | 15c | 土 + 風 | 15 | 0137 0147 0139 0148 0143 0009 0008 / 0133 0117 0118 0135 0134 0120 0136 0131 | 未着手 |
   | 15d | 光 + 闇 | 15 | 0018 0106 0091 0014 / 0088 0072 0085 0080 0068 0084 0069 0071 0070 0081 0006 | 未着手 |
   | 15e | 基盤(Opus) | 1+ | L004(15bから送った) | 未着手 |

   **★「スタッツのみの変更だからコード作業は不要」と一覧の見た目で判断しないこと。**
   **1枚ずつ `grep -rn "QTE-XXXX" --include=*.java` で照合してから可否を判断する。**
3. Batch 15e(基盤設計、Opus・拡張思考)。剛火の将(L004)の盤面参照型の体力修正。
4. 全6文明168枚の整合チェック(Fable 5・拡張思考)。15c〜15e の完了後。
5. ウェポンの寿命のUI表示 / `AutoChoice` の割り込み選択への移行 /
   ウェポン攻撃時効果7件の `CardEffectRegistry` への移設 /
   突風のまとめ役・暴風の双剣の自己バフ問題 / リーダーへの戦闘ダメージがトリガーを通らない件。

---

## 3. 作業のルール

### モデルと工数

| 作業 | モデル | 拡張思考 |
|---|---|---|
| 転記・台帳更新 | Sonnet 5 | 不要 |
| b系バッチ(カード登録・画面) | Sonnet 5 | 不要 |
| a系バッチ(基盤設計・実装) | Opus | 必要 |
| 全文明の整合チェック | Fable 5 | 必要 |

**1バッチ = 1チャット。** 中断・再開しない。

**★b系バッチで基盤の新設が必要だと判明した項目は、その場で実装しない。** 飛ばして記録し、
バッチの最後にまとめて報告する。**バッチは飛ばした分を報告して完了させる**ため、
これは「中断禁止」と衝突しない。

### 納品の形式

1. 実装 → 2. 機械チェック → 3. zip化 → 4. 設計解説 `notes/batchNN-design-notes.md`
→ 5. 引き継ぎ書の更新

設計解説の構成: ⚡結論チートシート → 本文(★重要度マーカー) → ✅検証手順 →
✅理解確認(details/summaryで答えを隠す) → 次バッチ予告。
文体は「である調」の技術文書(会話文の口調は持ち込まない)。

### 納品前の機械チェック(必須)

```bash
python3 tools/check_structure.py src/main/java                       # ★最優先(構造の破壊)
python3 tools/check_all.py .                                         # 項目 1・3・5・6
python3 tools/check_records.py src/main/java                         # 項目 4
python3 tools/check_undeclared.py src/main/resources/static/js/*.js  # 項目 8
node --check src/main/resources/static/js/battle.js                  # 項目 7
```

**ブレース・括弧の同数チェックは同数削除で通過してしまう。**
`check_structure.py` の「クラス内メソッド呼び出しの宣言存在確認」が必須である。

**`check_records.py` の★3件(`GameActions.java:775` / `GameService.java:1144` /
`CardEffectRegistry.java:706`)は既知の偽陽性である**(スクリプト自身が注記している)。
新しく★が出たら必ず該当行を目視すること。

### 手動モードのカードデータを触るとき

```bash
python3 tools/convert_manual_cards.py .
```

- **`src/main/resources/cards/manual-cards.json` を手で編集しない。** 生成物である。
- 直すときは `tools/csv/*.csv` かスクリプト内の `PURE_ELEMENT` 定数を直して再変換する。
- 報告に★が付いた項目(画像が無いカードなど)は必ず読む。
- CSV は Shift_JIS。`tools/csv/.gitattributes` が `*.csv -text` で改行正規化を止めている。

### コンテキスト効率

grep 優先でファイルを渡り歩き、`view` による全体読み込みは避ける。
チャットの中断は再取得ぶんコンテキストを余計に消費するため避ける。

### 発注者とのやりとり

判断に迷う点・確認したい点は**まとめて質問する。**1つずつ聞かない。
呼び方は「クロエ」、発注者の呼び方は「マスター」。会話は日本語カジュアル体。

---

## 4. 既知の落とし穴

- **★「反映済み」という記述を信じない。** 台帳だけでなくコードも実データで照合する。
  15b の未 push は 16 と 17a で2回続けて検出している。
- **数値はコードに埋まっていることがある。** 台帳が持つのは cost/attack/hp/keywords だけであり、
  「ドロー2枚」「自傷2」のような効果内の数値は `CardEffectRegistry` にある。
  **台帳を直しても効果は古いままになる。**
- **実装済み文明リストの唯一の正は `DeckValidator.IMPLEMENTED`。**
  `LobbyController.selectableLeaders()` と `deck-builder.js` の `IMPLEMENTED_CIVS` は
  `GET /api/implemented-civilizations` を経由して同じ集合を見る。
- **静的ファイルを変更したらキャッシュバスティングのバージョンをインクリメントする。**
  ただし**手動モードのカード画像には不要**である。ファイル名が内容に対応しているため、
  作り直せば URL が変わる。
- **カード登録は card ID が `qte-cards.json` に実在するか必ず照合する。**
- **`ManualCardRepository` の読み込みに失敗するとアプリ全体が起動しない。**
  手動モードだけでなく通常モードも巻き添えになる。
  `manual-cards.json` を消したり壊したりしないこと。
- **手動モードのカードIDを Java にリテラルで書かない**(1章「既知の限界」)。

---

## 5. チャット開始テンプレート

```
QTE Battle の開発を継続する。以下の手順で作業を始めてほしい。

1. プロジェクトナレッジ内の `qte-project-reference.md` を読む
   (ゲームルール・設計判断の唯一の正)。
2. プロジェクトナレッジ内の `qte-handoff-v11.md` を読む
   (直近の状態・次の作業・既知の落とし穴)。
3. プロジェクトナレッジ内の `<今回のバッチの前提ドキュメント>` を読む。
4. ソースコードを取得する(zipのアップロードは不要)。

https://codeload.github.com/okuson-droid/qte-battle-batch0/zip/refs/heads/main

5. **★取得したコードで `<直前のバッチ>` が反映されているかを確認し、結果を報告すること。**
   `<確認すべき具体的な箇所を2〜3個>` を実際に読んで照合する。
   「反映済み」という記述を信じないこと。

次の作業は Batch <NN>(<種別>) で、<やること>。

このバッチでは <やること> のみを行う。<やらないこと> は次のバッチで扱う。

<a系なら: a系バッチのため Opus + 拡張思考で作業してほしい。>
<b系なら: b系バッチのため Sonnet で作業してほしい。基盤の新設が必要だと判明した項目は
その場で実装せず、飛ばして記録し、バッチの最後にまとめて報告すること。>

判断に迷う点や確認したいことはまとめて質問すること。1つずつ聞かない。
1バッチ1チャットの原則を守り、中断しないこと。

呼び方は「クロエ」、こちらの呼び方は「マスター」で。会話は日本語カジュアル体でお願い。
ドキュメント類は通常文体(である調)で書くこと。
```

### 前提ドキュメントの選び方

**直前のバッチの設計解説を必ず含める。** そのバッチが作った仕組みの上に次が載るためである。

| 次のバッチ | 前提ドキュメント |
|---|---|
| **17b** | `batch16-manual-mode-design-v2_2.md` + `notes/batch17a-design-notes.md` |
| 18a | 同上 + `notes/batch17b-design-notes.md` |
| 15c / 15d | `ver0.4-transcription-notes.md` + `batch15a-design-notes.md` + `batch15b-design-notes.md` |
| 15e(基盤) | `batch15b-design-notes.md` 6章 + `batch15a-design-notes.md` |
| 整合チェック | 全文明の設計解説 + `qte-cards.json` |

---

## 6. この先の予定

手動モードを 17b → 18a → 18b → 18c → 19a の順に進め、フェーズ1(一人回し)を完成させる。
フェーズ2(ソロ対戦・対戦・観戦)はその後である。

Ver.0.4 対応は 15b の push を済ませてから 15c・15d・15e を進める。
その後に全6文明168枚の整合チェックを行う。

**2系統はファイルが重ならない。** どちらを先に進めてもよいが、混ぜないこと。

---

## 付録. 主要ファイルのメソッド索引の再生成

**行番号は変わる。着手前に必ず再生成すること。**

```bash
cd src/main/java/com/example/qte
for f in effect/CardEffectRegistry.java game/GameService.java game/GameActions.java \
         effect/RuleGuards.java effect/StatCalculator.java game/view/GameViewBuilder.java \
         game/PlayerState.java game/MinionInstance.java effect/TargetSpec.java; do
  echo "=== $f ==="; grep -n "^    \(public\|private\|protected\).*(" $f | sed 's/ *{$//'
done
grep -n "^function " ../../../../resources/static/js/battle.js | sed 's/(.*//'
```

手動モード側は次で足りる。

```bash
grep -rn "^    \(public\|private\)" src/main/java/com/example/qte/manual/
```
