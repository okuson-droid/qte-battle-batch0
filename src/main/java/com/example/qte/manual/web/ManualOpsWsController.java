package com.example.qte.manual.web;

import java.util.function.Consumer;
import java.util.function.Function;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import com.example.qte.manual.ManualGameState;
import com.example.qte.manual.ManualOpRequest;
import com.example.qte.manual.ManualOperationService;
import com.example.qte.manual.ManualRoom;
import com.example.qte.manual.ManualRoomManager;

import lombok.RequiredArgsConstructor;

/**
 * 手動モードの操作の入口(設計書 5-3 の13項目・4-5 の進化・5-6 の Undo/Redo)。
 * 宛先は {@code /app/manual/{roomId}/{action}}(設計書 2-4)。
 *
 * <h2>★なぜ {@link ManualWsController} に足さず、別のクラスにしたのか</h2>
 * Batch 18a は「既存ファイルを1行も変更しない」制約の下にある。
 * {@code @MessageMapping} は宛先が重ならない限り複数の {@code @Controller} に分かれていてよく、
 * {@code ready} / {@code resync} と本クラスの18本は宛先が1つも重ならない。
 * {@code WebSocketConfig} も無変更である。
 *
 * 代償として、部屋を引いてロックを取り配信するまでの型
 * ({@link #dispatch}) が {@code ManualWsController.execute} と二重になっている。
 * <b>既存ファイルの変更が許される Batch 19a で1本にまとめること</b>(積み残しに記載)。
 *
 * <h2>操作は3つの型のどれかに落ちる</h2>
 * <ol>
 *   <li>{@link #mutate} — 盤面を変える。★履歴に積む</li>
 *   <li>{@link #direct} — 盤面に触らない(メモ・宣言)、または履歴そのものを動かす(Undo/Redo)。
 *       ★履歴に積まない</li>
 * </ol>
 * どちらに落ちるかを1本のメソッド呼び出しで表しておくと、
 * 操作を足すときに「履歴に積むのを忘れる」余地が無くなる。
 *
 * <h2>★席の権限は検査しない(設計書 6-1)</h2>
 * 一人回しでは1人が両方の席を動かす。在室者であることだけを確かめる。
 */
@Controller
@RequiredArgsConstructor
public class ManualOpsWsController {

    private final ManualRoomManager roomManager;

    private final ManualBroadcaster broadcaster;

    private final ManualOperationService operations;

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
    public void undo(@DestinationVariable String roomId,
            ManualWsController.OccupantRequest request) {
        direct(roomId, request.occupantId(), operations::undo);
    }

    @MessageMapping("/manual/{roomId}/redo")
    public void redo(@DestinationVariable String roomId,
            ManualWsController.OccupantRequest request) {
        direct(roomId, request.occupantId(), operations::redo);
    }

    // ---- 共通処理 ----

    /** 盤面を変える操作。★履歴への push は {@code ManualOperationService.apply} が行う。 */
    private void mutate(String roomId, String occupantId,
            Function<ManualGameState, String> mutation) {
        dispatch(roomId, occupantId, room -> operations.apply(room, mutation));
    }

    /** 盤面を変えない操作と、履歴そのものを動かす操作。★履歴に積まない。 */
    private void direct(String roomId, String occupantId, Function<ManualRoom, String> action) {
        dispatch(roomId, occupantId, room -> operations.applyDirect(room, action));
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
}
