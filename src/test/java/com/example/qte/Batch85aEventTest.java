package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.effect.TargetChoice;
import com.example.qte.game.GameEvent;
import com.example.qte.game.GameService;
import com.example.qte.game.GameState;
import com.example.qte.game.GameStatus;
import com.example.qte.game.ManaCard;
import com.example.qte.game.MinionInstance;
import com.example.qte.game.PlayerState;
import com.example.qte.game.TurnPhase;
import com.example.qte.game.view.EventView;
import com.example.qte.game.view.GameViewBuilder;
import com.example.qte.manual.web.ManualBroadcaster;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.room.GameRoom;
import com.example.qte.room.GameRoomManager;
import com.example.qte.room.GameRoomOptions;
import com.example.qte.room.PlayerSlot;
import com.example.qte.room.SeatId;
import com.example.qte.room.Spectator;
import com.example.qte.support.CapturingTemplate;
import com.example.qte.web.GameBroadcaster;
import com.example.qte.web.GameBroadcaster.GameStep;
import com.example.qte.web.GameBroadcaster.WsMessage;
import com.example.qte.web.GameWsController;
import com.example.qte.web.GameWsController.ActionRequest;
import com.example.qte.web.GameWsController.AttackRequest;
import com.example.qte.web.GameWsController.PlayCardRequest;
import com.example.qte.web.GameWsController.TabooRequest;
import com.example.qte.web.StepRecorder;

/**
 * Batch 85a。<b>通常モードの出来事をサーバに足す</b>(裁定371〜376)の番人。
 *
 * <h2>0. この試験が守っているもの</h2>
 *
 * 84b までの演出は<b>前後2枚のビューの差分</b>から作られており、差分には「誰が誰を」が無かった。
 * 85a はサーバに<b>出来事</b>({@link GameEvent})を足した。画面は1ピクセルも変えていない。
 *
 * <p>★<b>すべて本物の入口({@link GameWsController})から起こす</b>(裁定187)。
 * 出来事は「記録係が居るとき」しか溜まらないので、<b>{@code GameService} を直接叩くと1件も出ない</b> ——
 * 記録係を差し込むのは {@code GameWsController.execute} である。
 *
 * <h2>1. 入口ごとに1本ずつ置く(76・77 の教訓)</h2>
 *
 * 取り付け点は20箇所ある(`notes/batch85a-design-notes.md` 0章)。
 * ★<b>同じ種類の出来事が複数の入口から出るときは、入口の数だけ番人を置いた</b>
 * (DAMAGE は4入口・CAST は4入口・SUMMON は2入口・HEAL は2入口・BANISH は2入口)。
 *
 * <h2>2. 視点(設計判断9・42)</h2>
 *
 * ★<b>出来事は閲覧者で分岐しない</b>(裁定373)—— 数も種類も順序も同じである。
 * ★違うのは<b>中身が届くか</b>だけであり、相手の {@code DRAW} は面を持たない。
 * ★★<b>プレイヤーIDは1文字も載らない</b> —— 宛先そのものだからである。
 */
@SpringBootTest
class Batch85aEventTest {

    @Autowired
    private CardMasterRepository cards;
    @Autowired
    private GameService game;
    @Autowired
    private GameViewBuilder viewBuilder;
    @Autowired
    private GameRoomManager roomManager;

