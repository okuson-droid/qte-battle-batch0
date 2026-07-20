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
            boolean upTo,
            List<Filter> filters,
            String prompt) {

        /** 絞り込みなしの要求を作る */
        public static Requirement of(Kind kind, Side side, int count, boolean optional, String prompt) {
            return new Requirement(kind, side, count, optional, false, List.of(), prompt);
        }

        /** 絞り込み条件付きの要求を作る */
        public static Requirement filtered(Kind kind, Side side, int count, boolean optional,
                String prompt, Filter... filters) {
            return new Requirement(kind, side, count, optional, false, List.of(filters), prompt);
        }

        /**
         * 「好きな数だけ」「あるだけ」選ばせる要求(0個からcount個までのどこでもよい)。
         * 死者蘇生の生贄・禁忌の墓地利用の2枚(墓地に1枚しかなければ1枚)で使う。
         */
        public static Requirement upTo(Kind kind, Side side, int max, String prompt, Filter... filters) {
            return new Requirement(kind, side, max, true, true, List.of(filters), prompt);
        }
    }

    /** 何を選ぶか */
    public enum Kind {
        HAND, MINION,
        /** 自分のマナゾーンのカード(流転の智者の起動能力など)。SideはSELFのみ有効 */
        MANA,
        /**
         * 自分の墓地のカード(闇文明で追加)。SideはSELFのみ有効。
         * 選ばれたカードは墓地に残ったまま効果に渡され、移動は効果自身が行う
         * (蘇生・手札回収・マナ送りで行き先が異なるため)。
         */
        TRASH,
        /**
         * 装備中のウェポン(光文明で追加。聖光の武装解除)。
         * ウェポンは各プレイヤー最大1枚のためインスタンスIDを持たず、
         * どちら側の装備ウェポンかだけを選ばせる(TargetChoice.weaponSides)。
         */
        WEAPON
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
        COST_4_OR_LESS,
        /** コストが3以下(裏切りの魔女) */
        COST_3_OR_LESS,
        /** スペルカードである(墓地からスペルだけを選ばせる) */
        SPELL_CARD,
        /** 光文明のカードである(神の福音) */
        LIGHT_CIVILIZATION,
        /** コストが7以下(聖なる降誕の儀式) */
        COST_7_OR_LESS,
        /**
         * 相手の場で現在攻撃力が最も高い(ホーリー・シグナル)。
         * 複数タイのときだけ実質的にプレイヤーの選択が発生する(タイでなければ1体しか条件を満たさない)。
         */
        HIGHEST_ATTACK_OPPONENT,
        /**
         * 【潜伏】による相手からの対象化禁止を無視する(ホーリー・シグナル)。
         * 通常「相手の効果の対象にならない」潜伏の原則に対する初のテキスト上書き例。
         */
        IGNORES_STEALTH
    }
}
