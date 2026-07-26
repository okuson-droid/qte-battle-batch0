# QTE 対戦アプリ — 引き継ぎ書

最終更新: 2026-07-25 (Batch 13c 実装完了・攻撃時の割り込み選択 + 全6文明の解禁)
次の作業: **全6文明168枚の整合チェック**（または 2 章の候補）

このファイルは、新しいチャットで作業を再開するための最小限の情報をまとめたものである。
ゲームルール・設計判断の詳細は `qte-project-reference.md`、カードの詳細は `qte-cards.json`、
割り込み選択と文明解禁は `設計解説/batch13c-design-notes.md`、
土のカード登録は `設計解説/batch13b-design-notes.md`、
土の土台は `設計解説/batch13a-design-notes.md` を参照する。

---

## 0. 最初にやること

1. `qte-project-reference.md` を読む(唯一の正)。
2. 作業に応じて該当する `設計解説/batchNN-design-notes.md` を読む。
3. ソースコードを取得する。**zipのアップロードは不要**。

```
https://codeload.github.com/okuson-droid/qte-battle-batch0/zip/refs/heads/main
```

4. **カード台帳の注意。** アプリが読むのは `src/main/resources/cards/qte-cards.json`(169枚)である。
   プロジェクトフォルダ側の台帳が最新であり、両者が食い違う場合はプロジェクトフォルダ側を正とする。
5. この環境では **Maven ビルドができない**(Maven Central が到達不可)。納品前の検証は `tools/` の
   機械チェックで行い、型エラーは発注者の手元のビルドで拾う。

---

## 1. 現在の状態

| 項目 | 状態 |
|---|---|
| 完了バッチ | Batch 0〜13c |
| 実装済み文明 | 水28 / 火28 / 闇28 / 光28 / 風28 / **土28** = **168枚(全6文明完成)** |
| 転記済み総数 | 169枚(6文明×28枚 + 文明なし1) |
| 台帳(resources) | 169枚(`src/main/resources/cards/qte-cards.json`) |
| 公開URL | https://qte-battle-batch0.onrender.com/ |
| 静的ファイルのバージョン | `battle.js(v=13)` / `battle.css(v=10)`(13a〜13cとも変更なし)<br>`deck-builder.js(v=10)` / `deck-builder.css(v=10)`(13cで変更) |
| 選択可能なリーダー | **全6文明12体**(13cで風を解禁) |

### 直近の内容(前チャット: Batch 13c — 割り込み選択と全6文明の解禁)

1. **地砕きの突撃兵(0155)の攻撃時マナ回収を、自動選択から割り込み選択(a9)へ移行した。**
   - 割り込みの器は 12b で完成済みだったため、追加したのは選択の種類
     `PendingChoice.Kind.MANA` と再開先 `ResumePoint.EARTHBREAKER_MANA_RETURN` の2つだけ。
   - 候補は**マナゾーン内の位置**。表向き・裏向きの両方を候補に含める(テキストが向きを限定していない)。
   - **フロントエンドは無変更。** `renderPendingChoice` は候補を汎用のラベルボタンとして描画しており、
     種類ごとの分岐を持たないため、サーバ側でラベルを付けるだけで動く。
   - 位置を指定して1枚戻す `GameActions.returnManaToHandAt` を新設(`manaLeft` の発火まで内包)。
2. **リーダー選択画面とデッキビルダーで全6文明を選べるようにした。**
   - 実装済み文明の定義が3か所(DeckValidator / LobbyController / deck-builder.js)に散っており、
     風と土がその同期漏れで選べなくなっていた。**`DeckValidator` を唯一の正に一本化**し、
     `LobbyController` は直接参照、クライアントへは新設した `/api/implemented-civilizations` で配信する。
   - 並び順が安定するよう `Set.of` → `EnumSet` に変更(列挙体の宣言順: 火水風光闇土)。
   - デッキビルダーのプール配色を6文明ぶんに拡張(`civ-water` 等)。`deck-builder` の `?v=` を10へ。

編集ファイル: `PendingChoice` / `ResumePoint` / `GameActions` / `CardEffectRegistry` /
`GameViewBuilder` / `DeckValidator` / `LobbyController` / `CardApiController` /
`deck-builder.js` / `deck-builder.css` / `deck-builder.html`

### ★既知の限界(要確認・未対応)

- **割り込み選択で戻すマナは、戦闘解決の後に手札へ戻る。** 攻撃処理を途中で止める仕組み(継続)が
  無いため、`requestChoice` は選択待ちにするだけで攻撃は最後まで進む(風護の杖と同じ挙動)。
  戻すマナは戦闘の数値に関与しないため戦闘結果は変わらないが、厳密な順序ではない。
- **`AutoChoice` による自動決定が闇文明5枚と風の嵐の呼び手に残っている。** 割り込み選択への移行は
  独立バッチで行う(地砕きの突撃兵は 13c で移行済み)。
- **突風のまとめ役・暴風の双剣の自己バフ問題(12bからの持ち越し)** は未対応。詳細は
  `batch12b-design-notes.md` 3章。

> 解消済み: 豊穣の地霊主のマナ配置カウンタが自ターン開始時リセットだった件は、13b で数え直し方式
> (`PlayerState.recordManaPlacement`)に改めて解消した。
> 解消済み: 地砕きの突撃兵の攻撃時マナ回収の自動選択近似、および風がリーダー選択に出ない件は、
> 13c で解消した。

---

