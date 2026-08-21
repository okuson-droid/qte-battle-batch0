# QTE 対戦アプリ — 引き継ぎ書 v63

最終更新: 2026-08-20。**Batch 56(作り直し② 区分3b・4)を火・水・風・光の23枚まで実施。
闇・土の17枚は未着手のまま次バッチへ持ち越し**(マスターの指示でここまでで区切った)。

| 項目 | 現在値 |
|---|---|
| JUnit | **516件 全緑**(480 → +36。`Batch56ReworkTest`) |
| verify | **543/543**(据え置き) |
| 効果の未実装 | **1枚**(剛火の将。57 で解消予定。変化なし) |
| 版数 | `manual-battle.js` v=33 / `battle.js` v=25 / `battle.css` v=46(**据え置き**) |
| 裁定 | **259番まで確定** / ★**260〜274(15件)を依頼中・未確定** |

---

## ★この文書の構成(v56 から継続)

| 文書 | 役割 | 更新のしかた |
|---|---|---|
| `notes/qte-rulings.md` | 裁定1〜259 の全文 | 追記専用 |
| `notes/qte-pitfalls.md` | 既知の落とし穴(33節) | 追記・訂正のみ |
| 本ファイル(ハンドオフ) | 直近バッチの要点・次の作業 | 毎バッチ書き直す |

---

## 0. 最初にやること

1. `notes/ver11-migration-plan.md` を読む。
2. **`notes/rework-triage.md` を読む。**56・57 の作業範囲の正である。
3. `notes/qte-pitfalls.md` の該当節を読む(5章に一覧)。
4. 本ファイルと **`notes/batch56-design-notes.md`**(今回作った火水風光23枚の設計解説)を読む。
5. `notes/batch55-ruling-requests.md` を読み、マスターの回答が揃っているか確認する。
   揃っていなければ、揃うまで区分3b・4・5の該当カードには着手しない(裁定184)。
6. ソースコード取得: `git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git`
7. **「反映済み」を信じず、56 の変更箇所を実際に読んで照合する**(下の「56 の確認項目」)。

---

## ★カードデータの正(変更なし)

- `src/main/resources/cards/manual-cards.json`(Ver1.1・全235枚)が全モード共通の正。
- キーワードはテキストから作る(`CardTextKeywords.extract`。裁定158)。
- `qte-cards.json`(Ver0.4の台帳)は 58 まで消さないこと。

---

## ★★56(前半)がやったこと(要点)

設計解説は **`notes/batch56-design-notes.md`**。区分3b・4のうち裁定待ちの8枚を除く40枚の
**前半23枚**(火3・水4・風7・光9)を直した。**闇9・土8の17枚は未着手のまま**である。

1. **火3枚**: 武具昇華の炎(対象が両者のウェポンへ拡大)・鳳凰神ヴォルカニクスレヴォ
   (0→1コスト特殊召喚)・覚醒の炎童(召喚時1回復が追加)。
2. **水4枚**: 双流の幻術師(参照が【知識】ミニオンの数に復帰)・流転の智者(実装変更なし。
   確認のみ)・智将ポセイドン・コア(条件12→9・召喚時効果が別物に)・静寂の瞑想
   (3→2ドロー・使用制限が消えた)。
3. **風7枚**: 嵐の守り手(条件反転・0→1コスト)・風神ヴァーユ(参照が風文明カード数に)・
   詠唱の風詠士(対象が全カード種別に拡大)・選択の追い風(守護限定が外れた)・
   回帰の風穴(実装変更なし)・風護の杖(実装変更なし。【知識】が付いただけ)。
4. **光9枚**: 聖域の案内人(自身除外の修正)・**天界の守護神ゾディアック**(召喚時ウェポン
   破壊を追加)・**ホーリー・シグナル**(2体破壊に対応。★下記の設計上の注意点を参照)・
   唱導の聖騎士(実装変更なし)・戒律のガーディアン(実装変更なし)・降臨の伝道師
   (実装変更なし)・断罪の大天使(実装変更なし)・聖光の武装解除(破壊成功時1ドローを
   追加)・詠唱の宝珠(実装変更なし)。
