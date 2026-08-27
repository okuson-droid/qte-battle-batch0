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
import com.example.qte.game.GameStatus;
import com.example.qte.game.PlayerState;
import com.example.qte.game.view.GameView;
import com.example.qte.game.view.GameViewBuilder;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.room.GameRoom;
import com.example.qte.room.GameRoomManager;
import com.example.qte.room.GameRoomOptions;
import com.example.qte.room.PlayerSlot;
import com.example.qte.room.SeatId;
import com.example.qte.room.Spectator;
import com.example.qte.support.AutoGameFixture;

/**
 * 試合の出入り(★Batch 72)—— 席・退室・投了・再戦。
 *
 * <h2>ここでしか測れないもの</h2>
 *
 * 72 は <b>Java と JavaScript の両方</b>を変えたバッチである。
 * 71 とは逆で、守る対象の多くが<b>サーバの状態</b>にある ——
 * verify のハーネスは Java を起こさないので、{@code GameRoom} を壊しても
 * <b>あちらには1件も届かない</b>(70 の教訓「回る場所を選ぶ前に、そこまで届くかを確かめる」)。
 *
 * <p>逆に、ボタンの出し分け・重なり・確認モーダル・遷移は
 * <b>verify にしか照合先が無い</b>。両方に置くのではなく、
 * <b>届く側に置く</b>のがこのバッチの割り振りである。
 *
 * <h2>★いちばん大事なのは「同じ id が2つの立場に居ないこと」である</h2>
 *
 * 72 は席と観戦者を行き来できるようにしたが、<b>id は変えない</b>
 * (配信の宛先であり、localStorage の値であるため)。
 * 席から観戦者へ移す処理が「消して足す」の片方だけになると、
 * <b>同じ宛先へ2回配信される</b>か、<b>誰にも届かなくなる</b>。
 * 画面からは決して見えない事故であり、ここが唯一の番人である。
 */
@SpringBootTest
class Batch72SeatTest {

    private static final Path DARK_DECK = Path.of("decks/batch54-dark-check-deck.json");
    private static final Path LIGHT_DECK = Path.of("decks/batch54-light-check-deck.json");

    /** 常在効果を持たないリーダー(足場の既定。48・49 の流儀) */
    private static final String WIND_LEADER = "QTE-M-WIND-1";
    private static final String WATER_LEADER = "QTE-M-WATER-1";

    @Autowired
    GameRoomManager roomManager;

    @Autowired
    GameViewBuilder viewBuilder;

    @Autowired
    GameService gameService;

    @Autowired
    DeckFileReader deckFileReader;

    @Autowired
    CardMasterRepository cards;

    private GameRoom newRoom(boolean spectatorAllowed) {
        return roomManager.createRoom(new GameRoomOptions("試験部屋", spectatorAllowed, false));
    }

    private DeckDefinition deck(Path path) throws Exception {
        return deckFileReader.read(Files.readString(path, StandardCharsets.UTF_8));
    }

    /** 両席が埋まり、デッキも購読も済んだ部屋(=試合が始まる直前) */
    private GameRoom seatedRoom() throws Exception {
        GameRoom room = newRoom(true);
        PlayerSlot a = room.join("あるふぁ", SeatId.A);
        PlayerSlot b = room.join("べーた", SeatId.B);
        a.loadDeck(deck(DARK_DECK), "闇");
        b.loadDeck(deck(LIGHT_DECK), "光");
        a.setReady(true);
        b.setReady(true);
        return room;
    }

    // ===================================================================
    // 席の移動
    // ===================================================================

    @Test
    void 席を立つと観戦者になる_idは変わらない() {
        GameRoom room = newRoom(true);
        PlayerSlot slot = room.join("あるふぁ", SeatId.A);
        String id = slot.getPlayerId();

        Spectator spectator = room.standUp(id);

        assertThat(spectator.spectatorId())
                .as("★★★id は配信の宛先であり localStorage の値でもある。席を移っても変えない")
                .isEqualTo(id);
        assertThat(spectator.displayName()).isEqualTo("あるふぁ");
        assertThat(room.slotOfSeat(SeatId.A)).isEmpty();
        assertThat(room.getSpectators()).hasSize(1);
    }

    @Test
    void 同じidが席と観戦者の両方に現れることはない() {
        GameRoom room = newRoom(true);
        String id = room.join("あるふぁ", SeatId.A).getPlayerId();

        room.standUp(id);
        assertThat(room.findSlot(id))
                .as("★席から抜けていなければ、同じ宛先へ2回配信される")
                .isEmpty();
        assertThat(room.findSpectator(id)).isPresent();

        room.takeSeat(id, SeatId.B);
        assertThat(room.findSpectator(id))
                .as("★観戦者から抜けていなければ、同じ宛先へ2回配信される")
                .isEmpty();
        assertThat(room.findSlot(id)).isPresent();
        assertThat(room.slotOfSeat(SeatId.B)).isPresent();
    }

