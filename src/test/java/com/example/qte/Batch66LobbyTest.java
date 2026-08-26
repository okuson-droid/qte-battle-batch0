package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.deck.DeckDefinition;
import com.example.qte.deck.DeckFileReader;
import com.example.qte.game.GameService;
import com.example.qte.game.view.GameView;
import com.example.qte.game.view.GameViewBuilder;
import com.example.qte.room.GameRoom;
import com.example.qte.room.GameRoomManager;
import com.example.qte.room.GameRoomOptions;
import com.example.qte.room.PlayerSlot;
import com.example.qte.room.SeatId;
import com.example.qte.room.Spectator;

/**
 * 通常モードの受付(★Batch 66)の試験。
 *
 * <h2>何を測るのか</h2>
 *
 * 66 は通常モードのロビーを手動モードの形へ揃えた。
 * {@link LobbyPageTest} が HTTP の入口を測るのに対し、
 * ここは<b>部屋の模型そのもの</b>(席・観戦者・デッキ・開始条件)と
 * <b>観戦者に届くビューの中身</b>を測る。
 *
 * <h2>★いちばん大事なのは「観戦者に手札が届かないこと」である</h2>
 *
 * 観戦は 66 で新しく作った経路であり、<b>情報の非対称を破る道が1本増えた</b>。
 * 手札・裏向きマナ・禁忌デッキが観戦者に届いていないことは、
 * ここでしか測れない(画面を見ても「出ていない」ことしか分からない)。
 */
@SpringBootTest
class Batch66LobbyTest {

    /** ★実物の確認用デッキ(デッキメーカーが書く形式)。テスト用に作った偽物ではない */
    private static final Path DARK_DECK = Path.of("decks/batch54-dark-check-deck.json");
    private static final Path LIGHT_DECK = Path.of("decks/batch54-light-check-deck.json");

    @Autowired
    GameRoomManager roomManager;

    @Autowired
    GameViewBuilder viewBuilder;

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

    /** 両席に人を座らせ、デッキを載せ、購読済みにする(=試合が始まる直前の状態) */
    private GameRoom seatedRoom() throws Exception {
        GameRoom room = newRoom();
        PlayerSlot a = room.join("あるふぁ", SeatId.A);
        PlayerSlot b = room.join("べーた", SeatId.B);
        a.loadDeck(deck(DARK_DECK), "闇");
        b.loadDeck(deck(LIGHT_DECK), "光");
        a.setReady(true);
        b.setReady(true);
        return room;
    }

    // ------------------------------------------------------------------
    // 開始条件(★デッキが載っていること)
    // ------------------------------------------------------------------

    /**
     * ★★<b>デッキが揃うまで試合は始まらない。</b>
     * 65 までは「2人揃って購読が済んだ」だけで始まった —— デッキはロビーで受け取り済み
     * (または「おまかせ」)という前提があったからである。
     * プリセットを退役させた 66 で、その前提は消えた。
     * <b>前提が消えたら、前提に合わせて曲げた実装を戻すところまでが仕事である</b>(64 の教訓)。
     */
    @Test
    void デッキが片方だけのときは試合が始まらない() throws Exception {
        GameRoom room = newRoom();
        PlayerSlot a = room.join("あるふぁ", SeatId.A);
        PlayerSlot b = room.join("べーた", SeatId.B);
        a.loadDeck(deck(DARK_DECK), "闇");
        a.setReady(true);
        b.setReady(true);

        assertThat(room.bothReady()).as("デッキが片方だけ").isFalse();
        gameService.startIfBothReady(room);
        assertThat(room.getGameState()).as("盤面はまだ作られない").isNull();

        b.loadDeck(deck(LIGHT_DECK), "光");
        assertThat(room.bothReady()).as("両方そろった").isTrue();
        gameService.startIfBothReady(room);
        assertThat(room.getGameState()).as("★盤面が作られる").isNotNull();
    }

    /** ★席が1つしか埋まっていなければ、デッキが載っていても始まらない */
    @Test
    void 席が片方だけのときは試合が始まらない() throws Exception {
        GameRoom room = newRoom();
        PlayerSlot a = room.join("あるふぁ", SeatId.A);
        a.loadDeck(deck(DARK_DECK), "闇");
        a.setReady(true);

        assertThat(room.bothReady()).isFalse();
        gameService.startIfBothReady(room);
        assertThat(room.getGameState()).isNull();
    }

    // ------------------------------------------------------------------
    // 席(★到着順ではない)
    // ------------------------------------------------------------------

    /**
     * ★★<b>{@code getSlots()} は席順(A → B)である。</b>
     * 65 までは到着順のリストで、{@code GameService} は {@code get(0)} を
     * 「1人目」として読んでいた。席Bの人が先に入った部屋では、
     * ダイスの記録に出る順が入れ替わっていた。
     */
    @Test
    void 席の並びは到着順ではなく席順である() {
        GameRoom room = newRoom();
        room.join("あとから席A", SeatId.B);
        room.join("さきに席B", SeatId.A);

        assertThat(room.getSlots().stream().map(PlayerSlot::getSeat).toList())
                .as("★席順で並ぶ").containsExactly(SeatId.A, SeatId.B);
    }

    /**
     * ★対戦が始まったあとには座れない(盤面の持ち主は2人で固定である)。
     *
     * <p>★<b>この門が単独で効く盤面は作れない。</b>試合は両席が埋まってはじめて始まるので、
     * 始まった部屋には必ず空席が無く、「席が埋まっている」でも同じように断られる。
     * それでも門を先に置いてあるのは、<b>断る理由のほうが正しいから</b>である ——
     * 「埋まっています」と言われると次の人は空くのを待ってしまう。
     * ★測れているのは<b>文言</b>だけであり、そこは正直に書いておく(裁定196)。
     */
    @Test
    void 対戦が始まったあとは席に着けない() throws Exception {
        GameRoom room = seatedRoom();
        gameService.startIfBothReady(room);
        assertThat(room.getGameState()).isNotNull();

        assertThatThrownBy(() -> room.join("わりこみ", SeatId.A))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("対戦が始まっています");
    }

