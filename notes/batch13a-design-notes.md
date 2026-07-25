# Batch 13a 設計解説 — 土文明の土台(新機構 e1〜e5)

対象: 土文明28枚が要求する基盤の実装。カード28枚の登録は Batch 13b で行う。
前提: Batch 12b 完了時点のコードベース。
変更規模: Java 6ファイル(TriggerType / PlayerState / GameActions / CardEffectRegistry /
GameService / RuleGuards)。加えてカード台帳を145枚版から169枚版へ差し替え。

---

## ⚡ 結論チートシート

| 項目 | 一言でいうと | 触った中心 |
|---|---|---|
| **e1** 表向きマナ配置 | 土の背骨。**山札・手札のカードを表向きでマナに置く**入口を1本に集約。配置回数を数え、置くたびにイベントを発火する | `GameActions.placeCardInManaFaceUp` / `PlayerState.cardsPutToManaThisTurn` / `CardEffectRegistry.fireManaPlaced` |
| **e2** 守護盾の肩代わり | リーダーへの攻撃を**ウェポンの破壊で肩代わり**する置換効果 | `GameActions.tryInterceptLeaderAttackWithShield` / `GameService.attack`・`leaderAttack` |
| **e3** 戦闘での撃破 | 「破壊された側」ではなく**「撃破した側」に発火するトリガー**を新設 | `TriggerType.ON_COMBAT_KILL` / `GameService.attack` |
| **e4** 装備時効果 | ウェポンに**装備の瞬間に発動する効果**の入口を作った | `TriggerType.ON_EQUIP` / `CardEffectRegistry.fireEquip` / `GameService.equipWeapon` |
| **e5** リーダー攻撃不可 | 「このミニオンはリーダーを攻撃できない」を判定層に1行追加 | `RuleGuards.minionAttackDenial` |

**この3行だけ覚えて帰るなら:**

1. 土文明が繰り返し要求するのは「表向きでマナに置く」という操作である。既存のマナ配置ヘルパは
   すべて闇文明のための**裏向き**であり、表向きの入口が無かった。だから e1 が要る。
2. 配置の入口を1本に集約したのは、豊穣の地霊主が参照する**配置回数の計数**と、
   置くたびの**イベント発火**を、マナチャージも含めて漏れなく行うためである。
3. 土のカードの多くは新規機構を必要としない。動的ステータス(e2〜e5以外)・還元・
   ON_ATTACK・ON_TURN_END・特殊召喚枠・リーダー起動能力枠は**すべて既存の土台で足りる**。
   本バッチが新設したのは上記5点だけである。

---

## ★★★★ 1. e1 — 表向きマナ配置プリミティブ(本バッチの中心)

### 1-1. 何が無かったか

土文明のカードを効果別に数えると、最も多いのが「カードをマナゾーンに置く」動作である。

| 置く元 | 置き方 | 該当カード |
|---|---|---|
| 山札の上 | 表向き | 大地の精霊グラン / ガイア・リソース / 豊穣の祈り / 大地の恵み / ガイア・ハンマー(装備時) / 地砕きの突撃兵(破壊時) |
| 手札 | 表向き | 苗木植えの精霊 / 大地の巨頭(リーダー起動能力) |
| 手札(マナチャージ) | 表向き | 総合ルール6章-3 の通常マナチャージ |

ところが既存のマナ配置ヘルパ(`putHandCardIntoManaFaceDown`・`putTrashCardIntoManaFaceDown`)は
すべて**裏向き**である。裏向きマナは闇文明が消費する資源であり、土の「表向きで置く」とは意味が違う。
表向きで置く経路は、通常のマナチャージ(`GameService.chargeMana`)が
`manaZone.add(new ManaCard(cardId, false))` を直書きしている1箇所しか無かった。

### 1-2. ★最重要: 配置の入口を1本に集約する

土文明はマナ配置を9枚が要求する。これを各カードで直書きすると、
2つの共通処理が**書き漏れる**。

