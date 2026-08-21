# QTE 対戦アプリ — 引き継ぎ書 v64

最終更新: 2026-08-21。**Batch 57(= 56後半。作り直し② 区分3b・4 の闇9枚・土8枚)を実施。**
マスターの指示により、このチャットのバッチ番号は **57** としたが、作業の中身は
v63 の1章「56後半がやること」そのものである。**区分5(本来の57)には着手していない。**

| 項目 | 現在値 |
|---|---|
| JUnit | **549件 全緑**(516 → +33。`Batch56ReworkTest` に闇・土のセクションを追記) |
| verify | **543/543**(据え置き) |
| 効果の未実装 | **1枚**(剛火の将。変化なし) |
| 版数 | `manual-battle.js` v=33 / `battle.js` v=25 / `battle.css` v=46(**据え置き**) |
| 裁定 | **259番まで確定** / ★**260〜274(15件)+ 新規275(1件)を依頼中・未確定** |
| 作り直しの消化 | 121枚中 **97枚**(区分3b 20/27・区分4 19/21・区分5 0/15) |

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
4. 本ファイルと **`notes/batch56-design-notes.md`** を読む。
   ★後者は**前半23枚(1〜4章)と後半17枚(6〜10章)が1つのファイルに入っている。**
5. `notes/batch55-ruling-requests.md` を読み、マスターの回答が揃っているか確認する。
   ★**260〜274 に加えて、57 で新しく 275 が増えた。**
6. ソースコード取得: `git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git`
7. **「反映済み」を信じず、57 の変更箇所を実際に読んで照合する**(下の「57 の確認項目」)。

---

## ★カードデータの正(変更なし)

- `src/main/resources/cards/manual-cards.json`(Ver1.1・全235枚)が全モード共通の正。
- キーワードはテキストから作る(`CardTextKeywords.extract`。裁定158)。
- `qte-cards.json`(Ver0.4の台帳)は区分5が終わるまで消さないこと。

---

## ★★57 がやったこと(要点)

設計解説は **`notes/batch56-design-notes.md` の6〜10章**。

### 闇文明9枚

1. **執念の暗殺者**(3b): 監視から「自分の」が消えた → **両者のミニオンの破壊**で1ドロー。
   `watchAnyMinionDestroyed` と `EffectContext.swapSides()` を新設した。
2. **墓場の怨念集合体**(3b): 墓地のスペル以外1枚につき **Cost-1 も**付いた(+【守護】)。
3. **死者蘇生**(3b): 実装変更なし(送りがなの差だけ)。
4. **群がる死霊王**(3b): 軽減量が1枚につき **-1 → -2**(印刷コストは6→8)。
5. **冥府の禁皇**(4): 参照ゾーンが**裏向きマナ → 墓地**、後半が**2ドロー → セルフミル2**。
   対象指定を伴う起動能力になった(`LeaderAbilitySpec` は元から `TargetSpec` を運べる)。
   ★副産物として `GameActions.returnFaceDownManaToHand` が死んだので削除した。
6. **獄門の裁定者**(4): 「リーダーを攻撃できない」が追加(9/9/9)。`RuleGuards` に置いた。
7. **禁忌の代償**(4): 後半が**相手ミニオン破壊 → 自分の墓地からコスト4以下を場に出す**へ。
8. **絶望の連鎖**(4): 「そうしたら」の条件化 + **このターン3体以上破壊で1ドロー**
   (両者の合計を `GameState.minionsDestroyedThisTurn` から読む)。
9. **黄泉の召喚主**(4): ★**裁定275 待ちで本体は未着手。**下記参照。

### 土文明8枚

10. **アースクエイク・ジャイアント**(3b): 実装変更なし(【召喚時】の明記だけ)。
11. **大地の精霊 グラン**(3b/4): 実装変更なし。
12. **天変地異のタイタン**(3b): 実装変更なし。
13. **安らぎのガーディアン**(3b): ★**「自分の」ターンエンド時に限定**された。
    `ON_TURN_END` は両者の場を回すため、旧は**相手のターンの終わりにも4回復**していた
    (実質8回復)。ここがこのバッチで最も遊び味の変わる1枚である(+【守護】)。