    private static final String VANILLA = "QTE-M-WIND-2";          // ウィンド・ペティ 1/1【知識】
    private static final String LONGEST_CHAIN = "QTE-M-EARTH-7";   // 百獣の王 ベヒーモス(24段)
    private static final String BLESSED_RAIN = "QTE-M-WATER-10";   // 恵みの雨: 4回復・1枚引く
    private static final String SPLASH_DRAW = "QTE-M-WATER-9";     // スプラッシュ・ドロー: 2枚引く
    private static final String TIDAL_WAVE = "QTE-M-WATER-11";     // タイダルウェーブ: 相手のコスト4以下を手札へ
    private static final String BURNING_LIFE = "QTE-M-FIRE-11";    // 命を削る烈火: 自分に3・相手の全ミニオンに3
    private static final String EARTHQUAKE = "QTE-M-EARTH-11";     // 大地震: お互いのコスト4以下を破壊
    private static final String KENKA_JOTO = "QTE-M-EARTH-38";     // 喧嘩上等: 相手のミニオンを裏向きでマナへ
    private static final String ZOMBIE_STRIKER = "QTE-M-DARK-16";  // ゾンストライカー: 墓地の同名を全て出す
    private static final String GRAVE_GIRLS = "QTE-M-DARK-37";     // グレイヴガールズファン【賢魂：1】
    private static final String PURE_ELEMENT = "QTE-M-NONE-01";
    private static final String PEARL_TRIDENT = "QTE-M-WATER-13";  // 真珠の三叉槍(攻撃2)
    private static final String EXCALIBUR = "QTE-M-LIGHT-14";      // 聖剣 エクスカリバー(攻撃3)
    private static final String MICHAEL = "QTE-M-LIGHT-7";         // 大天使 ミカエル 4/8【守護】

    // ===================================================================
    // ATTACK(裁定371)
    // ===================================================================

    @Test
    @DisplayName("★★★ミニオンの攻撃は「誰が誰を」を運ぶ —— ATTACK は宣言の段に属する(裁定371)")
    void minionAttackCarriesWhoAndWhom() {
        Fixture w = wired(20);
        MinionInstance attacker = w.onField(w.me(), VANILLA);
        MinionInstance target = w.onField(w.you(), VANILLA);
        w.battle();

        w.controller.attack(w.roomId(), new AttackRequest(w.meId,
                attacker.getInstanceId(), target.getInstanceId()));

        GameStep declared = w.stepWithLog(w.meId, "攻撃を宣言");
        EventView attack = only(declared.events(), "ATTACK");
        assertThat(attack.src()).isEqualTo(attacker.getInstanceId());
        assertThat(attack.dst()).isEqualTo(target.getInstanceId());
        assertThat(attack.side()).isEqualTo("YOU");
        // ★相手から見ると、攻撃したのは「相手」である
        assertThat(only(w.stepWithLog(w.youId, "攻撃を宣言").events(), "ATTACK").side())
                .isEqualTo("OPPONENT");
        // ★1/1 同士なので、両方がダメージを受けて両方が破壊される
        assertThat(kinds(w.all(w.meId)))
                .containsExactly("ATTACK", "DAMAGE", "DAMAGE", "DESTROY", "DESTROY");
    }

    @Test
    @DisplayName("★★★リーダーへの攻撃は、宛先を閲覧者から見た向きで名指しする(裁定371・設計判断42)")
    void attackOnLeaderIsNamedRelatively() {
        Fixture w = wired(20);
        MinionInstance attacker = w.onField(w.me(), VANILLA);
        w.battle();

        w.controller.attack(w.roomId(), new AttackRequest(w.meId, attacker.getInstanceId(), null));

        assertThat(only(w.all(w.meId), "ATTACK").dst()).isEqualTo("leader:OPPONENT");
        assertThat(only(w.all(w.youId), "ATTACK").dst()).isEqualTo("leader:YOU");
    }

    @Test
    @DisplayName("★★★戦闘によるリーダーへのダメージも DAMAGE を出す(damageLeader を通らない地点・裁定376)")
    void combatDamageToLeaderIsRecorded() {
        // ★★設計書 3-2 の表が数えていなかった地点である(GameService.attack の LP の直接減算)
        Fixture w = wired(20);
        MinionInstance attacker = w.onField(w.me(), VANILLA);
        w.battle();

        w.controller.attack(w.roomId(), new AttackRequest(w.meId, attacker.getInstanceId(), null));

        EventView damage = only(w.all(w.meId), "DAMAGE");
        assertThat(damage.dst()).isEqualTo("leader:OPPONENT");
        assertThat(damage.amount()).isEqualTo(1);
        assertThat(damage.after()).isEqualTo(19);
        assertThat(w.stepWithLog(w.meId, "リーダーに1ダメージ").events())
                .extracting(EventView::kind).containsExactly("DAMAGE");
    }