- **配置回数の計数。** 豊穣の地霊主(L012)は「マナにカードが置かれたとき、そのターン中
  それが2回目なら1ドロー」である。配置経路がバラバラだと計数を全経路に入れ忘れる。
- **マナ上限15枚の判定。** どの経路でも上限を超えて置いてはならない。

そこで **表向きでマナに置く操作をすべて `GameActions.placeCardInManaFaceUp` の1メソッドに集約した。**

```java
public boolean placeCardInManaFaceUp(GameRoom room, PlayerState owner, String cardId) {
    if (owner.getManaZone().size() >= PlayerState.MAX_MANA) {
        room.addLog("マナが15枚のため、これ以上マナに置けません");
        return false;
    }
    owner.getManaZone().add(new ManaCard(cardId, false)); // 第2引数=一時マナか。faceUpは既定true
    owner.setCardsPutToManaThisTurn(owner.getCardsPutToManaThisTurn() + 1);
    effects.fireManaPlaced(contextOf(room, owner, null));
    return true;
}
```

置く元別の2つの薄いラッパーを用意した。どちらも最終的に上記1本を通る。

- `placeTopOfDeckInManaFaceUp` — 山札の上から1枚を引いて置く。山札が空なら何もしない。
- `placeHandCardIntoManaFaceUp(handIndex)` — 手札の指定カードを置く。既存の裏向き版
  `putHandCardIntoManaFaceDown` の表向き版にあたる。

**マナチャージも同じ入口を通す。** `GameService.chargeMana` の直書きを
`actions.placeCardInManaFaceUp(...)` の呼び出しに置き換えた。これにより通常のマナチャージも
配置回数に含まれる(発注者確認済み: マナチャージも豊穣の地霊主の計数に含む)。

> **☆補足: なぜ `ManaCard(cardId, false)` で表向きになるのか。**
> `ManaCard` のコンストラクタ第2引数は `temporary`(一時マナか)であり、faceUp ではない。
> `faceUp` はフィールド初期値が `true` のため、`new ManaCard(cardId, false)` は
> 「非一時・表向き」のマナになる。裏向きにしたい既存ヘルパは生成後に `turnFaceDown()` を
> 呼んでいる。この非対称は Batch 10b(闇)からのもので、本バッチでは踏襲した。

### 1-3. ★配置回数カウンタと fireManaPlaced

`PlayerState` にターン内カウンタを1本追加した。既存の使用カウンタ群と同じ様式である。

```java
private int cardsPutToManaThisTurn = 0; // startTurnReset で0に戻す
```

配置イベントの発火は、`fireManaLeft`(黄泉還る水龍)・`fireLeaderDamaged`(反転の炎鏡)と
同じ「盤面横断イベント」の様式で `CardEffectRegistry.fireManaPlaced` として実装した。
リーダーの常在能力はカードIDの直書きで判定する。

```java
public void fireManaPlaced(EffectContext ctx) {
    PlayerState owner = ctx.owner();
    if ("QTE-L012".equals(owner.getLeader().id())
            && owner.getCardsPutToManaThisTurn() == 2) {
        ctx.room().addLog("【豊穣の地霊主】: このターン2回目のマナ配置により1ドロー");
        ctx.actions().drawCards(ctx.room(), owner, 1);
    }
}
```

**加算はイベント発火の前に置く。** `placeCardInManaFaceUp` はカウンタを進めてから
`fireManaPlaced` を呼ぶ。したがって2回目の配置ではカウンタがちょうど `2` を読む。
豊穣の地霊主の裁定「2回目なら」を `== 2` で表現できる。

> **採用しなかった案: 新しい `TriggerType` 値を足す。**
> ON_MANA_PLACED という enum 値を新設し `fire()` を使う案もあったが、豊穣の地霊主は
> **リーダーの常在能力**であってミニオンではない。`fire()` は場のミニオンを走査する仕組みで
> あり、リーダーには届かない。`fireManaLeft` と同じ専用入口にする方が既存様式に沿う。
> よって enum は増やしていない。

### 1-4. ☆山札切れとマナ上限のときの挙動

