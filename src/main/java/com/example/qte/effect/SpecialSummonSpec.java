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
 * @param fromGrave   ★Batch 53。<b>自分の墓地からも特殊召喚できるか</b>
 *                    (《サモナーポップ・エンラ》「自分の手札または墓地から」)。
 *                    既定は false —— 【特殊召喚】は本来「手札から」の代替召喚であり、
 *                    墓地から出せると書いてあるカードだけがここを true にする。
 *                    ★真偽値をここに置いたのは、判定の正を<b>カードの宣言1箇所</b>に
 *                    保つためである。{@code GameService.specialSummonFromGrave} も
 *                    {@code GameViewBuilder}(墓地の面に印を出す側)も同じ値を読む
 */
public record SpecialSummonSpec(
        Condition condition,
        int mpCost,
        TargetSpec targets,
        Consumer<EffectContext> costEffect,
        Consumer<EffectContext> onSpecialSummon,
        String description,
        boolean fromGrave) {

    /** MPコストなし・特殊召喚限定効果なし・手札からのみ、の標準形 */
    public static SpecialSummonSpec of(Condition condition, TargetSpec targets,
            Consumer<EffectContext> costEffect, String description) {
        return new SpecialSummonSpec(condition, 0, targets, costEffect, ctx -> {
        }, description, false);
    }

    /** 手札からのみ出せる従来の6引数の形(★Batch 53 で fromGrave を足したときの互換) */
    public SpecialSummonSpec(Condition condition, int mpCost, TargetSpec targets,
            Consumer<EffectContext> costEffect, Consumer<EffectContext> onSpecialSummon,
            String description) {
        this(condition, mpCost, targets, costEffect, onSpecialSummon, description, false);
    }

    @FunctionalInterface
    public interface Condition {
        boolean test(GameState state, PlayerState player, int handIndex);
    }
}
