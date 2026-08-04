# QTE 対戦アプリ — 引き継ぎ書

最終更新: 2026-08-04 (Batch 18a 完了。手動モードの操作13項目・進化スタックの分解・Undo)
次の作業: **Batch 18b(b系・Sonnet 5) — 手動モードの盤面の画面(タイル・手札・拡大画像・操作規約)**

**★新しいチャットを始めるときは 5章「チャット開始テンプレート」を使うこと。**

このファイルは、新しいチャットで作業を再開するための最小限の情報をまとめたものである。
ゲームルール・設計判断の詳細は `qte-project-reference.md`、カードの詳細は `qte-cards.json`。

系統が2つ並行している。**混ぜないこと。**

| 系統 | 前提ドキュメント |
|---|---|
| **手動モード**(17a〜19a) | `batch16-manual-mode-design-v2_2.md`(唯一の正) + `notes/batch18a-design-notes.md` |
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
   16・17a・17b では 15b の Java 4ファイルが push されていないことを3回続けて検出したが、
   **18a の時点で 15b は反映されている**(1章)。
5. この環境では **Maven ビルドができない**(Maven Central が到達不可)。納品前の検証は `tools/` の
   機械チェックで行い、型エラーは発注者の手元のビルドで拾う。
   **★ただし 18a では別の手段で型検査と実行検証まで通せた。**
   `apt` から JDK を入れ、既存クラスの公開APIを写したスタブと Spring / JUnit / AssertJ の
   最小スタブを手で書いて `javac` を通す。手順は `notes/batch18a-design-notes.md` 3-2 にある。
   **手間はかかるが、a系バッチでは費用対効果が高い。**

---

## 1. 現在の状態

| 項目 | 状態 |
|---|---|
| 完了バッチ | Batch 0〜13c + Ver.0.4転記 + 15a + 15b + 16(設計) + 17a + 17b + **18a** |
| **★リポジトリの状態** | **15b を含めてすべて反映済み**(18a で確認)。3回続いた未 push は解消した |
| 台帳の状態 | Ver.0.4反映済み(52枚更新)。2つの台帳は内容一致・確認待ち0件 |
| 効果の実装状況 | 15a で5枚 + 15b で16枚 = 21枚完了。残り31枚(うちL004は基盤待ち) |
| 実装済み文明 | 水28 / 火28 / 闇28 / 光28 / 風28 / 土28 = **168枚(全6文明完成)** |
| 転記済み総数 | 169枚(6文明×28枚 + 文明なし1) |
| 手動モードのカード | 235枚(CSV 234 + ピュア・エレメント1)。`manual-cards.json` |
| **手動モードの基盤** | 状態モデル・部屋・配信・デッキ取り込み(17b)<br>**+ 操作13項目・進化スタック・Undo/Redo まで完成(18a)** |
| 公開URL | https://qte-battle-batch0.onrender.com/ |
| 静的ファイルのバージョン | `battle.js(v=13)` / `battle.css(v=10)`(**17a・17b・18a とも静的ファイル無変更**)<br>`deck-builder.js(v=10)` / `deck-builder.css(v=10)` |
| 選択可能なリーダー | 全6文明12体(通常モード) |

### 直近の内容(前チャット: Batch 18a — 手動モードの操作・進化スタックの分解・Undo)

**既存ファイルを1行も変更していない。8ファイルすべてが新規である。**
詳細は `notes/batch18a-design-notes.md` を参照。

- 操作: `ManualOperationService`(設計書 5-3 の13項目 + 進化 + Undo/Redo)。
  補助として `ManualCardRef` / `ManualBoardIndex` / `ManualLabels` / `ManualDeclaration` /
  `ManualOpRequest`。
- 入口: `manual/web/ManualOpsWsController` に `@MessageMapping` を18本。
- テスト: `ManualOperationTest` 26件。
- **★設計書 5-3 の13項目のうち5項目が `move` 1本に収まった**
  (ゾーン間移動 / ウェポンの装備・解除 / 進化スタックの分解 / マリガン / 素材を場へ戻す)。

### ★★ 18a から送った作業

| 項目 | 送り先 |
|---|---|
| **操作の疎通確認** | 発注者。画面が無いためコンソールから WebSocket を叩く<br>(`notes/batch18a-design-notes.md` 4-4 に手順あり) |
| **`ManualWsController` との統合** | Batch 19a。`execute` 相当が二重になっている |
| **ログの視点フィルタ** | フェイズ2。裏向きカードの名前がログに出る |
| **既定9種の札の配信口** | Batch 18b。`ManualLabels.DEFAULTS` はサーバ定数として置いただけである |
| `GameRoomManager` の `RoomIds` 化 | Batch 19a(17b から継続) |
| `CardMasterLoadTest` の `hasSize(72)` 修正 | 既存ファイル変更が許されるバッチ |

### ★手動モードの設計の要点(18b 以降で必ず守ること)