- **山札が空のとき**の「山札の上から1枚をマナに置く」は、何も置かない(計数もしない)。
  空の山札からの**ドロー**は敗北(デュエマ準拠)だが、マナに置けないだけでは敗北しない。
  両者は別処理である。
- **マナが15枚のとき**は置けない。`placeTopOfDeckInManaFaceUp` は、引いてしまったカードを
  山札の上へ戻してから false を返す(1枚をロストさせない)。

---

## ★★★ 2. e2 — 大地の守護盾(リーダー被攻撃の置換)

### 2-1. 何が問題だったか

大地の守護盾(QTE-0146)は「自分のリーダーが攻撃されたとき、代わりにこのカードを破壊する」
ウェポンである。ダメージそのものを無効化する置換効果であり、被ダメージトリガーは誘発しない
(発注者確認済み)。

リーダーが攻撃を受ける経路は2つある。

- ミニオンによる攻撃(`GameService.attack` の targetIsLeader 分岐)
- ウェポンを装備したリーダーによる攻撃(`GameService.leaderAttack` の targetIsLeader 分岐)

どちらも `opponent.setLp(opponent.getLp() - damage)` を直書きしており、割り込む余地が無かった。

### 2-2. ★肩代わりを GameActions の1メソッドに閉じ込める

守護盾を持つのは**守られる側**(defender)である。攻撃側のウェポン攻撃時効果とは無関係なので、
既存の「ウェポン攻撃時効果8件のswitch」には足さない。リーダーへLPダメージを与える直前に
呼ぶ肩代わり判定として、`GameActions` に1メソッドを新設した。

```java
public boolean tryInterceptLeaderAttackWithShield(GameRoom room, PlayerState defender) {
    CardMaster weapon = defender.getEquippedWeapon();
    if (weapon == null || !"QTE-0146".equals(weapon.id())) {
        return false;
    }
    room.addLog("【大地の守護盾】がリーダーへの攻撃を肩代わりしました");
    destroyOwnWeapon(room, defender);   // 墓地/消滅への送りは既存処理に任せる
    return true;
}
```

両方の攻撃経路の targetIsLeader 分岐を、この肩代わり判定でくるんだ。

```java
if (!actions.tryInterceptLeaderAttackWithShield(room, opponent)) {
    // 肩代わりが起きなかったときだけ、通常どおりLPを減らす
    opponent.setLp(opponent.getLp() - damage);
    ...
}
```

### 2-3. ☆設計上の要点3つ

- **ダメージが発生しない。** LP減算そのものをスキップするため、被ダメージトリガー
  (ON_LEADER_DAMAGED)は誘発しない。裁定どおりである。
- **1回きり。** 肩代わりでウェポンが破壊されるため、次の攻撃からは守護盾はもう無い。
  フラグ管理は不要で、ウェポンの有無がそのまま「肩代わりできるか」になる。
- **攻撃宣言は成立している。** 肩代わりが起きても、攻撃側の攻撃時効果
  (真珠の三叉槍のドロー等)は通常どおり発動する。守護盾が消すのは**ダメージ**であって
  **攻撃という事象**ではないためである。`leaderAttack` では肩代わり判定の後に
  ウェポン攻撃時効果のswitchが続く構造を保っている。

---

## ★★★ 3. e3 — ON_COMBAT_KILL(戦闘での撃破トリガー)

### 3-1. 「破壊された側」と「撃破した側」は別の向きである

タイタン・ウォリアー(QTE-0140)は「このミニオンが戦闘で相手ミニオンを破壊した時、
相手のリーダーに4ダメージ」を持つ。

既存の `ON_DESTROYED_BY_COMBAT`(ボーン・コレクター)は**破壊された側**に発火する。
タイタンが必要とするのは**撃破した側**への発火であり、向きが逆である(設計判断31:
トリガーには向きがある)。そこで `TriggerType.ON_COMBAT_KILL` を新設した。

### 3-2. ★発火は破壊判定がすべて終わった後

戦闘はお互いのAttackを同時に与え合い、その後にまとめて破壊判定を行う(既存構造)。
ON_COMBAT_KILL は、両者の `checkDestruction` が終わった後に、
**「相手が場を離れ、かつ自分が場に残っている」側にのみ**発火する。