5. **既存試験の書き換え**: `SoulSpellTest.賢魂はスペルのコスト軽減を受ける` が唱導の聖騎士の
   新条件(リーダーが光文明)を満たしていなかったため、光文明リーダーの構成に書き換えた
   (裁定187の含意どおり)。

### ★★設計上の注意点: ホーリー・シグナルの対象選択(重要)

新本文は「最も攻撃力の高いミニオン」と「最も体力の低いミニオン」を**同時に**破壊する。
これを2つの `TargetSpec.Requirement` で素直に実装すると、`GameService.validateTargets` が
**1枚のカードの検証中に `usedMinionIds` を全Requirementで使い回す**ため、
**同じミニオンが両方の条件を満たすケース**(相手の場が1体しかいない、など)で
「同じミニオンを重複して選べません」の例外になり、**カードが使用不能になる**バグを
実装中に発見した。

対処として、最高攻撃力側だけを引き続き `Requirement` としてプレイヤーに選ばせ(タイの
ときだけ実質選択の意味があるため)、最低体力側は `AutoChoice.lowestCurrentHp` を新設して
効果解決時に自動決定する形にした。両対象は破壊前の盤面スナップショットから同時に確定し、
同一ミニオンなら1回だけ破壊する。**複数の対象を要求する新しいカードを作るときは、
同じ弱点(usedMinionIdsの使い回し)がないか必ず確認すること。**
`AutoChoice.java` のJavadocに詳細を書いてある。

## 56(前半)の確認項目(★これを照合する)

- **`CardEffectRegistry.java`**: `QTE-M-FIRE-24`・`QTE-M-FIRE-22`・`QTE-M-FIRE-20`・
  `QTE-M-WATER-26`・`QTE-M-WIND-19`・`QTE-M-WIND-21`・`QTE-M-WIND-25`・`QTE-M-LIGHT-3`・
  `QTE-M-LIGHT-8`・`QTE-M-LIGHT-10`・`QTE-M-LIGHT-26` に `★Batch 56` のコメントがあり、
  新本文どおりの実装になっている
- **`StatCalculator.java`**: `TWIN_ILLUSIONIST`(双流の幻術師)・`WIND_CHANTER_LEADER`
  (詠唱の風詠士)・`CHANT_PALADIN`/`PRECEPT_GUARDIAN`/`CHANT_ORB`(光文明3枚)に
  `★Batch 56` のコメントがある
- **`AutoChoice.java`**: `lowestCurrentHp` メソッドが新設されている(ホーリー・シグナル用)
- **`src/test/java/.../Batch56ReworkTest.java`**(新規・59件): 火水風光23枚分の新旧挙動の
  試験。それぞれ「旧:」「新:」のコメント付き
- **`SoulSpellTest.java`**: `賢魂はスペルのコスト軽減を受ける` が光文明リーダーの構成に
  なっている
- JUnit 516件全緑 / verify 543/543
- `python3 tools/check_structure.py` / `check_all.py` / `check_records.py`(★既知の誤検出
  4件は無害)/ `check_undeclared.py` / `node --check` 2種 いずれも異常なし
- `python3 tools/rework_triage.py --check` / `check_leader_abilities.py` /
  `build_id_map.py --check` いずれも OK
- `python3 tools/report_effects.py --summary` で未実装は引き続き剛火の将1枚のみ
- ★`battle.js` の v は今回 **変更なし(v=25のまま)**。ホーリー・シグナルの実装過程で
  `Filter.LOWEST_HP_OPPONENT` をクライアント側にも足しかけたが、上記の設計変更で
  不要になったため revert 済み(死んだコードを残さない)

---

## 1. 次の作業(Batch 56 後半 = 闇9枚・土8枚)

**作業範囲の正は `notes/rework-triage.md` である。**ここには要点だけを置く。

