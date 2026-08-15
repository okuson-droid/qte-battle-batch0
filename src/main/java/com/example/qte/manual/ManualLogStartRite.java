package com.example.qte.manual;

import java.util.List;

/**
 * 開始シーケンスのログ1行が持つ<b>構造</b>(★Batch 38 設計書2章)。
 *
 * <h2>★Batch 35 の {@link ManualLogDeclaration} と同じ形である</h2>
 * 35 は「宣言は盤面に痕跡を残さないので、サーバ側に観測できる形を1つ足した」。
 * 38 は逆で、開始シーケンスは<b>痕跡を残しすぎる</b> —— 総入れ替えなので、
 * 差分から読み取れることが何も無い。方向は逆だが、結論は同じである:
 * <b>材料にしてよいのは書式ではなく構造である</b>(裁定40)。
 * ログ本文の「シャッフルして初期ドロー: 席A 4枚 / 席B 5枚」を正規表現で読むのは、
 * 21a が文字列ログをやめた理由を表示側で繰り返すことである。
 *
 * <h2>★1行に「ダイス」と「配り」が同居しうる</h2>
 * 全公開部屋でランダムを選ぶと、{@code chooseMethod} はダイスを振ってそのまま配る。
 * ログも1行である。そこで {@code kind} は<b>主たる儀式</b>を表し、
 * ダイスは {@code diceA} / {@code diceB} という<b>別の欄</b>に載る。
 * 「ダイスだけ起きた(対戦部屋・選択権へ)」は {@code kind = DICE} かつ {@code dealt} が空、
 * 「ダイスを振って配った(ソロ)」は {@code kind = DEAL} かつダイスの欄が埋まる。
 * ★画面はこの2つの欄を見るだけでよく、<b>推測する余地が無い</b>。
 *
 * @param kind   主たる儀式。★{@code dealt} をどう読むかもこれで決まる
 * @param diceA  席A の出目。振っていなければ null
 * @param diceB  席B の出目。振っていなければ null
 * @param winner ダイスに勝った席。振っていなければ null
 * @param label  ダイスの結果の説明(「席A が先攻」「席A が選択権」)。
 *               ★<b>クライアントで組み立てない</b>(裁定46 と同じ理由)。
 *               ソロと対戦で意味が変わる(そのまま先攻 / 選択権を得ただけ)ため、
 *               クライアントに部屋の種類から書き分けさせると条件が2箇所に分かれる
 * @param dealt  席ごとの出入りの員数。★{@link ManualStartRite#DICE} では空である
 */
public record ManualLogStartRite(
        ManualStartRite kind,
        Integer diceA,
        Integer diceB,
        ManualSeatId winner,
        String label,
        List<ManualStartDeal> dealt) {

    public ManualLogStartRite {
        dealt = dealt == null ? List.of() : List.copyOf(dealt);
    }

    /** ダイスだけを振った(対戦部屋で選択権が発生した)。 */
    public static ManualLogStartRite dice(int diceA, int diceB, ManualSeatId winner, String label) {
        return new ManualLogStartRite(ManualStartRite.DICE, diceA, diceB, winner, label, List.of());
    }

    /**
     * 初期ドローを配った。
     * ★ダイスを振ってそのまま配った場合({@code seed} が非 null)は、その出目を引き継ぐ。
     * 引き継ぎをここ1箇所に閉じ込めているので、呼び出し側は
     * 「ダイスを振ったかどうか」を配りの処理で分岐しなくてよい。
     */
    public static ManualLogStartRite deal(ManualLogStartRite seed, List<ManualStartDeal> dealt) {
        return new ManualLogStartRite(ManualStartRite.DEAL,
                seed == null ? null : seed.diceA(),
                seed == null ? null : seed.diceB(),
                seed == null ? null : seed.winner(),
                seed == null ? null : seed.label(),
                dealt);
    }

    /** マリガン(1席ぶん)。 */
    public static ManualLogStartRite mulligan(ManualSeatId seat, int back, int drew) {
        return new ManualLogStartRite(ManualStartRite.MULLIGAN, null, null, null, null,
                List.of(new ManualStartDeal(seat, back, drew)));
    }
}
