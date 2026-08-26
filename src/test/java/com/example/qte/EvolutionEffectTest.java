package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.effect.PendingChoice;
import com.example.qte.effect.StatCalculator;
import com.example.qte.effect.TargetChoice;
import com.example.qte.game.GameService;
import com.example.qte.game.ManaCard;
import com.example.qte.game.MinionInstance;
import com.example.qte.game.PlayerState;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.Keyword;
import com.example.qte.support.AutoGameFixture;

/**
 * Batch 53 が実装した進化7枚 +《英術・スケアロック》の試験(★P3 の2本目)。
 *
 * <h2>52 と分けた理由</h2>
 *
 * {@code EvolutionEngineTest} が守っているのは<b>進化スタックという構造</b>である
 * (束・引き継ぎ・同伴・召喚酔いの免除)。こちらが守るのは
 * <b>その上に乗ったカードの挙動</b>と、53 が新しく通した2つの経路 ——
 * <b>効果から進化を出すこと</b>(《英術・スケアロック》)と
 * <b>墓地から特殊召喚すること</b>(《サモナーポップ・エンラ》)である。
 *
 * <h2>測り方の方針(48〜52 から継続)</h2>
 *
 * <ul>
 * <li>効果は<b>本物の入口</b>から起こす(裁定187)。割り込みも
 *     {@code GameService.resolveChoice} を通す —— 候補の配列を直に読まない。</li>
 * <li><b>「そうでない側」も測る</b>(裁定181)。「コレキが相手を1体に縛る」だけでは
 *     <b>誰でも1体しか出せない</b>実装でも通るので、「自分は縛られない」を並べて置く。</li>
 * <li>ドロー数は<b>山札の減り</b>で測る。</li>
 * </ul>
 */
@SpringBootTest
class EvolutionEffectTest {

    /** 常在効果を持たないリーダー(既定) */
    private static final String PLAIN_LEADER = "QTE-M-WATER-1";

    // ---- Batch 53 が実装した8枚 ----
    private static final String RAKABU = "QTE-M-WATER-31";     // 海淵獣ラカブ(3引いて1捨て)
    private static final String ZOKUSHIMU = "QTE-M-WATER-32";  // 海淵獣ゾクシム(2引く・破壊時2捨て)
    private static final String NOA = "QTE-M-DARK-30";         // リボーンライヴ・ノア(墓地から3体)
    private static final String ENRA = "QTE-M-DARK-31";        // サモナーポップ・エンラ(墓地から特殊召喚)
    private static final String NYUKIRO = "QTE-M-LIGHT-30";    // 英霊・ニュウキロ(相手のスペルを重く)
    private static final String KOREKI = "QTE-M-LIGHT-31";     // 英霊・コレキ(相手は1ターン1体)
    private static final String REIKOSHA = "QTE-M-WIND-32";    // 灰ノ霊呼者(【破壊時】持ちを2体)
    private static final String SCARELOCK = "QTE-M-LIGHT-39";  // 英術・スケアロック(効果で進化)

    // ---- 素材・道具として使う既存カード ----
    /** コスト1・1/1・【潜伏】(海獣タウギーナ)。ラカブの素材 */
    private static final String WATER_STEALTH = "QTE-M-WATER-33";
    /** コスト1・2/1・効果なし(フレア・ポーン)。水文明でない汎用の素材・蘇生の的 */
    private static final String PLAIN_MINION = "QTE-M-FIRE-2";
    /** コスト3・1/4・【守護】(カムバックキーパー)。闇文明で体力4以上 = ノアの素材 */
    private static final String DARK_HP4 = "QTE-M-DARK-35";
    /** コスト2・1/3・【守護】(ライト・シールド)。光文明・体力2以上 = ニュウキロとコレキの素材 */
    private static final String LIGHT_GUARD = "QTE-M-LIGHT-2";
    /** コスト1・1/1・【知識】(ウィンド・ペティ)。風文明 = 灰ノ霊呼者の素材 */
    private static final String WIND_PLAIN = "QTE-M-WIND-2";
    /** コスト0・1/1・【守護】+【破壊時】自分のリーダーに1ダメージ(支援盾機狸) */
    private static final String ON_DESTROY_MINION = "QTE-M-FIRE-33";
    /** コスト1・ミニオン1体に3ダメージ(マグマ・ストレート)。破壊の道具・支払い用マナの中身 */
    private static final String MAGMA = "QTE-M-FIRE-10";
    /** コスト2・カードを2枚引く(スプラッシュ・ドロー)。相手のスペルのコストを測る的 */
    private static final String SPLASH_DRAW = "QTE-M-WATER-9";

    @Autowired
    private CardMasterRepository cards;

    @Autowired
    private GameService game;

    @Autowired
    private StatCalculator stats;

    /** ★判定層を直に問う試験が1件だけある(下の「相手のターン以外は止めない」)。理由はそこに書いた */
    @Autowired
    private com.example.qte.effect.RuleGuards guards;

