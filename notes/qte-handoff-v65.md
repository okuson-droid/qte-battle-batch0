# QTE 対戦アプリ — 引き継ぎ書 v65

最終更新: 2026-08-21。**Batch 58(作り直し③ = 区分5「ほぼ書き直し」のうち裁定を要さない8枚)を実施。**
裁定268〜274 待ちの7枚には着手していない(裁定184)。

| 項目 | 現在値 |
|---|---|
| JUnit | **571件 全緑**(549 → +22。`Batch58ReworkTest` を新設) |
| verify | **543/543**(据え置き) |
| 効果の未実装 | ★**0枚**(剛火の将の常在効果を実装した。55 以来3バッチぶりに0へ戻った) |
| 版数 | `manual-battle.js` v=33 / `battle.js` v=25 / `battle.css` v=46(**据え置き**) |
| 裁定 | **259番まで確定** / ★**260〜275(16件)+ 新規276(1件)を依頼中・未確定** |
| 作り直しの消化 | 121枚中 **105枚**(区分3b 20/27・区分4 19/21・区分5 **8/15**) |
| 壊し検証 | `tools/batch58_break_check.py` 19ケース **OK 19 / NG 0** |

---

## ★この文書の構成(v56 から継続)

| 文書 | 役割 | 更新のしかた |
|---|---|---|
| `notes/qte-rulings.md` | 裁定1〜259 の全文 | 追記専用 |
| `notes/qte-pitfalls.md` | 既知の落とし穴 | 追記・訂正のみ |
| 本ファイル(ハンドオフ) | 直近バッチの要点・次の作業 | 毎バッチ書き直す |

---

## 0. 最初にやること

1. `notes/ver11-migration-plan.md` を読む。
2. **`notes/rework-triage.md` を読む。**作業範囲の正である。★4-1 章に消化の状況がある。
3. `notes/qte-pitfalls.md` の該当節を読む(3章に一覧)。
4. 本ファイルと **`notes/batch58-design-notes.md`** を読む。
5. **`notes/batch55-ruling-requests.md` を読み、マスターの回答が揃っているか確認する。**
   ★**260〜275 に加えて、58 で新しく 276 が増えた。合計17件である。**
6. ソースコード取得: `git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git`
7. **「反映済み」を信じず、58 の変更箇所を実際に読んで照合する**(下の「58 の確認項目」)。

---

## ★カードデータの正(変更なし)

- `src/main/resources/cards/manual-cards.json`(Ver1.1・全235枚)が全モード共通の正。
- キーワードはテキストから作る(`CardTextKeywords.extract`。裁定158)。
- `qte-cards.json`(Ver0.4の台帳)は区分5が終わるまで消さないこと。

---

## ★★58 がやったこと(要点)

設計解説は **`notes/batch58-design-notes.md`**(新規ファイル)。

1. **剛火の将**(火・L): 起動能力が消えた跡地に、**常在の「【速攻】を持つカードのHP+2」**を実装。
   規則の正は `StatCalculator.rushHpBonus`、加算は `MinionInstance.getMaxHp`。
   ★**「自分の」と書いていないので両者の場に効く**(裁定156(2))。
   ★両者のリーダーが剛火の将なら **+4** に累積する。
   ★`pendingFireMinionDiscount` 関連の死んだコードを4箇所から掃除した。
   ★**「効果未実装」の印がこれで0枚に戻った。**
2. **背水の烈火使い**(火): 【召喚時】手札全捨てが**丸ごと消えた**。素の4コスト3/5【守護】になった。
   ★**登録を消すのが実装である。**
3. **英知の継承者**(水): 任意の捨て→1ドロー が **4枚引いて3枚捨てる**(必須)に。
   引いた後に選ばせるので、`TargetSpec` から**割り込み(a9)へ移した**(《海淵獣ラカブ》と同じ形)。
4. **知恵の双翼**(水): ★**実装変更なし。**記法だけの差であり、
   **区分5 ではなく区分2 であった**(《死者蘇生》《ガイア・ハンマー》に続く3例目)。
5. **ストーム・カイザー**(風): 条件 4→**5**枚・代替コスト 0→**1**・**【速攻】**が付いた。
   `SpecialSummonSpec` は元から `mpCost` を持っており、新しい仕組みは要らなかった。
