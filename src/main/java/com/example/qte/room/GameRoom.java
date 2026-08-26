package com.example.qte.room;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.example.qte.game.GameState;

import lombok.Getter;
import lombok.Setter;

/**
 * 対戦部屋。同じ GameState を席A・席Bの2人が共有し、観戦者が横から見る。
 *
 * lockオブジェクトについて: WebSocketでは両プレイヤーの操作が別スレッドで
 * ほぼ同時に届きうる。1つの試合の状態変更は必ず synchronized (room.getLock())
 * の中で行い、「1部屋につき同時に1操作」を保証する。
 * 部屋ごとに別ロックなので、他の部屋の試合を待たせることはない。
 *
 * <h2>★Batch 66: 席・観戦者・部屋の属性を持つようになった</h2>
 * 65 までの受付は「入った順に最大2人のリスト」だけであり、
 * <b>ロビーに一覧を出すための材料が1つも無かった</b>(部屋名も、どちらの席が
 * 空いているかも、鍵の有無も持っていない)。手動モードの {@code ManualRoom} が
 * 21a から持っていたものを、通常モードに要るぶんだけ写した形である。
 *
 * <p>★<b>写したのであって、共有はしていない。</b>{@code ManualRoom} は
 * 盤面・ログ・履歴・切断の猶予まで抱えており、あれを通常モードから使うと
 * 「盤面の入れ物」の都合がルール執行側へ流れ込む。65 のマナ行と同じ判断で、
 * <b>同じ語彙を使って複製し、一致は機械に見張らせる</b>(裁定130)。
 */
@Getter
public class GameRoom {

    private final String roomId;
    private final Instant createdAt = Instant.now();
    private final Object lock = new Object();

    /** 部屋の属性(部屋名・観戦の可否・鍵)。★Batch 66 */
    private final GameRoomOptions options;

    /** 席に着いた人。★席が鍵であり、到着順ではない */
    private final Map<SeatId, PlayerSlot> seats = new EnumMap<>(SeatId.class);

    /** 観戦者。席に着かない人はここに入る(★Batch 66) */
    private final List<Spectator> spectators = new ArrayList<>();

    /** ダイス勝者(先攻/後攻の選択権を持つプレイヤー)のplayerId */
    @Setter
    private String diceWinnerId;

    /** 対戦ログ(両者に公開される進行記録) */
    private final List<String> log = new ArrayList<>();

    @Setter
    private GameState gameState;

    public GameRoom(String roomId, GameRoomOptions options) {
        this.roomId = roomId;
        this.options = options;
    }

    // ---- 席 ----

    /**
     * 席に着いた人を席順(A → B)で返す。
     *
     * <p>★{@code GameService.startIfBothReady} は今も {@code get(0) / get(1)} で読む。
     * <b>65 まではここが到着順だった</b>ので、同じ2人でも入り直すと
     * ダイスの記録に出る順が入れ替わっていた。66 からは席順である。
     */
    public List<PlayerSlot> getSlots() {
        List<PlayerSlot> list = new ArrayList<>(2);
        for (SeatId seat : SeatId.values()) {
            PlayerSlot slot = seats.get(seat);
            if (slot != null) {
                list.add(slot);
            }
        }
        return list;
    }

    public boolean isFull() {
        return seats.size() >= 2;
    }

    public Optional<PlayerSlot> slotOfSeat(SeatId seat) {
        return Optional.ofNullable(seats.get(seat));
    }

    /**
     * 席に着く。★席が埋まっていれば断る —— 「押せるが失敗する」を
     * 画面側で消す(ボタンを無効化する)のは操作補助にすぎず、
     * 断るのはここである(設計判断27)。
     */
    public PlayerSlot join(String displayName, SeatId seat) {
        if (seat == null) {
            throw new IllegalArgumentException("座る席を選んでください");
        }
        if (gameState != null) {
            throw new IllegalArgumentException("この部屋は既に対戦が始まっています");
        }
        if (seats.containsKey(seat)) {
            throw new IllegalArgumentException("席%sは既に埋まっています".formatted(seat));
        }
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("名前を入力してください");
        }
        PlayerSlot slot = new PlayerSlot(UUID.randomUUID().toString(), name, seat);
        seats.put(seat, slot);
        return slot;
    }

    public Optional<PlayerSlot> findSlot(String playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        return getSlots().stream().filter(s -> s.getPlayerId().equals(playerId)).findFirst();
    }

    /**
     * 試合を始めてよいか。★<b>デッキが載っていることまで条件である</b>(★Batch 66)。
     * 65 までは「2人揃って購読が済んだ」だけで始まった —— デッキはロビーで
     * 受け取り済み(または「おまかせ」)という前提があったからである。
     * その前提は、デッキの読み込みを盤面へ移した時点で消えている。
     */
    public boolean bothReady() {
        List<PlayerSlot> slots = getSlots();
        return slots.size() == 2
                && slots.stream().allMatch(PlayerSlot::isReady)
                && slots.stream().allMatch(PlayerSlot::isDeckLoaded);
    }

    // ---- 観戦者 ----

    /** 観戦する。★観戦を許していない部屋では断る(届く宛先を作らない) */
    public Spectator spectate(String displayName) {
        if (!options.spectatorAllowed()) {
            throw new IllegalArgumentException("この部屋は観戦を許可していません");
        }
        String name = displayName == null || displayName.isBlank()
                ? "観戦者" : displayName.trim();
        Spectator spectator = new Spectator(UUID.randomUUID().toString(), name);
        spectators.add(spectator);
        return spectator;
    }

    public Optional<Spectator> findSpectator(String spectatorId) {
        if (spectatorId == null) {
            return Optional.empty();
        }
        return spectators.stream().filter(s -> s.spectatorId().equals(spectatorId)).findFirst();
    }

    public int spectatorCount() {
        return spectators.size();
    }

    // ---- ログ ----

    public void addLog(String message) {
        log.add(message);
        if (log.size() > 60) {
            log.remove(0);
        }
    }
}
