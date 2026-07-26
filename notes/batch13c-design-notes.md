# Batch 13c 設計解説 — 攻撃時の割り込み選択と、全6文明の解禁

本バッチの内容は 2 つである。

1. 地砕きの突撃兵（QTE-0155）の攻撃時マナ回収を、自動選択の近似から**本物の割り込み選択**に置き換えた。
2. リーダー選択画面とデッキビルダーで**全 6 文明**を選べるようにした。あわせて、
   「どの文明が実装済みか」の定義が 3 か所に散っていた状態を 1 か所へまとめた。

---

## ⚡ 結論チートシート

- **割り込み選択の器（a9）は 12b で完成していた。** 今回足したのは「マナゾーンから選ぶ」という
  選択の種類（`PendingChoice.Kind.MANA`）と、再開先 `ResumePoint.EARTHBREAKER_MANA_RETURN` だけ。
- **フロントエンドの変更は不要だった。** `renderPendingChoice` は候補をラベル付きボタンとして
  汎用的に描画しており、種類ごとの分岐を持たない。種類を増やしてもそのまま動く。
- **候補は「マナゾーン内の位置」で表す。** 表向き・裏向きの両方を候補に含める
  （カードテキストが向きを限定していないため。流転の智者の `TargetSpec.Kind.MANA` と同じ扱い）。
- **マナが実際に手札へ戻るのは戦闘解決の後である。** 攻撃の途中で処理を止める仕組みは無いため、
  選択だけを保留して戦闘は最後まで進む。風護の杖と同じ挙動であり、戦闘結果には影響しない。
- **実装済み文明の定義を `DeckValidator` に一本化した。** 以前は Java 側に 2 か所、JS 側に 1 か所、
  合計 3 か所へ同じ列挙を書き写していた。実際に風と土がその同期漏れで選べなくなっていた。
  新設した `/api/implemented-civilizations` でクライアントへ配信する。

---

## ★★★★ 1. 攻撃時の割り込み選択（地砕きの突撃兵）

### 1-1. 何が足りなかったか

地砕きの突撃兵のテキストは「攻撃時、自分のマナから 1 枚**選び**手札に戻す」である。
13b では、ON_ATTACK のタイミングで対象を選ばせる仕組みが無いという理由で、
`returnFaceUpManaToHand`（最後に置かれた表向きマナを 1 枚戻す）による自動選択で近似していた。

改めて調べると、**割り込み選択の器そのものは Batch 12b で完成していた**。
`PendingChoice`（何を何個選ぶか）・`ResumePoint`（どの効果の続きか）・
`GameActions.requestChoice`（選択待ちにする）・`GameService.resolveChoice`（答えを検証して再開する）・
`CardEffectRegistry.resolveChoice`（効果の続きを実行する）が揃っている。
足りなかったのは「マナゾーンから選ぶ」という**選択の種類**だけだった。

### 1-2. ★追加したのは種類 1 つと再開先 1 つ

```java
// PendingChoice.Kind
MANA   // 候補は manaZone 内の位置。表向き・裏向きのどちらも候補になりうる

// ResumePoint
EARTHBREAKER_MANA_RETURN   // 地砕きの突撃兵: 攻撃時に手札へ戻すマナを選ぶ
```

効果の登録側は、候補の数で 3 通りに分ける。降臨の伝道師・風護の杖と同じ流儀である。

```java
private void requestEarthbreakerManaReturn(EffectContext ctx) {
    int size = ctx.owner().getManaZone().size();
    if (size == 0) { /* 不発（ログのみ） */ return; }
    if (size == 1) { /* 自動決定（選ばせる意味がない） */ return; }
    /* 2枚以上: requestChoice で本人に選ばせる */
}
```

再開側は `CardEffectRegistry.resolveChoice` に分岐を 1 つ足すだけである。