14. **苗木植えの精霊**(3b): 実装変更なし。
15. **豊穣の地霊主**(3b): 実装変更なし(【常在】の印だけ)。
16. **ガイア・ハンマー**(4): 実装変更なし(ウェポンの「場に出る」= `ON_EQUIP`)。
17. **大地の恵み**(4): **マナが10枚以上なら1ドロー**が追加(置いた後に判定)。

### ★★カードの作り直しとは別に見つかった穴2件

1. **墓地からの召喚が対象選択を運べない**(《黄泉の召喚主》)。
   `GameService.summonFromGrave` は `TargetChoice` を受け取る口が無く、
   `summonToField` に `null` を渡していた。**対象を読む【召喚時】を持つミニオンを
   墓地から召喚すると NullPointerException で 500 になる**(Ver.0.4 からの穴。
   闇のスターターは黄泉の召喚主と執念の暗殺者を同時に積むので実際に踏める)。
   → 裁定275 が付くまでの暫定として、**理由付きで拒否する**ガードを置いた。
2. **`Kind.WEAPON` の要求で `Side` がサーバ側で検証されていなかった**(★57 で修正)。
   `Requirement.side()` がクライアントへの助言にしかなっておらず、細工したクライアントなら
   《天界の守護神 ゾディアック》(`Side.OPPONENT`)で**自分のウェポンを破壊**できた。
   ★**壊し検証(ケース1)が見つけた穴である。**

## 57 の確認項目(★これを照合する)

- **`CardEffectRegistry.java`**: `QTE-M-DARK-20`・`QTE-M-DARK-22`・`QTE-M-DARK-1`・
  `QTE-M-DARK-23`・`QTE-M-DARK-10`・`QTE-M-DARK-9`・`QTE-M-EARTH-20`・`QTE-M-EARTH-9` に
  `★Batch 57` のコメントがあり、新本文どおりの実装になっている
- **`CardEffectRegistry.java`**: `anyMinionDestroyedWatchers` / `watchAnyMinionDestroyed` が
  新設され、`fireOwnMinionDestroyed` が**相手側の場も走査する**2本目のループを持つ
- **`CardEffectRegistry.java`**: `registerDarkSpellsAndWeapons()` に分割されている
  (300行超で `check_structure.py` に触れたため。中身は動かしていない)
- **`EffectContext.java`**: `swapSides()` が新設されている
- **`StatCalculator.java`**: `SWARM_LICH`(× 2)・`GRAVE_WRAITH_MASS`(コスト軽減)に
  `★Batch 57` のコメントがある
- **`RuleGuards.java`**: `WARDEN_JUDGE`(獄門の裁定者)が `IMPLEMENTED_CARDS` と
  `minionAttackDenial` の両方に居る
- **`GameService.java`**: `summonFromGrave` に対象指定ミニオンのガード、
  `validateTargets` の `WEAPON` 分岐に `sideMismatch` の検証がある
- **`GameActions.java`**: `returnFaceDownManaToHand` が**削除**され、削除理由のコメントが残っている
- **`src/test/java/.../Batch56ReworkTest.java`**: 闇・土のセクション(+33件)。
  それぞれ「旧:」「新:」のコメント付き
- **`tools/batch56_break_check.py`**(新規・29ケース): **OK 28 / NG(想定内) 1**
- JUnit 549件全緑 / verify 543/543
- `python3 tools/check_structure.py` / `check_all.py` / `check_records.py`
  (★誤検出は**4件 → 3件に減った**。冥府の禁皇の書き換えで1件が実質解消した)/
  `check_undeclared.py` / `node --check` 2種 いずれも異常なし
- `python3 tools/rework_triage.py --check` / `check_leader_abilities.py` /
  `build_id_map.py --check` いずれも OK
- `python3 tools/report_effects.py --summary` で未実装は引き続き剛火の将1枚のみ
- ★**JS は1行も触っていない**。版数も据え置き(`battle.js` v=25)。盤面の見た目・操作感は無変化

---

