# QTE 対戦アプリ — 引き継ぎ書 v58

最終更新: 2026-08-20。**Batch 51(火7枚 + 土8枚)完了。★これで P2 が終わった。**

| 項目 | 現在値 |
|---|---|
| JUnit | **353件 全緑** |
| verify | **533/533** |
| 効果の未実装 | **23枚**(進化18 + 非進化5) |
| 版数 | `manual-battle.js` v=33 / `battle.js` v=22 / `battle.css` v=46(**3つとも据え置き**) |
| 裁定 | 219番まで確定(次は **220** から) |

---

## ★この文書の構成(v56 から)

**ハンドオフ書は3つに割ってある。**

| 文書 | 役割 | 更新のしかた |
|---|---|---|
| **`notes/qte-rulings.md`** | 裁定1〜219 の**全文** | **追記専用。**新しい裁定を末尾に足すだけ |
| **`notes/qte-pitfalls.md`** | 既知の落とし穴(30節) | **追記・訂正のみ。**該当する節に足す |
| **本ファイル**(ハンドオフ) | 直近バッチの要点・確認項目・次の作業・開始テンプレート | 毎バッチ書き直す(**20KB 前後に収める**) |

★**ハンドオフ書に裁定の全文や落とし穴の全文を書き戻さないこと。**

---

## 0. 最初にやること

1. **`notes/ver11-migration-plan.md`** を読む(46〜55 の作業の正)。
   ★**バッチ番号は 49 で詰めてある**(P3 = 52〜53 / P4 = 54 / P5 = 55)。
2. **`notes/qte-pitfalls.md`** を読む。★作業対象に関係する節は必読。
   Batch 52(P3 = 進化)なら「サンドボックスの制約」「通常モードのゲームプレイ試験」
   「効果の実装状況・効果未実装の印」「対象指定(TargetSpec)の落とし穴」
   「デッキ構築」「進化スタック(手動モードの話)」「**火文明・土文明の Ver1.1(Batch 51)**」の7節。
3. **`notes/qte-rulings.md`** は**引く**もので、通しで読むものではない。
   ★**ただし P3 は例外** —— 進化の裁定154・157 は着手前に全文を読むこと。
4. 本ファイルと、直近バッチの design-notes を読む。
5. `qte-project-reference.md` を読む。★1章の実装状況は2026-07-21のまま古い(P5 で更新)。
6. ソースコード取得: `git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git`
7. **「反映済み」を信じず、直近バッチの変更箇所を実際に読んで照合する**(下の「51 の確認項目」)。

---

## ★カードデータの正(46b で確定)

- **`src/main/resources/cards/manual-cards.json`(Ver1.1・全235枚)が全モード共通の正。**
- **カードが実際に持つキーは10個だけ**:
  `id / name / type / civilization / cost / attack / hp / imageId / ledgerCardId / text`。
  **`keywords` と `unlimitedCopies` は存在しない**(落とし穴「消えたフィールド」)。
- **キーワードはテキストから作る。**`CardTextKeywords.extract(text)` が唯一の出どころ(裁定158)。
- 内訳: リーダー18 / ミニオン119 / **進化18** / スペル61 / ウェポン19。6文明×39 + 文明なし1。
- **進化18枚はデッキ構築で弾かれる**(裁定166)。**P3(52〜53)で解禁する。**
- 実装状況: **登録あり146 / 宣言あり46 / 未実装23**(`python3 tools/report_effects.py --summary`)。

---

## ★★51 が変えたこと(要点)

設計解説は **`notes/batch51-design-notes.md`**。

1. **火7枚 + 土8枚 = 15枚を実装した。**
   火: 支援盾機狸 / 乱戦鉄機狼 / 砲台鉄機虎 / ラスト・アタック / リペア・チューナー /
   アイアン・リターン / ドレイン・ブラスト。
   土: 百獣の王ベヒーモス / 地上覇総長・翔山(リーダー)/ 分那愚利 / 勝鼓美 /
   素手喧嘩 / 仏恥義理 / 喧嘩上等 / 俺等地上覇夜露死苦。
   ★見積もり(火5 + 土8 = 13)は**当たった**。増えた2枚は裁定215 による**スコープの拡大**である。
