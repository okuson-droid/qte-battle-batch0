package com.example.qte.effect;

import java.util.function.BiPredicate;
import java.util.function.Consumer;

import com.example.qte.game.GameState;
import com.example.qte.game.PlayerState;

/**
 * リーダーの起動能力の仕様。使用はメインフェイズ中・1ターンに1回
 * (発注者確認済みルール + 現行リーダーカードの記載)。
 *
 * @param mpCost      使用に必要なMP(流転の智者=2、蒼海の賢者=0)
 * @param targets     使用時に選ばせるもの
 * @param effect      能力の処理
 * @param condition   使用可能かの判定。冥府の禁皇のように「代償を払えなければ使えない」
 *                    能力のため、状態を変更する前に判定する必要がある
 * @param description ボタンのツールチップ等に出す説明文
 */
public record LeaderAbilitySpec(
        int mpCost,
        TargetSpec targets,
        Consumer<EffectContext> effect,
        BiPredicate<GameState, PlayerState> condition,
        String description) {

    /** 使用条件のない標準形(いつでも使える) */
    public static LeaderAbilitySpec of(int mpCost, TargetSpec targets,
            Consumer<EffectContext> effect, String description) {
        return new LeaderAbilitySpec(mpCost, targets, effect, (state, player) -> true, description);
    }
}
