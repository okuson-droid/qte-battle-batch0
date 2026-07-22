package com.example.qte.game;

/**
 * 使用され終わったスペルの行き先の置換(設計判断: 置換効果は本来の処理をスキップする)。
 *
 * 通常、スペルは解決後に必ず {@code GameActions.disposeUsedSpell}(墓地 or 【還元】でマナ)を通る。
 * 風文明の2枚はこの行き先そのものを置き換える。
 *
 * <pre>
 *   回帰の風穴   : 追加コストを払った場合、このカードを山札の一番下に置く
 *   風弾の跳弾   : 追加コストを払った場合、墓地に置く代わりに手札に戻す
 * </pre>
 *
 * 効果側が {@code PlayerState.pendingSpellDisposition} に書き込み、
 * 呼び出し元(GameService.playSpell)が読んで消費する。
 * これは pendingFireMinionDiscount(剛火の将)・pendingSacrificeCount(死者蘇生)と同じ、
 * 「効果と呼び出し元のあいだで値を1個受け渡す」既存の型であり、新しい流儀を持ち込んでいない。
 */
public enum SpellDisposition {

    /** 墓地に置く代わりに手札へ戻す(風弾の跳弾) */
    TO_HAND,

    /** 墓地に置く代わりに山札の一番下へ置く(回帰の風穴) */
    TO_DECK_BOTTOM
}
