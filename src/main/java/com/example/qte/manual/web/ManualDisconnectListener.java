package com.example.qte.manual.web;

import java.time.Instant;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import com.example.qte.manual.ManualOccupant;
import com.example.qte.manual.ManualRoom;
import com.example.qte.manual.ManualRoomManager;

import lombok.RequiredArgsConstructor;

/**
 * WebSocket の切断を検知する(設計書 6-3)。
 *
 * ★プロジェクト全体でこの種のリスナーはこれが最初である(通常モードにも無い)。
 * {@code SessionDisconnectEvent} はブラウザのタブを閉じる・リロードする・回線が切れる、
 * いずれでも Spring が発行する。ここでは「誰が切れたか」を特定して連結中フラグを倒すだけで、
 * 席を空ける判断(5分の猶予)は {@link ManualCleanupScheduler} に委ねる。
 *
 * <h2>occupantId ではなく sessionId で引く理由</h2>
 * {@code SessionDisconnectEvent} が持つのは WebSocket セッションIDだけで、
 * アプリ層の occupantId は含まれない。{@code ready} を受けた時点で
 * {@link ManualOccupant#setSessionId} に記録しておいた対応を、ここで逆引きする。
 *
 * <h2>全部屋を走査する理由</h2>
 * イベントにはどの部屋の接続かという情報も無い。一人回しの部屋数は多くても数十程度であり、
 * 走査コストは無視できる。部屋数が増えたら sessionId→部屋の索引を足す余地はあるが、
 * フェーズ1の規模では過剰設計になる。
 */
@Component
@RequiredArgsConstructor
public class ManualDisconnectListener {

    private final ManualRoomManager roomManager;

    private final ManualBroadcaster broadcaster;

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        for (ManualRoom room : roomManager.allRooms()) {
            ManualOccupant occupant;
            synchronized (room.getLock()) {
                occupant = room.findOccupantBySession(sessionId).orElse(null);
                if (occupant == null || !occupant.isConnected()) {
                    continue;
                }
                occupant.setConnected(false);
                occupant.setDisconnectedAt(Instant.now());
                room.addLog("%s が切断した(5分以内に戻らなければ席を空ける)"
                        .formatted(occupant.getDisplayName()));
            }
            broadcaster.broadcast(room);
            // ★sessionId は接続1本につき1つなので、見つかった時点で他の部屋を探す必要は無い。
            break;
        }
    }
}
