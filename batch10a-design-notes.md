# Batch 10a 設計解説 — 闇文明の土台づくり

対象: 闇文明28枚を実装するために必要な「基盤側の変更」のみ。
カード1枚ごとの効果登録は Batch 10b で行う。

---

## ⚡ 結論チートシート

| やったこと | 一言でいうと |
|---|---|
| `DestructionCause` 新設 | 破壊に「原因」を持たせた(戦闘か効果か) |
| 破壊処理を1本に統合 | 破壊の実体は `leaveFieldByDestruction` ただ1つ |
| `ON_DESTROYED_BY_COMBAT` / `ON_MINION_DAMAGED` | トリガーを2種類追加 |
| `fireOwnMinionDestroyed` | 「他人の破壊に反応する」向きのトリガーを新設 |
| `GameActions` に闇用の操作を9個追加 | ミル・蘇生・墓地回収・マナの表裏操作など |
| `StatCalculator` に墓地参照を追加 | 墓地の枚数がコストと攻撃力を動かす |
| `GameService.summonFromGrave` | サブフェイズに墓地から召喚できるようにした |
| 踏み倒し禁止フラグ | 「コストを支払わず場に出せない」を1か所で弾く |

**この時点でゲームの見た目と挙動は一切変わらない。** 10a の動作確認は
「今までどおり動くこと」(回帰確認)である。

---

## 1. なぜ「破壊」に手を入れる必要があったのか ★

これまでのカードプールでは、破壊は「HPが0になったら場から取り除く」だけで済んでいた。
闇文明はここに3つの要求を突きつけてくる。

1. **ボーン・コレクター**: 「**戦闘で**破壊された時」— 原因を区別したい
2. **執念の暗殺者 / 不滅のネクロマンサー**: 「自分のミニオンが破壊される**たび**」
   — 破壊された本人ではなく、**場に残っている別のミニオン**が反応する
3. **冥界神ハデス**: 「**このターン**破壊された味方ミニオン」— 破壊の履歴を残したい

破壊の処理が複数箇所に散っていると、この3つを漏れなく満たすのは不可能である。
そこで、破壊の実体を1つのprivateメソッドに集約した。

```java
private void leaveFieldByDestruction(GameRoom room, PlayerState owner,
        MinionInstance minion, DestructionCause cause) {
    owner.getMinionZone().remove(minion);                       // ① 場から取り除く
    boolean wentToTrash = sendToTrashOrRestore(...);            // ② 行き先(墓地/還元/消滅)
    owner.setOwnMinionDestroyedThisTurn(true);                  // ③ ターン内の記録
    if (wentToTrash) owner.getMinionsDestroyedThisTurn().add(...);
    effects.fire(TriggerType.ON_DESTROYED, minion, ctx);        // ④ 本人のトリガー
    if (cause == COMBAT) effects.fire(ON_DESTROYED_BY_COMBAT, ...);
    effects.fireOwnMinionDestroyed(ctx, minion.getMaster().id()); // ⑤ 監視側のトリガー
}
```

**②→③→⑤ の順序には意味がある。** 不滅のネクロマンサーは「破壊されたミニオンを
復活させる」が、復活元は墓地である。つまり **墓地に入れ終わってから** 監視側を
呼ばなければ、蘇生したいカードがまだ墓地に無い、という事故が起きる。
処理の順序そのものがルールの一部になっている、という典型例である。

`destroyMinion` と `checkDestruction` は、この実体を呼ぶ薄い入口に変わった。
既存の呼び出し(引数2つ・3つ)はそのまま動くように、原因を省略した版は
`EFFECT` として扱うオーバーロードを残してある。

### なぜ EffectContext に原因を足さなかったか ★

素直に考えれば「破壊原因を `EffectContext` に持たせて、効果側で分岐する」が自然に見える。
しかし `EffectContext` は **record** であり、フィールドを1つ足すと
**全ての生成箇所(GameService・GameActions の十数か所)が壊れる**。
一方でトリガーの種類を増やすのはコストがほぼゼロである。

そこで「原因で条件分岐する」のではなく「原因ごとに別のトリガーを鳴らす」形にした。
`ON_DESTROYED`(原因を問わない)と `ON_DESTROYED_BY_COMBAT`(戦闘のみ)の2本立てである。
カード側は、自分が反応したい方に登録するだけでよい。

---

## 2. トリガーの「向き」という新しい概念 ★

既存のトリガーはすべて **自分自身に起きたこと** への反応だった。

- ON_SUMMON = このミニオンが召喚された
- ON_ATTACK = このミニオンが攻撃した
- ON_DESTROYED = このミニオンが破壊された

闇文明で初めて、**他者に起きたことへの反応** が必要になる。

```
[破壊された] ゾンストライカー
       │
       └──→ [場に残っている] 不滅のネクロマンサー が反応する
```

この向きのトリガーは、対象がカードID単位ではなく「場にいる全員」なので、
既存の `triggers` マップ(カードID → タイミング → 処理)では表現できない。
そこで専用の台帳を追加した。

