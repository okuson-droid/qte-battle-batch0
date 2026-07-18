package com.example.qte.effect;

import java.util.function.Consumer;

import com.example.qte.game.GameState;
import com.example.qte.game.PlayerState;

/**
 * 【特殊召喚】の仕様。条件・代替コストはカードごとに異なる(キーワード定義参照)。
 *
 * @param condition  特殊召喚が可能かの判定。handIndexは手札中のこのカード自身の位置
 *                   (プレサージュのように「手札の他のカード」を数える条件のため)
 * @param targets    代替コストとして選ばせるもの(なければ空のTargetSpec)
 * @param costEffect 代替コストの支払い処理(選択済み対象を受け取って実行する)
 * @param description クライアントの確認ダイアログに出す説明文
 */
public record SpecialSummonSpec(
        Condition condition,
        TargetSpec targets,
        Consumer<EffectContext> costEffect,
        String description) {

    @FunctionalInterface
    public interface Condition {
        boolean test(GameState state, PlayerState player, int handIndex);
    }
}