```java
case EARTHBREAKER_MANA_RETURN -> {
    int idx = Integer.parseInt(chosen.get(0));
    ctx.actions().returnManaToHandAt(ctx.room(), ctx.owner(), idx);
}
```

### 1-3. ★フロントエンドを触っていない理由

`battle.js` の `renderPendingChoice` は、候補を `cand.label` のボタンとして並べるだけで、
`choice.kind` による分岐を持たない（`MINION` のときに盤面のミニオンを直接クリックできる、という
補助経路があるだけ）。したがって種類を増やしても既存の描画がそのまま使える。

サーバ側で必要なのは、候補にどんな**ラベル**を付けるかだけである。
`GameViewBuilder.buildPendingChoice` に `MANA` の分岐を足した。

```java
case MANA -> {
    int idx = Integer.parseInt(id);
    ManaCard mana = player.getManaZone().get(idx);
    CardMaster m = cards.findById(mana.getCardId());
    label = mana.isFaceUp() ? m.name() : m.name() + "(裏向き)";
}
```

裏向きのマナもカード名を出している。マナゾーンのビュー生成（`toManaView`）が
`contentVisible = mana.isFaceUp() || isSelf` としており、**自分の裏向きマナの中身は本人には見える**
という既存の扱いに合わせたためである。

### 1-4. ★「位置」を候補にしてよい理由

`PendingChoice` は候補を文字列で持ち、クライアントは**候補配列内の位置**を送り返す。
`MANA` の候補文字列はマナゾーン内の位置（0 起点）である。

選択待ちの間に位置がずれると、別のマナを戻してしまう。ずれないことは次の 2 点で保証される。

- **選択待ちの間、そのプレイヤーは他の操作を行えない。** `GameService.requireTurnPlayer` が
  「先に選択を解決してください」で塞ぐ（`resolveChoice` 自身はこの検査を通らないため、
  選択操作だけは通る）。カードをプレイしてマナを増減させることはできない。
- **この攻撃の続きで起きうるマナゾーンの変化は、末尾への追加だけである。**
  自身が戦闘で破壊されると ON_DESTROYED が山札の上 1 枚をマナに置くが、追加は末尾であり
  既存の位置は動かない。追加された 1 枚は候補に入っていないため選べないが、これは
  「攻撃時の時点のマナから選ぶ」という解釈として妥当である。

### 1-5. ☆マナが戻るのは戦闘解決の後になる

Java には効果の途中で処理を止めて後から同じ場所へ戻る仕組み（継続）が無い。
そのため `requestChoice` は「選択待ちの状態にする」だけで、**攻撃の処理はそのまま最後まで進む**。
実際の順序は次のようになる。

1. 攻撃宣言 → ON_ATTACK 発火 → 選択待ちにする（マナはまだ動かない）
2. 戦闘ダメージ・破壊判定・ON_COMBAT_KILL まで、攻撃の処理が最後まで進む
3. プレイヤーがマナを選ぶ → そこで初めて手札に戻り、`manaLeft`（ゾーン横断トリガー）が発火する

風護の杖（12b）も同じ挙動である。戻すマナは戦闘の数値に関与しないため、
この遅れが戦闘結果を変えることはない。厳密な「攻撃時に解決してから戦闘」を実現するには
攻撃処理そのものを再開可能にする必要があり、影響範囲が大きいため採らなかった。

### 1-6. ☆`returnManaToHandAt` を新設した理由

マナがマナゾーンを離れたら `manaLeft`（黄泉還る水龍のトリガー）を発火しなければならない。
これは呼び出し側に書かせると必ず忘れる種類の規則なので、
「位置を指定して 1 枚戻す」操作を `GameActions` の 1 メソッドに閉じた。

既存の `returnFaceUpManaToHand` は「向きで自動的に 1 枚選ぶ」用途（風のマナ変換）であり、
役割が異なるためそのまま残している。

---

## ★★★ 2. 全 6 文明の解禁と、実装済み文明の一本化

