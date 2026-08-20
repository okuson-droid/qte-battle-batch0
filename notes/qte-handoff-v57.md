# QTE 対戦アプリ — 引き継ぎ書 v57

最終更新: 2026-08-19。**Batch 50(闇6枚 + 光6枚)完了**。P2 の3本目で、**初の2文明バッチ**。

| 項目 | 現在値 |
|---|---|
| JUnit | **307件 全緑** |
| verify | **533/533** |
| 効果の未実装 | **38枚**(進化18 + 非進化20) |
| 版数 | `manual-battle.js` v=33 / `battle.js` v=22 / `battle.css` v=46(**3つとも据え置き**) |
| 裁定 | 209番まで確定(次は **210** から) |

---

## ★この文書の構成(v56 から)

**ハンドオフ書は3つに割ってある。**

| 文書 | 役割 | 更新のしかた |
|---|---|---|
| **`notes/qte-rulings.md`** | 裁定1〜209 の**全文** | **追記専用。**新しい裁定を末尾に足すだけ |
| **`notes/qte-pitfalls.md`** | 既知の落とし穴(29節) | **追記・訂正のみ。**該当する節に足す |
| **本ファイル**(ハンドオフ) | 直近バッチの要点・確認項目・次の作業・開始テンプレート | 毎バッチ書き直す(**20KB 前後に収める**) |

★**ハンドオフ書に裁定の全文や落とし穴の全文を書き戻さないこと。**
番号と一行要約、または節の名前だけを書き、正は上の2ファイルに置く。

★**v56 の分割は効いた。**v57 は書き直しの手間がはっきり減っている ——
裁定12件と落とし穴1節は追記だけで済み、本ファイルに写す必要が無かった。

---

## 0. 最初にやること

1. **`notes/ver11-migration-plan.md`** を読む(46〜55 の作業の正)。
   ★**バッチ番号は 49 で詰めてある**(P3 = 52〜53 / P4 = 54 / P5 = 55)。
2. **`notes/qte-pitfalls.md`** を読む。★作業対象に関係する節は必読。
   Batch 51 なら「サンドボックスの制約」「通常モードのゲームプレイ試験」
   「効果の実装状況・効果未実装の印」「対象指定(TargetSpec)の落とし穴」
   「闇文明・光文明の Ver1.1(Batch 50)」の5節。
3. **`notes/qte-rulings.md`** は**引く**もので、通しで読むものではない。
   実装中に番号が出てきたら引く。
4. 本ファイルと、直近バッチの design-notes を読む。
5. `qte-project-reference.md` を読む。★1章の実装状況は2026-07-21のまま古い(P5 で更新)。
6. ソースコード取得: `git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git`
7. **「反映済み」を信じず、直近バッチの変更箇所を実際に読んで照合する**(下の「50 の確認項目」)。

---

## ★カードデータの正(46b で確定)

- **`src/main/resources/cards/manual-cards.json`(Ver1.1・全235枚)が全モード共通の正。**
  通常モード・手動モード・デッキメーカーの3つとも同じファイルを読む(裁定D1 の案B)。
- **カードが実際に持つキーは10個だけ**:
  `id / name / type / civilization / cost / attack / hp / imageId / ledgerCardId / text`。
  **`keywords` と `unlimitedCopies` は存在しない**(落とし穴「消えたフィールド」)。
- **キーワードはテキストから作る。**`CardTextKeywords.extract(text)` が唯一の出どころ(裁定158)。
- 内訳: リーダー18 / ミニオン119 / **進化18** / スペル61 / ウェポン19。6文明×39 + 文明なし1。
- **進化18枚はデッキ構築で弾かれる**(裁定166)。P3(52〜53)で解禁する。
- **進化以外は全部デッキに入れられる。**未実装なら盤面に印が出る(裁定D2)。
- 実装状況: **登録あり131 / 宣言あり46 / 未実装38**(`python3 tools/report_effects.py --summary`)。

