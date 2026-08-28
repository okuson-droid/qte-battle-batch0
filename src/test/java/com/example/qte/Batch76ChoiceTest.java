package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.effect.TargetChoice;
import com.example.qte.game.GameActions;
import com.example.qte.game.GameService;
import com.example.qte.game.ManaCard;
import com.example.qte.game.MinionInstance;
import com.example.qte.game.PlayerState;
import com.example.qte.game.view.CardView;
import com.example.qte.game.view.GameViewBuilder;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.support.AutoGameFixture;

/**
 * Batch 76。<b>「自動で選ぶ」を全部やめる</b>(裁定346〜349)と、
 * <b>使用条件を運ぶ</b>(裁定350)の試験。
 *
 * <h2>0. 母集団(★作業を始める前に固定して控えた。73 の教訓)</h2>
 *
 * 探したのは「ゾーンの<b>真部分集合</b>を、プレイヤーに選ばせずコード側の順序・基準で
 * 決めている実装」である。母集団は
 * {@code CardEffectRegistry.java} 全6013行 + {@code GameActions.java} 全体であり、
 * 文明ごとに4つに分けて並行で読み切った。結果は<b>4件 + 呼び手を失った器3件</b>:
 *
 * <table border="1">
 *   <caption>76 の着手時点で残っていた自動決定</caption>
 *   <tr><th>カード</th><th>何を自動で決めていたか</th><th>裁定</th></tr>
 *   <tr><td>マナを貪る怨霊 DARK-11</td><td>墓地の闇文明を<b>古い順</b>に2枚</td><td>346</td></tr>
 *   <tr><td>禁忌の代償 DARK-10</td><td>裏向きマナを<b>末尾から</b>1枚</td><td>347</td></tr>
 *   <tr><td>光霊・モアニール LIGHT-36</td><td>肩代わりで壊す1体を<b>並び順の先頭</b></td><td>348</td></tr>
 *   <tr><td>ホーリー・シグナル LIGHT-10</td><td>最低体力が<b>同値なら先頭</b></td><td>349</td></tr>
 *   <tr><td colspan="3">器3件({@code turnManaFaceDown} / {@code turnManaFaceUp} /
 *       {@code returnFaceUpManaToHand})は呼び手ゼロ。裁定178 により消した</td></tr>
 * </table>
 *
 * <h2>1. ★★★64 の「自動決定は1件だけ残った」は、当時から正しくなかった</h2>
 *
 * 64 は {@code AutoChoice} というクラスを退役させ、
 * 「自動決定として残るのは《ホーリー・シグナル》の1件だけ」と書き残した。
 * ★<b>その母集団は「AutoChoice を通るもの」だった</b> ——
 * 上の表の他の3件は、どれも {@code AutoChoice} を1度も通っていない。
 * ★★<b>名前で数えた母集団は、名前を通らないものを数え落とす。</b>
 *
 * <h2>2. ★裁定346 は裁定211 の読み方を狭めた</h2>
 *
 * 59 は「本文に『選び』の字が無いから選ばせない」(裁定211)と書いた。
 * ★<b>裁定211 は「本文に無い<b>限定</b>を足さない」規則であって、
 * 「本文に無い<b>選択</b>を奪ってよい」規則ではない。</b>
 * マスターの言葉では「このカードゲームで自動で選ぶということがそもそも想定されていない」。
 * ★★<b>したがってカード本文は1文字も直していない</b> ——
 * 直すべきは実装の側だったからである({@code text-impl-review.json} も動いていない)。
 *
 * <h2>3. 何をどこで測るか(設計判断45)</h2>
 *
 * ここが測るのは<b>サーバの状態</b>である ——
 * 問い合わせが立つか / 立たないか、答えた結果どこが動くか、順序が守られるか。
 * <b>画面の側</b>(条件未達の印・掴めないこと・マナのホバーと名前)は
 * verify が実測で見張る。verify のハーネスは Java を起こさないので、
 * <b>あちらにはここの1件も届かない</b>(70 の教訓)。
 */
@SpringBootTest
class Batch76ChoiceTest {

    /** 常在効果を持たないリーダー(他の試験と同じ足場) */
    private static final String PLAIN_LEADER = "QTE-M-WATER-1";
    /** マナ支払い用(スペルなので墓地・場の候補を汚さない) */
    private static final String MAGMA = "QTE-M-FIRE-10";

