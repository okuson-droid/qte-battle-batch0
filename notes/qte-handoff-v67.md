# QTE 対戦アプリ — 引き継ぎ書 v67

最終更新: 2026-08-21。**Batch 60(P6 仕上げ)を実施。**
★★**旧 P5 の積み残しがすべて片付いた。**Ver1.1 移行(P0〜P6)はこれで一区切りである。

| 項目 | 現在値 |
|---|---|
| JUnit | **626件 全緑**(611 → +15。`Batch60Test` を新設し、役目を終えた2件を畳んだ) |
| verify | **543/543**(据え置き) |
| 効果の未実装 | **0枚**(据え置き) |
| 版数 | `manual-battle.js` v=33 / `battle.js` **v=26**(★上げた)/ `battle.css` v=46 |
| 裁定 | **279番まで確定** / ★**新規280〜282(3件)を依頼中・未確定** |
| カード定義 | 235枚(1枚も増減していない) |
| 壊し検証 | `tools/batch60_break_check.py` 10ケース **OK 10 / NG 0** |

---

## ★この文書の構成(v56 から継続)

| 文書 | 役割 | 更新のしかた |
|---|---|---|
| `notes/qte-rulings.md` | 裁定1〜279 の全文 | 追記専用 |
| `notes/qte-pitfalls.md` | 既知の落とし穴 | 追記・訂正のみ |
| `qte-project-reference.md` | プロジェクト定義・ゲームルール・設計判断 | ★**60 で1章と7章を全面改訂** |
| 本ファイル(ハンドオフ) | 直近バッチの要点・次の作業 | 毎バッチ書き直す |

---

## 0. 最初にやること

1. **`qte-project-reference.md` を読む。**★60 で1章(実装状況・フェーズ)と
   7章(カードデータの運用)を書き直した。**Ver0.4 時代の前提はもう残っていない。**
2. `notes/ver11-migration-plan.md` を読む(P0〜P6 の全体計画)。
3. `notes/qte-pitfalls.md` の該当節を読む(特に**「消すこと・揃えること・古い前提(Batch 60)」**)。
4. 本ファイルと **`notes/batch60-design-notes.md`** を読む。
5. **`notes/batch60-ruling-requests.md` を読み、裁定280〜282 の回答が揃っているか確認する。**
   ★**3件とも次の作業を止めない**(本文どおりの側・安全な側で実装済み)。
6. ソースコード取得: `git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git`
7. **「反映済み」を信じず、60 の変更箇所を実際に読んで照合する**(下の「60 の確認項目」)。

★★**注意: 59 の時点で GitHub が batch58 のままだった。**
clone した中身が古い可能性を必ず疑い、`git log -1` の日付とマスターの Eclipse ワークスペースを
突き合わせること。ズレていたら、変更されたファイルだけを接続フォルダから取り込んで合流させる。

---

## ★カードデータの正(★7章が更新された)

- `src/main/resources/cards/manual-cards.json`(Ver1.1・全235枚)が全モード共通の正。
- キーワードはテキストから作る(`CardTextKeywords.extract`。裁定158)。
- ★★**`qte-cards.json`(Ver0.4 の台帳)は Batch 60 で削除した。**
  残っているのは次の2つだけで、どちらも台帳ではない。
  - `manual-cards.json` の `ledgerCardId` …… 由来の記録(169枚に付く。新カード66枚は null)
  - `src/test/resources/keyword-baseline.json` …… キーワード抽出の回帰の物差し(169件・7KB)

---

## ★★60 がやったこと(要点)

設計解説は **`notes/batch60-design-notes.md`**(新規ファイル)。

### 1. 裁定の反映(277・278・279)

| 番号 | 内容 | コード |
|---|---|---|
| 277(a) | 《神風の大号令》は「いるだけ必ず選ぶ」を強制しない | **0行**(番人の試験だけ置いた) |
| 278(c) | **墓地からの召喚に対象選択の導線を新設**。57〜59 のガードは外れた | Java 2 + JS 1 |
| 279(a) | 《英知の水晶》の誘発は、誘発によるドローを数えない | **0行**(59 のまま) |

★**278 が今回いちばん大きい。**ただし新しい仕組みは1つも作っていない ——
Batch 53 が墓地からの【特殊召喚】のために敷いた導線
(`GraveSummonRequest` / `battle.js` の `beginSelection`)がそのまま使えた。