| フェーズ | バッチ | 内容 | 枚数 |
|---|---|---|---|
| P5 作り直し | 55(完了) | 棚卸し | — |
| **P5 作り直し** | **56 前半(完了)** | 火3・水4・風7・光9=23枚 | 23 |
| **P5 作り直し** | **56 後半(次)** | 闇9・土8=17枚。新旧の挙動を設計解説に併記 | 17 |
| | 57 | ほぼ書き直し(区分5)。裁定待ち含む | 15+8 |
| P6 仕上げ | 58 | 旧P5の項目(変更なし) | — |

### 56後半がやること

1. **闇文明9枚**: 執念の暗殺者・墓場の怨念集合体・死者蘇生・群がる死霊王(区分3b)/
   冥府の禁皇(参照ゾーンがマナ裏向き→墓地のみ。コストは55で直し済み)・獄門の裁定者・
   禁忌の代償・絶望の連鎖・黄泉の召喚主(区分4)。
2. **土文明8枚**: アースクエイク・ジャイアント・大地の精霊グラン(★55で確認済み・
   実装変更なし)・天変地異のタイタン・安らぎのガーディアン・苗木植えの精霊・
   豊穣の地霊主(区分3b)/ガイア・ハンマー・大地の恵み(区分4)。
3. **新旧の挙動を設計解説に併記する**(`notes/batch56-design-notes.md` に追記する形で。
   新規ファイルにしない)。
4. 火水風光23枚と同じやり方: `Batch56ReworkTest.java` に闇・土のセクションを追記する形で
   試験を足す(新規ファイルにしない)。
5. 56全体(40枚)が揃ったら、`tools/batch56_break_check.py` を新設して壊し検証を行い、
   `notes/rework-triage.md` の区分3b・4の消化数を一括更新する。

### 各バッチがやること(P2から継続・変更なし)

1. `python3 tools/report_effects.py --summary` で未実装が1枚(剛火の将)のままであることを
   確認する。
2. **本文が2通り以上に読めるカードは、実装で決めずに必ず裁定を仰ぐ**(裁定184)。
3. **カード毎の JUnit を `support/AutoGameFixture` の上に書く**(裁定187)。
   ★**作り直しでは、Ver0.4の挙動を測っている既存の試験が落ちる —— それが正しい。**
   消さずに新しい本文へ書き換えること。
4. `mvn test` で回すこと(`surefire:test` はコンパイルしない。裁定208)。
5. **複数の対象を要求する効果を実装するときは、`GameService.validateTargets` の
   `usedMinionIds` が全Requirementで共有される点に注意する**(上記「設計上の注意点」参照。
   同じ対象が2つの条件を満たしうる効果は `AutoChoice` 側で解決する)。

---

## 2. 発注者とのやりとり

- grep優先でファイルを渡り歩き、全体読み込みは避ける。
- **判断に迷う点はまとめて質問する。**1つずつ聞かない。
- 呼び方は「クロエ」、発注者は「マスター」。会話は日本語カジュアル体、
  **ドキュメントは通常文体(である調)。**

---

## 3. 既知の落とし穴

**★全文は `notes/qte-pitfalls.md`(33節)にある。**Batch 56後半で特に効くのは
v62の3章の表と同じ(対象指定・デッキ構築)に加え、今回見つかった
「対象指定(TargetSpec)の落とし穴」節に **usedMinionIds の共有** を追記すること
(まだ追記していない。56後半の着手前にやる)。

---

## 4. デリバリー形式(変更なし)

マスターの Eclipse ワークスペース
(`C:\Users\奥村優斗\OneDrive\ドキュメント\eclipse_workフォルダ\qte-battle-batch0`)へ
直接書き込む。手順はv62の4章のとおり。

反映前に必ず実行:

