# QTE 対戦アプリ — 引き継ぎ書 v60

最終更新: 2026-08-20。**Batch 53(進化7枚 +《英術・スケアロック》)完了。★P3 完了。**

| 項目 | 現在値 |
|---|---|
| JUnit | **439件 全緑** |
| verify | **539/539** |
| 効果の未実装 | **7枚**(★すべて【賢魂】待ち。P4 が丸ごと引き取る) |
| 版数 | `manual-battle.js` v=33 / **`battle.js` v=24(★上げた)** / `battle.css` v=46 |
| 裁定 | 246番まで確定(次は **247** から) |

---

## ★この文書の構成(v56 から)

**ハンドオフ書は3つに割ってある。**

| 文書 | 役割 | 更新のしかた |
|---|---|---|
| **`notes/qte-rulings.md`** | 裁定1〜246 の**全文** | **追記専用。**新しい裁定を末尾に足すだけ |
| **`notes/qte-pitfalls.md`** | 既知の落とし穴(32節) | **追記・訂正のみ。**該当する節に足す |
| **本ファイル**(ハンドオフ) | 直近バッチの要点・確認項目・次の作業・開始テンプレート | 毎バッチ書き直す(**20KB 前後に収める**) |

★**ハンドオフ書に裁定の全文や落とし穴の全文を書き戻さないこと。**

---

## 0. 最初にやること

1. **`notes/ver11-migration-plan.md`** を読む(46〜55 の作業の正)。
   ★**バッチ番号は 49 で詰めてある**(P3 = 52〜53 **完了** / P4 = 54 / P5 = 55)。
2. **`notes/qte-pitfalls.md`** を読む。★作業対象に関係する節は必読。
   Batch 54(P4 = 賢魂)なら「**進化エンジン(Batch 52)**」「**進化の効果と登場の制限(Batch 53)**」
   「サンドボックスの制約」「通常モードのゲームプレイ試験」「効果の実装状況・効果未実装の印」
   「対象指定(TargetSpec)の落とし穴」「デッキ構築」の7節。
3. **`notes/qte-rulings.md`** は**引く**もので、通しで読むものではない。
   ★**ただし P4 は例外** —— **裁定152(【賢魂】の定義)は着手前に読むこと。**
   進化に触るなら 154・157・220〜246 も引く。
4. 本ファイルと **`notes/batch53-design-notes.md`**(+ 必要なら 52)を読む。
5. `qte-project-reference.md` を読む。★1章の実装状況は2026-07-21のまま古い(P5 で更新)。
6. ソースコード取得: `git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git`
7. **「反映済み」を信じず、直近バッチの変更箇所を実際に読んで照合する**(下の「53 の確認項目」)。

---

## ★カードデータの正(46b で確定)

- **`src/main/resources/cards/manual-cards.json`(Ver1.1・全235枚)が全モード共通の正。**
- **カードが実際に持つキーは10個だけ**:
  `id / name / type / civilization / cost / attack / hp / imageId / ledgerCardId / text`。
  **`keywords` と `unlimitedCopies` は存在しない**(落とし穴「消えたフィールド」)。
- **キーワードはテキストから作る。**`CardTextKeywords.extract(text)` が唯一の出どころ(裁定158)。
- 内訳: リーダー18 / ミニオン119 / **進化18** / スペル61 / ウェポン19。6文明×39 + 文明なし1。
- 実装状況: **登録あり156 / 宣言あり52 / 未実装7**(`python3 tools/report_effects.py --summary`)。
- ★**未実装の7枚はすべて【賢魂】を持つ。**「まだ手が回っていないカード」は0枚になった。

---

## ★★53 が変えたこと(要点)

設計解説は **`notes/batch53-design-notes.md`**。

1. ★★★**効果から進化を出す経路を作った**(裁定226・245)。
   `GameActions.putIntoFieldByEffect(room, owner, cardId, materials)` の素材版。
   ★**【召喚時】は発動せず、登場時(ON_ENTER)だけが発動する。**
2. ★★★**束を作る処理を `GameActions.attachEvolutionMaterials` に1本化した。**
   52 は `GameService.summonToField` に直接書いていた。★**召喚側もこれを呼ぶ。**
3. ★★★**「場に出た後」の共通処理を `GameActions.fireEntryTriggers` に1本化した。**
   登場の数え上げ + 発火3つ。召喚だけが持つのは<b>直前の ON_SUMMON 1つ</b>である。