---

## ★★50 が変えたこと(要点)

設計解説は **`notes/batch50-design-notes.md`**。

1. **闇6枚 + 光6枚 = 12枚を実装した。**
   闇: 演舞の墓守(リーダー)/ デビルズマイク / サモンズライト / カムバックキーパー /
   ダークネオンステージ / 1stL「NEMれぬ夜のドリーミー」。
   光: 英皇アントマルエル(リーダー)/ 光霊・テングスン / 光霊・ネフラ / 光霊・モアニール /
   英術・グラーニス / 英術・バンユー。
   ★**引き継ぎ(v56)の見積もりは「闇6 + 光5 = 11枚」だったが、実際は12枚だった。**
2. **「場以外から自分の墓地へ」の入口を1本作った**(`GameActions.putIntoTrashFromElsewhere`)。
   ★**`TriggerType` は足していない**(裁定206)。カムバックキーパーが反応するのは
   **墓地に居る間**であり、`fire()` は場のミニオンしか引かないため<b>できない</b>。
3. **「墓地から場へ」の発火口を作り、経路も1本に集約した**
   (`CardEffectRegistry.fireMinionEnteredFromGrave` / `GameActions.reviveFromGrave`)。
4. **`reviveFromGrave` が `MinionInstance` を返すようになった**(boolean から変更)。
   ついでに「場の末尾を取る」形を3箇所やめた。★**降臨の伝道師の実害あるバグを1件直した。**
5. **光霊・モアニールの2つの置換を足した**(登場 → 山札の下 / リーダーへのダメージの肩代わり)。
   判定は `RuleGuards`、実行は `GameActions` / `GameService`。
6. **`PlayerState` に「場全体の攻撃回数」と「その制限」を足した**(英術・バンユー。裁定200)。
7. **`EffectImplementationTest` の「未実装のスペル」の試験から、カードの名指しをやめた**(裁定209)。
8. **新しい裁定を12個確定した(198〜209)。**全文は `notes/qte-rulings.md`。
9. ★**壊し検証をスクリプトとして残した**(`tools/batch50_break_check.py`。19通り全部 OK)。

## 50 の確認項目(★これを照合する)

- **`GameActions.putIntoTrashFromElsewhere`** がある / 呼び出しが**13箇所**ある
  (`GameActions` 3箇所 = `drawCards` の置換 / `mill` / `destroyFaceDownMana`。
  手札 discard 7箇所。`resolveChoice` 3箇所)/
  ★**`sendToTrashOrRestore` は通っていない**(場を離れる経路と混ざっていないこと)
- **`CardEffectRegistry.fireCardPutIntoTrashFromElsewhere`** が
  **`putCardId` を引数で受けてカムバックキーパーだけを見る**(裁定203)
- **`CardEffectRegistry.fireMinionEnteredFromGrave`** がある / 発火が**2箇所**
  (`GameActions.reviveFromGrave` / `GameService.summonFromGrave`)/
  修正が **`Duration.THIS_TURN`** である
- **`GameActions.reviveFromGrave` の戻り値が `MinionInstance`** / 呼び出し8箇所がすべて
  `!= null` になっている / **黄泉還る水龍・ゾンストライカーもこの経路を通る**
- **`zone.get(zone.size() - 1)` が本番コードに残っていない**(3箇所とも戻り値に置換済み)
- **`RuleGuards`**: `MOANIRU` 定数 + `isEntryToDeckBottom` + `leaderDamageInterceptor` +
  バンユーの攻撃制限の分岐 / `IMPLEMENTED_CARDS` に **MOANIRU**
- **モアニールの置換が5箇所**: 登場2(`summonToField` / `putIntoFieldByEffect`)+
  ダメージ3(`damageLeader` / `attack` / `leaderAttack`)★**片方だけ直っていないか**