```java
actions.checkDestruction(room, opponent, target, DestructionCause.COMBAT);
actions.checkDestruction(room, player, attacker, DestructionCause.COMBAT);

boolean targetGone = !opponent.getMinionZone().contains(target);
boolean attackerGone = !player.getMinionZone().contains(attacker);
if (targetGone && !attackerGone) {
    effects.fire(TriggerType.ON_COMBAT_KILL, attacker,
            contextOf(room, state, player, attacker, null));
}
if (attackerGone && !targetGone) {
    effects.fire(TriggerType.ON_COMBAT_KILL, target,
            contextOf(room, state, opponent, target, null));
}
```

**攻撃側・防御側のどちらが撃破した場合も対称に扱う。** タイタンは【突進】持ちで攻撃する側の
設計だが、防御側として相手を撃破する状況もありうる。両方向に発火させておくことで、
「このミニオンが戦闘で相手を破壊した時」というテキストに正しく従う。

> **☆相打ち(相討ち)の扱い。** 両者が同時に破壊された場合、`targetGone && attackerGone` と
> なり、どちらの `if` も成立しない。撃破した側が生き残っていないため、ON_COMBAT_KILL は
> 発火しない。破壊されたミニオンの死に際の追撃までは表現しない、という割り切りである。

---

## ★★ 4. e4 — ON_EQUIP(ウェポンの装備時効果)

### 4-1. 装備は召喚ではない

ガイア・ハンマー(QTE-0142)は「装備時に山札の上から1枚を表向きでマナに置く」ウェポンである。
発注者確認により、これは**召喚時ではなく装備の瞬間**に発動する。

ウェポンは `MinionInstance` を持たないため、ミニオンの ON_SUMMON/ON_ENTER の仕組みには
乗らない。既存では【知識】のみが `GameService.equipWeapon` に直書きで装備時発動していた。
これを一般化し、`TriggerType.ON_EQUIP` と発火入口 `CardEffectRegistry.fireEquip` を新設した。

```java
// CardEffectRegistry
public void fireEquip(String weaponId, EffectContext ctx) {
    Consumer<EffectContext> effect = triggers
            .getOrDefault(weaponId, Map.of())
            .get(TriggerType.ON_EQUIP);
    if (effect != null) {
        effect.accept(ctx);
    }
}
```

`equipWeapon` の【知識】ブロックの直後に、装備時効果の発火を1行足した。
【知識】は従来どおり `equipWeapon` 側で処理し、ON_EQUIP はそれ以外の装備時効果を扱う。

```java
effects.fireEquip(master.id(), contextOf(room, room.getGameState(), player, null, null));
```

> **☆なぜ String cardId を渡すのか。** `resolveSpell(String cardId, ctx)` と同じ様式に揃えた。
> `CardEffectRegistry` は `CardMaster` を import していないため、id 文字列で受ける方が
> 依存を増やさない。

---

## ★ 5. e5 — 「リーダーを攻撃できない」(判定層に1行)

不動の絶対神ガイア(QTE-0150)は「リーダーを攻撃できない(ミニオンにしか攻撃できない)」。
これは光文明の創世神ゾディアックアイリス(QTE-0107)と**まったく同じ判定**であり、
`RuleGuards.minionAttackDenial` に定数と分岐を1組足すだけで済む。

```java
private static final String ABSOLUTE_GAIA = "QTE-0150";
...
if (targetIsLeader && ABSOLUTE_GAIA.equals(attacker.getMaster().id())) {
    return "【不動の絶対神ガイア】はリーダーを攻撃できません";
}
```

「攻撃時に相手リーダーへ4ダメージ」の方は既存の `ON_ATTACK` トリガーで登録できる(13b)。
攻撃対象がリーダー以外でも、攻撃時効果として相手リーダーへ直接ダメージを入れるためである。

---

## 6. 台帳の更新(145枚 → 169枚)