    @Test
    void 観戦者は空いている席に着ける() {
        GameRoom room = newRoom(true);
        Spectator spectator = room.spectate("みてるひと");

        PlayerSlot slot = room.takeSeat(spectator.spectatorId(), SeatId.A);

        assertThat(slot.getSeat()).isEqualTo(SeatId.A);
        assertThat(slot.getDisplayName()).isEqualTo("みてるひと");
        assertThat(slot.isDeckLoaded())
                .as("★デッキは持っていない。66 が作ったデッキゲートがそのまま開く")
                .isFalse();
        assertThat(room.bothReady()).isFalse();
    }

    @Test
    void 埋まっている席には着けない() {
        GameRoom room = newRoom(true);
        room.join("あるふぁ", SeatId.A);
        Spectator spectator = room.spectate("みてるひと");

        assertThatThrownBy(() -> room.takeSeat(spectator.spectatorId(), SeatId.A))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("既に埋まっています");
        assertThat(room.findSpectator(spectator.spectatorId()))
                .as("★断られたのなら、観戦者のままでいなければならない")
                .isPresent();
    }

    @Test
    void 対戦が始まったら席を立てないし着けない() throws Exception {
        GameRoom room = seatedRoom();
        Spectator spectator = room.spectate("みてるひと");
        String seatedId = room.slotOfSeat(SeatId.A).orElseThrow().getPlayerId();
        gameService.startIfBothReady(room);
        assertThat(room.getGameState()).isNotNull();

        assertThatThrownBy(() -> room.standUp(seatedId))
                .as("★★席は GameState の2人と1対1である。動かすと盤面の持ち主が消える")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("対戦が始まった");
        assertThatThrownBy(() -> room.takeSeat(spectator.spectatorId(), SeatId.A))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("既に対戦が始まっています");
    }

    /**
     * ★★★決着したあとに空いた席には、まだ座れない。
     *
     * <p>★<b>この状態は 72 で初めて作れるようになった。</b>
     * 「盤面が在るのに席が空いている」は、決着後に退室できるようにしたことの帰結である ——
     * 71 まではそもそも席が空かなかった。
     *
     * <p>★★<b>座らせてはいけない。</b>{@code GameState} の2人は決着した試合の2人であり、
     * そこへ第三者が着くと、<b>席と盤面の持ち主が食い違う</b>
     * (ビューは {@code state.hasPlayer} で観戦者と判定するので、
     * 座ったのに操作できない人ができる)。
     *
     * <p>★★★<b>壊し検証の軸5 はこの試験を狙っている。</b>両席が埋まったままだと
     * 「席が埋まっている」の判定が先に弾いてしまい、
     * <b>盤面の有無を見る判定を消しても誰も赤くしない</b>(65 の形)——
     * この盤面を作って初めて、その判定に番人が当たる。
     */
    @Test
    void 決着後に空いた席にはまだ座れない() throws Exception {
        GameRoom room = seatedRoom();
        Spectator spectator = room.spectate("みてるひと");
        gameService.startIfBothReady(room);
        room.getGameState().setStatus(GameStatus.FINISHED);
        String a = room.slotOfSeat(SeatId.A).orElseThrow().getPlayerId();
        room.leave(a);
        assertThat(room.slotOfSeat(SeatId.A))
                .as("★決着後は退室できるので、席は空いている")
                .isEmpty();

        assertThatThrownBy(() -> room.takeSeat(spectator.spectatorId(), SeatId.A))
                .as("★★盤面はまだ在る。座らせると席と盤面の持ち主が食い違う")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("既に対戦が始まっています");
    }

    @Test
    void 観戦できない部屋では席を立てない_退室は別の操作である() {
        GameRoom room = newRoom(false);
        String id = room.join("あるふぁ", SeatId.A).getPlayerId();

        assertThatThrownBy(() -> room.standUp(id))
                .as("★★手動モードは退室に読み替えるが、通常モードは断る(マスター確認)")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("退室してください");
        assertThat(room.slotOfSeat(SeatId.A))
                .as("★断ったのだから、席はそのままである")
                .isPresent();

        room.leave(id);
        assertThat(room.slotOfSeat(SeatId.A))
                .as("★★退室は別の口であり、こちらは通る")
                .isEmpty();
    }