    private static final String MANA_WRAITH = "QTE-M-DARK-11";   // マナを貪る怨霊
    private static final String TABOO_PRICE = "QTE-M-DARK-10";   // 禁忌の代償
    private static final String HOLY_SIGNAL = "QTE-M-LIGHT-10";  // ホーリー・シグナル
    private static final String MOANIRU = "QTE-M-LIGHT-36";      // 光霊・モアニール
    private static final String MEDITATION = "QTE-M-WATER-26";   // 静寂の瞑想

    /** 闇文明のカード(墓地に積む道具)。どれもコスト4以下のミニオンではない */
    private static final String DARK_SPELL_A = "QTE-M-DARK-9";
    private static final String DARK_SPELL_B = "QTE-M-DARK-12";
    private static final String DARK_SPELL_C = "QTE-M-DARK-25";
    /** 闇文明の低コストミニオン(蘇生先) */
    private static final String DARK_MINION = "QTE-M-DARK-2";
    /** 風文明のミニオン(闇ではないので《マナを貪る怨霊》の候補にならない) */
    private static final String WIND_MINION = "QTE-M-WIND-2";

    @Autowired
    GameService game;

    @Autowired
    GameActions actions;

    @Autowired
    CardMasterRepository cards;

    @Autowired
    GameViewBuilder views;

    private AutoGameFixture newGame() {
        AutoGameFixture f = new AutoGameFixture(cards, PLAIN_LEADER, PLAIN_LEADER);
        f.fillDeck(f.me(), 30);
        f.fillDeck(f.you(), 30);
        return f;
    }

    private void payMana(PlayerState player, int count) {
        for (int i = 0; i < count; i++) {
            player.getManaZone().add(new ManaCard(MAGMA, false));
        }
    }

    /** 裏向きのマナを n 枚置く(禁忌の代償の候補) */
    private void faceDownMana(PlayerState player, String... cardIds) {
        for (String cardId : cardIds) {
            ManaCard mana = new ManaCard(cardId, false);
            mana.turnFaceDown();
            player.getManaZone().add(mana);
        }
    }

    /** 墓地の対象指定は<b>位置</b>で送る(他の試験と同じ足場) */
    private static TargetChoice trash(Integer... indexes) {
        return new TargetChoice(null, null, null, List.of(indexes), null);
    }

    private static TargetChoice minion(String instanceId) {
        return new TargetChoice(null, List.of(instanceId), null, null, null);
    }

    // ===================================================================
    // 裁定346: マナを貪る怨霊 —— 墓地から動かす2枚を本人が選ぶ
    // ===================================================================

    @Test
    @DisplayName("★★★墓地の闇が3枚以上なら、どの2枚を置くかを問う(346)")
    void 怨霊は墓地の闇が3枚以上なら問い合わせる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        f.me().getTrash().addAll(List.of(DARK_SPELL_A, DARK_SPELL_B, DARK_SPELL_C));
        int spell = f.giveHand(f.me(), MANA_WRAITH);

        game.playCard(f.room(), "me", spell, List.of(), false);

