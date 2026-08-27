package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.effect.PersistentAura;
import com.example.qte.effect.RuleGuards;
import com.example.qte.effect.StatCalculator;
import com.example.qte.effect.TargetChoice;
import com.example.qte.game.GameActions;
import com.example.qte.game.GameService;
import com.example.qte.game.ManaCard;
import com.example.qte.game.MinionInstance;
import com.example.qte.game.PlayerState;
import com.example.qte.game.StatModifier;
import com.example.qte.game.TurnPhase;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.support.AutoGameFixture;

/**
 * Batch 74。Batch 73 が残した判断待ち B(13件)にマスターが下した裁定328〜336 の試験。
 *
 * <h2>このクラスは何を守っているのか</h2>
 *
 * 73 は「読みが2通り以上に分かれるもの」を13件、
 * {@code notes/batch73-ruling-requests.md} に判断待ちとして残した(裁定184)。
 * 74 はその回答を実装に落とし、<b>回答そのものをここで固定する</b>。
 *
 * <table border="1">
 *   <caption>13件の行き先</caption>
 *   <tr><th>件</th><th>カード</th><th>裁定</th><th>実装</th></tr>
 *   <tr><td>B-1</td><td>天界の守護神 ゾディアック</td><td>328</td><td><b>変えた</b>(ミニオン側の禁止を外した)</td></tr>
 *   <tr><td>B-2</td><td>蒼海の賢者</td><td>329</td><td><b>変えた</b>(手札0枚でも起動できる)</td></tr>
 *   <tr><td>B-3</td><td>分那愚利</td><td>330</td><td><b>変えた</b>(対象は必須)</td></tr>
 *   <tr><td>B-4</td><td>風弾の跳弾</td><td>331</td><td><b>変えた</b>(相手側は任意)</td></tr>
 *   <tr><td>B-5</td><td>静空の風使い</td><td>333</td><td><b>変えた</b>(アンタップするマナを選ばせる)</td></tr>
 *   <tr><td>B-6</td><td>回帰の風穴</td><td>334</td><td><b>変えた</b>(2回目も使用として数える)</td></tr>
 *   <tr><td>B-7</td><td>神風の大号令</td><td>332</td><td><b>変えた</b>(期限を PERMANENT へ)</td></tr>
 *   <tr><td>B-8</td><td>豊穣の地霊主</td><td>337</td><td>据え置き(自分のマナだけ)</td></tr>
 *   <tr><td>B-9</td><td>傷痕の闘帝</td><td>338</td><td>据え置き(無条件でドロー)</td></tr>
 *   <tr><td>B-10</td><td>背水の炎壁</td><td>339</td><td>据え置き(自分のリーダーだけ)</td></tr>
 *   <tr><td>B-11</td><td>悪夢 / 墓場の怨念集合体</td><td>340</td><td>据え置き(自分の墓地だけ)</td></tr>
 *   <tr><td>B-12</td><td>聖光の守護聖</td><td>335</td><td><b>変えた</b>(リーダーにも掛ける)</td></tr>
 *   <tr><td>B-13</td><td>詠唱の宝珠</td><td>336</td><td><b>変えた</b>(破壊のときだけ)</td></tr>
 * </table>
 *
 * <h2>★★★据え置きの5件にも番人を置いた</h2>
 *
 * B-8・B-9・B-10・B-11 は「現行のままでよい」という裁定である。
 * <b>実装を1文字も変えていないのに試験を足すのは、裁定が確定したからである</b> ——
 * 73 の調査では、この4件のうち<b>3件に番人が1つも無かった</b>
 * (地霊主だけは肯定側の番人があったが、「相手のマナでは数えない」という
 * <b>否定側</b>は測られていなかった)。
 *
 * <p>★<b>「変えない」と決めたことこそ、次の人が壊しやすい。</b>
 * 「本文に『自分の』が無いのだから両者を見るべきだ」という読みは、
 * この裁定を知らなければ<b>正しく見える</b>。
 *
 * <h2>測り方</h2>
 *
 * {@link AutoGameFixture} の上に書き、本物の入口から起こす(裁定187)。
 * 数値は<b>試験の側に直接書く</b>(裁定298)。
 * ★<b>ただし B-12 だけは本物の入口が存在しない</b> ——
 * 235枚にリーダーを破壊するカードが1枚も無いため、{@link RuleGuards} を直接叩く。
 */
