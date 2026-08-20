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
 * @param fromTaboo ★Batch 54。この使用が<b>禁忌デッキ由来</b>か。
 *                 効果が<b>使用したカード自身を場に出す</b>場合にだけ意味を持つ ——
 *                 禁忌由来のミニオンは場を離れると消滅する(総合ルール3-6)ので、
 *                 その印を持たせずに場へ出すと、あとで墓地へ行ってしまう。
 *                 ★現在の使い手は《スタンディングテント》の【賢魂：2】1枚だけである。
 *                 それ以外の効果が出すカードは手札・墓地・マナ・山札から来るので、
 *                 禁忌由来でありえない(禁忌カードはそれらのゾーンを通らない)
 */
public record EffectContext(
        GameRoom room,
        GameState state,
        PlayerState owner,
        PlayerState opponent,
        MinionInstance source,
        ResolvedTargets targets,
        GameActions actions,
        boolean enhanced,
        boolean fromTaboo) {

    /** 強化使用の区別を持たない文脈(トリガー・基本操作からの発火)を作る */
    public EffectContext(GameRoom room, GameState state, PlayerState owner, PlayerState opponent,
            MinionInstance source, ResolvedTargets targets, GameActions actions) {
        this(room, state, owner, opponent, source, targets, actions, false, false);
    }

    /** 強化使用の区別だけを持たせる文脈(禁忌由来ではない通常の使用) */
    public EffectContext(GameRoom room, GameState state, PlayerState owner, PlayerState opponent,
            MinionInstance source, ResolvedTargets targets, GameActions actions, boolean enhanced) {
        this(room, state, owner, opponent, source, targets, actions, enhanced, false);
    }

    /** 同じ文脈で発生源のミニオンだけを差し替える(トリガーの一斉発火で使う) */
    public EffectContext withSource(MinionInstance newSource) {
        return new EffectContext(room, state, owner, opponent, newSource, targets, actions,
                enhanced, fromTaboo);
    }
}