4. ★★**墓地からの【特殊召喚】を通した**(`SpecialSummonSpec.fromGrave`)。
   `GameService.specialSummonFromGrave` / WS の `special-summon-from-grave`。
   ★**手札からと違うのは出どころだけ**で、着地は `summonToField` に合流する。
5. ★★★**「場に出られるか」を問う場所を1つにした**(`GameActions.isFieldEntryBlocked`。裁定246)。
   ★**満杯を直に見ていた8箇所を置き換えた** —— 見落とすとカードが消えるか無限ループになる。
6. ★**登場の数え上げを1箇所に置いた**(《英霊・コレキ》。ターン番号の刻印)。
7. **進化7枚 +《英術・スケアロック》を実装した。**未実装 15 → **7枚**。
   ★**水文明の未実装が 0 になった**(火に次いで2文明目)。
8. **新しい裁定を12個確定した(235〜246)。**全文は `notes/qte-rulings.md`。
9. ★**壊し検証 33通り**(JUnit 30 + verify 3)。**33/33 OK。**
   ★1回目は NG 3件・EMPTY 2件 —— **うち1件は実装の欠陥だった**(余計な宣言。設計解説 5-1)。

## 53 の確認項目(★これを照合する)

- **`GameActions`**: `isFieldEntryBlocked` / `attachEvolutionMaterials` /
  `fireEntryTriggers` / `putIntoFieldByEffect` の**素材つきオーバーロード**がある
- ★**`isMinionZoneFull()` の直呼びが、カードが宙に浮きうる箇所から消えている**
  (`reviveFromGrave` / `putManaCardIntoField` / 神の福音 / ギガマウス・バイト /
  カムバックキーパー / 降臨の伝道師 / 黄泉還る水龍 / ゾンストライカー / 不滅のネクロマンサー)
- ★**`GameService.summonToField` から素材の for 文が消え、`actions.attachEvolutionMaterials` と
  `actions.fireEntryTriggers` の2行になっている**
- **`GameService`**: `requireCanEnterField`(5つの召喚の入口が呼ぶ)/ `specialSummonFromGrave`
- **`RuleGuards`**: `KOREKI` / `minionEntryDenial` / `IMPLEMENTED_CARDS` に KOREKI
- ★**`minionEntryDenial` が「自分の手番か」を最初に見ている**(裁定237(2))
- **`PlayerState`**: `minionEntryTurn` / `minionEntryCount` / `countMinionEntry` /
  `minionEntriesOn` / `pendingEvolutionCardId`
  / ★**`startTurnReset()` で登場のカウンタを戻していない**
- **`SpecialSummonSpec`**: `fromGrave`(7つ目の成分)と6引数の互換コンストラクタ
- **`StatCalculator`**: `NYUKIRO`(相手のスペル + 手札の数)/ `IMPLEMENTED_CARDS` に追加
- **`CardEffectRegistry`**: `registerEvolutionEffectCards()` がコンストラクタから呼ばれている /
  `requestScarelockEvolution` / `evolutionMaterialsAvailable` / `countMinionsInTrash` /
  `requestDiscard` / `takeHandCardsAt` / `discardChosenHandCards` / `resolveScarelockMaterials` /
  `fireMinionEnteredFromGrave` に**ノアの【突進】付与**が足されている
- ★**`NOA` 定数は `IMPLEMENTED_CARDS` に入っていない**(表にも載っているため。設計解説 5-1)
- ★**ノアの表への登録はカードIDのリテラルである**(定数だとツールが数え落とす)
- **`ResumePoint`**: `RAKABU_DISCARD` / `ZOKUSHIMU_DISCARD` / `ENRA_DESTROY` /
  `ASHINO_REIKOSHA_SUMMON` / `SCARELOCK_EVOLUTION` / `SCARELOCK_MATERIAL` の6つ
- ★**`TargetSpec.Filter` が1つも増えていない**(裁定234 の2度目)
- **`CardView`** に `canSpecialSummonFromGrave` / **`GameViewBuilder.buildCardView`** が
  `inTrash` を取る / **`GameWsController`** に `special-summon-from-grave` と `GraveSummonRequest`
- **`battle.js`**: `showZoneFaces(title, cards, graveSummon)` / `beginGraveSpecialSummon` /
  自分の墓地だけ導線が出る
