package com.example.qte.master;

import java.util.Set;

/**
 * カードマスタ(1種類のカードの定義)。ゲーム中に変化しない不変データ。
 * ゲーム中に変化する値(残りHP・修正値など)は game.MinionInstance 側が持つ。
 *
 * @param cost    スペル・ミニオン・ウェポンの基礎コスト。リーダーはnull。
 * @param attack  基礎攻撃力。動的修正(手札枚数参照など)は含まない印刷値。スペル・リーダーはnull。
 * @param hp      基礎体力。ミニオンのみ。
 * @param text    効果テキスト(原文)。効果の構造化はBatch 2のエフェクトシステムで行う。
 */
public record CardMaster(
        String id,
        String name,
        CardType type,
        Civilization civilization,
        Integer cost,
        Integer attack,
        Integer hp,
        Set<Keyword> keywords,
        String text) {

    public boolean hasKeyword(Keyword keyword) {
        return keywords.contains(keyword);
    }
}