- **神の福音・ギガマウス・バイトが `isMinionZoneFull()` を呼ぶ前に見ている**
  (`null` を手札に戻すとカードが2枚に増える)
- **`PlayerState`**: `minionAttackLimitedOnTurn` / `minionAttacksUsedThisTurn` の**2本** /
  ★**`startTurnReset()` が戻すのは回数のほうだけ**(制限は刻印なので戻さない)
- **`StatCalculator`**: `TENGSUN` / `DREAMY` 定数 + `IMPLEMENTED_CARDS` に26枚 /
  テングスンが **`state.opponentOf(owner)`** を数える
- **`CardEffectRegistry`**: `GRAVE_DANCER_LEADER` / `COMEBACK_KEEPER` / `ANTOMARUEL_LEADER` +
  `IMPLEMENTED_CARDS` に9枚 / `registerDarkVer11Cards()` / `registerLightVer11Cards()`
- **`fireAnyMinionEntered` にアントマルエルが相乗りしている**(★ターン刻印を使っていないこと)
- **版数は3つとも据え置き**(`battle.js` v=22。JS を変えていない)
- **`DarkLightVer11EffectTest`(44件)** / `EffectImplementationTest` 11 → 14 = **JUnit 307件**
- **`EffectImplementationTest`**: 「38枚」「進化18 + 非進化20」/ 闇6枚・光6枚を名指しする試験 /
  「Batch50が足した5枚は表ではなく宣言で実装済み」/
  ★「効果が未実装のスペル」の試験が**カードを名指ししない形に変わっている**
- **verify 533/533** / `report_effects.py --summary` で **DARK 5 / LIGHT 4**
- `decks/batch50-dark-check-deck.json` / `decks/batch50-light-check-deck.json`
  (どちらも `DeckValidator` を通ることを確認済み)
- `tools/batch50_break_check.py` がある(★`mvn test` で回している。`surefire:test` ではない)

★49 以前の確認項目は各版のハンドオフにある(49 = v56 / 48 = v55 / 47 = v54 / 46b = v53 /
46a = v52 / 24〜45 = v51)。

---

## 1. 次の作業(★Batch 51 = P2 の最後)

| バッチ | 文明 | 今すぐ実装できる枚数 | 後送り |
|---|---|---|---|
| **51** | **火 + 土** | **13枚の見込み**(火5 + 土8。★要確認) | 火3(進化を参照 → P3)/ 土1(【賢魂】→ P4)/ 進化3+3 |

★**枚数は必ず本文を読んで数え直すこと。**v56 は 50 を「11枚」と見込んで実際は12枚だった。
`python3 tools/report_effects.py`(--summary なし)で **FIRE 11 / EARTH 12** の内訳が出る。
進化3枚ずつを除くと 火8 / 土9 で、そこから v56 の見込み
(火3が進化を参照・土1が【賢魂】)を引くと 火5 + 土8 = 13 になる。
★**この見込み自体が外れうる。**本文を読んで、P3・P4 送りが本当にその枚数かを確かめること。

### 各バッチがやること

1. 未実装の一覧を `python3 tools/report_effects.py`(--summary なし)で出し、実装する。
2. **本文が2通り以上に読めるカードは、実装で決めずに必ず裁定を仰ぐ**(裁定184)。
   ★**2文明ぶんをまとめて1回で聞く。**50 では7枚ぶんを2回の質問にまとめた。
3. **ルール側に実装したら、そのクラスの `IMPLEMENTED_CARDS` に足す**(裁定176)。
   ただし `CardEffectRegistry` の表への登録は**リテラルのまま**にする(ツールが左辺で数えるため)。
4. `EffectImplementationTest` の**枚数を減らし、その文明のカードを名指しする試験も足す**
   (裁定162・181)。★**「印が付く」側の題材はもう名指ししていない**(裁定209)。
5. **カード毎の JUnit を `support/AutoGameFixture` の上に書く**(裁定187)。
   `DarkLightVer11EffectTest` が最新の雛形。**効果は本物の入口から起こす。**