- **版数**: `battle.js` **v=24**(`battle.html` と `verify/build_harness.py` の両方)
- **`EvolutionEffectTest`(32件)** / `EffectImplementationTest` 19 → 20 = **JUnit 439件**
- **`EffectImplementationTest`**: 「7枚」「進化4 + 非進化3」/
  ★**`印が付くカードはすべて賢魂を持つ`** が新設されている /
  ★**`Batch53で実装した8枚には印が付かない`** /
  ★**`Batch52と53が賢魂待ちとしてP4へ送った7枚には今も印が付く`**
- **verify 539/539**(53-1・53-2 を追加)/ `report_effects.py --summary` で **WATER 0**
- `decks/batch53-light-check-deck.json` / `decks/batch53-dark-check-deck.json`
  (どちらも `DeckValidator` を通ることを確認済み)
- `tools/batch53_break_check.py` がある(★`mvn test` で回している。`surefire:test` ではない)

★52 以前の確認項目は各版のハンドオフにある(52 = v59 / 51 = v58 / 50 = v57 / 49 = v56 /
48 = v55 / 47 = v54 / 46b = v53 / 46a = v52 / 24〜45 = v51)。

---

## 1. 次の作業(★Batch 54 = P4 賢魂)

| フェーズ | バッチ | 内容 |
|---|---|---|
| **P4 賢魂** | **54** | **裁定152 の実装**(アクション種別 + 手札UIの2導線)+ **7枚** |
| P5 仕上げ | 55 | プリセットの Ver1.1 版 / デッキメーカー JSON の受け入れ / E2E / **`qte-cards.json` の削除**(`LedgerCards` と台帳照合3件も同時に消す)/ **`unlimitedCopies` の死んだ分岐3箇所の掃除** / **裏向きマナと `fireManaPlaced` の非対称**(51 設計解説 6-2)/ `qte-project-reference` 更新 |

### ★Batch 54 が実装するもの(7枚 + エンジン)

**エンジン(裁定152):**「【賢魂：n】を持つミニオンは、**スペルとしても使える**」。

- コストは **n**。効果は【賢魂：n】に続くテキスト。
- **ミニオンとして召喚した場合、賢魂の効果は発動しない。**
- スペルとして使う場合は**スペルのコスト軽減などの影響を受ける**
  (= ルール上「スペルの使用」として扱う)。
- 実装上の含意: (1) **手札 UI に2導線**(ミニオンとして出す / スペルとして使う)、
  (2) エンジンに「ミニオンカードのスペル使用」という**新しいアクション種別**。
  解決後はスペルと同様に墓地へ、`ON_CARD_USED` 等のスペル系トリガも発火、の想定。
  ★**この想定は着手時に裁定を仰ぐこと**(v58 の時点から「P4 の設計解説で個別確認する」となっている)。

| カード | 本文の要点 |
|---|---|
| 英霊・タイガラム(光・進化) | 【進化】+【賢魂：3】 |
| 黒ノ霊導者(風・進化) | 【進化】+【賢魂：1】 |
| 白ノ霊知者(風・進化) | 【進化】+【賢魂：2】 |
| 愚乱怒土地(土・進化) | 【進化】+【賢魂：3】 |
| グレイヴガールズファン(闇) | 【守護】【賢魂：1】1枚引く。その後山札の上から1枚を墓地に置く |
| スタンディングテント(闇) | 【守護】【召喚時】2枚引く。【賢魂：2】このミニオンを場に出す。そのミニオンの【召喚時】は使えない |
| 勝阿外(土) | 【賢魂：2】 |

★**進化4枚は「進化部分だけなら 52・53 で書けた」が丸ごと送ってある**(裁定229) ——
部分実装は印で表せない(裁定165)ためである。**54 で7枚すべての印が消え、未実装は0になる。**

★**54 が終わると `EffectImplementationTest.印が付くカードはすべて賢魂を持つ` が空になる。**
そのときこの試験は「印の付くカードは1枚も無い」を主張する形へ書き換えること。

### 各バッチがやること(P2 から継続)

1. 未実装の一覧を `python3 tools/report_effects.py`(--summary なし)で出し、実装する。
2. **本文が2通り以上に読めるカードは、実装で決めずに必ず裁定を仰ぐ**(裁定184)。
   ★**まとめて1〜2回で聞く。**52 は18件を2回、**53 は12件を3回**に分けて出した。