```bash
python3 tools/check_structure.py src/main/java
python3 tools/check_all.py .
python3 tools/check_records.py src/main/java     # ★既知の誤検出4件あり
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
QTE Battle の開発を継続する。Batch 56 後半(闇9枚・土8枚)を行う。

読む順:
1. プロジェクトナレッジ内の `notes/ver11-migration-plan.md`
2. プロジェクトナレッジ内の `claude/qte-handoff-v63.md`(本ファイル)
   ★★「56(前半)がやったこと」と「設計上の注意点(ホーリー・シグナル)」は必読。
3. ★★プロジェクトナレッジ内の `notes/rework-triage.md`(56後半17枚の一覧)
4. プロジェクトナレッジ内の `notes/batch56-design-notes.md`(前半23枚の設計解説。
   後半はここに追記する)
5. `notes/qte-pitfalls.md` の該当節
6. ★★プロジェクトナレッジ内の `notes/batch55-ruling-requests.md`
   —— 裁定260〜274の回答が揃っているか確認。揃っていない裁定に関わるカードには着手しない。

環境:
7. git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git
8. 接続フォルダの m2repo.zip を device_stage_files で取り込んで展開し、
   mvn -o -B "-Dmaven.repo.local=/root/m2work/repository" test を回す。
   ★516件全緑が出発点である。緑でなければ止めて報告する。
9. 56前半の反映を本ファイルの「56の確認項目」で照合し、verify 543/543と
   report_effectsの「未実装1枚(剛火の将)」、rework_triage.py --checkと
   check_leader_abilities.pyが両方OKであることを確認する。

作業:
10. 本ファイル1章「56後半がやること」に従う。
    ★裁定待ちの8枚は回答が揃うまで着手しない。
    ★新旧の挙動を `notes/batch56-design-notes.md` に追記すること(新規ファイルにしない)。
    ★試験は `Batch56ReworkTest.java` に闇・土のセクションを追記すること。
11. 40枚(前半23+後半17)が揃ったら壊し検証(`tools/batch56_break_check.py`新設)と
    `rework-triage.md`の区分消化数の一括更新を行う。
12. 納品は4章のとおり。

呼び方は「クロエ」、こちらの呼び方は「マスター」で。会話は日本語カジュアル体、
ドキュメントは通常文体(である調)で。
```

## 6. 56前半完了時点の積み残し

### マスターにお願いすること

- **Eclipse で refresh(F5)→ Run As → JUnit Test。**期待: **全516件が緑**。
- ★★**裁定依頼(`notes/batch55-ruling-requests.md`)への回答をお願いします。**
  15件、番号260から。これが決まり次第、56後半と並行して裁定待ちの8枚にも着手できます。
- 実機確認: 今回 `battle.js` の版数は変えていない(v=25のまま)ので、
  実質のJS変更はゼロ。盤面の見た目・操作感は無変化。
- 問題なければ **自分で git commit / push**。

### 継続中の積み残し(v62から)

- 闇文明9枚・土文明8枚が未着手(本バッチの主な積み残し)。
- 裁定260〜274(15件)が未回答。
- 《冥府の禁皇》は55と56で二度触る。55はコストのみ(済)、56は効果
  (参照ゾーンがマナ裏向き→墓地)を直す(未着手)。
- 《剛火の将》の常在効果は未実装のまま Batch 57 へ。
- `pendingFireMinionDiscount` 関連の死んだコードの掃除も57で。
- `unlimitedCopies` に乗ったままの死んだ分岐が3箇所。無害。58で掃除。
- 裏向きマナと `fireManaPlaced` の非対称。58へ。
- 35〜55の実機確認が未報告のまま(46bと48は報告あり)。
- 進化召喚がモアニールの登場置換で止まったときの素材の扱い(裁定232)。実機で違和感の
  有無を確認してほしい。
- 《英霊・コレキ》の「相手のターン中は止めない」を本物の入口から観測できない。
- `qte-cards.json` の削除(58)。57が終わるまで消さないこと。
- 新カード66枚のテキストの目視校正(46aからの持ち越し)。
- `qte-project-reference.md` の1章が古い(2026-07-21)。58で更新。
- 手動モード関連の積み残しは一時停止中。
