package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.game.GameService;
import com.example.qte.game.GameState;
import com.example.qte.game.GameStatus;
import com.example.qte.game.ManaCard;
import com.example.qte.game.PlayerState;
import com.example.qte.game.TurnPhase;
import com.example.qte.game.view.GameViewBuilder;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.room.GameRoom;
import com.example.qte.room.GameRoomManager;
import com.example.qte.room.GameRoomOptions;
import com.example.qte.room.PlayerSlot;
import com.example.qte.room.SeatId;
import com.example.qte.room.Spectator;
import com.example.qte.support.AutoGameFixture;
import com.example.qte.support.CapturingTemplate;
import com.example.qte.web.GameBroadcaster;
import com.example.qte.web.GameBroadcaster.GameStep;
import com.example.qte.web.GameBroadcaster.WsMessage;
import com.example.qte.web.GameWsController;
import com.example.qte.web.StepRecorder;

/**
 * Batch 84a。<b>通常モードの解決を段に分ける</b>(裁定362〜370)の番人。
 *
 * <h2>0. この試験が守っているもの</h2>
 *
 * 本バッチの中心命題は<b>「ログ行が1つ増えるたびに1段である」</b>(裁定362)である。
 * ★<b>これが実装のどこかで静かに崩れたときに落ちるのは、項目3(段の数 = ログの増加行数)だけ</b>である。
 *
 * <p>★★<b>根拠にしたものにも番人を置く</b>(83 の教訓)——
 * 「配信の口は増やしていない」「記録係は居残らない」は、どちらも<b>言っただけでは守られない</b>。
 *
 * <h2>1. 段の上限は 8 である(裁定370)</h2>
 *
 * ★<b>クライアントの上限(累計時間・裁定364)とは別の上限である。</b>
 * こちらは<b>配信量</b>を守っており、実測では上限が無いと最悪 511KB になる
 * (`notes/batch84a-design-notes.md` 2-2)。
 */
@SpringBootTest
class Batch84aStepTest {

    @Autowired
    private CardMasterRepository cards;
    @Autowired
    private GameService game;
    @Autowired
    private GameViewBuilder viewBuilder;
    @Autowired
    private GameRoomManager roomManager;

    private static final String VANILLA = "QTE-M-WIND-2";
    /** ★実測で最も段数が多かったカード(24段)。`notes/batch84a-design-notes.md` 2-1 */
    private static final String LONGEST_CHAIN = "QTE-M-EARTH-7";

    // ===================================================================
    // 段 = ログ行(裁定362)
    // ===================================================================

