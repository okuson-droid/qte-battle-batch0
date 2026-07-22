package com.example.qte.effect;

import java.util.function.BiPredicate;
import java.util.function.Consumer;

import com.example.qte.game.GameState;
import com.example.qte.game.MinionInstance;
import com.example.qte.game.PlayerState;

/**
 * 場のミニオンが持つ起動能力の仕様(a6。静空の風使いが初出)。
 *
 * <b>LeaderAbilitySpec と統合しない理由。</b>
 * 構造はほぼ同じだが、共通なのは名前だけである。
 *
 * <pre>
 *   コスト体系  : リーダー = MP / ミニオン = 自身をタップ
 *   使用制限    : リーダー = 1ターン1回のフラグ / ミニオン = タップ状態そのもの
 *   呼び出し経路: リーダー = プレイヤー単位 / ミニオン = インスタンス単位
 * </pre>
 *
 * サービスクラスの分割基準と同じ理屈であり、
 * 依存関係・呼び出しコンテキスト・責務のいずれもが異なるため別の型として持つ。
 *
 * @param mpCost      MPコスト。現行カードは0だが、将来のために枠を用意しておく
 * @param targets     使用時に選ばせるもの
 * @param effect      能力の処理。EffectContext.source には能力を使ったミニオン自身が入る
 * @param condition   使用可能かの判定(代償を払えない場合に弾く)
 * @param description ボタンのツールチップに出す説明文
 */
public record MinionAbilitySpec(
        int mpCost,
        TargetSpec targets,
        Consumer<EffectContext> effect,
        BiPredicate<GameState, PlayerState> condition,
        String description) {

    /** 使用条件のない標準形 */
    public static MinionAbilitySpec of(int mpCost, TargetSpec targets,
            Consumer<EffectContext> effect, String description) {
        return new MinionAbilitySpec(mpCost, targets, effect, (state, player) -> true, description);
    }

    /**
     * このミニオンが今この瞬間に能力を使えるか(タップ状態・MP・条件)。
     * サーバの検証とビューの活性判定が同じ判断を通るようにするため、ここに置く。
     */
    public boolean usableBy(GameState state, PlayerState owner, MinionInstance minion) {
        return !minion.isTapped()
                && owner.getAvailableMp() >= mpCost
                && condition.test(state, owner);
    }
}