@SpringBootTest
class Batch74RulingTest {

    /** 常在効果を持たないリーダー(蒼海の賢者)。既定の対戦相手 */
    private static final String PLAIN_LEADER = "QTE-M-WATER-1";

    // ---- 74 が触った/固定したカード ----
    private static final String ZODIAC = "QTE-M-LIGHT-8";          // 天界の守護神 ゾディアック
    private static final String PIERCER = "QTE-M-WATER-16";        // 急流の狙撃手(2/2/1・【知識】【貫通】)
    private static final String BUNNAGURI = "QTE-M-EARTH-33";      // 分那愚利
    private static final String RICOCHET = "QTE-M-WIND-24";        // 風弾の跳弾(スペル・1)
    private static final String QUIET_WIND = "QTE-M-WIND-17";      // 静空の風使い(2/1/2)
    private static final String WINDHOLE = "QTE-M-WIND-26";        // 回帰の風穴(スペル・2)
    private static final String DIVINE_WIND = "QTE-M-WIND-12";     // 神風の大号令(スペル・3)
    private static final String HARVEST_LEADER = "QTE-M-EARTH-15"; // 豊穣の地霊主(リーダー)
    private static final String SCAR_EMPEROR = "QTE-M-FIRE-15";    // 傷痕の闘帝(リーダー)
    private static final String LAST_STAND = "QTE-M-FIRE-21";      // 背水の炎壁(【特殊召喚】)
    private static final String NIGHTMARE = "QTE-M-DARK-27";       // 悪夢(スペル・13)
    private static final String HOLY_PROTECTOR = "QTE-M-LIGHT-1";  // 聖光の守護聖(リーダー)
    private static final String CHANT_ORB = "QTE-M-LIGHT-28";      // 詠唱の宝珠(ウェポン・1/1)
    private static final String JUSTICE_SHIELD = "QTE-M-LIGHT-13"; // 正義の御盾(リーダーへのダメージ-1)

    // ---- 道具として使うカード ----
    /** マグマ・ストレート(火・スペル・1)。マナの中身の物差し */
    private static final String MAGMA = "QTE-M-FIRE-10";
    /** 死神の大鎌(闇・ウェポン・1)。付け替え相手 */
    private static final String REAPER_SCYTHE = "QTE-M-DARK-13";
    /** スカイ・スワロー(風・1/1/1・【速攻】)。効果を持たない最小のミニオン */
    private static final String SKY_SWALLOW = "QTE-M-WIND-3";
    /** 海獣タウギーナ(水・1/1/1・【潜伏】)。効果を持たない物差し */
    private static final String SEA_BEAST = "QTE-M-WATER-33";

    @Autowired
    GameService game;

    @Autowired
    GameActions actions;

    @Autowired
    RuleGuards guards;

    @Autowired
    CardMasterRepository cards;

    @Autowired
    StatCalculator stats;

    private AutoGameFixture newGame() {
        return newGame(PLAIN_LEADER);
    }

    private AutoGameFixture newGame(String myLeaderId) {
        AutoGameFixture f = new AutoGameFixture(cards, myLeaderId, PLAIN_LEADER);
        f.fillDeck(f.me(), 40);
        f.fillDeck(f.you(), 40);
        return f;
    }

    /** スペルだけでマナを作る(マナの中身が対象候補に紛れ込まないようにする) */
    private void payMana(PlayerState player, int count) {
        for (int i = 0; i < count; i++) {
            player.getManaZone().add(new ManaCard(MAGMA, false));
        }
    }

