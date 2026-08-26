package com.example.qte.game.view;

import java.util.List;

/**
 * クライアントに配信する盤面ビューの頂点。
 * GameStateそのものは絶対に送らない。プレイヤーごとに「見えてよい情報」だけを
 * 詰め直したものがこのDTOである(設計判断9: 非公開情報のフィルタリング)。
 *
 * <p>★Batch 66: 受付({@link RoomView})を頂点に足した。席選択とデッキの読み込みは
 * 盤面がまだ無い時間帯に行われるため、{@code you / opponent} の下には置けない。
 *
 * @param room          部屋そのものの情報(★Batch 66)。盤面の有無によらず必ず入る
 * @param you           自分の情報(手札の中身を含む)。★観戦者には席Aの<b>公開情報</b>が入る
 * @param opponent      相手の情報(手札は枚数のみ)。★観戦者には席Bの公開情報が入る
 * @param chooseOrder   trueなら「先攻/後攻を選んでください」の入力待ち(ダイス勝者にのみtrue)
 * @param mulligan      trueならこのプレイヤーのマリガン選択待ち
 * @param winnerName    決着時のみ非null
 */
public record GameView(
        String roomId,
        String status,
        int turnNumber,
        String phase,
        String phaseDisplay,
        boolean myTurn,
        boolean chooseOrder,
        boolean mulligan,
        String winnerName,
        RoomView room,
        PlayerView you,
        PlayerView opponent,
        List<String> log) {
}
