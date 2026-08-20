# QTE 対戦アプリ — 引き継ぎ書 v61

最終更新: 2026-08-20。**Batch 54(【賢魂】のエンジン + 7枚)完了。★P4 完了。**

| 項目 | 現在値 |
|---|---|
| JUnit | **469件 全緑** |
| verify | **543/543** |
| 効果の未実装 | **★0枚**(Ver1.1 全235枚の効果が実装済みになった) |
| 版数 | `manual-battle.js` v=33 / **`battle.js` v=25(★上げた)** / `battle.css` v=46 |
| 裁定 | **259番まで確定**(次は **260** から) |

---

## ★この文書の構成(v56 から)

**ハンドオフ書は3つに割ってある。**

| 文書 | 役割 | 更新のしかた |
|---|---|---|
| **`notes/qte-rulings.md`** | 裁定1〜259 の**全文** | **追記専用。**新しい裁定を末尾に足すだけ |
| **`notes/qte-pitfalls.md`** | 既知の落とし穴(33節) | **追記・訂正のみ。**該当する節に足す |
| **本ファイル**(ハンドオフ) | 直近バッチの要点・確認項目・次の作業・開始テンプレート | 毎バッチ書き直す(**20KB 前後に収める**) |

★**ハンドオフ書に裁定の全文や落とし穴の全文を書き戻さないこと。**

---

## 0. 最初にやること

1. **`notes/ver11-migration-plan.md`** を読む(46〜55 の作業の正)。
   ★**バッチ番号は 49 で詰めてある**(P3 = 52〜53 完了 / P4 = 54 **完了** / P5 = 55)。
2. **`notes/qte-pitfalls.md`** を読む。★作業対象に関係する節は必読。
   Batch 55(P5 = 仕上げ)なら「**消えたフィールド**」「**デッキ構築**」
   「サンドボックスの制約」「通常モードのゲームプレイ試験」「効果の実装状況・効果未実装の印」
   「Ver1.1 移行(Batch 46 系)」「**【賢魂】の2つの姿(Batch 54)**」の7節。
3. **`notes/qte-rulings.md`** は**引く**もので、通しで読むものではない。
   P5 が引くのは 158・162・169・171・173・176・209・255 あたりである。
4. 本ファイルと **`notes/batch54-design-notes.md`**(+ 必要なら 53)を読む。
5. `qte-project-reference.md` を読む。★1章の実装状況は2026-07-21のまま古い(**P5 で更新する**)。
6. ソースコード取得: `git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git`
7. **「反映済み」を信じず、直近バッチの変更箇所を実際に読んで照合する**(下の「54 の確認項目」)。

---

## ★カードデータの正(46b で確定)

- **`src/main/resources/cards/manual-cards.json`(Ver1.1・全235枚)が全モード共通の正。**
- **カードが実際に持つキーは10個だけ**:
  `id / name / type / civilization / cost / attack / hp / imageId / ledgerCardId / text`。
  **`keywords` と `unlimitedCopies` は存在しない**(落とし穴「消えたフィールド」)。
- **キーワードはテキストから作る。**`CardTextKeywords.extract(text)` が唯一の出どころ(裁定158)。
  ★**54 から、テキストは【賢魂：n】を境に2つに割れる**(裁定248)。
  `extract` は境目の手前まで / `soulKeywords` は後ろだけ / `soulCost` が n を読む。
- 内訳: リーダー18 / ミニオン119 / 進化18 / スペル61 / ウェポン19。6文明×39 + 文明なし1。
- 実装状況: **登録あり163 / 宣言あり52 / 未実装0**(`python3 tools/report_effects.py --summary`)。
- ★**「効果の文があるのにエンジンが処理を持たないカード」は0枚になった。**

---

## ★★54 が変えたこと(要点)

設計解説は **`notes/batch54-design-notes.md`**。

1. ★★★**カードのテキストを【賢魂：n】で2つに割った**(裁定248)。
   前半がミニオンの姿、後半がスペルの姿である。規則は `CardTextKeywords` にしかない。
   ★**《白ノ霊知者》の【還元】がミニオン本体から消えた**(53 までは持っていた)。
2. ★★★**n はカードテキストが唯一の出どころである。**`SoulSpellSpec` は効果と対象要求だけを持つ。
3. ★★★**賢魂の使用は「スペルの使用」である**(裁定247)。使用回数・`ON_CARD_USED`・
   スペル封じ・コスト軽減・`ON_NEXT_SPELL` がすべて通常のスペルと同じ道具を通る。
   ★`StatCalculator` のコスト計算は**基準コストと種別を差し替えて同じ計算へ流す**(写していない)。