    private AutoGameFixture newGame() {
        AutoGameFixture f = new AutoGameFixture(cards, PLAIN_LEADER, PLAIN_LEADER);
        f.fillDeck(f.me(), 40);
        f.fillDeck(f.you(), 40);
        return f;
    }

    private static TargetChoice trash(Integer... indexes) {
        return new TargetChoice(null, null, null, List.of(indexes), null);
    }

    private static TargetChoice hand(Integer... indexes) {
        return new TargetChoice(List.of(indexes), null, null, null, null);
    }

    /** コスト支払い用のマナを n 枚置く(中身はスペル。場の数え上げに混ざらない) */
    private void payMana(PlayerState player, int count) {
        for (int i = 0; i < count; i++) {
            player.getManaZone().add(new ManaCard(MAGMA, false));
        }
    }

    /** 手札のカードを進化召喚する(素材の instanceId を並べて渡す) */
    private void evolve(AutoGameFixture f, String cardId, MinionInstance... materials) {
        evolve(f, cardId, List.of(), materials);
    }

    private void evolve(AutoGameFixture f, String cardId, List<TargetChoice> choices,
            MinionInstance... materials) {
        game.playCard(f.room(), "me", f.giveHand(f.me(), cardId), choices, false,
                List.of(java.util.Arrays.stream(materials)
                        .map(MinionInstance::getInstanceId).toArray(String[]::new)));
    }

    /** 場に居る進化ミニオン(このクラスの試験は1体ずつしか出さない) */
    private MinionInstance evolutionOnField(PlayerState player) {
        return player.getMinionZone().stream().filter(MinionInstance::isEvolution)
                .findFirst().orElseThrow();
    }

    /** 割り込みの候補のうち、指定した位置のものを選ぶ(候補の並び順ではなく中身で指す) */
    private void chooseCandidates(AutoGameFixture f, String playerId, String... candidateIds) {
        PendingChoice choice = f.state().playerOf(playerId).getPendingChoice();
        assertThat(choice).as("割り込みの問い合わせが出ていない").isNotNull();
        List<Integer> indexes = new java.util.ArrayList<>();
        for (String id : candidateIds) {
            int at = choice.candidates().indexOf(id);
            assertThat(at).as("候補に " + id + " が居ない: " + choice.candidates()).isGreaterThanOrEqualTo(0);
            indexes.add(at);
        }
        game.resolveChoice(f.room(), playerId, indexes);
    }

