package com.example.qte.game;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.example.qte.master.CardMaster;
import com.example.qte.master.Keyword;

import lombok.Getter;

/**
 * 場に出ているミニオン1体。
 * CardMaster(不変の定義)への参照と、ゲーム中に変化する状態だけを持つ。
 * 同名カードが複数体並ぶため、個体識別用のinstanceIdを発行する。
 */
@Getter
public class MinionInstance {

    /** 個体識別ID。攻撃対象の指定などクライアントとのやり取りはこのIDで行う */
    private final String instanceId = UUID.randomUUID().toString();

    private final CardMaster master;

    /** 受けているダメージの累計。現在HP = 基礎HP - damage(回復はdamageを減らす) */
    private int damage = 0;

    /** 登場したターン番号。召喚酔いの判定に使う */
    private final int enteredTurn;

    /**
     * 禁忌デッキ由来か(総合ルール3-6)。
     * trueの場合、このミニオンが場を離れるときは墓地・手札ではなく消滅(Lost)ゾーンへ行く。
     */
    private final boolean fromTaboo;

    /**
     * ★★Batch 68(裁定311): <b>召喚ではなく効果で場に出た</b>か。
     *
     * <h2>なぜ「【召喚時】が発動したか」では代用できないのか</h2>
     *
     * 63 までは「効果で出た = 【召喚時】が発動しない」だったので、
     * この2つは同じことを指していた。裁定311 が
     * <b>手札から効果で出た場合にも【召喚時】を発動させた</b>ことで、両者は分かれた ——
     * 《光の召喚士》に出された《ガイル・フォックス》は【召喚時】が発動するが、
     * <b>そのカード自身は使用されていない</b>。
     *
     * <p>★<b>この違いが効くのは「このターン使用したカードの枚数」を読む効果だけである</b>
     * (使用カウンタは解決中の自分自身をまだ数えていない。裁定1)。
     * 現在の使い手は《ガイル・フォックス》1枚である。
     */
    private boolean enteredByEffect = false;

    /** このターンに攻撃した回数。アンタップフェイズで0に戻す。上限はStatCalculator.maxAttacksが評価する */
    private int attacksUsedThisTurn = 0;

    /**
     * タップ状態(Batch 12a で追加)。ミニオンの起動能力(静空の風使い)のコストとして使う。
     * 総合ルール6章-2は「自分の場・マナゾーンの全タップ状態カード」をアンタップすると定めており、
     * 場のカードにタップ状態が無かったこれまでの実装のほうがルールから逸脱していた。
     *
     * 攻撃回数を tapped ではなく attacksUsedThisTurn で数えているのは、
     * 「1ターンに2回攻撃できる」カード(ツイン・ストライク等)があるためである。
     * 裁定4により、タップ中のミニオンは攻撃できない(判定は RuleGuards)一方、
     * 【守護】【潜伏】はタップ中も機能する(キーワードの評価には手を入れない)。
     */
    @lombok.Setter
    private boolean tapped = false;

    /**
     * ターンの終わりに自身を破壊するか(這い寄る生霊を特殊召喚で出した場合)。
     * 通常召喚で出したときは立てない(カードテキストが特殊召喚のときだけを指しているため)。
     */
    @lombok.Setter
    private boolean destroyAtEndOfTurn = false;

    /** このターン番号の間は攻撃できない(氷結の杖の凍結)。0なら制限なし */
    @lombok.Setter
    private int cannotAttackOnTurn = 0;

    /** 効果で永続的に付与されたキーワード(設計判断16: 印刷+付与の合成で評価する) */
    private final Set<Keyword> grantedKeywords = EnumSet.noneOf(Keyword.class);

    /** このターンだけ付与されたキーワード(捨て身の猛進など)。ターン終了時にクリアする */
    private final Set<Keyword> grantedKeywordsThisTurn = EnumSet.noneOf(Keyword.class);

    /** ステータス修正のスタック(設計判断12) */
    private final List<StatModifier> modifiers = new ArrayList<>();

    /**
     * 進化の下に置かれているカードの束(★Batch 52。裁定154)。下から順に並ぶ。
     *
     * <b>場を離れるときは進化ミニオンと一緒に移動する。</b>破壊されたのではなく
     * 同伴しただけなので、束のカードの【破壊時】は発動しない(マスター裁定 C1) ——
     * 束が {@link MinionInstance} ではなく {@link StackedCard} であることで、
     * これは<b>条件分岐ではなく構造として</b>守られている。
     */
    private final List<StackedCard> under = new ArrayList<>();