    @Test
    void 観戦にも名前が要る() {
        GameRoom room = newRoom(true);
        assertThatThrownBy(() -> room.spectate("  "))
                .as("★★名前を持たないまま席に着けると、相手に「観戦者」という対戦相手が現れる")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("名前");
    }

    // ===================================================================
    // 退室
    // ===================================================================

    @Test
    void 対戦中の着席者は退室できない_観戦者はできる() throws Exception {
        GameRoom room = seatedRoom();
        Spectator spectator = room.spectate("みてるひと");
        String seatedId = room.slotOfSeat(SeatId.A).orElseThrow().getPlayerId();
        gameService.startIfBothReady(room);

        assertThatThrownBy(() -> room.leave(seatedId))
                .as("★★黙って抜けられると、相手には「相手が動かなくなった」としか見えない")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("投了");
        assertThat(room.slotOfSeat(SeatId.A)).isPresent();

        room.leave(spectator.spectatorId());
        assertThat(room.getSpectators())
                .as("★観戦者は盤面の持ち主ではないので、いつでも抜けられる")
                .isEmpty();
    }

    @Test
    void 決着したら着席者も退室できる() throws Exception {
        GameRoom room = seatedRoom();
        String seatedId = room.slotOfSeat(SeatId.A).orElseThrow().getPlayerId();
        gameService.startIfBothReady(room);
        room.getGameState().setStatus(GameStatus.FINISHED);

        room.leave(seatedId);

        assertThat(room.slotOfSeat(SeatId.A)).isEmpty();
        assertThat(room.getGameState())
                .as("★★盤面は残る。残ったほうは決着した盤面を読み続けられる")
                .isNotNull();
    }

    @Test
    void 退室すると再戦の申し込みも倒れる() throws Exception {
        GameRoom room = seatedRoom();
        gameService.startIfBothReady(room);
        room.getGameState().setStatus(GameStatus.FINISHED);
        String a = room.slotOfSeat(SeatId.A).orElseThrow().getPlayerId();

        gameService.rematch(room, a, GameService.RematchAction.OFFER);
        assertThat(room.getRematchOfferedBy()).isEqualTo(a);

        room.leave(a);
        assertThat(room.getRematchOfferedBy())
                .as("★★旗を立てた人が居なくなったら、旗も倒れる(誰も答えられない問いを残さない)")
                .isNull();
    }

    // ===================================================================
    // 投了
    // ===================================================================

    @Test
    void 投了すると相手の勝ちになる() {
        AutoGameFixture f = new AutoGameFixture(cards, WIND_LEADER, WATER_LEADER);

        gameService.concede(f.room(), "me");

        assertThat(f.state().getStatus()).isEqualTo(GameStatus.FINISHED);
        assertThat(f.state().getWinnerPlayerId()).isEqualTo("you");
        assertThat(f.room().getLog()).anyMatch(line -> line.contains("投了"));
    }

    @Test
    void 投了は相手のターン中でも押せる() {
        AutoGameFixture f = new AutoGameFixture(cards, WIND_LEADER, WATER_LEADER);
        f.state().setTurnPlayerId("you");

        // ★★★<b>requireTurnPlayer を通さない。</b>詰まったときの逃げ道は、
        //   詰まりの原因になっている規則に左右されてはいけない
        gameService.concede(f.room(), "me");

        assertThat(f.state().getWinnerPlayerId()).isEqualTo("you");
    }

    @Test
    void 投了は割り込み待ちでも押せて_待ちはたたまれる() {
        AutoGameFixture f = new AutoGameFixture(cards, WIND_LEADER, WATER_LEADER);
        f.me().enqueuePendingChoice(new com.example.qte.effect.PendingChoice(
                com.example.qte.effect.PendingChoice.Kind.MINION,
                List.of("m1"), 1, 1, com.example.qte.effect.ResumePoint.SUMMON_TARGETS,
                "1体選んでください", List.of(), null, null));
        assertThat(f.me().getPendingChoice()).isNotNull();

        gameService.concede(f.room(), "me");

        assertThat(f.state().getStatus()).isEqualTo(GameStatus.FINISHED);
        assertThat(f.me().getPendingChoice())
                .as("★★★決着したら答えようのない待ちは残さない。"
                        + "残すと決着後の画面に問いと [確定] が出続け、しかも押せば通る")
                .isNull();
        assertThat(f.you().getPendingChoice()).isNull();
    }

