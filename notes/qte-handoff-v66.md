# QTE 対戦アプリ — 引き継ぎ書 v66

最終更新: 2026-08-21。**Batch 59(作り直し④ = 裁定が付いた16枚)を実施。**
★★**これで P5「作り直し」の121枚がすべて消化された。次は P6(仕上げ)である。**

| 項目 | 現在値 |
|---|---|
| JUnit | **611件 全緑**(571 → +40。`Batch59ReworkTest` を新設) |
| verify | **543/543**(据え置き) |
| 効果の未実装 | **0枚**(据え置き) |
| 版数 | `manual-battle.js` v=33 / `battle.js` v=25 / `battle.css` v=46(**据え置き**) |
| 裁定 | **276番まで確定** / ★**新規277〜279(3件)を依頼中・未確定** |
| 作り直しの消化 | 121枚中 **★121枚(完了)** |
| 壊し検証 | `tools/batch59_break_check.py` 28ケース **OK 28 / NG 0** |

---

## ★この文書の構成(v56 から継続)

| 文書 | 役割 | 更新のしかた |
|---|---|---|
| `notes/qte-rulings.md` | 裁定1〜276 の全文 | 追記専用 |
| `notes/qte-pitfalls.md` | 既知の落とし穴 | 追記・訂正のみ |
| 本ファイル(ハンドオフ) | 直近バッチの要点・次の作業 | 毎バッチ書き直す |

---

## 0. 最初にやること

1. `notes/ver11-migration-plan.md` を読む。
2. **`notes/rework-triage.md` を読む。**★4-1 章が「**完了**」になっていることを確認する。
3. `notes/qte-pitfalls.md` の該当節を読む(3章に一覧)。
4. 本ファイルと **`notes/batch59-design-notes.md`** を読む。
5. **`notes/batch59-ruling-requests.md` を読み、裁定277〜279 の回答が揃っているか確認する。**
   ★**3件とも Batch 60 の作業を止めない**(本文どおりの側・安全な側で実装済み)。
6. ソースコード取得: `git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git`
7. **「反映済み」を信じず、59 の変更箇所を実際に読んで照合する**(下の「59 の確認項目」)。

---

## ★カードデータの正(変更なし)

- `src/main/resources/cards/manual-cards.json`(Ver1.1・全235枚)が全モード共通の正。
- キーワードはテキストから作る(`CardTextKeywords.extract`。裁定158)。
- ★**`qte-cards.json`(Ver0.4の台帳)は区分5が終わったので、Batch 60 で削除してよい。**

---

## ★★59 がやったこと(要点)

設計解説は **`notes/batch59-design-notes.md`**(新規ファイル)。

裁定260〜276 の17件が揃ったので、残っていた16枚をすべて実装した。

### 実装が動かなかった4枚(★これも成果である)

- **突風の祝福**(260): 「1体」が消えたのは**省略**であり、単体のまま。
- **創世神 ガイア**(263): 「マナ最大値」= マナゾーンの現在の枚数。管理値の新設は不要。
- **フレア・ポーン**(268): 「効果なし」に登録は要らない。
- **黄泉の召喚主**(275): 「手札にあるかのように」は**狭い読み**。57 の暫定ガードが恒久のルールに。

★**この4枚にも試験を置いてある。**壊しどころが無いので壊し検証には載らないが、
後のバッチが単体を全体化するのを止める番人である。

### 実装が動いた12枚

1. **痛撃の炎術師**(261): 誘発を ON_SUMMON → **ON_ENTER**。【知識】の1ドローに加えて自傷が起きる。
2. **ガイル・フォックス**(262): 登場時判定・**永続的に**【潜伏】。
   ★**閾値が経路で1違う**(召喚は2、効果は3。裁定1 の数え方による)。
3. **禁忌の冥魔剣**(264): ターンに5回まで。★**回数の器を 276 と共有**。
4. **悪夢**(265): 【召喚時】封じ(自分だけ・このターン)。★判定は `fire()` の入口。
5. **ボーン・コレクター**(266): ON_DESTROYED_BY_COMBAT → **ON_DESTROYED**。
6. **ゾンストライカー**(267): 構築特例の廃止は**コード0行**(データが持つ)。
   実装したのは**破壊時セルフミル1枚**のほう。
