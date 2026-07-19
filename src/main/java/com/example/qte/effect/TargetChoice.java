package com.example.qte.effect;

import java.util.List;

/**
 * クライアントから届く、要求1件分の生の選択。検証前の入力値であり信用しない。
 * TargetSpecのrequirementsと同じ順・同じ件数で送られてくることを期待する。
 *
 * @param handIndexes 選んだ手札の位置(Kind.HANDの要求のとき)
 * @param minionIds   選んだミニオンのinstanceId(Kind.MINIONの要求のとき)
 * @param manaIndexes 選んだマナゾーンの位置(Kind.MANAの要求のとき)
 */
public record TargetChoice(List<Integer> handIndexes, List<String> minionIds, List<Integer> manaIndexes) {

    public List<Integer> handIndexes() {
        return handIndexes == null ? List.of() : handIndexes;
    }

    public List<String> minionIds() {
        return minionIds == null ? List.of() : minionIds;
    }

    public List<Integer> manaIndexes() {
        return manaIndexes == null ? List.of() : manaIndexes;
    }
}
