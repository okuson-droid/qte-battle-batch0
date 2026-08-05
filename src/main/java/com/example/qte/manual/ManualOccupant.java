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
 * ★猶予の打ち切り(5分)は {@link com.example.qte.manual.web.ManualCleanupScheduler} が、
 * 切断の検知は {@link com.example.qte.manual.web.ManualDisconnectListener} が行う
 * (いずれも Batch 19a)。再入室による復帰はクライアント側の localStorage が担う(設計書 6-3)。
 *
 * <h2>★Batch 21a: 席と観戦視点を持つ</h2>
 * 役割({@link ManualOccupantRole})を固定のフィールドではなく<b>席の有無から導く</b>形に
 * 変えた(21 設計書 2-2)。席を立てば観戦者、座ればプレイヤーであり、
 * この2つが独立に変わりうると必ず食い違う(「役割は PLAYER なのに席が無い人」が生まれる)。
 * 導出なら食い違いようがない。
 */
@Getter
public class ManualOccupant {

    private final String occupantId = UUID.randomUUID().toString();

    private final String displayName;

    private final Instant joinedAt = Instant.now();

    /**
     * 座っている席(Batch 21a 2-1)。★null は観戦者を意味する。
     *
     * 席の割り当てと解放は {@link ManualRoom#takeSeat} / {@link ManualRoom#standUp} が行う。
     * ここを直接書き換えると、二重着席(同じ席に2人)の検査を素通りする。
     */
    @Setter
    private ManualSeatId seatId;

    /**
     * 観戦者の視点(3-2)。★プレイヤーには効かない。
     * 既定は「公開のみ」である。入ってきた人にいきなり両者の手札を見せない側を既定にする。
     */
    @Setter
    private ManualSpectatorView spectatorView = ManualSpectatorView.PUBLIC_ONLY;

    /** WebSocket の購読が有効か。入室直後は false で、ready を受け取って true になる */
    @Setter
    private boolean connected;

    /** 直近に切断した時刻。connected が true の間は null */
    @Setter
    private Instant disconnectedAt;

    /**
     * WebSocket セッションID(Batch 19a)。
     *
     * {@code ready} を受けた時点のセッションIDを保持し、{@code SessionDisconnectEvent} が
     * 届いたときにどの在室者が切断したかを引くための鍵として使う。占有者本人にしか意味が無く、
     * 配信ビュー({@link com.example.qte.manual.view.ManualOccupantView})には載せない。
     */
    @Setter
    private String sessionId;

    public ManualOccupant(String displayName) {
        this.displayName = displayName;
    }

    /** 役割(設計書 6章)。★席の有無から導く。フィールドとして別に持たない。 */
    public ManualOccupantRole getRole() {
        return seatId == null ? ManualOccupantRole.SPECTATOR : ManualOccupantRole.PLAYER;
    }

    /** 席に着いているか。 */
    public boolean isSeated() {
        return seatId != null;
    }
}
