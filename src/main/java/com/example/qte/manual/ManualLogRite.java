package com.example.qte.manual;

import java.util.List;

/**
 * 儀式を伴うログ1行が持つ<b>構造</b>(★Batch 38 設計書2章)。
 *
 * <h2>★Batch 35 の {@link ManualLogDeclaration} と同じ形である</h2>
 * 35 は「宣言は盤面に痕跡を残さないので、サーバ側に観測できる形を1つ足した」。
 * 38 の開始シーケンスは逆で、<b>痕跡を残しすぎる</b> —— 総入れ替えなので、
 * 差分から読み取れることが何も無い。方向は逆だが、結論は同じである:
 * <b>材料にしてよいのは書式ではなく構造である</b>(裁定40)。
 * ログ本文の「シャッフルして初期ドロー: 席A 4枚 / 席B 5枚」を正規表現で読むのは、
 * 21a が文字列ログをやめた理由を表示側で繰り返すことである。
 *
 * <p>★★手動のシャッフル({@link ManualRiteKind#SHUFFLE})は3つ目の類型である ——
 * <b>盤面に何も起きない</b>。枚数もゾーンも変わらず、非公開の並びだけが変わる。
 * 「痕跡が無い」「痕跡が多すぎる」「そもそも変化が無い」の3つとも、
 * 差分では語れないという一点で同じ器に収まった。</p>
 *
 * <h2>★1行に「ダイス」と「配り」が同居しうる</h2>
 * 全公開部屋でランダムを選ぶと、{@code chooseMethod} はダイスを振ってそのまま配る。
 * ログも1行である。そこで {@code kind} は<b>主たる儀式</b>を表し、
 * ダイスは {@code diceA} / {@code diceB} という<b>別の欄</b>に載る。
 * 「ダイスだけ起きた(対戦部屋・選択権へ)」は {@code kind = DICE} かつ {@code dealt} が空、
 * 「ダイスを振って配った(ソロ)」は {@code kind = DEAL} かつダイスの欄が埋まる。
 * ★画面はこの2つの欄を見るだけでよく、<b>推測する余地が無い</b>。
 *
 * @param kind     主たる儀式。★{@code dealt} をどう読むかもこれで決まる
 * @param diceA    席A の出目。振っていなければ null
 * @param diceB    席B の出目。振っていなければ null
 * @param winner   ダイスに勝った席。振っていなければ null
 * @param label    ダイスの結果の説明(「席A が先攻」「席A が選択権」)。
 *                 ★<b>クライアントで組み立てない</b>(裁定46 と同じ理由)。
 *                 ソロと対戦で意味が変わる(そのまま先攻 / 選択権を得ただけ)ため、
 *                 クライアントに部屋の種類から書き分けさせると条件が2箇所に分かれる
 * @param dealt    <b>この儀式が触った席</b>と、その席で動いた枚数。
 *                 ★{@link ManualRiteKind#DICE} では空、{@link ManualRiteKind#SHUFFLE} では
 *                 席1件で員数は 0 / 0 である(枚数は動かないが、どの山札かは要る)
 * @param pureSeat 【ピュア・エレメント】を受け取った席(★38 追補・マスター裁定 Q1 = b)。
 *                 マリガンが両席とも確定した配信でだけ入る。それ以外は null。
 *                 ★<b>{@code dealt} に混ぜない。</b>あれは山札との出入りの員数であり、
 *                 ピュアは<b>デッキの外</b>から来る。混ぜるとドローと区別が付かなくなる
 */
public record ManualLogRite(
        ManualRiteKind kind,
        Integer diceA,
        Integer diceB,
        ManualSeatId winner,
        String label,
        List<ManualRiteDeal> dealt,
        ManualSeatId pureSeat) {

    public ManualLogRite {
        dealt = dealt == null ? List.of() : List.copyOf(dealt);
    }

    /** ダイスだけを振った(対戦部屋で選択権が発生した)。 */
    public static ManualLogRite dice(int diceA, int diceB, ManualSeatId winner, String label) {
        return new ManualLogRite(ManualRiteKind.DICE, diceA, diceB, winner, label,
                List.of(), null);
    }

    /**
     * 初期ドローを配った。
     * ★ダイスを振ってそのまま配った場合({@code seed} が非 null)は、その出目を引き継ぐ。
     * 引き継ぎをここ1箇所に閉じ込めているので、呼び出し側は
     * 「ダイスを振ったかどうか」を配りの処理で分岐しなくてよい。
     */
    public static ManualLogRite deal(ManualLogRite seed, List<ManualRiteDeal> dealt) {
        return new ManualLogRite(ManualRiteKind.DEAL,
                seed == null ? null : seed.diceA(),
                seed == null ? null : seed.diceB(),
                seed == null ? null : seed.winner(),
                seed == null ? null : seed.label(),
                dealt, null);
    }

    /**
     * マリガン(1席ぶん)。
     *
     * @param pureSeat この確定で開始が完了し、ピュア・エレメントが渡った席。
     *                 渡っていなければ null(★設定が無い / 後攻がデッキ未読込 / まだ完了していない)
     */
    public static ManualLogRite mulligan(ManualSeatId seat, int back, int drew,
            ManualSeatId pureSeat) {
        return new ManualLogRite(ManualRiteKind.MULLIGAN, null, null, null, null,
                List.of(new ManualRiteDeal(seat, back, drew)), pureSeat);
    }

    /**
     * 山札のシャッフル(★38 追補)。
     * ★員数は 0 / 0 である。運ぶのは「どの席の山札を混ぜたか」だけであり、
     * <b>並びは運ばない</b>(非公開情報であり、そもそも演出に要らない)。
     */
    public static ManualLogRite shuffle(ManualSeatId seat) {
        return new ManualLogRite(ManualRiteKind.SHUFFLE, null, null, null, null,
                List.of(new ManualRiteDeal(seat, 0, 0)), null);
    }
}
