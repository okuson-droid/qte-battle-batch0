package com.example.qte.manual.web;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import com.example.qte.manual.ManualActor;
import com.example.qte.manual.ManualCardRef;
import com.example.qte.manual.ManualBoardIndex;
import com.example.qte.manual.ManualDragCue;
import com.example.qte.manual.ManualGameService;
import com.example.qte.manual.ManualGameState;
import com.example.qte.manual.ManualLogEvent;
import com.example.qte.manual.ManualLogPlace;
import com.example.qte.manual.ManualOccupant;
import com.example.qte.manual.ManualOpRequest;
import com.example.qte.manual.ManualOperationService;
import com.example.qte.manual.ManualPermissions;
import com.example.qte.manual.ManualRoom;
import com.example.qte.manual.ManualRoomManager;
import com.example.qte.manual.ManualSeatId;
import com.example.qte.manual.ManualSpectatorView;
import com.example.qte.manual.ManualStartService;

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
 *   <li>操作者({@link ManualActor})を解決する</li>
 *   <li>状態を変更する</li>
 *   <li>成功: 在室者全員へビューを配信 / 失敗: 操作者にだけ理由を返す</li>
 * </ol>
 *
 * <h2>★Batch 21a: 席の権限を検査する(設計書 6章)</h2>
 * 19a までの「在室者であることだけを確かめる」を改め、操作者の席を解決して
 * {@link ManualPermissions} に渡す。<b>ただし判定そのものはここに書かない。</b>
 * 権限は操作の中身と対で決まる(このカードは自席のものか、この席は自分か)ため、
 * 各操作メソッドの中で判定を呼ぶ。入口が判定を持つと、
 * 操作を1つ足すたびに入口の switch に条件を書き足すことになる。
 *
 * ★違反は例外として上がり、{@link #dispatch} が捕まえて<b>操作者にだけ</b>返す。
 * 盤面は配信されないため、他の在室者からは何も起きなかったように見える(6-1・6-4)。
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
 *   <li>{@link #execute} — 在室・ログ・部屋そのものを扱う操作(入退室・席・リセット等)</li>
 * </ol>
 */
@Controller
@RequiredArgsConstructor
public class ManualWsController {

    private final ManualRoomManager roomManager;

    private final ManualBroadcaster broadcaster;

    private final ManualOperationService operations;

    private final ManualGameService gameService;

    /** ★Batch 23: 開始シーケンス(総合ルール 2-5)。盤面操作とは別の経路である */
    private final ManualStartService startService;

    // ---- 在室(設計書 6-3) ----

    /**
     * 購読の準備が整った通知。在室者を接続中にして、最初のビューを送る。
     * ★Batch 19a で WebSocket セッションIDを occupant に記録する。切断検知
     * ({@code SessionDisconnectEvent})が、どの在室者が切断したかを引くための鍵になる。
     */
    @MessageMapping("/manual/{roomId}/ready")
    public void ready(@DestinationVariable String roomId, OccupantRequest request,
            @Header("simpSessionId") String sessionId) {
        execute(roomId, request.occupantId(), (room, actor) -> {
            ManualOccupant occupant = room.requireOccupant(request.occupantId());
            if (!occupant.isConnected()) {
                room.addLog("%s が入室した%s".formatted(occupant.getDisplayName(),
                        occupant.isSeated() ? "(席%s)".formatted(occupant.getSeatId()) : "(観戦)"));
            }
            occupant.setConnected(true);
            occupant.setDisconnectedAt(null);
            occupant.setSessionId(sessionId);
        });
    }

    /** 盤面の再送要求。状態は変えない(リロード直後や取りこぼしの復旧に使う)。 */
    @MessageMapping("/manual/{roomId}/resync")
    public void resync(@DestinationVariable String roomId, OccupantRequest request) {
        execute(roomId, request.occupantId(),
                (room, actor) -> room.requireOccupant(request.occupantId()));
    }

    /**
     * 明示的な退室(設計書 6-3)。切断(セッション切れ)とは区別し、即座に席を空ける。
     * 空けた後は在室者リストから消えるため、以降その occupantId 宛の配信は届かなくなる。
     */
    @MessageMapping("/manual/{roomId}/leave")
    public void leave(@DestinationVariable String roomId, OccupantRequest request) {
        execute(roomId, request.occupantId(), (room, actor) -> {
            ManualOccupant occupant = room.requireOccupant(request.occupantId());
            room.addLog("%s が退室した".formatted(occupant.getDisplayName()));
            room.leave(request.occupantId());
        });
    }

    /**
     * 席に着く / 席を立つ(Batch 21a 設計書 2-2)。
     *
     * <ul>
     *   <li>{@code seat} 指定あり — その席に着く(観戦者からの昇格を含む)</li>
     *   <li>{@code seat} が null — 席を立って観戦者に降りる。
     *       ★観戦を許可しない部屋では降りる先が無いため、退室として扱う(2-2)</li>
     * </ul>
     *
     * ★着席・離席の瞬間に、その人に見えるものが丸ごと変わる。
     * {@link #dispatch} が最後に全員へ配信するため、ビューもログ全文も
     * 新しい視点でレンダリングし直されて届く(5-5)。再送のための特別な経路は要らない。
     */
    @MessageMapping("/manual/{roomId}/seat")
    public void seat(@DestinationVariable String roomId, SeatRequest request) {
        execute(roomId, request.occupantId(), (room, actor) -> {
            ManualOccupant occupant = room.requireOccupant(request.occupantId());
            if (request.seat() == null) {
                if (!occupant.isSeated()) {
                    throw new IllegalArgumentException("すでに観戦しています");
                }
                ManualSeatId before = occupant.getSeatId();
                room.standUp(occupant);
                if (!room.getOptions().spectatorAllowed()) {
                    // ★観戦できない部屋では「席を立つ = 退室」である(2-2)
                    room.addLog("%s が席%s を離れて退室した".formatted(occupant.getDisplayName(), before));
                    room.leave(occupant.getOccupantId());
                    return;
                }
                room.addLog("%s が席%s を離れて観戦に移った".formatted(occupant.getDisplayName(), before));
                return;
            }
            room.takeSeat(occupant, request.seat());
            room.addLog("%s が席%s に着いた".formatted(occupant.getDisplayName(), request.seat()));
        });
    }

    /**
     * 観戦者の視点切替(設計書 3-2)。
     *
     * ★サーバへ送る。「全部送っておいてクライアントで隠す」形にすると、
     * 公開のみ視点の観戦者のブラウザに相手の手札が届いてしまい、
     * 3-3 の「カードオブジェクトを一切載せない」が意味を失う。
     * ★上下反転(どちらの席を下に置くか)はサーバへ送らない。あれは描画だけの話であり、
     * サーバのビューは常に席A/Bのまま送る(3-1・10章)。
     */
    @MessageMapping("/manual/{roomId}/viewpoint")
    public void viewpoint(@DestinationVariable String roomId, ViewpointRequest request) {
        execute(roomId, request.occupantId(), (room, actor) -> {
            ManualOccupant occupant = room.requireOccupant(request.occupantId());
            if (occupant.isSeated()) {
                throw new IllegalArgumentException("視点を切り替えられるのは観戦者だけです");
            }
            if (request.spectatorView() == null) {
                throw new IllegalArgumentException("視点が指定されていません");
            }
            occupant.setSpectatorView(request.spectatorView());
            room.addLog("%s が観戦の視点を「%s」に変えた".formatted(
                    occupant.getDisplayName(), request.spectatorView().getDisplayName()));
        });
    }

    /**
     * リセットして引き直す(設計書 7-1・5-6)。
     * ★対戦部屋ではどちらの席でも押せる(21 6-3)。確認ダイアログは画面側の責務である。
     *
     * ★★Batch 23 7-2: <b>開始シーケンス中でもリセットだけは通す。</b>
     * {@code denyDuringStart} を通る {@code mutate} / Undo とは違い、この経路は
     * {@code execute} であり棄却されない。開始シーケンスが何かの理由で詰まったときの
     * 逃げ道であり、<b>止まったまま抜けられない画面を作らない</b>ためである。
     * {@code ManualGameService.resetRoom} がフェーズも {@code IDLE} へ戻す。
     */
    @MessageMapping("/manual/{roomId}/reset")
    public void reset(@DestinationVariable String roomId, OccupantRequest request) {
        execute(roomId, request.occupantId(), (room, actor) -> {
            ManualPermissions.require(ManualPermissions.denyOperate(actor));
            gameService.resetRoom(room);
        });
    }

    // ---- 1. ゾーン間移動 / 9. 進化スタック ----

    @MessageMapping("/manual/{roomId}/move")
    public void move(@DestinationVariable String roomId, ManualOpRequest.Move request) {
        mutate(roomId, request.occupantId(), (state, actor) -> operations.move(state, actor, request));
    }

    @MessageMapping("/manual/{roomId}/evolve")
    public void evolve(@DestinationVariable String roomId, ManualOpRequest.Evolve request) {
        mutate(roomId, request.occupantId(),
                (state, actor) -> operations.evolve(state, actor, request));
    }

    // ---- 2. LP / 3・4. ATK・HP ----

    @MessageMapping("/manual/{roomId}/lp")
    public void lp(@DestinationVariable String roomId, ManualOpRequest.Lp request) {
        mutate(roomId, request.occupantId(),
                (state, actor) -> operations.changeLp(state, actor, request));
    }

    @MessageMapping("/manual/{roomId}/stat")
    public void stat(@DestinationVariable String roomId, ManualOpRequest.Stat request) {
        mutate(roomId, request.occupantId(),
                (state, actor) -> operations.changeStats(state, actor, request));
    }

    @MessageMapping("/manual/{roomId}/stat-reset")
    public void statReset(@DestinationVariable String roomId, ManualOpRequest.Target request) {
        mutate(roomId, request.occupantId(),
                (state, actor) -> operations.resetStats(state, actor, request));
    }

    // ---- 5. 札 ----

    @MessageMapping("/manual/{roomId}/label-add")
    public void labelAdd(@DestinationVariable String roomId, ManualOpRequest.Label request) {
        mutate(roomId, request.occupantId(),
                (state, actor) -> operations.addLabel(state, actor, request));
    }

    @MessageMapping("/manual/{roomId}/label-remove")
    public void labelRemove(@DestinationVariable String roomId, ManualOpRequest.Label request) {
        mutate(roomId, request.occupantId(),
                (state, actor) -> operations.removeLabel(state, actor, request));
    }

    // ---- 6・7・8. タップ / 表裏 / 使用済み ----

    @MessageMapping("/manual/{roomId}/tap")
    public void tap(@DestinationVariable String roomId, ManualOpRequest.Flag request) {
        mutate(roomId, request.occupantId(), (state, actor) -> operations.tap(state, actor, request));
    }

    @MessageMapping("/manual/{roomId}/flip")
    public void flip(@DestinationVariable String roomId, ManualOpRequest.Flag request) {
        mutate(roomId, request.occupantId(), (state, actor) -> operations.flip(state, actor, request));
    }

    @MessageMapping("/manual/{roomId}/used")
    public void used(@DestinationVariable String roomId, ManualOpRequest.Flag request) {
        mutate(roomId, request.occupantId(),
                (state, actor) -> operations.markUsed(state, actor, request));
    }

    // ---- 10. ターン / フェイズ ----

    @MessageMapping("/manual/{roomId}/turn")
    public void turn(@DestinationVariable String roomId, ManualOpRequest.Turn request) {
        mutate(roomId, request.occupantId(),
                (state, actor) -> operations.setTurn(state, actor, request));
    }

    @MessageMapping("/manual/{roomId}/phase")
    public void phase(@DestinationVariable String roomId, ManualOpRequest.Phase request) {
        mutate(roomId, request.occupantId(),
                (state, actor) -> operations.setPhase(state, actor, request));
    }

    // ---- 11. ドロー / シャッフル ----

    @MessageMapping("/manual/{roomId}/draw")
    public void draw(@DestinationVariable String roomId, ManualOpRequest.Draw request) {
        mutate(roomId, request.occupantId(), (state, actor) -> operations.draw(state, actor, request));
    }

    @MessageMapping("/manual/{roomId}/shuffle")
    public void shuffle(@DestinationVariable String roomId, ManualOpRequest.Seat request) {
        mutate(roomId, request.occupantId(),
                (state, actor) -> operations.shuffleDeck(state, actor, request));
    }

    // ---- 12・13. 宣言 / メモ(★盤面に触らないので履歴に積まない) ----

    @MessageMapping("/manual/{roomId}/declare")
    public void declare(@DestinationVariable String roomId, ManualOpRequest.Declare request) {
        direct(roomId, request.occupantId(), (room, actor) -> operations.declare(actor, request));
    }

    @MessageMapping("/manual/{roomId}/note")
    public void note(@DestinationVariable String roomId, ManualOpRequest.Note request) {
        direct(roomId, request.occupantId(), (room, actor) -> operations.note(actor, request));
    }

    // ---- ★Batch 23. ゲーム開始シーケンス(総合ルール 2-5 / 23 設計書2章) ----
    //
    // ★21c の /first-player は削除した(23 設計書 3-4)。先攻を決める経路は1本でなければ
    //   ならない。2つ残すと「ヘッダの先攻決めとモーダルの先攻決めのどちらが正か」が
    //   決まらなくなる。
    //
    // ★開始シーケンスは direct(履歴に積まない)を通す。開始処理は複数の状態変更を含み、
    //   途中まで戻せると意味のない中間状態が作れてしまう(2-5・P12)。
    //   完了時に ManualStartService が履歴をクリアする。戻したいならリセットする。

    /** 開始シーケンスを始める(2-3)。全公開部屋は [ゲームを始める]、対戦部屋は両者のデッキ読込が合図 */
    @MessageMapping("/manual/{roomId}/start-begin")
    public void startBegin(@DestinationVariable String roomId, OccupantRequest request) {
        direct(roomId, request.occupantId(), startService::begin);
    }

    /** 開始方法の3択(3-1)。★ソロでは DICE が「ランダムで先攻を決める」になる */
    @MessageMapping("/manual/{roomId}/start-method")
    public void startMethod(@DestinationVariable String roomId, ManualOpRequest.StartMethod request) {
        direct(roomId, request.occupantId(),
                (room, actor) -> startService.chooseMethod(room, actor, request.method()));
    }

    /** ダイスの勝者が先攻 / 後攻を選ぶ(3-3)。★押せるのは勝った席だけである */
    @MessageMapping("/manual/{roomId}/start-order")
    public void startOrder(@DestinationVariable String roomId, ManualOpRequest.StartOrder request) {
        direct(roomId, request.occupantId(), (room, actor) -> startService.chooseOrder(room, actor,
                Boolean.TRUE.equals(request.takeFirst())));
    }

    /**
     * マリガン(4-2・4-4)。★<b>サーバが「戻す → シャッフル → 同数ドロー」を1操作で行う。</b>
     * クライアントが {@code move} を並べて送る形にしてはならない(設計判断27)。
     */
    @MessageMapping("/manual/{roomId}/mulligan")
    public void mulligan(@DestinationVariable String roomId, ManualOpRequest.Mulligan request) {
        direct(roomId, request.occupantId(), (room, actor) ->
                startService.mulligan(room, actor, request.seat(), request.cardIds()));
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

    // ---- 7章. ドラッグ軌跡の矢印(★揮発。ログ・履歴・Undo に一切残さない) ----

    /**
     * ドラッグ中の矢印を中継する(設計書 7章)。
     *
     * ★{@link #dispatch} を<b>通さない</b>。dispatch は最後に全在室者へ盤面を配信するが、
     * 矢印はドラッグ中に何度も飛ぶ(100msスロットル)ため、そのたびに盤面を配ると
     * 通信量が跳ね上がる。加えて履歴もログも触らないので、
     * dispatch が引き受けている「1操作の型」に載せる理由が無い。
     *
     * ★起点のゾーンはクライアントの申告ではなく instanceId から引き直す(設計判断27)。
     * 盤面に無いカードなら黙って捨てる。矢印が1本描かれないだけであり、
     * 揮発メッセージにエラーを返しても操作者を邪魔するだけである。
     */
    @MessageMapping("/manual/{roomId}/dragcue")
    public void dragCue(@DestinationVariable String roomId, ManualDragCue.Request request) {
        ManualRoom room = roomManager.findRoom(roomId).orElse(null);
        if (room == null) {
            return;
        }
        ManualOccupant actor;
        ManualLogPlace from = null;
        String cardId = null;
        synchronized (room.getLock()) {
            actor = room.findOccupant(request.occupantId()).orElse(null);
            if (actor == null || !actor.isSeated()) {
                // ★観戦者は操作できない(6-1)ので、矢印を出す主体にもならない
                return;
            }
            if (request.active() && request.cardId() != null) {
                Optional<ManualCardRef> ref =
                        ManualBoardIndex.find(room.getGameState(), request.cardId());
                if (ref.isEmpty()) {
                    return;
                }
                from = ManualLogPlace.of(ref.get());
                cardId = request.cardId();
            }
        }
        ManualLogPlace origin = from;
        String originCardId = cardId;
        ManualLogPlace to = request.toZone() == null
                ? null
                : ManualLogPlace.of(request.toSeat(), request.toZone());
        ManualOccupant sender = actor;
        broadcaster.sendDragCue(room, sender, viewpoint -> new ManualDragCue.View(
                sender.getSeatId(),
                sender.getDisplayName(),
                origin,
                // ★見えないカードの識別子は載せない(7-3)。根はゾーンだけになる
                origin != null && viewpoint.canSeeZone(origin.seatId(), origin.zone())
                        ? originCardId
                        : null,
                to,
                request.active()));
    }

    // ---- 共通処理 ----

    /** 盤面を変える操作。★履歴への push は {@code ManualOperationService.apply} が行う。 */
    private void mutate(String roomId, String occupantId,
            BiFunction<ManualGameState, ManualActor, ManualLogEvent> mutation) {
        dispatch(roomId, occupantId,
                (room, actor) -> operations.apply(room, actor, state -> mutation.apply(state, actor)));
    }

    /** 盤面を変えない操作と、履歴そのものを動かす操作。★履歴に積まない。 */
    private void direct(String roomId, String occupantId,
            BiFunction<ManualRoom, ManualActor, ManualLogEvent> action) {
        dispatch(roomId, occupantId,
                (room, actor) -> operations.applyDirect(room, r -> action.apply(r, actor)));
    }

    /** 在室確認だけ済ませ、あとは丸ごと渡した処理に委ねる(在室・退室・席・リセット等)。 */
    private void execute(String roomId, String occupantId, BiConsumer<ManualRoom, ManualActor> action) {
        dispatch(roomId, occupantId, action);
    }

    /**
     * 部屋を引き、1部屋1操作に直列化し、成功したら全在室者へ配信する。
     * 失敗したら操作者にだけ理由を返す(盤面は操作前へ差し戻されている)。
     *
     * ★権限違反(6-1)も競合による棄却(6-4)も、この1本の経路で
     * 「盤面を配らず、操作者にだけ通知する」に落ちる。専用の仕組みを足していない。
     */
    private void dispatch(String roomId, String occupantId, BiConsumer<ManualRoom, ManualActor> body) {
        ManualRoom room = roomManager.findRoom(roomId).orElse(null);
        if (room == null) {
            broadcaster.sendError(roomId, occupantId, "部屋が見つかりません: " + roomId);
            return;
        }
        try {
            synchronized (room.getLock()) {
                ManualOccupant occupant = room.requireOccupant(occupantId);
                body.accept(room, ManualActor.of(room, occupant));
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

    /** 席に着く / 立つ(2-2)。{@code seat} が null なら席を立つ */
    public record SeatRequest(String occupantId, ManualSeatId seat) {
    }

    /** 観戦者の視点切替(3-2) */
    public record ViewpointRequest(String occupantId, ManualSpectatorView spectatorView) {
    }
}
