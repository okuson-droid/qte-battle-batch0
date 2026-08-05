package com.example.qte.manual;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.Getter;
import lombok.Setter;

/**
 * 手動モードの部屋。盤面・ログ・履歴・在室者・部屋の属性を持つ。
 *
 * <h2>lock の使い方は通常モードと同じ(設計書 2-1)</h2>
 * WebSocket では複数の在室者の操作が別スレッドでほぼ同時に届きうる。
 * 状態変更は必ず {@code synchronized (room.getLock())} の中で行い、
 * 「1部屋につき同時に1操作」を保証する。部屋ごとに別ロックなので他の部屋を待たせない。
 * ★Batch 21 の同時操作(6-4)も新しい仕組みを足さない。楽観方式であり、
 * 後着の操作で対象カードが既に無ければ、既存の「盤面に無いカードです」で自然に弾かれる。
 *
 * <h2>★ログと履歴は盤面の外にある</h2>
 * {@link ManualGameState} はスナップショットとして丸ごと複製されるため、
 * 巻き戻ってはならないもの(ログ)と、巻き戻す仕組みそのもの(履歴)は、この階層に置く。
 *
 * <h2>在室者リストが CopyOnWriteArrayList である理由</h2>
 * 配信({@code ManualBroadcaster})はロックの外で在室者を走査する。
 * 通常の ArrayList だと、走査中に誰かが入室した瞬間に
 * ConcurrentModificationException で配信が落ちる。
 * 在室者は多くて数人であり、入退室より配信のほうが桁違いに多いので、
 * 書き込み時コピーの代償は事実上ゼロである。
 *
 * <h2>★Batch 21a: 部屋の属性と席</h2>
 * 部屋の種類・観戦可否・鍵は {@link ManualRoomOptions} が持ち、<b>作成後は変わらない</b>。
 * 席の割り当ては在室者({@link ManualOccupant#getSeatId()})が持ち、部屋は
 * 「二重着席させない」検査だけを引き受ける。席を部屋側の配列で持たなかったのは、
 * 在室者と席の対応を2箇所に置くと、切断・退室・着席のたびに同期が要るためである。
 */
@Getter
public class ManualRoom {

    private final String roomId;

    private final ManualRoomOptions options;

    private final Instant createdAt = Instant.now();

    private final Object lock = new Object();

    private final List<ManualOccupant> occupants = new CopyOnWriteArrayList<>();

    /** ★古い行を捨てない(設計書 5-5)。ログはこのモードの成果物である */
    private final List<ManualLogEntry> log = new CopyOnWriteArrayList<>();

    private final ManualHistory history;

    private final AtomicInteger logSequence = new AtomicInteger();

    /**
     * 在室者が0人になった時刻(Batch 21a 1-4)。1人でも居れば null。
     * ★{@link com.example.qte.manual.web.ManualCleanupScheduler} が
     * ここを見て無人部屋を片付ける。19a 以来「呼び出し元の無い
     * {@link ManualRoomManager#removeRoom}」だった積み残しの解消である。
     */
    @Setter
    private Instant emptySince = Instant.now();

    @Setter
    private ManualGameState gameState;

    /** 従来どおりの全公開部屋。★既存のテストと 20c までの入口はこれを使う。 */
    public ManualRoom(String roomId) {
        this(roomId, ManualRoomOptions.openDefault());
    }

    public ManualRoom(String roomId, ManualRoomOptions options) {
        this.roomId = roomId;
        this.options = options == null ? ManualRoomOptions.openDefault() : options;
        this.gameState = new ManualGameState(roomId);
        this.history = ManualHistory.forRoom(this.options.type());
    }

    /** 部屋の種類(1-1)。判定の入口はここ1つにする。 */
    public ManualRoomType getType() {
        return options.type();
    }

    // ================= 在室 =================

    /**
     * 在室者を1人追加し、発行した occupantId を持つ在室者を返す。
     *
     * @param seatId 座らせる席。★null なら観戦者として入室する。
     *               空いていない席を指定すると例外になる
     */
    public ManualOccupant join(String displayName, ManualSeatId seatId) {
        String name = displayName == null || displayName.isBlank() ? "プレイヤー" : displayName.trim();
        if (options.type().isRestricted() && (displayName == null || displayName.isBlank())) {
            throw new IllegalArgumentException("対戦部屋では名前が必要です");
        }
        if (seatId == null && !options.spectatorAllowed()) {
            throw new IllegalArgumentException("この部屋は観戦を許可していません");
        }
        ManualOccupant occupant = new ManualOccupant(name);
        if (seatId != null) {
            requireFreeSeat(seatId);
            occupant.setSeatId(seatId);
        }
        occupants.add(occupant);
        emptySince = null;
        return occupant;
    }

