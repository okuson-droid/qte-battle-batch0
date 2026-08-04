package com.example.qte.manual.web;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import com.example.qte.manual.ManualOccupant;
import com.example.qte.manual.ManualRoom;
import com.example.qte.manual.ManualRoomManager;

import lombok.RequiredArgsConstructor;

/**
 * 手動モードの WebSocket 入口。宛先は {@code /app/manual/{roomId}/{action}}(設計書 2-4)。
 *
 * ★{@code WebSocketConfig} は1行も変更していない。{@code /app} と {@code /topic} の規約は
 * 通常モードと共有し、前置詞({@code room} と {@code manual})だけで系統を分ける。
 *
 * すべてのハンドラは共通の型で処理する。
 * <ol>
 *   <li>部屋を特定する</li>
 *   <li>{@code synchronized (room.getLock())} で「1部屋1操作」に直列化する</li>
 *   <li>状態を変更する</li>
 *   <li>成功: 在室者全員へビューを配信 / 失敗: 操作者にだけエラーを返す</li>
 * </ol>
 *
 * <h2>★Batch 17b にある操作は ready と resync だけである</h2>
 * 設計書 5-3 の操作13項目は Batch 18a で、この型に沿って足す。
 * 17b の目的は、その13項目が同じ形で並べられる配管を先に通しておくことである。
 *
 * <h2>occupantId をここで発行しない理由</h2>
 * 配信先が {@code /topic/manual/{roomId}/view/{occupantId}} である以上、
 * occupantId を知る前のクライアントには受信できる宛先が存在しない。
 * したがって入室は HTTP({@link ManualLobbyController})で行い、
 * 受け取った occupantId で購読してから ready を送る。
 * 通常モードで playerId を {@code LobbyController} が発行しているのと同じ構造である。
 */
@Controller
@RequiredArgsConstructor
public class ManualWsController {

    private final ManualRoomManager roomManager;

    private final ManualBroadcaster broadcaster;

    /** 購読の準備が整った通知。在室者を接続中にして、最初のビューを送る。 */
    @MessageMapping("/manual/{roomId}/ready")
    public void ready(@DestinationVariable String roomId, OccupantRequest request) {
        execute(roomId, request.occupantId(), room -> {
            ManualOccupant occupant = room.requireOccupant(request.occupantId());
            if (!occupant.isConnected()) {
                room.addLog("%s が入室した".formatted(occupant.getDisplayName()));
            }
            occupant.setConnected(true);
            occupant.setDisconnectedAt(null);
        });
    }

    /** 盤面の再送要求。状態は変えない(リロード直後や取りこぼしの復旧に使う)。 */
    @MessageMapping("/manual/{roomId}/resync")
    public void resync(@DestinationVariable String roomId, OccupantRequest request) {
        execute(roomId, request.occupantId(), room -> room.requireOccupant(request.occupantId()));
    }

    // ---- 共通処理 ----

    private void execute(String roomId, String occupantId, ManualRoomAction action) {
        ManualRoom room = roomManager.findRoom(roomId).orElse(null);
        if (room == null) {
            broadcaster.sendError(roomId, occupantId, "部屋が見つかりません: " + roomId);
            return;
        }
        try {
            synchronized (room.getLock()) {
                action.apply(room);
            }
            broadcaster.broadcast(room);
        } catch (IllegalStateException | IllegalArgumentException e) {
            // 状態は変更されていないので、操作者にだけ理由を返す
            broadcaster.sendError(roomId, occupantId, e.getMessage());
        }
    }

    @FunctionalInterface
    private interface ManualRoomAction {
        void apply(ManualRoom room);
    }

    // ---- クライアントから受け取るメッセージの型 ----

    /** すべての操作リクエストの土台。誰が送ったかを示す */
    public record OccupantRequest(String occupantId) {
    }
}