    @Test
    @DisplayName("★★★リーダーの攻撃は src がリーダーであり、戦闘ダメージも DAMAGE を出す(leaderAttack の入口)")
    void leaderAttackUsesLeaderRef() {
        Fixture w = wired(20);
        w.me().setEquippedWeapon(cards.findById(PEARL_TRIDENT));
        w.battle();

        w.controller.leaderAttack(w.roomId(), new AttackRequest(w.meId, null, null));

        List<EventView> events = w.all(w.meId);
        EventView attack = only(events, "ATTACK");
        assertThat(attack.src()).isEqualTo("leader:YOU");
        assertThat(attack.dst()).isEqualTo("leader:OPPONENT");
        EventView damage = only(events, "DAMAGE");
        assertThat(damage.dst()).isEqualTo("leader:OPPONENT");
        assertThat(damage.amount()).isEqualTo(2);
        assertThat(damage.after()).isEqualTo(18);
    }

    @Test
    @DisplayName("★★致死の攻撃は DAMAGE → GAME_OVER で終わり、勝者の向きは閲覧者で入れ替わる")
    void lethalAttackEndsWithGameOver() {
        Fixture w = wired(20);
        MinionInstance attacker = w.onField(w.me(), VANILLA);
        w.you().setLp(1);
        w.battle();

        w.controller.attack(w.roomId(), new AttackRequest(w.meId, attacker.getInstanceId(), null));

        assertThat(kinds(w.all(w.meId))).containsExactly("ATTACK", "DAMAGE", "GAME_OVER");
        assertThat(only(w.stepWithLog(w.meId, "勝利").events(), "GAME_OVER").side()).isEqualTo("YOU");
        assertThat(only(w.all(w.youId), "GAME_OVER").side()).isEqualTo("OPPONENT");
        assertThat(only(w.all(w.youId), "GAME_OVER").dst()).isEqualTo("leader:OPPONENT");
    }

    // ===================================================================
    // DAMAGE / HEAL / DESTROY(効果の入口)
    // ===================================================================

    @Test
    @DisplayName("★効果によるダメージは、リーダーにもミニオンにも量と残りを載せる(damageLeader / damageMinion の入口)")
    void effectDamageCarriesAmountAndRemainder() {
        Fixture w = wired(20);
        MinionInstance big = w.onField(w.you(), MICHAEL); // 体力8 → 5 で残る

        w.play(BURNING_LIFE);

        List<EventView> damages = w.all(w.meId).stream().filter(e -> e.kind().equals("DAMAGE")).toList();
        assertThat(damages).hasSize(2);
        assertThat(damages.get(0).dst()).isEqualTo("leader:YOU");
        assertThat(damages.get(0).amount()).isEqualTo(3);
        assertThat(damages.get(0).after()).isEqualTo(17);
        assertThat(damages.get(1).dst()).isEqualTo(big.getInstanceId());
        assertThat(damages.get(1).after()).isEqualTo(5);
    }

    @Test
    @DisplayName("★回復は実際に増えた量を運ぶ。CAST と DRAW も同じ操作に並ぶ(healLeader の入口)")
    void healLeaderCarriesActualGain() {
        Fixture w = wired(20);
        w.me().setLp(18); // ★4回復を撃っても上限20までしか増えない

        w.play(BLESSED_RAIN);

        EventView heal = only(w.all(w.meId), "HEAL");
        assertThat(heal.dst()).isEqualTo("leader:YOU");
        assertThat(heal.amount()).isEqualTo(2);
        assertThat(heal.after()).isEqualTo(20);
        assertThat(kinds(w.all(w.meId))).containsSubsequence("CAST", "HEAL", "DRAW");
    }