4. ★★**入口を2つ足した**(`play-soul` / `play-taboo-soul`)。
   どちらの姿で使うかは<b>プレイヤーの宣言</b>であり、種別からは決まらない(裁定207)。
5. ★★**禁忌デッキからも賢魂として使える。退けるマナは n 枚である**(裁定250)。
6. ★★**`SpellDisposition` に4つ目 `KEPT_BY_EFFECT` を足した**(《スタンディングテント》)。
   他の2値が置き換え先を指すのに対し、これだけは**「もう動かすな」**という指示である。
7. ★**`EffectContext.fromTaboo` を足した。**禁忌由来のカードが効果で場に出る唯一の例のため。
8. **7枚を実装した。**未実装 7 → **0枚**。★**印の付くカードは1枚も無くなった。**
9. **新しい裁定を13個確定した(247〜259)。**全文は `notes/qte-rulings.md`。
10. ★**壊し検証 33通り**(JUnit 29 + verify 4)。**33/33 OK。**
    ★1回目は NG 1件 —— **試験が2つの理由を同じ語で測っていた**(設計解説 8-1)。

## 54 の確認項目(★これを照合する)

- **`CardTextKeywords`**: `soulCost` / `soulKeywords` / `soulText` / `hasSoul` /
  private の `minionFace` / `soulFace` / `toHalfWidthDigits` / `keywordsIn`
  / ★**`SOUL` の正規表現が全角数字(`０-９`)も取る**
  / ★**`extract` が `keywordsIn(minionFace(text))` になっている**
- **`SoulSpellSpec`**(新規): `targets` と `effect` の2つだけ。**コストを持たない**
- **`CardEffectRegistry`**: `soulSpells` の表 / `soulSpellOf` / `registerSoulCards()` が
  コンストラクタから呼ばれている / ★**`isRegistered` が `soulSpells` を見ている**
  / `resolveGurandorandoChoice` / `KATSUAGE_DRAW_MANA_LIMIT`
- **`GameService`**: `playSoulCard` / `playTabooSoulCard` / `requireSoul` / `soulCostOf` /
  `resolveSoulSpell` / 3引数の private `playTabooCard(..., asSoul)`
  / ★**`resolveSoulSpell` が【還元】を `soulKeywords` で見ている**(`hasKeyword` ではない)
- **`StatCalculator`**: `effectiveSoulCost` / private の
  `effectiveCost(state, owner, card, baseCost, asType)` / 勝阿外の Attack 加算
  / ★**`KATSUAGE` が `IMPLEMENTED_CARDS` に入っていない**(理由はコメントにある)
- **`RuleGuards`**: `spellDenial` が `KATSUAGE` を見ている
  / ★**`KATSUAGE` が `IMPLEMENTED_CARDS` に入っていない**
- **`GameActions`**: `disposeUsedCard`(5引数)/ `placeCardInManaFaceDown` /
  `placeTopOfDeckInManaFaceDown` / `putIntoFieldByEffect` の**5引数版**(素材 + 禁忌由来)
- **`EffectContext`**: `fromTaboo`(9つ目の成分)と8引数・7引数の互換コンストラクタ
- **`SpellDisposition`**: `KEPT_BY_EFFECT`
- **`ResumePoint`**: `HAKUNO_REICHISHA_DESTROY` / `GURANDORANDO_MANA` の2つ
- **`CardView`** に `soulCost` / `soulEffectiveCost` / `soulTargets` / `soulText`
  / **`GameWsController`** に `play-soul` と `play-taboo-soul`
- **`battle.js`**: `soulPrompt(card)` / `onHandCardClick` の**賢魂の確認が進化素材の選択より前**
  / `onTabooCardClick` の `action` 分岐 / `finishTabooPayment` が `action` を読む
  / 手札と禁忌の両方に `★賢魂:n` のバッジ
- **版数**: `battle.js` **v=25**(`battle.html` と `verify/build_harness.py` の両方)
- **`SoulSpellTest`(31件)** / `EffectImplementationTest` 20 → 19 = **JUnit 469件**
- **`EffectImplementationTest`**: ★**`効果未実装のカードは1枚も無い`** /
  ★**`賢魂を持つ7枚はすべて実装済みである`** /
  ★**`Batch52と53がP4へ送った7枚は54で実装された`**(53 の試験を裏返した形)
- **verify 543/543**(54-1〜54-4 を追加)/ `report_effects.py --summary` で **未実装 0**
- `tools/report_effects.py` の `REGISTRY_MAPS` に **`soulSpells`** がある
- `decks/batch54-dark-check-deck.json` / `-wind-` / `-light-`(3本とも `DeckValidator` 通過済み)
- `tools/batch54_break_check.py` がある(★`mvn test` で回している。`surefire:test` ではない)