### 2-1. 同じ列挙が 3 か所に散っていた

「どの文明が実装済みか」は、次の 3 か所に別々に書かれていた。

| 場所 | 用途 | 13c 直前の状態 |
|------|------|----------------|
| `DeckValidator.IMPLEMENTED` | デッキ検証（未実装文明のカードを弾く） | 水火闇光風土（13b で土を追加） |
| `LobbyController.selectableLeaders()` | リーダー選択画面に出す文明 | 水火闇光土（**風が抜けていた**） |
| `deck-builder.js` の `IMPLEMENTED_CIVS` | ビルダーが扱う文明 | 水火闇光（**風も土も抜けていた**） |

風は 12b で完成していたのに選択画面に出ておらず、土は 13b で `DeckValidator` にだけ追加されて
ビルダーには反映されていなかった。**同じ事実を 3 か所に書き写す構造そのものが原因**である。

### 2-2. ★`DeckValidator` を唯一の正にする

`DeckValidator.IMPLEMENTED` を公開アクセサ経由で参照できるようにし、他の 2 か所はそれを使う。

```java
// DeckValidator
public static Set<Civilization> implementedCivilizations() {
    return java.util.Collections.unmodifiableSet(IMPLEMENTED);
}

// LobbyController
private List<CardMaster> selectableLeaders() {
    return DeckValidator.implementedCivilizations().stream()
            .flatMap(civ -> cards.findByCivilization(civ).stream())
            .filter(c -> c.type() == CardType.LEADER)
            .toList();
}
```

クライアント（デッキビルダー）へは新設した API で配信する。

```java
@GetMapping("/api/implemented-civilizations")
public List<CivilizationDto> implementedCivilizations() { ... }
```

```javascript
// deck-builder.js: 文明コードをJSに書き写すのをやめた
Promise.all([
    fetch('/api/cards').then(res => res.json()),
    fetch('/api/implemented-civilizations').then(res => res.json())
])
```

これで、次に文明を追加するときに触るのは `DeckValidator` の 1 行だけになる。

### 2-3. ★`Set.of` から `EnumSet` へ変えた理由

`IMPLEMENTED` はリーダー選択画面の**並び順**にも使われるようになった。
`Set.of` は反復順を保証しないため、そのまま使うとリーダーの並びが実行のたびに変わりうる。
`EnumSet` は列挙体の宣言順（火・水・風・光・闇・土）で反復するため、並びが安定する。

### 2-4. ☆デッキビルダーの文明別配色

禁忌デッキ用のプールには「リーダーと異なる文明」のカードが並ぶ。従来は火文明しか
選択肢が無かったため配色も `.fire`（赤）1 つだけだったが、6 文明が並ぶようになったため
`civ-water` / `civ-fire` / `civ-wind` / `civ-light` / `civ-dark` / `civ-earth` の 6 色に拡張した。
JS 側は `civ-${card.civilization.toLowerCase()}` を付けるだけである。

`deck-builder.js` と `deck-builder.css` を変更したため、`deck-builder.html` の
キャッシュ避けを `v=9` → `v=10` に上げた（`battle.js` / `battle.css` は未変更のため据え置き）。

### 2-5. ☆能力を持たないリーダーがいても壊れない

風の詠唱の風詠士（L010）・土の豊穣の地霊主（L012）・闇の黄泉の召喚主（L006）は
起動能力を持たない（常在型の効果だけを持つ）。`GameViewBuilder.buildLeaderAbility` は
`leaderAbilityOf` が null なら null を返すため、能力ボタンが出ないだけで問題なく動く。

---

## ✅ 動作確認手順

### 割り込み選択（地砕きの突撃兵）

1. 土リーダーで対戦を開始し、地砕きの突撃兵（3 コスト・突進）を召喚する。
2. マナを 2 枚以上にした状態で攻撃する。攻撃宣言の直後に
   「手札に戻すマナを 1 枚選んでください」の候補ボタンが並ぶことを確認する。
   候補には**表向き・裏向きの両方**が並び、裏向きには「(裏向き)」が付く。