2. ★**マナと場を直接つなぐ2方向を作った**
   (`GameActions.putManaCardIntoField` / `putFieldMinionIntoManaFaceDown`)。
   どちらも召喚でも破壊でもないので【召喚時】【破壊時】は発動せず、出るほうは `ON_ENTER` のみ。
3. ★★**攻撃時の割り込みが出たら戦闘の解決を保留する**(裁定213)。
   `GameState.pendingAttack` を新設し、`attack` から `resolveCombat` を切り出した。
   ★**素手喧嘩専用の分岐は1つも無い** —— 「攻撃者が場に居るか」で決まる。
   ★**《地砕きの突撃兵》の挙動が変わった**(マナを選んでから戦闘になる)。
4. ★★★**`resolveChoice` がターンプレイヤーを要求しなくなった**(裁定214)。
   50 の設計解説 6-2 で先送りした工事。**選択待ちであること自体が根拠**である。
   ★対になる規則: `requireTurnPlayer` が**相手の** `pendingChoice` も見て手番の側を塞ぐ。
   ★**JavaScript は1行も変えていない**(クライアントは元から `myTurn` を見ていなかった)。
5. **「マナから場に出すミニオンを選ぶ」を `requestManaSummon` に集約した**(4枚が使う)。
6. ★**47 が置いた名指し試験(《百獣の王 ベヒーモス》)の陳腐化を片付けた**(裁定219)。
   50 の裁定209 をスペル以外にも広げ、**「送ると決めたもの」だけを名指しする**形に整理した。
7. **新しい裁定を10個確定した(210〜219)。**全文は `notes/qte-rulings.md`。
8. ★**壊し検証 26通り**(`tools/batch51_break_check.py`)。**26/26 OK。**

## 51 の確認項目(★これを照合する)

- **`GameActions.putManaCardIntoField`** がある / **満杯・踏み倒し禁止・ミニオンでないを
  `manaZone.remove` より前に見ている** / **`manaLeft` の発火が `putIntoFieldByEffect` の後**
- **`GameActions.putFieldMinionIntoManaFaceDown`** がある /
  **マナ上限で置けないとき `minionZone` から取り除いていない** / 禁忌由来は消滅ゾーンへ
- **`GameState.pendingAttack`** と **`game/PendingAttack.java`**(record)がある
- **`GameService.attack` が `resolveCombat` を呼ぶ形になっている** /
  攻撃時効果の後に `player.getPendingChoice() != null` を見て**保留して return** している /
  **`resolveCombat` の先頭に攻撃者・対象の在場判定が2つある**
- **`GameService.resumePendingAttack`** がある / `resolveChoice` の中で
  **`advanceTurnIfPending` より先に**呼ばれている
- ★**`GameService.resolveChoice` からターンプレイヤー判定が消えている**
- ★**`GameService.requireTurnPlayer` に「相手が選択中です」の判定が足されている**(★対の規則)
- **`RuleGuards`**: `SUPPORT_TANUKI` 定数 + 攻撃禁止の分岐 / `IMPLEMENTED_CARDS` に **SUPPORT_TANUKI**
- **`CardEffectRegistry`**: `registerFireVer11Cards()` / `registerEarthVer11Cards()` が
  コンストラクタから呼ばれている / `IRON_WOLF_LP_THRESHOLD` 定数 / `hasEvolutionOnAnyField` /
  `requestManaSummon`
- **`ResumePoint` に6つ増えている**(`BUCCHIGIRI_MANA_PUT` / `KACHIKOMI_MANA_SUMMON` /
  `STEGORO_TO_MANA` / `STEGORO_MANA_SUMMON` / `KENKAJOTO_MANA_SUMMON` / `SEKAIWO_MANA_SUMMON`)/
  `resolveChoice` の分岐も対応している
- ★**向きの限定が本文どおり**(裁定211): `SEKAIWO` と `STEGORO` だけが `mana.isFaceUp()` を見る。
  `KACHIKOMI` と `KENKAJOTO` は**見ない**
- **版数は3つとも据え置き**(`battle.js` v=22。JS を変えていない)
- **`FireEarthVer11EffectTest`(43件)** / `EffectImplementationTest` 14 → 17 = **JUnit 353件**
- **`EffectImplementationTest`**: 「23枚」「進化18 + 非進化5」/ 火7枚・土8枚を名指しする試験 /
  ★**`Batch51が後続に送った2枚には今も印が付く`** がある /
  ★**ベヒーモスを名指ししていた試験が `プリセットに載っていることは実装済みの根拠にならない` に
  置き換わっている**(裁定219)
