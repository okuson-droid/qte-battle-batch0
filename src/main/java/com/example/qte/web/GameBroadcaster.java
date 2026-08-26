package com.example.qte.web;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.example.qte.game.view.GameView;
import com.example.qte.game.view.GameViewBuilder;
import com.example.qte.room.GameRoom;
import com.example.qte.room.PlayerSlot;
import com.example.qte.room.Spectator;

import lombok.RequiredArgsConstructor;

/**
 * 盤面ビューの配信担当。
 * 同じGameStateから「プレイヤーごとに違うビュー」を組み立てて、
 * それぞれ専用の宛先(/topic/room/{roomId}/player/{playerId})に送り分ける。
 * playerIdは推測不能なUUIDなので、この宛先が実質的な「本人だけの受信箱」になる。
 *
 * ペイロードはMapではなくWsMessage型で送る。Spring Framework 7の
 * convertAndSendには「ペイロード＋ヘッダーMap」を受けるオーバーロードがあり、
 * Mapを第2引数に渡すと呼び出しが曖昧になってコンパイルエラーになるため
 * (加えて、送信プロトコルの形が型として明文化される利点もある)。
 */
@Component
@RequiredArgsConstructor
public class GameBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;
    private final GameViewBuilder viewBuilder;

    /**
     * クライアントへ送るメッセージの型。
     * type=VIEW のとき view が入り、type=ERROR のとき message が入る。
     */
    public record WsMessage(String type, GameView view, String message) {

        static WsMessage ofView(GameView view) {
            return new WsMessage("VIEW", view, null);
        }

        static WsMessage ofError(String message) {
            return new WsMessage("ERROR", null, message);
        }
    }

    /**
     * 部屋の全員に、それぞれの視点のビューを配信する。
     *
     * <p>★<b>Batch 66: 観戦者も宛先になった。</b>観戦者ぶんの絞り込みは
     * {@code GameViewBuilder} が1本で行う —— この層が「観戦者にはこれを送らない」を
     * 判断しはじめると、フィルタが配信層とビルダー層に割れる(設計判断9)。
     * <b>ここの仕事は「誰に送るか」だけであり、「何を見せるか」ではない。</b>
     */
    public void broadcast(GameRoom room) {
        for (PlayerSlot slot : room.getSlots()) {
            sendViewTo(room, slot.getPlayerId());
        }
        for (Spectator spectator : room.getSpectators()) {
            sendViewTo(room, spectator.spectatorId());
        }
    }

    private void sendViewTo(GameRoom room, String viewerId) {
        GameView view = viewBuilder.build(room, viewerId);
        messagingTemplate.convertAndSend(destinationOf(room.getRoomId(), viewerId),
                WsMessage.ofView(view));
    }

    /** 特定プレイヤーへのエラー通知(ルール違反の操作を拒否したとき) */
    public void sendError(String roomId, String playerId, String message) {
        messagingTemplate.convertAndSend(destinationOf(roomId, playerId),
                WsMessage.ofError(message));
    }

    private String destinationOf(String roomId, String playerId) {
        return "/topic/room/%s/player/%s".formatted(roomId, playerId);
    }
}