★53 以前の確認項目は各版のハンドオフにある(53 = v60 / 52 = v59 / 51 = v58 / 50 = v57 /
49 = v56 / 48 = v55 / 47 = v54 / 46b = v53 / 46a = v52 / 24〜45 = v51)。

---

## 1. 次の作業(★Batch 55 = P5 仕上げ)

| フェーズ | バッチ | 内容 |
|---|---|---|
| **P5 仕上げ** | **55** | 下の一覧のとおり |

### ★Batch 55 がやること

1. **プリセットデッキ(`DeckFactory`)の Ver1.1 版。**現在はまだ Ver0.4 の構成である。
2. **デッキメーカーの JSON がそのまま通常モードで使えることの確認**(D1 案B の目的そのもの)。
3. **`qte-cards.json` の削除。**★`src/test/java/.../support/LedgerCards.java` と
   台帳照合3件も同時に消す。`tools/build_id_map.py` の台帳参照も要確認。
4. **`unlimitedCopies` の死んだ分岐3箇所の掃除**(`ManualCardRepository` /
   `ManualDeckImporter.validate` / `manual-deck-maker.html`。49 設計解説 6-2)。
5. **★裏向きマナと `fireManaPlaced` の非対称に裁定を仰ぐ**(51 設計解説 6-2)。
   ★**54 で裏向きの経路が2本増えたが、既存の非対称をそのまま踏襲してある。**
6. **E2E 目視** / **`qte-project-reference.md` の1章を更新**(2026-07-21 のまま古い)。

### ★★P5 の前に拾うべきもの(裁定を仰ぐこと)

- **作り直し 69〜80枚。**Ver1.1 で本文が変わった実装済みカードである。
  48〜54 では一貫して範囲外にしてきた。**P5 の手前で1〜2バッチ要る。**
  ★数の出どころ: `python3 tools/report_effects.py`(--summary なし)の
  「本文が台帳と異なるカード」の表。**字面の比較なので、遊びに効く変更が何枚かは人が読んで決める**
  (2026-08-16 の読み合わせでは実質変更86・うち実装済み69。`ver11-migration-plan.md` 0-2)。

### 各バッチがやること(P2 から継続)

1. 未実装の一覧を `python3 tools/report_effects.py`(--summary なし)で出す。
   ★**54 で 0枚になったので、55 以降このステップは「増えていないこと」の確認である。**
2. **本文が2通り以上に読めるカードは、実装で決めずに必ず裁定を仰ぐ**(裁定184)。
   ★**まとめて1〜2回で聞く。**52 は18件を2回、53 は12件を3回、**54 は15件を1回**で出した。
3. **ルール側に実装したら、そのクラスの `IMPLEMENTED_CARDS` に足す**(裁定176)。
   ★**ただし表にも載っているカードは足さない** —— 外しても何も落ちない宣言になる
   (53 のノア・**54 の勝阿外**)。
   ★**`CardEffectRegistry` の表への登録はカードIDのリテラルのまま**にする(定数だと数え落とす)。
   ★進化の素材条件の表(`evolutions`)は「登録」に数えない(裁定233)。
   ★**賢魂の表(`soulSpells`)は数える**(裁定247 の含意。効果そのものだから)。
4. `EffectImplementationTest` は**不変条件を測る形になった**(裁定209)。
   ★**新しくカードを足したら「効果未実装のカードは1枚も無い」が落ちる。**枚数を直すのではなく、
   そのカードの効果を実装すること。
5. **カード毎の JUnit を `support/AutoGameFixture` の上に書く**(裁定187)。
   `SoulSpellTest` が最新の雛形。**効果は本物の入口から起こす。**
6. **壊し検証は `tools/batch54_break_check.py` をコピーして書く。**
   ★**`mvn test` で回すこと**(`surefire:test` はコンパイルしない。裁定208)。
   ★**JavaScript を触ったら verify 側も手で壊して確かめる**(54 は4通り)。
7. 成果物の書き方は下の「運用ルール」に従う。

### ★★運用ルール(2026-08-19 にマスターと決めた。v56 から継続)

**削るもの:**

- **ハンドオフ書は 20KB 前後に収める。**裁定と落とし穴の全文を書き戻さない。
- **設計解説は「新しい判断」だけを厚く書く。**既存カードの写しで済むものは**表に1行**でよい。
  ★**ただし裁定の根拠と「なぜそう決めたか」は今までどおり全部残す。**目安 12KB 前後。