★**裁定275(狭い読み)は変わっていない。**変わったのは
「対象を運ぶ口が無いから止めていた」という**実装の都合**のほうである。

### 2. 裏向きマナと `fireManaPlaced` の非対称(51 設計解説 6-2)

《豊穣の地霊主》の本文は「マナにカードが置かれたとき」で**向きを条件にしていない**。
本文どおりの読みが1つに定まるので、**裏向きの配置も数える**ようにした(裁定280 で確認依頼)。

- 裏向きの配置6経路を `GameActions.addFaceDownMana` 1本に集約し、そこで `manaPlaced` を焚く。
- ピュア・エレメントの**一時マナも1回**として数える。
- ★遊びに効く例: 《ガイア・リソース》は1枚で**2回**マナに置く(表向き+【還元】)。
  土のリーダーが《豊穣の地霊主》なら、この1枚だけで1ドローが付く。

### 3. Ver0.4 台帳の削除と、番人の凍結

- `qte-cards.json`(101KB × 2箇所)/ `LedgerCards.java` /
  `tools/build_id_map.py` / `tools/rework_triage.py` を削除。
- ★**番人だけ凍結して残した**(マスター判断)。
  台帳の「人手で付けたキーワード169件」を Ver1.1 のIDに写して
  `src/test/resources/keyword-baseline.json`(7KB)へ。読み口は `support/KeywordBaseline`。
- `tools/check_legacy_ids.py` を新設(Ver0.4 形式のIDが本番のコードに無いことを見張る)。
- `tools/convert_manual_cards.py` は**退役**(実行すると理由を出して止まる)。
  ★再実行すると全235枚の本文が消えるためである。

### 4. `unlimitedCopies` の掃除

裁定267 で構築特例が廃止され、データ側にも項目が無い = **必ず偽になる分岐**を4箇所撤去した
(`ManualCardMaster` / `ManualCardRepository` / `ManualDeckImporter` / デッキメーカー)。
★`DeckValidator` は 46b で既に撤去済みで、**片方だけ直った状態が14バッチ続いていた**。

### 5. プリセットデッキの Ver1.1 化

6文明とも、**その文明の新カード10種を全部**入れた(3進化 + 7枚)。合計40枚は不変。
闇にはさらに《墓場の怨念集合体》(裁定278 の道具)と《悪夢》(裁定265)も入れた。

### 6. 文書

`qte-project-reference.md` の1章(実装状況・フェーズ・Ver1.1 で変わった前提)と
7章(カードデータの運用)を全面改訂。2〜6章も Ver1.1 の変更点に★を付けて更新した。

### ★副産物: 機械検証の穴を2つ塞いだ

- `verify/build_harness.py` が**版数まで含めた文字列一致**で Thymeleaf を書き換えていたため、
  `battle.js` を v=26 に上げた瞬間に検証が全滅した(`render is not defined`)。
  正規表現化し、**置換が0件ならその場で止める**ようにした。
- 壊し検証の `run_class` が、走らせる前に対象クラスの XML を消していなかった。
  コンパイルが通らないと前回の結果を読んで「NG」と誤判定する。
  ★**59 までの版にも同じ穴がある。**

---

## 60 の確認項目(★これを照合する)

- **`GameService.java`**: `summonFromGrave` が `List<TargetChoice>` を受け取り、
  「墓地からは召喚できません」のガードが**消えている** /
  `requireTrashSourceNotTargeted` が新設され `specialSummonFromGrave` からも呼ばれている /
  `playPureElement` の末尾が `actions.manaPlaced` を呼ぶ
- **`GameActions.java`**: `manaPlaced` と `addFaceDownMana` が新設され、
  裏向きの配置6箇所がすべて後者を通っている
- **`GameWsController.java`**: `summon-from-grave` が `GraveSummonRequest` を受け取り、
  `TrashActionRequest` が**消えている**
- **`battle.js`**: `beginGraveSummon` が新設され、`pickTrashCard` に自己対象の門がある /
  `battle.html` の版数が **v=26**
- **`DeckFactory.java`**: 6文明とも新カード10種が入り、合計40枚は不変
- **削除**: `qte-cards.json`(2箇所)/ `LedgerCards.java` /
  `tools/build_id_map.py` / `tools/rework_triage.py` / `unlimitedCopies`(4箇所)/
  `TrashActionRequest`