    /**
     * 下にあるカード1枚につき Attack と HP に足す量(★Batch 52。《不敗鉄人闘太》の【常在】)。
     *
     * <b>保存された修正ではなく、規則そのものを持っている。</b> 値は
     * {@link com.example.qte.effect.EvolutionSpec} が持ち、場に出るときに1度だけ写す。
     * 実際の加算は<b>読むたびに束を数えて</b>行うので、【起動：1】で束が増えれば
     * 即座に反映され、束が変わらないのに古い値が残ることも起きない
     * (【常在】は保存しない、の原則)。
     *
     * <p>★<b>加算する場所は Attack と HP で違う。</b>
     * HP は {@link #getMaxHp()}(このクラスが唯一の出どころ)、
     * Attack は {@link com.example.qte.effect.StatCalculator#effectiveAttack}
     * (ゲームロジックが必ず通る出どころ)である。同じ規則を2箇所に書かないため、
     * <b>それぞれ1回だけ</b>足している。この非対称は Batch 12 からのもので、52 は増やしていない。
     *
     * <p>★<b>進化を重ねても引き継がない。</b>これは「他のカードによって付与された効果」ではなく
     * このカード自身のテキストなので、裁定157(2) の対象外である
     * ({@link #inheritGrantsFrom} が写さないことで守っている)。
     */
    @lombok.Setter
    private int statPerUnderCard = 0;

    /**
     * 【速攻】を持つ場合に加算される体力(★Batch 58。《剛火の将》
     * 「場にある【速攻】を持つカードのHPを+2する」)。
     *
     * <b>なぜ場に出るときに1度だけ写すのか。</b> この加算の有無を決めるのは
     * <b>どちらかのリーダーが《剛火の将》か</b>であり、リーダーは対戦の途中で変わらない。
     * 一方、【速攻】は効果で後から付くことがある(《赫灼の重戦士》の【召喚時】、
     * 《1stL「NEMれぬ夜のドリーミー」》の条件付き獲得)。したがって
     * <b>値は写し、キーワードの有無は読むたびに見る</b>のが正しい形になる
     * ({@link #getMaxHp()} が毎回 {@code hasKeyword(Keyword.HASTE)} を見ている)。
     * ★<b>【速攻】は HASTE であって RUSH(=【突進】)ではない。</b>この2語は別のキーワードである。
     * {@link #statPerUnderCard} と同じ流儀である ——
     * 規則そのものの正は {@code StatCalculator.rushHpBonus} が持つ。
     *
     * <p>★<b>「自分の」と書いていないので両者の場に効く</b>(裁定156(2))。
     * 両者のリーダーが《剛火の将》なら常在が2つ重なるため +4 になる
     * (常在の既定の累積。《サービスブレイク・メリィナ》と同じ扱い)。
     */
    @lombok.Setter
    private int rushHpBonus = 0;

    public MinionInstance(CardMaster master, int enteredTurn) {
        this(master, enteredTurn, false);
    }

    public MinionInstance(CardMaster master, int enteredTurn, boolean fromTaboo) {
        this.master = master;
        this.enteredTurn = enteredTurn;
        this.fromTaboo = fromTaboo;
    }

    /**
     * 最大体力。印刷値に体力修正(突風の祝福・そよ風の加護・風護の杖)を合成した値。
     *
     * 攻撃力・コストと違って StatCalculator に出していないのは、
     * 現行の体力修正がすべて固定値(+1 / +2)であり、手札枚数や墓地枚数のような
     * 盤面の参照を必要としないためである。MinionInstance 内で閉じることで、
     * getCurrentHp の呼び出し元(破壊判定・ビュー・HP_5_OR_LESSフィルタ)を
     * 1箇所も書き換えずに済む。盤面参照型の体力修正が出た時点で StatCalculator へ移す。
     *
     * 適用順序は攻撃力と同じく SET が先、ADD が後(設計判断12)。
     */
    public int getMaxHp() {
        int hp = master.hp();
        for (StatModifier m : modifiers) {
            if (m.stat() == StatModifier.Stat.HP && m.operation() == StatModifier.Operation.SET) {
                hp = m.value();
            }
        }
        for (StatModifier m : modifiers) {
            if (m.stat() == StatModifier.Stat.HP && m.operation() == StatModifier.Operation.ADD) {
                hp += m.value();
            }
        }
        hp += statPerUnderCard * under.size();
        // ★Batch 58: 《剛火の将》の常在(場にある【速攻】を持つカードのHP+2)。
        // 加算量は場に出るときに写してあるが、【速攻】を持つかは<b>読むたびに</b>見る ——
        // 効果で後から【速攻】を得たミニオンにも、失ったミニオンにも正しく追随する
        if (rushHpBonus > 0 && hasKeyword(Keyword.HASTE)) {
            hp += rushHpBonus;
        }
        return Math.max(0, hp);
    }

    /** 現在HP。0以下かどうかの破壊「判定」はダメージ適用とは別ステップで行う(設計判断2) */
    public int getCurrentHp() {
        return getMaxHp() - damage;
    }

    public void takeDamage(int amount) {
        this.damage += amount;
    }

