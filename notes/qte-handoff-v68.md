# QTE 対戦アプリ — 引き継ぎ書 v68

最終更新: 2026-08-21。**Batch 61(カード一覧の統一)を実施。**

| 項目 | 現在値 |
|---|---|
| JUnit | **637件 全緑**(626 → +11) |
| verify | **545件 全緑**(543 → +2) |
| 効果の未実装 | **0枚**(据え置き) |
| 版数 | `battle.css` **v=47**(★上げた)/ `battle.js` v=26 / `manual-battle.js` v=33 |
| 裁定 | **279番まで確定** / ★**280〜282(3件)は依頼中のまま・未確定** |
| カード定義 | 235枚(枚数・数値・意味は不変。**本文の表記だけ76枚を揃えた**) |
| 壊し検証 | `tools/batch61_break_check.py` 8ケース **OK 8 / NG 0** |

---

## ★この文書の構成(v56 から継続)

| 文書 | 役割 | 更新のしかた |
|---|---|---|
| `notes/qte-rulings.md` | 裁定1〜279 の全文 | 追記専用 |
| `notes/qte-pitfalls.md` | 既知の落とし穴 | 追記・訂正のみ |
| `qte-project-reference.md` | プロジェクト定義・ゲームルール・設計判断 | 60 で1章と7章を全面改訂 |
| 本ファイル(ハンドオフ) | 直近バッチの要点・次の作業 | 毎バッチ書き直す |

---

## 0. 最初にやること

1. `qte-project-reference.md` を読む(60 で1章と7章を全面改訂した)。
2. `notes/qte-pitfalls.md` の該当節を読む
   (特に**「画面とカード本文(Batch 61)」**と「消すこと・揃えること・古い前提(Batch 60)」)。
3. 本ファイルと **`notes/batch61-design-notes.md`** を読む。
4. **`notes/batch60-ruling-requests.md` を読み、裁定280〜282 の回答が揃っているか確認する。**
   ★**3件とも次の作業を止めない**(本文どおりの側・安全な側で実装済み)。
5. ソースコード取得: `git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git`
6. **「反映済み」を信じず、61 の変更箇所を実際に読んで照合する**(下の「61 の確認項目」)。

★★**注意: 59・60 の時点で GitHub が古いままだった。**
clone した中身が古い可能性を必ず疑い、`git log -1` の日付とマスターの Eclipse ワークスペースを
突き合わせること。ズレていたら、変更されたファイルだけを接続フォルダから取り込んで合流させる。

---

## ★カードデータの正(変更なし)

- `src/main/resources/cards/manual-cards.json`(Ver1.1・全235枚)が全モード共通の正。
- キーワードはテキストから作る(`CardTextKeywords.extract`。裁定158)。
- ★**Batch 61 でこのファイルは「手で直すもの」になった。**
  生成元の `tools/convert_manual_cards.py` は 60 で退役している(再実行すると本文が消える)。
  番人は `CardTextKeywordsTest`(キーワード照合 + **書き方の照合**)と `EffectImplementationTest`。

---

## ★★61 がやったこと(要点)

設計解説は **`notes/batch61-design-notes.md`**(新規ファイル)。
マスターの指示は「カード一覧で表示されるカードを最新のカードテキスト、および形式に統一」の1行だった。

### 1. ★発見: カードフェイスの実装がすでに2つあった

デッキメーカーの `.tile` / `.t-*` は、盤面の `.mcard` / `.mcard-*` を**値まで写した手書きのコピー**。
つまり「デッキメーカーの形式」= 「盤面の形式」であり、正が2箇所に散っているだけだった。

★新しい2画面は **`battle.css` の `.mcard` をそのまま使う**(3つ目のコピーを作らない)。
★**1本化(`.tile` → `.mcard`)はやっていない** —— 機械検証545件のうち**28件**が
`.tile` / `.t-*` を名指しで見ており、今クラス名を変えると検証のほうが先に壊れる。**次のバッチの候補。**

### 2. カード本文の改行が全画面で潰れていた