3. **選ぶまで他の操作ができない**ことを確認する（別のミニオンで攻撃しようとすると
   「先に選択を解決してください」になる）。ターンエンドも同様に塞がる。
4. 1 枚選ぶと、そのマナが手札に戻り、マナゾーンから消えることを確認する。
5. マナが 1 枚しか無い状態で攻撃すると、選択画面が出ずに自動でそのマナが戻ることを確認する。
   マナが 0 枚なら何も起きず、ログに「マナが無いため、手札に戻せませんでした」と出る。
6. 攻撃で地砕きの突撃兵自身が破壊される状況を作り、破壊時のマナ加速（山札の上 1 枚）が
   起きたうえで、選択がそのまま残っていることを確認する。

### 全 6 文明の解禁

7. トップ画面のリーダー選択に**12 体すべて**（火・水・風・光・闇・土 × 2）が並ぶことを確認する。
8. 風のリーダー（疾風の導き手・詠唱の風詠士）を選んで対戦が始まり、風スターターで遊べることを確認する。
9. デッキビルダーを開き、リーダーのプルダウンに 12 体すべてが並ぶことを確認する。
10. 風または土のリーダーを選び、メインプールに同文明のカードが出ること、
    禁忌プールに他文明のカードが**文明ごとの色分けで**並ぶことを確認する。
11. 作ったデッキをファイルに保存し、ロビーから読み込んで対戦できることを確認する
    （サーバの `DeckValidator` が通ること）。

---

## ✅ 理解確認

<details>
<summary>Q1. 割り込み選択の種類を増やすとき、フロントエンドの変更が要らないのはなぜか。</summary>

`renderPendingChoice` が候補を「ラベル付きのボタン」として汎用的に描画しており、
`choice.kind` による分岐を持たないため。サーバ側で候補にラベルを付けさえすれば、
どんな種類でもそのまま表示・選択できる。種類ごとの見た目を変えたくなったときに初めて
フロントの分岐が必要になる。
</details>

<details>
<summary>Q2. マナの候補を「位置」で持っても安全なのはなぜか。</summary>

選択待ちの間、そのプレイヤーは他の操作を行えない（requireTurnPlayer が塞ぐ）ため、
カードのプレイでマナが増減することがない。この攻撃の続きで起きうる変化は
ON_DESTROYED による末尾への追加だけで、既存の位置は動かないから。
</details>

<details>
<summary>Q3. マナが実際に手札へ戻るのは、戦闘の前か後か。それはなぜか。</summary>

後である。効果の途中で処理を止めて後から戻る仕組み（継続）が無いため、`requestChoice` は
選択待ちの状態にするだけで、攻撃の処理はそのまま最後まで進む。戻すマナは戦闘の数値に
関与しないため、戦闘結果は変わらない。
</details>

<details>
<summary>Q4. 風が選択画面に出ていなかったのはなぜか。同じ問題を防ぐために何をしたか。</summary>

「実装済みの文明」という同じ事実が DeckValidator・LobbyController・deck-builder.js の
3 か所に別々に書かれており、風を実装したときに 2 か所の更新が漏れたため。
DeckValidator を唯一の正とし、LobbyController はそれを直接参照、クライアントへは
`/api/implemented-civilizations` で配信する形にした。次からは 1 行の変更で済む。
</details>

---

## 次バッチ予告

- **全 6 文明 168 枚の整合チェック**（文明をまたぐ相互作用の通し確認）。
- **`AutoChoice` の残りを割り込み選択へ移行**。今回で地砕きの突撃兵が移行し、
  残るのは闇文明 5 枚と風の嵐の呼び手である。
- **ウェポン攻撃時効果 9 件の `CardEffectRegistry` への移設**（`GameService.leaderAttack` の
  switch に直書きされている状態の解消）。