    @Test
    void 決着した対戦には投了できない() {
        AutoGameFixture f = new AutoGameFixture(cards, WIND_LEADER, WATER_LEADER);
        gameService.concede(f.room(), "me");

        assertThatThrownBy(() -> gameService.concede(f.room(), "you"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("既に決着");
        assertThat(f.state().getWinnerPlayerId())
                .as("★2回目の投了で勝者がひっくり返らない")
                .isEqualTo("you");
    }

    @Test
    void 観戦者は投了できない() throws Exception {
        GameRoom room = seatedRoom();
        Spectator spectator = room.spectate("みてるひと");
        gameService.startIfBothReady(room);

        assertThatThrownBy(() ->
                gameService.concede(room, spectator.spectatorId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("プレイヤーではありません");
        assertThat(room.getGameState().getStatus()).isNotEqualTo(GameStatus.FINISHED);
    }

    // ===================================================================
    // 再戦
    // ===================================================================

    @Test
    void 再戦は申し込みと承諾の2段である() throws Exception {
        GameRoom room = seatedRoom();
        gameService.startIfBothReady(room);
        room.getGameState().setStatus(GameStatus.FINISHED);
        String a = room.slotOfSeat(SeatId.A).orElseThrow().getPlayerId();
        String b = room.slotOfSeat(SeatId.B).orElseThrow().getPlayerId();

        gameService.rematch(room, a, GameService.RematchAction.OFFER);
        assertThat(room.getGameState())
                .as("★★申し込んだだけでは盤面は消えない —— 相手はまだ読んでいるかもしれない")
                .isNotNull();

        gameService.rematch(room, b, GameService.RematchAction.ACCEPT);
        assertThat(room.getGameState()).isNull();
        assertThat(room.getRematchOfferedBy()).isNull();
        assertThat(room.getDiceWinnerId()).isNull();
        assertThat(room.slotOfSeat(SeatId.A).orElseThrow().isDeckLoaded())
                .as("★マスター指定: デッキを読み込み直して再戦する")
                .isFalse();
        assertThat(room.slotOfSeat(SeatId.B).orElseThrow().isDeckLoaded()).isFalse();
        assertThat(room.slotOfSeat(SeatId.A).orElseThrow().isReady())
                .as("★★ready は「購読が済んでいるか」という事実である。再戦で購読は切れない")
                .isTrue();
        assertThat(room.bothReady())
                .as("★デッキが外れているので、まだ始まらない")
                .isFalse();
    }

    @Test
    void 再戦に応じるとログも消える() throws Exception {
        GameRoom room = seatedRoom();
        gameService.startIfBothReady(room);
        room.getGameState().setStatus(GameStatus.FINISHED);
        String a = room.slotOfSeat(SeatId.A).orElseThrow().getPlayerId();
        String b = room.slotOfSeat(SeatId.B).orElseThrow().getPlayerId();
        assertThat(room.getLog()).anyMatch(line -> line.contains("ダイス"));

        gameService.rematch(room, a, GameService.RematchAction.OFFER);
        gameService.rematch(room, b, GameService.RematchAction.ACCEPT);

        assertThat(room.getLog())
                .as("★★60行のリングバッファである。残すと前の試合の行が新しい試合に混ざる")
                .noneMatch(line -> line.contains("ダイス"));
        assertThat(room.getLog()).anyMatch(line -> line.contains("再戦"));
    }

    @Test
    void 自分の申し込みには自分で答えられない() throws Exception {
        GameRoom room = seatedRoom();
        gameService.startIfBothReady(room);
        room.getGameState().setStatus(GameStatus.FINISHED);
        String a = room.slotOfSeat(SeatId.A).orElseThrow().getPlayerId();

        gameService.rematch(room, a, GameService.RematchAction.OFFER);

        assertThatThrownBy(() ->
                gameService.rematch(room, a, GameService.RematchAction.ACCEPT))
                .as("★★答えられると、2段(申し込み → 承諾)にした意味が消える")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("自分の申し込み");
        assertThat(room.getGameState()).isNotNull();
    }

    @Test
    void 再戦を断ると旗だけが倒れて盤面は残る() throws Exception {
        GameRoom room = seatedRoom();
        gameService.startIfBothReady(room);
        room.getGameState().setStatus(GameStatus.FINISHED);
        String a = room.slotOfSeat(SeatId.A).orElseThrow().getPlayerId();
        String b = room.slotOfSeat(SeatId.B).orElseThrow().getPlayerId();

        gameService.rematch(room, a, GameService.RematchAction.OFFER);
        gameService.rematch(room, b, GameService.RematchAction.DECLINE);

        assertThat(room.getRematchOfferedBy()).isNull();
        assertThat(room.getGameState()).isNotNull();
        assertThat(room.slotOfSeat(SeatId.A).orElseThrow().isDeckLoaded()).isTrue();
    }

    @Test
    void 決着する前は再戦を申し込めない() throws Exception {
        GameRoom room = seatedRoom();
        gameService.startIfBothReady(room);
        String a = room.slotOfSeat(SeatId.A).orElseThrow().getPlayerId();

        assertThatThrownBy(() ->
                gameService.rematch(room, a, GameService.RematchAction.OFFER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("決着してから");
    }

    // ===================================================================
    // ビュー
    // ===================================================================

    @Test
    void 再戦の申し込みは席で配られる_観戦者にも同じ値が届く() throws Exception {
        GameRoom room = seatedRoom();
        Spectator spectator = room.spectate("みてるひと");
        gameService.startIfBothReady(room);
        room.getGameState().setStatus(GameStatus.FINISHED);
        String a = room.slotOfSeat(SeatId.A).orElseThrow().getPlayerId();
        String b = room.slotOfSeat(SeatId.B).orElseThrow().getPlayerId();

        gameService.rematch(room, a, GameService.RematchAction.OFFER);

        GameView toB = viewBuilder.build(room, b);
        GameView toSpectator = viewBuilder.build(room, spectator.spectatorId());
        assertThat(toB.room().rematchOfferedBySeat())
                .as("★★viewer 目線に加工しない。席で持てば viewerSeat と比べるだけで足りる")
                .isEqualTo("A");
        assertThat(toB.room().rematchOfferedByName()).isEqualTo("あるふぁ");
        assertThat(toSpectator.room().rematchOfferedBySeat())
                .as("★★誰が申し込んだかは部屋の公開情報である。観戦者にも同じ値が届く")
                .isEqualTo("A");
        assertThat(toSpectator.room().viewerSeat()).isNull();
    }

    @Test
    void 盤面が在るあいだビューのstatusはWAITINGにならない() throws Exception {
        // ★★★<b>battle.js の renderRoomControls がこの一致に乗っている。</b>
        //   あちらは status === 'WAITING' を「盤面がまだ無い」と読んでボタンを出し分ける。
        //   GameService.startIfBothReady が SETUP を<b>盤面を部屋に載せる前に</b>立てるので、
        //   WAITING の盤面が配信される瞬間は存在しない ——
        //   順序が入れ替わると、あちらの分岐が黙って1つ増える(WAITING なのに席を動かせる)。
        GameRoom room = seatedRoom();
        String a = room.slotOfSeat(SeatId.A).orElseThrow().getPlayerId();

        assertThat(viewBuilder.build(room, a).status())
                .as("★盤面が無い間は WAITING である")
                .isEqualTo(GameStatus.WAITING.name());

        gameService.startIfBothReady(room);

        assertThat(room.getGameState()).isNotNull();
        assertThat(viewBuilder.build(room, a).status())
                .as("★★盤面が載った瞬間から WAITING ではない")
                .isNotEqualTo(GameStatus.WAITING.name());
    }

    @Test
    void 席を立った人はもう配信の宛先ではない() {
        // ★★<b>GameBroadcaster は席と観戦者を回る。</b>片方に残っていると2回届き、
        //   どちらからも消えていると1回も届かない。ここは前者を測る。
        GameRoom room = newRoom(true);
        String id = room.join("あるふぁ", SeatId.A).getPlayerId();

        room.standUp(id);

        long asSlot = room.getSlots().stream()
                .filter(s -> s.getPlayerId().equals(id)).count();
        long asSpectator = room.getSpectators().stream()
                .filter(s -> s.spectatorId().equals(id)).count();
        assertThat(asSlot + asSpectator)
                .as("★★★宛先はちょうど1つでなければならない")
                .isEqualTo(1);
    }

    @Test
    void 決着で保留していた戦闘とターンの受け渡しもたたまれる() {
        AutoGameFixture f = new AutoGameFixture(cards, WIND_LEADER, WATER_LEADER);
        f.state().setTurnHandoffPending(true);
        f.state().setPendingNextPlayerId("you");
        f.state().setResolvingCardId("QTE-M-WIND-2");

        PlayerState winner = f.you();
        gameService.concede(f.room(), winner.getPlayerId().equals("you") ? "me" : "you");

        assertThat(f.state().isTurnHandoffPending()).isFalse();
        assertThat(f.state().getPendingNextPlayerId()).isNull();
        assertThat(f.state().getPendingAttack()).isNull();
        assertThat(f.state().getResolvingCardId())
                .as("★「プレイ中のカード」も残さない(70 の面が決着後に出続ける)")
                .isNull();
    }
}
