package com.example.qte.manual;

import java.util.ArrayList;
import java.util.List;

/**
 * ログ1件の中身(Batch 21 設計書 5-1)。★<b>表示文字列ではなく構造化イベントである。</b>
 *
 * <h2>なぜ文字列をやめたのか</h2>
 * 20c までのログは「操作した時点で組み立てた1本の文字列」だった。対戦部屋では
 * 同じ行が閲覧者によって違う見え方をしなければならない(5-2)ため、
 * 文字列のままだと後から名前を抜くことができない。抜けるように作るには、
 * 名前がどこに埋まっているかを文字列から復元することになり、必ず破綻する。
 *
 * <h2>★配信とダウンロードを同時に変える(設計書 10章)</h2>
 * レンダリングは {@link ManualLogRenderer} が1本で持ち、
 * 配信({@code ManualViewBuilder})とダウンロード({@code ManualLobbyController})の
 * 両方がそれを通る。片方だけを構造化すると「ダウンロードだけ完全版」という
 * 裏口が残り、5-4 の約束が破れる。
 *
 * <h2>フィールドの役割</h2>
 * <ul>
 *   <li>{@code text} — {@link ManualLogKind#isPlain()} が true の種別で使う。
 *       閲覧者によらずそのまま1行として出る</li>
 *   <li>{@code publicNote} — 誰にでも見せてよい補足(表裏・付け替え結果・残り枚数)。
 *       ★表向き/裏向きは盤面を見れば分かる公開情報である</li>
 *   <li>{@code secretNote} — <b>対象が見える閲覧者にだけ</b>見せる補足
 *       (数値の前後・札の文字列)。手札のカードの数値をいじった事実は残るが、
 *       いくつからいくつへ動いたかは相手に渡さない</li>
 * </ul>
 *
 * @param actorSeat   操作した席。★観戦者・システムでは null。ログの主語ではなく
 *                    「誰が押したか」であり、対象の席({@code cards} の所在)とは別物である
 * @param declaration ★Batch 35: 勝敗宣言の構造({@link ManualLogDeclaration})。
 *                    宣言以外の種別では null である。本文と重複しているように見えるが、
 *                    本文は人が読むもの、こちらは<b>画面が読むもの</b>である(2-2)
 */
public record ManualLogEvent(
        ManualLogKind kind,
        ManualSeatId actorSeat,
        ManualLogPlace origin,
        ManualLogPlace destination,
        List<ManualLogCard> cards,
        String publicNote,
        String secretNote,
        String text,
        ManualLogDeclaration declaration) {

    public ManualLogEvent {
        cards = cards == null ? List.of() : List.copyOf(cards);
    }

    /** 部屋そのものの出来事(入退室・着席・切断・警告)。常に全員へそのまま出る。 */
    public static ManualLogEvent system(String text) {
        return plain(ManualLogKind.SYSTEM, null, text);
    }

    /** 本文をそのまま出す種別(LP・ターン・ドロー・メモなど)。 */
    public static ManualLogEvent plain(ManualLogKind kind, ManualSeatId actorSeat, String text) {
        if (!kind.isPlain()) {
            throw new IllegalArgumentException(
                    "マスク対象の種別を本文だけで記録することはできません: " + kind);
        }
        // ★★Batch 35: 宣言だけは本文のみで作らせない。構造({@link ManualLogDeclaration})が
        //   欠けた DECLARE 行は、ログには出るのに<b>帯も強調も出ない</b>という静かな壊れ方をする。
        //   「分類を忘れると名前が漏れる」を型で止めているのと同じ考え方である。
        if (kind == ManualLogKind.DECLARE) {
            throw new IllegalArgumentException("宣言は declaration(...) で記録すること: " + kind);
        }
        return new ManualLogEvent(kind, actorSeat, null, null, List.of(), null, null, text, null);
    }

    /**
     * 勝敗の宣言(★Batch 35 設計書 2-2)。本文に加えて構造を残す唯一の入口である。
     *
     * @param actorSeat   押した人の席(全公開部屋では null になりうる)
     * @param declaration 宣言の主語と内容。★押した人ではなく<b>宣言される席</b>である
     */
    public static ManualLogEvent declaration(ManualSeatId actorSeat,
            ManualLogDeclaration declaration, String text) {
        if (declaration == null || declaration.declaration() == null) {
            throw new IllegalArgumentException("宣言の内容が指定されていません");
        }
        return new ManualLogEvent(ManualLogKind.DECLARE, actorSeat, null, null, List.of(),
                null, null, text, declaration);
    }

    /**
     * 対象カードを伴う種別(移動・数値・札・タップ等)。★{@link ManualLogRenderer} が組み立てる。
     *
     * @param origin      移動元・対象の所在のまとめ。複数の場所にまたがるなら null
     * @param destination 移動先。移動以外では null
     */
    public static ManualLogEvent targeted(ManualLogKind kind, ManualSeatId actorSeat,
            ManualLogPlace origin, ManualLogPlace destination, List<ManualLogCard> cards,
            String publicNote, String secretNote) {
        if (kind.isPlain()) {
            throw new IllegalArgumentException("本文だけで足りる種別です: " + kind);
        }
        return new ManualLogEvent(kind, actorSeat, origin, destination, cards,
                publicNote, secretNote, null, null);
    }

    /**
     * 複数のカードから所在のまとめを作る。全部が同じ場所なら その場所、
     * ばらけていれば null(= レンダラは「複数の場所」と書く)。
     *
     * ★共有ゾーンでは席が null になるため、{@code seatId} の比較を
     * {@code equals} ではなく参照で行っている(enum なので同値)。
     */
    public static ManualLogPlace commonPlace(List<ManualLogCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return null;
        }
        ManualLogCard first = cards.get(0);
        for (ManualLogCard card : cards) {
            if (card.seatId() != first.seatId() || card.zone() != first.zone()) {
                return null;
            }
        }
        return first.place();
    }

    /** カード一覧を組み立てる小さな補助。呼び出し側の {@code new ArrayList<>()} を減らす。 */
    public static List<ManualLogCard> cardList(ManualLogCard... cards) {
        return new ArrayList<>(List.of(cards));
    }
}
