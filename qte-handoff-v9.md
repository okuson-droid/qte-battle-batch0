# QTE 対戦アプリ — 引き継ぎ書

最終更新: 2026-08-01 (Batch 15a 完了。Ver.0.4 の基盤4件と13c実装の削除2件)
次の作業: **Batch 15b(カード登録) — Ver.0.4 の火5枚+水12枚の効果反映**

**★新しいチャットを始めるときは 5章「チャット開始テンプレート」を使うこと。**

このファイルは、新しいチャットで作業を再開するための最小限の情報をまとめたものである。
ゲームルール・設計判断の詳細は `qte-project-reference.md`、カードの詳細は `qte-cards.json`、
**Ver.0.4 の変更内容は `notes/ver0.4-transcription-notes.md`、
15a で入れた仕組みは `notes/batch15a-design-notes.md` を読むこと(15b の前提)。**
割り込み選択と文明解禁は `notes/batch13c-design-notes.md`、
土のカード登録は `notes/batch13b-design-notes.md`、
土の土台は `notes/batch13a-design-notes.md` を参照する。

---

## 0. 最初にやること

1. `qte-project-reference.md` を読む(唯一の正)。
2. 作業に応じて該当する `notes/batchNN-design-notes.md` を読む。
3. ソースコードを取得する。**zipのアップロードは不要**。

```
https://codeload.github.com/okuson-droid/qte-battle-batch0/zip/refs/heads/main
```

4. **★台帳が最新かを必ず抜き取り確認する。** アプリが読むのは
   `src/main/resources/cards/qte-cards.json`(169枚)、リポジトリ直下にも同名の台帳がある。
   **15a の冒頭で、Ver.0.4 の転記結果が GitHub にもプロジェクトナレッジにも
   push されていない事故が起きた。** 「反映済み」という記述を信じず、
   直近バッチで変更したはずのカードを2〜3枚読んで値を照合してから着手すること。
5. この環境では **Maven ビルドができない**(Maven Central が到達不可)。納品前の検証は `tools/` の
   機械チェックで行い、型エラーは発注者の手元のビルドで拾う。

---

## 1. 現在の状態

| 項目 | 状態 |
|---|---|
| 完了バッチ | Batch 0〜13c + Ver.0.4転記 + **15a** |
| **台帳の状態** | **Ver.0.4反映済み(52枚更新)。2つの台帳は内容一致・確認待ち0件** |
| **効果の実装状況** | **15a で5枚(L011/0061/0073/0058/0015)完了。残り47枚は15b以降** |
| 実装済み文明 | 水28 / 火28 / 闇28 / 光28 / 風28 / 土28 = **168枚(全6文明完成)** |
| 転記済み総数 | 169枚(6文明×28枚 + 文明なし1) |
| 公開URL | https://qte-battle-batch0.onrender.com/ |
| 静的ファイルのバージョン | `battle.js(v=13)` / `battle.css(v=10)`(**15aは静的ファイル無変更**)<br>`deck-builder.js(v=10)` / `deck-builder.css(v=10)` |
| 選択可能なリーダー | 全6文明12体 |

### 直近の内容(前チャット: Batch 15a — 基盤4件 + 削除2件)

Ver.0.4 で必要になった仕組みを実装した。**Java 6ファイルのみの変更で、
JavaScript・HTML・CSS・カード台帳は無変更。** 詳細は `notes/batch15a-design-notes.md` を参照。

- **ウェポンの寿命。** `PlayerState.weaponAttackedThisTurn` を `GameService.leaderAttack` で立て、
  `finishEndTurnCleanup` で `destroyOwnWeapon` を呼ぶ。禁忌の消滅は既存の
  `sendToTrashOrRestore` が自動処理するため追加実装なし。フラグを落とすのは
  `GameActions.onWeaponLeftPlay` の1箇所のみ(破壊・付け替えの合流点)。
- **ミニオンゾーン上限の動的化。** `PlayerState.getMinionZoneLimit()` を新設し
  `isMinionZoneFull()` の中身を差し替えた。呼び出し側8箇所は無変更。
  定数は `DEFAULT_MINION_ZONE_LIMIT(6)` と `MAX_MINION_ZONE_LIMIT(8)` の2本立て。
