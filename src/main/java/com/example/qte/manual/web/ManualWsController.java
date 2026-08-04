package com.example.qte.manual.web;

import java.util.function.Consumer;
import java.util.function.Function;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import com.example.qte.manual.ManualGameService;
import com.example.qte.manual.ManualGameState;
import com.example.qte.manual.ManualOccupant;
import com.example.qte.manual.ManualOpRequest;
import com.example.qte.manual.ManualOperationService;
import com.example.qte.manual.ManualRoom;
import com.example.qte.manual.ManualRoomManager;

import lombok.RequiredArgsConstructor;

/**
 * 手動モードの WebSocket 入口。宛先は {@code /app/manual/{roomId}/{action}}(設計書 2-4)。
 *
 * ★{@code WebSocketConfig} は1行も変更していない。{@code /app} と {@code /topic} の規約は
 * 通常モードと共有し、前置詞({@code room} と {@code manual})だけで系統を分ける。
 *
 * <h2>★Batch 19a で {@code ManualOpsWsController} を統合した</h2>
 * 18a は「既存ファイルを1行も変更しない」制約の下にあり、{@code ready}/{@code resync} と
 * 操作13項目を別クラスへ分けて {@code @MessageMapping} の宛先だけを衝突させない形にしていた
 * (18a design-notes 参照)。19a は既存ファイルの変更が前提のバッチであるため、
 * 二重になっていた {@code dispatch} の型を1本にまとめた。
 *
 * すべてのハンドラは共通の型で処理する。
 * <ol>
 *   <li>部屋を特定する</li>
 *   <li>{@code synchronized (room.getLock())} で「1部屋1操作」に直列化する</li>
 *   <li>状態を変更する</li>
 *   <li>成功: 在室者全員へビューを配信 / 失敗: 操作者にだけエラーを返す</li>
 * </ol>
 *
 * <h2>occupantId をここで発行しない理由</h2>
 * 配信先が {@code /topic/manual/{roomId}/view/{occupantId}} である以上、
 * occupantId を知る前のクライアントには受信できる宛先が存在しない。
 * したがって入室は HTTP({@link ManualLobbyController})で行い、
 * 受け取った occupantId で購読してから ready を送る。
 *
 * <h2>操作は3つの型のどれかに落ちる(Batch 18a〜)</h2>
 * <ol>
 *   <li>{@link #mutate} — 盤面を変える。★履歴に積む({@link ManualOperationService#apply})</li>
 *   <li>{@link #direct} — 盤面に触らない(メモ・宣言)、または履歴そのものを動かす(Undo/Redo)。
 *       ★履歴に積まない</li>
 *   <li>{@link #execute} — 在室・ログ・部屋そのものを扱う操作(入退室・リセット等)。
 *       {@link ManualOperationService} を経由しないため、履歴の扱いは各操作の中身に委ねる</li>
 * </ol>
 *
 * <h2>★席の権限は検査しない(設計書 6-1)</h2>
 * 一人回しでは1人が両方の席を動かす。在室者であることだけを確かめる。
 */
@Controller
@RequiredArgsConstructor
public class ManualWsController {

    private final ManualRoomManager roomManager;

    private final ManualBroadcaster broadcaster;

    private final ManualOperationService operations;

    private final ManualGameService gameService;

    // ---- 在室(設計書 6-3) ----

    /**
     * 購読の準備が整った通知。在室者を接続中にして、最初のビューを送る。
     * ★Batch 19a で WebSocket セッションIDを occupant に記録する。切断検知
     * ({@code SessionDisconnectEvent})が、どの在室者が切断したかを引くための鍵になる。
     */
    @MessageMapping("/manual/{roomId}/ready")
    public void ready(@DestinationVariable String roomId, OccupantRequest request,
            @Header("simpSessionId") String sessionId) {
        execute(roomId, request.occupantId(), room -> {
            ManualOccupant occupant = room.requireOccupant(request.occupantId());
            if (!occupant.isConnected()) {
                room.addLog("%s が入室した".formatted(occupant.getDisplayName()));
            }
            occupant.setConnected(true);
            occupant.setDisconnectedAt(null);
            occupant.setSessionId(sessionId);
        });
    }

