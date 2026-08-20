package com.example.qte.effect;

import java.util.function.Consumer;

/**
 * 【賢魂：n】としての使用の仕様(★Batch 54。裁定152)。
 *
 * <blockquote>
 * 賢魂：n を持つミニオンは、スペルとしても使うことができる。
 * スペルとして使う場合のコストは n。効果は【賢魂：n】に続くテキスト。
 * ミニオンとして召喚した場合、賢魂の効果は発動しない。
 * </blockquote>
 *
 * <h2>★コストをここに持たない理由</h2>
 *
 * n は<b>カードテキストに書いてある</b>。
 * {@link com.example.qte.master.CardTextKeywords#soulCost(String)} が唯一の出どころであり、
 * この仕様は<b>効果と対象要求だけ</b>を持つ。両方に書けば、カードデータを直したときに
 * コードが古いままになる日が必ず来る(裁定158 の延長 —— テキストが正である)。
 *
 * <h2>★ミニオンとしての対象要求とは別物である</h2>
 *
 * {@code CardEffectRegistry.targetSpecs} はミニオンとして召喚したときの
 * 【召喚時】が要求する対象である。賢魂として使うときはそちらを見ない ——
 * 同じカードでも、姿が違えば選ばせるものが違う。
 * 《白ノ霊知者》は召喚時に「破壊するミニオン1体」を、
 * 賢魂では「攻撃力+1する自分のミニオン1体」を選ばせる。
 *
 * @param targets 賢魂として使うときの対象要求(不要なら {@code TargetSpec.of()})
 * @param effect  賢魂としての効果。解決の作法は通常のスペル({@code spellEffects})と同じである
 */
public record SoulSpellSpec(TargetSpec targets, Consumer<EffectContext> effect) {

    /** 対象を要求しない賢魂 */
    public static SoulSpellSpec of(Consumer<EffectContext> effect) {
        return new SoulSpellSpec(TargetSpec.of(), effect);
    }

    /** 対象を要求する賢魂 */
    public static SoulSpellSpec of(TargetSpec targets, Consumer<EffectContext> effect) {
        return new SoulSpellSpec(targets, effect);
    }
}