- **ウェポンの新発火口。** `TriggerType` に `ON_ALLY_MINION_ATTACK` /
  `ON_ALLY_MINION_ENTER` を追加し、`CardEffectRegistry.fireAllyMinionEvent` から発火する。
  発火箇所は `GameService.attack` / `GameService.summonToField` /
  `GameActions.putIntoFieldByEffect` の3箇所。
- **回復量の計数。** `healLeader` に発生源カードIDの引数を追加(`damageLeader` と同じ形)。
  文明別の累計回復量を `EnumMap` で保持する。呼び出し10箇所すべてに発生源を配線した。
- **削除2件を実施。** 大地の巨頭(L011)の `leaderAbilities` 登録、
  地脈の覚醒(0015)の `StatCalculator` 動的コスト分岐。
- **旧実装の撤去。** レーヴァテイン(0061)・冥魔剣(0073)を
  `GameService.leaderAttack` の switch から外した(残すと二重発動になる)。

編集ファイル: `PlayerState` / `GameActions` / `GameService` / `TriggerType` /
`CardEffectRegistry` / `StatCalculator` の6本。

### ★既知の限界(要確認・未対応)

- **★回復量の数え方が未確定。** 「実際にLPが増えた分」で数えている
  (LP19で4回復なら+1計上)。「効果が回復させようとした量」で数える読みもありうる。
  変更する場合は `GameActions.healLeader` の `int healed = ...` の1行のみ。
- **ウェポンの寿命がUIに現れない。** 「攻撃済みでターン終了時に壊れる」ことはログにしか出ない。
  `PlayerView` に真偽値を1つ足して装備欄に印を出すのが望ましいが、
  JavaScript 変更とキャッシュバスティングを伴うため 15a では見送った。
- **Ver.0.4 の残り47枚は未実装。** 台帳のテキスト・数値のみ更新済み。
  スタッツ・キーワードのみの変更は登録側の作業が不要なものが多いが、
  テキストが変わったカードは効果の登録を書き直す必要がある。
  一覧は `notes/ver0.4-transcription-notes.md` 5章。
- **割り込み選択で戻すマナは、戦闘解決の後に手札へ戻る。** 攻撃処理を途中で止める仕組み(継続)が
  無いため、`requestChoice` は選択待ちにするだけで攻撃は最後まで進む(風護の杖と同じ挙動)。
- **`AutoChoice` による自動決定が闇文明5枚と風の嵐の呼び手に残っている。** 割り込み選択への移行は
  独立バッチで行う(地砕きの突撃兵は 13c で移行済み)。
- **突風のまとめ役・暴風の双剣の自己バフ問題(12bからの持ち越し)** は未対応。詳細は
  `notes/batch12b-design-notes.md` 3章。

> 解消済み: 大地の巨頭の起動能力・地脈の覚醒の動的コストは 15a で削除した。
> 解消済み: レーヴァテイン・冥魔剣の旧「リーダー攻撃時」効果は 15a で移設した。
> 解消済み: 台帳が Ver.0.4 未反映だった件は、15a 冒頭で検出し発注者の push により解消した。

---

## 2. 次の作業の候補(優先順位順)

1. **★Batch 15b / 15c / 15d(Sonnet 5)。** Ver.0.4 の残り47枚の効果を文明ごとに反映する。
   変更内容の一覧は `notes/ver0.4-transcription-notes.md` 5章。
   **15a で5枚(L011 / 0061 / 0073 / 0058 / 0015)は完了しており、対象外である。**

   47枚を1チャットで扱うのは重いため、次の3分割を推奨する。

   | バッチ | 文明 | 枚数 | 対象ID |
   |---|---|---|---|
   | 15b | 火 + 水 | 17 | L004 0063 0064 0060 0057 / 0029 0034 0042 0023 0024 0031 0028 0033 0021 0041 0032 0035 |
   | 15c | 土 + 風 | 15 | 0137 0147 0139 0148 0143 0009 0008 / 0133 0117 0118 0135 0134 0120 0136 0131 |
   | 15d | 光 + 闇 | 15 | 0018 0106 0091 0014 / 0088 0072 0085 0080 0068 0084 0069 0071 0070 0081 0006 |

   **★「スタッツのみの変更だからコード作業は不要」と一覧の見た目で判断しないこと。**
   無尽蔵の巨神(0008)は hp の変更だけに見えるが `StatCalculator` に登場し、
   百獣の王ベヒーモス(0147)は召喚時効果を新規追加するのに現在コードに一切登場しない。
   **1枚ずつ `grep -rn "QTE-XXXX" --include=*.java` で照合してから可否を判断する。**