- **verify 533/533** / `report_effects.py --summary` で **FIRE 4 / EARTH 4**
- `decks/batch51-fire-check-deck.json` / `decks/batch51-earth-check-deck.json`
  (どちらも `DeckValidator` を通ることを確認済み)
- `tools/batch51_break_check.py` がある(★`mvn test` で回している。`surefire:test` ではない)

★50 以前の確認項目は各版のハンドオフにある(50 = v57 / 49 = v56 / 48 = v55 / 47 = v54 /
46b = v53 / 46a = v52 / 24〜45 = v51)。

---

## 1. 次の作業(★Batch 52 = P3 の1本目・進化エンジン)

**P2 は完了した。**残る非進化5枚は、すべて P3・P4 が引き取る ——
【賢魂】3枚(闇の《グレイヴガールズファン》《スタンディングテント》・土の《勝阿外》= P4)、
進化に関わる2枚(火の《機神兵長茶爺》・光の《英術・スケアロック》= P3)。

| フェーズ | バッチ | 内容 |
|---|---|---|
| **P3 進化** | **52〜53** | 進化エンジン(裁定154・157)+ **18枚** + UI + **デッキ構築の解禁** + 機神兵長茶爺 + 英術・スケアロック |
| P4 賢魂 | 54 | 裁定152 の実装(アクション種別 + 手札UIの2導線 + **3枚**) |
| P5 仕上げ | 55 | プリセットの Ver1.1 版 / デッキメーカー JSON の受け入れ / E2E / **`qte-cards.json` の削除**(`LedgerCards` と台帳照合3件も同時に消す)/ **`unlimitedCopies` の死んだ分岐3箇所の掃除** / **裏向きマナと `fireManaPlaced` の非対称**(51 設計解説 6-2)/ `qte-project-reference` 更新 |

### ★P3 に着手する前に読むもの

1. **裁定154・157 の全文**(`notes/qte-rulings.md`)。進化の中核はこの2つである。
2. **`notes/qte-pitfalls.md` の「進化スタック(Batch 27)」節。**★あれは**手動モードの話**であり、
   通常モードのエンジンには進化が1行も無い。**流用できるのは考え方だけである。**
3. **裁定157 が「P3 で個別確認する」と名指しした細目**は、着手時にまとめて裁定を仰ぐこと ——
   素材が2体以上のときの引き継ぎ / 素材のタップ状態や受けているダメージの扱い /
   「そのターンの間」付きの付与の残り方。
4. ★**`TargetSpec.Filter` に値を足したら `battle.js` の2箇所にも足し、版数を上げる**(裁定195)。
   ★**P3 は 48〜51 と違って JavaScript を必ず触る**(進化の出し方の UI)。
   版数を上げたら `verify/build_harness.py` の置換文字列も同時に直すこと。

### 各バッチがやること(P2 から継続。P3 でも同じ)

1. 未実装の一覧を `python3 tools/report_effects.py`(--summary なし)で出し、実装する。
2. **本文が2通り以上に読めるカードは、実装で決めずに必ず裁定を仰ぐ**(裁定184)。
   ★**まとめて1〜2回で聞く。**51 では8つを2回に分けて出した。
3. **ルール側に実装したら、そのクラスの `IMPLEMENTED_CARDS` に足す**(裁定176)。
   ただし `CardEffectRegistry` の表への登録は**リテラルのまま**にする(ツールが左辺で数えるため)。
4. `EffectImplementationTest` の**枚数を減らし、その文明のカードを名指しする試験も足す**
   (裁定162・181)。★**「印が付く」側で名指ししてよいのは
   「意図して後続へ送ったカード」だけである**(裁定219)。
5. **カード毎の JUnit を `support/AutoGameFixture` の上に書く**(裁定187)。
   `FireEarthVer11EffectTest` が最新の雛形。**効果は本物の入口から起こす。**
   ★**足場の `giveMana` はマナにミニオンを置く。**マナを参照するカードを測るときは
   自前で `payMana` を持つこと(51 設計解説 4-1)。
6. **壊し検証は `tools/batch51_break_check.py` をコピーして書く。**
   ★**`mvn test` で回すこと**(`surefire:test` はコンパイルしない。裁定208)。
7. 成果物の書き方は下の「運用ルール」に従う。