アプリがカードを読み込むのは `src/main/resources/cards/qte-cards.json` である。
このファイルは土文明が4枚しか入っていない旧版(145枚)であった。本バッチで、土28枚を含む
最新版(169枚)へ差し替えた。

- 差し替え後の内訳: 水28 / 火28 / 闇28 / 光28 / 風28 / 土28 / 文明なし1 = 169枚。
- 土のフィールド構成・キーワード値(速攻/突進/守護/潜伏/還元/特殊召喚)は他文明と完全に一致し、
  すべて `Keyword` enum に対応する。デシリアライズは問題なく通る。
- **13a時点では土のカード効果は未登録である。** 効果未登録のスペルは使用時に拒否され
  (`resolveSpell` の "未実装" 例外)、効果未実装のカードを含むデッキは `DeckValidator` が
  入口で弾く(設計判断27)。土のプリセットデッキも未作成のため、13a単体で土カードが
  誤って場に出ることはない。台帳を先に載せても壊れた状態にはならない。

---

## 7. 再利用で済むもの(13aでは触れていない)

土のカードの大半は、既存の土台で登録できる。13a で新規実装しなかったのは意図的である。

| 仕組み | 既存の土台 | 該当する土カード |
|---|---|---|
| 動的Attack(手札枚数) | `StatCalculator.effectiveAttack`(設計判断4) | 無尽蔵の巨神 |
| 動的コスト(マナ7枚以上) | `StatCalculator.effectiveCost`(設計判断5) | 大地の狂戦士 / 地脈の覚醒 |
| 攻撃回数の上書き | `StatCalculator.maxAttacks`(a2) | 連撃の巨岩(2回) |
| ターンエンドの強制バウンス | `ON_TURN_END`(a4) + `bounceToHand`(設計判断8) | 連撃の巨岩 / 疾風怒濤のベヒーモス |
| ターンエンドの回復 | `ON_TURN_END`(a4) + `healLeader` | 安らぎのガーディアン |
| 還元 | `SpellDisposition`(設計判断20) | 地脈の覚醒 / ガイア・リソース |
| 攻撃時効果 | `ON_ATTACK` | 地砕きの突撃兵(マナ回収) / 不動の絶対神ガイア(4ダメージ) |
| 破壊時効果 | `ON_DESTROYED` | 地砕きの突撃兵(山札→マナ) |
| 表向きマナ→手札 | `returnFaceUpManaToHand`(Batch 12bで新設) | 地砕きの突撃兵(攻撃時) |
| 特殊召喚枠 | `SpecialSummonSpec`(設計判断17・23) | 創世神ガイア(マナ最大値10以上でコスト0) |
| リーダー起動能力枠 | `LeaderAbilitySpec` | 大地の巨頭(コスト4で手札→マナ) |
| 全体除去・フィルタ除去 | 既存の対象/破壊処理 | 大地震 / アースクエイクジャイアント / 創世神ガイア / 天変地異のタイタン |
| 対象ダメージ・AoEダメージ | 既存のダメージ処理 | 落石の罠 / 地響きの槌 |
| バニラ・キーワードのみ | 既存 | 百獣の王ベヒーモス / 不動の岩石竜 / ゴーレム・ウォール |

これらは 13b で登録する。

---

## ✅ 動作確認手順(13b 実装後に本格確認。13a単体は起動確認まで)

13a はカード効果を登録しないため、単体では「起動して既存文明が従来どおり動く」ことと、
「台帳が169枚で読み込める」ことの確認にとどまる。

1. `mvn spring-boot:run` で起動し、エラーログが出ないことを確認する。
2. 既存文明(例: 風のプリセット同士)で1ゲーム進め、マナチャージ・攻撃・戦闘が
   従来どおり動くことを確認する(e1のマナチャージ経路の差し替え・e2/e3の攻撃経路への
   割り込みが、既存挙動を壊していないことの確認)。
3. `/api/cards` が169枚を返すことを確認する。

> 土のカードを実際に使った確認は 13b の設計解説に回す。

---

## ✅ 理解確認