2. 全6文明168枚の整合チェック(Fable 5・拡張思考)。15b 以降の完了後に行う。
3. ウェポンの寿命のUI表示(`PlayerView` に1項目追加 + `battle.js`)。
4. `AutoChoice` の残り(闇文明5枚+風の嵐の呼び手)の割り込み選択(a9)への移行(独立バッチ)。
5. **ウェポン攻撃時効果7件の `CardEffectRegistry` への移設(独立バッチ)。**
   15a で 0061・0073 が抜けて9件から7件になった。
   移設先の枠組みは 15a の `fireAllyMinionEvent` と同じ考え方で作れる。
6. 突風のまとめ役・暴風の双剣の自己バフ問題への対応。
7. リーダーへの戦闘ダメージがトリガーを通らない件(Batch 8からの持ち越し)。

## 3. 作業のルール

### モデルと工数

| 作業 | モデル | 拡張思考 |
|---|---|---|
| 転記・台帳更新 | Sonnet 5 | 不要 |
| b系バッチ(カード登録) | Sonnet 5 | 不要 |
| a系バッチ(基盤設計・実装) | Opus 4.8 | 必要 |
| 全文明の整合チェック | Fable 5 | 必要 |

**1バッチ = 1チャット。** 中断・再開しない。

**★b系バッチで基盤の新設が必要だと判明したカードは、その場で実装しない。** 飛ばして記録し、
バッチの最後にまとめて報告する。b系は「既にある仕組みにカードを載せる」バッチであり、
基盤の設計はa系(Opus・拡張思考)で行う。判断がつかない場合も同じ扱いにする。
これは「中断禁止」と衝突しない。**バッチは飛ばした分を報告して完了させる**からである。

### 納品の形式

1. 実装 → 2. 機械チェック → 3. zip化 → 4. 設計解説 `notes/batchNN-design-notes.md`

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
**不一致が出たら必ず該当行を目視すること。** 15a 時点の既知の3件は
`GameActions`(EffectContext 7引数)・`GameService`(EffectContext 7引数)・
`CardEffectRegistry`(LeaderAbilitySpec 内の `> 0`)である。

- オーバーロードされたコンストラクタの呼び出し(`EffectContext` の7引数版)
- 引数の中に単独の比較演算子(`a > b`)がある呼び出し

**機械チェックが見ない範囲は自分で確認すること。** 項目5が解決するのは
`actions.` / `effects.` / `stats.` / `guards.` の4つの接頭辞に限られる。
`player.` や `ctx.owner().` 経由で `PlayerState` のアクセサを呼ぶ場合は検査対象外であり、
Lombok が生成する名前(`boolean` は `isXxx()`)を含めて目視で確認する。

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
| バランス調整資料の「変更前」記述が台帳と食い違う | Ver.0.4転記 | 台帳の値を信じて進めるか要確認 |
| **転記した台帳が GitHub に push されず、次バッチが旧データで着手しかけた** | **Ver.0.4→15a** | **着手前に台帳の値を抜き取り照合する(0章-4)** |
| **新メソッドを既存メソッドのJavadocと本体の間に挿入した** | **Batch 15a** | **`str_replace` の挿入位置は、直前がコメント終端(`*/`)でないことを確認する** |

### コンテキスト効率

- **ファイル全体の `view` を既定にしない。** まず `grep -n` で当たりをつける。
- 横断編集が要る項目は、着手前に触るファイルを列挙する。
- **`str_replace` の `old_str` に「次のメソッドの宣言行」を含めない。**
- **同一文字列が多数ある一括置換は、Python で「出現回数が期待どおりか」を検証してから行う。**
  15a の `healLeader` 10箇所はこの方式で配線した。