7. **神風の大号令**(269): 自分のミニオンを**いるだけ**破壊し、その数だけ Attack+1。
8. **英知の水晶**(270): コスト軽減 → **相手のドローに反応して引く**。
   ★**新しい発火口 `TriggerType.ON_OPPONENT_DRAW`**。
9. **創世神 ゾディアックアイリス**(271): ターン終了時に**現在HP分**リーダーを回復。両者のターンで起きる。
10. **大天使 ミカエル**(272): 置換する層を**破壊 → ダメージ**へ1段下げた。HPが削れなくなった。
11. **マナを貪る怨霊**(273): 墓地の闇2枚を裏向きマナへ + **置いた枚数**ドロー。
12. **地響きの槌**(274): **両者**のミニオンに2ダメージ + 破壊数分のマナ加速。
13. **地脈の覚醒**(276・58 の上書き): 2枚目は**【還元】もしない**。墓地へ行く。

### ★構造の手当て3件

1. **「ターンに n 回まで」の器を1本にした**(`PlayerState.tryUseTurnLimited`)。
   58 の `tryUseLeylineAwakening(int)` を置き換えた。裁定264 と 276 が同じ規則だからである。
2. **`TriggerType.ON_OPPONENT_DRAW` と `CardEffectRegistry.fireOpponentDrew` を新設**。
   焚くのは `GameActions.drawCards` 1点。★**カードIDは `GameActions` に1つも書いていない。**
3. **ミカエルの置換を `RuleGuards.preventsCombatDamage` へ移した**。
   `isDestructionPrevented` からは消えている。

## 59 の確認項目(★これを照合する)

- **`PlayerState.java`**: `tryUseLeylineAwakening` が**消え**、
  `turnLimitedUses` / `turnLimitedUsesTurn` / `tryUseTurnLimited(String,int,int)` が新設されている
- **`SpellDisposition.java`**: `TO_TRASH` が増えている(4値になった)
- **`TriggerType.java`**: `ON_OPPONENT_DRAW` が増えている
- **`RuleGuards.java`**: `preventsCombatDamage(MinionInstance)` が新設され、
  `isDestructionPrevented` から `MICHAEL` の分岐が**消えている**
- **`StatCalculator.java`**: `WISDOM_CRYSTAL` の定数・コスト軽減・`IMPLEMENTED_CARDS` の登録が
  **3つとも消えている**
- **`GameActions.java`**: `dealCombatDamage` の先頭に `guards.preventsCombatDamage` のガードがあり、
  `drawCards` の末尾が `fireOpponentDrawWatchers` を呼び、
  `firingOpponentDrawWatchers`(再入ガード)のフィールドがある
- **`GameService.java`**: `resolveQuakeHammerAttack` が新設され、`QUAKE_HAMMER` の分岐が
  それを呼ぶだけになっている / `playSpell` と `resolveSoulSpell` の switch に `TO_TRASH` がある /
  `summonFromGrave` のコメントが「裁定275 確定・恒久のルール」に書き換わっている
- **`CardEffectRegistry.java`**: `NIGHTMARE_SUMMON_LOCK` / `MEIMA_SWORD_USES_PER_TURN` /
  `LEYLINE_AWAKENING_USES_PER_TURN` の3定数と `fireOpponentDrew` / `grantGaleFoxStealth` が新設。
  `fire()` の先頭が**【召喚時】封じ → 【知識】の順**になっている(★順序が意味を持つ)。
  `QTE-M-FIRE-18` が **ON_ENTER**、`QTE-M-WIND-6` が **ON_SUMMON と ON_ENTER の2つ**、
  `QTE-M-DARK-6` と `QTE-M-DARK-16` が **ON_DESTROYED**、
  `QTE-M-LIGHT-19` が **ON_OPPONENT_DRAW**、`QTE-M-LIGHT-25` が **ON_TURN_END** に登録されている
- **`src/test/java/.../Batch59ReworkTest.java`**(新規・40件)
- **`Batch58ReworkTest.java`**: 地脈の覚醒の2枚目の試験が「墓地へ行く」に書き換わっている
- **`tools/batch59_break_check.py`**(新規・28ケース): **OK 28 / NG 0**
- JUnit **611件全緑** / verify **543/543**
- `python3 tools/check_structure.py` / `check_all.py` / `check_records.py`
  (★既知の誤検出3件。58 から変化なし)/ `check_undeclared.py` / `node --check` 2種 いずれも異常なし
