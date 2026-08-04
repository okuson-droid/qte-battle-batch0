package com.example.qte.manual.web;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.qte.manual.ManualOccupant;
import com.example.qte.manual.ManualRoom;
import com.example.qte.manual.ManualRoomManager;

import lombok.RequiredArgsConstructor;

/**
 * 切断中の在室者が5分を超えたら席を空ける(設計書 6-3)。
 *
 * ★猶予の起点は {@link ManualDisconnectListener} が記録する {@code disconnectedAt} である。
 * 明示的な退室({@code ManualWsController#leave})は即座に在室者リストから消えるため、
 * このスケジューラの対象にはならない(そもそも走査時に見つからない)。
 *
 * 1分ごとに全部屋を確認する。一人回しの想定規模(部屋数は多くても数十)では、
 * この頻度・走査コストのどちらも無視できる。
 */
@Component
@RequiredArgsConstructor
public class ManualCleanupScheduler {

    private static final Duration GRACE_PERIOD = Duration.ofMinutes(5);

    private final ManualRoomManager roomManager;

    private final ManualBroadcaster broadcaster;

    @Scheduled(fixedRate = 60_000)
    public void sweep() {
        Instant now = Instant.now();
        for (ManualRoom room : roomManager.allRooms()) {
            boolean changed = false;
            synchronized (room.getLock()) {
                // ★先に「空けるべき在室者」を集めてから leave する。走査中の occupants
                //   (CopyOnWriteArrayList)そのものを走査しながら書き換えることを避けるため。
                List<ManualOccupant> expired = new ArrayList<>();
                for (ManualOccupant occupant : room.getOccupants()) {
                    if (occupant.isConnected() || occupant.getDisconnectedAt() == null) {
                        continue;
                    }
                    if (Duration.between(occupant.getDisconnectedAt(), now).compareTo(GRACE_PERIOD) >= 0) {
                        expired.add(occupant);
                    }
                }
                for (ManualOccupant occupant : expired) {
                    room.addLog("%s が退室した(切断から5分経過)".formatted(occupant.getDisplayName()));
                    room.leave(occupant.getOccupantId());
                    changed = true;
                }
            }
            if (changed) {
                broadcaster.broadcast(room);
            }
        }
    }
}