### ★★運用ルール(2026-08-19 にマスターと決めた。v56 から継続)

**削るもの:**

- **ハンドオフ書は 20KB 前後に収める。**裁定と落とし穴の全文を書き戻さない。
- **設計解説は「新しい判断」だけを厚く書く。**既存カードの写しで済むものは**表に1行**でよい。
  ★**ただし裁定の根拠と「なぜそう決めたか」は今までどおり全部残す。**目安 12KB 前後。
  ★**51 はエンジンの工事が3つあったので、そちらの説明に紙面を寄せた**(50 とほぼ同じ量で、
  カード15枚のうち10枚は表の1行だけにしてある)。カードが多い回とは配分が違う。
- **壊し検証は新しい仕組みに集中する。**既存の形に乗っただけのカードは**1通りで足りる**。
  目安 10〜25通り(49 は23、50 は19、51 は26)。

**削らないもの(削るとかえって高くつく):**

- **裁定を仰ぐこと(184)。**★**51 の3つの工事は、どれもマスターの裁定が要求したものである。**
  実装で決めていたら「自動決定にする」「戦闘は普通に起こす」で済み、遊びが本文と違うものになっていた。
- **本物の入口からの JUnit(187)。**P2 で積み上がった唯一の資産である。
- **機械チェック(`tools/` と `verify/`)。**実行は数分で、実際に事故を止めている。

### 完了済みバッチ

17a〜23 / 24〜31 / 32設計・32a〜32c / 33〜45 /
46a(Ver1.1 移行の土台)/ 46b(カードマスタの移行)/ 47(効果未実装の印)/
48(風8枚)/ 49(水6枚)/ 50(闇6 + 光6)/ **51(火7 + 土8。★P2 完了)**

### 保留中

- **作り直し69〜80枚**(本文が Ver1.1 で変わった実装済みカード)。48〜51 では範囲外。
  P5 の手前で拾うバッチが要る。
- **裏向きでマナに置く経路が `fireManaPlaced` を発火しない**(51 設計解説 6-2)。
  13a から続く非対称で、**現状の実装のほうが本文から外れている可能性が高い。**
  ★裁定を仰いでいない。P5 で拾うこと。
- **攻撃の経路に、現行のカードプールでは到達しない防御的な分岐が2つある**(51 設計解説 6-1)。
- 印を**デッキメーカー**にも出すか(47 で見送り)/ 音声素材の差し替え(中断中)/
  部屋の永続化(優先順位10)/ タブレット対応(優先順位11)/
  デッキメーカーの名前付きスロット(40 Q2 = b。未着手)。
- ★**「相手のターンに本人へ問い合わせる仕組みが無い」は 51 で解消した**(裁定214)。

---

## 2. 発注者とのやりとり

- grep 優先でファイルを渡り歩き、全体読み込みは避ける。
- **判断に迷う点はまとめて質問する。**1つずつ聞かない。
- **本文が2通り以上に読めるカードは、実装で決めずに必ず裁定を仰ぐ**(裁定184)。
- 呼び方は「クロエ」、発注者は「マスター」。
  会話は日本語カジュアル体、**ドキュメントは通常文体(である調)。**

---

## 3. 既知の落とし穴

**★全文は `notes/qte-pitfalls.md`(30節)にある。**ここには置かない。

Batch 52(P3 = 進化)で特に効くのは次の7節である。

| 節 | なぜ効くか |
|---|---|
| サンドボックスの制約 | m2repo.zip / 日本語テスト名が選べない / Jackson が無い |
| 通常モードのゲームプレイ試験 | `AutoGameFixture` の使い方。★`giveMana` はマナにミニオンを置く |
| 効果の実装状況・「効果未実装」の印 | `IMPLEMENTED_CARDS` の足し方 |
| 対象指定(TargetSpec)の落とし穴 | `Filter` を足したら `battle.js` にも足す。★P3 は JS を必ず触る |
| デッキ構築(Batch 47 で変わった) | ★**進化の解禁はここ**(`requireDeckable`。裁定166) |
| 進化スタック(Batch 27) | ★**手動モードの話。**流用できるのは考え方だけである |
| **火文明・土文明の Ver1.1(Batch 51)** | ★**マナ⇄場の入口 / 攻撃の保留 / 相手ターンの選択** |

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
QTE Battle の開発を継続する。Batch 52(P3 の1本目・進化エンジン)を行う。