6. **風弾の跳弾**(風): 自分側が**バウンス → 破壊**、ダメージ 2→**3**、追加コスト +3→**+2**。
   ★**本文の順序は入れ替わったが、解決の順序は変わらない**(追加コストは支払いより前に確定する)。
7. **カース・ボーン**(闇): 裏向きマナ生成 → **自分のミニオン1体を破壊し、そのコスト分セルフミル**。
   ★候補には**自分自身が含まれる**(割り込みで問い合わせるため)。
   ★【還元】が付いたので、自分を破壊した場合は墓地ではなく**裏向きでマナへ**行く。
   ★闇の**裏向きマナを作る起点が消えた** —— 闇文明全体の遊び味に効く。
8. **地脈の覚醒**(土): 空欄 → **自分のマナから1枚を手札へ + ターン1回制限**。実質の新規実装。
   ★ターン1回制限は**「発動」だけを止める**(2枚目も使用でき、【還元】だけを残す)。
   この読みは**裁定276 として確認を依頼中**だが、本文どおりの側で実装済みなので作業は止まっていない。

### ★構造の手当て2件

1. **場に出るミニオンの実体を作る入口を1本にした**(`GameActions.newFieldMinion`)。
   `new MinionInstance(...)` は召喚と効果による登場の2箇所にあり、
   常在の加算量を写し忘れないためには作る場所を1つにするしかない(裁定163)。
2. **`registerWindCards()` を分割した**(`registerWindSpellsAndWeapons()`)。
   《風弾の跳弾》の書き換えで330行になり `check_structure.py` が△を出したため。
   **中身は1行も動かしていない**(Batch 57 の闇文明と同じ処置)。

## 58 の確認項目(★これを照合する)

- **`StatCalculator.java`**: `FIRE_GENERAL` 定数・`rushHpBonus(GameState)` が新設され、
  `IMPLEMENTED_CARDS` に `FIRE_GENERAL` が入っている。
  `effectiveCost` の先頭にあった火文明ミニオンの割引が**削除**され、削除理由が残っている
- **`MinionInstance.java`**: `rushHpBonus` フィールドが新設され、`getMaxHp` が
  **`hasKeyword(Keyword.HASTE)`**(RUSH ではない)を見て加算している
- **`GameActions.java`**: `newFieldMinion` が新設され、`StatCalculator` を1つ持っている
- **`GameService.java`**: `summonToField` が `actions.newFieldMinion` を呼び、
  `playMinion` の割引の消費が**削除**されている
- **`PlayerState.java`**: `pendingFireMinionDiscount` が**削除**され、
  `tryUseLeylineAwakening(int)` と `leylineAwakeningTurn` が新設されている
- **`ResumePoint.java`**: `WISDOM_HEIR_DISCARD` / `CURSE_BONE_SACRIFICE` /
  `LEYLINE_AWAKENING_TO_HAND` の3つが増えている
- **`CardEffectRegistry.java`**: `QTE-M-FIRE-7` の登録が**無い**(削除が実装である)。
  `QTE-M-WATER-19`・`QTE-M-WIND-8`・`QTE-M-WIND-24`・`QTE-M-DARK-2`・`QTE-M-EARTH-27` に
  `★Batch 58` のコメントがあり、新本文どおりの実装になっている。
  `resolveCurseBoneSacrifice` が新設され、`registerWindSpellsAndWeapons()` に分割されている
- **`src/test/java/.../Batch58ReworkTest.java`**(新規・22件): それぞれ「旧:」「新:」のコメント付き
- **`EffectImplementationTest.java`**: `効果未実装のカードは1枚も無い` に書き換わっている
- **`support/AutoGameFixture.java`**: `putOnField` が `rushHpBonus` を写している
- **`tools/batch58_break_check.py`**(新規・19ケース): **OK 19 / NG 0**
- JUnit **571件全緑** / verify **543/543**
- `python3 tools/check_structure.py` / `check_all.py` / `check_records.py`
  (★既知の誤検出3件。57 から変化なし)/ `check_undeclared.py` / `node --check` 2種 いずれも異常なし
- `python3 tools/rework_triage.py --check` / `check_leader_abilities.py` /
  `build_id_map.py --check` いずれも OK
- `python3 tools/report_effects.py --summary` で **未実装 0枚**
- ★**JS は1行も触っていない**。版数も据え置き。盤面の見た目・操作感は無変化

---

## 1. 次の作業(Batch 59 = 裁定が付いた16枚)

**作業範囲の正は `notes/rework-triage.md` である。**ここには要点だけを置く。

