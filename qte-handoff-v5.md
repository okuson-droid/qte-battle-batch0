# QTE 対戦アプリ — 引き継ぎ書

最終更新: 2026-07-25 (Batch 13a 実装完了・土文明の土台 e1〜e5 + 台帳169枚化)
次の作業: **Batch 13b(土文明28枚のカード登録)**

このファイルは、新しいチャットで作業を再開するための最小限の情報をまとめたものである。
ゲームルール・設計判断の詳細は `qte-project-reference.md`、カードの詳細は `qte-cards.json`、
土の土台の設計は `設計解説/batch13a-design-notes.md`、
風の土台は `設計解説/batch12a-design-notes.md`・`batch12b-design-notes.md` を参照する。

---

## 0. 最初にやること

1. `qte-project-reference.md` を読む(唯一の正)。
2. 次の作業(13b)に応じて `設計解説/batch13a-design-notes.md` を読む。
3. ソースコードを取得する。**zipのアップロードは不要**。

```
https://codeload.github.com/okuson-droid/qte-battle-batch0/zip/refs/heads/main
```

4. **カード台帳の注意。** アプリが読むのは `src/main/resources/cards/qte-cards.json` である。
   13aでこれを土28枚を含む169枚版へ差し替え済み。**プロジェクトフォルダ側の台帳が最新**であり、
   両者が食い違う場合はプロジェクトフォルダ側を正とする。
5. この環境では **Maven ビルドができない**。納品前の検証は `tools/` の機械チェックで行い、
   型エラーは発注者の手元のビルドで拾う。

---

## 1. 現在の状態

| 項目 | 状態 |
|---|---|
| 完了バッチ | Batch 0〜12b + **13a(土の土台)** |
| 実装済み文明 | 水28 / 火28 / 闇28 / 光28 / 風28 = 140枚 |
| 土文明の状態 | **土台(e1〜e5)実装済み・カード効果は未登録(13bで登録)** |
| 転記済み総数 | 169枚(水・火・闇・光・風・土の28枚ずつ + 文明なし1) |
| 台帳(resources) | **169枚に更新済み**(`src/main/resources/cards/qte-cards.json`) |
| 公開URL | https://qte-battle-batch0.onrender.com/ |
| 静的ファイルのバージョン | `battle.js(v=13)` / `battle.css(v=10)`(13aで変更なし) |

### 直近の内容(前チャット: Batch 13a — 土文明の土台)

土文明が要求する新機構5点(e1〜e5)を実装した。カード28枚の登録は 13b で行う。

- **e1 表向きマナ配置。** 土の背骨。山札・手札のカードを表向きでマナに置く入口を
  `GameActions.placeCardInManaFaceUp` の1本に集約。配置回数カウンタ
  `PlayerState.cardsPutToManaThisTurn` と配置イベント `CardEffectRegistry.fireManaPlaced`
  (豊穣の地霊主L012が反応)を新設。**マナチャージ(GameService.chargeMana)もこの入口を通す。**
  薄いラッパー `placeTopOfDeckInManaFaceUp`・`placeHandCardIntoManaFaceUp` を用意。
- **e2 守護盾の肩代わり。** 大地の守護盾(0146)。`GameActions.tryInterceptLeaderAttackWithShield`
  を `GameService.attack`・`leaderAttack` の targetIsLeader 分岐に組み込み済み。
  **13bでの登録は不要**(既に動く)。
- **e3 ON_COMBAT_KILL。** 戦闘で撃破した側に発火する新トリガー(タイタン・ウォリアー0140)。
  `GameService.attack` の破壊判定後、生き残った撃破側に発火。攻撃側・防御側を対称に扱う。
- **e4 ON_EQUIP。** ウェポン装備時効果の入口(ガイア・ハンマー0142)。
  `CardEffectRegistry.fireEquip` を `GameService.equipWeapon` から呼ぶ。
- **e5 リーダー攻撃不可。** 不動の絶対神ガイア(0150)。`RuleGuards.minionAttackDenial` に
  創世神アイリス(0107)と同型の判定を1組追加。

### ★既知の限界(要確認・未対応)

- **豊穣の地霊主の配置回数カウンタは自分のターン開始時にリセットされる**(他のThisTurnカウンタと
  同様)。地砕きの突撃兵が相手ターン中に破壊されて山札→マナが起きた場合など、相手ターン中の
  マナ配置は前回の自分のターンの残り値に加算されうる。現行カードプールでは実害はほぼ無いが、
  厳密には「現在のターン」で数え直す設計が正しい。低優先度。
- **突風のまとめ役・暴風の双剣の自己バフ問題(12bからの持ち越し)**は未対応のまま。
  詳細は `batch12b-design-notes.md` 3章。

---

## 2. 次の作業の候補(優先順位順)

1. **★Batch 13b: 土文明28枚のカード登録**(Sonnet 5・拡張思考不要)。
   `batch13a-design-notes.md` の「7. 再利用で済むもの」の表と「次バッチ予告」に沿って登録する。
   土のプリセットデッキ(EARTH_STARTER, 40枚)を `DeckFactory` に追加する。
2. 全6文明168枚が揃った時点での整合チェック(Fable 5)。
3. ウェポン攻撃時効果8件(既存7件+風護の杖)の `CardEffectRegistry` への移設(独立バッチ)。
4. 闇文明5枚+風の嵐の呼び手の `AutoChoice` → 割り込み選択(a9)への移行(独立バッチ)。
5. 突風のまとめ役・暴風の双剣の自己バフ問題への対応(a1のシグネチャ変更を伴う)。

