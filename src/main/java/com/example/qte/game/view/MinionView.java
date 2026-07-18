package com.example.qte.game.view;

import java.util.List;

/**
 * 場のミニオン1体のビュー。
 * attackは修正込みの現在値。UIの攻撃可否ハイライト用にサーバ側の判定結果を添える
 * (正当性の最終判定はサーバが行う。クライアントの表示はあくまで補助)。
 *
 * @param canAttackMinion 現時点でミニオンに攻撃宣言できるか
 * @param canAttackLeader 現時点でリーダーに攻撃宣言できるか(突進は召喚ターンここがfalse)
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
        boolean frozen) {
}
