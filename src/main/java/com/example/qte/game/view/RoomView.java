package com.example.qte.game.view;

/**
 * 部屋そのもののビュー(★Batch 66)。盤面ではなく<b>受付</b>の情報である。
 *
 * <h2>なぜ {@link GameView} の頂点に置くのか</h2>
 * 席選択とデッキの読み込みは、<b>盤面がまだ存在しない状態</b>で行われる
 * ({@code GameView.you / opponent} はどちらも null である)。
 * 受付の情報を {@code PlayerView} の下にぶら下げると、
 * いちばん要る時間帯に届かない。
 *
 * <h2>★ここには「見えてよい情報」しか無い</h2>
 * 名前・席の埋まり・デッキを読んだかどうかは、入室していない人にも見える
 * (ロビーの一覧が同じものを出している)。デッキの<b>中身</b>は入っていない ——
 * 相手のデッキリストが見えたら対戦にならない。
 *
 * @param roomName         部屋名
 * @param spectatorAllowed 観戦を許す部屋か
 * @param spectatorCount   今いる観戦者の数
 * @param seatA            席Aの状態
 * @param seatB            席Bの状態
 * @param viewerSeat       この人が座っている席("A"/"B")。観戦者は null
 * @param viewerSpectator  この人が観戦者か
 */
public record RoomView(
        String roomName,
        boolean spectatorAllowed,
        int spectatorCount,
        SeatView seatA,
        SeatView seatB,
        String viewerSeat,
        boolean viewerSpectator) {

    /**
     * 席1つの状態。
     *
     * @param name       在席者の名前。空席なら null
     * @param deckLoaded デッキファイルを読み込み済みか。★試合開始の条件の半分である
     * @param ready      WebSocket の購読まで済んでいるか
     */
    public record SeatView(String name, boolean deckLoaded, boolean ready) {

        /** 空席 */
        public static SeatView empty() {
            return new SeatView(null, false, false);
        }

        public boolean occupied() {
            return name != null;
        }
    }
}