### 発注者とのやりとり

- 呼び方は「マスター」。口調は user preferences に従う(会話文のみ。ドキュメントは通常文体)。
- 確認事項はまとめて質問する。1つずつ聞かない。
- 確定していない裁定があるカードは実装しない(ただし AutoChoice 近似は確立済みパターンとして許容)。
- **前提が崩れていることに気づいたら、作業を進める前に報告する。** 15a では台帳が
  未反映であることを冒頭で報告し、push を待ってから着手した。

---

## 4. 既知の落とし穴

- **アプリが読む台帳は `src/main/resources/cards/qte-cards.json`。** リポジトリ直下の
  台帳とは別ファイル。台帳を更新したら両方を差し替え、**GitHub に push すること。**
- **新しい文明を実装したら `DeckValidator.IMPLEMENTED` に1行足す。** ここが唯一の正であり、
  リーダー選択画面もデッキビルダーも `GET /api/implemented-civilizations` 経由でここを参照する。
- **★ウェポンが場を離れる処理を足すときは `GameActions.onWeaponLeftPlay` を必ず通す。**
  詠唱の宝珠の発動、暴風の双剣の加算リセット、**ウェポン攻撃フラグの解除**の3つが
  ここに集約されている。直接 `setEquippedWeapon(null)` を書くとすべて漏れる。
- **★`ON_ENTER` を発火する箇所を増やしたら、隣の `fireAllyMinionEvent(ON_ALLY_MINION_ENTER, ...)`
  も足すこと。** 現在の発火箇所は `GameService.summonToField` と
  `GameActions.putIntoFieldByEffect` の2箇所(蘇生は後者に委譲されるため自動的に含まれる)。
  `ON_ATTACK` 側は `GameService.attack` の1箇所。
- **★ミニオンゾーンの上限は `PlayerState.getMinionZoneLimit()` が唯一の正。**
  定数 `DEFAULT_MINION_ZONE_LIMIT` を直接読まないこと。
  静的に上限が要る場所(`TargetSpec`)では `MAX_MINION_ZONE_LIMIT`(=8、天井)を使う。
- **割り込み選択(a9)を足すときは、種類(`PendingChoice.Kind`)と再開先(`ResumePoint`)と
  `resolveChoice` の分岐、そして `GameViewBuilder.buildPendingChoice` のラベル生成の4点。**
  フロントエンドは汎用描画のため通常は無変更でよい。
- **表向きマナ配置は `GameActions.placeCardInManaFaceUp` の1本に集約されている。** `manaZone.add` を
  直書きしないこと(配置回数の計数と豊穣の地霊主の発火が漏れる)。
- **手札をマナに置くときは cardId を `placeCardInManaFaceUp` に渡す。** 対象に選ばれた手札は検証時点で
  除去済みで渡るため、インデックス版 `placeHandCardIntoManaFaceUp` を使うと二重除去になる。
- **ブラウザキャッシュ**: 静的ファイルを変更したら `battle.html` の `?v=N` を必ず上げる
  (15a は静的ファイル未変更のため据え置き)。
- **`CardEffectRegistry` が肥大している。** 置換編集時は終端を必ず確認する。
- **ウェポンの攻撃時効果**が `GameService.leaderAttack` に**7件**ある(15a で 0061・0073 が抜けた)。
  移設は独立バッチ。大地の守護盾は防御用でありこのswitchには入らない(別経路の肩代わり)。
- **リーダーへの戦闘ダメージ**はトリガーを通らない(Batch 8からの持ち越し。未着手)。
- **`TargetSpec.Kind` は5種類(HAND/MINION/MANA/TRASH/WEAPON)のまま増やさない。**
  一方 `TriggerType` は拡張してよい(15a で2種追加した)。
- **`TriggerType`**: ON_COMBAT_KILL は撃破した側、ON_DESTROYED_BY_COMBAT は破壊された側で向きが逆。
  ON_EQUIP は装備時(召喚時ではない)。**ON_ATTACK は攻撃した本人、
  ON_ALLY_MINION_ATTACK はその持ち主のウェポン**で反応する主体が違う。混同しないこと。
