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
 * @param enhanced 追加コストを支払う「強化使用」を宣言してこのカードを使ったか(a5)。
 *                 該当しないカード・トリガー由来の効果では常にfalse
 */
public record EffectContext(
        GameRoom room,
        GameState state,
        PlayerState owner,
        PlayerState opponent,
        MinionInstance source,
        ResolvedTargets targets,
        GameActions actions,
        boolean enhanced) {

    /** 強化使用の区別を持たない文脈(トリガー・基本操作からの発火)を作る */
    public EffectContext(GameRoom room, GameState state, PlayerState owner, PlayerState opponent,
            MinionInstance source, ResolvedTargets targets, GameActions actions) {
        this(room, state, owner, opponent, source, targets, actions, false);
    }

    /** 同じ文脈で発生源のミニオンだけを差し替える(トリガーの一斉発火で使う) */
    public EffectContext withSource(MinionInstance newSource) {
        return new EffectContext(room, state, owner, opponent, newSource, targets, actions, enhanced);
    }
}
