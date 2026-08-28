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
    private int roomLostCount;
    private int broadcastCount;

    public PeekingBroadcaster() {
        super(null, null);
    }

    @Override
    public void sendError(String roomId, String playerId, String message) {
        this.roomId = roomId;
        this.playerId = playerId;
        this.message = message;
    }

    /**
     * ★Batch 75(裁定344): 部屋消失も覗く。
     *
     * <p>★<b>{@link #message} には何も書かない。</b>ROOM_LOST は本文を持たない型であり、
     * ここで文言を作ると<b>試験の中に、実装には無い文字列が生まれる</b>(裁定181)。
     * ★数だけを数えるのは、「ERROR ではなくこちらが呼ばれた」ことが測りたいものだからである。
     */
    @Override
    public void sendRoomLost(String roomId, String occupantId) {
        this.roomId = roomId;
        this.playerId = occupantId;
        this.roomLostCount++;
    }

    /** 部屋消失を返した回数(★Batch 75)。★0 なら ERROR の側へ行っている */
    public int roomLostCount() {
        return roomLostCount;
    }

    /**
     * ★Batch 75: <b>成功したときの配信を受け止める。</b>
     *
     * <p>73 まで、この器を使う試験は<b>拒否される操作しか流していなかった</b> ——
     * 成功すると {@code execute} が {@code broadcast} を呼び、
     * 依存が null のこの器は<b>そこで落ちる</b>。
     * ★75 は「{@code ready} を通すと接続が記録される」を測るために
     * <b>成功する操作</b>を流す必要があった(72 の教訓「番人は実際の入口から起こす」)。
     * ★<b>ビューは組み立てない。</b>ここで測りたいのは配信の中身ではなく、
     * <b>部屋の状態が変わったこと</b>である。
     */
    @Override
    public void broadcast(com.example.qte.room.GameRoom room) {
        broadcastCount++;
    }

    /** 配信が起きた回数(★Batch 75)。操作が受理されたことの目印である */
    public int broadcastCount() {
        return broadcastCount;
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
