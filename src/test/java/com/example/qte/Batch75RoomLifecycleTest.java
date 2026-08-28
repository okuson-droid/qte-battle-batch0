package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.deck.DeckDefinition;
import com.example.qte.deck.DeckFileReader;
import com.example.qte.game.GameService;
import com.example.qte.room.GameRoom;
import com.example.qte.room.GameRoomManager;
import com.example.qte.room.GameRoomOptions;
import com.example.qte.room.PlayerSlot;
import com.example.qte.room.SeatId;
import com.example.qte.room.Spectator;
import com.example.qte.support.PeekingBroadcaster;
import com.example.qte.web.GameCleanupScheduler;
import com.example.qte.web.GameDisconnectListener;
import com.example.qte.web.GameWsController;

/**
 * ★★★Batch 75: 部屋消失の検出と無人部屋の掃除(裁定342〜345)。
 *
 * <h2>ここでしか測れないもの</h2>
 * 75 は <b>Java と JavaScript の両方</b>を変えたバッチである(72・74 と同じ)。
 * <b>接続の記録・猶予の起点・掃除・ROOM_LOST の送出</b>はサーバの状態であり、
 * verify のハーネスは Java を起こさないので<b>あちらには1件も届かない</b>
 * (70 の教訓「回る場所を選ぶ前に、そこまで届くかを確かめる」)。
 * 逆に<b>部屋消失の画面</b>は verify にしか照合先が無い。
 *
 * <h2>★★★掃除の試験は台帳を自前で持つ</h2>
 * {@link GameRoomManager} は singleton であり、他の試験が作った部屋も入っている ——
 * {@code @Autowired} のものを掃除すると、<b>同じ文脈で走る他の試験の部屋まで消す</b>。
 * ★掃除そのものを測る節だけは {@code new GameRoomManager()} を使う。
 * 依存を持たない {@code @Component} なので、これで本物の {@link GameCleanupScheduler} が回る。
 *
 * <h2>★★★「変えない」と決めたことにも番人を置く(74 の教訓)</h2>
 * 裁定342 は<b>席を空ける段を作らない</b>と決めている。
 * 手動モードを知っている次の人は「切断から5分で席を空けるべきだ」と読む —— それは
 * <b>裁定を知らなければ正しく見える</b>。{@link #切断しても席は空かない()} が唯一の番人である。
 */
@SpringBootTest
class Batch75RoomLifecycleTest {

    private static final Path DARK_DECK = Path.of("decks/batch54-dark-check-deck.json");
    private static final Path LIGHT_DECK = Path.of("decks/batch54-light-check-deck.json");

    /** 猶予より確実に長い時間。★実装の定数を書き写さない(裁定298) */
    private static final Duration 猶予より長く =
            GameCleanupScheduler.DESERTED_ROOM_TTL.plusMinutes(1);

    @Autowired
    GameRoomManager roomManager;

    @Autowired
    GameService gameService;

    @Autowired
    DeckFileReader deckFileReader;

    private GameRoom newRoom() {
        return roomManager.createRoom(new GameRoomOptions("試験部屋", true, false));
    }

    private DeckDefinition deck(Path path) throws Exception {
        return deckFileReader.read(Files.readString(path, StandardCharsets.UTF_8));
    }

    // ===================================================================
    // 1) 接続の記録(裁定342 の土台)
    // ===================================================================

