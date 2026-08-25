package com.example.qte.effect;

import java.util.List;

/**
 * 効果の解決の途中で、プレイヤーに問い合わせている選択(設計判断32の置き換え)。
 *
 * <b>既存の対象選択との違い。</b>
 * {@link TargetSpec} による対象選択は「カードを使う瞬間に、必要な選択をすべて終える」前提であり、
 * 検証(validateTargets)→ 支払い → 解決 が一直線に進む。
 * 解決の途中で新たに選択が必要になるカード(引いた後に捨てる・戻したマナを含めて選ぶ・
 * 1体目をバウンスした後に2体目を選ぶ)は、この経路では表現できない。
 * 闇文明では {@code AutoChoice} による自動決定で回避していたが、
 * 風文明の裁定5・6により「本人に選ばせる」ことが要求されたため、中断と再開の器を用意した。
 *
 * <h2>★これは「中断」ではなく「後回し」である(★Batch 64。裁定301)</h2>
 *
 * 名前に反して、問い合わせを作っても<b>呼び出し元の続きはそのまま走る</b>。
 * {@code GameActions.requestChoice} は選択を積んで戻るだけであり、
 * 止まるのは<b>答えで動く部分</b>(={@code CardEffectRegistry.resolveChoice} に書いた続き)だけである。
 *
 * <p>この性質が分かるまで、59 までの設計解説は
 * 「ドローはエンジンのあらゆる場所から呼ばれるので中断点を作れない」と書いていた。
 * <b>中断しないのだから、深いところから問い合わせても困らない。</b>
 * 実際に詰まっていたのは (1) はい/いいえの器が無い (2) 1人につき1件しか積めない
 * (3) 再開に要る値を運べない、の3つであり、64 はその3つを外した。
 *
 * <p>★代償として、<b>「してもよい」の結果は元の効果の残りより後に起きる</b>。
 * 《冥界神ハデス》が全体を破壊し、自分の蘇生まで終えたあとに
 * 《不滅のネクロマンサー》の蘇生が乗る、という順になる。
 *
 * <b>候補は「選べるものだけ」を入れる。</b>
 * クライアントは {@code candidates} の並び順の位置(0起点)を送り返す。
 * 盤面上の識別子(手札の位置・instanceId 等)を直接送らせないのは、
 * 送られてきた値をそのまま盤面の操作に使わせないためである。
 *
 * <p>★<b>ただし位置そのものはずれうる</b>(★Batch 64)。1人が複数の問い合わせを
 * 同時に持てるようになったので、<b>先に答えた選択の解決で、後ろの選択の候補が指す先が動く</b>。
 * {@code expectedCardIds} がその番人である —— 作った瞬間のゾーンの中身を控えておき、
 * 解決の直前に照合する。詳細は {@code GameService.resolveChoice}。
 *
 * @param kind            何の中から選ばせるか。クライアントの表示の切り替えに使う
 * @param candidates      候補の識別子。kind によって意味が変わる
 *                        (HAND=手札の位置 / MINION=instanceId / TRASH=墓地の位置 /
 *                        REVEALED=公開領域 {@code PlayerState.revealedZone} の位置 /
 *                        MANA=マナゾーンの位置 / CONFIRM=固定の1件)。
 *                        いずれも文字列で保持し、選べないものは最初から入れない
 * @param min             最低選択数。0なら「選ばなくてもよい」
 * @param max             最大選択数
 * @param resumeAt        再開先。どの効果の続きなのかを識別する
 * @param prompt          クライアントに表示する案内文
 * @param expectedCardIds 候補と同じ長さの、作った瞬間のカードID。位置で指すゾーン
 *                        (HAND/TRASH/REVEALED/MANA)のときだけ入る。空なら照合しない
 * @param payload         再開に要る、候補以外の値(★Batch 64)。
 *                        《不滅のネクロマンサー》の「どのミニオンを蘇生するか」など。
 *                        不要ならnull
 */
