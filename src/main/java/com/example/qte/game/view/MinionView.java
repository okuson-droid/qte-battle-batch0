package com.example.qte.game.view;

import java.util.List;

/**
 * 場のミニオン1体のビュー。
 * attackは修正込みの現在値。UIの攻撃可否ハイライト用にサーバ側の判定結果を添える
 * (正当性の最終判定はサーバが行う。クライアントの表示はあくまで補助)。
 *
 * @param canAttackMinion 現時点でミニオンに攻撃宣言できるか
 * @param canAttackLeader 現時点でリーダーに攻撃宣言できるか(突進は召喚ターンここがfalse)
 * @param tapped          タップ状態か(起動能力を使った後。攻撃できない)
 * @param canUseAbility   今この瞬間、起動能力を発動できるか(能力を持たないミニオンはfalse)
 * @param abilityText     起動能力の説明文(能力を持たないミニオンはnull)
 */
public record MinionView(
        String instanceId,
        String cardId,
        String name,
        int attack,
        int currentHp,
        int maxHp,
        List<String> keywords,
        boolean canAttackMinion,
        boolean canAttackLeader,
        boolean frozen,
        boolean tapped,
        boolean canUseAbility,
        String abilityText) {
}
