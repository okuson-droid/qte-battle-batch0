package com.example.qte.web;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.example.qte.game.view.GameView;
import com.example.qte.game.view.GameViewBuilder;
import com.example.qte.room.GameRoom;
import com.example.qte.room.PlayerSlot;

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

    /** 部屋の全プレイヤーに、それぞれの視点のビューを配信する */
    public void broadcast(GameRoom room) {
        for (PlayerSlot slot : room.getSlots()) {
            GameView view = viewBuilder.build(room, slot.getPlayerId());
            messagingTemplate.convertAndSend(destinationOf(room.getRoomId(), slot.getPlayerId()),
                    WsMessage.ofView(view));
        }
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