6. **`TargetSpec.Filter` に値を足したら `battle.js` の2箇所にも足し、版数を上げる**(裁定195)。
7. **壊し検証は `tools/batch50_break_check.py` をコピーして書く。**
   ★**`mvn test` で回すこと**(`surefire:test` はコンパイルしない。裁定208)。
8. 成果物の書き方は下の「運用ルール」に従う。

### ★★運用ルール(2026-08-19 にマスターと決めた。v56 から継続)

**削るもの:**

- **ハンドオフ書は 20KB 前後に収める。**裁定と落とし穴の全文を書き戻さない。
- **設計解説は「新しい判断」だけを厚く書く。**既存カードの写しで済むものは**表に1行**でよい。
  ★**ただし裁定の根拠と「なぜそう決めたか」は今までどおり全部残す。**目安 12KB 前後。
- **壊し検証は新しい仕組みに集中する。**既存の形に乗っただけのカードは**1通りで足りる**。
  目安 10〜20通り(49 は23、50 は19)。

**削らないもの(削るとかえって高くつく):**

- **裁定を仰ぐこと(184)。**実装で勝手に決めると、後で全部やり直しになる。
- **本物の入口からの JUnit(187)。**P2 で積み上がっている唯一の資産である。
- **機械チェック(`tools/` と `verify/`)。**実行は数分で、実際に事故を止めている。

### その先

| フェーズ | バッチ | 内容 |
|---|---|---|
| P3 進化 | 52〜53 | 進化エンジン(裁定154・157)+ 18枚 + UI + **デッキ構築の解禁** + 英術・スケアロック |
| P4 賢魂 | 54 | 裁定152 の実装(アクション種別 + 手札UIの2導線 + 7枚) |
| P5 仕上げ | 55 | プリセットの Ver1.1 版 / デッキメーカー JSON の受け入れ / E2E /
  **`qte-cards.json` の削除**(`LedgerCards` と台帳照合3件も同時に消す)/
  **`unlimitedCopies` の死んだ分岐3箇所の掃除**(49 設計解説 6-2)/ `qte-project-reference` 更新 |

### 完了済みバッチ

17a〜23 / 24〜31 / 32設計・32a〜32c / 33〜45 /
46a(Ver1.1 移行の土台)/ 46b(カードマスタの移行)/ 47(効果未実装の印)/
48(風文明8枚)/ 49(水文明6枚)/ **50(闇6枚 + 光6枚。初の2文明バッチ)**

### 保留中

- **作り直し69〜80枚**(本文が Ver1.1 で変わった実装済みカード)。48〜50 では範囲外。
  P5 の手前で拾うバッチが要る。
- **相手のターンに本人へ問い合わせる仕組みが無い**(50 設計解説 6-2)。
  `GameService.resolveChoice` はターンプレイヤーしか受け付けないため、
  相手ターンに発火する効果は自動決定(`AutoChoice`)にするしかない。P4 以降で必要になったら作る。
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

**★全文は `notes/qte-pitfalls.md`(29節)にある。**ここには置かない。

Batch 51 で特に効くのは次の5節である。

| 節 | なぜ効くか |
|---|---|
| サンドボックスの制約 | m2repo.zip / 日本語テスト名が選べない / Jackson が無い |
| 通常モードのゲームプレイ試験 | `AutoGameFixture` の使い方。`endTurn` の後は `nextPhase` が要る |
| 効果の実装状況・「効果未実装」の印 | `IMPLEMENTED_CARDS` の足し方。ここを外すと動くカードに印が付く |
| 対象指定(TargetSpec)の落とし穴 | `Filter` を足したら `battle.js` にも足す |
| **闇文明・光文明の Ver1.1(Batch 50)** | ★**墓地の入口が2本ある**。`getTrash().add(...)` を直書きしない |

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
QTE Battle の開発を継続する。Batch 51(P2 の最後・火文明 + 土文明)を行う。

