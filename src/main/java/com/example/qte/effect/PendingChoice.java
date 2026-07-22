package com.example.qte.effect;

import java.util.List;

/**
 * 効果の解決を中断して、プレイヤーに問い合わせている選択(設計判断32の置き換え)。
 *
 * <b>既存の対象選択との違い。</b>
 * {@link TargetSpec} による対象選択は「カードを使う瞬間に、必要な選択をすべて終える」前提であり、
 * 検証(validateTargets)→ 支払い → 解決 が一直線に進む。
 * 解決の途中で新たに選択が必要になるカード(引いた後に捨てる・戻したマナを含めて選ぶ・
 * 1体目をバウンスした後に2体目を選ぶ)は、この経路では表現できない。
 * 闇文明では {@link AutoChoice} による自動決定で回避していたが、
 * 風文明の裁定5・6により「本人に選ばせる」ことが要求されたため、中断と再開の器を用意した。
 *
 * <b>候補は「選べるものだけ」を入れる。</b>
 * クライアントは {@code candidates} の並び順の位置(0起点)を送り返す。
 * 盤面上の識別子(手札の位置・instanceId 等)を直接送らせないのは、
 * 中断中に盤面が変化しても位置の解釈がずれないようにするためである。
 *
 * @param kind       何の中から選ばせるか。クライアントの表示の切り替えに使う
 * @param candidates 候補の識別子。kind によって意味が変わる
 *                   (HAND=手札の位置 / MINION=instanceId / TRASH=墓地の位置 /
 *                   REVEALED=公開領域 {@code PlayerState.revealedZone} の位置)。
 *                   いずれも文字列で保持し、選べないものは最初から入れない
 * @param min        最低選択数。0なら「選ばなくてもよい」
 * @param max        最大選択数
 * @param resumeAt   再開先。どの効果の続きなのかを識別する
 * @param prompt     クライアントに表示する案内文
 */
public record PendingChoice(
        Kind kind,
        List<String> candidates,
        int min,
        int max,
        ResumePoint resumeAt,
        String prompt) {

    public PendingChoice {
        candidates = List.copyOf(candidates);
        if (min < 0 || max < min) {
            throw new IllegalArgumentException("選択数の指定が不正です");
        }
    }

    /** 候補の中から1つだけ選ばせる(選ばない選択肢はない) */
    public static PendingChoice one(Kind kind, List<String> candidates,
            ResumePoint resumeAt, String prompt) {
        return new PendingChoice(kind, candidates, 1, 1, resumeAt, prompt);
    }

    /** 0個からmax個まで選ばせる(「〜してもよい」「最大N枚まで」) */
    public static PendingChoice upTo(Kind kind, List<String> candidates, int max,
            ResumePoint resumeAt, String prompt) {
        return new PendingChoice(kind, candidates, 0, Math.min(max, candidates.size()), resumeAt, prompt);
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
        REVEALED
    }
}