public record PendingChoice(
        Kind kind,
        List<String> candidates,
        int min,
        int max,
        ResumePoint resumeAt,
        String prompt,
        List<String> expectedCardIds,
        String payload) {

    /** 「はい」を表す唯一の候補(CONFIRM)。選ばなければ「いいえ」である */
    public static final String CONFIRM_YES = "YES";

    public PendingChoice {
        candidates = List.copyOf(candidates);
        expectedCardIds = List.copyOf(expectedCardIds);
        if (min < 0 || max < min) {
            throw new IllegalArgumentException("選択数の指定が不正です");
        }
        if (!expectedCardIds.isEmpty() && expectedCardIds.size() != candidates.size()) {
            throw new IllegalArgumentException("控えの数が候補の数と合いません");
        }
    }

    /**
     * 選択数を明示して作る(★Batch 64 で {@code new PendingChoice(...)} から置き換え)。
     * 控え({@code expectedCardIds})は {@code GameActions.requestChoice} が入れるので、
     * 作る側は持たない。
     */
    public static PendingChoice of(Kind kind, List<String> candidates, int min, int max,
            ResumePoint resumeAt, String prompt) {
        return new PendingChoice(kind, candidates, min, max, resumeAt, prompt, List.of(), null);
    }

    /** 候補の中から1つだけ選ばせる(選ばない選択肢はない) */
    public static PendingChoice one(Kind kind, List<String> candidates,
            ResumePoint resumeAt, String prompt) {
        return new PendingChoice(kind, candidates, 1, 1, resumeAt, prompt, List.of(), null);
    }

    /** 0個からmax個まで選ばせる(「〜してもよい」「最大N枚まで」) */
    public static PendingChoice upTo(Kind kind, List<String> candidates, int max,
            ResumePoint resumeAt, String prompt) {
        return new PendingChoice(kind, candidates, 0, Math.min(max, candidates.size()),
                resumeAt, prompt, List.of(), null);
    }

    /**
     * 「〜してもよい」の はい/いいえ(★Batch 64)。
     *
     * <p>★<b>候補を1件だけ持つ選択として表す。</b>選べば「はい」、選ばなければ「いいえ」であり、
     * 送受信の形は他の選択と1バイトも変わらない —— WebSocket の型もビューの型も足していない。
     * クライアントは {@code kind === 'CONFIRM'} を見て[はい][いいえ]の2つのボタンに描き替えるだけである。
     */
    public static PendingChoice confirm(ResumePoint resumeAt, String prompt) {
        return new PendingChoice(Kind.CONFIRM, List.of(CONFIRM_YES), 0, 1,
                resumeAt, prompt, List.of(), null);
    }

    /** 再開に要る値を添えた同じ選択を返す(★Batch 64) */
    public PendingChoice withPayload(String value) {
        return new PendingChoice(kind, candidates, min, max, resumeAt, prompt, expectedCardIds, value);
    }

    /** 作った瞬間のゾーンの中身を控えた同じ選択を返す(★Batch 64。{@code GameActions.requestChoice} が呼ぶ) */
    public PendingChoice withExpectedCardIds(List<String> cardIds) {
        return new PendingChoice(kind, candidates, min, max, resumeAt, prompt, cardIds, payload);
    }

    /** この選択の候補が「ゾーン内の位置」であるか(★Batch 64。控えを取る対象かどうか) */
    public boolean pointsAtZonePositions() {
        return kind == Kind.HAND || kind == Kind.TRASH || kind == Kind.REVEALED || kind == Kind.MANA;
    }

    /** 何を選ぶか。TargetSpec.Kind とは別物(あちらは使用宣言時、こちらは解決中の選択) */
    public enum Kind {
        /** 自分の手札 */
        HAND,
        /** 場のミニオン(候補の側は candidates の内容で表現する) */
        MINION,
        /** 自分の墓地 */
        TRASH,
        /** 一時公開領域(PlayerState.revealedZone) */
        REVEALED,
        /**
         * 自分のマナゾーン(候補は manaZone 内の位置)。
         * 表向き・裏向きのどちらも候補になりうる(地砕きの突撃兵の「自分のマナから1枚選び」は
         * 向きを限定していないため。流転の智者の TargetSpec.Kind.MANA と同じ扱い)。
         */
        MANA,
        /**
         * はい/いいえ(★Batch 64)。「〜してもよい」を本人に問う。
         * 候補は {@link #CONFIRM_YES} 1件だけで、選べば「はい」・選ばなければ「いいえ」である。
         */
        CONFIRM
    }
}