    @Test
    @DisplayName("★★★段の数は、ログが増えた行数とちょうど同じである(裁定362・本命の番人)")
    void stepCountEqualsLogGrowth() {
        AutoGameFixture f = bigBoard();
        int before = f.room().getLog().size();
        List<String> lines = new ArrayList<>();
        f.room().setStepRecorder(lines::add);
        game.playCard(f.room(), "me", f.giveHand(f.me(), LONGEST_CHAIN), List.of(), false);
        f.room().setStepRecorder(null);

        assertThat(f.room().getLog().size() - before).isEqualTo(lines.size());
        assertThat(lines).hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("★★配信された段の並びは、ログの並びとまったく同じである(項目2)")
    void stepOrderEqualsLogOrder() {
        // ★★★<b>ここは「配信された段」で測る。</b>
        //   {@code GameRoom} の記録係を試験が自分で差し込んで測ると、
        //   <b>StepRecorder が並びを崩しても1本も落ちない</b> ——
        //   壊し検証の軸6 が NG を返して、それを教えた(80・83 の教訓:
        //   NG は「番人が足りない」ことも教える)。
        Fixture w = wired();
        w.giveManaAndBoard();
        int logBefore = w.room.getLog().size();

        w.controller.playCard(w.room.getRoomId(), new GameWsController.PlayCardRequest(
                w.meId, w.hand(LONGEST_CHAIN), List.of(), false, List.of(), List.of()));

        List<GameStep> steps = w.template.lastTo(w.room.getRoomId(), w.meId).steps();
        List<String> expected = w.room.getLog().subList(logBefore, logBefore + steps.size());
        assertThat(steps.stream().map(GameStep::logLine).toList())
                .containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("段の数は、ログが増えた行数と一致する(記録係の層で測る)")
    void recorderSeesEveryLogLine() {
        AutoGameFixture f = bigBoard();
        int before = f.room().getLog().size();
        List<String> lines = new ArrayList<>();
        f.room().setStepRecorder(lines::add);
        game.playCard(f.room(), "me", f.giveHand(f.me(), LONGEST_CHAIN), List.of(), false);
        f.room().setStepRecorder(null);

        assertThat(lines)
                .containsExactlyElementsOf(f.room().getLog().subList(before, before + lines.size()));
    }

    @Test
    @DisplayName("記録係が居なければ、ログは今までどおり積まれるだけである")
    void logStillWorksWithoutRecorder() {
        AutoGameFixture f = bigBoard();
        int before = f.room().getLog().size();
        game.playCard(f.room(), "me", f.giveHand(f.me(), VANILLA), List.of(), false);

        assertThat(f.room().getLog().size()).isGreaterThan(before);
        assertThat(f.room().isRecordingSteps()).isFalse();
    }

    // ===================================================================
    // 上限で畳む(裁定370・368)
    // ===================================================================

    @Test
    @DisplayName("★★★8段を超えた連鎖は、9段目から畳まれる(裁定370)")
    void foldsBeyondLimit() {
        Fixture w = wired();
        w.giveManaAndBoard();
        int handIndex = w.hand(LONGEST_CHAIN);
        int logBefore = w.room.getLog().size();

        w.controller.playCard(w.room.getRoomId(), new GameWsController.PlayCardRequest(
                w.meId, handIndex, List.of(), false, List.of(), List.of()));

        WsMessage message = w.template.lastTo(w.room.getRoomId(), w.meId);
        assertThat(message.type()).isEqualTo("VIEW");
        assertThat(message.steps()).hasSize(StepRecorder.LIMIT);
        assertThat(message.foldedSteps()).isGreaterThan(0);
        assertThat(message.steps().size() + message.foldedSteps())
                .isEqualTo(w.room.getLog().size() - logBefore);
    }

    @Test
    @DisplayName("★★畳んでも、ログ行は1行も欠けない(裁定368)")
    void foldingKeepsEveryLogLine() {
        Fixture w = wired();
        w.giveManaAndBoard();
        int logBefore = w.room.getLog().size();

        w.controller.playCard(w.room.getRoomId(), new GameWsController.PlayCardRequest(
                w.meId, w.hand(LONGEST_CHAIN), List.of(), false, List.of(), List.of()));

        WsMessage message = w.template.lastTo(w.room.getRoomId(), w.meId);
        int grown = w.room.getLog().size() - logBefore;
        assertThat(grown).isGreaterThan(StepRecorder.LIMIT);
        assertThat(message.view().log()).containsAll(
                w.room.getLog().subList(logBefore, logBefore + grown));
    }

    @Test
    @DisplayName("段の通し番号は1から詰まっている(飛び番を作らない)")
    void seqIsDense() {
        Fixture w = wired();
        w.giveManaAndBoard();
        w.controller.playCard(w.room.getRoomId(), new GameWsController.PlayCardRequest(
                w.meId, w.hand(LONGEST_CHAIN), List.of(), false, List.of(), List.of()));

        List<GameStep> steps = w.template.lastTo(w.room.getRoomId(), w.meId).steps();
        for (int i = 0; i < steps.size(); i++) {
            assertThat(steps.get(i).seq()).isEqualTo(i + 1);
        }
    }

    // ===================================================================
    // 記録係の着脱(項目10)
    // ===================================================================

    @Test
    @DisplayName("★★★操作を抜けたら、記録係は必ず外れている(項目10)")
    void recorderIsAlwaysRemoved() {
        Fixture w = wired();
        w.giveManaAndBoard();
        w.controller.playCard(w.room.getRoomId(), new GameWsController.PlayCardRequest(
                w.meId, w.hand(VANILLA), List.of(), false, List.of(), List.of()));

        assertThat(w.room.isRecordingSteps()).isFalse();
    }

    @Test
    @DisplayName("★★★ルール違反で拒否された操作でも、記録係は外れている(項目10・例外の経路)")
    void recorderIsRemovedOnRuleViolation() {
        Fixture w = wired();
        w.giveManaAndBoard();

        // 手番ではない側から撃つ。★execute の catch を通る
        w.controller.playCard(w.room.getRoomId(), new GameWsController.PlayCardRequest(
                w.youId, 0, List.of(), false, List.of(), List.of()));

        assertThat(w.room.isRecordingSteps()).isFalse();
        assertThat(w.template.lastTo(w.room.getRoomId(), w.youId).type()).isEqualTo("ERROR");
    }

    @Test
    @DisplayName("★★前の操作の段が、次の操作に混ざらない")
    void stepsDoNotLeakBetweenOperations() {
        Fixture w = wired();
        w.giveManaAndBoard();
        w.controller.playCard(w.room.getRoomId(), new GameWsController.PlayCardRequest(
                w.meId, w.hand(LONGEST_CHAIN), List.of(), false, List.of(), List.of()));
        int first = w.template.lastTo(w.room.getRoomId(), w.meId).steps().size();

        w.controller.playCard(w.room.getRoomId(), new GameWsController.PlayCardRequest(
                w.meId, w.hand(VANILLA), List.of(), false, List.of(), List.of()));
        List<GameStep> second = w.template.lastTo(w.room.getRoomId(), w.meId).steps();

        assertThat(first).isEqualTo(StepRecorder.LIMIT);
        assertThat(second).hasSizeLessThan(StepRecorder.LIMIT);
    }

    // ===================================================================
    // 退化の経路(項目9)と席ごとの視点
    // ===================================================================

    @Test
    @DisplayName("★段を1つも作らない操作でも、VIEW は従来どおり届く(項目9・退化の経路)")
    void viewStillArrivesWithoutSteps() {
        Fixture w = wired();
        w.room.spectate("みるひと");
        w.template.clear();

        w.controller.ready(w.room.getRoomId(),
                new GameWsController.ActionRequest(w.meId), "session-me");

        WsMessage message = w.template.lastTo(w.room.getRoomId(), w.meId);
        assertThat(message.type()).isEqualTo("VIEW");
        assertThat(message.steps()).isEmpty();
        assertThat(message.foldedSteps()).isZero();
        assertThat(message.view()).isNotNull();
    }

    @Test
    @DisplayName("★★段のビューも、席ごとに視点が違う(設計判断9・フィルタは1本)")
    void stepViewsAreFilteredPerViewer() {
        Fixture w = wired();
        w.giveManaAndBoard();
        w.controller.playCard(w.room.getRoomId(), new GameWsController.PlayCardRequest(
                w.meId, w.hand(LONGEST_CHAIN), List.of(), false, List.of(), List.of()));

        List<GameStep> mine = w.template.lastTo(w.room.getRoomId(), w.meId).steps();
        List<GameStep> yours = w.template.lastTo(w.room.getRoomId(), w.youId).steps();
        assertThat(mine).hasSameSizeAs(yours);
        // ★★★<b>「自分の側」が席ごとに入れ替わっていることを測る。</b>
        //   手札が隠れているかだけを見ると、<b>全員に同じ視点を配っても落ちない</b> ——
        //   壊し検証の軸8 が NG を返して、それを教えた。
        for (int i = 0; i < mine.size(); i++) {
            assertThat(mine.get(i).view().you().displayName()).isEqualTo("わたし");
            assertThat(mine.get(i).view().opponent().displayName()).isEqualTo("あいて");
            assertThat(yours.get(i).view().you().displayName()).isEqualTo("あいて");
            assertThat(yours.get(i).view().opponent().displayName()).isEqualTo("わたし");
        }
        assertThat(mine.get(0).view().you().hand()).isNotNull();
        assertThat(yours.get(0).view().opponent().hand()).isNullOrEmpty();
    }

    @Test
    @DisplayName("★観戦者にも同じ段数が届く(裁定366・席で分岐していない)")
    void spectatorGetsTheSameSteps() {
        Fixture w = wired();
        Spectator watcher = w.room.spectate("みるひと");
        w.giveManaAndBoard();
        w.controller.playCard(w.room.getRoomId(), new GameWsController.PlayCardRequest(
                w.meId, w.hand(LONGEST_CHAIN), List.of(), false, List.of(), List.of()));

        assertThat(w.template.lastTo(w.room.getRoomId(), watcher.spectatorId()).steps())
                .hasSameSizeAs(w.template.lastTo(w.room.getRoomId(), w.meId).steps());
    }

    // ===================================================================
    // 足場
    // ===================================================================

    private AutoGameFixture bigBoard() {
        AutoGameFixture f = new AutoGameFixture(cards, "QTE-M-EARTH-1", "QTE-M-DARK-1");
        f.giveMana(f.me(), 20);
        f.fillDeck(f.me(), 30);
        f.fillDeck(f.you(), 30);
        for (int i = 0; i < 5; i++) {
            f.putOnField(f.me(), VANILLA);
        }
        for (int i = 0; i < 6; i++) {
            f.putOnField(f.you(), VANILLA);
        }
        return f;
    }

    /** 本物の入口({@code GameWsController})まで通す足場 */
    private final class Fixture {
        private GameRoom room;
        private String meId;
        private String youId;
        private GameWsController controller;
        private CapturingTemplate template;

        private int hand(String cardId) {
            PlayerState me = room.getGameState().playerOf(meId);
            me.getHand().add(cardId);
            return me.getHand().size() - 1;
        }

        private void giveManaAndBoard() {
            GameState state = room.getGameState();
            PlayerState me = state.playerOf(meId);
            PlayerState you = state.playerOf(youId);
            for (int i = 0; i < 20; i++) {
                me.getManaZone().add(new ManaCard(VANILLA, false));
            }
            for (int i = 0; i < 30; i++) {
                me.getDeck().addLast(VANILLA);
                you.getDeck().addLast(VANILLA);
            }
            for (int i = 0; i < 5; i++) {
                me.getMinionZone().add(new com.example.qte.game.MinionInstance(
                        cards.findById(VANILLA), 1));
            }
            for (int i = 0; i < 6; i++) {
                you.getMinionZone().add(new com.example.qte.game.MinionInstance(
                        cards.findById(VANILLA), 1));
            }
            template.clear();
        }
    }

    private Fixture wired() {
        Fixture w = new Fixture();
        w.room = roomManager.createRoom(new GameRoomOptions("試験84a", true, false));
        PlayerSlot a = w.room.join("わたし", SeatId.A);
        PlayerSlot b = w.room.join("あいて", SeatId.B);
        w.meId = a.getPlayerId();
        w.youId = b.getPlayerId();
        PlayerState me = new PlayerState(w.meId, "わたし", cards.findById("QTE-M-EARTH-1"));
        PlayerState you = new PlayerState(w.youId, "あいて", cards.findById("QTE-M-DARK-1"));
        GameState state = new GameState(w.room.getRoomId(), me, you);
        w.room.setGameState(state);
        state.setStatus(GameStatus.PLAYING);
        state.setFirstPlayerId(w.meId);
        state.setTurnPlayerId(w.meId);
        state.setTurnNumber(5);
        state.setPhase(TurnPhase.MAIN);
        w.template = new CapturingTemplate();
        w.controller = new GameWsController(roomManager, game,
                new GameBroadcaster(w.template, viewBuilder));
        return w;
    }
}
