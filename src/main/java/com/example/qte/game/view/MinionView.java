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
 * @param effectUnimplemented ★Batch 47。効果の文があるのにエンジンが処理を持っていないカード。
 *                        手札で見た印が場でも消えないよう、場のタイルにも同じ印を出す
 * @param evolution       ★Batch 52。進化ミニオンか。素材の絞り込み(【起動：1】機神兵長茶爺)と
 *                        束のバッジの表示に使う
 * @param underCardIds    ★Batch 52。下に置かれているカードのID(下から順)。裁定154 により
 *                        場を離れるときは一緒に移動する。★<b>相手の束も公開情報である</b> ——
 *                        素材は場に出ていたミニオンであり、誰でも見ていたものだからである
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
        String abilityText,
        boolean effectUnimplemented,
        boolean evolution,
        List<String> underCardIds) {
}