235枚中**28枚**が改行を持つ。HTML は既定でそれを空白1つに畳むので、
カード画像では2行のものが1行につながって出ていた。
`white-space: pre-wrap` を `.mcard-text`(battle.css)と `.t-text` / `.bc-text`(デッキメーカー)に当てた。
★宣言が2箇所にあるので、**verify の番人も2件**置いてある。

### 3. 本文の表記を76枚ぶん揃えた(新カードの書き方に寄せる)

丸括弧は全角（）/ 数字は半角 / 各行は句点か閉じ記号で終わる / 【】まわりの空白を削る。
★**語も数値も意味も1文字も変えていない。**
★`CardTextKeywordsTest` に**書き方の番人を4件**足した(違反したカードを一覧で返す)。
★**カード名は触っていない**(名前の一部の括弧は照合キーである)。

### 4. カード一覧2画面をカードフェイスに作り替えた

`templates/fragments/card-face.html` に markup の正を1つ置き、2画面が呼ぶ。
★このプロジェクトで **Thymeleaf のフラグメントを使うのは初めて**である。

- `/cards` …… 7列の表 → カードフェイスのグリッド(黒地に統一)
- `/manual/cards` …… 画像グリッド → カードフェイスのグリッド。
  ★**カード画像はセルから外した**(マスター判断)。画像の欠けは検算アラートが名指しする。
- ★`ManualCardMaster` が **`text` を持つようになった** ——
  手動モードのカード一覧が本文を出せなかった原因である(設計書 3-1 の理由は Ver0.4 時代のもの)。
  **`keywords` は今も持たせていない**(裁定158)。

### 5. ★画面が描けることを初めて試験した

`CardListPageTest`(7件)を新設。**60 までこの2画面には試験が1件も無かった。**
機械検証はテンプレートから作ったハーネスを見るだけで、
**Spring がテンプレートを解決できるか**は誰も測っていなかった。

★**実際に1回落ちた** —— `th:each` と `th:replace` を同じタグに書いており、
Thymeleaf の優先順位で差し込みが先に走って 500 になっていた。
試験が無ければ、マスターがページを開くまで分からなかった。

---

## 61 の確認項目(★これを照合する)

- **`templates/fragments/card-face.html`**(新規)…… `styles` / `cell(card)` / `grid(cards)`。
  `th:each` は外側の `th:block` にあり、`th:replace` と同じタグに乗っていない
- **`cards.html`** …… 表が消え、黒地 + `qcard-grid` になっている
- **`manual-cards.html`** …… セルからカード画像が消え、`qcard-grid` になっている。
  検算ヘッダと「画像の欠け」アラートは残っている
- **`ManualCardMaster` / `ManualCardRepository`** …… `text` を持つ
- **`battle.css`** …… `.mcard-text` に `white-space: pre-wrap` / 版数が **v=47**
  (`battle.html` / `manual-battle.html` / `manual-deck-maker.html` の3つとも)
- **`manual-deck-maker.html`** …… `.t-text` / `.bc-text` に `white-space: pre-wrap`
- **`manual-cards.json`** …… 半角丸括弧・全角数字・行末の句点なし・【】まわりの空白が**0件**
- **新設**: `CardListPageTest.java` / `tools/batch61_break_check.py` / `notes/batch61-design-notes.md`
- JUnit **637件全緑** / verify **545/545**
- `python3 tools/check_structure.py` / `check_all.py` / `check_records.py`
  (★既知の誤検出3件)/ `check_undeclared.py` / `node --check` 2種 いずれも異常なし
- `python3 tools/check_legacy_ids.py` / `check_leader_abilities.py` いずれも OK
- `python3 tools/report_effects.py --summary` で **未実装 0枚**

---

## 1. 次の作業(Batch 62)

どれをやるかは**マスターの選択**である。