- **壊し検証は新しい仕組みに集中する。**既存の形に乗っただけのカードは**1通りで足りる**。
  目安 10〜25通り(49 は23、50 は19、51 は26、52 は28、53 は33、**54 は33**)。

**削らないもの(削るとかえって高くつく):**

- **裁定を仰ぐこと(184)。**★**54 の15件のうち、実装で決めていたら違っていたものが4件ある**
  (白ノ霊知者の【還元】の帰属 / 禁忌からの賢魂 / 黒ノ霊導者の空撃ち / 愚乱怒土地のマナ上限)。
- **本物の入口からの JUnit(187)。**P2〜P4 で積み上がった唯一の資産である。
- **機械チェック(`tools/` と `verify/`)。**実行は数分で、実際に事故を止めている。
  ★**54 では `report_effects.py` の「未知の登録先がある」検査が実際に働いた。**
- ★**壊し検証(116)。**54 では**試験の欠陥を1件**見つけた(2つの理由を同じ語で測っていた)。

### 完了済みバッチ

17a〜23 / 24〜31 / 32設計・32a〜32c / 33〜45 /
46a(Ver1.1 移行の土台)/ 46b(カードマスタの移行)/ 47(効果未実装の印)/
48(風8枚)/ 49(水6枚)/ 50(闇6 + 光6)/ 51(火7 + 土8。P2 完了)/
52(進化エンジン + 進化6枚 + 茶爺)/ 53(進化7枚 + スケアロック。P3 完了)/
**54(【賢魂】のエンジン + 7枚。★P4 完了。効果の未実装が 0 になった)**

### 保留中

- **作り直し69〜80枚**(本文が Ver1.1 で変わった実装済みカード)。**P5 の手前で拾うバッチが要る。**
- **裏向きマナと `fireManaPlaced` の非対称**(51 設計解説 6-2)。
  ★裁定を仰いでいない。**54 で経路が2本増えた。P5 で拾うこと。**
- **攻撃の経路に、現行のカードプールでは到達しない防御的な分岐が2つある**(51 設計解説 6-1)。
  ★**54 でも到達しないままである。**
- ★**《英霊・コレキ》の「相手のターン中は止めない」を本物の入口から観測できない**
  (53 設計解説 6-2)。この1件だけ判定層に直接問う試験になっている。
- 印を**デッキメーカー**にも出すか(47 で見送り。★未実装が0になったので、
  **もう出す意味が無いかもしれない** —— P5 で確認する)/ 音声素材の差し替え(中断中)/
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

**★全文は `notes/qte-pitfalls.md`(33節)にある。**ここには置かない。

Batch 55(P5 = 仕上げ)で特に効くのは次の7節である。

| 節 | なぜ効くか |
|---|---|
| **消えたフィールド(46b の置き土産)** | ★`unlimitedCopies` の死んだ分岐3箇所を掃除する回である |
| **Ver1.1 移行(Batch 46 系)** | ★台帳照合・`ledgerCardId`・一括 sed の作法。`qte-cards.json` を消す回である |
| **デッキ構築(Batch 47・52 で変わった)** | ★プリセットを Ver1.1 化する。進化は解禁済み |
| サンドボックスの制約 | m2repo.zip / 日本語テスト名が選べない / Jackson が無い |
| 通常モードのゲームプレイ試験 | `AutoGameFixture` の使い方。★`giveMana` はマナにミニオンを置く |
| 効果の実装状況・「効果未実装」の印 | ★**未実装は0枚。**試験は不変条件を測る形になった |
| **【賢魂】の2つの姿(Batch 54)** | ★テキストの割れ方・2つの入口・行き先の4通り |

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
python3 tools/check_records.py src/main/java     # ★既知の誤検出4件あり(54 で3→4)
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
QTE Battle の開発を継続する。Batch 55(P5 = 仕上げ)を行う。

読む順:
1. プロジェクトナレッジ内の `notes/ver11-migration-plan.md`(46〜55 の作業の正)
2. プロジェクトナレッジ内の `claude/qte-handoff-v61.md`
   ★裁定の全文は `notes/qte-rulings.md`、既知の落とし穴は `notes/qte-pitfalls.md` にあり、
   ハンドオフ書には要点しかない。
3. `notes/qte-pitfalls.md` の7節(v61 の3章に一覧がある)。
   ★とくに「消えたフィールド」「Ver1.1 移行(Batch 46 系)」「デッキ構築」は必読。
4. `notes/batch54-design-notes.md`(直近の作り)