- **新設**: `src/test/resources/keyword-baseline.json` / `support/KeywordBaseline.java` /
  `tools/check_legacy_ids.py` / `tools/batch60_break_check.py` / `Batch60Test.java`
- JUnit **626件全緑** / verify **543/543**
- `python3 tools/check_structure.py` / `check_all.py` / `check_records.py`
  (★既知の誤検出3件。58 から変化なし)/ `check_undeclared.py` /
  `node --check` 2種 いずれも異常なし
- `python3 tools/check_legacy_ids.py` / `check_leader_abilities.py` いずれも OK
- `python3 tools/report_effects.py --summary` で **未実装 0枚**
- ★**`tools/rework_triage.py --check` はもう無い**(P5 完了で役目終了)。
  毎バッチのチェックリストから外すこと。

---

## 1. 次の作業(Batch 61)

**★Ver1.1 移行(P0〜P6)はこれで終わった。**61 は新しい章の1本目である。
どれをやるかは**マスターの選択**であり、こちらから1つに決めていない。

### 候補

| 候補 | 内容 | 大きさ |
|---|---|---|
| **A. 割り込み選択の一般化** | 「〜してもよい」を毎回問い合わせる形にする。ドローがエンジンのあらゆる場所から呼ばれるため、中断点(`ResumePoint`)を一般化する工事になる | **大** |
| **B. 実機確認の消化** | 35〜59 の実機確認が未報告のまま溜まっている。遊んで違和感を洗い出す | 中(マスターの作業) |
| **C. 新カード66枚のテキストの目視校正** | 46a からの持ち越し。転記のゆれを潰す | 中 |
| **D. バランス調整** | プリセットが全カードに触れる形になったので、遊んで強弱を見る | 中 |
| **E. 観測できない3件の手当て** | 本物の入口から測れない挙動に、測れる形を与える | 中 |

★**わたし(クロエ)の推し**: まず **B**(実機確認)である。
P2〜P5 で145枚 + 121枚を作り直したのに、35 以降の実機確認がほとんど報告されていない。
**機械検証が緑でも、遊び味は誰も見ていない。**A の工事はそのあとでも遅くない。

### 各バッチがやること(P2から継続・変更なし)

1. `python3 tools/report_effects.py --summary` で未実装の枚数を確認する。
   ★**58 から 0枚が正常値である。**1枚でも出たら、それは新しく壊れたということである。
2. **本文が2通り以上に読めるカードは、実装で決めずに必ず裁定を仰ぐ**(裁定184)。
   ★ただし**本文どおりの読みが1つに定まるなら、実装して確認を依頼する形でよい**(作業を止めない)。
3. **カード毎の JUnit を `support/AutoGameFixture` の上に書く**(裁定187)。
4. `mvn test` で回すこと(`surefire:test` はコンパイルしない。裁定208)。
5. ★**壊し検証の改変は「軸」ごとに1件ずつ当てる**(57 の教訓)。
6. ★★**壊しても落ちないとき、「試験が足りない」の前に
   「改変が当たっているか」「その盤面が構造的に作れるか」を疑う**(59・60 の教訓)。

---

## 2. 発注者とのやりとり

- grep優先でファイルを渡り歩き、全体読み込みは避ける。
- **判断に迷う点はまとめて質問する。**1つずつ聞かない。
- 呼び方は「クロエ」、発注者は「マスター」。会話は日本語カジュアル体、
  **ドキュメントは通常文体(である調)。**

---

## 3. 既知の落とし穴

**★全文は `notes/qte-pitfalls.md` にある。**60 で1節を追記した。

| 節 | 追記した内容 |
|---|---|
| 消すこと・揃えること・古い前提(Batch 60) | 「止めていた理由」が裁定か実装の都合かを分けて書く / 似た経路が既にあるかを見積もりの前に grep する / 検証は支払いより前 / 絞り込みが偶然守っている穴は次の1枚で開く / 本文が条件にしていないものを実装が条件にしていないか疑う / 共通化は「上限に達したときどうするか」の手前で止める / 消したものは grep に出てこない / 番人のために資料を丸ごと残さない / 動かないツールは「消す」と「止める」を使い分ける / 同じ規則が2箇所にあると片方だけ直った状態が続く / プリセットを厚くすると試験の前提が変わる / 進化には素材の仲間が要る / ★**版数を上げると機械検証が落ちることがある** / ★**壊し検証は結果ファイルが今回のものか測っていなかった** |

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

