package com.example.qte.effect;

import java.util.List;

/**
 * カードが要求する対象指定の仕様。
 * クライアントはこれを見て選択UIを進行し、サーバはこれに照らして選択の正当性を検証する。
 * 「何を選ばせるか」の定義は1箇所(CardEffectRegistry)にだけ存在し、
 * クライアントとサーバが同じ定義を共有する。
 *
 * @param requirements 要求の列。カードのテキストに書かれた順に選択させる
 */
public record TargetSpec(List<Requirement> requirements) {

    public static TargetSpec of(Requirement... requirements) {
        return new TargetSpec(List.of(requirements));
    }

    /**
     * 対象指定の要求1件。
     *
     * @param optional trueなら「〜してもよい」(0個かcount個かを選べる)
     * @param filter   選択可能なカードの絞り込み(nullなら制限なし)
     * @param prompt   クライアントに表示する選択の案内文
     */
    public record Requirement(
            Kind kind,
            Side side,
            int count,
            boolean optional,
            Filter filter,
            String prompt) {
    }

    /** 何を選ぶか */
    public enum Kind {
        HAND, MINION,
        /** 自分のマナゾーンのカード(流転の智者の起動能力など)。SideはSELFのみ有効 */
        MANA
    }

    /** どちら側から選ぶか */
    public enum Side {
        SELF, OPPONENT, ANY
    }

    /** 選択対象の絞り込み条件 */
    public enum Filter {
        /** 【知識】を持つカードのみ */
        KNOWLEDGE
    }
}
