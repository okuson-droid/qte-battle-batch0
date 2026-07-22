# QTE 対戦アプリ — 引き継ぎ書

最終更新: 2026-07-22 (Batch 12a 実装完了時点)
次の作業: **Batch 12b(風文明28枚の登録)。Sonnet 5・拡張思考不要**

このファイルは、新しいチャットで作業を再開するための最小限の情報をまとめたものである。
ゲームルール・設計判断の詳細は `qte-project-reference.md`、
カードの詳細は `qte-cards.json`、12a で作った土台の設計は
`設計解説/batch12a-design-notes.md` を参照する(重複記載しない)。

---

## 0. 最初にやること

1. `qte-project-reference.md` を読む(唯一の正)。
2. `設計解説/batch12a-design-notes.md` を読む(12b が乗る土台。特に a9 の節)。
3. ソースコードを取得する。**zipのアップロードは不要**。

```
https://codeload.github.com/okuson-droid/qte-battle-batch0/zip/refs/heads/main
```

4. この環境では **Maven ビルドができない**。納品前の検証は `tools/` の機械チェックで行い、
   型エラーは発注者の手元のビルドで拾う。

---

## 1. 現在の状態

| 項目 | 状態 |
|---|---|
| 完了バッチ | Batch 0〜12a |
| 実装済み文明 | 水28 / 火28 / 闇28 / 光28 = 112枚 |
| 転記済み総数 | 145枚(水・火・闇・光・風の28枚ずつ + 土4 + 文明なし1) |
| 風文明の状態 | **転記完了・土台(a1〜a9)実装済み。カード28枚の登録が 12b の作業** |
| 公開URL | https://qte-battle-batch0.onrender.com/ |
| 静的ファイルのバージョン | `battle.js(v=12)` / `battle.css(v=10)` |

### 直近の内容(前チャット: Batch 12a 実装)

- 台帳 `qte-cards.json` の notes 3枚(QTE-0113 / 0134 / 0136)を裁定1に沿って訂正した。
  併せて、リポジトリ側の台帳が119枚(風の転記が未反映)だったため145枚版に差し替えた。
- 新機構 **a1〜a9 をすべて実装**した。25ファイル / +1166行。
- **`pendingReveal` を廃止し `pendingChoice` + `revealedZone` に分離した**(a9)。
  降臨の伝道師も新経路へ移行済み。
- `endTurn` を「後始末」「ターンの受け渡し」に分割した(a9)。
- 機械チェック用スクリプトを **`tools/` としてリポジトリに追加**した
  (12a 着手時に `check_undeclared.py` が失われて作り直しになったため)。

### 12a で確定した裁定(発注者回答 2026-07-22)

1. a1 のカウンタ加算は `playCard` の switch 直後に集約してよい(設計書の3箇所案から変更)。
2. **ミニオンの起動能力の発動も「カードの使用」として数える**(リーダーと同じ扱い)。
3. **禁忌由来のスペルは行き先の置換(a5)を受けない。** 総合ルール3-6の消滅を優先する。

---

## 2. 次の作業: Batch 12b(風文明28枚の登録)

### 2-1. 必ず触る場所(忘れると動かない)

| 場所 | やること |
|---|---|
| `DeckValidator.IMPLEMENTED` | `Civilization.WIND` を追加。**忘れると風のデッキが入口で全部弾かれる** |
| `DeckFactory` | 風のプリセット(メイン40枚 + 禁忌8枚)を追加 |
| `CardEffectRegistry.resolveChoice` | `ResumePoint` の**5件が例外を投げるだけ**になっている。中身を実装する |
| `StatCalculator.maxAttacks` | サイクロン・フェンサー(QTE-0133)を追加 |
| `StatCalculator.maxLeaderAttacks` | 疾風のレイピア(QTE-0130)を追加 |
| `battle.js` の `pickChoiceCandidateByMinion` | 現在は空。MINION種別の割り込み(風護の杖・回帰の風穴)で実装 |

### 2-2. 28枚の分類(設計書 batch12a-wind-design-v2.md 2章)

- **既存機構のみ(8枚)**: 0010 / 0016 / 0115 / 0119 / 0124 / 0129 / 0132 / (0122はa3)
- **a1 のカウンタのみ(6枚)**: 0113 / 0117 / 0120 / 0128 / L009 / L010
- **新機構を使う(14枚)**: 0114 / 0116 / 0118 / 0121 / 0123 / 0125 / 0126 / 0127 /
  0130 / 0131 / 0133 / 0134 / 0135 / 0136

### 2-3. 12a で用意した器の使い方