| 候補 | 内容 | 大きさ |
|---|---|---|
| **A. カードフェイスの1本化** | デッキメーカーの `.tile` / `.t-*` を `.mcard` へ寄せ、コピーを消す。★verify 28件の作り替えが同時に要る | 中〜大 |
| **B. 実機確認の消化** | 35〜61 の実機確認が未報告のまま溜まっている | 中(マスターの作業) |
| **C. 新カード66枚のテキストの目視校正** | 46a からの持ち越し。★<b>61 で表記は揃ったが、画像との突き合わせはしていない</b> | 中 |
| **D. 割り込み選択の一般化** | 「〜してもよい」を毎回問い合わせる形にする | 大 |
| **E. バランス調整** | プリセットが全カードに触れる形になったので、遊んで強弱を見る | 中 |

★**わたし(クロエ)の推し**: **C**(目視校正)である。
61 で**表記**は揃ったが、**中身が画像と合っているか**は誰も見ていない。
しかも 61 でカード一覧が読める形になったので、**今なら画面を見ながら照合できる**。
そのあと A(1本化)へ行くのが自然だと思う。

### 各バッチがやること(P2から継続・変更なし)

1. `python3 tools/report_effects.py --summary` で未実装の枚数を確認する(**0枚が正常値**)。
2. **本文が2通り以上に読めるカードは、実装で決めずに必ず裁定を仰ぐ**(裁定184)。
   ★ただし**本文どおりの読みが1つに定まるなら、実装して確認を依頼する形でよい**。
3. **カード毎の JUnit を `support/AutoGameFixture` の上に書く**(裁定187)。
4. `mvn test` で回すこと(`surefire:test` はコンパイルしない。裁定208)。
5. ★**壊し検証の改変は「軸」ごとに1件ずつ当てる**(57 の教訓)。
6. ★★**壊しても落ちないとき、「試験が足りない」の前に
   「改変が当たっているか」「その盤面が構造的に作れるか」を疑う**(59・60 の教訓)。
7. ★**画面(テンプレート)を作ったら、MockMvc で 200 と中身を測る試験を1件は置く**(61 の教訓)。

---

## 2. 発注者とのやりとり

- grep優先でファイルを渡り歩き、全体読み込みは避ける。
- **判断に迷う点はまとめて質問する。**1つずつ聞かない。
- 呼び方は「クロエ」、発注者は「マスター」。会話は日本語カジュアル体、
  **ドキュメントは通常文体(である調)。**

---

## 3. 既知の落とし穴

**★全文は `notes/qte-pitfalls.md` にある。**61 で1節を追記した。

| 節 | 追記した内容 |
|---|---|
| 画面とカード本文(Batch 61) | 同じものを見る画面が4つあって形が違った / ★**カードフェイスの実装は2つある**(1本化はまだやらない) / ★**カード本文の改行は HTML では消える**(pre-wrap。pre は不可) / 表記は放っておくとばらつく(書き方の番人4件) / カード本文を触るときの番人は3つ / カード名は本文と別扱い / ★**Thymeleaf: th:each と th:replace を同じタグに書かない** / th:classappend の値に先頭の空白を入れない / ★**画面が描けることは誰も測っていなかった** / Spring Boot 4 では @AutoConfigureMockMvc が使えないことがある / 手動モードの text 除外の理由はもう成り立たない |

---

## 4. デリバリー形式(変更なし)

マスターの Eclipse ワークスペース
(`C:\Users\奥村優斗\OneDrive\ドキュメント\eclipse_workフォルダ\qte-battle-batch0`)へ
直接書き込む。手順はv62の4章のとおり。

反映前に必ず実行:

```bash
python3 tools/check_structure.py src/main/java
python3 tools/check_all.py .
python3 tools/check_records.py src/main/java     # ★既知の誤検出3件あり(EffectContextの多重定義)
python3 tools/check_undeclared.py src/main/resources/static/js/*.js
node --check src/main/resources/static/js/manual-battle.js
node --check src/main/resources/static/js/battle.js
NODE_PATH=/home/claude/.npm-global/lib/node_modules \
PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers \
  python3 verify/build_harness.py && node verify/verify.js
mvn -o -B "-Dmaven.repo.local=/root/m2work/repository" test
python3 tools/check_legacy_ids.py
python3 tools/report_effects.py --summary
python3 tools/check_leader_abilities.py
```