| フェーズ | バッチ | 内容 | 枚数 |
|---|---|---|---|
| P5 作り直し | 55(完了) | 棚卸し | — |
| P5 作り直し | 56 前半(完了) | 火3・水4・風7・光9 | 23 |
| P5 作り直し | 57(完了) | 闇9・土8 | 17 |
| **P5 作り直し** | **58(完了)** | **区分5 のうち裁定を要さない8枚** | **8** |
| **P5 作り直し** | **59(次)** | **裁定が付いた16枚**(3b 7 + 4 の2 + 5 の7) | **16** |
| P6 仕上げ | 60 | 旧P5の項目(プリセット Ver1.1 化・`qte-cards.json` 削除ほか) | — |

★**v64 で「59 = P6 仕上げ」としていたものを 60 へ1つ後ろにずらした。**
58 が裁定待ちの16枚を消化しきれなかったためである。

### 59 がやること

1. **区分3b の7枚**(裁定260〜266): 突風の祝福・痛撃の炎術師・ガイル・フォックス・
   創世神ガイア・禁忌の冥魔剣・悪夢・ボーン・コレクター。
2. **区分4 の2枚**: ゾンストライカー(裁定267)・黄泉の召喚主(裁定275)。
   ★後者は暫定ガードを外し、対象選択を通す実装に置き換えるかを裁定で決める。
3. **区分5 の7枚**(裁定268〜274): フレア・ポーン・神風の大号令・英知の水晶・
   創世神 ゾディアックアイリス・大天使 ミカエル・マナを貪る怨霊・地響きの槌。
4. **裁定276**(地脈の覚醒のターン1回制限)が (a) だった場合の手当て(数行)。
   ★**264(禁忌の冥魔剣のターン5回)と揃えて実装すること。**同じ規則の2つの現れ方である。
5. 設計解説は**新しいファイル**(`notes/batch59-design-notes.md`)に書く。
6. 壊し検証は `tools/batch59_break_check.py` を新設する。

### 各バッチがやること(P2から継続・変更なし)

1. `python3 tools/report_effects.py --summary` で未実装の枚数を確認する。
   ★**58 から 0枚が正常値である。**1枚でも出たら、それは新しく壊れたということである。
2. **本文が2通り以上に読めるカードは、実装で決めずに必ず裁定を仰ぐ**(裁定184)。
   ★ただし**本文どおりの読みが1つに定まるなら、実装して確認を依頼する形でよい**
   (58 の裁定276 がその形。作業を止めない)。
3. **カード毎の JUnit を `support/AutoGameFixture` の上に書く**(裁定187)。
   ★**作り直しでは、Ver0.4の挙動を測っている既存の試験が落ちる —— それが正しい。**
   消さずに新しい本文へ書き換えること。
4. `mvn test` で回すこと(`surefire:test` はコンパイルしない。裁定208)。
5. **複数の対象を要求する効果は `usedMinionIds` の共有に注意する**(`qte-pitfalls.md`)。
6. ★**壊し検証の改変は「軸」ごとに1件ずつ当てる**(57 の教訓。58 でも守った)。

---

## 2. 発注者とのやりとり

- grep優先でファイルを渡り歩き、全体読み込みは避ける。
- **判断に迷う点はまとめて質問する。**1つずつ聞かない。
- 呼び方は「クロエ」、発注者は「マスター」。会話は日本語カジュアル体、
  **ドキュメントは通常文体(である調)。**

---

## 3. 既知の落とし穴

**★全文は `notes/qte-pitfalls.md` にある。**58 で1節を追記した。

| 節 | 追記した内容 |
|---|---|
| 常在の体力修正・実体を作る入口(Batch 58) | **【速攻】は HASTE、【突進】は RUSH**(取り違えた)/ `GameActions.newFieldMinion` が実体を作る唯一の入口 / 常在の値のうち動かないものだけを写す / 「ターンに1回」の判定と記録は1つのメソッドに / 【還元】は効果の解決より後 / キーワードだけになったカードは登録を消すのが実装 |

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
python3 tools/build_id_map.py --check
python3 tools/report_effects.py --summary
python3 tools/rework_triage.py --check
python3 tools/check_leader_abilities.py
```

---

## 5. チャット開始テンプレート

```
QTE Battle の開発を継続する。Batch 59(裁定が付いた16枚)を行う。

