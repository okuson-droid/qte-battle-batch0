package com.example.qte.effect;

import org.springframework.stereotype.Component;

import com.example.qte.game.GameState;
import com.example.qte.game.MinionInstance;
import com.example.qte.game.PlayerState;
import com.example.qte.game.StatModifier;
import com.example.qte.master.CardMaster;
import com.example.qte.master.Keyword;

/**
 * ミニオンの現在攻撃力の評価器。
 *
 * 攻撃力は保存された値ではなく「評価するたびに計算される値」として扱う(設計判断4)。
 * 手札枚数のような参照元は刻々と変わるため、どこかに数値をキャッシュすると
 * 更新漏れのバグ(例: ドロー後に攻撃力表示が古いまま)を必ず生むからである。
 *
 * 適用順序: 基礎値 → 動的SET → 静的SET → 動的ADD → 静的ADD
 * (SET=値の置き換え、ADD=加算。設計判断12)
 */
@Component
public class StatCalculator {

    /**
     * 手札のカードの現在コスト。コストも動的ステータス(設計判断5)。
     * 例: 双流の幻術師「場に居る知識の数Cost-1」(両者の場を参照: 発注者確認済み)
     */
    public int effectiveCost(GameState state, PlayerState owner, CardMaster card) {
        int cost = card.cost();

        // 【剛火の将】の起動能力: 次に手札から使用する火文明ミニオンのコスト-1(0にはならない)
        if (owner.getPendingFireMinionDiscount() > 0
                && card.type() == com.example.qte.master.CardType.MINION
                && card.civilization() == com.example.qte.master.Civilization.FIRE) {
            cost = Math.max(1, cost - 1);
        }
        if ("QTE-0041".equals(card.id())) {
            long knowledgeOnBoard = java.util.stream.Stream
                    .of(state.getPlayer1(), state.getPlayer2())
                    .flatMap(p -> p.getMinionZone().stream())
                    .filter(m -> m.hasKeyword(Keyword.KNOWLEDGE))
                    .count();
            cost -= (int) knowledgeOnBoard;
        }
        return Math.max(0, cost);
    }

    /**
     * リーダーに装備中のウェポンの現在攻撃力。
     * 例: 影潜む水刺客「自分の場の潜伏の数Attack+1」— ウェポンにも動的値がある
     */
    public int effectiveWeaponAttack(GameState state, PlayerState owner) {
        CardMaster weapon = owner.getEquippedWeapon();
        if (weapon == null) {
            return 0;
        }
        int attack = weapon.attack();
        if ("QTE-0022".equals(weapon.id())) {
            attack += (int) owner.getMinionZone().stream()
                    .filter(m -> m.hasKeyword(Keyword.STEALTH))
                    .count();
        }
        return attack;
    }

    public int effectiveAttack(GameState state, PlayerState owner, MinionInstance minion) {
        String cardId = minion.getMaster().id();
        int attack = minion.getMaster().attack();

        // ---- 動的SET(カード固有のルール) ----
        // 知識の守護者: 攻撃力は自分の手札の枚数と同じになる(常に連動)
        if ("QTE-0037".equals(cardId)) {
            attack = owner.getHand().size();
        }

        // ---- 静的SET(インスタンスに積まれた修正) ----
        for (StatModifier m : minion.getModifiers()) {
            if (m.stat() == StatModifier.Stat.ATTACK && m.operation() == StatModifier.Operation.SET) {
                attack = m.value();
            }
        }

        // ---- 動的ADD(オーラ) ----
        // 溢れ出る英知: このターン中、手札の枚数分すべての自ミニオンの攻撃力+1(常に連動)
        for (String aura : owner.getThisTurnAuras()) {
            if ("QTE-0024".equals(aura)) {
                attack += owner.getHand().size();
            }
        }

        // ---- 静的ADD ----
        for (StatModifier m : minion.getModifiers()) {
            if (m.stat() == StatModifier.Stat.ATTACK && m.operation() == StatModifier.Operation.ADD) {
                attack += m.value();
            }
        }

        return Math.max(0, attack);
    }
}
