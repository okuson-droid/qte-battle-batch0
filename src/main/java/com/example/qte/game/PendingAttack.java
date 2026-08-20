package com.example.qte.game;

/**
 * 解決を保留している戦闘(★Batch 51)。
 *
 * <b>なぜ必要になったか。</b>
 * {@code GameService.attack} は「攻撃を宣言する → 攻撃時効果を発火する → 戦闘を解決する」を
 * 1本の同期処理として書いていた。攻撃時効果が割り込み選択({@link com.example.qte.effect.PendingChoice})を
 * 作る場合でも、選択の答えを待たずに戦闘の解決まで進んでいた
 * (地砕きの突撃兵は「マナを手札に戻す」だけで攻撃者が場を離れないため、それでも辻褄が合っていた)。
 *
 * 素手喧嘩(QTE-M-EARTH-35)は<b>攻撃時に攻撃者自身がマナへ移る</b>。
 * マスター裁定213 により、マナへ置いた場合は戦闘が起きない。
 * つまり「選択の答えが、戦闘を行うかどうかを決める」——
 * 選択を待たずに戦闘を解決すると、答える前に結果が確定してしまう。
 *
 * <b>なぜ MinionInstance を持たないか。</b>
 * {@link ResumePoint} と同じ理由である —— 保留の状態は文字列と真偽値だけで表せる形に保つ。
 * 再開時に instanceId から引き直すことで、選択中に攻撃者や対象が場を離れた場合を
 * 「見つからない = 戦闘は起きない」として自然に扱える。
 *
 * @param attackerPlayerId   攻撃側プレイヤーのID
 * @param attackerInstanceId 攻撃したミニオンのインスタンスID
 * @param targetInstanceId   攻撃対象のミニオンのインスタンスID(リーダーを攻撃した場合はnull)
 * @param targetIsLeader     リーダーへの攻撃だったか
 */
public record PendingAttack(
        String attackerPlayerId,
        String attackerInstanceId,
        String targetInstanceId,
        boolean targetIsLeader) {
}