- **ゾーンは `seat.zone(ManualZone)` で引く。** 全ゾーンが `EnumMap` の1本に入っており、
  移動は `zone(from).remove(i)` → `zone(to).add(j, card)` の2行で書ける。
- **進化スタックは平坦である。** 最上段の `ManualCardInstance` が `materials` を持ち、
  入れ子にしない。`+n` の n は `materialCount()` そのもの。
  **進化は枠数を N → 1 に減らす**。通常のカードプレイに無い性質である。
- **数値は現在値の1軸だけ。** 印刷値は配信のたびに `ManualCardRepository` から引き直す。
  状態に `ManualCardMaster` への参照を持ち込まないこと(スナップショット方式が壊れる)。
- **`ManualHistory.push()` は中で複製する。** 呼び出し側で `copy()` は不要。
- **ログは `ManualRoom` にあり、Undo で巻き戻らない。** 追記専用である。
- **`ManualGameService` は自分でロックを取らない。** 呼び出し側が
  `synchronized (room.getLock())` の中で呼ぶ。
- **他人の occupantId をビューに載せない。** 宛先の一部であり、知ることが受信の権利になる。
- **MP を直接増減する操作を作らない。** マナのアンタップ枚数からの派生値である。
- **★操作は `ManualOperationService` に足し、`ManualOpsWsController` から呼ぶ。**
  個々の操作は「状態を変えてログ本文を返す」だけの関数であり、
  `push` も `addLog` も書かない。それを行うのは `apply()` の1箇所だけである。
- **★`move` は数値に触らない。** 印刷値への初期化は
  デッキ読み込み時の `ManualCardInstance.of(master)` で既に済んでいる。
  戻したいときは明示的に `/stat-reset` を送る。**画面が勝手に初期化しない。**
- **★進化はミニオン枠を N → 1 に減らす。** 画面の枠計算は配信ビューから毎回やり直すこと。
- **★履歴に積むのは盤面を変える操作だけ。** 自由メモ・勝敗の宣言・Undo・Redo は積まない。
- **★失敗した操作は盤面もログも履歴も変えない。** 失敗時とUndo/Redo のときだけ
  `ManualGameState` のオブジェクトが差し替わる。参照を持ち回らないこと。

### ★既知の限界(要確認・未対応)

- **★`CardMasterLoadTest.台帳の全カードが読み込まれる()` が赤い。**
  `hasSize(72)` に対して台帳は169枚。Batch 3 頃の値のまま。
  `Dockerfile` は `-DskipTests` のためデプロイには影響しないが、手元の `mvn test` は落ちる。
- **`tools/check_all.py` の項目3 は手動モードIDを偽陽性にする。**
  **手動モードのカードIDを Java にリテラルで書かないこと。** 性質(文明・種別)で引く。
- **`tools/check_records.py` の `TARGETS` が固定リスト**であり、
  手動モードの record 30個(17b で16個 + 18a で14個)を見ない。
  17b・18a とも同じロジックで手動検算した(それぞれ design-notes 3-3 / 3-1)。
  なお `ManualDeckImport.Entry` は `DeckDefinition.Entry` と名前が衝突し、
  検算時に★が1件出るが**偽陽性**である(3コンポーネント / 3引数で一致している)。
- **★`check_structure.py` は `src/main/java` しか見ない。`src/test` は無防備である。**
  17b・18a とも B 検査を `src/test` 向けに調整したものを別途走らせ、0件を確認した。
- **★画像IDは不透明なキーである。** `sha256(file) == filename` を検査に使ってはならない。
- **カードIDに CSV の行番号を使っている。** カードは末尾に足すこと。
- **手動モードの部屋は掃除されない。** `removeRoom()` はあるが誰も呼んでいない(19a)。
- 裏面画像 `75ee790b…` のみ 1063×1480(表面は 500×700)。CSS で吸収する方針。
- CSV に対応しない余剰画像が8枚ある。旧版であり、配信されないため削除不要。

---

## 2. 次の作業の候補(優先順位順)

### 手動モード(フェイズ1 = 一人回し)

| バッチ | 種別 | 範囲 | 既存変更 | 状態 |
|---|---|---|---|---|
| ~~17a~~ | a系 | データ変換・カードマスタ・確認画面 | なし | **完了** |
| ~~17b~~ | a系 | 状態モデル・部屋・在室者・個別宛先配信・デッキ取り込み | なし | **完了** |
| ~~18a~~ | a系 | 操作13項目(設計書5-3)・進化スタックの分解・Undo | なし | **完了** |
| **18b** | **b系** | **盤面の画面(タイル・手札・拡大画像・操作規約)** | **`battle.css`** | **次** |
| 18c | b系 | ゾーンを開く画面(帯・全面・検索・スタック帯) | なし | 未着手 |
| 19a | b系 | 入口の作り替え・リセット・ログ書き出し・切断復帰・仕上げ | `LobbyController` | 未着手 |

