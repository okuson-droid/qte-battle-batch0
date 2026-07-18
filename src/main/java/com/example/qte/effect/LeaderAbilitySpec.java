package com.example.qte.effect;

import java.util.function.Consumer;

/**
 * リーダーの起動能力の仕様。使用はメインフェイズ中・1ターンに1回
 * (発注者確認済みルール + 現行リーダーカードの記載)。
 *
 * @param mpCost      使用に必要なMP(流転の智者=2、蒼海の賢者=0)
 * @param targets     使用時に選ばせるもの
 * @param effect      能力の処理
 * @param description ボタンのツールチップ等に出す説明文
 */
public record LeaderAbilitySpec(
        int mpCost,
        TargetSpec targets,
        Consumer<EffectContext> effect,
        String description) {
}