3. **ルール側に実装したら、そのクラスの `IMPLEMENTED_CARDS` に足す**(裁定176)。
   ★**ただし表にも載っているカードは足さない** —— 外しても何も落ちない宣言になる(53 の教訓)。
   ★**`CardEffectRegistry` の表への登録はカードIDのリテラルのまま**にする(定数だと数え落とす)。
   ★進化の素材条件の表(`evolutions`)は「登録」に数えない(裁定233)。
4. `EffectImplementationTest` の**枚数を減らし、その文明のカードを名指しする試験も足す**
   (裁定162・181)。★「印が付く」側で名指ししてよいのは
   「意図して後続へ送ったカード」だけである(裁定219)。
5. **カード毎の JUnit を `support/AutoGameFixture` の上に書く**(裁定187)。
   `EvolutionEffectTest` が最新の雛形。**効果は本物の入口から起こす。**
6. **壊し検証は `tools/batch53_break_check.py` をコピーして書く。**
   ★**`mvn test` で回すこと**(`surefire:test` はコンパイルしない。裁定208)。
   ★**JavaScript を触ったら verify 側も手で壊して確かめる**(53 は3通り)。
7. 成果物の書き方は下の「運用ルール」に従う。

### ★★運用ルール(2026-08-19 にマスターと決めた。v56 から継続)

**削るもの:**

- **ハンドオフ書は 20KB 前後に収める。**裁定と落とし穴の全文を書き戻さない。
- **設計解説は「新しい判断」だけを厚く書く。**既存カードの写しで済むものは**表に1行**でよい。
  ★**ただし裁定の根拠と「なぜそう決めたか」は今までどおり全部残す。**目安 12KB 前後。
- **壊し検証は新しい仕組みに集中する。**既存の形に乗っただけのカードは**1通りで足りる**。
  目安 10〜25通り(49 は23、50 は19、51 は26、52 は28、**53 は33**)。

**削らないもの(削るとかえって高くつく):**

- **裁定を仰ぐこと(184)。**★**53 の12件のうち、実装で決めていたら違っていたものが3件ある**
  (ニュウキロの読み / コレキの範囲 / スケアロックの素材不足の扱い)。
- **本物の入口からの JUnit(187)。**P2〜P3 で積み上がった唯一の資産である。
- **機械チェック(`tools/` と `verify/`)。**実行は数分で、実際に事故を止めている。
- ★**壊し検証(116)。**53 では**実装の欠陥を1件**見つけた(余計な宣言)。
  「試験が足りない」だけでなく「実装が余計」も出る。

### 完了済みバッチ

17a〜23 / 24〜31 / 32設計・32a〜32c / 33〜45 /
46a(Ver1.1 移行の土台)/ 46b(カードマスタの移行)/ 47(効果未実装の印)/
48(風8枚)/ 49(水6枚)/ 50(闇6 + 光6)/ 51(火7 + 土8。P2 完了)/
52(進化エンジン + 進化6枚 + 茶爺)/ **53(進化7枚 + スケアロック。★P3 完了)**

### 保留中

- **作り直し69〜80枚**(本文が Ver1.1 で変わった実装済みカード)。48〜53 では範囲外。
  P5 の手前で拾うバッチが要る。
- **裏向きでマナに置く経路が `fireManaPlaced` を発火しない**(51 設計解説 6-2)。
  ★裁定を仰いでいない。P5 で拾うこと。
- **攻撃の経路に、現行のカードプールでは到達しない防御的な分岐が2つある**(51 設計解説 6-1)。
  ★**53 でも到達しないままである。**
- ★**効果で出した進化の【召喚時】が観測できない**(53 設計解説 6-1)。
  光文明の進化3枚に【召喚時】を持つものが無いためで、実装は構造で守られている。
  **P4 でタイガラムに【召喚時】が無いかを確かめること。**
- ★**《英霊・コレキ》の「相手のターン中は止めない」を本物の入口から観測できない**
  (53 設計解説 6-2)。この1件だけ判定層に直接問う試験になっている。
- 印を**デッキメーカー**にも出すか(47 で見送り)/ 音声素材の差し替え(中断中)/
  部屋の永続化(優先順位10)/ タブレット対応(優先順位11)/
  デッキメーカーの名前付きスロット(40 Q2 = b。未着手)。

---

## 2. 発注者とのやりとり