    /** 盤面の再送要求。状態は変えない(リロード直後や取りこぼしの復旧に使う)。 */
    @MessageMapping("/manual/{roomId}/resync")
    public void resync(@DestinationVariable String roomId, OccupantRequest request) {
        execute(roomId, request.occupantId(), room -> room.requireOccupant(request.occupantId()));
    }

    /**
     * 明示的な退室(設計書 6-3)。切断(セッション切れ)とは区別し、即座に席を空ける。
     * 空けた後は在室者リストから消えるため、以降その occupantId 宛の配信は届かなくなる。
     */
    @MessageMapping("/manual/{roomId}/leave")
    public void leave(@DestinationVariable String roomId, OccupantRequest request) {
        execute(roomId, request.occupantId(), room -> {
            ManualOccupant occupant = room.requireOccupant(request.occupantId());
            room.addLog("%s が退室した".formatted(occupant.getDisplayName()));
            room.leave(request.occupantId());
        });
    }

    /**
     * リセットして引き直す(設計書 7-1・5-6)。
     * ★{@link ManualGameService#resetRoom} が履歴のクリアとログの追記まで行うため、
     * ここでは在室確認だけ行って処理を委ねる({@link #execute} 系。{@link #mutate} は使わない)。
     */
    @MessageMapping("/manual/{roomId}/reset")
    public void reset(@DestinationVariable String roomId, OccupantRequest request) {
        execute(roomId, request.occupantId(), room -> {
            room.requireOccupant(request.occupantId());
            gameService.resetRoom(room);
        });
    }

    // ---- 1. ゾーン間移動 / 9. 進化スタック ----

    @MessageMapping("/manual/{roomId}/move")
    public void move(@DestinationVariable String roomId, ManualOpRequest.Move request) {
        mutate(roomId, request.occupantId(), state -> operations.move(state, request));
    }

    @MessageMapping("/manual/{roomId}/evolve")
    public void evolve(@DestinationVariable String roomId, ManualOpRequest.Evolve request) {
        mutate(roomId, request.occupantId(), state -> operations.evolve(state, request));
    }

    // ---- 2. LP / 3・4. ATK・HP ----

    @MessageMapping("/manual/{roomId}/lp")
    public void lp(@DestinationVariable String roomId, ManualOpRequest.Lp request) {
        mutate(roomId, request.occupantId(), state -> operations.changeLp(state, request));
    }

    @MessageMapping("/manual/{roomId}/stat")
    public void stat(@DestinationVariable String roomId, ManualOpRequest.Stat request) {
        mutate(roomId, request.occupantId(), state -> operations.changeStats(state, request));
    }

    @MessageMapping("/manual/{roomId}/stat-reset")
    public void statReset(@DestinationVariable String roomId, ManualOpRequest.Target request) {
        mutate(roomId, request.occupantId(), state -> operations.resetStats(state, request));
    }

    // ---- 5. 札 ----

    @MessageMapping("/manual/{roomId}/label-add")
    public void labelAdd(@DestinationVariable String roomId, ManualOpRequest.Label request) {
        mutate(roomId, request.occupantId(), state -> operations.addLabel(state, request));
    }

    @MessageMapping("/manual/{roomId}/label-remove")
    public void labelRemove(@DestinationVariable String roomId, ManualOpRequest.Label request) {
        mutate(roomId, request.occupantId(), state -> operations.removeLabel(state, request));
    }

    // ---- 6・7・8. タップ / 表裏 / 使用済み ----

    @MessageMapping("/manual/{roomId}/tap")
    public void tap(@DestinationVariable String roomId, ManualOpRequest.Flag request) {
        mutate(roomId, request.occupantId(), state -> operations.tap(state, request));
    }

    @MessageMapping("/manual/{roomId}/flip")
    public void flip(@DestinationVariable String roomId, ManualOpRequest.Flag request) {
        mutate(roomId, request.occupantId(), state -> operations.flip(state, request));
    }

