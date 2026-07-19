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

    /** このターンに攻撃した回数。アンタップフェイズで0に戻す */
    private int attacksUsedThisTurn = 0;

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

    /** 現在HP。0以下かどうかの破壊「判定」はダメージ適用とは別ステップで行う(設計判断2) */
    public int getCurrentHp() {
        return master.hp() - damage;
    }

    public void takeDamage(int amount) {
        this.damage += amount;
    }

    /** 回復。基礎HPを超えては回復しない */
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
}