        assertThat(f.me().getPendingChoice()).as("★選ばせずに置いてはいけない").isNotNull();
        assertThat(f.me().getPendingChoice().candidates())
                .as("★候補は墓地の位置である(同名が並んでも区別できる)")
                .containsExactly("0", "1", "2");
        assertThat(f.me().getPendingChoice().min()).isEqualTo(2);
        assertThat(f.me().getPendingChoice().max()).isEqualTo(2);
    }

    @Test
    @DisplayName("★★★問い合わせている間は、まだ1枚もマナへ動いていない(346)")
    void 怨霊は答える前には何も動かさない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        f.me().getTrash().addAll(List.of(DARK_SPELL_A, DARK_SPELL_B, DARK_SPELL_C));
        int deckBefore = f.me().getDeck().size();
        int spell = f.giveHand(f.me(), MANA_WRAITH);

        game.playCard(f.room(), "me", spell, List.of(), false);

        // ★使い終えた《マナを貪る怨霊》自身も墓地へ行くので4枚である。
        //   ★★<b>候補を作った時点では墓地に居なかった</b>ので、候補は3件のままである
        //     (自分自身も闇文明なので、順序が逆なら候補に混じっていた)
        assertThat(f.me().getTrash())
                .as("★墓地の3枚は1枚も動いていない")
                .containsExactly(DARK_SPELL_A, DARK_SPELL_B, DARK_SPELL_C, MANA_WRAITH);
        assertThat(f.me().getManaZone()).as("★マナは支払い分の4枚のままである").hasSize(4);
        assertThat(f.me().getDeck().size()).as("★引くのは置いた後である").isEqualTo(deckBefore);
    }

    @Test
    @DisplayName("★★★選んだ2枚だけがマナへ行き、置いた枚数だけ引く(346・裁定273)")
    void 怨霊は選んだ2枚を置いて2枚引く() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        f.me().getTrash().addAll(List.of(DARK_SPELL_A, DARK_SPELL_B, DARK_SPELL_C));
        int deckBefore = f.me().getDeck().size();
        int spell = f.giveHand(f.me(), MANA_WRAITH);
        game.playCard(f.room(), "me", spell, List.of(), false);

        // 「古い順」なら 0・1 が選ばれる。★1・2 を選んで、先頭が残ることを測る
        f.answerChoice(game, "me", "1", "2");

        assertThat(f.me().getTrash())
                .as("★★★古い順の自動決定なら DARK_SPELL_A が消えていたはずである")
                .containsExactly(DARK_SPELL_A, MANA_WRAITH);
        assertThat(f.me().getManaZone().stream().map(ManaCard::getCardId))
                .contains(DARK_SPELL_B, DARK_SPELL_C);
        assertThat(f.me().getManaZone().subList(4, 6))
                .allSatisfy(m -> assertThat(m.isFaceUp()).as("★裏向きで置く").isFalse());
        assertThat(f.me().getDeck().size()).as("置いた枚数だけ引く").isEqualTo(deckBefore - 2);
    }

    @Test
    @DisplayName("★墓地の闇が2枚なら、選ぶ余地が無いので問わない(12b・51 からの流儀)")
    void 怨霊は墓地の闇が2枚なら問わない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        f.me().getTrash().addAll(List.of(DARK_SPELL_A, WIND_MINION, DARK_SPELL_B));
        int deckBefore = f.me().getDeck().size();
        int spell = f.giveHand(f.me(), MANA_WRAITH);

        game.playCard(f.room(), "me", spell, List.of(), false);

        assertThat(f.me().getPendingChoice()).as("★選ぶ余地が無い").isNull();
        assertThat(f.me().getTrash())
                .as("★闇ではない1枚は残る(末尾は使い終えた怨霊自身である)")
                .containsExactly(WIND_MINION, MANA_WRAITH);
        assertThat(f.me().getDeck().size()).isEqualTo(deckBefore - 2);
    }

    @Test
    @DisplayName("墓地に闇が1枚も無ければ、問わずに何も起きない(裁定273(a))")
    void 怨霊は墓地に闇が無ければ何も起きない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        f.me().getTrash().add(WIND_MINION);
        int deckBefore = f.me().getDeck().size();
        int spell = f.giveHand(f.me(), MANA_WRAITH);

        game.playCard(f.room(), "me", spell, List.of(), false);

        assertThat(f.me().getPendingChoice()).isNull();
        assertThat(f.me().getTrash()).contains(WIND_MINION);
        assertThat(f.me().getDeck().size()).as("引かない").isEqualTo(deckBefore);
    }

    // ===================================================================
    // 裁定347: 禁忌の代償 —— 破壊する裏向きマナを本人が選ぶ
    // ===================================================================

    @Test
    @DisplayName("★★★裏向きマナが2枚以上なら、どれを壊すかを問う(347)")
    void 代償は裏向きマナが2枚以上なら問い合わせる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        faceDownMana(f.me(), DARK_SPELL_A, DARK_SPELL_B);
        f.me().getTrash().add(DARK_MINION);
        int spell = f.giveHand(f.me(), TABOO_PRICE);

        game.playCard(f.room(), "me", spell, List.of(trash(0)), false);

        assertThat(f.me().getPendingChoice()).isNotNull();
        assertThat(f.me().getPendingChoice().candidates())
                .as("★候補は裏向きのマナの位置だけである(表向き3枚は入らない)")
                .containsExactly("3", "4");
    }

    @Test
    @DisplayName("★★★答える前は蘇生も起きていない —— 本文の「その後」を守る(347)")
    void 代償は答える前に蘇生しない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        faceDownMana(f.me(), DARK_SPELL_A, DARK_SPELL_B);
        f.me().getTrash().add(DARK_MINION);
        int spell = f.giveHand(f.me(), TABOO_PRICE);

        game.playCard(f.room(), "me", spell, List.of(trash(0)), false);

        assertThat(f.me().getMinionZone())
                .as("★★問い合わせは「後回し」なので、続きを書く場所を誤ると先に出てしまう")
                .isEmpty();
        assertThat(f.me().getManaZone()).as("★まだ壊していない").hasSize(5);
    }

    @Test
    @DisplayName("★★★選んだ裏向きマナが壊れ、その後に蘇生する(347)")
    void 代償は選んだ裏向きマナを壊してから蘇生する() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        faceDownMana(f.me(), DARK_SPELL_A, DARK_SPELL_B);
        f.me().getTrash().add(DARK_MINION);
        int spell = f.giveHand(f.me(), TABOO_PRICE);
        game.playCard(f.room(), "me", spell, List.of(trash(0)), false);

        // 「末尾から」の自動決定なら位置4(DARK_SPELL_B)が壊れていた。★位置3 を選ぶ
        f.answerChoice(game, "me", "3");

        assertThat(f.me().getManaZone().stream().map(ManaCard::getCardId))
                .as("★★★末尾からの自動決定なら DARK_SPELL_B が消えていたはずである")
                .containsExactly(MAGMA, MAGMA, MAGMA, DARK_SPELL_B);
        assertThat(f.me().getTrash()).as("壊れたマナは墓地へ行く").contains(DARK_SPELL_A);
        assertThat(f.fieldIds(f.me())).as("その後、墓地から場に出る").containsExactly(DARK_MINION);
    }

    @Test
    @DisplayName("★裏向きマナが1枚なら、選ぶ余地が無いので問わない(347)")
    void 代償は裏向きマナが1枚なら問わない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        faceDownMana(f.me(), DARK_SPELL_A);
        f.me().getTrash().add(DARK_MINION);
        int spell = f.giveHand(f.me(), TABOO_PRICE);

        game.playCard(f.room(), "me", spell, List.of(trash(0)), false);

        assertThat(f.me().getPendingChoice()).isNull();
        assertThat(f.me().getManaZone()).hasSize(3);
        assertThat(f.fieldIds(f.me())).containsExactly(DARK_MINION);
    }

    // ===================================================================
    // 裁定348: 光霊・モアニール —— 肩代わりで壊れる1体を本人が選ぶ
    // ===================================================================

    @Test
    @DisplayName("★★★モアニールが2体並んでいたら、どちらを壊すかを問う(348)")
    void モアニールは2体並んでいたら問い合わせる() {
        AutoGameFixture f = newGame();
        MinionInstance first = f.putOnField(f.me(), MOANIRU);
        MinionInstance second = f.putOnField(f.me(), MOANIRU);
        int hpBefore = f.me().getLp();

        actions.damageLeader(f.room(), f.me(), 5);

        assertThat(f.me().getLp()).as("肩代わりでダメージは0になる").isEqualTo(hpBefore);
        assertThat(f.me().getPendingChoice()).isNotNull();
        assertThat(f.me().getPendingChoice().candidates())
                .containsExactly(first.getInstanceId(), second.getInstanceId());
        assertThat(f.me().getMinionZone())
                .as("★★答えるまでは壊れない(問い合わせは後回しである)").hasSize(2);
    }

    @Test
    @DisplayName("★★★選んだ1体が壊れ、もう1体は残る(348)")
    void モアニールは選んだ1体が壊れる() {
        AutoGameFixture f = newGame();
        MinionInstance first = f.putOnField(f.me(), MOANIRU);
        MinionInstance second = f.putOnField(f.me(), MOANIRU);
        actions.damageLeader(f.room(), f.me(), 5);

        // ★並び順の先頭(first)ではなく second を選ぶ
        f.answerChoice(game, "me", second.getInstanceId());

        assertThat(f.me().getMinionZone()).hasSize(1);
        assertThat(f.me().getMinionZone().get(0).getInstanceId())
                .as("★★★先頭を壊す自動決定なら second が残っていたはずである")
                .isEqualTo(first.getInstanceId());
    }

    @Test
    @DisplayName("モアニールが1体だけなら、選ぶ余地が無いので問わずに壊れる(348)")
    void モアニールは1体だけなら問わない() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), MOANIRU);
        int hpBefore = f.me().getLp();

        actions.damageLeader(f.room(), f.me(), 5);

        assertThat(f.me().getPendingChoice()).isNull();
        assertThat(f.me().getMinionZone()).isEmpty();
        assertThat(f.me().getLp()).isEqualTo(hpBefore);
    }

    @Test
    @DisplayName("★★★選んだ個体が既に居なければ、残っている1体を壊す —— 肩代わりをただにしない(348)")
    void モアニールは選んだ個体が居なければ残りを壊す() {
        AutoGameFixture f = newGame();
        MinionInstance first = f.putOnField(f.me(), MOANIRU);
        MinionInstance second = f.putOnField(f.me(), MOANIRU);
        actions.damageLeader(f.room(), f.me(), 5);
        // 答える前に second が場から消える(別の効果で壊れた等)
        f.me().getMinionZone().removeIf(m -> m.getInstanceId().equals(second.getInstanceId()));

        f.answerChoice(game, "me", second.getInstanceId());

        assertThat(f.me().getMinionZone())
                .as("★★失われるのは選択であって、代償ではない").isEmpty();
        assertThat(first.getInstanceId()).isNotEqualTo(second.getInstanceId());
    }

    // ===================================================================
    // 裁定349: ホーリー・シグナル —— 最低体力が同値なら本人が選ぶ
    // ===================================================================

    @Test
    @DisplayName("★★★最低体力が同値で並んだら、どれを壊すかを問う(349)")
    void シグナルは最低体力が同値なら問い合わせる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        MinionInstance big = f.putOnField(f.you(), DARK_SPELL_B_MINION);
        MinionInstance tieA = f.putOnField(f.you(), WIND_MINION);
        MinionInstance tieB = f.putOnField(f.you(), WIND_MINION);
        int spell = f.giveHand(f.me(), HOLY_SIGNAL);

        game.playCard(f.room(), "me", spell, List.of(minion(big.getInstanceId())), false);

        assertThat(f.me().getPendingChoice()).isNotNull();
        assertThat(f.me().getPendingChoice().candidates())
                .containsExactly(tieA.getInstanceId(), tieB.getInstanceId());
        assertThat(f.you().getMinionZone())
                .as("★★答えるまでは1体も壊れない(先に確定してから破壊する)").hasSize(3);
    }

    @Test
    @DisplayName("★★★答えると、選んだ1体と最高攻撃力の1体が壊れる(349)")
    void シグナルは選んだ1体と最高攻撃力を壊す() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        MinionInstance big = f.putOnField(f.you(), DARK_SPELL_B_MINION);
        MinionInstance tieA = f.putOnField(f.you(), WIND_MINION);
        MinionInstance tieB = f.putOnField(f.you(), WIND_MINION);
        int spell = f.giveHand(f.me(), HOLY_SIGNAL);
        game.playCard(f.room(), "me", spell, List.of(minion(big.getInstanceId())), false);

        // ★並び順の先頭(tieA)ではなく tieB を選ぶ
        f.answerChoice(game, "me", tieB.getInstanceId());

        assertThat(f.you().getMinionZone()).hasSize(1);
        assertThat(f.you().getMinionZone().get(0).getInstanceId())
                .as("★★★先頭を壊す自動決定なら tieB が残っていたはずである")
                .isEqualTo(tieA.getInstanceId());
    }

    @Test
    @DisplayName("★最低体力が1体だけなら、選ぶ余地が無いので問わない(349)")
    void シグナルは最低体力が1体なら問わない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        MinionInstance big = f.putOnField(f.you(), DARK_SPELL_B_MINION);
        MinionInstance small = f.putOnField(f.you(), WIND_MINION);
        int spell = f.giveHand(f.me(), HOLY_SIGNAL);

        game.playCard(f.room(), "me", spell, List.of(minion(big.getInstanceId())), false);

        assertThat(f.me().getPendingChoice()).isNull();
        assertThat(f.you().getMinionZone()).isEmpty();
        assertThat(small.getInstanceId()).isNotEqualTo(big.getInstanceId());
    }

    // ===================================================================
    // 禁忌デッキの使用条件(不具合の修正。裁定 A6 の写し忘れ)
    // ===================================================================

    @Test
    @DisplayName("★★★禁忌デッキの《静寂の瞑想》も、そのターン1枚目でなければ使えない")
    void 禁忌の静寂の瞑想は1枚目でなければ使えない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        f.me().setPlayedCardThisTurn(true);   // 既に1枚使った後を模す
        f.me().getTabooDeck().add(MEDITATION);

        assertThatThrownBy(() -> game.playTabooCard(f.room(), "me", 0, List.of(), List.of()))
                .as("★75 までは素通りしていた(playTabooCard が requirePlayable を呼んでいなかった)")
                .hasMessageContaining("条件");
    }

    @Test
    @DisplayName("禁忌デッキの《静寂の瞑想》も、そのターン1枚目なら使える")
    void 禁忌の静寂の瞑想は1枚目なら使える() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        f.me().getTabooDeck().add(MEDITATION);
        int deckBefore = f.me().getDeck().size();

        assertThatCode(() -> game.playTabooCard(f.room(), "me", 0, List.of(), List.of()))
                .as("★<b>塞ぎすぎない</b>ことも測る(72 の教訓・幅)").doesNotThrowAnyException();
        assertThat(f.me().getDeck().size()).isEqualTo(deckBefore - 2);
    }

    @Test
    @DisplayName("★禁忌デッキの《禁忌の代償》も、裏向きマナが無ければ使えない")
    void 禁忌の代償は裏向きマナが無ければ使えない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        f.me().getTrash().add(DARK_MINION);
        f.me().getTabooDeck().add(TABOO_PRICE);

        assertThatThrownBy(() -> game.playTabooCard(f.room(), "me", 0, List.of(),
                List.of(trash(0))))
                .as("★使用条件は9枚に掛かっている。1枚だけ直したのではない")
                .hasMessageContaining("条件");
    }

    // ===================================================================
    // 裁定350: 使用条件をビューが運ぶ
    // ===================================================================

    @Test
    @DisplayName("★★★条件を満たしていない手札は playConditionMet=false で届く(350)")
    void 条件を満たさない手札はビューに印が付く() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        f.me().setPlayedCardThisTurn(true);
        f.giveHand(f.me(), MEDITATION);

        CardView card = views.build(f.room(), "me").you().hand().get(0);

        assertThat(card.playConditionMet())
                .as("★75 までビューは使用条件を1つも運んでいなかった").isFalse();
    }

    @Test
    @DisplayName("条件を満たしていれば true で届く(350)")
    void 条件を満たす手札はビューでも使える() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        f.giveHand(f.me(), MEDITATION);

        assertThat(views.build(f.room(), "me").you().hand().get(0).playConditionMet()).isTrue();
    }

    @Test
    @DisplayName("使用条件を持たないカードは常に true(350)")
    void 条件を持たないカードは常に使える扱いである() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        f.me().setPlayedCardThisTurn(true);
        f.giveHand(f.me(), MAGMA);

        assertThat(views.build(f.room(), "me").you().hand().get(0).playConditionMet()).isTrue();
    }

    @Test
    @DisplayName("★★★禁忌デッキの面にも届く —— このバッチの発端がそこである(350)")
    void 禁忌デッキの面にも使用条件が届く() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        f.me().setPlayedCardThisTurn(true);
        f.me().getTabooDeck().add(MEDITATION);

        assertThat(views.build(f.room(), "me").you().taboo().get(0).playConditionMet()).isFalse();
    }

    // ===================================================================
    // 撤去したもの・据え置いたもの(裁定178・196、74 の教訓)
    // ===================================================================

    @Test
    @DisplayName("★★destroyManaAt は位置で壊す —— 候補を絞るのは呼び出し元である")
    void マナの破壊は位置で行う() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        faceDownMana(f.me(), DARK_SPELL_A);

        assertThat(actions.destroyManaAt(f.room(), f.me(), 0)).isTrue();
        assertThat(f.me().getManaZone()).hasSize(2);
        assertThat(f.me().getTrash()).as("破壊されたマナは墓地へ行く").contains(MAGMA);
        assertThat(actions.destroyManaAt(f.room(), f.me(), 99))
                .as("★範囲外は false。例外にしない").isFalse();
    }

    @Test
    @DisplayName("★★★裁定346 はカード本文を1文字も変えていない(据え置きの番人・74 の教訓)")
    void 怨霊の本文は変えていない() {
        assertThat(cards.findById(MANA_WRAITH).text())
                .as("★本文に「選び」を足して実装を正当化する、という直し方をしていない")
                .isEqualTo("自分の墓地にある闇文明のカードを2枚裏向きでマナに置く。その後置いた枚数カードを1枚引く。");
        assertThat(cards.findById(HOLY_SIGNAL).text())
                .isEqualTo("相手の場にいる最も攻撃力の高いミニオン1体と最も体力の低いミニオン1体を破壊。");
    }

    /**
     * ★★《ホーリー・シグナル》の道具として使う、体力の高い闇のミニオン。
     * 最高攻撃力側に選ばせるため、体力もタイの2体より高いものを使う。
     */
    private static final String DARK_SPELL_B_MINION = "QTE-M-DARK-23";  // 獄門の裁定者(9/9)
}