    @Test
    @DisplayName("★ミニオンの回復も HEAL を出す(エクスカリバーの入口・heal の呼び出しは全体で1つ)")
    void minionHealIsRecorded() {
        Fixture w = wired(20);
        MinionInstance guard = w.onField(w.me(), MICHAEL);
        guard.takeDamage(3);
        w.me().setEquippedWeapon(cards.findById(EXCALIBUR));
        w.battle();

        w.controller.leaderAttack(w.roomId(), new AttackRequest(w.meId, null, null));

        EventView heal = only(w.all(w.meId), "HEAL");
        assertThat(heal.dst()).isEqualTo(guard.getInstanceId());
        assertThat(heal.amount()).isEqualTo(3);
        assertThat(heal.after()).isEqualTo(8);
    }

    @Test
    @DisplayName("★★破壊は、それを語るログ行の段に属する(「ログ行 X の前に起きた出来事は段 X に属する」)")
    void destroyBelongsToItsOwnLogLine() {
        Fixture w = wired(20);
        List<String> doomed = List.of(
                w.onField(w.me(), VANILLA).getInstanceId(),
                w.onField(w.you(), VANILLA).getInstanceId());

        w.play(EARTHQUAKE);

        List<GameStep> steps = w.last(w.meId).steps();
        List<String> destroyed = new ArrayList<>();
        for (GameStep step : steps) {
            for (EventView e : step.events()) {
                if (e.kind().equals("DESTROY")) {
                    assertThat(step.logLine()).contains("破壊されました");
                    // ★その段のビューの場には、もう居ない
                    assertThat(step.view().you().minions()).noneMatch(m -> m.instanceId().equals(e.dst()));
                    assertThat(step.view().opponent().minions()).noneMatch(m -> m.instanceId().equals(e.dst()));
                    destroyed.add(e.dst());
                }
            }
        }
        assertThat(destroyed).containsExactlyInAnyOrderElementsOf(doomed);
    }

    // ===================================================================
    // BANISH(裁定372)
    // ===================================================================

    @Test
    @DisplayName("★★★禁忌由来のミニオンが手札へ戻されて消滅したら BANISH である。普通に戻ったものは語らない(bounceToHand の入口)")
    void bounceOfTabooMinionIsBanish() {
        Fixture w = wired(20);
        MinionInstance taboo = w.onField(w.you(), VANILLA, true);
        w.onField(w.you(), VANILLA, false);

        w.play(TIDAL_WAVE);

        List<EventView> events = w.all(w.meId);
        EventView banish = only(events, "BANISH");
        assertThat(banish.dst()).isEqualTo(taboo.getInstanceId());
        assertThat(banish.side()).isEqualTo("OPPONENT");
        assertThat(banish.cards()).containsExactly(VANILLA);
        assertThat(kinds(events)).doesNotContain("DESTROY");
    }

    @Test
    @DisplayName("★★禁忌由来のミニオンがマナへ置かれそうになって消滅しても BANISH である(putFieldMinionIntoManaFaceDown の入口)")
    void manaPutOfTabooMinionIsBanish() {
        Fixture w = wired(20);
        MinionInstance taboo = w.onField(w.you(), VANILLA, true);

        w.play(KENKA_JOTO, new TargetChoice(null, List.of(taboo.getInstanceId()), null, null, null));

        assertThat(only(w.all(w.meId), "BANISH").dst()).isEqualTo(taboo.getInstanceId());
    }

    @Test
    @DisplayName("★破壊された禁忌由来のミニオンは DESTROY であり、BANISH を重ねない(裁定372)")
    void destroyedTabooMinionIsOnlyDestroy() {
        Fixture w = wired(20);
        w.onField(w.you(), VANILLA, true);

        w.play(EARTHQUAKE);

        assertThat(kinds(w.all(w.meId))).contains("DESTROY").doesNotContain("BANISH");
    }

    // ===================================================================
    // SUMMON / CAST(入口ごと)
    // ===================================================================