```java
// 割り込み選択を出す(a9)
ctx.actions().requestChoice(ctx.room(), ctx.owner(),
        PendingChoice.upTo(PendingChoice.Kind.TRASH, candidates, 2,
                ResumePoint.GALE_KNIGHT_RECOVER, "回収するスペルを選んでください"));

// 使用カウンタを読む(a1。自身は含まれない)
int used = ctx.owner().getCardsUsedThisTurn();

// 体力を上げる(a3)
minion.addModifier(new StatModifier(Stat.HP, Operation.ADD, 2, Duration.PERMANENT, cardId));

// 攻撃回数を増やす(a2)
minion.addModifier(new StatModifier(Stat.EXTRA_ATTACKS, Operation.ADD, 1, Duration.THIS_TURN, cardId));

// スペルの行き先を置換する(a5)
ctx.owner().setPendingSpellDisposition(SpellDisposition.TO_DECK_BOTTOM);

// 強化使用の分岐(a5)
if (ctx.enhanced()) { ... }
```

---

## 3. 作業のルール

### モデルと工数

| 作業 | モデル | 拡張思考 |
|---|---|---|
| 転記・台帳更新 | Sonnet 5 | 不要 |
| b系バッチ(カード登録) | Sonnet 5 | 不要 |
| a系バッチ(基盤設計・実装) | Opus 4.8 | 必要 |
| 全文明の整合チェック | Fable 5 | 必要 |

**次のチャットは b系である。** Sonnet 5 でよい。

**1バッチ = 1チャット。** 中断・再開しない。

### 納品の形式

1. 実装 → 2. 機械チェック → 3. zip 化 → 4. 設計解説 `batch12b-design-notes.md`

設計解説の構成: ⚡結論チートシート → 本文(★重要度マーカー) → ✅動作確認手順 →
✅理解確認(details/summaryで答えを隠す) → 次バッチ予告。
文体は「である調」の技術文書(会話文の口調は持ち込まない)。

### 納品前の機械チェック(必須)

**`tools/` にスクリプトを置いてある。** 12a から、失われないようリポジトリで管理している。

```bash
python3 tools/check_all.py .                                        # 項目 1・3・5・6
python3 tools/check_records.py src/main/java                        # 項目 4
python3 tools/check_undeclared.py src/main/resources/static/js/*.js # 項目 8
node --check src/main/resources/static/js/battle.js                 # 項目 7
```

括弧の均衡(項目2)は `check_all.py` に含めていないため、置換編集をしたら目視するか
簡易スクリプトを回すこと。

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
| チェックスクリプトを次チャットに引き継げず作り直し | Batch 12a | `tools/` としてリポジトリ管理 |

### コンテキスト効率

- **ファイル全体の `view` を既定にしない。** まず `grep -n` で当たりをつける。
- 横断編集が要る項目は、着手前に触るファイルを列挙する。
- **チャットを中断・再開しない。**

### 発注者とのやりとり

- 呼び方は「マスター」。口調は user preferences に従う(会話文のみ。ドキュメントは通常文体)。
- 確認事項はまとめて質問する。1つずつ聞かない。
- 確定していない裁定があるカードは実装しない。
- 発注者はコードを書かないが、カード枚数の誤り・ルールの誤読を自力で見つける。
  指摘は率直に、結論から。

---

## 4. 既知の落とし穴

- **ブラウザキャッシュ**: 静的ファイルを変更したら `battle.html` の `?v=N` を必ず上げる。
- **`CardEffectRegistry` が肥大している**(1200行超)。置換編集時は終端を必ず確認する。
- **ウェポンの攻撃時効果**が `GameService.leaderAttack` の switch に7件溜まっている。
  **12b で風護の杖(8件目)が加わるが、a9 の割り込みを経由させ、switch には足さないこと。**
  既存7件の移設は独立バッチで行う。
- **`AutoChoice` は暫定策。** 闇文明5枚(冥界神ハデス・死者蘇生・執念の暗殺者・
  不滅のネクロマンサー・死霊の収鎌)の a9 への移行は**独立バッチ**で行う。12b では触らない。
- **リーダーへの戦闘ダメージ**はトリガーを通らない(Batch 8 からの持ち越し。未着手)。
- **`TargetSpec.Kind` は5種類(HAND/MINION/MANA/TRASH/WEAPON)のまま増やさない。**
  合計指定は `combinedTotal` で解いてある。
- **`PendingChoice.Kind` と `TargetSpec.Kind` は別物である。** 前者は解決中の選択、
  後者は使用宣言時の対象指定。混同しないこと。

---

## 5. この先の予定

1. **Batch 12b: 風文明28枚の登録**(次チャットの作業)
2. 土文明の転記完了(現在4枚)→ 実装(a系→b系)。**a2 と a4 は土でもそのまま使える見込み**
3. ウェポン攻撃時効果の `CardEffectRegistry` への移設(独立バッチ)
4. 闇文明5枚の `AutoChoice` → 割り込み選択(a9)への移行(独立バッチ)
5. 全6文明168枚が揃った時点での整合チェック(Fable 5)

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
