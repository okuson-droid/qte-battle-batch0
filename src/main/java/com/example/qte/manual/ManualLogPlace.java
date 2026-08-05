package com.example.qte.manual;

/**
 * ログが指す「盤面上の場所」(Batch 21 設計書 5-1)。
 *
 * ★{@link ManualCardRef} と役割が似ているが別物である。{@code ManualCardRef} は
 * 「今どこに居るか」を表す検索結果であり、操作を1つ適用すると無効になる(20b の javadoc)。
 * こちらは<b>ログとして永久に残る記録</b>であり、添字も実体の参照も持たない。
 * 状態を持たない値だけで構成しておくと、後からいくら盤面が動いても行の意味が変わらない。
 *
 * @param seatId 席。★共有ゾーン(PLAY / REVEAL)では null になる
 *               (ハンドオフ3章の「seatId == null 問題」)。無条件に参照しないこと
 * @param zone   ゾーン。★null はリーダーを表す
 */
public record ManualLogPlace(ManualSeatId seatId, ManualZone zone) {

    /** カードの所在から場所を作る。素材(進化スタックの下段)も抱え主のゾーンとして記録する。 */
    public static ManualLogPlace of(ManualCardRef ref) {
        return new ManualLogPlace(ref.seatId(), ref.zone());
    }

    /** 移動先。共有ゾーンなら席は無視される(20b 3-2)。 */
    public static ManualLogPlace of(ManualSeatId seatId, ManualZone zone) {
        return new ManualLogPlace(zone != null && zone.isShared() ? null : seatId, zone);
    }

    /**
     * 「席A 手札」のような表示文字列。
     *
     * ★<b>ゾーンの名前は誰にでも見せる。</b>「どのゾーンで何かが起きたか」は
     * 設計書 5-1 が「行自体は全員に配る(何かが1枚動いたという回数は公開情報)」と
     * 定めた範囲であり、隠すのは中身(カード名)だけである。
     */
    public String describe() {
        String place = seatId == null ? "共有" : "席%s".formatted(seatId);
        if (zone == null) {
            return "%s リーダー".formatted(place);
        }
        return "%s %s".formatted(place, zone.getDisplayName());
    }
}
