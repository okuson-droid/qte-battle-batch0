package com.example.qte.room;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.example.qte.game.GameState;
import com.example.qte.game.GameStatus;

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

    /**
     * 観戦する。★観戦を許していない部屋では断る(届く宛先を作らない)
     *
     * <p>★★<b>Batch 72: 名前が必須になった。</b>66 は空欄なら「観戦者」に寄せていたが、
     * 72 で<b>観戦者が席に着けるようになった</b>({@link #takeSeat})。
     * 名前を持たないまま席に着くと、相手には「観戦者」という名前の対戦相手が現れる ——
     * しかも {@link #join} は名前を必須にしているので、<b>同じ席に着くのに
     * 経路によって required が違う</b>という状態になる(設計判断28)。
     * ★既定名の分岐を足すのではなく、<b>消した</b>。
     */
    public Spectator spectate(String displayName) {
        if (!options.spectatorAllowed()) {
            throw new IllegalArgumentException("この部屋は観戦を許可していません");
        }
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("名前を入力してください");
        }
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

    // ---- ★★★席の移動と退室(Batch 72) ----
    //
    // ★66 は「席を立てない」と書いた。理由は<b>通常モードの席が GameState の2人と
    //   1対1であり、始まったあとに動かすと盤面の持ち主が消える</b>ことである。
    //   ★<b>その理由は「始まったあと」にしか掛かっていない。</b>
    //   72 は理由の掛かる範囲だけを守り、掛からない時間帯(WAITING)を開けた。
    //
    // ★★★<b>id は変えない。</b>席と観戦者を行き来しても playerId / spectatorId は
    //   同じ文字列のままである。理由は3つある。
    //     (1) id は<b>配信の宛先</b>である(/topic/room/{roomId}/player/{id})。
    //         変えると購読を張り直すまでビューが1通も届かない。
    //     (2) id は<b>localStorage の値</b>である(qte-auto-occupant-{roomId})。
    //         変えると、書き換えに失敗した端末が二度と戻れなくなる。
    //     (3) 「同じ人である」ことを表すのは id であって、席ではない。
    //   ★<b>型は別のままである</b>(裁定: {@link Spectator} の Javadoc)。
    //     66 が型を分けたのは「席を見るのを忘れた分岐が観戦者をプレイヤーとして扱う」のを
    //     コンパイル時に止めるためであり、その理由は 72 でも生きている。
    //
    // ★★<b>不変条件: 1つの id は、席と観戦者のどちらか一方にしか居ない。</b>
    //   両方に居ると {@code GameBroadcaster.broadcast} が同じ宛先へ2回送る。
    //   番人は {@code Batch72SeatTest#同じidが席と観戦者の両方に現れることはない}。

    /**
     * 席を立って観戦者に降りる(★Batch 72)。
     *
     * <p>★<b>観戦を許さない部屋では断る。</b>降りる先が無いためである。
     * 手動モードはこの場合を「退室」に読み替えるが({@code ManualWsController#seat})、
     * <b>通常モードは読み替えない</b>(マスター確認) —— 退室は
     * {@link #leave} という別の操作として在り、押した人が自分で選ぶ。
     * ★1つのボタンが部屋の設定しだいで別のことをするのは、
     * 「押すつもりが無かった」を作る形である(設計判断47 の筋)。
     *
     * @return 降りた先の観戦者(id は席に着いていたときのまま)
     */
    public Spectator standUp(String playerId) {
        if (gameState != null) {
            throw new IllegalArgumentException("対戦が始まったあとは席を立てません");
        }
        PlayerSlot slot = findSlot(playerId).orElseThrow(
                () -> new IllegalArgumentException("この部屋の席に着いていません"));
        if (!options.spectatorAllowed()) {
            throw new IllegalArgumentException(
                    "この部屋は観戦できないため、席を立てません(退室してください)");
        }
        seats.remove(slot.getSeat());
        Spectator spectator = new Spectator(slot.getPlayerId(), slot.getDisplayName());
        spectators.add(spectator);
        return spectator;
    }

    /**
     * 観戦者が空いている席に着く(★Batch 72)。
     *
     * <p>★<b>デッキは持っていない。</b>新しい {@link PlayerSlot} は
     * {@code deck == null} で始まるので、{@link #bothReady()} は偽のままであり、
     * 盤面のデッキ読み込みゲートが<b>そのまま開く</b> ——
     * 66 が作った器がそのまま効く(「器が無いと思ったら、まず在るかどうかを見る」)。
     */
    public PlayerSlot takeSeat(String spectatorId, SeatId seat) {
        if (seat == null) {
            throw new IllegalArgumentException("座る席を選んでください");
        }
        if (gameState != null) {
            throw new IllegalArgumentException("この部屋は既に対戦が始まっています");
        }
        Spectator spectator = findSpectator(spectatorId).orElseThrow(
                () -> new IllegalArgumentException("この部屋を観戦していません"));
        if (seats.containsKey(seat)) {
            throw new IllegalArgumentException("席%sは既に埋まっています".formatted(seat));
        }
        spectators.remove(spectator);
        PlayerSlot slot = new PlayerSlot(spectator.spectatorId(), spectator.displayName(), seat);
        seats.put(seat, slot);
        return slot;
    }

    /**
     * 明示的な退室(★Batch 72)。席にも観戦者にも使える。
     *
     * <p>★<b>対戦中の着席者は退室できない。</b>盤面の持ち主が黙って消えると、
     * 相手には「相手が動かなくなった」としか見えない —— それは
     * 71 が潰した「気づきにくい事故」と同じ形である。
     * 抜けるなら {@code GameService.concede}(投了)を先に通し、
     * <b>相手に勝ちを渡してから</b>抜ける。
     * ★決着後({@link GameStatus#FINISHED})は退室できる。
     *
     * <p>★★<b>部屋は残る。</b>通常モードには無人部屋の掃除が無い(66 の積み残し)。
     * 72 はそれを直していない —— 直すなら {@code ManualCleanupScheduler} にあたるものが要り、
     * それは「退室できるようにする」とは別の工事である。
     */
    public void leave(String occupantId) {
        PlayerSlot slot = findSlot(occupantId).orElse(null);
        if (slot != null && gameState != null
                && gameState.getStatus() != GameStatus.FINISHED) {
            throw new IllegalArgumentException("対戦中は退室できません(先に投了してください)");
        }
        if (slot != null) {
            seats.remove(slot.getSeat());
        }
        spectators.removeIf(s -> s.spectatorId().equals(occupantId));
        if (occupantId.equals(rematchOfferedBy)) {
            rematchOfferedBy = null;
        }
    }

    // ---- ★★★再戦(Batch 72) ----

    /**
     * 再戦を申し込んだ人の playerId。申し込みが無ければ null(★Batch 72)。
     *
     * <p>★★<b>2段(申し込み → 承諾)にしたのはマスターの選択である。</b>
     * 片方が押しただけで盤面を捨てる形も採れたが、それは
     * <b>相手の見ている画面を相手の同意なしに消す</b>操作になる。
     *
     * <p>★<b>旗を立てる人が居るから作った。</b>71 は {@code connectionFatal} を
     * 「立てる人が1人も居ない」という理由で作らなかった(裁定178)。
     * こちらは {@code GameService.offerRematch} が立て、
     * {@code acceptRematch} / {@code declineRematch} / {@link #leave} が倒す。
     */
    @Setter
    private String rematchOfferedBy;

    /**
     * 再戦のために盤面を捨て、受付の状態へ戻す(★Batch 72)。
     *
     * <p>★<b>捨てるもの</b>: 盤面・ダイスの勝者・申し込みの旗・両席のデッキ・ログ。
     * <p>★<b>捨てないもの</b>: 席・名前・id・{@code ready}(購読の旗)。
     * ★{@code ready} は「WebSocket の購読が済んでいるか」という事実であり、
     * 再戦で購読が切れるわけではない。倒すと<b>意味を取り違えた旗</b>になる。
     *
     * <p>★★<b>ログを消すのは、60行のリングバッファだからである。</b>
     * 残すと前の試合の行が新しい試合に混ざり、
     * 「―― ターン1 ――」が2回出るログを人が読むことになる。
     */
    public void resetForRematch() {
        gameState = null;
        diceWinnerId = null;
        rematchOfferedBy = null;
        log.clear();
        for (PlayerSlot slot : getSlots()) {
            slot.clearDeck();
        }
    }

    // ---- ログ ----

    public void addLog(String message) {
        log.add(message);
        if (log.size() > 60) {
            log.remove(0);
        }
    }
}