    /**
     * 回復。受けているダメージを減らす形で表現しているため、
     * 最大体力(修正込み)を超えて回復することは構造的に起こらない。
     */
    public void heal(int amount) {
        this.damage = Math.max(0, this.damage - amount);
    }

    /**
     * 静的な修正のみを合成した攻撃力。
     * 手札枚数参照などの動的修正を含む最終値の評価はStatCalculatorが担う。
     * ゲームロジックからは必ずStatCalculator経由で参照すること。
     */
    public int getEffectiveAttack() {
        int base = master.attack();
        for (StatModifier m : modifiers) {
            if (m.stat() == StatModifier.Stat.ATTACK && m.operation() == StatModifier.Operation.SET) {
                base = m.value();
            }
        }
        for (StatModifier m : modifiers) {
            if (m.stat() == StatModifier.Stat.ATTACK && m.operation() == StatModifier.Operation.ADD) {
                base += m.value();
            }
        }
        return base;
    }

    /** 印刷キーワード + 付与キーワード(永続・このターン限り)の合成(設計判断16・24) */
    public boolean hasKeyword(Keyword keyword) {
        return master.hasKeyword(keyword)
                || grantedKeywords.contains(keyword)
                || grantedKeywordsThisTurn.contains(keyword);
    }

    public void grantKeyword(Keyword keyword) {
        grantedKeywords.add(keyword);
    }

    /** このターンだけキーワードを付与する */
    public void grantKeywordThisTurn(Keyword keyword) {
        grantedKeywordsThisTurn.add(keyword);
    }

    public void addModifier(StatModifier modifier) {
        modifiers.add(modifier);
    }

    /** ターン終了時: THIS_TURN期限の修正と付与キーワードを除去する */
    public void expireThisTurnModifiers() {
        modifiers.removeIf(m -> m.duration() == StatModifier.Duration.THIS_TURN);
        grantedKeywordsThisTurn.clear();
    }

    /** 進化ミニオンか(★Batch 52)。召喚酔いの免除・素材条件の判定に使う */
    public boolean isEvolution() {
        return master.type() == com.example.qte.master.CardType.EVOLUTION;
    }

    /** 束に1枚加える(進化の素材・《機神兵長茶爺》の【起動：1】)。下から順に積む */
    public void putUnder(StackedCard card) {
        under.add(card);
    }

    /**
     * 素材から<b>他のカードによって付与されていた効果だけ</b>を受け継ぐ(裁定157(2))。
     *
     * <ul>
     * <li><b>写すもの</b>: {@link StatModifier} のスタックと付与キーワード。
     *     期限が「このターンの間」のものも写す(マスター裁定 B4) ——
     *     裁定157(2) が言う「他のカードによって付与された効果」であることは、
     *     期限の有無で変わらない。写したあとは既存の
     *     {@link #expireThisTurnModifiers()} がターン終了時に落とす。</li>
     * <li><b>写さないもの</b>: ダメージ・タップ状態・このターンの攻撃回数
     *     (マスター裁定 B2・B3・B5)、および {@link #statPerUnderCard}。</li>
     * </ul>
     *
     * <p>★<b>【常在】による修正が写らないのは、そもそも保存されていないからである</b>
     * (裁定157(3))。「自分のミニオンすべて Attack+1」のような効果は
     * {@link com.example.qte.effect.StatCalculator} が評価のたびに場を見て算出しており、
     * {@link #modifiers} には1つも積まれない。したがって
     * <b>ここで「常在由来のものを除く」処理を書く必要がない</b> ——
     * 裁定157 が要求した「個別付与と常在計算の2系統」は、Batch 12 の設計判断12 の時点で
     * 既に分かれていた。
     *
     * <p>★素材が2体以上のときは、この呼び出しを素材の数だけ繰り返す
     * (=全素材分を合算する。マスター裁定 B1)。
     */
    public void inheritGrantsFrom(MinionInstance material) {
        modifiers.addAll(material.modifiers);
        grantedKeywords.addAll(material.grantedKeywords);
        grantedKeywordsThisTurn.addAll(material.grantedKeywordsThisTurn);
    }

    public void countAttack() {
        this.attacksUsedThisTurn++;
    }

    public void resetAttacksUsed() {
        this.attacksUsedThisTurn = 0;
    }

    /** アンタップフェイズ、および起動能力を使っていない状態への復帰 */
    public void untap() {
        this.tapped = false;
    }

    public void tap() {
        this.tapped = true;
    }

    /**
     * 「召喚ではなく効果で場に出た」の印を付ける(★Batch 68。裁定311)。
     * {@code GameActions.putIntoFieldByEffect} が、誘発を焚く<b>前に</b>呼ぶ ——
     * 【召喚時】の中からこの値を読むためである。
     */
    public void markEnteredByEffect() {
        this.enteredByEffect = true;
    }
}