    /**
     * 席に着く / 席を移る(2-2)。
     *
     * ★A⇔Bの直接交換は作らない(設計書 2-2)。それでもこのメソッドが席の移動を
     * 受け付けられるのは、空席へ移るだけなら「立って座り直す」と結果が同じだからである。
     * 埋まっている席へは移れない。
     */
    public void takeSeat(ManualOccupant occupant, ManualSeatId seatId) {
        if (seatId == null) {
            throw new IllegalArgumentException("席が指定されていません");
        }
        if (occupant.getSeatId() == seatId) {
            return;
        }
        requireFreeSeat(seatId);
        occupant.setSeatId(seatId);
    }

    /**
     * 席を立つ(2-2)。観戦者に降りる。
     *
     * ★観戦を許可しない部屋では降りる先が無い。その場合は退室として扱うのが正しいが、
     * 「退室させる」判断は入口({@code ManualWsController})が行う。
     * ここは席を空けるだけに留める。
     */
    public void standUp(ManualOccupant occupant) {
        occupant.setSeatId(null);
    }

    /** 席の在席者。切断猶予中の在室者も席を保持している(2-4)ため、ここで見つかる。 */
    public Optional<ManualOccupant> occupantOfSeat(ManualSeatId seatId) {
        if (seatId == null) {
            return Optional.empty();
        }
        return occupants.stream().filter(o -> o.getSeatId() == seatId).findFirst();
    }

    /** 空いている席を1つ返す。両方埋まっていれば空。 */
    public Optional<ManualSeatId> firstFreeSeat() {
        for (ManualSeatId seatId : ManualSeatId.values()) {
            if (occupantOfSeat(seatId).isEmpty()) {
                return Optional.of(seatId);
            }
        }
        return Optional.empty();
    }

    /** 観戦者(席に着いていない在室者)の数。部屋一覧に出す(1-3)。 */
    public int spectatorCount() {
        return (int) occupants.stream().filter(o -> !o.isSeated()).count();
    }

    private void requireFreeSeat(ManualSeatId seatId) {
        Optional<ManualOccupant> current = occupantOfSeat(seatId);
        if (current.isPresent()) {
            // ★切断猶予中でも席は空かない(2-4)。他人が座れないことを明示的に伝える
            String suffix = current.get().isConnected() ? "" : "(切断中。5分の猶予があります)";
            throw new IllegalArgumentException(
                    "席%s は %s が使用中です%s".formatted(seatId, current.get().getDisplayName(), suffix));
        }
    }

    public Optional<ManualOccupant> findOccupant(String occupantId) {
        if (occupantId == null) {
            return Optional.empty();
        }
        return occupants.stream().filter(o -> o.getOccupantId().equals(occupantId)).findFirst();
    }

    /** WebSocketセッションIDから在室者を引く(Batch 19a。切断検知で使う)。 */
    public Optional<ManualOccupant> findOccupantBySession(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return occupants.stream().filter(o -> sessionId.equals(o.getSessionId())).findFirst();
    }

    /** 在室者を引く。いなければ入室していない旨の例外を投げる。 */
    public ManualOccupant requireOccupant(String occupantId) {
        return findOccupant(occupantId)
                .orElseThrow(() -> new IllegalArgumentException("この部屋に入室していません"));
    }

    /** 明示的な退室。切断とは区別する(設計書 6-3)。 */
    public void leave(String occupantId) {
        findOccupant(occupantId).ifPresent(occupants::remove);
        if (occupants.isEmpty() && emptySince == null) {
            emptySince = Instant.now();
        }
    }

    /** 無人になってから経過した時間(1-4)。1人でも居れば {@link Duration#ZERO}。 */
    public Duration emptyFor(Instant now) {
        if (!occupants.isEmpty() || emptySince == null) {
            return Duration.ZERO;
        }
        return Duration.between(emptySince, now);
    }

    // ================= ログ =================

    /**
     * 構造化イベントを1行として記録する(21 5-1)。
     * ★通し番号と時刻を採番するのは部屋だけである。
     */
    public ManualLogEntry addLog(ManualLogEvent event) {
        ManualLogEntry entry =
                new ManualLogEntry(logSequence.incrementAndGet(), Instant.now(), event);
        log.add(entry);
        return entry;
    }

    /** 部屋そのものの出来事(入退室・警告など)。★全員に同じ本文が出る。 */
    public ManualLogEntry addLog(String text) {
        return addLog(ManualLogEvent.system(text));
    }
}
