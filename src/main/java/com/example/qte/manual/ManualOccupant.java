package com.example.qte.manual;

import java.time.Instant;
import java.util.UUID;

import lombok.Getter;
import lombok.Setter;

/**
 * 部屋にいる人1人(設計書 6章)。
 *
 * ★occupantId は入室時にサーバが発行するランダムUUIDである。
 * これは表示用の識別子ではなく、配信先 {@code /topic/manual/{roomId}/view/{occupantId}} の
 * 一部を成す鍵である(設計書 2-4)。SimpleBroker は購読を認可しないため、
 * 宛先を知っていることがそのまま受信の権利になる。
 * したがって<b>他人の occupantId を配信ビューに載せてはならない</b>。
 * 在室者リストに出すのは名前と役割だけである。
 *
 * 切断と退室を区別する(レビューI反映)。明示的な退室は即座に席を空け、
 * WebSocket の切断は connected=false のまま保持して猶予を与える。
 * 猶予の打ち切り(5分)と再入室による復帰は Batch 19a で実装する。
 * ここでは判断に必要な値を持つところまでとする。
 */
@Getter
public class ManualOccupant {

    private final String occupantId = UUID.randomUUID().toString();

    private final String displayName;

    private final ManualOccupantRole role;

    private final Instant joinedAt = Instant.now();

    /** WebSocket の購読が有効か。入室直後は false で、ready を受け取って true になる */
    @Setter
    private boolean connected;

    /** 直近に切断した時刻。connected が true の間は null */
    @Setter
    private Instant disconnectedAt;

    public ManualOccupant(String displayName, ManualOccupantRole role) {
        this.displayName = displayName;
        this.role = role;
    }
}