```java
private final Map<String, BiConsumer<EffectContext, String>> ownMinionDestroyedWatchers;
```

第2引数が **破壊されたミニオンのカードID(String)** であることに注意してほしい。
既に場を離れているので `MinionInstance` は渡せない(渡しても意味がない)。
ネクロマンサーが蘇生に必要とするのはカードIDであり、これで十分である。

実装上の注意も1つある。監視効果は実行中に**場を変える**(蘇生でミニオンが増える、
連鎖破壊で減る)。そのため走査はリストのコピーに対して行い、実行の直前に
「この監視者はまだ場にいるか」を確認している。これを怠ると
`ConcurrentModificationException` か、既に死んだミニオンの効果が発動する。

同じ構造は火文明の `fireLeaderDamaged` で既に一度作っている。
**「場の全員に問い合わせるイベント」は台帳側にメソッドとして生やす** という
パターンが、これで2例目として定着したことになる。

---

## 3. ダメージ経路の一本化 ★

`ON_MINION_DAMAGED`(獄門の裁定者)を実装するには、**あらゆるダメージが同じ道を通る**
必要がある。ところが Batch 9 時点では、戦闘ダメージだけが `GameActions` を通らず、
`GameService` が直接 `target.takeDamage(damage)` を呼んでいた。

```java
// Before(GameService内)          // After
target.takeDamage(toTarget);       actions.dealCombatDamage(room, opponent, target, toTarget);
attacker.takeDamage(toAttacker);   actions.dealCombatDamage(room, player, attacker, toAttacker);
```

`dealCombatDamage` は **ダメージの適用とトリガー発火だけを行い、破壊判定は行わない**。
これは戦闘が「両者に同時にダメージ → その後まとめて破壊判定」という順序だからである
(片方ずつ適用+判定にすると、相打ちが成立しなくなる)。
効果ダメージ用の `damageMinion` は、内部で同じ適用処理を呼んだ後に破壊判定まで行う。

引き継ぎ書の未解決事項3「戦闘によるリーダーへのダメージはトリガーを通らない」は
**今回も意図的に据え置いた**。ミニオンへの戦闘ダメージだけを経路に載せている。

---

## 4. GameActions に足した闇の基本操作

| メソッド | 用途 | 設計上のポイント |
|---|---|---|
| `mill` | 山札→墓地 | **ドローではないので山札切れでも敗北しない** |
| `reviveFromGrave` | 墓地→場 | 「出す」なので ON_ENTER のみ。踏み倒し禁止を判定 |
| `returnFromTrashToHand` | 墓地→手札 | |
| `turnManaFaceDown` / `turnManaFaceUp` | マナの表裏 | 表向きに戻す操作は今回が初 |
| `destroyFaceDownMana` | 裏向きマナ→墓地 | 破壊後に `manaLeft` を発火(黄泉還る水龍が反応する) |
| `returnFaceDownManaToHand` | 裏向きマナ→手札 | 同上 |
| `putTrashCardIntoManaFaceDown` | 墓地→マナ(裏) | マナチャージの1回制限とは別枠 |

**マナを動かしたら `manaLeft` を呼ぶ**、という約束を守っている点に注目してほしい。
これは水文明の黄泉還る水龍(マナが離れたら墓地から出てくる)のためのイベントであり、
闇のカードが呼ばなければ水龍は静かに動かなくなる。
**文明をまたいだ相互作用は、こういう「呼び忘れ」で壊れる。**

### 「マナの破壊」に関するルールの上書き

総合ルールでは「マナの墓地送りは**破壊として扱わない**」と定められている。
しかし不滅のネクロマンサーと禁忌の代償は、テキストで明示的に「破壊する」と書いている。
ルールの優先順位は **カードテキストが最優先** であるため、
`destroyFaceDownMana` は破壊として実装した(発注者確認済み)。

---

## 5. 動的な値の参照先が「墓地」に広がった

`StatCalculator` に `CardMasterRepository` を注入した。理由は単純で、
**墓地はカードIDの羅列でしかなく、それがスペルか否かを判定するにはマスタが要る**からである。

```java
public int nonSpellCountInTrash(PlayerState owner) {
    return (int) owner.getTrash().stream()
            .filter(id -> cards.findById(id).type() != CardType.SPELL)
            .count();
}
```

これを使う闇のカードは4枚ある。

- **悪夢**: 墓地のスペル以外1枚につきコスト-1
- **群がる死霊王**: 墓地の「ゾンストライカー」の数だけコスト-1
- **封印されし禁忌魔人**: 禁忌デッキの残り枚数だけコスト**+1**(初の増加)
- **墓場の怨念集合体**: 墓地のスペル以外1枚につき攻撃力+1

怨念集合体の加算は、評価順序の **動的ADD** の位置に置いてある。
SETより前に書くと「攻撃力を◯◯にする」系の効果に上書きされて消えるためである。
値をキャッシュせず毎回計算する方針(設計判断4)のおかげで、
墓地が増えた瞬間に攻撃力表示も追随する。

---

## 6. リーダーが「ルールそのもの」を書き換える ★

