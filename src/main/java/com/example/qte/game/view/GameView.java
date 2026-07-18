package com.example.qte.game.view;

import java.util.List;

/**
 * クライアントに配信する盤面ビューの頂点。
 * GameStateそのものは絶対に送らない。プレイヤーごとに「見えてよい情報」だけを
 * 詰め直したものがこのDTOである(設計判断9: 非公開情報のフィルタリング)。
 *
 * @param you           自分の情報(手札の中身を含む)
 * @param opponent      相手の情報(手札は枚数のみ)
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
        PlayerView you,
        PlayerView opponent,
        List<String> log) {
}
