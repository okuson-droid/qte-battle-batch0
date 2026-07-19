package com.example.qte.effect;

import java.util.function.Consumer;

import com.example.qte.game.GameState;
import com.example.qte.game.PlayerState;

/**
 * 【特殊召喚】の仕様。条件・代替コストはカードごとに異なる(キーワード定義)。
 *
 * @param condition   特殊召喚が可能かの判定。handIndexは手札中のこのカード自身の位置
 *                    (プレサージュのように「手札の他のカード」を数える条件のため)
 * @param mpCost      特殊召喚時に支払うMP。多くは0だが、極炎竜ヴォルカニクスは1
 * @param targets     代替コストとして選ばせるもの(なければ空のTargetSpec)
 * @param costEffect  代替コストの支払い処理(選択済み対象を受け取って実行する)
 * @param onSpecialSummon 特殊召喚で出したときのみ発生する追加効果
 *                    (背水の炎壁「これで出したとき1回復」)。通常の【召喚時】とは別枠
 * @param description クライアントの確認ダイアログに出す説明文
 */
public record SpecialSummonSpec(
        Condition condition,
        int mpCost,
        TargetSpec targets,
        Consumer<EffectContext> costEffect,
        Consumer<EffectContext> onSpecialSummon,
        String description) {

    /** MPコストなし・特殊召喚限定効果なしの標準形 */
    public static SpecialSummonSpec of(Condition condition, TargetSpec targets,
            Consumer<EffectContext> costEffect, String description) {
        return new SpecialSummonSpec(condition, 0, targets, costEffect, ctx -> {
        }, description);
    }

    @FunctionalInterface
    public interface Condition {
        boolean test(GameState state, PlayerState player, int handIndex);
    }
}