    /** ★部屋IDは大文字小文字を問わず引ける(RoomIds.normalize を通っていることの確認) */
    @Test
    void 部屋IDは小文字で打っても引ける() {
        GameRoom room = newRoom();
        assertThat(roomManager.findRoom(room.getRoomId().toLowerCase()))
                .as("★17b が作った RoomIds.normalize を通っている").isPresent();
    }

    // ------------------------------------------------------------------
    // ★★★観戦者に届くビュー
    // ------------------------------------------------------------------

    /**
     * ★★★<b>観戦者に手札の中身は届かない。</b>
     *
     * 観戦者のビューは {@code buildPlayerView(isSelf = false)} を両席に当てただけであり、
     * 観戦専用の「見せてよい範囲」は1行も書いていない。
     * その帰結を<b>実際のビューで</b>確かめる —— 書かなかったことは、
     * 読んでも分からない(裁定186)。
     */
    @Test
    void 観戦者には両席の手札の中身が届かない() throws Exception {
        GameRoom room = seatedRoom();
        gameService.startIfBothReady(room);
        Spectator spectator = room.spectate("みるだけ");

        GameView view = viewBuilder.build(room, spectator.spectatorId());

        assertThat(view.room().viewerSpectator()).as("観戦者として扱われる").isTrue();
        assertThat(view.room().viewerSeat()).as("席は無い").isNull();
        assertThat(view.you().hand()).as("★席Aの手札の中身").isNull();
        assertThat(view.opponent().hand()).as("★席Bの手札の中身").isNull();
        assertThat(view.you().taboo()).as("★席Aの禁忌デッキの中身").isNull();
        assertThat(view.opponent().taboo()).as("★席Bの禁忌デッキの中身").isNull();
        assertThat(view.myTurn()).as("★観戦者に自分の番は無い").isFalse();
        assertThat(view.chooseOrder()).as("★先後の選択権も無い").isFalse();
        assertThat(view.mulligan()).as("★マリガンも無い").isFalse();
    }

    /**
     * ★<b>空振りでないことの証拠</b>(裁定186)。
     * 同じ盤面をプレイヤーの視点で組むと、手札の中身は<b>届く</b>。
     * これが無いと、上の試験は「ビルダーが常に null を返すだけ」でも通ってしまう。
     */
    @Test
    void 同じ盤面でもプレイヤーには自分の手札が届く() throws Exception {
        GameRoom room = seatedRoom();
        gameService.startIfBothReady(room);
        PlayerSlot a = room.slotOfSeat(SeatId.A).orElseThrow();

        GameView view = viewBuilder.build(room, a.getPlayerId());

        assertThat(view.room().viewerSpectator()).isFalse();
        assertThat(view.room().viewerSeat()).isEqualTo("A");
        assertThat(view.you().hand()).as("★自分の手札は届く").isNotNull();
        assertThat(view.opponent().hand()).as("相手の手札は届かない").isNull();
    }

    /** ★観戦を許可していない部屋では観戦者を作れない(届く宛先を作らない) */
    @Test
    void 観戦を許可していない部屋では観戦者を作れない() {
        GameRoom room = roomManager.createRoom(new GameRoomOptions("観戦不可", false, false));
        assertThatThrownBy(() -> room.spectate("みるだけ"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("観戦");
    }

    // ------------------------------------------------------------------
    // 受付のビュー(盤面がまだ無い時間帯)
    // ------------------------------------------------------------------

    /**
     * ★★<b>盤面が無い時間帯にも部屋の情報は届く。</b>
     * 席選択とデッキの読み込みは、まさにこの時間帯に行われる ——
     * {@code you / opponent} の下に置いたら、いちばん要るときに届かない。
     */
    @Test
    void 盤面がまだ無くても席とデッキの状況が届く() throws Exception {
        GameRoom room = newRoom();
        PlayerSlot a = room.join("あるふぁ", SeatId.A);
        a.loadDeck(deck(DARK_DECK), "闇デッキ");

        GameView view = viewBuilder.build(room, a.getPlayerId());

        assertThat(view.status()).isEqualTo("WAITING");
        assertThat(view.you()).as("盤面はまだ無い").isNull();
        assertThat(view.room().roomName()).isEqualTo("試験部屋");
        assertThat(view.room().seatA().name()).isEqualTo("あるふぁ");
        assertThat(view.room().seatA().deckLoaded()).as("★デッキを読んだ").isTrue();
        assertThat(view.room().seatB().name()).as("席Bは空き").isNull();
        assertThat(view.room().seatB().deckLoaded()).isFalse();
    }

    /** ★部屋名は必須である(全公開部屋が無いので、名前を省略できる部屋が1つも無い) */
    @Test
    void 部屋名は必須である() {
        assertThatThrownBy(() -> new GameRoomOptions("   ", true, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("部屋名");
        assertThatThrownBy(() -> new GameRoomOptions("あ".repeat(41), true, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("部屋名");
    }

    /** ★一覧は台帳の全部屋を返す(作った部屋が見えなければロビーの意味が無い) */
    @Test
    void 作った部屋は台帳の一覧に現れる() {
        GameRoom room = newRoom();
        List<String> ids = roomManager.allRooms().stream().map(GameRoom::getRoomId).toList();
        assertThat(ids).contains(room.getRoomId());
    }
}