- grep 優先でファイルを渡り歩き、全体読み込みは避ける。
- **判断に迷う点はまとめて質問する。**1つずつ聞かない。
- **本文が2通り以上に読めるカードは、実装で決めずに必ず裁定を仰ぐ**(裁定184)。
- 呼び方は「クロエ」、発注者は「マスター」。
  会話は日本語カジュアル体、**ドキュメントは通常文体(である調)。**

---

## 3. 既知の落とし穴

**★全文は `notes/qte-pitfalls.md`(32節)にある。**ここには置かない。

Batch 54(P4 = 賢魂)で特に効くのは次の7節である。

| 節 | なぜ効くか |
|---|---|
| **進化の効果と登場の制限(Batch 53)** | ★**「場に出られるか」は1箇所 / 束と登場後の共通処理 / 表の登録はリテラル** |
| **進化エンジン(Batch 52)** | ★賢魂を持つ進化4枚は、進化部分もここに乗る |
| サンドボックスの制約 | m2repo.zip / 日本語テスト名が選べない / Jackson が無い |
| 通常モードのゲームプレイ試験 | `AutoGameFixture` の使い方。★`giveMana` はマナにミニオンを置く |
| 効果の実装状況・「効果未実装」の印 | `IMPLEMENTED_CARDS` の足し方。★足しすぎない |
| 対象指定(TargetSpec)の落とし穴 | ★**規則はクライアントに置かない**(裁定234。53 は Filter を1つも足していない) |
| デッキ構築(Batch 47・52 で変わった) | ★進化は解禁済み。禁忌の進化は墓地に戻らない |

---

## 4. デリバリー形式(Batch 42 から)

**zip は廃止。** マスターの Eclipse ワークスペース
(`C:\Users\奥村優斗\OneDrive\ドキュメント\eclipse_workフォルダ\qte-battle-batch0`、
デバイス接続フォルダ)へ、変更・新規ファイルを**直接書き込む**。

手順(Claude 側):

1. サンドボックスのクローンで実装・検証。
2. **書く前に `device_stage_files` の mtime で「マスターが触っていないか」を確かめる。**
3. SendUserFile → `device_commit_files` でワークスペースへ反映する。
4. 設計解説とハンドオフ更新も同じ経路で `notes/` へ入れ、プロジェクトナレッジにも書く。

手順(マスター側): Eclipse で **refresh(F5)** → **Run As → JUnit Test**(★mvn CLI は無い)
→ 確認して問題なければ**自分で git commit / push**。

反映前に必ず実行:

```bash
python3 tools/check_structure.py src/main/java
python3 tools/check_all.py .
python3 tools/check_records.py src/main/java     # ★既知の誤検出3件あり
python3 tools/check_undeclared.py src/main/resources/static/js/*.js
node --check src/main/resources/static/js/manual-battle.js
node --check src/main/resources/static/js/battle.js
NODE_PATH=/home/claude/.npm-global/lib/node_modules \
PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers \
  python3 verify/build_harness.py && node verify/verify.js
mvn -o -B "-Dmaven.repo.local=/root/m2work/repository" test
python3 tools/build_id_map.py --check      # 「ベタ書きされた台帳ID: 0 種」であること
python3 tools/report_effects.py --summary  # ★宣言の過不足もここで検出される
```

---

## 5. チャット開始テンプレート

★**このテンプレートは手順だけを書く。**やることの中身は1章、制約は
`notes/qte-pitfalls.md` にある —— **ここへ写すと、また同じ重複が育つ。**

