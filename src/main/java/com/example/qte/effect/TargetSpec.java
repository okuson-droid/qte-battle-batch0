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
     * @param filters  選択可能なカードの絞り込み条件。複数指定した場合はAND条件
     *                 (空なら制限なし)
     * @param prompt   クライアントに表示する選択の案内文
     */
    public record Requirement(
            Kind kind,
            Side side,
            int count,
            boolean optional,
            List<Filter> filters,
            String prompt) {

        /** 絞り込みなしの要求を作る */
        public static Requirement of(Kind kind, Side side, int count, boolean optional, String prompt) {
            return new Requirement(kind, side, count, optional, List.of(), prompt);
        }

        /** 絞り込み条件付きの要求を作る */
        public static Requirement filtered(Kind kind, Side side, int count, boolean optional,
                String prompt, Filter... filters) {
            return new Requirement(kind, side, count, optional, List.of(filters), prompt);
        }
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

    /**
     * 選択対象の絞り込み条件。1つの要求に複数指定でき、その場合はすべてを満たすものだけが選べる。
     * 例: フレイム・スナイプ = GUARD + HP_5_OR_LESS(相手の守護持ちでHP5以下)
     */
    public enum Filter {
        /** 【知識】を持つ */
        KNOWLEDGE,
        /** 【守護】を持つ */
        GUARD,
        /** ミニオンカードである(手札から選ぶ場合に使う) */
        MINION_CARD,
        /** 現在HPが5以下(場のミニオンにのみ意味を持つ) */
        HP_5_OR_LESS,
        /** コストが4以下 */
        COST_4_OR_LESS
    }
}