    /** 割り込みの候補を「先頭からn個」選ぶ */
    private void chooseFirst(AutoGameFixture f, String playerId, int n) {
        PendingChoice choice = f.state().playerOf(playerId).getPendingChoice();
        assertThat(choice).as("割り込みの問い合わせが出ていない").isNotNull();
        List<Integer> indexes = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            indexes.add(i);
        }
        game.resolveChoice(f.room(), playerId, indexes);
    }

    // ==================================================================
    // 1. 海淵獣ラカブ(QTE-M-WATER-31)
    // ==================================================================

    @Test
    void ラカブは召喚時に3枚引いてから1枚捨てる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        int deckBefore = f.me().getDeck().size();
        evolve(f, RAKABU, f.putOnField(f.me(), WATER_STEALTH));
        assertThat(f.me().getDeck()).as("3枚引く").hasSize(deckBefore - 3);
        // 引いた後に捨てる相手を選ばせる(引く前ではない)
        assertThat(f.me().getPendingChoice()).isNotNull();
        assertThat(f.me().getPendingChoice().candidates()).hasSize(f.me().getHand().size());
        int handBefore = f.me().getHand().size();
        chooseFirst(f, "me", 1);
        assertThat(f.me().getHand()).hasSize(handBefore - 1);
        assertThat(f.me().getTrash()).hasSize(1);
    }

    /** ★素材は「水文明の【潜伏】を持つミニオン」に限る(52 が登録した素材条件が生きている) */
    @Test
    void ラカブは潜伏を持たない水ミニオンを素材にできない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        MinionInstance notStealth = f.putOnField(f.me(), "QTE-M-WATER-2"); // アクア・ジェリー(潜伏なし)
        assertThatThrownBy(() -> evolve(f, RAKABU, notStealth))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================================================================
    // 2. 海淵獣ゾクシム(QTE-M-WATER-32)
    // ==================================================================

    /**
     * ★本文の前半に誘発の印が無いが<b>【召喚時】として扱う</b>(マスター裁定)。
     * 同じ水の進化である《海淵獣ラカブ》が明示的に【召喚時】と書いているので、書き分けを尊重した。
     */
    @Test
    void ゾクシムは召喚時に2枚引く() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        int deckBefore = f.me().getDeck().size();
        evolve(f, ZOKUSHIMU, f.putOnField(f.me(), PLAIN_MINION));
        assertThat(f.me().getDeck()).hasSize(deckBefore - 2);
        assertThat(f.me().getPendingChoice()).as("召喚時に捨てさせない").isNull();
    }

    @Test
    void ゾクシムは破壊されると手札を2枚捨てる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 8);
        evolve(f, ZOKUSHIMU, f.putOnField(f.me(), PLAIN_MINION));
        // 引いた2枚を含む手札から2枚捨てさせる。破壊はマグマ・ストレート(3ダメージ)で起こす
        MinionInstance zoku = evolutionOnField(f.me());
        int handBefore = f.me().getHand().size();
        game.playCard(f.room(), "me", f.giveHand(f.me(), MAGMA),
                List.of(new TargetChoice(null, List.of(zoku.getInstanceId()), null, null, null)), false);
        assertThat(f.me().getMinionZone()).as("2/1 なので3ダメージで破壊される").isEmpty();
        assertThat(f.me().getPendingChoice()).isNotNull();
        assertThat(f.me().getPendingChoice().min()).isEqualTo(2);
        chooseFirst(f, "me", 2);
        // マグマ・ストレート自身も手札を離れているので、捨てた2枚と合わせて3枚減る
        assertThat(f.me().getHand()).hasSize(handBefore - 2);
    }

    /** ★手札が2枚に満たなければ、あるだけ捨てる(裁定191・217 と同じ形) */
    @Test
    void ゾクシムは手札が1枚しかなければ1枚だけ捨てる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 8);
        evolve(f, ZOKUSHIMU, f.putOnField(f.me(), PLAIN_MINION));
        MinionInstance zoku = evolutionOnField(f.me());
        f.me().getHand().clear(); // 引いた2枚を捨てて、破壊の道具1枚だけを持たせる
        game.playCard(f.room(), "me", f.giveHand(f.me(), MAGMA),
                List.of(new TargetChoice(null, List.of(zoku.getInstanceId()), null, null, null)), false);
        assertThat(f.me().getPendingChoice()).as("手札が空なら問い合わせない").isNull();
    }

    // ==================================================================
    // 3. リボーンライヴ・ノア(QTE-M-DARK-30)
    // ==================================================================

    /**
     * ★★★Batch 68(裁定282)で<b>2手</b>になった ——
     * 【召喚時】の対象は<b>ノアが場に出てから</b>選ぶ。
     * 66 までは進化召喚の宣言と一緒に渡していた。
     *
     * <p>★これは<b>進化ミニオンでこそ効く直し</b>である。宣言時の検証は
     * 素材を場から外す<b>前</b>に走っていたので、素材にしたミニオンを
     * 対象に選べてしまう危うさが構造として残っていた(裁定258 の罠)。
     */
    @Test
    void ノアは召喚時に選んだ墓地のミニオンを3体場に出す() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 8);
        f.me().getTrash().addAll(List.of(PLAIN_MINION, PLAIN_MINION, PLAIN_MINION, PLAIN_MINION));
        evolve(f, NOA, f.putOnField(f.me(), DARK_HP4));
        f.answerChoice(game, "me", "0", "1", "2");
        assertThat(f.me().getMinionZone()).as("ノア + 蘇生3体").hasSize(4);
        assertThat(f.me().getTrash()).as("4枚のうち3枚が墓地を離れた").hasSize(1);
    }

    /**
     * ★【常在】は<b>自身の【召喚時】で出す3体にも乗る</b>(マスター裁定)。
     * 【召喚時】はノアが場に出た後に発動するので、その時点でノアは既に場に居る。
     */
    @Test
    void ノアの召喚時で墓地から出たミニオンは突進を得る() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 8);
        f.me().getTrash().addAll(List.of(PLAIN_MINION, PLAIN_MINION));
        evolve(f, NOA, f.putOnField(f.me(), DARK_HP4));
        f.answerChoice(game, "me", "0", "1");
        List<MinionInstance> revived = f.me().getMinionZone().stream()
                .filter(m -> PLAIN_MINION.equals(m.getMaster().id())).toList();
        assertThat(revived).hasSize(2);
        assertThat(revived).allMatch(m -> m.hasKeyword(Keyword.RUSH));
    }

    /**
     * ★<b>「そうでない側」も測る</b>(裁定181)。ノアが場に居なければ【突進】は付かない ——
     * これが無いと「墓地から出たものは常に【突進】」の実装でも通る。
     */
    @Test
    void ノアが場に居なければ墓地から出たミニオンは突進を得ない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        f.me().getTrash().add(PLAIN_MINION);
        // ★【突進】を自分で配らない蘇生を選ぶ —— 《死者蘇生》は蘇生した1体に
        //   自前で【突進】を与えるので、この試験の相手にすると何も測れない(裁定181)。
        //   《サモンズライト》の【破壊時】は墓地からコスト1のミニオンを出すだけである
        MinionInstance summonsLight = f.putOnField(f.me(), "QTE-M-DARK-34");
        game.playCard(f.room(), "me", f.giveHand(f.me(), MAGMA),
                List.of(new TargetChoice(null, List.of(summonsLight.getInstanceId()),
                        null, null, null)), false);
        MinionInstance revived = f.me().getMinionZone().get(0);
        assertThat(revived.getMaster().id()).isEqualTo(PLAIN_MINION);
        assertThat(revived.hasKeyword(Keyword.RUSH)).isFalse();
    }

    /** ★同じ蘇生でも、ノアが場に居れば【突進】が付く(上の試験と対になる) */
    @Test
    void ノアが場に居れば他の効果で墓地から出たミニオンも突進を得る() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 9);
        // ★★Batch 68: 墓地が空なので【召喚時】の候補は0件 —— 問い合わせは立たない(裁定302)
        evolve(f, NOA, f.putOnField(f.me(), DARK_HP4));
        f.me().getTrash().add(PLAIN_MINION);
        MinionInstance summonsLight = f.putOnField(f.me(), "QTE-M-DARK-34");
        game.playCard(f.room(), "me", f.giveHand(f.me(), MAGMA),
                List.of(new TargetChoice(null, List.of(summonsLight.getInstanceId()),
                        null, null, null)), false);
        MinionInstance revived = f.me().getMinionZone().stream()
                .filter(m -> PLAIN_MINION.equals(m.getMaster().id())).findFirst().orElseThrow();
        assertThat(revived.hasKeyword(Keyword.RUSH)).isTrue();
    }

    /**
     * 墓地に3体居なければ、居るだけ出す(裁定191)。
     *
     * <p>★★Batch 68: 墓地が1枚しか無い場合、候補も1件になる。
     * ノアの要求は {@code upTo}(0〜3体)なので<b>「選ばない」も選択肢である</b> ——
     * 自動では決まらず、ちゃんと問い合わせが立つ(裁定302 の裏返し)。
     */
    @Test
    void ノアは墓地が足りなければ居るだけ出す() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 8);
        f.me().getTrash().add(PLAIN_MINION);
        evolve(f, NOA, f.putOnField(f.me(), DARK_HP4));
        f.answerChoice(game, "me", "0");
        assertThat(f.me().getMinionZone()).hasSize(2);
    }

    // ==================================================================
    // 4. サモナーポップ・エンラ(QTE-M-DARK-31)
    // ==================================================================

    /** 墓地にミニオンを n 体積む(エンラの特殊召喚条件を満たすため) */
    private void fillTrashWithMinions(PlayerState player, int count) {
        for (int i = 0; i < count; i++) {
            player.getTrash().add(PLAIN_MINION);
        }
    }

    @Test
    void エンラは登場時に相手のコスト3以下のミニオンを1体破壊する() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        MinionInstance victim = f.putOnField(f.you(), PLAIN_MINION); // コスト1
        f.putOnField(f.you(), "QTE-M-DARK-8"); // 冥界神ハデス(コスト8)は候補にならない
        evolve(f, ENRA, f.putOnField(f.me(), PLAIN_MINION));
        assertThat(f.me().getPendingChoice()).isNotNull();
        assertThat(f.me().getPendingChoice().candidates())
                .as("コスト3以下だけが候補").containsExactly(victim.getInstanceId());
        chooseCandidates(f, "me", victim.getInstanceId());
        assertThat(f.you().getMinionZone()).hasSize(1);
        assertThat(f.you().getMinionZone().get(0).getMaster().id()).isEqualTo("QTE-M-DARK-8");
    }

    /** ★【潜伏】持ちは相手の効果の対象にならない(既存の原則を候補の作り方で守る) */
    @Test
    void エンラは相手の潜伏持ちを候補にしない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        f.putOnField(f.you(), WATER_STEALTH); // コスト1だが【潜伏】
        evolve(f, ENRA, f.putOnField(f.me(), PLAIN_MINION));
        assertThat(f.me().getPendingChoice()).as("候補が0なら問い合わせない").isNull();
        assertThat(f.you().getMinionZone()).hasSize(1);
    }

    /**
     * ★<b>墓地からの特殊召喚</b>(53 が新しく通した経路)。
     * 墓地にミニオンが6体以上のとき、コスト1で場に出せる。
     * ★<b>墓地に居るエンラ自身も6体に数える</b>(マスター裁定)ので、
     * 「エンラ + ミニオン5体」で条件を満たす。
     */
    @Test
    void エンラは墓地から特殊召喚できる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        fillTrashWithMinions(f.me(), 5);
        f.me().getTrash().add(ENRA); // 墓地の6枚目 = エンラ自身
        MinionInstance material = f.putOnField(f.me(), PLAIN_MINION);
        int trashIndex = f.me().getTrash().indexOf(ENRA);
        game.specialSummonFromGrave(f.room(), "me", trashIndex, List.of(),
                List.of(material.getInstanceId()));
        assertThat(f.fieldIds(f.me())).containsExactly(ENRA);
        assertThat(f.me().getTrash()).as("墓地から出た").hasSize(5);
        assertThat(evolutionOnField(f.me()).getUnder()).as("墓地から出しても素材は要る").hasSize(1);
        assertThat(f.me().getAvailableMp()).as("コスト1を支払う").isEqualTo(1);
    }

    /** ★墓地に5体しか居なければ(エンラ自身を入れても6に届かない)特殊召喚できない */
    @Test
    void エンラは墓地のミニオンが6体未満なら墓地から特殊召喚できない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        fillTrashWithMinions(f.me(), 4);
        f.me().getTrash().add(ENRA); // 合計5体
        MinionInstance material = f.putOnField(f.me(), PLAIN_MINION);
        int trashIndex = f.me().getTrash().indexOf(ENRA);
        assertThatThrownBy(() -> game.specialSummonFromGrave(f.room(), "me", trashIndex, List.of(),
                List.of(material.getInstanceId())))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * ★墓地から出せると宣言していないカードは、この入口を通れない。
     *
     * ★<b>比べる相手は「【特殊召喚】を持つが墓地からは出せないカード」である</b>(裁定181) ——
     * 【特殊召喚】を1つも持たないカードで測ると、{@code spec == null} の側で弾かれるだけなので
     * <b>fromGrave の判定を外しても落ちない</b>(壊し検証9番がそれを検出した)。
     */
    @Test
    void 墓地から特殊召喚できないカードはこの入口を通れない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        f.putOnField(f.me(), PLAIN_MINION);
        f.putOnField(f.me(), PLAIN_MINION);
        f.putOnField(f.me(), PLAIN_MINION); // 走太の特殊召喚条件(場に3体以上)は満たしておく
        f.me().getTrash().add("QTE-M-FIRE-32"); // 飛翔鉄人走太(手札からの特殊召喚のみ)
        assertThatThrownBy(() -> game.specialSummonFromGrave(f.room(), "me", 0, List.of(),
                List.of(f.me().getMinionZone().get(0).getInstanceId())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("墓地から特殊召喚できません");
    }

    // ==================================================================
    // 5. 英霊・ニュウキロ(QTE-M-LIGHT-30)
    // ==================================================================

    /**
     * ★増える量は<b>自分の手札の数そのもの</b>である(マスター裁定。裁定230 と同じ読み)。
     * 「自分」はニュウキロの持ち主なので、相手がスペルを唱えるときに数えるのはこちらの手札である。
     */
    @Test
    void ニュウキロは相手のスペルのコストを自分の手札の数だけ重くする() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        evolve(f, NYUKIRO, f.putOnField(f.me(), LIGHT_GUARD));
        f.me().getHand().clear();
        f.giveHand(f.me(), MAGMA);
        f.giveHand(f.me(), MAGMA);
        f.giveHand(f.me(), MAGMA); // 手札3枚
        int cost = stats.effectiveCost(f.state(), f.you(), cards.findById(SPLASH_DRAW));
        assertThat(cost).as("印刷2 + 手札3").isEqualTo(5);
    }

    /**
     * ★自分のスペルは重くならない(「相手の」と書いてある)。
     *
     * ★<b>相手にも手札を持たせる</b>(裁定181) —— 相手の手札が0枚だと、
     * 「自分の場のニュウキロを数える」という取り違えをしても増加量が 1×0 = 0 になり、
     * <b>壊れているのに通ってしまう</b>(壊し検証27番がそれを検出した)。
     */
    @Test
    void ニュウキロは自分のスペルのコストを変えない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        evolve(f, NYUKIRO, f.putOnField(f.me(), LIGHT_GUARD));
        f.me().getHand().clear();
        f.giveHand(f.me(), MAGMA);
        f.giveHand(f.me(), MAGMA);
        f.giveHand(f.you(), MAGMA);
        f.giveHand(f.you(), MAGMA); // 相手の手札も2枚(数え違いが結果に出るようにする)
        int cost = stats.effectiveCost(f.state(), f.me(), cards.findById(SPLASH_DRAW));
        assertThat(cost).isEqualTo(2);
    }

    /** ★手札が0枚なら重くならない(「1体につき+1」ではないことの証拠) */
    @Test
    void ニュウキロは自分の手札が0枚ならスペルを重くしない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        evolve(f, NYUKIRO, f.putOnField(f.me(), LIGHT_GUARD));
        f.me().getHand().clear();
        int cost = stats.effectiveCost(f.state(), f.you(), cards.findById(SPLASH_DRAW));
        assertThat(cost).isEqualTo(2);
    }

    // ==================================================================
    // 6. 英霊・コレキ(QTE-M-LIGHT-31)
    // ==================================================================

    /** 相手("you")の手番にして、メインフェイズに置く */
    private void giveTurnToOpponent(AutoGameFixture f) {
        f.state().setTurnPlayerId("you");
        f.state().setTurnNumber(f.state().getTurnNumber() + 1);
        f.state().setPhase(com.example.qte.game.TurnPhase.MAIN);
    }

    @Test
    void コレキがあると相手は自身のターンに1体しかミニオンを出せない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        evolve(f, KOREKI, f.putOnField(f.me(), LIGHT_GUARD));
        giveTurnToOpponent(f);
        payMana(f.you(), 5);
        game.playCard(f.room(), "you", f.giveHand(f.you(), PLAIN_MINION), List.of(), false);
        assertThat(f.you().getMinionZone()).hasSize(1);
        int second = f.giveHand(f.you(), PLAIN_MINION);
        assertThatThrownBy(() -> game.playCard(f.room(), "you", second, List.of(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("コレキ");
    }

    /**
     * ★<b>「そうでない側」も測る</b>(裁定181)。
     * コレキを出した本人は縛られない —— テキストが「相手は」と書いているためである。
     */
    @Test
    void コレキは自分の展開を縛らない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        evolve(f, KOREKI, f.putOnField(f.me(), LIGHT_GUARD));
        game.playCard(f.room(), "me", f.giveHand(f.me(), PLAIN_MINION), List.of(), false);
        game.playCard(f.room(), "me", f.giveHand(f.me(), PLAIN_MINION), List.of(), false);
        assertThat(f.me().getMinionZone()).as("コレキ + 2体").hasSize(3);
    }

    /**
     * ★<b>「場に出す」はあらゆる登場を数える</b>(マスター裁定)。
     * 効果による「出す」も1体で打ち止めになり、出せなかったぶんは手札に戻る ——
     * <b>場が満杯のときとまったく同じ形</b>である(神の福音・ギガマウス・バイトの既存の扱い)。
     */
    @Test
    void コレキの制限下では3体出す効果でも1体しか出ない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        evolve(f, KOREKI, f.putOnField(f.me(), LIGHT_GUARD));
        giveTurnToOpponent(f);
        payMana(f.you(), 15);
        // ギガマウス・バイト(QTE-M-WATER-38): 手札から水文明のミニオンを3体場に出す
        int a = f.giveHand(f.you(), "QTE-M-WATER-2");
        int b = f.giveHand(f.you(), "QTE-M-WATER-2");
        int c = f.giveHand(f.you(), "QTE-M-WATER-2");
        int spell = f.giveHand(f.you(), "QTE-M-WATER-38");
        game.playCard(f.room(), "you", spell, List.of(hand(a, b, c)), false);
        assertThat(f.you().getMinionZone()).as("1体だけ出る").hasSize(1);
        assertThat(f.you().getHand()).as("出せなかった2体は手札に戻る")
                .filteredOn("QTE-M-WATER-2"::equals).hasSize(2);
    }

    /**
     * ★相手のターン中の登場は止められない(本文が「自身のターン中」と限定している。裁定211)。
     *
     * ★<b>この1件だけは判定層({@link com.example.qte.effect.RuleGuards})に直接問う。</b>
     * 現行のカードプールで「相手の手番中に自分の場へミニオンが出る」経路は
     * 【破壊時】の蘇生と《カムバックキーパー》の自力復帰しかなく、
     * どちらも<b>コレキの持ち主が自分の手番に相手を殴る</b>という前提を要求する ——
     * 本物の入口から起こすと、測りたい1つの条件のまわりに8手ぶんの段取りが付いてしまう。
     * ★裁定187 が守りたいのは「発火する場所が正しいか」であり、
     * ここで測っているのは<b>判定そのものの向き</b>である(ニュウキロを
     * {@link StatCalculator} に直接問うているのと同じ扱い)。
     */
    @Test
    void コレキは相手の手番でないあいだの登場を止めない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        evolve(f, KOREKI, f.putOnField(f.me(), LIGHT_GUARD));
        // 相手("you")は既にこのターン1体出している = 手番なら止まるはずの状態
        f.you().countMinionEntry(f.state().getTurnNumber());
        assertThat(guards.minionEntryDenial(f.state(), f.you()))
                .as("相手の手番ではないので止めない").isNull();
        // 手番を渡すと同じ状態で止まる(そうでない側)
        giveTurnToOpponent(f);
        f.you().countMinionEntry(f.state().getTurnNumber());
        assertThat(guards.minionEntryDenial(f.state(), f.you()))
                .as("相手の手番なら止める").contains("コレキ");
    }

    // ==================================================================
    // 7. 灰ノ霊呼者(QTE-M-WIND-32)
    // ==================================================================

    @Test
    void 灰ノ霊呼者は召喚時に破壊時持ちを手札から2体出す() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        f.giveHand(f.me(), ON_DESTROY_MINION);
        f.giveHand(f.me(), ON_DESTROY_MINION);
        f.giveHand(f.me(), PLAIN_MINION); // 【破壊時】を持たないので候補に入らない
        evolve(f, REIKOSHA, f.putOnField(f.me(), WIND_PLAIN));
        PendingChoice choice = f.me().getPendingChoice();
        assertThat(choice).isNotNull();
        assertThat(choice.candidates()).as("【破壊時】持ちの2枚だけが候補").hasSize(2);
        chooseFirst(f, "me", 2);
        assertThat(f.fieldIds(f.me()))
                .containsExactly(REIKOSHA, ON_DESTROY_MINION, ON_DESTROY_MINION);
        assertThat(f.me().getHand()).as("出した2体は手札を離れる").containsExactly(PLAIN_MINION);
    }

    /**
     * ★「【破壊時】を持つ」の判定は<b>本文に【破壊時】と書いてあるか</b>である(マスター裁定)。
     * ★<b>この規則をクライアントに持たせていない</b>(裁定234) ——
     * 候補はサーバが絞り込んで送る。ここで測っているのはその絞り込みである。
     */
    @Test
    void 灰ノ霊呼者は破壊時を持たないミニオンを候補にしない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        f.giveHand(f.me(), PLAIN_MINION);
        f.giveHand(f.me(), MAGMA); // スペルも候補に入らない
        evolve(f, REIKOSHA, f.putOnField(f.me(), WIND_PLAIN));
        assertThat(f.me().getPendingChoice()).as("候補が0なら問い合わせない").isNull();
        assertThat(f.fieldIds(f.me())).containsExactly(REIKOSHA);
    }

    /** ★効果による「出す」なので【召喚時】は発動しない(登場時のみ) */
    @Test
    void 灰ノ霊呼者が出したミニオンの召喚時は発動しない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        // 喚ビ集ウ・アヤカシ(QTE-M-WIND-36):【召喚時】自分の他のミニオンを1体破壊する
        // ★【破壊時】の文字を持たないので、まず候補に入らないことを確かめる素材にはできない。
        //   代わりにサモンズライト(【召喚時】相手のミニオンに1ダメージ +【破壊時】)を使う
        f.giveHand(f.me(), "QTE-M-DARK-34");
        MinionInstance opponentMinion = f.putOnField(f.you(), "QTE-M-DARK-8"); // 7/7
        evolve(f, REIKOSHA, f.putOnField(f.me(), WIND_PLAIN));
        chooseFirst(f, "me", 1);
        assertThat(f.fieldIds(f.me())).contains("QTE-M-DARK-34");
        assertThat(opponentMinion.getCurrentHp()).as("【召喚時】の1ダメージは入らない").isEqualTo(7);
    }

    // ==================================================================
    // 8. 英術・スケアロック(QTE-M-LIGHT-39)。★53 の本体
    // ==================================================================

    /**
     * ★<b>効果から進化を出す初めての経路である</b>(裁定226)。
     * 1体目に出した光ミニオンを、そのまま2体目の進化の素材にできる(マスター裁定)。
     */
    @Test
    void スケアロックは出した1体目を素材にして進化を出す() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        int light = f.giveHand(f.me(), LIGHT_GUARD);
        f.giveHand(f.me(), KOREKI); // 素材条件 = 光文明のミニオン1体
        game.playCard(f.room(), "me", f.giveHand(f.me(), SCARELOCK), List.of(hand(light)), false);
        // 1体目が場に出たあと、出す進化を選ばせる
        assertThat(f.fieldIds(f.me())).containsExactly(LIGHT_GUARD);
        chooseFirst(f, "me", 1);
        // 続けて素材を選ばせる。候補は今場に居る光ミニオン(=1体目)である
        PendingChoice materialChoice = f.me().getPendingChoice();
        assertThat(materialChoice).isNotNull();
        assertThat(materialChoice.kind()).isEqualTo(PendingChoice.Kind.MINION);
        assertThat(materialChoice.candidates())
                .containsExactly(f.me().getMinionZone().get(0).getInstanceId());
        chooseFirst(f, "me", 1);
        assertThat(f.fieldIds(f.me())).containsExactly(KOREKI);
        assertThat(evolutionOnField(f.me()).getUnder().get(0).cardId()).isEqualTo(LIGHT_GUARD);
    }

    /**
     * ★素材条件が違えば、選ばれる進化も変わる(ニュウキロ =【守護】を持つ体力2以上)。
     *
     * ★<b>「効果で出した進化は【召喚時】を発動しない」はここでは測れない</b> ——
     * 光文明の進化3枚(ニュウキロ・コレキ・タイガラム)のうち、
     * <b>【召喚時】を持つものが1枚も無い</b>からである。
     * 発動しないこと自体は {@code GameActions.putIntoFieldByEffect} が
     * ON_SUMMON を1度も焚かないという構造で決まっており、
     * その経路は上の「灰ノ霊呼者が出したミニオンの召喚時は発動しない」が測っている。
     * ★空振りする試験をここに置かない(裁定186: 仕事をしていないことを区別できない判定を作らない)。
     */
    @Test
    void スケアロックは守護持ちを素材にする進化も出せる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        int light = f.giveHand(f.me(), LIGHT_GUARD); // 【守護】1/3 = ニュウキロの素材条件を満たす
        f.giveHand(f.me(), NYUKIRO);
        game.playCard(f.room(), "me", f.giveHand(f.me(), SCARELOCK), List.of(hand(light)), false);
        chooseFirst(f, "me", 1); // 出す進化 = ニュウキロ
        chooseFirst(f, "me", 1); // 素材 = ライト・シールド
        assertThat(f.fieldIds(f.me())).containsExactly(NYUKIRO);
        assertThat(evolutionOnField(f.me()).getUnder().get(0).cardId()).isEqualTo(LIGHT_GUARD);
    }

    /**
     * ★<b>素材条件を満たさない光の進化は候補にならない</b>。
     * ニュウキロは「【守護】を持つ体力2以上」を要求するので、
     * 場に居るのが【守護】を持たない光ミニオンだけなら選ばせない(マスター裁定)。
     */
    @Test
    void スケアロックは素材条件を満たさない光の進化を候補にしない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        int light = f.giveHand(f.me(), "QTE-M-LIGHT-17"); // 聖域の司祭(2/2・【知識】。守護なし)
        f.giveHand(f.me(), NYUKIRO);
        game.playCard(f.room(), "me", f.giveHand(f.me(), SCARELOCK), List.of(hand(light)), false);
        assertThat(f.fieldIds(f.me())).containsExactly("QTE-M-LIGHT-17");
        assertThat(f.me().getPendingChoice()).as("守護を持たないので素材にできない").isNull();
        assertThat(f.me().getHand()).contains(NYUKIRO);
    }

    /**
     * ★<b>素材条件を満たすミニオンが場に居ない進化は、そもそも候補に入れない</b>(マスター裁定)。
     * 裁定227(条件を満たす素材が居なければ使用できない)の効果版である。
     */
    @Test
    void スケアロックは素材を確保できない進化を候補にしない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        // 1体目を選ばない(upTo なので0枚でよい)。場に光ミニオンが居ないので
        // コレキ(素材 = 光文明のミニオン1体)は候補にならない
        f.giveHand(f.me(), KOREKI);
        game.playCard(f.room(), "me", f.giveHand(f.me(), SCARELOCK),
                List.of(new TargetChoice(List.of(), null, null, null, null)), false);
        assertThat(f.me().getPendingChoice()).isNull();
        assertThat(f.me().getMinionZone()).isEmpty();
        assertThat(f.me().getHand()).as("進化カードは手札に残る").contains(KOREKI);
    }

    /** ★手札に【進化】の光文明ミニオンが無ければ、前半だけが起きる(裁定217 と同じ形) */
    @Test
    void スケアロックは進化が手札に無くても1体目を出す() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        int light = f.giveHand(f.me(), LIGHT_GUARD);
        game.playCard(f.room(), "me", f.giveHand(f.me(), SCARELOCK), List.of(hand(light)), false);
        assertThat(f.fieldIds(f.me())).containsExactly(LIGHT_GUARD);
        assertThat(f.me().getPendingChoice()).isNull();
    }

    /** ★光文明でない進化は候補にならない(本文が「光文明ミニオン」と限定している) */
    @Test
    void スケアロックは光文明でない進化を候補にしない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        int light = f.giveHand(f.me(), LIGHT_GUARD);
        f.giveHand(f.me(), "QTE-M-FIRE-30"); // 不敗鉄人闘太(火文明。素材 = 自分のミニオン1体以上)
        game.playCard(f.room(), "me", f.giveHand(f.me(), SCARELOCK), List.of(hand(light)), false);
        assertThat(f.me().getPendingChoice()).isNull();
        assertThat(f.fieldIds(f.me())).containsExactly(LIGHT_GUARD);
    }

    /**
     * ★効果で出した進化でも<b>素材は下に置かれ、引き継ぎも同じ</b>(裁定224)。
     * 束を作る処理が {@code GameActions.attachEvolutionMaterials} 1箇所にあることの確認である。
     */
    @Test
    void スケアロックで出した進化も付与された効果を引き継ぐ() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        int light = f.giveHand(f.me(), LIGHT_GUARD);
        f.giveHand(f.me(), KOREKI);
        game.playCard(f.room(), "me", f.giveHand(f.me(), SCARELOCK), List.of(hand(light)), false);
        // 1体目に「他のカードによって付与された効果」を載せてから進化させる
        MinionInstance first = f.me().getMinionZone().get(0);
        first.addModifier(new com.example.qte.game.StatModifier(
                com.example.qte.game.StatModifier.Stat.ATTACK,
                com.example.qte.game.StatModifier.Operation.ADD, 3,
                com.example.qte.game.StatModifier.Duration.PERMANENT, "test"));
        chooseFirst(f, "me", 1);
        chooseFirst(f, "me", 1);
        MinionInstance koreki = evolutionOnField(f.me());
        assertThat(stats.effectiveAttack(f.state(), f.me(), koreki))
                .as("印刷2 + 引き継いだ+3").isEqualTo(5);
    }
}
