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
 * 切断中の在室者が5分を超えたら席を空け、無人の部屋を片付ける
 * (設計書 6-3 / Batch 21a 設計書 1-4)。
 *
 * ★猶予の起点は {@link ManualDisconnectListener} が記録する {@code disconnectedAt} である。
 * 明示的な退室({@code ManualWsController#leave})は即座に在室者リストから消えるため、
 * このスケジューラの席解放の対象にはならない(そもそも走査時に見つからない)。
 *
 * <h2>★Batch 21a: 無人部屋の削除(1-4)</h2>
 * 19a で {@link ManualRoomManager#removeRoom} を用意したまま呼び出し元が無かった積み残しを、
 * ここで解消する。<b>部屋一覧(1-3)を作る以上、無人の死に部屋が並ぶのは受け入れられない</b>
 * ため、機能を足したこのバッチが後始末の責任も引き受ける。
 *
 * 判定は「在室者0(切断猶予中も含めて0)の状態が5分続いたか」である。
 * 猶予中の人は在室者リストに残っているため、この条件では消えない。
 * 順序も重要で、<b>先に猶予切れの席を空けてから</b>無人判定を行う。
 * 逆にすると、最後の1人の猶予が切れた回では部屋が無人と判定されず、
 * さらに5分待つことになる(実害は小さいが、意図した挙動ではない)。
 *
 * 1分ごとに全部屋を確認する。想定規模(部屋数は多くても数十)では、
 * この頻度・走査コストのどちらも無視できる。
 */
@Component
@RequiredArgsConstructor
public class ManualCleanupScheduler {

    /** 切断からの猶予(設計書 6-3)。★{@code ManualViewBuilder} の表示もこの値に合わせる */
    private static final Duration GRACE_PERIOD = Duration.ofMinutes(5);

    /** 無人の部屋を保持する時間(21 1-4)。猶予と同じ長さにする理由は下の javadoc に書いた */
    private static final Duration EMPTY_ROOM_TTL = Duration.ofMinutes(5);

    private final ManualRoomManager roomManager;

    private final ManualBroadcaster broadcaster;

    @Scheduled(fixedRate = 60_000)
    public void sweep() {
        Instant now = Instant.now();
        List<String> abandoned = new ArrayList<>();

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
                    if (Duration.between(occupant.getDisconnectedAt(), now)
                            .compareTo(GRACE_PERIOD) >= 0) {
                        expired.add(occupant);
                    }
                }
                for (ManualOccupant occupant : expired) {
                    room.addLog("%s が退室した(切断から5分経過)".formatted(occupant.getDisplayName()));
                    room.leave(occupant.getOccupantId());
                    changed = true;
                }
                // ★席を空けた直後に無人を判定する(上の javadoc)。
                //   削除そのものはロックの外で行う。台帳の Map を触る処理を
                //   部屋のロックの中に入れる必要が無く、入れると2つのロックの順序を
                //   気にすることになる。
                if (room.emptyFor(now).compareTo(EMPTY_ROOM_TTL) >= 0) {
                    abandoned.add(room.getRoomId());
                }
            }
            if (changed) {
                broadcaster.broadcast(room);
            }
        }

        for (String roomId : abandoned) {
            roomManager.removeRoom(roomId);
        }
    }
}
