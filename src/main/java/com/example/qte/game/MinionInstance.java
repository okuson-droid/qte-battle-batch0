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
}
