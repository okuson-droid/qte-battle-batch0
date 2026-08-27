package com.example.qte.support;

import com.example.qte.web.GameBroadcaster;

/**
 * {@code sendError} だけを覗く器(★Batch 72b で {@code WsRequestPayloadTest} の中に生まれ、
 * ★Batch 73 でここへ出した)。
 *
 * <h2>なぜ器を1つにしたか</h2>
 * 73 は「送られてこない項目を断る」を作ったので、
 * <b>断った理由が送り主へ返るか</b>を測る試験が2つになった ——
 * 変換の層({@code WsRequestPayloadTest})と、実際の入口の層
 * ({@code Batch73PayloadGuardTest})である。
 * ★「番人が無い」と思ったら、まず在るかどうかを見る(65 の教訓)——
 * <b>器についても同じである</b>(67)。
 *
 * <p>★他の経路は使わないので、依存は null でよい。
 * {@link GameBroadcaster#broadcast} を呼ぶ試験からは使えない。
 */
public final class PeekingBroadcaster extends GameBroadcaster {

    private String roomId;
    private String playerId;
    private String message;

    public PeekingBroadcaster() {
        super(null, null);
    }

    @Override
    public void sendError(String roomId, String playerId, String message) {
        this.roomId = roomId;
        this.playerId = playerId;
        this.message = message;
    }

    public String roomId() {
        return roomId;
    }

    public String playerId() {
        return playerId;
    }

    /** 返っていなければ null である。★「何も返らない」= 72b の不具合そのものの形 */
    public String message() {
        return message;
    }
}