**全バッチを通じて既存ファイルの変更は `LobbyController` と `battle.css` の2つだけである。**

### Ver.0.4 対応(手動モードとファイルが重ならない)

1. ~~Batch 15b の push~~ **完了。18a で反映を確認した(3回続いた未 push は解消)。**
2. **Batch 15c / 15d(Sonnet 5)。** Ver.0.4 の残り30枚の効果を文明ごとに反映する。
   **★着手条件は満たされている。**

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

**★`check_structure.py` は `src/main/java` しか見ない。`src/test` は無防備である。**
17a はこれでビルドを1回落とした(テストが宣言していないヘルパを呼んでいた)。
`src` 全体にかけると `@Test void 名前()` と静的インポートの `assertThat` を
誤検出するため、そのままでは使えない。
**テストを新設・変更したバッチでは、テストクラス内の素呼び出しを目視で追うこと。**

**`check_records.py` の★3件(`GameActions.java:775` / `GameService.java:1144` /
`CardEffectRegistry.java:748`)は既知の偽陽性である**(スクリプト自身が注記している)。
新しく★が出たら必ず該当行を目視すること。
**手動モードの record は `TARGETS` に無いため検査されない。** 増やしたら手動で検算すること。

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
  15b の未 push は 16・17a・17b で3回続けて検出している。
- **数値はコードに埋まっていることがある。** 台帳が持つのは cost/attack/hp/keywords だけであり、
  「ドロー2枚」「自傷2」のような効果内の数値は `CardEffectRegistry` にある。
  **台帳を直しても効果は古いままになる。**
- **実装済み文明リストの唯一の正は `DeckValidator.IMPLEMENTED`。**
  `LobbyController.selectableLeaders()` と `deck-builder.js` の `IMPLEMENTED_CIVS` は
  `GET /api/implemented-civilizations` を経由して同じ集合を見る。
  **★手動モードはこの判定を一切使わない。** 未実装のカードを場に出して試すための場所である。
- **静的ファイルを変更したらキャッシュバスティングのバージョンをインクリメントする。**
  ただし**手動モードのカード画像には不要**である。
- **カード登録は card ID が `qte-cards.json` に実在するか必ず照合する。**
- **`ManualCardRepository` の読み込みに失敗するとアプリ全体が起動しない。**
  手動モードだけでなく通常モードも巻き添えになる。
- **手動モードのカードIDを Java にリテラルで書かない**(1章「既知の限界」)。
- **★テストコードは機械チェックの網の外にある。** この環境では Maven ビルドができないため、
  テストの型エラー・未宣言メソッドは発注者の手元でしか出ない。実装と同じ厳しさで読むこと。
- **★手動モードで「判断」を実装しない。** アプリが担うのは
  「同じ盤面を見ていることの保証」だけである(設計書 5-1)。
  コスト支払い・戦闘の解決・攻撃可否・フェイズ強制・勝敗判定・デッキ切れはすべて切る。

---

## 5. チャット開始テンプレート

```
QTE Battle の開発を継続する。以下の手順で作業を始めてほしい。

1. プロジェクトナレッジ内の `qte-project-reference.md` を読む
   (ゲームルール・設計判断の唯一の正)。
2. プロジェクトナレッジ内の `qte-handoff-v13.md` を読む
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
| **18b** | `batch16-manual-mode-design-v2_2.md` + `notes/batch18a-design-notes.md` |
| 18c | 同上 + `notes/batch18b-design-notes.md` |
| 19a | 同上 + `notes/batch18c-design-notes.md` |
| 15c / 15d | `ver0.4-transcription-notes.md` + `batch15a-design-notes.md` + `batch15b-design-notes.md` |
| 15e(基盤) | `batch15b-design-notes.md` 6章 + `batch15a-design-notes.md` |
| 整合チェック | 全文明の設計解説 + `qte-cards.json` |

### 18b の確認項目(次チャットで照合すること)

- `src/main/java/com/example/qte/manual/ManualOperationService.java` に
  `apply` / `move` / `evolve` / `undo` / `redo` があること
- `manual/web/ManualOpsWsController.java` に `@MessageMapping("/manual/{roomId}/move")` を含む
  18本の宛先があること
- `ManualBoardIndex.detach` が「素材」と「ゾーン直下」を分岐していること
- `src/test/java/com/example/qte/ManualOperationTest.java` が存在すること

---

## 6. この先の予定

手動モードを 18b → 18c → 19a の順に進め、フェイズ1(一人回し)を完成させる。
フェイズ2(ソロ対戦・対戦・観戦)はその後であり、
**着手前に UI 詰めのセッションをもう1回行う**(設計書11章)。

Ver.0.4 対応は 15b の push が済んだため、15c・15d・15e をいつでも進められる。
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
grep -rn "^    \(public\|private\)" src/main/java/com/example/qte/manual/ src/main/java/com/example/qte/room/RoomIds.java
```
