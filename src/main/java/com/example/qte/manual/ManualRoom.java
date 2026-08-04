package com.example.qte.manual;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.Getter;
import lombok.Setter;

/**
 * 手動モードの部屋。盤面・ログ・履歴・在室者を持つ。
 *
 * <h2>lock の使い方は通常モードと同じ(設計書 2-1)</h2>
 * WebSocket では複数の在室者の操作が別スレッドでほぼ同時に届きうる。
 * 状態変更は必ず {@code synchronized (room.getLock())} の中で行い、
 * 「1部屋につき同時に1操作」を保証する。部屋ごとに別ロックなので他の部屋を待たせない。
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
 */
@Getter
public class ManualRoom {

    private final String roomId;

    private final Instant createdAt = Instant.now();

    private final Object lock = new Object();

    private final List<ManualOccupant> occupants = new CopyOnWriteArrayList<>();

    /** ★古い行を捨てない(設計書 5-5)。ログはこのモードの成果物である */
    private final List<ManualLogEntry> log = new CopyOnWriteArrayList<>();

    private final ManualHistory history = new ManualHistory();

    private final AtomicInteger logSequence = new AtomicInteger();

    @Setter
    private ManualGameState gameState;

    public ManualRoom(String roomId) {
        this.roomId = roomId;
        this.gameState = new ManualGameState(roomId);
    }

    /** 在室者を1人追加し、発行した occupantId を持つ在室者を返す。 */
    public ManualOccupant join(String displayName, ManualOccupantRole role) {
        String name = displayName == null || displayName.isBlank() ? "プレイヤー" : displayName.trim();
        ManualOccupant occupant = new ManualOccupant(name, role);
        occupants.add(occupant);
        return occupant;
    }

    public Optional<ManualOccupant> findOccupant(String occupantId) {
        if (occupantId == null) {
            return Optional.empty();
        }
        return occupants.stream().filter(o -> o.getOccupantId().equals(occupantId)).findFirst();
    }

    /** 在室者を引く。いなければ入室していない旨の例外を投げる。 */
    public ManualOccupant requireOccupant(String occupantId) {
        return findOccupant(occupantId)
                .orElseThrow(() -> new IllegalArgumentException("この部屋に入室していません"));
    }

    /** 明示的な退室。切断とは区別する(設計書 6-3)。 */
    public void leave(String occupantId) {
        findOccupant(occupantId).ifPresent(occupants::remove);
    }

    public ManualLogEntry addLog(String text) {
        ManualLogEntry entry = new ManualLogEntry(logSequence.incrementAndGet(), Instant.now(), text);
        log.add(entry);
        return entry;
    }
}