```
QTE Battle の開発を継続する。Batch 54(P4 = 【賢魂】のエンジン + 7枚)を行う。

読む順:
1. プロジェクトナレッジ内の `notes/ver11-migration-plan.md`(46〜55 の作業の正)
2. プロジェクトナレッジ内の `claude/qte-handoff-v60.md`
   ★裁定の全文は `notes/qte-rulings.md`、既知の落とし穴は `notes/qte-pitfalls.md` にあり、
   ハンドオフ書には要点しかない。
3. ★**裁定152 の全文**(【賢魂】の定義。P4 の中核)。進化に触るなら 154・157・220〜246 も引く。
4. `notes/qte-pitfalls.md` の7節(v60 の3章に一覧がある)。
   ★とくに「進化の効果と登場の制限(Batch 53)」「進化エンジン(Batch 52)」は必読。
5. `notes/batch53-design-notes.md`(直近の作り。54 はこの上に足す)

環境:
6. git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git
7. 接続フォルダの m2repo.zip を device_stage_files で取り込んで展開し、
   mvn -o -B "-Dmaven.repo.local=/root/m2work/repository" test を回す
   (手順は pitfalls の「サンドボックスの制約」)。
   ★439件全緑が出発点である。緑でなければ止めて報告する。
8. 53 の反映を v60 の「53 の確認項目」で照合し、verify 539/539 を確認する。
   反映されていなければ止めて報告する。

作業:
9. v60 の1章「次の作業」に従う。★54 は【賢魂】のエンジン + 7枚である。
   ★裁定152 の「実装上の含意」(スペル系トリガの発火・墓地への行き先)は
   <b>想定であって確定ではない</b>。着手時にまとめて裁定を仰ぐこと。
   ★同章の「運用ルール」に、成果物の分量と壊し検証の絞り方が書いてある。
10. 納品は v60 の4章のとおり。ドキュメントはチャットにファイルとして添付する。

呼び方は「クロエ」、こちらの呼び方は「マスター」で。会話は日本語カジュアル体、
ドキュメントは通常文体(である調)で。
```

## 6. 53 完了時点の積み残し

### マスターにお願いすること(★53 分)

- **Eclipse で refresh(F5)→ Run As → JUnit Test。**期待: **全439件が緑**。
- **実機確認。★★JavaScript を変えたのでキャッシュクリア(Ctrl+F5)が必要である。**
  添付の **`decks/batch53-light-check-deck.json`**(リーダー = 英皇アントマルエル)と
  **`decks/batch53-dark-check-deck.json`**(リーダー = 演舞の墓守)で1試合ずつ。
  見てほしい11点は **53 設計解説の 7-2** にある(ここには写さない)。
  ★とくに大事なのは次の4つである ——
  1. **《英術・スケアロック》が「光の3コストを出す → その上に進化を重ねる」を1枚でやる**
     (直前に出した1体をそのまま素材にできる。裁定243)。
  2. **《サモナーポップ・エンラ》を墓地パイルから特殊召喚できる**
     (墓地のミニオン6体以上。自身も数える。裁定240)。
  3. **《英霊・コレキ》が居ると相手はそのターン1体しかミニオンを出せない**
     (効果で3体出すカードでも1体だけ。裁定237)。
  4. **《リボーンライヴ・ノア》の【召喚時】で出した3体が【突進】を得てそのターン殴れる**(裁定239)。
- 問題なければ **自分で git commit / push**。

### 継続中の積み残し

- **作り直し(Ver1.1 で本文が変わった実装済みカード)は手つかず**(48〜53 で範囲外)。
- **`unlimitedCopies` に乗ったままの死んだ分岐が3箇所**(49 設計解説 6-2)。**無害。**P5 で掃除。
- **裏向きマナと `fireManaPlaced` の非対称**(51 設計解説 6-2)。★裁定を仰いでいない。P5。
- **35〜52 の実機確認が未報告のまま**(46b と 48 は報告あり)。
- **演舞の墓守の2本目の発火位置(`summonFromGrave`)は現行のカードプールでは到達しない**
  (50 設計解説 6-1)。★51 の防御的な分岐2つも同じ性質のまま(51 設計解説 6-1)。
- **進化召喚が登場の置換(モアニール)で止まったときの素材の扱い**(裁定232)。
  ★実機で見かけたら違和感の有無を教えてほしい。
- **効果で出した進化の【召喚時】は観測できない**(53 設計解説 6-1)。P4 で確かめる。
- **《英霊・コレキ》の「相手のターン中は止めない」も本物の入口から観測できない**(53 設計解説 6-2)。
- 印をデッキメーカーにも出すか(47 で見送り)/ 部分実装は印で表せない(裁定165)。
- `qte-cards.json` の削除(P5)。`support/LedgerCards` と台帳照合3件も同時に。
- 新カード66枚のテキストの目視校正(46a からの持ち越し)。
- `qte-project-reference.md` の1章が古い(2026-07-21)。P5 で更新。
- 手動モード関連の積み残しは**一時停止中**。再開時は v47 の6章。
- ハーネスが `static/vendor/` を読んでいない(意図的。41 設計解説 1-6)。
- 通常モードの `.log-box` の高さが手動モードの定義に引きずられる(42 設計解説5章。実害小)。