読む順:
1. プロジェクトナレッジ内の `notes/ver11-migration-plan.md`(46〜55 の作業の正)
2. プロジェクトナレッジ内の `claude/qte-handoff-v57.md`
   ★裁定の全文は `notes/qte-rulings.md`、既知の落とし穴は `notes/qte-pitfalls.md` にあり、
   ハンドオフ書には要点しかない。
3. `notes/qte-pitfalls.md` の5節(v57 の3章に一覧がある)
   ★`notes/qte-rulings.md` は通読しない。番号が出てきたら引く。
4. `notes/batch50-design-notes.md`(2文明バッチのやり方の雛形)

環境:
5. git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git
6. 接続フォルダの m2repo.zip を device_stage_files で取り込んで展開し、
   mvn -o -B "-Dmaven.repo.local=/root/m2work/repository" test を回す
   (手順は pitfalls の「サンドボックスの制約」)。
   ★307件全緑が出発点である。緑でなければ止めて報告する。
7. 50 の反映を v57 の「50 の確認項目」で照合し、verify 533/533 を確認する。
   反映されていなければ止めて報告する。

作業:
8. v57 の1章「次の作業」に従う。
   ★火と土の2文明を1バッチで実装する(枚数は本文を読んで数え直すこと)。
   ★同章の「運用ルール」に、成果物の分量と壊し検証の絞り方が書いてある。
9. 納品は v57 の4章のとおり。ドキュメントはチャットにファイルとして添付する。

呼び方は「クロエ」、こちらの呼び方は「マスター」で。会話は日本語カジュアル体、
ドキュメントは通常文体(である調)で。
```

## 6. 50 完了時点の積み残し

### マスターにお願いすること(★50 分)

- **Eclipse で refresh(F5)→ Run As → JUnit Test。**期待: **全307件が緑**。
- **実機確認。★JS も CSS も変えていないのでキャッシュクリアは不要。**
  添付の **`decks/batch50-dark-check-deck.json`**(リーダー = 演舞の墓守)と
  **`decks/batch50-light-check-deck.json`**(リーダー = 英皇アントマルエル)で1試合ずつ。
  見てほしい12点は **50 設計解説の 7-2** にある(ここには写さない)。
  ★とくに大事なのは **《カムバックキーパー》が「捨てられたら戻る・破壊されたら戻らない」**の
  2つが両方成り立っていることである。
- 問題なければ **自分で git commit / push**。

### 継続中の積み残し

- **作り直し(Ver1.1 で本文が変わった実装済みカード)は手つかず**(48〜50 で範囲外)。
- **`unlimitedCopies` に乗ったままの死んだ分岐が3箇所**(49 設計解説 6-2)。**無害。**P5 で掃除。
- **35〜49 の実機確認が未報告のまま**(46b と 48 は報告あり。35〜45・47・49 は未報告)。
- **演舞の墓守の2本目の発火位置(`summonFromGrave`)は現行のカードプールでは到達せず、
  試験もできていない**(50 設計解説 6-1)。
- **相手のターンに本人へ問い合わせる仕組みが無い**(50 設計解説 6-2)。
- 印をデッキメーカーにも出すか(47 で見送り)/ 部分実装は印で表せない(裁定165)。
- `qte-cards.json` の削除(P5)。`support/LedgerCards` と台帳照合3件も同時に。
- 新カード66枚のテキストの目視校正(46a からの持ち越し)。
- `qte-project-reference.md` の1章が古い(2026-07-21)。P5 で更新。
- 手動モード関連の積み残しは**一時停止中**。再開時は v47 の6章。
- ハーネスが `static/vendor/` を読んでいない(意図的。41 設計解説 1-6)。
- 通常モードの `.log-box` の高さが手動モードの定義に引きずられる(42 設計解説5章。実害小)。