読む順:
1. プロジェクトナレッジ内の `notes/ver11-migration-plan.md`(46〜55 の作業の正)
2. プロジェクトナレッジ内の `claude/qte-handoff-v58.md`
   ★裁定の全文は `notes/qte-rulings.md`、既知の落とし穴は `notes/qte-pitfalls.md` にあり、
   ハンドオフ書には要点しかない。
3. ★**裁定154・157 の全文**(進化の中核。P3 はここだけ通読すること)
4. `notes/qte-pitfalls.md` の7節(v58 の3章に一覧がある)
5. `notes/batch51-design-notes.md`(エンジン工事のやり方の雛形)

環境:
6. git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git
7. 接続フォルダの m2repo.zip を device_stage_files で取り込んで展開し、
   mvn -o -B "-Dmaven.repo.local=/root/m2work/repository" test を回す
   (手順は pitfalls の「サンドボックスの制約」)。
   ★353件全緑が出発点である。緑でなければ止めて報告する。
8. 51 の反映を v58 の「51 の確認項目」で照合し、verify 533/533 を確認する。
   反映されていなければ止めて報告する。

作業:
9. v58 の1章「次の作業」に従う。
   ★P3 は進化エンジン + 18枚 + UI + デッキ構築の解禁である。2バッチ(52〜53)に割ってよい。
   ★裁定157 が「P3 で個別確認する」と名指しした細目は、着手時にまとめて裁定を仰ぐこと。
   ★同章の「運用ルール」に、成果物の分量と壊し検証の絞り方が書いてある。
10. 納品は v58 の4章のとおり。ドキュメントはチャットにファイルとして添付する。

呼び方は「クロエ」、こちらの呼び方は「マスター」で。会話は日本語カジュアル体、
ドキュメントは通常文体(である調)で。
```

## 6. 51 完了時点の積み残し

### マスターにお願いすること(★51 分)

- **Eclipse で refresh(F5)→ Run As → JUnit Test。**期待: **全353件が緑**。
- **実機確認。★JS も CSS も変えていないのでキャッシュクリアは不要。**
  添付の **`decks/batch51-fire-check-deck.json`**(リーダー = 剛火の将)と
  **`decks/batch51-earth-check-deck.json`**(リーダー = 地上覇総長・翔山)で1試合ずつ。
  見てほしい16点は **51 設計解説の 7-2** にある(ここには写さない)。
  ★とくに大事なのは次の3つである ——
  1. **《勝鼓美》が相手のターンに破壊されても、あなたに選択画面が出る**(裁定214)。
  2. **《素手喧嘩》はマナに置くと戦闘が起きず、置かなければ普通に殴る**(裁定213)。
  3. **《地砕きの突撃兵》の順序が変わった**(マナを選んでから戦闘)。違和感がないか。
- 問題なければ **自分で git commit / push**。

### 継続中の積み残し

- **作り直し(Ver1.1 で本文が変わった実装済みカード)は手つかず**(48〜51 で範囲外)。
- **`unlimitedCopies` に乗ったままの死んだ分岐が3箇所**(49 設計解説 6-2)。**無害。**P5 で掃除。
- **裏向きマナと `fireManaPlaced` の非対称**(51 設計解説 6-2)。★裁定を仰いでいない。P5。
- **35〜50 の実機確認が未報告のまま**(46b と 48 は報告あり)。
- **演舞の墓守の2本目の発火位置(`summonFromGrave`)は現行のカードプールでは到達しない**
  (50 設計解説 6-1)。★**51 も同じ性質の分岐を2つ増やした**(51 設計解説 6-1)。
- 印をデッキメーカーにも出すか(47 で見送り)/ 部分実装は印で表せない(裁定165)。
- `qte-cards.json` の削除(P5)。`support/LedgerCards` と台帳照合3件も同時に。
- 新カード66枚のテキストの目視校正(46a からの持ち越し)。
- `qte-project-reference.md` の1章が古い(2026-07-21)。P5 で更新。
- 手動モード関連の積み残しは**一時停止中**。再開時は v47 の6章。
- ハーネスが `static/vendor/` を読んでいない(意図的。41 設計解説 1-6)。
- 通常モードの `.log-box` の高さが手動モードの定義に引きずられる(42 設計解説5章。実害小)。