- **バランス調整資料の読み方(Ver.0.4で確立)。** 「変更前→変更後」の矢印は、変更後の行に
  スタッツ・キーワード・テキストを毎回完全に書き直す形式。書かれていない項目は「変更なし」を
  意味する。資料の「変更前」記述が現行の台帳と食い違うことがある(剛火の将で発生)。
  その場合は台帳を信じるか資料を信じるかを都度確認する。

---

## 5. チャット開始テンプレート

**新しいバッチを始めるときは、次の文面の `<>` を埋めて冒頭に貼る。**
この手順は Batch 15a の事故(台帳が push されておらず、旧データで着手しかけた)を受けて
ルール化したものである。**5番の台帳確認を省略しない。**

```
QTE Battle(カード対戦アプリ)の開発を継続する。以下の手順で作業を始めてほしい。

1. プロジェクトナレッジ内の `qte-project-reference.md` を読む
   (ゲームルール・設計判断の唯一の正)。
2. プロジェクトナレッジ内の `<最新の引き継ぎ書>` を読む
   (直近の状態・次の作業・既知の落とし穴)。
3. プロジェクトナレッジ内の `<今回のバッチの前提ドキュメント>` を読む。
4. ソースコードを取得する(zipのアップロードは不要)。

https://codeload.github.com/okuson-droid/qte-battle-batch0/zip/refs/heads/main

5. 実装に入る前に台帳の抜き取り確認を行う。`<今回期待する値>` が反映されているかを
   `src/main/resources/cards/qte-cards.json` とリポジトリ直下の `qte-cards.json` の
   両方で照合する。食い違っていたら着手せず報告すること。

次の作業は Batch <NN>(<種別>) で、<やること>。

このバッチでは <やること> のみを行う。<やらないこと> は次のバッチで扱う。

<a系なら: a系バッチのため Opus + 拡張思考で作業してほしい。>
<b系なら: b系バッチのため Sonnet で作業してほしい。基盤の新設が必要だと判明した項目は
その場で実装せず、飛ばして記録し、バッチの最後にまとめて報告すること。>

1バッチ1チャットの原則を守り、中断しないこと。

呼び方は「クロエ」、こちらの呼び方は「マスター」で。会話は日本語カジュアル体でお願い。
```

### テンプレートの各項目の意図

| 項目 | なぜ必要か |
|---|---|
| 1〜3 の読む順 | ルール(不変) → 状態(可変) → 今回の前提、の順に読ませる。逆順だと前提を状態で上書きしてしまう |
| 4 の取得URL | zip添付よりコンテキスト消費が少なく、常に最新が取れる |
| **5 の台帳確認** | **ドキュメントの「反映済み」という記述ではなく実データを確認させる。15aの事故対策** |
| 「〜のみを行う」 | 範囲の膨張を防ぐ。同時に「やらないこと」を書くことで境界が両側から決まる |
| モデル指定 | a系は設計判断を伴うため拡張思考が要る。b系は登録作業のため不要 |
| b系の飛ばし規則 | b系が基盤を作り始めると設計が場当たりになる。3章の規則と対になる |
| 呼称・口調 | 毎チャット指定が要る(user preferences は自動適用されるが、明示すると確実) |

### 前提ドキュメントの選び方

`<今回のバッチの前提ドキュメント>` には、**直前のバッチの設計解説を必ず含める。**
そのバッチが作った仕組みの上に次のバッチが載るためである。

| 次のバッチ | 前提ドキュメント |
|---|---|
| 15b / 15c / 15d | `ver0.4-transcription-notes.md`(変更一覧) + `batch15a-design-notes.md`(乗る仕組み) |
| 整合チェック | 全文明の設計解説 + `qte-cards.json` |
| 独立バッチ(移設・改修) | 対象機能を作ったバッチの設計解説 |

---

## 6. この先の予定

Ver.0.4 の残り47枚を 15b・15c・15d の3バッチで反映し、その後に全6文明の整合チェックを行う。

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