    @Test
    @DisplayName("★★召喚は SUMMON を出し、その段のビューの場に同じ instanceId が居る(summonToField の入口)")
    void summonPointsAtTheNewMinion() {
        Fixture w = wired(20);

        w.play(VANILLA);

        GameStep summoned = w.stepWithLog(w.meId, "召喚しました");
        EventView summon = only(summoned.events(), "SUMMON");
        assertThat(summoned.view().you().minions())
                .anyMatch(m -> m.instanceId().equals(summon.dst()));
        assertThat(summon.cards()).containsExactly(VANILLA);
        // ★場に出たカードは公開情報である —— 相手にも面が届く
        assertThat(only(w.all(w.youId), "SUMMON").cards()).containsExactly(VANILLA);
    }

    @Test
    @DisplayName("★★効果で場に出たミニオンも SUMMON を出す(putIntoFieldByEffect の入口)")
    void effectEntryIsSummonToo() {
        Fixture w = wired(20);
        w.me().getTrash().add(ZOMBIE_STRIKER);

        w.play(ZOMBIE_STRIKER);

        assertThat(w.all(w.meId).stream().filter(e -> e.kind().equals("SUMMON")).toList()).hasSize(2);
        assertThat(w.stepWithLog(w.meId, "効果で場に出ました").events())
                .extracting(EventView::kind).contains("SUMMON");
    }

    @Test
    @DisplayName("★★スペルの使用は CAST であり、面は相手にも観戦者にも届く(playSpell の入口)")
    void castIsPublic() {
        Fixture w = wired(20);
        Spectator watcher = w.room.spectate("みるひと");

        w.play(SPLASH_DRAW);

        assertThat(only(w.stepWithLog(w.meId, "唱えました").events(), "CAST").cards())
                .containsExactly(SPLASH_DRAW);
        assertThat(only(w.all(w.youId), "CAST").cards()).containsExactly(SPLASH_DRAW);
        assertThat(only(w.all(watcher.spectatorId()), "CAST").cards()).containsExactly(SPLASH_DRAW);
    }

    @Test
    @DisplayName("★【賢魂】の使用も CAST である(resolveSoulSpell の入口・裁定247)")
    void soulCastIsCast() {
        Fixture w = wired(20);
        int index = w.hand(GRAVE_GIRLS);

        w.controller.playSoul(w.roomId(),
                new PlayCardRequest(w.meId, index, List.of(), false, List.of(), List.of()));

        assertThat(only(w.stepWithLog(w.meId, "【賢魂：1】として唱えました").events(), "CAST").cards())
                .containsExactly(GRAVE_GIRLS);
    }

    @Test
    @DisplayName("★禁忌デッキのスペルも CAST である(playTabooCard の入口)")
    void tabooSpellIsCast() {
        Fixture w = wired(20);
        w.me().getTabooDeck().add(SPLASH_DRAW);

        w.controller.playTaboo(w.roomId(), new TabooRequest(w.meId,
                w.me().getTabooDeck().size() - 1, List.of(), List.of(), List.of()));

        assertThat(only(w.stepWithLog(w.meId, "禁忌カード").events(), "CAST").cards())
                .containsExactly(SPLASH_DRAW);
    }

    @Test
    @DisplayName("★【ピュア・エレメント】の使用も CAST である(playPureElement の入口)")
    void pureElementIsCast() {
        Fixture w = wired(5);

        w.play(PURE_ELEMENT);

        assertThat(only(w.all(w.meId), "CAST").cards()).containsExactly(PURE_ELEMENT);
    }

    // ===================================================================
    // DRAW と視点(設計判断9・42・裁定373)
    // ===================================================================

    @Test
    @DisplayName("★★★DRAW の面は引いた本人にだけ届く。相手と観戦者には枚数だけ届く")
    void drawFaceOnlyForOwner() {
        Fixture w = wired(20);
        Spectator watcher = w.room.spectate("みるひと");
        w.me().getDeck().addFirst(MICHAEL);

        w.play(BLESSED_RAIN);

        assertThat(only(w.all(w.meId), "DRAW").cards()).containsExactly(MICHAEL);
        EventView yours = only(w.all(w.youId), "DRAW");
        assertThat(yours.cards()).isEmpty();
        assertThat(yours.amount()).isEqualTo(1);
        assertThat(yours.side()).isEqualTo("OPPONENT");
        assertThat(only(w.all(watcher.spectatorId()), "DRAW").cards()).isEmpty();
    }