読む順:
1. プロジェクトナレッジ内の `notes/ver11-migration-plan.md`
2. プロジェクトナレッジ内の `claude/qte-handoff-v65.md`(本ファイル)
3. ★★プロジェクトナレッジ内の `notes/rework-triage.md`(★4-1章の消化状況)
4. プロジェクトナレッジ内の `notes/batch58-design-notes.md`
5. `notes/qte-pitfalls.md` の該当節(特に「常在の体力修正・実体を作る入口(Batch 58)」)
6. ★★プロジェクトナレッジ内の `notes/batch55-ruling-requests.md`
   —— 裁定260〜276(17件)の回答が揃っているか確認。揃っていない裁定に関わる
   カードには着手しない(裁定184)。

環境:
7. git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git
8. 接続フォルダの m2repo.zip を device_stage_files で取り込んで /root/m2work へ展開し、
   mvn -o -B "-Dmaven.repo.local=/root/m2work/repository" test を回す。
   ★571件全緑が出発点である。緑でなければ止めて報告する。
9. 58 の反映を本ファイルの「58の確認項目」で照合し、verify 543/543 と
   report_effects の「未実装0枚」、rework_triage.py --check と
   check_leader_abilities.py が両方 OK であることを確認する。

作業:
10. 本ファイル1章「59 がやること」に従う。
    ★設計解説は新規ファイル `notes/batch59-design-notes.md` に書く。
    ★試験は新規ファイル `Batch59ReworkTest.java` に書く。
    ★壊し検証は `tools/batch59_break_check.py` を新設し、改変は「軸」ごとに1件ずつ当てる。
11. 納品は4章のとおり。

呼び方は「クロエ」、こちらの呼び方は「マスター」で。会話は日本語カジュアル体、
ドキュメントは通常文体(である調)で。
```

## 6. 58 完了時点の積み残し

### マスターにお願いすること

- **Eclipse で refresh(F5)→ Run As → JUnit Test。**期待: **全571件が緑**。
- ★★**裁定依頼(`notes/batch55-ruling-requests.md`)への回答をお願いします。**
  **260〜275 の16件に加えて、末尾に 276(地脈の覚醒)を1件足しました。合わせて17件です。**
  ★**276 は 58 の作業を止めていません**(本文どおりの読みで実装済み・確認のお願いです)。
  残り16枚はこれが決まらないと着手できません。
- 実機確認: JS は1行も触っていないので、盤面の見た目・操作感は無変化です。
  遊び味が大きく変わるのは次の3枚です。
  - **《背水の烈火使い》** …… デメリットが消え、4コスト3/5【守護】が素で出せるようになった。
  - **《カース・ボーン》** …… 裏向きマナを作らず、自分の場を1体食う。
    闇のスターターに**4枚**入っているので序盤の展開が別物になります。
  - **《風弾の跳弾》** …… 自分のミニオンが手札に戻らず**破壊される**。
- 問題なければ **自分で git commit / push**。

### 継続中の積み残し

- 区分5 の残り7枚が未着手(裁定268〜274 待ち)。
- 区分3b の7枚(裁定260〜266)・区分4 の2枚(裁定267・275)も未着手。
- **裁定260〜276(17件)が未回答。**★残り16枚はすべてこれ待ちである。
- 《黄泉の召喚主》は暫定ガードのみ。裁定275 で本体を決める。
- 《地脈の覚醒》のターン1回制限は本文どおりの読みで実装済み。裁定276 で確認したい。
- `unlimitedCopies` に乗ったままの死んだ分岐が3箇所。無害。60で掃除。
- 裏向きマナと `fireManaPlaced` の非対称。60へ。
- 35〜58 の実機確認が未報告のまま(46bと48は報告あり)。
- 進化召喚がモアニールの登場置換で止まったときの素材の扱い(裁定232)。実機で違和感の
  有無を確認してほしい。
- 《英霊・コレキ》の「相手のターン中は止めない」を本物の入口から観測できない。
  ★58 で《風弾の跳弾》の「そうしたら」も同じ立場のものが1件増えた
  (設計解説 6-2。壊し検証の対象にはしていない)。
- `qte-cards.json` の削除(60)。区分5が終わるまで消さないこと。
- 新カード66枚のテキストの目視校正(46aからの持ち越し)。
- `qte-project-reference.md` の1章が古い(2026-07-21)。60で更新。
- 手動モード関連の積み残しは一時停止中。