---

## 3. 作業のルール

### モデルと工数

| 作業 | モデル | 拡張思考 |
|---|---|---|
| 転記・台帳更新 | Sonnet 5 | 不要 |
| b系バッチ(カード登録) | Sonnet 5 | 不要 |
| a系バッチ(基盤設計・実装) | Opus 4.8 | 必要 |
| 全文明の整合チェック | Fable 5 | 必要 |

**1バッチ = 1チャット。** 中断・再開しない。

### 納品の形式

1. 実装 → 2. 機械チェック → 3. zip化 → 4. 設計解説 `batchNN-design-notes.md`

設計解説の構成: ⚡結論チートシート → 本文(★重要度マーカー) → ✅動作確認手順 →
✅理解確認(details/summaryで答えを隠す) → 次バッチ予告。
文体は「である調」の技術文書(会話文の口調は持ち込まない)。

### 納品前の機械チェック(必須)

**`tools/` にスクリプトを置いてある。**

```bash
python3 tools/check_structure.py src/main/java                      # ★最優先(構造の破壊)
python3 tools/check_all.py .                                        # 項目 1・3・5・6
python3 tools/check_records.py src/main/java                        # 項目 4
python3 tools/check_undeclared.py src/main/resources/static/js/*.js # 項目 8
node --check src/main/resources/static/js/battle.js                 # 項目 7
```

**`check_structure.py` を必ず最初に回すこと。**

`check_all.py` の項目3(カードID実在確認)は `src/main/resources/cards/qte-cards.json` を読む。
台帳を更新したらこのファイルを更新すること(13aで169枚に更新済み)。

`check_records.py` は次の2つを不一致として報告するが、いずれも誤検出である。
**不一致が出たら必ず該当行を目視すること。**

- オーバーロードされたコンストラクタの呼び出し(`EffectContext` の7引数版)
- 引数の中に単独の比較演算子(`a > b`)がある呼び出し

**過去の事故と対策:**

| 事故 | いつ | 対策 |
|---|---|---|
| 置換範囲を誤り登録メソッドを5個削除 | Batch 8 | 波括弧の均衡チェック |
| 存在しないアクセサを呼んだ | Batch 11a | `check_all.py` の項目5 |
| 変数の宣言だけ消して使用箇所を残した | Batch 11a | `check_undeclared.py` |
| メソッド宣言行を消し括弧が釣り合って素通り | Batch 12a | `check_structure.py` |
| アプリが読む台帳(resources)が旧版のままで土が4枚 | Batch 13a | 台帳更新時は resources 側も差し替える |

### コンテキスト効率

- **ファイル全体の `view` を既定にしない。** まず `grep -n` で当たりをつける。
- 横断編集が要る項目は、着手前に触るファイルを列挙する。
- **`str_replace` の `old_str` に「次のメソッドの宣言行」を含めない。**

### 発注者とのやりとり

- 呼び方は「マスター」。口調は user preferences に従う(会話文のみ。ドキュメントは通常文体)。
- 確認事項はまとめて質問する。1つずつ聞かない。
- 確定していない裁定があるカードは実装しない。

---

## 4. 既知の落とし穴

- **アプリが読む台帳は `src/main/resources/cards/qte-cards.json`。** プロジェクトフォルダ側の
  台帳とは別ファイル。台帳を更新したら resources 側を必ず差し替える(13aの事故対策)。
- **ブラウザキャッシュ**: 静的ファイルを変更したら `battle.html` の `?v=N` を必ず上げる。
- **表向きマナ配置は `GameActions.placeCardInManaFaceUp` の1本に集約されている。** 土のカードを
  登録するとき、`manaZone.add` を直書きしないこと(配置回数の計数と豊穣の地霊主の発火が漏れる)。
- **`CardEffectRegistry` が肥大している。** 置換編集時は終端を必ず確認する。
- **ウェポンの攻撃時効果**が `GameService.leaderAttack` に8件ある。移設は独立バッチで行う。
  大地の守護盾は防御用ウェポンでありこのswitchには入らない(別経路の肩代わり)。
- **`AutoChoice` は暫定策。** a9への移行は独立バッチで行う。
- **リーダーへの戦闘ダメージ**はトリガーを通らない(Batch 8からの持ち越し。未着手)。
- **`TargetSpec.Kind` は5種類(HAND/MINION/MANA/TRASH/WEAPON)のまま増やさない。**
- **`TriggerType` に土で ON_COMBAT_KILL・ON_EQUIP を追加した。** ON_COMBAT_KILL は撃破した側、
  ON_DESTROYED_BY_COMBAT は破壊された側であり向きが逆。混同しないこと。

---

## 5. この先の予定

2章の候補を参照。次チャットは 13b(土のカード登録)を想定している。

---

## 付録. 主要ファイルのメソッド索引の再生成

**行番号は変わる。着手前に必ず再生成すること。**

```bash
cd src/main/java/com/example/qte
for f in effect/CardEffectRegistry.java game/GameService.java game/GameActions.java \
         effect/RuleGuards.java effect/StatCalculator.java game/view/GameViewBuilder.java \
         game/PlayerState.java game/MinionInstance.java effect/TargetSpec.java; do
  echo "=== $f ==="; grep -n "^    \(public\|private\|protected\).*(" $f | sed 's/ *{$//'
done
grep -n "^function " ../../../../resources/static/js/battle.js | sed 's/(.*//'
```
