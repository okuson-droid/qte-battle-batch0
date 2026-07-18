package com.example.qte.effect;

import com.example.qte.game.GameActions;
import com.example.qte.game.GameState;
import com.example.qte.game.MinionInstance;
import com.example.qte.game.PlayerState;
import com.example.qte.room.GameRoom;

/**
 * 効果の実行に必要な文脈一式。効果のラムダはこれ1つを受け取って処理を行う。
 *
 * @param owner   効果の持ち主(スペルなら唱えた側、ミニオン効果ならその支配者)
 * @param source  効果の発生源のミニオン。スペル効果の場合はnull
 * @param targets 検証済みの対象。対象指定を要求しないカードではnull
 * @param actions ドロー・回復などの基本操作(勝敗判定込み)への入口
 */
public record EffectContext(
        GameRoom room,
        GameState state,
        PlayerState owner,
        PlayerState opponent,
        MinionInstance source,
        ResolvedTargets targets,
        GameActions actions) {
}