    @Test
    @DisplayName("★購読が済むと、繋がっている人として数える")
    void 購読で繋がっている人になる() {
        GameRoom room = newRoom();
        String id = room.join("あるふぁ", SeatId.A).getPlayerId();

        assertThat(room.connectedCount())
                .as("★入室しただけでは繋がっていない —— ready を撃つまで購読は無い")
                .isZero();

        room.markConnected(id, "sess-1");

        assertThat(room.connectedCount()).isEqualTo(1);
        assertThat(room.desertedFor(Instant.now().plus(猶予より長く)))
                .as("★繋がっている人が居るあいだ、猶予は1秒も進まない")
                .isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("★観戦者も「繋がっている人」に数える(席の有無を見ない)")
    void 観戦者も数える() {
        GameRoom room = newRoom();
        Spectator spectator = room.spectate("みるひと");

        room.markConnected(spectator.spectatorId(), "sess-1");

        assertThat(room.connectedCount()).isEqualTo(1);
        assertThat(room.desertedFor(Instant.now().plus(猶予より長く)))
                .as("★★観戦者だけが見ている部屋は無人ではない。掃除の対象にしない")
                .isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("★切断はセッションIDで引く(イベントは id を運ばない)")
    void 切断はセッションidで引く() {
        GameRoom room = newRoom();
        String id = room.join("あるふぁ", SeatId.A).getPlayerId();
        room.markConnected(id, "sess-1");

        assertThat(room.markDisconnected("よその-sess"))
                .as("★他の部屋のセッションでは、この部屋の誰も切断しない")
                .isNull();
        assertThat(room.connectedCount()).isEqualTo(1);

        assertThat(room.markDisconnected("sess-1")).isEqualTo(id);
        assertThat(room.connectedCount()).isZero();
    }

    @Test
    @DisplayName("★★★切断しても席は空かない(裁定342・手動モードと違うところ)")
    void 切断しても席は空かない() {
        GameRoom room = newRoom();
        String id = room.join("あるふぁ", SeatId.A).getPlayerId();
        room.markConnected(id, "sess-1");

        room.markDisconnected("sess-1");

        assertThat(room.slotOfSeat(SeatId.A))
                .as("★★★裁定342: 通常モードは席を空ける段を持たない。"
                        + "空けると盤面の持ち主が消え、しかも誰も座れない")
                .isPresent();
        assertThat(room.findSlot(id)).isPresent();
        assertThat(room.getSpectators())
                .as("★観戦者に降ろすのは席を立つ操作(72)であって、切断ではない")
                .isEmpty();
    }

    @Test
    @DisplayName("★★購読の旗(ready)は切断で倒れない —— 2つの事実は寿命が違う")
    void readyの旗は切断で倒れない() {
        GameRoom room = newRoom();
        PlayerSlot slot = room.join("あるふぁ", SeatId.A);
        slot.setReady(true);
        room.markConnected(slot.getPlayerId(), "sess-1");

        room.markDisconnected("sess-1");

        assertThat(slot.isReady())
                .as("★{@code PlayerSlot.ready} は「試合を始めてよいか」の旗であり、"
                        + "一度立ったら倒れない(72 は再戦でも倒さないと決めた)")
                .isTrue();
        assertThat(room.connectedCount())
                .as("★こちらは「いま繋がっているか」であり、切断で倒れる")
                .isZero();
    }

    @Test
    @DisplayName("★席を立って観戦に降りても、接続の記録は持ち越される(id が同じだから)")
    void 席を立っても接続の記録は残る() {
        GameRoom room = newRoom();
        String id = room.join("あるふぁ", SeatId.A).getPlayerId();
        room.markConnected(id, "sess-1");

        room.standUp(id);

        assertThat(room.connectedCount())
                .as("★★72 の不変条件(1つの id は席と観戦者のどちらか一方)により、"
                        + "鍵が id であることに曖昧さが無い")
                .isEqualTo(1);
        assertThat(room.markDisconnected("sess-1")).isEqualTo(id);
    }

    // ===================================================================
    // 1-2) ★★★本物の入口(ready)を通す —— 72 の教訓
    //
    // ★上の節は {@code room.markConnected} を直接叩いている。それだけだと
    //   <b>{@code ready} が呼び忘れても誰も赤くしない</b> ——
    //   72-16 が踏んだ「番人が実際の入口を通っていない」とまったく同じ形である。
    // ★★<b>{@code ready} には出口が2つある</b>(席に着いた人 / 観戦者)。
    //   71 の教訓に従い、<b>出口ごとに1件ずつ</b>置く。
    // ===================================================================

    @Test
    @DisplayName("★★★ready を通すと接続が記録される(席に着いた人)")
    void readyで接続が記録される_席() {
        PeekingBroadcaster 返ったもの = new PeekingBroadcaster();
        GameRoom room = newRoom();
        String id = room.join("あるふぁ", SeatId.A).getPlayerId();

        new GameWsController(roomManager, gameService, 返ったもの)
                .ready(room.getRoomId(), new GameWsController.ActionRequest(id), "sess-1");

        assertThat(返ったもの.message())
                .as("★受理されたことを先に確かめる(拒否されていたら何も測れていない)")
                .isNull();
        assertThat(room.connectedCount())
                .as("★★★入口が記録し忘れると、その部屋は永久に無人にならない")
                .isEqualTo(1);
        assertThat(room.markDisconnected("sess-1"))
                .as("★記録した鍵で引けること —— セッションIDを取り違えていない")
                .isEqualTo(id);
    }

    @Test
    @DisplayName("★★★ready を通すと接続が記録される(観戦者・もう1つの出口)")
    void readyで接続が記録される_観戦者() {
        PeekingBroadcaster 返ったもの = new PeekingBroadcaster();
        GameRoom room = newRoom();
        String id = room.spectate("みるひと").spectatorId();

        new GameWsController(roomManager, gameService, 返ったもの)
                .ready(room.getRoomId(), new GameWsController.ActionRequest(id), "sess-2");

        assertThat(返ったもの.message()).isNull();
        assertThat(room.connectedCount())
                .as("★★観戦者は ready の別の出口を通る(席が見つからず早期に return する)")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★部屋に居ない id の ready では、接続を記録しない")
    void 部屋に居ない人は記録しない() {
        PeekingBroadcaster 返ったもの = new PeekingBroadcaster();
        GameRoom room = newRoom();

        new GameWsController(roomManager, gameService, 返ったもの)
                .ready(room.getRoomId(), new GameWsController.ActionRequest("よそのひと"), "sess-x");

        assertThat(返ったもの.message())
                .as("★席にも観戦者にも居ない id は理由を返して弾く(66 から)")
                .isNotNull();
        assertThat(room.connectedCount())
                .as("★★★記録が検証より前に走ると、居ない人のセッションで部屋が永久に生き残る")
                .isZero();
    }

    // ===================================================================
    // 2) 猶予の起点
    // ===================================================================

    @Test
    @DisplayName("★全員が切断すると猶予の起点が立つ")
    void 全員切断で猶予が始まる() {
        GameRoom room = newRoom();
        String a = room.join("あるふぁ", SeatId.A).getPlayerId();
        String b = room.join("べーた", SeatId.B).getPlayerId();
        room.markConnected(a, "sess-a");
        room.markConnected(b, "sess-b");

        room.markDisconnected("sess-a");
        assertThat(room.desertedFor(Instant.now().plus(猶予より長く)))
                .as("★1人でも残っていれば無人ではない")
                .isEqualTo(Duration.ZERO);

        room.markDisconnected("sess-b");
        assertThat(room.desertedFor(Instant.now().plus(猶予より長く)))
                .isGreaterThanOrEqualTo(猶予より長く.minusMinutes(1));
    }

    @Test
    @DisplayName("★★退室でも接続の記録が外れる(切断イベントが来ない経路がある)")
    void 退室でも記録が外れる() {
        GameRoom room = newRoom();
        String id = room.join("あるふぁ", SeatId.A).getPlayerId();
        room.markConnected(id, "sess-1");

        room.leave(id);

        assertThat(room.connectedCount())
                .as("★★外さないと、誰も居ない部屋が永久に「1人繋がっている」と答える")
                .isZero();
        assertThat(room.desertedFor(Instant.now().plus(猶予より長く)))
                .isGreaterThan(Duration.ZERO);
    }

    @Test
    @DisplayName("★戻ってくれば猶予の起点は消える")
    void 再接続で猶予が消える() {
        GameRoom room = newRoom();
        String id = room.join("あるふぁ", SeatId.A).getPlayerId();
        room.markConnected(id, "sess-1");
        room.markDisconnected("sess-1");
        assertThat(room.desertedFor(Instant.now().plus(猶予より長く))).isGreaterThan(Duration.ZERO);

        room.markConnected(id, "sess-2");

        assertThat(room.desertedFor(Instant.now().plus(猶予より長く)))
                .as("★同じ人が別のセッションで戻る(再読み込み・回線復帰)。これが普通の形である")
                .isEqualTo(Duration.ZERO);
    }

    /**
     * ★★★猶予は「無人になった時刻」から数える —— <b>部屋を作った時刻からではない</b>。
     *
     * <p>{@code markConnected} が起点を消さないと、起点は<b>部屋を作った時刻のまま</b>動かない
     * ({@code noteSessionsChanged} は「まだ立っていなければ立てる」形だからである)。
     * すると<b>1時間遊んだ部屋が、切断した瞬間に掃除の対象になる</b>。
     *
     * <p>★★<b>壁時計でしか観測できない</b>ので、わざと少しだけ間を置く。
     * ★★★<b>これは揺れる試験ではない</b>(74 の「シャッフルに依存する試験は番人ではない」を
     * 踏まないための確認)。閾値を置かず、<b>2つの実測どうしを突き合わせている</b>:
     * <ul>
     *   <li>正しい実装 …… 起点は {@code markDisconnected} の中で立つので、
     *       必ず {@code 切断の直前} 以後である → 不等式は<b>常に</b>成り立つ</li>
     *   <li>壊れた実装 …… 起点は部屋を作った時刻のまま → 差は<b>間に置いた時間ぶん</b>開く</li>
     * </ul>
     * ★<b>間を置くのは「差を測れるようにする」ためであって、境界を跨がせるためではない。</b>
     * ★★{@code createdAt} と比べてはいけない ——
     * あれと {@code emptySince} は<b>別々の {@code Instant.now()}</b> で初期化されるので、
     * 壊れていても数ナノ秒ぶん小さくなり、<b>不等式が通ってしまう</b>(実際に踏んだ)。
     */
    @Test
    @DisplayName("★★★猶予の起点は「無人になった時刻」である(部屋を作った時刻ではない)")
    void 猶予は無人になった時刻から数える() throws Exception {
        GameRoom room = newRoom();
        String id = room.join("あるふぁ", SeatId.A).getPlayerId();
        Thread.sleep(50);
        room.markConnected(id, "sess-1");

        Instant 切断の直前 = Instant.now();
        room.markDisconnected("sess-1");
        Instant now = Instant.now();

        assertThat(room.desertedFor(now))
                .as("★★★起点が動かないと、長く遊んだ部屋ほど切断の直後に消される")
                .isLessThanOrEqualTo(Duration.between(切断の直前, now));
    }

    @Test
    @DisplayName("★作られた直後の部屋は無人である(誰も購読していない)")
    void 作った直後は無人である() {
        GameRoom room = newRoom();

        assertThat(room.desertedFor(Instant.now().plus(猶予より長く)))
                .as("★★「部屋だけ作って誰も来なかった」を掃除の対象にするための初期値である")
                .isGreaterThan(Duration.ZERO);
    }

    // ===================================================================
    // 3) 掃除(裁定342・343)★台帳は自前で持つ
    // ===================================================================

    /** ★他の試験の部屋を巻き込まないための、この節だけの台帳 */
    private record SweepRig(GameRoomManager ledger, GameCleanupScheduler sweeper) {

        static SweepRig create() {
            GameRoomManager manager = new GameRoomManager();
            return new SweepRig(manager, new GameCleanupScheduler(manager));
        }

        GameRoom room() {
            return ledger.createRoom(new GameRoomOptions("試験部屋", true, false));
        }
    }

    @Test
    @DisplayName("★★★誰も繋がっていない部屋は猶予を過ぎたら消える(裁定342)")
    void 無人の部屋は消える() {
        SweepRig t = SweepRig.create();
        GameRoom room = t.room();
        String roomId = room.getRoomId();

        t.sweeper().sweep(Instant.now());
        assertThat(t.ledger().findRoom(roomId))
                .as("★猶予の内側では消さない")
                .isPresent();

        t.sweeper().sweep(Instant.now().plus(猶予より長く));
        assertThat(t.ledger().findRoom(roomId))
                .as("★★★74 まで {@code removeRoom} には呼び出し元が1つも無かった")
                .isEmpty();
    }

    @Test
    @DisplayName("★繋がっている人が居る部屋は、いつまで経っても消えない")
    void 繋がっていれば消えない() {
        SweepRig t = SweepRig.create();
        GameRoom room = t.room();
        String id = room.join("あるふぁ", SeatId.A).getPlayerId();
        room.markConnected(id, "sess-1");

        t.sweeper().sweep(Instant.now().plus(猶予より長く.multipliedBy(10)));

        assertThat(t.ledger().findRoom(room.getRoomId())).isPresent();
    }

    @Test
    @DisplayName("★★★対戦中の部屋も、全員が切断していれば消える(裁定343)")
    void 対戦中でも消える() throws Exception {
        SweepRig t = SweepRig.create();
        GameRoom room = t.room();
        PlayerSlot a = room.join("あるふぁ", SeatId.A);
        PlayerSlot b = room.join("べーた", SeatId.B);
        a.loadDeck(deck(DARK_DECK), "闇");
        b.loadDeck(deck(LIGHT_DECK), "光");
        a.setReady(true);
        b.setReady(true);
        room.markConnected(a.getPlayerId(), "sess-a");
        room.markConnected(b.getPlayerId(), "sess-b");
        gameService.startIfBothReady(room);
        assertThat(room.getGameState())
                .as("★盤面が本当に在ることを確かめてから測る(前提の作り忘れを検出する)")
                .isNotNull();

        room.markDisconnected("sess-a");
        room.markDisconnected("sess-b");
        t.sweeper().sweep(Instant.now().plus(猶予より長く));

        assertThat(t.ledger().findRoom(room.getRoomId()))
                .as("★★★裁定343: 部屋はメモリ上にしか無い。"
                        + "残すと、両者が帰った対戦中の部屋が一覧に永久に並ぶ")
                .isEmpty();
    }

    @Test
    @DisplayName("★掃除は繋がっている部屋を巻き込まない(1周で両方を正しく分ける)")
    void 掃除は部屋を選ぶ() {
        SweepRig t = SweepRig.create();
        GameRoom 残る = t.room();
        GameRoom 消える = t.room();
        String id = 残る.join("あるふぁ", SeatId.A).getPlayerId();
        残る.markConnected(id, "sess-1");

        t.sweeper().sweep(Instant.now().plus(猶予より長く));

        assertThat(t.ledger().findRoom(残る.getRoomId())).isPresent();
        assertThat(t.ledger().findRoom(消える.getRoomId())).isEmpty();
    }

    // ===================================================================
    // 4) 切断イベントの結線
    // ===================================================================

    @Test
    @DisplayName("★★切断イベントが、その人の居る部屋にだけ届く")
    void 切断イベントが結線されている() {
        GameRoomManager 台帳 = new GameRoomManager();
        GameRoom 甲 = 台帳.createRoom(new GameRoomOptions("甲", true, false));
        GameRoom 乙 = 台帳.createRoom(new GameRoomOptions("乙", true, false));
        String 甲の人 = 甲.join("あるふぁ", SeatId.A).getPlayerId();
        String 乙の人 = 乙.join("べーた", SeatId.A).getPlayerId();
        甲.markConnected(甲の人, "sess-甲");
        乙.markConnected(乙の人, "sess-乙");

        new GameDisconnectListener(台帳).onDisconnect(
                new org.springframework.web.socket.messaging.SessionDisconnectEvent(
                        this, org.springframework.messaging.support.MessageBuilder
                                .withPayload(new byte[0]).build(),
                        "sess-甲", org.springframework.web.socket.CloseStatus.NORMAL));

        assertThat(甲.connectedCount()).isZero();
        assertThat(乙.connectedCount())
                .as("★セッションIDは接続1本につき1つである。他の部屋の人を巻き込まない")
                .isEqualTo(1);
    }

    // ===================================================================
    // 5) 部屋消失の送出(裁定344)
    // ===================================================================

    @Test
    @DisplayName("★★★部屋が無い宛先への操作は ROOM_LOST を返す(ERROR ではない)")
    void 部屋が無ければROOM_LOSTを返す() {
        PeekingBroadcaster 返ったもの = new PeekingBroadcaster();
        GameWsController 入口 = new GameWsController(roomManager, gameService, 返ったもの);

        入口.endTurn("KESHITA", new GameWsController.ActionRequest("だれか"));

        assertThat(返ったもの.roomLostCount())
                .as("★★★裁定344: ERROR は「その操作が拒否された理由」であり、"
                        + "画面はそれを出してその場に留まる。部屋消失は留まれない")
                .isEqualTo(1);
        assertThat(返ったもの.message())
                .as("★本文を持たない型である。文言で判定させない(手動モードはそうしている)")
                .isNull();
        assertThat(返ったもの.playerId()).isEqualTo("だれか");
        assertThat(返ったもの.roomId()).isEqualTo("KESHITA");
    }

    @Test
    @DisplayName("★★掃除で消えた部屋を、戻ってきた人が叩くと ROOM_LOST を受け取る")
    void 掃除の後に戻ると部屋消失を受け取る() {
        SweepRig t = SweepRig.create();
        GameRoom room = t.room();
        String id = room.join("あるふぁ", SeatId.A).getPlayerId();
        String roomId = room.getRoomId();

        t.sweeper().sweep(Instant.now().plus(猶予より長く));

        PeekingBroadcaster 返ったもの = new PeekingBroadcaster();
        new GameWsController(t.ledger(), gameService, 返ったもの)
                .endTurn(roomId, new GameWsController.ActionRequest(id));

        assertThat(返ったもの.roomLostCount())
                .as("★★これが M と N が同じ工事の裏表である理由そのものである —— "
                        + "掃除が作った状態を、部屋消失の検出が受け取る")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★部屋が在るときは ROOM_LOST を返さない(拒否は今までどおり ERROR)")
    void 部屋が在れば部屋消失にはならない() {
        PeekingBroadcaster 返ったもの = new PeekingBroadcaster();
        GameRoom room = newRoom();
        String id = room.join("あるふぁ", SeatId.A).getPlayerId();

        new GameWsController(roomManager, gameService, 返ったもの)
                .endTurn(room.getRoomId(), new GameWsController.ActionRequest(id));

        assertThat(返ったもの.roomLostCount())
                .as("★盤面が始まっていないので操作は拒否されるが、それは部屋消失ではない")
                .isZero();
        assertThat(返ったもの.message()).isNotNull();
    }
}