- `python3 tools/rework_triage.py --check` / `check_leader_abilities.py` /
  `build_id_map.py --check` いずれも OK
- `python3 tools/report_effects.py --summary` で **未実装 0枚**
  (★**登録あり 165 / 宣言あり 50** に動いた。英知の水晶が宣言から登録へ移ったためである)
- ★**JS は1行も触っていない**。版数も据え置き。盤面の見た目・操作感は無変化

---

## 1. 次の作業(Batch 60 = P6 仕上げ)

**★作り直し(P5)は完了した。**60 は旧 P5 の積み残しを片付けるフェーズである。

| フェーズ | バッチ | 内容 |
|---|---|---|
| P5 作り直し | 55〜**59(完了)** | 121枚すべて消化 |
| **P6 仕上げ** | **60(次)** | 下の一覧 |

### 60 がやること

1. **`qte-cards.json` の削除。**★区分5 が終わったので消してよい。
   `support/LedgerCards` と台帳照合3件(`CardIdMappingTest` ほか)も同時に整理する。
   ★`tools/build_id_map.py` / `rework_triage.py` / `report_effects.py` が台帳を読んでいるので、
   **消す前にどのツールが何を失うかを数える**こと。
2. **`unlimitedCopies` の掃除。**★裁定267 で**データ側も完全に死んだ**。
   `ManualCardMaster` / `ManualCardRepository` / `ManualDeckImporter` の3箇所。
3. **プリセットデッキの Ver1.1 化**(`DeckFactory`)。
4. **裏向きマナと `fireManaPlaced` の非対称**(51 設計解説 6-2)を決める。
5. **`qte-project-reference.md` の1章の更新**(2026-07-21 のまま)。
6. 裁定277〜279 の回答が付いたらその手当て(それぞれ数行〜数十行)。
7. 設計解説は**新しいファイル**(`notes/batch60-design-notes.md`)に書く。
8. 壊し検証は `tools/batch60_break_check.py` を新設する。

### 各バッチがやること(P2から継続・変更なし)

1. `python3 tools/report_effects.py --summary` で未実装の枚数を確認する。
   ★**58 から 0枚が正常値である。**1枚でも出たら、それは新しく壊れたということである。
2. **本文が2通り以上に読めるカードは、実装で決めずに必ず裁定を仰ぐ**(裁定184)。
   ★ただし**本文どおりの読みが1つに定まるなら、実装して確認を依頼する形でよい**(作業を止めない)。
3. **カード毎の JUnit を `support/AutoGameFixture` の上に書く**(裁定187)。
4. `mvn test` で回すこと(`surefire:test` はコンパイルしない。裁定208)。
5. ★**壊し検証の改変は「軸」ごとに1件ずつ当てる**(57 の教訓。58・59 でも守った)。
6. ★★**壊しても落ちないとき、「試験が足りない」の前に「その盤面が構造的に作れるか」を疑う**
   (59 の教訓。`qte-pitfalls.md` の Batch 59 節)。

---

## 2. 発注者とのやりとり

- grep優先でファイルを渡り歩き、全体読み込みは避ける。
- **判断に迷う点はまとめて質問する。**1つずつ聞かない。
- 呼び方は「クロエ」、発注者は「マスター」。会話は日本語カジュアル体、
  **ドキュメントは通常文体(である調)。**

---

## 3. 既知の落とし穴

**★全文は `notes/qte-pitfalls.md` にある。**59 で1節を追記した。

| 節 | 追記した内容 |
|---|---|
| 回数制限・封じ・観測できない軸(Batch 59) | 「ターンに n 回まで」の器は1本 / 何も起こさなかったカードは【還元】もしない / 誘発の種類で分かれる規則は判定を分岐の先頭に置く(★【知識】より前) / 相互に誘発しあう効果は放っておくと止まらない / ダメージを「0にする」のと「与えない」は別物 / 盤面に依存する要求数は表現できない / ★**本物の入口から観測できない軸がある** / 「登場時」は経路で数え方が1違うことがある |

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
QTE Battle の開発を継続する。Batch 60(P6 仕上げ)を行う。