---

## 5. チャット開始テンプレート

```
QTE Battle の開発を継続する。Batch 62 を行う。

読む順:
1. プロジェクトナレッジ内の `qte-project-reference.md`
2. プロジェクトナレッジ内の `claude/qte-handoff-v68.md`(本ファイル)
3. プロジェクトナレッジ内の `notes/batch61-design-notes.md`
4. `notes/qte-pitfalls.md` の該当節(特に「画面とカード本文(Batch 61)」)
5. ★プロジェクトナレッジ内の `notes/batch60-ruling-requests.md`
   —— 裁定280〜282(3件)の回答が揃っているか確認。★3件とも作業は止めない。

環境:
6. git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git
   ★clone が古い(未 push)可能性を必ず疑う。git log -1 の日付を確認すること。
7. 接続フォルダの m2repo.zip を device_stage_files で取り込んで /root/m2work へ展開し、
   mvn -o -B "-Dmaven.repo.local=/root/m2work/repository" test を回す。
   ★637件全緑が出発点である。緑でなければ止めて報告する。
8. 61 の反映を本ファイルの「61の確認項目」で照合し、verify 545/545 と
   report_effects の「未実装0枚」を確認する。

作業:
9. 本ファイル1章「次の作業(Batch 62)」の候補からマスターが選んだものを行う。
   ★設計解説は新規ファイル `notes/batch62-design-notes.md` に書く。
   ★壊し検証は `tools/batch62_break_check.py` を新設し、改変は「軸」ごとに1件ずつ当てる。
10. 納品は4章のとおり。

呼び方は「クロエ」、こちらの呼び方は「マスター」で。会話は日本語カジュアル体、
ドキュメントは通常文体(である調)で。
```

## 6. 61 完了時点の積み残し

### マスターにお願いすること

- **Eclipse で refresh(F5)→ Run As → JUnit Test。**期待: **全637件が緑**。
- ★**カード一覧を2つとも開いて見てほしい。**`/cards` と `/manual/cards` の両方である。
  - 手動モードのほうは**本文が出るようになった**(60 までは1文字も出ていなかった)。
  - 《潮流の魔導士》《水鏡の幻術師》のように、**本文が2行以上に分かれて出る**ようになった。
- ★**盤面とデッキメーカーでも改行が効く。**カードを拡大したときの見え方が変わっている。
- ★**裁定280〜282 の回答は 60 からの持ち越しである**(`notes/batch60-ruling-requests.md`)。
- 問題なければ **自分で git commit / push**。
  ★**59・60 が push されていない。**61 と一緒に上げてください。

### 継続中の積み残し

- **裁定280〜282(3件)が未回答**(作業は止まっていない)。
- ★**カードフェイスの実装が2つある**(デッキメーカーの `.tile` と盤面の `.mcard`)。
  1本化には verify 28件の作り替えが要る。**Batch 62 の候補 A。**
- ★**新カード66枚のテキストの目視校正**(46aからの持ち越し)。
  61 で表記は揃ったが、**画像との突き合わせはしていない**。**候補 C。**
- ★**カード名の表記は揃っていない**(「愚乱怒土地(グランドランド)」の半角括弧など)。
  名前は照合キーなので、揃えるなら別の判断が要る。
- **【常在】：のコロン**(3枚)。表記の統一ではなく語の削除に近いので 61 では見送った。
- 割り込み選択の一般化(「〜してもよい」の問い合わせ)は未着手。
- 35〜61 の実機確認が未報告のまま(46bと48は報告あり)。
- 進化召喚がモアニールの登場置換で止まったときの素材の扱い(裁定232)。
- **本物の入口から観測できない挙動が3件** ——
  《英霊・コレキ》/《風弾の跳弾》/《悪夢》の封じの範囲。
- プリセットに今も入っていない Ver0.4 のカードが数枚ある
  (火の《武具昇華の炎》《血の対価》、闇の《獄門の裁定者》など)。
- 手動モード関連の積み残しは一時停止中。