## 1. 次の作業(Batch 58 = 区分5「ほぼ書き直し」15枚)

**作業範囲の正は `notes/rework-triage.md` である。**ここには要点だけを置く。

| フェーズ | バッチ | 内容 | 枚数 |
|---|---|---|---|
| P5 作り直し | 55(完了) | 棚卸し | — |
| P5 作り直し | 56 前半(完了) | 火3・水4・風7・光9 | 23 |
| **P5 作り直し** | **57(完了)** | 闇9・土8(うち黄泉の召喚主は裁定275 待ち) | 17 |
| **P5 作り直し** | **58(次)** | **区分5(15枚)+ 裁定が付いた分の積み残し9枚** | 15+9 |
| P6 仕上げ | 59 | 旧P5の項目(プリセット Ver1.1 化・`qte-cards.json` 削除ほか) | — |

### 58 がやること

1. **区分5(15枚)**: 剛火の将・フレア・ポーン・背水の烈火使い(火)/ 英知の継承者・
   知恵の双翼(水)/ 神風の大号令・風弾の跳弾・ストーム・カイザー(風)/ 英知の水晶・
   創世神 ゾディアックアイリス・大天使 ミカエル(光)/ マナを貪る怨霊・カース・ボーン(闇)/
   地脈の覚醒・地響きの槌(土)。★**うち7枚は裁定268〜274 待ち。**
2. **裁定が付いた分の積み残し9枚**: 突風の祝福・痛撃の炎術師・ガイル・フォックス・
   創世神ガイア・禁忌の冥魔剣・悪夢・ボーン・コレクター(区分3b)/ ゾンストライカー(区分4)
   / 黄泉の召喚主(区分4・裁定275)。
3. **《剛火の将》の常在効果**を実装し、`pendingFireMinionDiscount` の死んだコードを掃除する。
4. 設計解説は**新しいファイル**(`notes/batch58-design-notes.md`)に書く
   —— 56 のファイルは前半+後半で既に10章あり、これ以上足すと読めなくなる。
5. 壊し検証は `tools/batch58_break_check.py` を新設する。

### 各バッチがやること(P2から継続・変更なし)

1. `python3 tools/report_effects.py --summary` で未実装の枚数を確認する。
2. **本文が2通り以上に読めるカードは、実装で決めずに必ず裁定を仰ぐ**(裁定184)。
3. **カード毎の JUnit を `support/AutoGameFixture` の上に書く**(裁定187)。
   ★**作り直しでは、Ver0.4の挙動を測っている既存の試験が落ちる —— それが正しい。**
   消さずに新しい本文へ書き換えること。
4. `mvn test` で回すこと(`surefire:test` はコンパイルしない。裁定208)。
5. **複数の対象を要求する効果は `usedMinionIds` の共有に注意する**(`qte-pitfalls.md`)。
6. ★**壊し検証の改変は「軸」ごとに1件ずつ当てる**(57 の教訓)。
   2つの誤りを1つの改変で同時に入れると、盤面によっては打ち消し合って落ちない。

---

## 2. 発注者とのやりとり

- grep優先でファイルを渡り歩き、全体読み込みは避ける。
- **判断に迷う点はまとめて質問する。**1つずつ聞かない。
- 呼び方は「クロエ」、発注者は「マスター」。会話は日本語カジュアル体、
  **ドキュメントは通常文体(である調)。**

---

## 3. 既知の落とし穴

**★全文は `notes/qte-pitfalls.md` にある。**57 で3節を追記した。

| 節 | 追記した内容 |
|---|---|
| 対象指定(TargetSpec)の落とし穴 | **`usedMinionIds` が全 Requirement で共有される**(56 の宿題を消化)/ リーダー起動能力も TargetSpec を運べる / **墓地からの召喚は対象選択を運べない** |
| 風文明の Ver1.1(Batch 48) | **`ON_TURN_END` は既定で1ターンに2回効く**(両者の場を回すため) |

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
QTE Battle の開発を継続する。Batch 58(区分5の15枚 + 裁定が付いた積み残し9枚)を行う。