環境:
5. git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git
6. 接続フォルダの m2repo.zip を device_stage_files で取り込んで展開し、
   mvn -o -B "-Dmaven.repo.local=/root/m2work/repository" test を回す
   (手順は pitfalls の「サンドボックスの制約」)。
   ★469件全緑が出発点である。緑でなければ止めて報告する。
7. 54 の反映を v61 の「54 の確認項目」で照合し、verify 543/543 と
   report_effects の「未実装 0」を確認する。反映されていなければ止めて報告する。

作業:
8. v61 の1章「次の作業」に従う。★55 は P5(仕上げ)である。
   ★同章の「P5 の前に拾うべきもの」(作り直し69〜80枚)を、
   <b>55 に含めるか別バッチにするかをマスターに確認すること。</b>
   ★同章の「運用ルール」に、成果物の分量と壊し検証の絞り方が書いてある。
9. 納品は v61 の4章のとおり。ドキュメントはチャットにファイルとして添付する。

呼び方は「クロエ」、こちらの呼び方は「マスター」で。会話は日本語カジュアル体、
ドキュメントは通常文体(である調)で。
```

## 6. 54 完了時点の積み残し

### マスターにお願いすること(★54 分)

- **Eclipse で refresh(F5)→ Run As → JUnit Test。**期待: **全469件が緑**。
- **実機確認。★★JavaScript を変えたのでキャッシュクリア(Ctrl+F5)が必要である。**
  添付の **3本のデッキ**で1試合ずつ ——
  `decks/batch54-dark-check-deck.json`(リーダー = 演舞の墓守)/
  `decks/batch54-wind-check-deck.json`(リーダー = 疾風の導き手)/
  `decks/batch54-light-check-deck.json`(リーダー = 英皇アントマルエル)。
  見てほしい9点は **54 設計解説の 7-2** にある(ここには写さない)。
  ★とくに大事なのは次の4つである ——
  1. **《スタンディングテント》を賢魂：2 で使うと、そのカード自身が場に出る**
     (【召喚時】の2ドローは起きず、2ダメージを受けた状態で立つ)。
  2. **《白ノ霊知者》を賢魂で使うとマナへ、ミニオンとして破壊されると墓地へ行く**
     (【還元】が賢魂の姿にだけ付いている。裁定248)。
  3. **禁忌の《勝阿外》を賢魂：2 で使える**(退けるマナは**2枚**。使用後は消滅)。
  4. **《英術・スケアロック》で出した《英霊・タイガラム》の【召喚時】が発動しない**
     (53 設計解説 6-1 の宿題。観測できるのは 54 が初めてである)。
- 問題なければ **自分で git commit / push**。

### 継続中の積み残し

- **作り直し(Ver1.1 で本文が変わった実装済みカード)は手つかず**(48〜54 で範囲外)。
- **`unlimitedCopies` に乗ったままの死んだ分岐が3箇所**(49 設計解説 6-2)。**無害。**P5 で掃除。
- **裏向きマナと `fireManaPlaced` の非対称**(51 設計解説 6-2)。★裁定を仰いでいない。P5。
- **35〜53 の実機確認が未報告のまま**(46b と 48 は報告あり)。
- **演舞の墓守の2本目の発火位置(`summonFromGrave`)は現行のカードプールでは到達しない**
  (50 設計解説 6-1)。★51 の防御的な分岐2つも同じ性質のまま(51 設計解説 6-1)。
- **進化召喚が登場の置換(モアニール)で止まったときの素材の扱い**(裁定232)。
  ★実機で見かけたら違和感の有無を教えてほしい。
- ★**《英霊・コレキ》の「相手のターン中は止めない」を本物の入口から観測できない**
  (53 設計解説 6-2)。
- ★**《勝阿外》の【常在】の下で相手が賢魂を使おうとする盤面**は、まだ実機で見られていない。
  JUnit では測ってある(54 設計解説 9章)。
- 印をデッキメーカーにも出すか(47 で見送り。★未実装0になったので要否を再確認)/
  部分実装は印で表せない(裁定165)。
- `qte-cards.json` の削除(P5)。`support/LedgerCards` と台帳照合3件も同時に。
- 新カード66枚のテキストの目視校正(46a からの持ち越し)。
- `qte-project-reference.md` の1章が古い(2026-07-21)。P5 で更新。
- 手動モード関連の積み残しは**一時停止中**。再開時は v47 の6章。
- ハーネスが `static/vendor/` を読んでいない(意図的。41 設計解説 1-6)。
- 通常モードの `.log-box` の高さが手動モードの定義に引きずられる(42 設計解説5章。実害小)。