読む順:
1. プロジェクトナレッジ内の `notes/ver11-migration-plan.md`
2. プロジェクトナレッジ内の `claude/qte-handoff-v66.md`(本ファイル)
3. プロジェクトナレッジ内の `notes/rework-triage.md`(★4-1章 = 作り直しは完了)
4. プロジェクトナレッジ内の `notes/batch59-design-notes.md`
5. `notes/qte-pitfalls.md` の該当節(特に「回数制限・封じ・観測できない軸(Batch 59)」)
6. ★プロジェクトナレッジ内の `notes/batch59-ruling-requests.md`
   —— 裁定277〜279(3件)の回答が揃っているか確認。★3件とも作業は止めない。

環境:
7. git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git
8. 接続フォルダの m2repo.zip を device_stage_files で取り込んで /root/m2work へ展開し、
   mvn -o -B "-Dmaven.repo.local=/root/m2work/repository" test を回す。
   ★611件全緑が出発点である。緑でなければ止めて報告する。
9. 59 の反映を本ファイルの「59の確認項目」で照合し、verify 543/543 と
   report_effects の「未実装0枚」、rework_triage.py --check と
   check_leader_abilities.py が両方 OK であることを確認する。

作業:
10. 本ファイル1章「60 がやること」に従う。
    ★設計解説は新規ファイル `notes/batch60-design-notes.md` に書く。
    ★試験は新規ファイル `Batch60Test.java` に書く。
    ★壊し検証は `tools/batch60_break_check.py` を新設し、改変は「軸」ごとに1件ずつ当てる。
11. 納品は4章のとおり。

呼び方は「クロエ」、こちらの呼び方は「マスター」で。会話は日本語カジュアル体、
ドキュメントは通常文体(である調)で。
```

## 6. 59 完了時点の積み残し

### マスターにお願いすること

- **Eclipse で refresh(F5)→ Run As → JUnit Test。**期待: **全611件が緑**。
- ★**裁定依頼(`notes/batch59-ruling-requests.md`)への回答をお願いします。**
  **277・278・279 の3件です。★3件とも Batch 60 の作業は止めません。**
- ★★**報告が1件あります: 《英知の水晶》の「引いても良い」を問い合わせにできていません。**
  ドローはエンジンのあらゆる場所から呼ばれ、そこで中断しても
  中断された側の続きを再開する術が無いためです。当面は自動判断
  (山札が空でなければ引く)に寄せてあります。詳細は設計解説 6-1。
- 実機確認: JS は1行も触っていないので、盤面の見た目・操作感は無変化です。
  遊び味が大きく変わるのは次の4枚です。
  - **《悪夢》** …… サブフェイズなら全ミニオンのコスト-4、ただし**そのターンの【召喚時】が全部死ぬ**。
    メインフェイズに撃つとデメリットだけが残ります。
  - **《大天使 ミカエル》** …… 戦闘で**一切傷つかなくなった**。4/8 のまま立ち続けます。
  - **《英知の水晶》** …… 相手のターンが始まるたびに1枚引きます(置くだけで手札が増え続ける)。
  - **《地響きの槌》** …… **自分の場も巻き込む**2ダメージ + 破壊数分のマナ加速。
- 問題なければ **自分で git commit / push**。

### 継続中の積み残し

- **裁定277〜279(3件)が未回答**(作業は止まっていない)。
- 《黄泉の召喚主》の対象を読む【召喚時】は止めたまま(裁定278 で決める)。
- `unlimitedCopies` の死んだ分岐3箇所。★**裁定267 でデータ側も死んだ。**60 で掃除。
- 裏向きマナと `fireManaPlaced` の非対称。60 へ。
- `qte-cards.json` の削除(60)。★**区分5 が終わったので消してよい。**
- 《神風の大号令》の強化の期限(このターン)と《突風の祝福》(恒久)の非対称。60 で確認したい。
- 35〜59 の実機確認が未報告のまま(46bと48は報告あり)。
- 進化召喚がモアニールの登場置換で止まったときの素材の扱い(裁定232)。実機で違和感の
  有無を確認してほしい。
- **本物の入口から観測できない挙動が3件**になった ——
  《英霊・コレキ》の「相手のターン中は止めない」/ 《風弾の跳弾》の「そうしたら」/
  ★**《悪夢》の封じの範囲(自分だけか両者か)**(59 設計解説 4-3)。
- 新カード66枚のテキストの目視校正(46aからの持ち越し)。
- `qte-project-reference.md` の1章が古い(2026-07-21)。60で更新。
- 手動モード関連の積み残しは一時停止中。