読む順:
1. プロジェクトナレッジ内の `notes/ver11-migration-plan.md`
2. プロジェクトナレッジ内の `claude/qte-handoff-v64.md`(本ファイル)
3. ★★プロジェクトナレッジ内の `notes/rework-triage.md`(★4-1章の消化状況と、
   4章の区分5 一覧)
4. プロジェクトナレッジ内の `notes/batch56-design-notes.md`
   (56前半=1〜4章 / 56後半=6〜10章。★8章「見つかった実装の穴」は必読)
5. `notes/qte-pitfalls.md` の該当節
6. ★★プロジェクトナレッジ内の `notes/batch55-ruling-requests.md`
   —— 裁定260〜275(16件)の回答が揃っているか確認。揃っていない裁定に関わる
   カードには着手しない(裁定184)。

環境:
7. git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git
8. 接続フォルダの m2repo.zip を device_stage_files で取り込んで /root/m2work へ展開し、
   mvn -o -B "-Dmaven.repo.local=/root/m2work/repository" test を回す。
   ★549件全緑が出発点である。緑でなければ止めて報告する。
9. 57 の反映を本ファイルの「57の確認項目」で照合し、verify 543/543 と
   report_effects の「未実装1枚(剛火の将)」、rework_triage.py --check と
   check_leader_abilities.py が両方 OK であることを確認する。

作業:
10. 本ファイル1章「58 がやること」に従う。
    ★設計解説は新規ファイル `notes/batch58-design-notes.md` に書く。
    ★試験は新規ファイル `Batch58ReworkTest.java` に書く。
    ★壊し検証は `tools/batch58_break_check.py` を新設し、改変は「軸」ごとに1件ずつ当てる。
11. 納品は4章のとおり。

呼び方は「クロエ」、こちらの呼び方は「マスター」で。会話は日本語カジュアル体、
ドキュメントは通常文体(である調)で。
```

## 6. 57 完了時点の積み残し

### マスターにお願いすること

- **Eclipse で refresh(F5)→ Run As → JUnit Test。**期待: **全549件が緑**。
- ★★**裁定依頼(`notes/batch55-ruling-requests.md`)への回答をお願いします。**
  **260〜274 の15件に加えて、末尾に 275(黄泉の召喚主)を1件足しました。**
  合わせて16件です。これが決まらないと 58 は区分5 の半分しか進められません。
- 実機確認: JS は1行も触っていないので、盤面の見た目・操作感は無変化です。
  遊び味が変わるのは **《安らぎのガーディアン》**(回復が実質半分になる)と
  **《冥府の禁皇》**(起動能力が別物)、**《禁忌の代償》**(除去 → 蘇生)の3枚が特に大きいです。
- 問題なければ **自分で git commit / push**。

### 継続中の積み残し

- 区分5(15枚)が未着手。うち7枚は裁定268〜274 待ち。
- 裁定260〜275(16件)が未回答。
- 《黄泉の召喚主》は暫定ガードのみ。裁定275 で本体を決める。
- 《剛火の将》の常在効果は未実装のまま(未実装1枚の正体)。
- `pendingFireMinionDiscount` 関連の死んだコードの掃除も 58 で。
- `unlimitedCopies` に乗ったままの死んだ分岐が3箇所。無害。59で掃除。
- 裏向きマナと `fireManaPlaced` の非対称。59へ。
- 35〜57 の実機確認が未報告のまま(46bと48は報告あり)。
- 進化召喚がモアニールの登場置換で止まったときの素材の扱い(裁定232)。実機で違和感の
  有無を確認してほしい。
- 《英霊・コレキ》の「相手のターン中は止めない」を本物の入口から観測できない。
  ★57 で《絶望の連鎖》の「そうしたら」も同じ立場のものが1件増えた
  (`batch56_break_check.py` の `EXPECTED_NG` に理由つきで記録してある)。
- `qte-cards.json` の削除(59)。区分5が終わるまで消さないこと。
- 新カード66枚のテキストの目視校正(46aからの持ち越し)。
- `qte-project-reference.md` の1章が古い(2026-07-21)。59で更新。
- 手動モード関連の積み残しは一時停止中。