    @MessageMapping("/manual/{roomId}/used")
    public void used(@DestinationVariable String roomId, ManualOpRequest.Flag request) {
        mutate(roomId, request.occupantId(), state -> operations.markUsed(state, request));
    }

    // ---- 10. ターン / フェイズ ----

    @MessageMapping("/manual/{roomId}/turn")
    public void turn(@DestinationVariable String roomId, ManualOpRequest.Turn request) {
        mutate(roomId, request.occupantId(), state -> operations.setTurn(state, request));
    }

    @MessageMapping("/manual/{roomId}/phase")
    public void phase(@DestinationVariable String roomId, ManualOpRequest.Phase request) {
        mutate(roomId, request.occupantId(), state -> operations.setPhase(state, request));
    }

    // ---- 11. ドロー / シャッフル ----

    @MessageMapping("/manual/{roomId}/draw")
    public void draw(@DestinationVariable String roomId, ManualOpRequest.Draw request) {
        mutate(roomId, request.occupantId(), state -> operations.draw(state, request));
    }

    @MessageMapping("/manual/{roomId}/shuffle")
    public void shuffle(@DestinationVariable String roomId, ManualOpRequest.Seat request) {
        mutate(roomId, request.occupantId(), state -> operations.shuffleDeck(state, request));
    }

    // ---- 12・13. 宣言 / メモ(★盤面に触らないので履歴に積まない) ----

    @MessageMapping("/manual/{roomId}/declare")
    public void declare(@DestinationVariable String roomId, ManualOpRequest.Declare request) {
        direct(roomId, request.occupantId(), room -> operations.declare(request));
    }

    @MessageMapping("/manual/{roomId}/note")
    public void note(@DestinationVariable String roomId, ManualOpRequest.Note request) {
        direct(roomId, request.occupantId(), room -> operations.note(request));
    }

    // ---- Undo / Redo(★履歴そのものを動かすので積まない) ----

    @MessageMapping("/manual/{roomId}/undo")
    public void undo(@DestinationVariable String roomId, OccupantRequest request) {
        direct(roomId, request.occupantId(), operations::undo);
    }

    @MessageMapping("/manual/{roomId}/redo")
    public void redo(@DestinationVariable String roomId, OccupantRequest request) {
        direct(roomId, request.occupantId(), operations::redo);
    }

    // ---- 共通処理 ----

    /** 盤面を変える操作。★履歴への push は {@code ManualOperationService.apply} が行う。 */
    private void mutate(String roomId, String occupantId, Function<ManualGameState, String> mutation) {
        dispatch(roomId, occupantId, room -> operations.apply(room, mutation));
    }

    /** 盤面を変えない操作と、履歴そのものを動かす操作。★履歴に積まない。 */
    private void direct(String roomId, String occupantId, Function<ManualRoom, String> action) {
        dispatch(roomId, occupantId, room -> operations.applyDirect(room, action));
    }

    /** 在室確認だけ済ませ、あとは丸ごと渡した処理に委ねる(在室・退室・リセット等)。 */
    private void execute(String roomId, String occupantId, Consumer<ManualRoom> action) {
        dispatch(roomId, occupantId, action);
    }

    /**
     * 部屋を引き、1部屋1操作に直列化し、成功したら全在室者へ配信する。
     * 失敗したら操作者にだけ理由を返す(盤面は操作前へ差し戻されている)。
     */
    private void dispatch(String roomId, String occupantId, Consumer<ManualRoom> body) {
        ManualRoom room = roomManager.findRoom(roomId).orElse(null);
        if (room == null) {
            broadcaster.sendError(roomId, occupantId, "部屋が見つかりません: " + roomId);
            return;
        }
        try {
            synchronized (room.getLock()) {
                room.requireOccupant(occupantId);
                body.accept(room);
            }
            broadcaster.broadcast(room);
        } catch (IllegalStateException | IllegalArgumentException e) {
            broadcaster.sendError(roomId, occupantId, e.getMessage());
        }
    }

    // ---- クライアントから受け取るメッセージの型 ----

    /** すべての操作リクエストの土台。誰が送ったかを示す */
    public record OccupantRequest(String occupantId) {
    }
}