★**`build_id_map.py` と `rework_triage.py` は削除した。**上のリストから外れている。

---

## 5. チャット開始テンプレート

```
QTE Battle の開発を継続する。Batch 61 を行う。

読む順:
1. プロジェクトナレッジ内の `qte-project-reference.md`(★60 で1章と7章を全面改訂)
2. プロジェクトナレッジ内の `notes/ver11-migration-plan.md`
3. プロジェクトナレッジ内の `claude/qte-handoff-v67.md`(本ファイル)
4. プロジェクトナレッジ内の `notes/batch60-design-notes.md`
5. `notes/qte-pitfalls.md` の該当節(特に「消すこと・揃えること・古い前提(Batch 60)」)
6. ★プロジェクトナレッジ内の `notes/batch60-ruling-requests.md`
   —— 裁定280〜282(3件)の回答が揃っているか確認。★3件とも作業は止めない。

環境:
7. git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git
   ★clone が古い(未 push)可能性を必ず疑う。git log -1 の日付を確認すること。
8. 接続フォルダの m2repo.zip を device_stage_files で取り込んで /root/m2work へ展開し、
   mvn -o -B "-Dmaven.repo.local=/root/m2work/repository" test を回す。
   ★626件全緑が出発点である。緑でなければ止めて報告する。
9. 60 の反映を本ファイルの「60の確認項目」で照合し、verify 543/543 と
   report_effects の「未実装0枚」、check_legacy_ids.py と
   check_leader_abilities.py が両方 OK であることを確認する。

作業:
10. 本ファイル1章「次の作業(Batch 61)」の候補からマスターが選んだものを行う。
    ★設計解説は新規ファイル `notes/batch61-design-notes.md` に書く。
    ★試験は新規ファイル `Batch61Test.java` に書く。
    ★壊し検証は `tools/batch61_break_check.py` を新設し、改変は「軸」ごとに1件ずつ当てる。
11. 納品は4章のとおり。

呼び方は「クロエ」、こちらの呼び方は「マスター」で。会話は日本語カジュアル体、
ドキュメントは通常文体(である調)で。
```

## 6. 60 完了時点の積み残し

### マスターにお願いすること

- **Eclipse で refresh(F5)→ Run As → JUnit Test。**期待: **全626件が緑**。
- ★**裁定依頼(`notes/batch60-ruling-requests.md`)への回答をお願いします。**
  **280・281・282 の3件です。★3件とも次の作業は止めません。**
- ★★**実機確認をお願いしたいことが増えました。**60 は JS を1本触っています(`battle.js` v=26)。
  - **《黄泉の召喚主》で墓地から《執念の暗殺者》を召喚する。**
    サブフェイズに墓地の一覧から選ぶと、**対象を選ぶ画面が出る**ようになりました。
    59 までは「墓地からは召喚できません」と断られていた操作です。
  - **プリセットデッキが6文明とも変わりました。**新カード10種ずつ + 進化3枚が入っています。
    ★進化召喚を1回試していただけると助かります(素材は自分の場のミニオンです)。
  - **土のリーダーを《豊穣の地霊主》にして《ガイア・リソース》を撃つ。**
    1枚で2回マナに置かれるので、**1ドローが付く**ようになりました。
- 問題なければ **自分で git commit / push**。
  ★**59 は push されていませんでした。**60 と一緒に上げてください。

### 継続中の積み残し

- **裁定280〜282(3件)が未回答**(作業は止まっていない)。
- 割り込み選択の一般化(「〜してもよい」の問い合わせ)は今回も未着手。
- 35〜59 の実機確認が未報告のまま(46bと48は報告あり)。★**60 の候補 B。**
- 新カード66枚のテキストの目視校正(46aからの持ち越し)。
- 進化召喚がモアニールの登場置換で止まったときの素材の扱い(裁定232)。実機で違和感の
  有無を確認してほしい。
- **本物の入口から観測できない挙動が3件** ——
  《英霊・コレキ》の「相手のターン中は止めない」/《風弾の跳弾》の「そうしたら」/
  《悪夢》の封じの範囲(自分だけか両者か)。
- ★**プリセットに今も入っていない Ver0.4 のカードが数枚ある**
  (火の《武具昇華の炎》《血の対価》、闇の《獄門の裁定者》など)。
  60 は「新カード66枚を全部入れる」を目標にしたため、そこまでは手を伸ばしていない。
- 手動モード関連の積み残しは一時停止中。