【黄泉の召喚主】は、これまでのリーダーと種類が違う。

- 既存のリーダー = **起動能力**(メインフェイズ・1ターン1回・プレイヤーが使う)
- 黄泉の召喚主 = **常在能力**(「サブフェイズはスペルのみ」という総則を上書きする)

そのため `LeaderAbilitySpec` には載せられず、`GameService` に専用の入口を作った。

```java
public void summonFromGrave(GameRoom room, String playerId, int trashIndex) {
    requirePhase(state, TurnPhase.SUB);                    // サブフェイズ限定
    if (!GRAVE_SUMMONER_LEADER_ID.equals(player.getLeader().id())) throw ...;
    payCost(player, stats.effectiveCost(state, player, master));  // コストは払う
    player.getTrash().remove(trashIndex);
    summonToField(...);   // ← 「召喚」なので ON_SUMMON も発動する
}
```

最後の行が重要である。`putIntoFieldByEffect`(効果で出す)ではなく
`summonToField`(召喚)を呼んでいるため、【召喚時】能力が発動する。
このプロジェクトで何度も出てくる **「召喚」と「出す」の区別** が、
そのままメソッドの選択として現れている。

回数制限は設けていない(発注者確認済み)。MPが尽きるまで何度でも召喚できる。

WebSocketの入口 `/room/{roomId}/summon-from-grave` も追加済みだが、
**この操作を呼び出すUIは Batch 10b で作る**(墓地から選ぶモーダルが必要になるため)。

---

## 7. 踏み倒し禁止という「1行の制約」

【封印されし禁忌魔人】(2コスト5/5守護)は、テキストに
「このカードはコストを支払わず場に出せない」と書かれている。
闇には蘇生・踏み倒しが4種もあるため、この制約は**そのすべてを通る場所**で
判定しなければ意味がない。

```java
private static final Set<String> NO_CHEAT_INTO_FIELD = Set.of("QTE-0083");
```

判定箇所は `putIntoFieldByEffect` と `reviveFromGrave` の2つ。
場に出る経路がこの2つに集約されているからこそ、定数1つで塞ぎ切れる。
**Batch 2 で「場に出す操作を GameActions に集約した」判断が、
3バッチ後にこういう形で効いてくる。**

---

## ✅ 動作確認手順

10a は内部構造の変更であり、**画面に見える変化はない**。
したがって確認すべきは「壊していないこと」である。

1. `mvn spring-boot:run` で起動し、2タブで部屋に入って対戦を開始する
2. **戦闘で相打ちが正しく起きるか**(互いのミニオンが同時に破壊されること)
   → ダメージ経路を書き換えた箇所の回帰確認。ここが一番危ない
3. **リーダーのウェポン攻撃でミニオンを倒せるか**(反撃を受けないこと)
4. **【還元】持ち(死の知識人ではなく水の流転の書など)が破壊後にマナへ行くか**
   → `sendToTrashOrRestore` の戻り値をbooleanに変えたため
5. **黄泉還る水龍**が、マナが離れたときに墓地から出てくるか
6. デッキビルダーで水・火のデッキが今までどおり作れるか
   (闇28枚は台帳に載ったが、`DeckValidator` は未実装文明として弾く)

異常があれば 10b に進む前に戻す。

---

## ✅ 理解確認

**問1.** 不滅のネクロマンサーの蘇生処理を実装するとき、
`leaveFieldByDestruction` の中で「墓地に入れる処理」を
「監視トリガーの発火」より**後**に書いてしまうと、何が起きるか。

<details><summary>答え</summary>

ネクロマンサーが蘇生しようとした時点で、破壊されたミニオンがまだ墓地に無いため、
`reviveFromGrave` が `false` を返して**何も起きない**。
しかもエラーにはならず「たまに蘇生しない」という再現の難しいバグになる。
処理の順序がルールの意味を決めている例である。
</details>

**問2.** 「戦闘で破壊された時」を実装するのに、`EffectContext` に
破壊原因を持たせるのではなく `ON_DESTROYED_BY_COMBAT` というトリガーを
足したのはなぜか。理由を2つ挙げよ。

<details><summary>答え</summary>

1. `EffectContext` は record であり、引数を1つ足すと生成箇所すべてが
   コンパイルエラーになる(変更の波及が大きい)
2. カード側が「原因を見て分岐する」必要がなくなり、
   反応したいトリガーに登録するだけで済む(効果の記述が単純になる)
</details>

---

## 次バッチ予告: Batch 10b

- 闇文明28枚を `CardEffectRegistry` に登録する
- `DeckValidator.IMPLEMENTED` に DARK を追加し、リーダー選択に闇2枚を出す
- `DeckFactory` に闇のプリセットデッキを追加する
- 墓地からカードを選ぶUI(蘇生・回収・墓地からの召喚で必要)
- 静的ファイルのバージョン(`?v=N`)を上げる

登録時の注意: `CardEffectRegistry` は約600行に達している。
編集時は**置換範囲の終端を必ず確認する**こと(Batch 8 で登録メソッドを
5個消す事故を起こしている)。