    @Test
    @DisplayName("★★★出来事の数・種類・順序は、自席・相手席・観戦者で同じである(裁定373)")
    void sameEventsForEveryViewer() {
        Fixture w = wired(20);
        Spectator watcher = w.room.spectate("みるひと");
        w.onField(w.you(), MICHAEL);

        w.play(BURNING_LIFE);
        List<String> mine = kinds(w.all(w.meId));

        assertThat(mine).isNotEmpty();
        assertThat(kinds(w.all(w.youId))).containsExactlyElementsOf(mine);
        assertThat(kinds(w.all(watcher.spectatorId()))).containsExactlyElementsOf(mine);
        // ★段への割り振りも同じである
        assertThat(perStepCounts(w.last(w.youId))).isEqualTo(perStepCounts(w.last(w.meId)));
    }

    @Test
    @DisplayName("★★観戦者は A 席を YOU として見る(GameViewBuilder.build と同じ向き)")
    void spectatorSeesSeatAAsYou() {
        Fixture w = wired(20);
        Spectator watcher = w.room.spectate("みるひと");

        w.play(SPLASH_DRAW); // A 席(わたし)が唱える

        assertThat(only(w.all(watcher.spectatorId()), "CAST").side()).isEqualTo("YOU");
        assertThat(w.last(watcher.spectatorId()).view().you().displayName()).isEqualTo("わたし");
    }

    @Test
    @DisplayName("★★★出来事にはプレイヤーIDが1文字も載らない(プレイヤーIDは配信の宛先である・設計判断42)")
    void noPlayerIdIsDelivered() {
        Fixture w = wired(20);
        Spectator watcher = w.room.spectate("みるひと");
        MinionInstance attacker = w.onField(w.me(), VANILLA);
        w.onField(w.you(), VANILLA);
        w.me().setEquippedWeapon(cards.findById(PEARL_TRIDENT));
        w.battle();

        w.controller.attack(w.roomId(), new AttackRequest(w.meId, attacker.getInstanceId(), null));
        w.controller.leaderAttack(w.roomId(), new AttackRequest(w.meId, null, null));

        for (String viewer : List.of(w.meId, w.youId, watcher.spectatorId())) {
            List<EventView> events = w.all(viewer);
            assertThat(events).isNotEmpty();
            for (EventView e : events) {
                String flat = String.join("|", String.valueOf(e.side()), String.valueOf(e.src()),
                        String.valueOf(e.dst()), String.join(",", e.cards()));
                assertThat(flat).doesNotContain(w.meId).doesNotContain(w.youId)
                        .doesNotContain(watcher.spectatorId());
            }
        }
    }

    // ===================================================================
    // 段との結び付け(設計書 3-3・裁定374)
    // ===================================================================

    @Test
    @DisplayName("★★★最後のログ行の後に起きた出来事は、最終状態に付く(ターン開始のドロー・closeTail)")
    void eventsAfterTheLastLogLineGoToTheFinalView() {
        Fixture w = wired(20);
        w.you().getDeck().addFirst(MICHAEL);

        w.controller.endTurn(w.roomId(), new ActionRequest(w.meId));

        WsMessage yours = w.last(w.youId);
        EventView turn = only(w.stepWithLog(w.youId, "ターン6").events(), "TURN");
        assertThat(turn.side()).isEqualTo("YOU");
        assertThat(turn.amount()).isEqualTo(6);
        // ★ドローはログを書かないので、ターン開始の段には載らず、最終状態に付く
        assertThat(yours.events()).extracting(EventView::kind).containsExactly("DRAW");
        assertThat(yours.events().get(0).cards()).containsExactly(MICHAEL);
        assertThat(w.last(w.meId).events().get(0).cards()).isEmpty();
    }