    private static TargetChoice none() {
        return new TargetChoice(null, null, null, null, null);
    }

    private static TargetChoice minions(String... instanceIds) {
        return new TargetChoice(null, List.of(instanceIds), null, null, null);
    }

    // ==================================================================
    // B-1 天界の守護神 ゾディアック(QTE-M-LIGHT-8) —— 裁定328
    //   本文: 「このミニオンが場にいる限り、相手のリーダーは攻撃できない。」
    //   73 まで: (a) 相手のリーダーが攻撃側になれない
    //            (b) 相手のミニオンが自分のリーダーを狙えない ← 本文に無い
    // ==================================================================

    @Test
    @DisplayName("★★★ゾディアックが居ても、【貫通】持ちのミニオンはリーダーを殴れる")
    void ゾディアックはミニオンからリーダーへの攻撃を止めない() {
        AutoGameFixture f = newGame();
        f.putOnField(f.you(), ZODIAC);           // 【守護】を持つ
        MinionInstance piercer = f.putOnField(f.me(), PIERCER); // 【貫通】を持つ
        f.state().setPhase(TurnPhase.BATTLE);

        int before = f.you().getLp();
        game.attack(f.room(), "me", piercer.getInstanceId(), null);

        assertThat(f.you().getLp())
                .as("★【貫通】は守護を無視できる。ゾディアックの禁止は「リーダーが攻撃する」側だけである")
                .isEqualTo(before - stats.effectiveAttack(f.state(), f.me(), piercer));
    }

    @Test
    @DisplayName("ゾディアックが居ると、装備したリーダーは攻撃できないまま(残した側)")
    void ゾディアックはリーダーの攻撃を止め続ける() {
        AutoGameFixture f = newGame();
        f.putOnField(f.you(), ZODIAC);
        f.me().setEquippedWeapon(cards.findById(REAPER_SCYTHE));
        f.state().setPhase(TurnPhase.BATTLE);

        assertThatThrownBy(() -> game.leaderAttack(f.room(), "me", null))
                .as("★外したのは片方だけである。出口ごとに測る(71 の教訓)")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ゾディアック");
    }

    // ==================================================================
    // B-2 蒼海の賢者(QTE-M-WATER-1) —— 裁定329
    //   本文: 「【起動：1】自分の手札を1枚デッキの一番下に戻す。自分のリーダーの体力を2回復。」
    // ==================================================================