<details>
<summary>Q1. なぜ土文明のためにわざわざ「表向きマナ配置」の入口を新設したのか。
既存の裏向きマナ配置ヘルパを使い回せなかったのか。</summary>

裏向きマナと表向きマナは意味が異なるためである。裏向きマナは闇文明が禁忌コストの支払いや
不滅のネクロマンサーで消費する資源であり、土の「山札や手札を表向きでマナに置く」加速とは別物。
既存ヘルパはすべて裏向き専用(生成後に `turnFaceDown()` を呼ぶ)であり、表向きで置く経路は
通常のマナチャージにしか無かった。加えて、入口を1本に集約したのは、豊穣の地霊主のための
配置回数の計数とイベント発火を、マナチャージも含めて漏れなく行うためである。
</details>

<details>
<summary>Q2. 豊穣の地霊主の「2回目なら1ドロー」を実装するとき、カウンタの加算を
イベント発火の前に置いたのはなぜか。</summary>

発火時にカウンタがちょうど正しい回数を読むようにするためである。`placeCardInManaFaceUp` は
カウンタを進めてから `fireManaPlaced` を呼ぶ。2回目の配置ではカウンタが `2` になっており、
`cardsPutToManaThisTurn == 2` で「2回目」を素直に表現できる。加算を後に置くと発火時点では
まだ `1` であり、判定がずれる。これは a1(使用カウンタ)が「加算を効果解決の後に置く」ことで
自身を含めなかったのと同じ、順序による意味の作り込みである(向きは逆)。
</details>

<details>
<summary>Q3. 大地の守護盾でリーダーへの攻撃を肩代わりしたとき、攻撃側のウェポン攻撃時効果
(例: 真珠の三叉槍の1ドロー)は発動するか。</summary>

発動する。守護盾が無効化するのは**ダメージ**であって、**攻撃という事象**ではない。攻撃宣言は
成立しているため、攻撃時効果は通常どおり発動する。実装上も、肩代わり判定は LP を減らす
ブロックだけをくるんでおり、その後に続くウェポン攻撃時効果のswitchには手を付けていない。
</details>

<details>
<summary>Q4. タイタン・ウォリアーの ON_COMBAT_KILL を、破壊された側に発火する
ON_DESTROYED_BY_COMBAT で代用できなかったのか。</summary>

できない。両者はトリガーの「向き」が逆である。ON_DESTROYED_BY_COMBAT は破壊された側
(被害者)に発火するが、タイタンが必要とするのは撃破した側(加害者)への発火である。
死んだミニオンから加害者を辿ることはできても、生き残った加害者側で反応させる仕組みが
別に要る。よって ON_COMBAT_KILL を新設し、破壊判定の後に「相手が場を離れ、自分が残っている」
側へ発火させた。
</details>

---

## 次バッチ予告(Batch 13b)

- 土文明28枚の効果登録。上記「7. 再利用で済むもの」の表に沿って `CardEffectRegistry`・
  `StatCalculator`・`SpecialSummonSpec`・`LeaderAbilitySpec` へ登録する。
- 新機構(e1〜e5)を使うカードの登録:
  - e1: 大地の精霊グラン / ガイア・リソース / 豊穣の祈り / 大地の恵み / 苗木植えの精霊 /
    大地の巨頭(L011) / ガイア・ハンマー(0142, ON_EQUIP経由) / 地砕きの突撃兵(破壊時)
  - e2: 大地の守護盾(0146) — 既に `tryInterceptLeaderAttackWithShield` に組み込み済みのため
    登録は不要。13bでは動作確認のみ。
  - e3: タイタン・ウォリアー(0140, ON_COMBAT_KILL)
  - e4: ガイア・ハンマー(0142)
  - e5: 不動の絶対神ガイア(0150) — 攻撃禁止は判定層に組み込み済み。ON_ATTACKの4ダメージのみ登録。
- 土のプリセットデッキ(EARTH_STARTER, 40枚)を `DeckFactory` に追加する。
- 静的ファイル(battle.js/css)は 13a では変更していないため、13bでUIに手を入れる場合のみ
  `?v=` を上げる。