## 2. 次の作業の候補(優先順位順)

1. **★全6文明168枚の整合チェック**(Fable 5・拡張思考)。文明をまたぐ相互作用(相手の還元マナを
   風のマナ変換が扱う、光のコスト軽減が土スペルに乗る、土の全体除去と守護の噛み合い、など)の通し確認。
2. `AutoChoice` の残り(闇文明5枚+風の嵐の呼び手)の割り込み選択(a9)への移行(独立バッチ)。
   13c で `PendingChoice.Kind.MANA` が増えたため、必要な種類はほぼ揃っている。
3. ウェポン攻撃時効果9件(既存8件+地響きの槌)の `CardEffectRegistry` への移設(独立バッチ)。
4. 突風のまとめ役・暴風の双剣の自己バフ問題への対応(a1のシグネチャ変更を伴う)。
5. リーダーへの戦闘ダメージがトリガーを通らない件(Batch 8からの持ち越し)。

## 3. 作業のルール

### モデルと工数

| 作業 | モデル | 拡張思考 |
|---|---|---|
| 転記・台帳更新 | Sonnet 5 | 不要 |
| b系バッチ(カード登録) | Sonnet 5 | 不要 |
| a系バッチ(基盤設計・実装) | Opus 4.8 | 必要 |
| 全文明の整合チェック | Fable 5 | 必要 |

**1バッチ = 1チャット。** 中断・再開しない(13a+13b を例外的に同一チャットで行った経緯はあるが原則は維持)。

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
python3 tools/check_undeclared.py src/main/resources/static/js/*.js # 項目 8(JS変更時)
node --check src/main/resources/static/js/battle.js                 # 項目 7(JS変更時)
```

**`check_structure.py` を必ず最初に回すこと。** `check_undeclared.py` は JS ファイル(複数可)を
引数に取る(ディレクトリを渡すと失敗する)。JS を変更しないバッチでは 8・7 はスキップしてよい。

`check_all.py` の項目3(カードID実在確認)は `src/main/resources/cards/qte-cards.json` を読む。

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
| 文明の効果を登録したのに遊べない | Batch 13b | 新文明は `DeckValidator.IMPLEMENTED` に追加する |
| 同じ列挙を3か所に書き写して2か所が漏れた | Batch 13c | 実装済み文明は `DeckValidator` を唯一の正とし、APIで配信する |

### コンテキスト効率

- **ファイル全体の `view` を既定にしない。** まず `grep -n` で当たりをつける。
- 横断編集が要る項目は、着手前に触るファイルを列挙する。
- **`str_replace` の `old_str` に「次のメソッドの宣言行」を含めない。**

### 発注者とのやりとり

- 呼び方は「マスター」。口調は user preferences に従う(会話文のみ。ドキュメントは通常文体)。
- 確認事項はまとめて質問する。1つずつ聞かない。
- 確定していない裁定があるカードは実装しない(ただし AutoChoice 近似は確立済みパターンとして許容)。

---

## 4. 既知の落とし穴

- **アプリが読む台帳は `src/main/resources/cards/qte-cards.json`。** プロジェクトフォルダ側の
  台帳とは別ファイル。台帳を更新したら resources 側を必ず差し替える。
- **新しい文明を実装したら `DeckValidator.IMPLEMENTED` に1行足す。** 13c でここが唯一の正になり、
  リーダー選択画面もデッキビルダーもここを参照する(以前は3か所に書き写していて風と土が漏れた)。
- **割り込み選択(a9)を足すときは、種類(`PendingChoice.Kind`)と再開先(`ResumePoint`)と
  `resolveChoice` の分岐、そして `GameViewBuilder.buildPendingChoice` のラベル生成の4点。**
  フロントエンドは汎用描画のため通常は無変更でよい。
- **表向きマナ配置は `GameActions.placeCardInManaFaceUp` の1本に集約されている。** `manaZone.add` を
  直書きしないこと(配置回数の計数と豊穣の地霊主の発火が漏れる)。
- **手札をマナに置くときは cardId を `placeCardInManaFaceUp` に渡す。** 対象に選ばれた手札は検証時点で
  除去済みで渡るため、インデックス版 `placeHandCardIntoManaFaceUp` を使うと二重除去になる。
- **ブラウザキャッシュ**: 静的ファイルを変更したら `battle.html` の `?v=N` を必ず上げる(13bは静的
  ファイル未変更のため据え置き)。
- **`CardEffectRegistry` が肥大している。** 置換編集時は終端を必ず確認する。
- **ウェポンの攻撃時効果**が `GameService.leaderAttack` に9件ある(地響きの槌を追加)。移設は独立バッチ。
  大地の守護盾は防御用でありこのswitchには入らない(別経路の肩代わり)。
- **`AutoChoice` は暫定策。** a9への移行は独立バッチで行う(地砕きの突撃兵もこの対象に加わった)。
- **リーダーへの戦闘ダメージ**はトリガーを通らない(Batch 8からの持ち越し。未着手)。
- **`TargetSpec.Kind` は5種類(HAND/MINION/MANA/TRASH/WEAPON)のまま増やさない。**
- **`TriggerType`**: ON_COMBAT_KILL は撃破した側、ON_DESTROYED_BY_COMBAT は破壊された側で向きが逆。
  ON_EQUIP は装備時(召喚時ではない)。混同しないこと。

---

## 5. この先の予定

2章の候補を参照。全6文明が揃ったため、次は文明をまたぐ整合チェックを想定している。

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