    @Test
    @DisplayName("★★★畳んだ段の出来事は落とす —— 破壊の数は、実際に破壊された数より少なく届く(裁定374)")
    void foldedStepsDropTheirEvents() {
        Fixture w = wired(20);
        List<String> field = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            field.add(w.onField(w.me(), VANILLA).getInstanceId());
        }
        for (int i = 0; i < 6; i++) {
            field.add(w.onField(w.you(), VANILLA).getInstanceId());
        }

        w.play(LONGEST_CHAIN);

        WsMessage message = w.last(w.meId);
        assertThat(message.foldedSteps()).isGreaterThan(0);
        long delivered = w.all(w.meId).stream().filter(e -> e.kind().equals("DESTROY")).count();
        long survivors = w.me().getMinionZone().stream().filter(m -> field.contains(m.getInstanceId())).count()
                + w.you().getMinionZone().stream().filter(m -> field.contains(m.getInstanceId())).count();
        assertThat(survivors).isZero();
        assertThat(delivered).isLessThan(field.size());
        // ★★畳んだ段の出来事が「ずれて」最後の段や最終状態へ載っていないこと
        assertThat(message.events()).isEmpty();
        GameStep lastStep = message.steps().get(message.steps().size() - 1);
        assertThat(lastStep.events().stream().filter(e -> e.kind().equals("DESTROY")).count())
                .isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("★★前の操作の出来事が、次の操作に混ざらない")
    void eventsDoNotLeakBetweenOperations() {
        Fixture w = wired(20);
        MinionInstance attacker = w.onField(w.me(), VANILLA);
        w.battle();
        w.controller.attack(w.roomId(), new AttackRequest(w.meId, attacker.getInstanceId(), null));
        assertThat(kinds(w.all(w.meId))).contains("ATTACK");

        w.controller.nextPhase(w.roomId(), new ActionRequest(w.meId));

        assertThat(kinds(w.all(w.meId))).doesNotContain("ATTACK", "DAMAGE");
    }

    // ===================================================================
    // 溜めの着脱(GameRoom)
    // ===================================================================

    @Test
    @DisplayName("★★記録係が居なければ出来事は溜まらない(試験や配信を伴わない経路で減らない溜めを作らない)")
    void nothingIsBufferedWithoutRecorder() {
        Fixture w = wired(20);
        w.room.recordEvent(GameEvent.turn(w.me(), 99));
        game.playCard(w.room, w.meId, w.hand(SPLASH_DRAW), List.of(), false);

        assertThat(w.room.drainEvents()).isEmpty();
    }

    @Test
    @DisplayName("★★記録係を差し替えると、前の出来事は捨てられる(居残らない)")
    void swappingRecorderClearsTheBuffer() {
        Fixture w = wired(20);
        List<String> lines = new ArrayList<>();
        Consumer<String> recorder = lines::add;
        w.room.setStepRecorder(recorder);
        w.room.recordEvent(GameEvent.turn(w.me(), 99));
        w.room.setStepRecorder(null);
        w.room.setStepRecorder(recorder);

        assertThat(w.room.drainEvents()).isEmpty();
        w.room.setStepRecorder(null);
    }

    @Test
    @DisplayName("★★記録係を差し込んだ時点で居なかった閲覧者には、最終状態の出来事も渡さない(退化の経路)")
    void unknownViewerGetsNoTailEvents() {
        Fixture w = wired(20);
        StepRecorder recorder = StepRecorder.forViewers(viewBuilder, w.room, List.of(w.meId));
        w.room.setStepRecorder(recorder);
        w.room.recordEvent(GameEvent.turn(w.me(), 99));
        recorder.closeTail();
        w.room.setStepRecorder(null);

        assertThat(recorder.eventsFor(w.meId)).extracting(EventView::kind).containsExactly("TURN");
        assertThat(recorder.eventsFor(w.youId)).isEmpty();
        assertThat(recorder.stepsFor(w.youId)).isEmpty();
    }

    @Test
    @DisplayName("★★操作を抜けたあと、部屋の溜めは空で記録係も外れている")
    void bufferIsEmptyAfterExecute() {
        Fixture w = wired(20);
        w.play(BLESSED_RAIN);

        assertThat(w.room.isRecordingSteps()).isFalse();
        assertThat(w.room.drainEvents()).isEmpty();
    }

