package com.example.qte.room;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.example.qte.game.GameState;

import lombok.Getter;
import lombok.Setter;

/**
 * 対戦部屋。部屋IDを知っている2人が同じGameStateを共有する。
 *
 * lockオブジェクトについて: WebSocketでは両プレイヤーの操作が別スレッドで
 * ほぼ同時に届きうる。1つの試合の状態変更は必ず synchronized (room.getLock())
 * の中で行い、「1部屋につき同時に1操作」を保証する。
 * 部屋ごとに別ロックなので、他の部屋の試合を待たせることはない。
 */
@Getter
public class GameRoom {

    private final String roomId;
    private final Instant createdAt = Instant.now();
    private final Object lock = new Object();

    /** 入室済みプレイヤー(最大2人)。試合開始前の受付情報 */
    private final List<PlayerSlot> slots = new ArrayList<>(2);

    /** ダイス勝者(先攻/後攻の選択権を持つプレイヤー)のplayerId */
    @Setter
    private String diceWinnerId;

    /** 対戦ログ(両者に公開される進行記録) */
    private final List<String> log = new ArrayList<>();

    @Setter
    private GameState gameState;

    public GameRoom(String roomId) {
        this.roomId = roomId;
    }

    public boolean isFull() {
        return slots.size() >= 2;
    }

    public void addSlot(PlayerSlot slot) {
        if (isFull()) {
            throw new IllegalStateException("この部屋は満室です");
        }
        slots.add(slot);
    }

    public Optional<PlayerSlot> findSlot(String playerId) {
        return slots.stream().filter(s -> s.getPlayerId().equals(playerId)).findFirst();
    }

    public boolean bothReady() {
        return slots.size() == 2 && slots.stream().allMatch(PlayerSlot::isReady);
    }

    public void addLog(String message) {
        log.add(message);
        if (log.size() > 60) {
            log.remove(0);
        }
    }
}