    @Test
    @DisplayName("★★蒼海の賢者は手札0枚でも起動でき、回復だけが起きる")
    void 蒼海の賢者は手札0枚でも回復する() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 1);
        f.me().getHand().clear();
        f.me().setLp(10);

        game.useLeaderAbility(f.room(), "me", List.of(none()));

        assertThat(f.me().getLp())
                .as("★2文は「そうしたら」で繋がっていない(裁定217・191 の流儀)")
                .isEqualTo(12);
        assertThat(f.me().getHand()).as("戻せる手札は無い").isEmpty();
    }

    @Test
    @DisplayName("手札があれば、選んだ1枚が山札の一番下へ戻る")
    void 蒼海の賢者は手札があれば1枚を山札の下へ戻す() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 1);
        f.me().getHand().clear();
        f.me().getHand().add(SEA_BEAST);
        f.me().setLp(10);

        game.useLeaderAbility(f.room(), "me",
                List.of(new TargetChoice(List.of(0), null, null, null, null)));

        assertThat(f.me().getHand()).isEmpty();
        assertThat(f.me().getDeck().peekLast()).isEqualTo(SEA_BEAST);
        assertThat(f.me().getLp()).isEqualTo(12);
    }

    // ==================================================================
    // B-3 分那愚利(QTE-M-EARTH-33) —— 裁定330
    //   ★肯定側の番人は FireEarthVer11EffectTest に置いた
    //     (分那愚利は召喚時に相手ミニオン1体に1ダメージ /
    //      分那愚利は相手の場に2体居ると必ず1体を選ばされる)。
    //   ここでは「相手の場が空でも召喚は通る」ことだけを重ねて測る ——
    //     必須化したことで召喚そのものが弾かれる、という壊し方があるためである。
    // ==================================================================

    @Test
    @DisplayName("★分那愚利は、対象が必須になっても相手の場が空なら素通りで召喚できる")
    void 分那愚利は対象必須でも相手の場が空なら召喚できる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        int hand = f.giveHand(f.me(), BUNNAGURI);

        game.playCard(f.room(), "me", hand, List.of(), false);

        assertThat(f.fieldIds(f.me()))
                .as("★候補0件は「不発」であって「使えない」ではない(裁定302)")
                .containsExactly(BUNNAGURI);
        assertThat(f.me().getPendingChoice()).isNull();
    }

    // ==================================================================
    // B-4 風弾の跳弾(QTE-M-WIND-24) —— 裁定331
    //   本文: 「自分のミニオンを1枚破壊する。そうしたら相手のミニオン1体に3ダメージ。」
    // ==================================================================

    @Test
    @DisplayName("★★風弾の跳弾は、相手の場が空でも使える(自分のミニオンだけが壊れる)")
    void 風弾の跳弾は相手の場が空でも使える() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        MinionInstance mine = f.putOnField(f.me(), SKY_SWALLOW);
        int hand = f.giveHand(f.me(), RICOCHET);

        game.playCard(f.room(), "me", hand,
                List.of(minions(mine.getInstanceId()), none()), false);

        assertThat(f.me().getMinionZone())
                .as("★「そうしたら」は後段を条件付けるだけで、使用条件にはしていない")
                .isEmpty();
    }

    @Test
    @DisplayName("相手が居れば、これまでどおり3ダメージが飛ぶ")
    void 風弾の跳弾は相手が居れば3ダメージ() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        MinionInstance mine = f.putOnField(f.me(), SKY_SWALLOW);
        MinionInstance target = f.putOnField(f.you(), ZODIAC);   // 体力9。1発では死なない
        int hand = f.giveHand(f.me(), RICOCHET);

        game.playCard(f.room(), "me", hand,
                List.of(minions(mine.getInstanceId()), minions(target.getInstanceId())), false);

        assertThat(target.getDamage()).isEqualTo(3);
    }

    // ==================================================================
    // B-5 静空の風使い(QTE-M-WIND-17) —— 裁定333
    //   本文: 「このカードをタップすることで自分のマナを1枚アンタップ状態にする。」
    //   73 まで: マナゾーンの先頭から自動で選んでいた
    // ==================================================================

    @Test
    @DisplayName("★★★静空の風使いは、タップ済みマナが2枚以上ならどれを戻すか問う")
    void 静空の風使いはアンタップするマナを選ばせる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        f.me().getManaZone().get(0).tap();
        f.me().getManaZone().get(2).tap();
        MinionInstance user = f.putOnField(f.me(), QUIET_WIND);

        game.useMinionAbility(f.room(), "me", user.getInstanceId(), List.of());

        assertThat(f.me().getPendingChoice())
                .as("★裁定315〜317 で払う順に表裏の差が付いた以上、どの1枚が戻るかは意味を持つ")
                .isNotNull();
        assertThat(f.me().getPendingChoice().candidates())
                .as("候補はタップ済みの位置だけ").containsExactly("0", "2");

        f.answerChoice(game, "me", "2");

        assertThat(f.me().getManaZone().get(2).isTapped()).as("選んだほうが戻る").isFalse();
        assertThat(f.me().getManaZone().get(0).isTapped())
                .as("★73 までは先頭(位置0)が自動で戻っていた").isTrue();
    }

    @Test
    @DisplayName("タップ済みマナが1枚なら問わない(選ぶ余地が無い)")
    void 静空の風使いはタップ済みが1枚なら問わない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        f.me().getManaZone().get(1).tap();
        MinionInstance user = f.putOnField(f.me(), QUIET_WIND);

        game.useMinionAbility(f.room(), "me", user.getInstanceId(), List.of());

        assertThat(f.me().getPendingChoice()).isNull();
        assertThat(f.me().getManaZone().get(1).isTapped()).isFalse();
    }

    @Test
    @DisplayName("タップ済みマナが1枚も無ければ、能力は不発になる(タップの代償は払われる)")
    void 静空の風使いはタップ済みが無ければ不発() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        MinionInstance user = f.putOnField(f.me(), QUIET_WIND);

        game.useMinionAbility(f.room(), "me", user.getInstanceId(), List.of());

        assertThat(f.me().getPendingChoice()).isNull();
        assertThat(user.isTapped()).as("★代償は効果より前に払われている").isTrue();
    }

    // ==================================================================
    // B-6 回帰の風穴(QTE-M-WIND-26) —— 裁定334
    //   本文: 「このカードのコストを+1してもよい。そうした場合このカードをもう一度墓地から唱え…」
    //   73 まで: 2回目は使用枚数に数えられていなかった
    // ==================================================================

    @Test
    @DisplayName("★★★回帰の風穴の強化使用は、カードの使用とスペルの詠唱をそれぞれ2回進める")
    void 回帰の風穴の強化使用は2回数える() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        MinionInstance a = f.putOnField(f.me(), SKY_SWALLOW);
        f.putOnField(f.me(), SEA_BEAST);
        int hand = f.giveHand(f.me(), WINDHOLE);

        game.playCard(f.room(), "me", hand, List.of(minions(a.getInstanceId())), true);

        assertThat(f.me().getCardsUsedThisTurn())
                .as("★本文が「もう一度<b>唱え</b>」と言っている以上、2回目も使用である(裁定247 の流儀)")
                .isEqualTo(2);
        assertThat(f.me().getSpellsCastThisTurn()).isEqualTo(2);
    }

    @Test
    @DisplayName("通常使用なら1回のままである(強化していないのに2回数えない)")
    void 回帰の風穴の通常使用は1回だけ数える() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        MinionInstance a = f.putOnField(f.me(), SKY_SWALLOW);
        int hand = f.giveHand(f.me(), WINDHOLE);

        game.playCard(f.room(), "me", hand, List.of(minions(a.getInstanceId())), false);

        assertThat(f.me().getCardsUsedThisTurn()).isEqualTo(1);
        assertThat(f.me().getSpellsCastThisTurn()).isEqualTo(1);
    }

    @Test
    @DisplayName("★2回目に手札へ戻す相手が居なくても、唱えたことは数える")
    void 回帰の風穴は2体目が居なくても2回数える() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        MinionInstance only = f.putOnField(f.me(), SKY_SWALLOW);
        int hand = f.giveHand(f.me(), WINDHOLE);

        game.playCard(f.room(), "me", hand, List.of(minions(only.getInstanceId())), true);

        assertThat(f.me().getSpellsCastThisTurn())
                .as("★数えるのは「唱えたこと」であって「戻せたこと」ではない")
                .isEqualTo(2);
    }

    // ==================================================================
    // B-7 神風の大号令(QTE-M-WIND-12) —— 裁定332
    //   本文: 「自分のミニオンを2体破壊する。破壊したミニオンの数自分のミニオンのAttackを+1する【還元】」
    //   73 まで: THIS_TURN(《突風の祝福》《追い風》《疾風の導き手》とは非対称)
    // ==================================================================

    @Test
    @DisplayName("★★神風の大号令の強化は、ターンが終わっても消えない")
    void 神風の大号令の強化は永続である() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        MinionInstance a = f.putOnField(f.me(), SKY_SWALLOW);
        MinionInstance b = f.putOnField(f.me(), SKY_SWALLOW);
        MinionInstance survivor = f.putOnField(f.me(), SEA_BEAST);
        int hand = f.giveHand(f.me(), DIVINE_WIND);

        game.playCard(f.room(), "me", hand,
                List.of(minions(a.getInstanceId(), b.getInstanceId())), false);

        int base = cards.findById(SEA_BEAST).attack();
        assertThat(stats.effectiveAttack(f.state(), f.me(), survivor)).isEqualTo(base + 2);

        game.endTurn(f.room(), "me");

        assertThat(stats.effectiveAttack(f.state(), f.me(), survivor))
                .as("★本文に期限は書かれていない。風の他の3枚(突風の祝福・追い風・疾風の導き手)は PERMANENT である")
                .isEqualTo(base + 2);
        assertThat(survivor.getModifiers())
                .filteredOn(m -> DIVINE_WIND.equals(m.sourceCardId()))
                .allMatch(m -> m.duration() == StatModifier.Duration.PERMANENT);
    }

    // ==================================================================
    // B-8 豊穣の地霊主(QTE-M-EARTH-15) —— 裁定337(据え置き)
    //   本文: 「【常在】：マナにカードが置かれたときそのターン中それが2回目ならカードを1枚引く。」
    //   ★「自分の」は書かれていないが、<b>数えるのは自分のマナだけ</b>である。
    // ==================================================================

    @Test
    @DisplayName("★★豊穣の地霊主は、相手のマナ配置では1枚も引かない(据え置きの番人)")
    void 豊穣の地霊主は相手のマナでは引かない() {
        AutoGameFixture f = newGame(HARVEST_LEADER);
        int before = f.me().getHand().size();

        // 相手のマナゾーンに2枚置かれたことを、本物の通知点から起こす
        f.you().getManaZone().add(new ManaCard(MAGMA, false));
        actions.manaPlaced(f.room(), f.you());
        f.you().getManaZone().add(new ManaCard(MAGMA, false));
        actions.manaPlaced(f.room(), f.you());

        assertThat(f.me().getHand())
                .as("★カウンタは PlayerState のフィールドであり、構造上「自分のマナ」しか数えられない")
                .hasSize(before);
    }

    @Test
    @DisplayName("自分のマナなら、これまでどおり2回目で1枚引く(肯定側)")
    void 豊穣の地霊主は自分のマナ2回目で引く() {
        AutoGameFixture f = newGame(HARVEST_LEADER);
        int before = f.me().getHand().size();

        f.me().getManaZone().add(new ManaCard(MAGMA, false));
        actions.manaPlaced(f.room(), f.me());
        assertThat(f.me().getHand()).hasSize(before);

        f.me().getManaZone().add(new ManaCard(MAGMA, false));
        actions.manaPlaced(f.room(), f.me());
        assertThat(f.me().getHand()).hasSize(before + 1);
    }

    // ==================================================================
    // B-9 傷痕の闘帝(QTE-M-FIRE-15) —— 裁定338(据え置き)
    //   本文: 「【起動：1】自分のリーダーに1ダメージ。そうしたら1枚ドローする。」
    //   ★「そうしたら」の成否は見ない。ダメージを与える行為を行えばドローする。
    // ==================================================================

    @Test
    @DisplayName("★★傷痕の闘帝は、正義の御盾でダメージが0になってもドローする(据え置きの番人)")
    void 傷痕の闘帝は軽減で0でもドローする() {
        AutoGameFixture f = newGame(SCAR_EMPEROR);
        payMana(f.me(), 1);
        f.me().setEquippedWeapon(cards.findById(JUSTICE_SHIELD)); // リーダーへのダメージ-1
        int lpBefore = f.me().getLp();
        int handBefore = f.me().getHand().size();

        game.useLeaderAbility(f.room(), "me", List.of());

        assertThat(f.me().getLp()).as("1ダメージは軽減されて0になる").isEqualTo(lpBefore);
        assertThat(f.me().getHand())
                .as("★裁定338: 「そうしたら」の成否は見ない。実装は 73 のままである")
                .hasSize(handBefore + 1);
    }

    // ==================================================================
    // B-10 背水の炎壁(QTE-M-FIRE-21) —— 裁定339(据え置き)
    //   本文: 「【特殊召喚】このターン中3回以上リーダーがダメージを受けていた場合手札から1コストで出せる。」
    //   ★「自分の」は書かれていないが、数えるのは<b>特殊召喚する側のリーダー</b>だけである。
    // ==================================================================

    @Test
    @DisplayName("★★背水の炎壁は、相手のリーダーが3回殴られても特殊召喚できない(据え置きの番人)")
    void 背水の炎壁は相手の被ダメージでは出せない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        for (int i = 0; i < 3; i++) {
            actions.damageLeader(f.room(), f.you(), 1);
        }
        int hand = f.giveHand(f.me(), LAST_STAND);

        assertThatThrownBy(() -> game.specialSummon(f.room(), "me", hand, List.of()))
                .as("★火文明の自傷テーマは「自分のリーダーを削る」ことに掛かっている")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("自分のリーダーが3回受けていれば出せる(肯定側)")
    void 背水の炎壁は自分の被ダメージ3回で出せる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        for (int i = 0; i < 3; i++) {
            actions.damageLeader(f.room(), f.me(), 1);
        }
        int hand = f.giveHand(f.me(), LAST_STAND);

        game.specialSummon(f.room(), "me", hand, List.of());

        assertThat(f.fieldIds(f.me())).contains(LAST_STAND);
    }

    // ==================================================================
    // B-11 悪夢(QTE-M-DARK-27) —— 裁定340(据え置き)
    //   本文: 「墓地にあるカード1枚につきコスト-1」
    //   ★「自分の」は書かれていないが、数えるのは<b>自分の墓地</b>だけである。
    // ==================================================================

    @Test
    @DisplayName("★★悪夢のコストは、相手の墓地を1枚も数えない(据え置きの番人)")
    void 悪夢は相手の墓地を数えない() {
        AutoGameFixture f = newGame();
        int printed = cards.findById(NIGHTMARE).cost();
        for (int i = 0; i < 5; i++) {
            f.you().getTrash().add(MAGMA);
        }

        assertThat(stats.effectiveCost(f.state(), f.me(), f.card(NIGHTMARE)))
                .as("★TargetCandidates.Kind.TRASH も Side を見ておらず、相手の墓地を読む仕組みが1つも無い")
                .isEqualTo(printed);

        for (int i = 0; i < 5; i++) {
            f.me().getTrash().add(MAGMA);
        }
        assertThat(stats.effectiveCost(f.state(), f.me(), f.card(NIGHTMARE)))
                .as("自分の墓地は数える(肯定側)")
                .isEqualTo(printed - 5);
    }

    // ==================================================================
    // B-12 聖光の守護聖(QTE-M-LIGHT-1) —— 裁定335
    //   本文: 「…自分のリーダーと自分のミニオンすべては『相手のカードや能力の効果で破壊されない』を得る」
    //   ★★★本物の入口が存在しない —— 235枚にリーダーを破壊するカードは1枚も無い。
    // ==================================================================

    @Test
    @DisplayName("★★★聖光の守護聖のオーラは、リーダーの破壊も防ぐ(相手の手番のとき)")
    void 聖光の守護聖はリーダーの破壊も防ぐ() {
        AutoGameFixture f = newGame(HOLY_PROTECTOR);
        f.me().getPersistentAuras().add(
                PersistentAura.untilEndOfTurn(HOLY_PROTECTOR, f.state().getTurnNumber() + 1));
        f.state().setTurnPlayerId("you");   // 相手の手番 = 相手由来と推定される

        assertThat(guards.isLeaderDestructionPrevented(f.state(), f.me()))
                .as("★本文の「自分のリーダーと」は、73 まで実装のどこにも現れていなかった")
                .isTrue();
    }

    @Test
    @DisplayName("自分の手番中の破壊は防がない(ミニオン側と同じ推定を使う)")
    void 聖光の守護聖は自分の手番では防がない() {
        AutoGameFixture f = newGame(HOLY_PROTECTOR);
        f.me().getPersistentAuras().add(
                PersistentAura.untilEndOfTurn(HOLY_PROTECTOR, f.state().getTurnNumber() + 1));
        f.state().setTurnPlayerId("me");

        assertThat(guards.isLeaderDestructionPrevented(f.state(), f.me()))
                .as("★「相手の効果か」の推定は causedByOpponent 1箇所である(裁定130)")
                .isFalse();
    }

    @Test
    @DisplayName("オーラが無ければ防がない")
    void 聖光の守護聖のオーラが無ければ防がない() {
        AutoGameFixture f = newGame(HOLY_PROTECTOR);
        f.state().setTurnPlayerId("you");

        assertThat(guards.isLeaderDestructionPrevented(f.state(), f.me())).isFalse();
    }

    // ==================================================================
    // B-13 詠唱の宝珠(QTE-M-LIGHT-28) —— 裁定336
    //   本文: 「このカードが破壊されたとき次の自分のターン唱える光のスペルのコスト-1。」
    //   73 まで: 「場を離れたとき」として、山札へ戻ったときにも発動していた
    // ==================================================================

    /** 自分に《詠唱の宝珠》の持続効果が付いているか */
    private boolean hasOrbAura(PlayerState player) {
        return player.getPersistentAuras().stream().anyMatch(a -> CHANT_ORB.equals(a.cardId()));
    }

    @Test
    @DisplayName("★破壊されたら発動する(攻撃したウェポンのターン終了時破壊)")
    void 詠唱の宝珠は破壊されたら発動する() {
        AutoGameFixture f = newGame();
        f.me().setEquippedWeapon(cards.findById(CHANT_ORB));
        f.me().setWeaponAttackedThisTurn(true);

        game.endTurn(f.room(), "me");

        assertThat(f.me().getEquippedWeapon()).as("攻撃したウェポンはターンの終わりに壊れる").isNull();
        assertThat(hasOrbAura(f.me())).isTrue();
    }

    @Test
    @DisplayName("★★付け替えも破壊扱いである(裁定336)")
    void 詠唱の宝珠は付け替えでも発動する() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        f.me().setEquippedWeapon(cards.findById(CHANT_ORB));
        int hand = f.giveHand(f.me(), REAPER_SCYTHE);

        game.playCard(f.room(), "me", hand, List.of(none()), false);

        assertThat(f.me().getTrash()).contains(CHANT_ORB);
        assertThat(hasOrbAura(f.me())).isTrue();
    }

    @Test
    @DisplayName("★★★禁忌由来なら発動しない —— 消滅するので「破壊」にならない(裁定336)")
    void 詠唱の宝珠は禁忌由来なら発動しない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        f.me().setEquippedWeapon(cards.findById(CHANT_ORB));
        f.me().setEquippedWeaponFromTaboo(true);
        int hand = f.giveHand(f.me(), REAPER_SCYTHE);

        game.playCard(f.room(), "me", hand, List.of(none()), false);

        assertThat(f.me().getLostZone())
                .as("禁忌カードは墓地ではなく消滅ゾーンへ行く(総合ルール3-6)")
                .contains(CHANT_ORB);
        assertThat(f.me().getTrash()).doesNotContain(CHANT_ORB);
        assertThat(hasOrbAura(f.me()))
                .as("★「破壊されて墓地へ」という出来事が起きていない")
                .isFalse();
    }

    @Test
    @DisplayName("★★山札へ戻されたときは発動しない(《サイクロン・リフレッシュ》の経路)")
    void 詠唱の宝珠は山札へ戻っても発動しない() {
        AutoGameFixture f = newGame();
        f.me().setEquippedWeapon(cards.findById(CHANT_ORB));

        String returned = actions.removeWeaponForDeck(f.room(), f.me());

        assertThat(returned).isEqualTo(CHANT_ORB);
        assertThat(hasOrbAura(f.me()))
                .as("★73 まではここでも発動していた。本文は「破壊されたとき」である")
                .isFalse();
    }
}