    // ===================================================================
    // 手動モード(設計書 3-4)
    // ===================================================================

    @Test
    @DisplayName("★★手動モードの配信は出来事の欄を持たない(GameActions を通らないので構造的に巻き込まれない)")
    void manualMessageHasNoEvents() {
        assertThat(java.util.Arrays.stream(ManualBroadcaster.ManualWsMessage.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("events", "steps");
        assertThat(java.util.Arrays.stream(WsMessage.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .contains("events");
    }

    // ===================================================================
    // 足場
    // ===================================================================

    private static EventView only(List<EventView> events, String kind) {
        List<EventView> found = events.stream().filter(e -> e.kind().equals(kind)).toList();
        assertThat(found).as("%s がちょうど1件あること: %s", kind, events).hasSize(1);
        return found.get(0);
    }

    private static List<String> kinds(List<EventView> events) {
        return events.stream().map(EventView::kind).toList();
    }

    private static List<Integer> perStepCounts(WsMessage message) {
        List<Integer> counts = new ArrayList<>();
        for (GameStep step : message.steps()) {
            counts.add(step.events().size());
        }
        counts.add(message.events().size());
        return counts;
    }

    private final class Fixture {
        private GameRoom room;
        private String meId;
        private String youId;
        private GameWsController controller;
        private CapturingTemplate template;

        private String roomId() {
            return room.getRoomId();
        }

        private PlayerState me() {
            return room.getGameState().playerOf(meId);
        }

        private PlayerState you() {
            return room.getGameState().playerOf(youId);
        }

        private int hand(String cardId) {
            me().getHand().add(cardId);
            return me().getHand().size() - 1;
        }

        private MinionInstance onField(PlayerState owner, String cardId) {
            return onField(owner, cardId, false);
        }

        private MinionInstance onField(PlayerState owner, String cardId, boolean fromTaboo) {
            MinionInstance minion = new MinionInstance(cards.findById(cardId), 1, fromTaboo);
            owner.getMinionZone().add(minion);
            return minion;
        }

        private void battle() {
            room.getGameState().setPhase(TurnPhase.BATTLE);
        }

        private void play(String cardId, TargetChoice... targets) {
            int index = hand(cardId);
            controller.playCard(roomId(), new PlayCardRequest(meId, index, List.of(targets), false,
                    List.of(), List.of()));
            WsMessage mine = last(meId);
            assertThat(mine.type()).as("操作が受理されたこと: %s", mine.message()).isEqualTo("VIEW");
        }

        private WsMessage last(String viewerId) {
            return template.lastTo(roomId(), viewerId);
        }

        /** その閲覧者へ最後に届いた1通の出来事を、段の順 → 最終状態の順に並べたもの */
        private List<EventView> all(String viewerId) {
            WsMessage message = last(viewerId);
            List<EventView> events = new ArrayList<>();
            for (GameStep step : message.steps()) {
                events.addAll(step.events());
            }
            events.addAll(message.events());
            return events;
        }

        private GameStep stepWithLog(String viewerId, String fragment) {
            return last(viewerId).steps().stream()
                    .filter(s -> s.logLine().contains(fragment))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("「" + fragment + "」の段が無い: "
                            + last(viewerId).steps().stream().map(GameStep::logLine).toList()));
        }
    }

    private Fixture wired(int manaCount) {
        Fixture w = new Fixture();
        w.room = roomManager.createRoom(new GameRoomOptions("試験85a", true, false));
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
        for (int i = 0; i < manaCount; i++) {
            me.getManaZone().add(new ManaCard(VANILLA, false));
        }
        for (int i = 0; i < 30; i++) {
            me.getDeck().addLast(VANILLA);
            you.getDeck().addLast(VANILLA);
        }
        w.template = new CapturingTemplate();
        w.controller = new GameWsController(roomManager, game,
                new GameBroadcaster(w.template, viewBuilder));
        return w;
    }
}
