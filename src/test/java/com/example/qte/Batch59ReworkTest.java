package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.effect.CardEffectRegistry;
import com.example.qte.effect.TargetChoice;
import com.example.qte.game.GameService;
import com.example.qte.game.ManaCard;
import com.example.qte.game.MinionInstance;
import com.example.qte.game.PlayerState;
import com.example.qte.game.TurnPhase;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.CardTextKeywords;
import com.example.qte.master.Keyword;
import com.example.qte.support.AutoGameFixture;

/**
 * Batch 59(作り直し④ = 裁定が付いた16枚)の挙動の試験。
 *
 * <h2>この試験が測っているもの</h2>
 *
 * {@code notes/rework-triage.md} の残り16枚 —— 区分3b の7枚(裁定260〜266)・
 * 区分4 の2枚(裁定267・275)・区分5 の7枚(裁定268〜274)である。
 * すべて Batch 58 の時点で「裁定待ち」として着手できなかったものであり、
 * マスターの回答が揃ったことで初めて実装できた(裁定184)。
 *
 * <h2>★「実装変更なし」と結論した4枚にも試験を置いてある</h2>
 *
 * 《突風の祝福》(260)・《創世神 ガイア》(263)・《フレア・ポーン》(268)・
 * 《黄泉の召喚主》(275)は裁定の結果「今の実装のままでよい」となった。
 * 壊しどころが無いので壊し検証の対象にはならないが、
 * <b>試験の存在そのものが番人になる</b> —— 後のバッチが「本文が変わっているから」と
 * 単体を全体化したり、マナ最大値の管理値を新設したりするのを止めるためである
 * (Batch 56 が《ガイア・ハンマー》に1件だけ置いたのと同じ考え方)。
 *
 * <h2>本物の入口を通す</h2>
 *
 * {@link AutoGameFixture} の上に書き、効果は {@code GameService.playCard} /
 * {@code specialSummon} / {@code leaderAttack} / {@code attack} / {@code endTurn} /
 * {@code summonFromGrave} から起こす(裁定187)。
 */
@SpringBootTest
class Batch59ReworkTest {

    /** 常在効果を持たないリーダー(蒼海の賢者)。既定の対戦相手 */
    private static final String PLAIN_LEADER = "QTE-M-WATER-1";
    /** 黄泉の召喚主(リーダー)。サブフェイズに墓地から召喚できる */
    private static final String GRAVE_SUMMONER = "QTE-M-DARK-15";

    // ---- 区分3b(裁定260〜266) ----
    private static final String GUST_BLESSING = "QTE-M-WIND-27";    // 突風の祝福(260)
    private static final String SEARING_MAGE = "QTE-M-FIRE-18";     // 痛撃の炎術師(261)
    private static final String GALE_FOX = "QTE-M-WIND-6";          // ガイル・フォックス(262)
    private static final String GAIA = "QTE-M-EARTH-8";             // 創世神 ガイア(263)
    private static final String MEIMA_SWORD = "QTE-M-DARK-14";      // 禁忌の冥魔剣(264)
    private static final String NIGHTMARE = "QTE-M-DARK-27";        // 悪夢(265)
    private static final String BONE_COLLECTOR = "QTE-M-DARK-6";    // ボーン・コレクター(266)

    // ---- 区分4(裁定267・275) ----
    private static final String ZOMB_STRIKER = "QTE-M-DARK-16";     // ゾンストライカー(267)

    // ---- 区分5(裁定268〜274) ----
    private static final String FLARE_PAWN = "QTE-M-FIRE-2";        // フレア・ポーン(268)
    private static final String KAMIKAZE = "QTE-M-WIND-12";         // 神風の大号令(269)
    private static final String WISDOM_CRYSTAL = "QTE-M-LIGHT-19";  // 英知の水晶(270)
    private static final String ZODIAC_IRIS = "QTE-M-LIGHT-25";     // 創世神 ゾディアックアイリス(271)
    private static final String MICHAEL = "QTE-M-LIGHT-7";          // 大天使 ミカエル(272)
    private static final String MANA_WRAITH = "QTE-M-DARK-11";      // マナを貪る怨霊(273)
    private static final String QUAKE_HAMMER = "QTE-M-EARTH-28";    // 地響きの槌(274)

    // ---- 道具として使うカード ----
    /** スカイ・スワロー(1/1/1・【速攻】)。ドローを起こさない最小のミニオン */
    private static final String SKY_SWALLOW = "QTE-M-WIND-3";
    /** 相打ちの咎人(4/2/2)。【召喚時】に両リーダーへ2ダメージ×2。封じの観測に使う */
    private static final String RECKONER = "QTE-M-FIRE-19";
    /** アクア・ジェリー(1/1/1・【知識】)。ON_ENTER の観測に使う */
    private static final String KNOWLEDGE_JELLY = "QTE-M-WATER-2";
    /** マグマ・ストレート(スペル・1)。ミニオン1体に3ダメージ。効果破壊を起こす道具 */
    private static final String MAGMA = "QTE-M-FIRE-10";
    /** 光の召喚士(5/3/3)。ON_ENTER で手札のコスト3以下のミニオンを「効果で」場に出す */
    private static final String LIGHT_SUMMONER = "QTE-M-LIGHT-21";
    /** 執念の暗殺者(闇)。【召喚時】に対象を選ぶミニオン(黄泉の召喚主のガードの物差し) */
    private static final String SHADOW_ASSASSIN = "QTE-M-DARK-20";

    @Autowired
    GameService game;

    @Autowired
    CardEffectRegistry effects;

    @Autowired
    CardMasterRepository cards;

    @Autowired
    com.example.qte.effect.StatCalculator stats;

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

    private static TargetChoice hand(Integer... indexes) {
        return new TargetChoice(List.of(indexes), null, null, null, null);
    }

    // ==================================================================
    // 260. 突風の祝福(QTE-M-WIND-27・区分3b)★実装変更なし
    //   旧: 「自分のミニオン1体の体力を+2する。【還元】」
    //   新: 「自分のミニオンの体力を+2する 【還元】」(「1体」が消えた)
    //   → マスター裁定260(a): 省略されただけであり、単体のままである
    // ==================================================================

    @Test
    void 突風の祝福は選んだ1体だけの体力を上げる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        MinionInstance chosen = f.putOnField(f.me(), SKY_SWALLOW);
        MinionInstance other = f.putOnField(f.me(), SKY_SWALLOW);
        int base = f.card(SKY_SWALLOW).hp();

        game.playCard(f.room(), "me", f.giveHand(f.me(), GUST_BLESSING),
                List.of(minions(chosen.getInstanceId())), false);

        assertThat(chosen.getMaxHp()).as("選んだ1体は+2").isEqualTo(base + 2);
        assertThat(other.getMaxHp()).as("★全体化していない: 選ばなかったほうは印刷値のまま")
                .isEqualTo(base);
    }

    @Test
    void 突風の祝福は対象を1体だけ要求する() {
        assertThat(effects.targetSpecOf(GUST_BLESSING).requirements())
                .as("要求は1件").hasSize(1);
        assertThat(effects.targetSpecOf(GUST_BLESSING).requirements().get(0).count())
                .as("★裁定260(a): 選ぶ数は1のままである").isEqualTo(1);
    }

    // ==================================================================
    // 261. 痛撃の炎術師(QTE-M-FIRE-18・区分3b)
    //   旧: 「【召喚時】自分のリーダーの体力が10以上なら自分のリーダーに1ダメージ。」
    //   新: 「【知識】自分のリーダーの体力が10以上なら自分のリーダーに1ダメージ。」
    //   → マスター裁定261(a): 【知識】の1ドローに<b>加えて</b>条件ダメージを持つ2段効果
    // ==================================================================

    @Test
    void 痛撃の炎術師は知識の1ドローと条件ダメージの両方を行う() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        int handBefore = f.me().getHand().size();

        game.playCard(f.room(), "me", f.giveHand(f.me(), SEARING_MAGE), List.of(), false);

        assertThat(f.me().getHand()).as("★【知識】の1ドローは起きる(裁定261(a))")
                .hasSize(handBefore + 1);
        assertThat(f.me().getLp()).as("体力20は10以上なので1ダメージ").isEqualTo(19);
    }

    @Test
    void 痛撃の炎術師は自分の体力が10未満ならダメージを与えない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        f.me().setLp(9);
        int handBefore = f.me().getHand().size();

        game.playCard(f.room(), "me", f.giveHand(f.me(), SEARING_MAGE), List.of(), false);

        assertThat(f.me().getLp()).as("9は10以上ではない").isEqualTo(9);
        assertThat(f.me().getHand()).as("【知識】のドローは条件に関係なく起きる")
                .hasSize(handBefore + 1);
    }

    /**
     * ★<b>誘発が【召喚時】から【知識】(=ON_ENTER型)に変わったことの証拠。</b>
     * 《光の召喚士》は「効果で場に出す」ので【召喚時】は発動しない。
     * それでも自傷が起きるなら、この効果は ON_ENTER で焚かれている。
     */
    @Test
    void 痛撃の炎術師は効果で場に出しても発動する() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        int summoner = f.giveHand(f.me(), LIGHT_SUMMONER);
        int mage = f.giveHand(f.me(), SEARING_MAGE);

        game.playCard(f.room(), "me", summoner, List.of(hand(mage)), false);

        assertThat(f.fieldIds(f.me())).as("効果で場に出ている").contains(SEARING_MAGE);
        assertThat(f.me().getLp()).as("★召喚していないのに自傷が起きる = ON_ENTER である")
                .isEqualTo(19);
    }

    // ==================================================================
    // 262. ガイル・フォックス(QTE-M-WIND-6・区分3b)
    //   旧: 「【召喚時】このターン中にカードを3枚以上使用しているなら【潜伏】。」
    //   新: 「このターン中にカードを3枚以上使用しているなら【潜伏】。」(印が消えた)
    //   → マスター裁定262: 「登場時、そのターン3枚以上カードをプレイしているなら
    //      <b>永続的に</b>潜伏を持つ」。常在の再評価ではない
    // ==================================================================

    @Test
    void ガイルフォックスは3枚目として召喚すると潜伏を得る() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        f.me().setCardsUsedThisTurn(2); // 自身が3枚目になる(カウンタは自身を含まない。裁定1)

        game.playCard(f.room(), "me", f.giveHand(f.me(), GALE_FOX), List.of(), false);

        assertThat(f.me().getMinionZone().get(0).hasKeyword(Keyword.STEALTH)).isTrue();
    }

    @Test
    void ガイルフォックスは2枚目として召喚しても潜伏を得ない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        f.me().setCardsUsedThisTurn(1); // 自身が2枚目

        game.playCard(f.room(), "me", f.giveHand(f.me(), GALE_FOX), List.of(), false);

        assertThat(f.me().getMinionZone().get(0).hasKeyword(Keyword.STEALTH)).isFalse();
    }

    /**
     * ★<b>「永続的に持つ」の証拠</b>(裁定262)。
     * 常在の再評価であれば、ターンが変わって使用カウンタが 0 に戻った時点で
     * 【潜伏】を失うはずである。失わないことを測る。
     */
    @Test
    void ガイルフォックスは一度得た潜伏をターンが変わっても手放さない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        f.me().setCardsUsedThisTurn(2);
        game.playCard(f.room(), "me", f.giveHand(f.me(), GALE_FOX), List.of(), false);
        MinionInstance fox = f.me().getMinionZone().get(0);

        game.endTurn(f.room(), "me"); // 相手のターンへ
        // ★startTurnReset が走るのはターンプレイヤーだけなので、こちらのカウンタは
        //   手番が戻るまで残る。条件が偽になった状態を作るため、ここで直接 0 に戻す
        f.me().setCardsUsedThisTurn(0);

        assertThat(f.me().getCardsUsedThisTurn()).as("前提: 条件は偽になっている").isZero();
        assertThat(fox.hasKeyword(Keyword.STEALTH))
                .as("★常在の再評価ではないので、条件を失っても【潜伏】は残る(裁定262)").isTrue();
    }

    /**
     * ★<b>「登場時」なので経路を問わない</b>(裁定193)。
     * ★効果で場に出た場合は自身が「使用」されていないため、素直に使用カウンタ3以上を求める。
     */
    @Test
    void ガイルフォックスは効果で場に出た場合は使用3枚以上で潜伏を得る() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        f.me().setCardsUsedThisTurn(3);
        int summoner = f.giveHand(f.me(), LIGHT_SUMMONER);
        int fox = f.giveHand(f.me(), GALE_FOX);

        game.playCard(f.room(), "me", summoner, List.of(hand(fox)), false);

        MinionInstance entered = f.me().getMinionZone().stream()
                .filter(m -> GALE_FOX.equals(m.getMaster().id())).findFirst().orElseThrow();
        assertThat(entered.hasKeyword(Keyword.STEALTH))
                .as("★召喚していなくても登場時に判定される").isTrue();
    }

    @Test
    void ガイルフォックスは効果で場に出た場合に使用2枚では潜伏を得ない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        f.me().setCardsUsedThisTurn(2);
        int summoner = f.giveHand(f.me(), LIGHT_SUMMONER);
        int fox = f.giveHand(f.me(), GALE_FOX);

        game.playCard(f.room(), "me", summoner, List.of(hand(fox)), false);

        MinionInstance entered = f.me().getMinionZone().stream()
                .filter(m -> GALE_FOX.equals(m.getMaster().id())).findFirst().orElseThrow();
        assertThat(entered.hasKeyword(Keyword.STEALTH))
                .as("★自身は使用されていないので、2枚では足りない").isFalse();
    }

    // ==================================================================
    // 263. 創世神 ガイア(QTE-M-EARTH-8・区分3b)★実装変更なし
    //   新本文に「【特殊召喚】(自分のマナ最大値が10以上の時、コスト0で手札から使用できる)」
    //   → マスター裁定263(a): 「マナ最大値」= マナゾーンの現在の枚数
    // ==================================================================

    @Test
    void 創世神ガイアはマナが10枚あれば0コストで特殊召喚できる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 10);
        MinionInstance victim = f.putOnField(f.you(), SKY_SWALLOW);

        game.specialSummon(f.room(), "me", f.giveHand(f.me(), GAIA), List.of());

        assertThat(f.fieldIds(f.me())).containsExactly(GAIA);
        assertThat(f.you().getMinionZone()).as("【召喚時】で自身以外は全破壊")
                .doesNotContain(victim);
    }

    @Test
    void 創世神ガイアはマナが9枚では特殊召喚できない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 9);

        assertThatThrownBy(() -> game.specialSummon(f.room(), "me", f.giveHand(f.me(), GAIA), List.of()))
                .as("★「これまでに置いた最大値」ではなく、今の枚数を見る(裁定263(a))")
                .isInstanceOf(IllegalStateException.class);
    }

    // ==================================================================
    // 264. 禁忌の冥魔剣(QTE-M-DARK-14・区分3b)
    //   新本文に「(このカードの効果はターンに5回までしか発動しない)」
    //   → マスター裁定264(a): 毎ターン5回にリセットされる
    // ==================================================================

    @Test
    void 禁忌の冥魔剣はターンに5回までしか発動しない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 11); // 装備5 + スカイ・スワロー6体
        game.playCard(f.room(), "me", f.giveHand(f.me(), MEIMA_SWORD), List.of(), false);

        for (int i = 0; i < 6; i++) {
            game.playCard(f.room(), "me", f.giveHand(f.me(), SKY_SWALLOW), List.of(), false);
        }

        assertThat(f.me().getMinionZone()).as("6体とも場には出る(止まるのは剣の発動だけ)")
                .hasSize(6);
        assertThat(f.you().getLp()).as("★1ダメージ×5回で止まる(6回目は発動しない)")
                .isEqualTo(PlayerState.INITIAL_LP - 5);
    }

    @Test
    void 禁忌の冥魔剣はターンが変われば再び5回発動できる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 11);
        game.playCard(f.room(), "me", f.giveHand(f.me(), MEIMA_SWORD), List.of(), false);
        for (int i = 0; i < 6; i++) {
            game.playCard(f.room(), "me", f.giveHand(f.me(), SKY_SWALLOW), List.of(), false);
        }
        assertThat(f.you().getLp()).isEqualTo(PlayerState.INITIAL_LP - 5);

        // 一周してきた自分のターン。場は掃除しないので、ミニオンは効果で追加する
        f.state().setTurnNumber(f.state().getTurnNumber() + 2);
        f.me().getMinionZone().clear();
        payMana(f.me(), 1);
        game.playCard(f.room(), "me", f.giveHand(f.me(), SKY_SWALLOW), List.of(), false);

        assertThat(f.you().getLp()).as("★毎ターン5回にリセットされる(裁定264(a))")
                .isEqualTo(PlayerState.INITIAL_LP - 6);
    }

    // ==================================================================
    // 265. 悪夢(QTE-M-DARK-27・区分3b)
    //   新本文に「このターンの間【召喚時】は使えない」
    //   → マスター裁定265: 封じる範囲は<b>自分だけ</b>、持続は使用したターンの残り全体
    // ==================================================================

    /** 悪夢のコストは「墓地のスペル以外のカード1枚につき-1」。9枚積んで 13→4 にする */
    private void cheapenNightmare(PlayerState player) {
        player.getTrash().addAll(Collections.nCopies(9, FLARE_PAWN));
    }

    @Test
    void 悪夢を使ったターンは自分の召喚時が発動しない() {
        AutoGameFixture f = newGame();
        cheapenNightmare(f.me());
        payMana(f.me(), 8); // 悪夢4 + 相打ちの咎人4
        game.playCard(f.room(), "me", f.giveHand(f.me(), NIGHTMARE), List.of(), false);

        game.playCard(f.room(), "me", f.giveHand(f.me(), RECKONER), List.of(), false);

        assertThat(f.fieldIds(f.me())).as("ミニオン自体は場に出る").contains(RECKONER);
        assertThat(f.me().getLp()).as("★【召喚時】の自傷が起きない").isEqualTo(PlayerState.INITIAL_LP);
        assertThat(f.you().getLp()).as("★【召喚時】の相手への打点も起きない")
                .isEqualTo(PlayerState.INITIAL_LP);
    }

    /**
     * ★<b>封じは「このターンの間」だけである</b>(裁定265(b))。
     *
     * <p>★ここは<b>持続の軸</b>を測っている。範囲の軸(自分だけか両者か)は
     * <b>本物の入口からは観測できない</b> —— 【召喚時】が起きるのはミニオンを召喚した
     * 瞬間だけで、召喚できるのはターンプレイヤーだけであり、
     * 印({@code thisTurnAuras})はターン終了時に消えるからである。
     * つまり「相手が印を持ったまま自分が召喚する」盤面が構造的に存在しない
     * (《英霊・コレキ》の「相手のターン中は止めない」と同じ立場のものである)。
     */
    @Test
    void 悪夢の召喚時封じは次のターンには残らない() {
        AutoGameFixture f = newGame();
        cheapenNightmare(f.me());
        payMana(f.me(), 8); // 悪夢4 + 次のターンの相打ちの咎人4
        game.playCard(f.room(), "me", f.giveHand(f.me(), NIGHTMARE), List.of(), false);

        game.endTurn(f.room(), "me");    // 相手のターンへ
        game.endTurn(f.room(), "you");   // 一周して自分のターンへ
        game.nextPhase(f.room(), "me");  // マナチャージ→メイン
        game.playCard(f.room(), "me", f.giveHand(f.me(), RECKONER), List.of(), false);

        assertThat(f.me().getLp()).as("★封じは「このターンの間」だけ。次のターンには【召喚時】が動く")
                .isEqualTo(PlayerState.INITIAL_LP - 4);
    }

    /** ★【知識】は ON_ENTER であって【召喚時】ではないので、悪夢では止まらない */
    @Test
    void 悪夢は知識のドローを止めない() {
        AutoGameFixture f = newGame();
        cheapenNightmare(f.me());
        payMana(f.me(), 5); // 悪夢4 + アクア・ジェリー1
        game.playCard(f.room(), "me", f.giveHand(f.me(), NIGHTMARE), List.of(), false);
        int handBefore = f.me().getHand().size();

        game.playCard(f.room(), "me", f.giveHand(f.me(), KNOWLEDGE_JELLY), List.of(), false);

        assertThat(f.me().getHand()).as("★【知識】は ON_ENTER なので封じの対象外")
                // +1(ジェリーを手札に加える) -1(使用で減る) +1(【知識】のドロー) = +1
                .hasSize(handBefore + 1);
    }

    // ==================================================================
    // 266. ボーン・コレクター(QTE-M-DARK-6・区分3b)
    //   旧: 「このミニオンが<b>戦闘で</b>破壊された時、カードを1枚引く。」
    //   新: 「このミニオンが破壊された時、カードを1枚引く。」
    //   → マスター裁定266(a): 効果による破壊でも1ドローする
    // ==================================================================

    @Test
    void ボーンコレクターは効果で破壊されても1枚引く() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 1);
        MinionInstance collector = f.putOnField(f.me(), BONE_COLLECTOR);
        int handBefore = f.me().getHand().size();

        // マグマ・ストレート(3ダメージ)で自分のボーン・コレクター(HP1)を焼く
        game.playCard(f.room(), "me", f.giveHand(f.me(), MAGMA),
                List.of(minions(collector.getInstanceId())), false);

        assertThat(f.me().getMinionZone()).as("破壊された").doesNotContain(collector);
        assertThat(f.me().getHand()).as("★「戦闘で」が消えたので効果破壊でも引く")
                .hasSize(handBefore + 1); // +1(引く) +1(スペルを手札へ) -1(使用) = +1
    }

    @Test
    void ボーンコレクターは戦闘で破壊されても1枚引く() {
        AutoGameFixture f = newGame();
        MinionInstance collector = f.putOnField(f.me(), BONE_COLLECTOR);
        f.putOnField(f.you(), BONE_COLLECTOR); // Attack4 なので相打ちになる
        game.nextPhase(f.room(), "me"); // メイン→バトル
        int handBefore = f.me().getHand().size();

        game.attack(f.room(), "me", collector.getInstanceId(),
                f.you().getMinionZone().get(0).getInstanceId());

        assertThat(f.me().getHand()).as("戦闘破壊でも引く(旧本文の挙動も残っている)")
                .hasSize(handBefore + 1);
    }

    // ==================================================================
    // 267. ゾンストライカー(QTE-M-DARK-16・区分4)
    //   旧: 「…このカードは4枚以上入れられる。」
    //   新: 「…【突進】破壊されたとき自分の山札の上から1枚を墓地に置く。」
    //   → マスター裁定267(a): 構築特例は廃止。通常の同名4枚上限へ戻る
    // ==================================================================

    @Test
    void ゾンストライカーは破壊されると山札の上から1枚墓地に置く() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 1);
        MinionInstance striker = f.putOnField(f.me(), ZOMB_STRIKER);
        int deckBefore = f.me().getDeck().size();

        game.playCard(f.room(), "me", f.giveHand(f.me(), MAGMA),
                List.of(minions(striker.getInstanceId())), false);

        assertThat(f.me().getMinionZone()).doesNotContain(striker);
        assertThat(f.me().getDeck()).as("★山札が1枚減る(セルフミル)").hasSize(deckBefore - 1);
        assertThat(f.me().getTrash()).as("墓地にはゾンストライカー本体・削れた1枚・使用済みスペル")
                .hasSize(3);
    }

    /**
     * ★<b>構築特例の廃止はコードではなくカード定義が持つ。</b>
     * Ver1.1 のカード定義には {@code unlimitedCopies} の項目がそもそも無い ——
     * 「実装が要らない」ことを、データの側で確かめる。
     */
    @Test
    void ゾンストライカーは同名上限の例外を持たない() {
        assertThat(cards.findById(ZOMB_STRIKER).text())
                .as("★Ver1.1 の本文から「4枚以上入れられる」が消えている")
                .doesNotContain("4枚以上");
    }

    // ==================================================================
    // 268. フレア・ポーン(QTE-M-FIRE-2・区分5)★実装変更なし
    //   旧: 空欄 / 新: 「効果なし」の明記
    //   → マスター裁定268(a): 効果の登録を一切行わない
    // ==================================================================

    @Test
    void フレアポーンは効果の登録を持たない() {
        assertThat(effects.isRegistered(FLARE_PAWN))
                .as("★裁定268(a): 「効果なし」に登録は要らない").isFalse();
        assertThat(CardTextKeywords.hasEffectSentence(cards.findById(FLARE_PAWN).text()))
                .as("★「効果なし」は効果の文として数えない(印も点かない)").isFalse();
    }

    // ==================================================================
    // 269. 神風の大号令(QTE-M-WIND-12・区分5)
    //   旧: 「このターン中に自分が使用したカードの枚数と同じだけ、自分のミニオンすべての攻撃力を+1。」
    //   新: 「自分のミニオンを2体破壊する。破壊したミニオンの数自分のミニオンのAttackを+1する【還元】」
    //   → マスター裁定269(b): 2体未満でも使用でき、いるだけ破壊する
    // ==================================================================

    @Test
    void 神風の大号令は2体破壊して残りを2上げる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        MinionInstance a = f.putOnField(f.me(), SKY_SWALLOW);
        MinionInstance b = f.putOnField(f.me(), SKY_SWALLOW);
        MinionInstance survivor = f.putOnField(f.me(), SKY_SWALLOW);
        int base = f.card(SKY_SWALLOW).attack();

        game.playCard(f.room(), "me", f.giveHand(f.me(), KAMIKAZE),
                List.of(minions(a.getInstanceId(), b.getInstanceId())), false);

        assertThat(f.me().getMinionZone()).containsExactly(survivor);
        assertThat(survivor.getMaster().attack()).as("印刷値は動かない").isEqualTo(base);
        assertThat(stats.effectiveAttack(f.state(), f.me(), survivor))
                .as("★破壊した数(2)だけ攻撃力が上がる").isEqualTo(base + 2);
    }

    @Test
    void 神風の大号令は自分のミニオンが1体でも使用できる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        MinionInstance only = f.putOnField(f.me(), SKY_SWALLOW);

        game.playCard(f.room(), "me", f.giveHand(f.me(), KAMIKAZE),
                List.of(minions(only.getInstanceId())), false);

        assertThat(f.me().getMinionZone()).as("★1体でも使用でき、いるだけ破壊する(裁定269(b))")
                .isEmpty();
    }

    @Test
    void 神風の大号令は自分のミニオンが0体でも使用できる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);

        game.playCard(f.room(), "me", f.giveHand(f.me(), KAMIKAZE), List.of(none()), false);

        assertThat(f.me().getManaZone().stream().map(ManaCard::getCardId))
                .as("★何も起きないが【還元】は効く(効果は発動している)").contains(KAMIKAZE);
    }

    // ==================================================================
    // 270. 英知の水晶(QTE-M-LIGHT-19・区分5)
    //   旧: 「自分の【知識】カードのコスト-1。」(静的なコスト軽減)
    //   新: 「相手がカードを引いたとき自分はカードを1枚引いても良い」
    //   → マスター裁定270(a): 通常ドロー・効果ドローの両方に反応する
    // ==================================================================

    @Test
    void 英知の水晶は相手が引くと自分も1枚引く() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), WISDOM_CRYSTAL);
        int handBefore = f.me().getHand().size();

        game.endTurn(f.room(), "me"); // 相手のターン開始時ドローが起きる

        assertThat(f.me().getHand()).as("★相手のターン開始ドローに反応して1枚引く")
                .hasSize(handBefore + 1);
    }

    @Test
    void 英知の水晶は自分が引いても反応しない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 1);
        f.putOnField(f.me(), WISDOM_CRYSTAL);
        int handBefore = f.me().getHand().size();

        // 自分が【知識】ミニオンを出して1枚引く
        game.playCard(f.room(), "me", f.giveHand(f.me(), KNOWLEDGE_JELLY), List.of(), false);

        assertThat(f.me().getHand()).as("★「相手が引いたとき」なので、自分のドローには反応しない")
                .hasSize(handBefore + 1); // +1(手札に加える) +1(【知識】) -1(使用) = +1
    }

    /**
     * ★★<b>両者が出しても終わる</b>ことの番人。
     * 誘発によるドローが再び誘発を呼ぶと、A と B のあいだで無限に往復する。
     * 「誘発によるドローは数えない」を再入ガードで表しているので、1往復で止まる。
     */
    @Test
    void 英知の水晶は両者が場に出していても無限に往復しない() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), WISDOM_CRYSTAL);
        f.putOnField(f.you(), WISDOM_CRYSTAL);
        int myHand = f.me().getHand().size();
        int yourHand = f.you().getHand().size();

        game.endTurn(f.room(), "me"); // 相手のターン開始ドロー → わたしが1枚引く → そこで止まる

        assertThat(f.you().getHand()).as("相手はターン開始の1枚だけ").hasSize(yourHand + 1);
        assertThat(f.me().getHand()).as("★わたしは1枚だけ引いて止まる").hasSize(myHand + 1);
    }

    // ==================================================================
    // 271. 創世神 ゾディアックアイリス(QTE-M-LIGHT-25・区分5)
    //   新本文に「ターンの終わりにこのカードの現在の体力分自分のリーダーの体力を回復する」
    //   → マスター裁定271(a): 読むのは「現在の体力」(ダメージが残った今の値)
    // ==================================================================

    @Test
    void ゾディアックアイリスはターン終了時に現在の体力分リーダーを回復する() {
        AutoGameFixture f = newGame();
        MinionInstance iris = f.putOnField(f.me(), ZODIAC_IRIS);
        f.me().setLp(5);

        game.endTurn(f.room(), "me");

        assertThat(iris.getCurrentHp()).as("前提: 無傷の11").isEqualTo(11);
        assertThat(f.me().getLp()).as("5 + 11 = 16").isEqualTo(16);
    }

    @Test
    void ゾディアックアイリスの回復量はダメージを受けた分だけ減る() {
        AutoGameFixture f = newGame();
        MinionInstance iris = f.putOnField(f.me(), ZODIAC_IRIS);
        iris.takeDamage(4);
        f.me().setLp(5);

        game.endTurn(f.room(), "me");

        assertThat(f.me().getLp()).as("★最大体力(11)ではなく現在の体力(7)を読む(裁定271(a))")
                .isEqualTo(12);
    }

    // ==================================================================
    // 272. 大天使 ミカエル(QTE-M-LIGHT-7・区分5)
    //   旧: 「【守護】戦闘では破壊されない(ダメージは受ける)。」
    //   新: 「【守護】戦闘時ダメージを受けない。」
    //   → マスター裁定272(a): ダメージが0になるので「ダメージを受けたとき」の誘発も起きない
    // ==================================================================

    @Test
    void ミカエルは戦闘でダメージを受けない() {
        AutoGameFixture f = newGame();
        MinionInstance michael = f.putOnField(f.you(), MICHAEL);
        MinionInstance attacker = f.putOnField(f.me(), BONE_COLLECTOR); // Attack 4
        game.nextPhase(f.room(), "me");

        game.attack(f.room(), "me", attacker.getInstanceId(), michael.getInstanceId());

        assertThat(michael.getCurrentHp())
                .as("★HPが削れない(旧本文は「ダメージは受ける」だった)").isEqualTo(8);
        assertThat(f.you().getMinionZone()).contains(michael);
    }

    @Test
    void ミカエルは効果ダメージは受ける() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 1);
        MinionInstance michael = f.putOnField(f.you(), MICHAEL);

        game.playCard(f.room(), "me", f.giveHand(f.me(), MAGMA),
                List.of(minions(michael.getInstanceId())), false);

        assertThat(michael.getCurrentHp()).as("★「戦闘時」の限定なので効果ダメージは通る")
                .isEqualTo(5);
    }

    // ==================================================================
    // 273. マナを貪る怨霊(QTE-M-DARK-11・区分5)
    //   旧: 「自分のマナゾーンの表向きのカード2枚を裏向きにする。そうしたらカードを2枚引く。」
    //   新: 「自分の墓地にある闇文明のカードを2枚裏向きでマナに置く。その後置いた枚数カードを1枚引く。」
    //   → マスター裁定273(a): 置いた枚数だけ引く(最大2枚)
    // ==================================================================

    @Test
    void マナを貪る怨霊は墓地の闇2枚をマナに置いて2枚引く() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        f.me().getTrash().add(ZOMB_STRIKER);
        f.me().getTrash().add(BONE_COLLECTOR);
        int manaBefore = f.me().getManaZone().size();
        int handBefore = f.me().getHand().size();

        game.playCard(f.room(), "me", f.giveHand(f.me(), MANA_WRAITH), List.of(), false);

        assertThat(f.me().getManaZone()).as("墓地の2枚がマナへ").hasSize(manaBefore + 2);
        assertThat(f.me().getTrash()).as("墓地に残るのは使用済みの怨霊だけ")
                .containsExactly(MANA_WRAITH);
        assertThat(f.me().getHand()).as("★置いた枚数(2)だけ引く(裁定273(a))")
                .hasSize(handBefore + 2); // +2(ドロー) +1(手札に加える) -1(使用) = +2
    }

    @Test
    void マナを貪る怨霊は墓地の闇が1枚なら1枚だけ引く() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        f.me().getTrash().add(ZOMB_STRIKER);
        f.me().getTrash().add(SKY_SWALLOW); // 風文明: 対象外
        int manaBefore = f.me().getManaZone().size();
        int handBefore = f.me().getHand().size();

        game.playCard(f.room(), "me", f.giveHand(f.me(), MANA_WRAITH), List.of(), false);

        assertThat(f.me().getManaZone()).as("置けたのは1枚だけ").hasSize(manaBefore + 1);
        assertThat(f.me().getHand()).as("★「1枚引く」ではなく「置いた枚数」引く")
                .hasSize(handBefore + 1);
    }

    @Test
    void マナを貪る怨霊は墓地に闇が無くても使用できる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        int handBefore = f.me().getHand().size();

        game.playCard(f.room(), "me", f.giveHand(f.me(), MANA_WRAITH), List.of(), false);

        assertThat(f.me().getHand()).as("★1枚も引かない(旧本文の使用条件は消えている)")
                .hasSize(handBefore);
        assertThat(f.me().getTrash()).containsExactly(MANA_WRAITH);
    }

    // ==================================================================
    // 274. 地響きの槌(QTE-M-EARTH-28・区分5)
    //   旧: 「攻撃時相手のミニオン全てに5ダメージ。」
    //   新: 「攻撃時ミニオン全てに2ダメージ与える。この効果で破壊したミニオンの数
    //        山札の上から裏向きでマナを1枚増やす。」
    //   → マスター裁定274(a): 本文どおり自分のミニオンも巻き込み、その破壊も数に含める
    // ==================================================================

    @Test
    void 地響きの槌は自分のミニオンも巻き込んで破壊数だけマナを増やす() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        MinionInstance mine = f.putOnField(f.me(), SKY_SWALLOW);   // HP1
        MinionInstance theirs = f.putOnField(f.you(), SKY_SWALLOW); // HP1
        game.playCard(f.room(), "me", f.giveHand(f.me(), QUAKE_HAMMER), List.of(), false);
        int manaBefore = f.me().getManaZone().size();
        game.nextPhase(f.room(), "me"); // メイン→バトル

        game.leaderAttack(f.room(), "me", null);

        assertThat(f.me().getMinionZone()).as("★自分のミニオンも巻き込む(裁定274(a))")
                .doesNotContain(mine);
        assertThat(f.you().getMinionZone()).doesNotContain(theirs);
        assertThat(f.me().getManaZone()).as("★破壊した2体分マナが増える(自分の分も数える)")
                .hasSize(manaBefore + 2);
    }

    @Test
    void 地響きの槌は1体も破壊できなければマナを増やさない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        MinionInstance tough = f.putOnField(f.you(), MICHAEL); // HP8。2ダメージでは落ちない
        game.playCard(f.room(), "me", f.giveHand(f.me(), QUAKE_HAMMER), List.of(), false);
        int manaBefore = f.me().getManaZone().size();
        game.nextPhase(f.room(), "me");

        game.leaderAttack(f.room(), "me", tough.getInstanceId()); // 【守護】持ちを殴る

        assertThat(f.you().getMinionZone()).contains(tough);
        assertThat(f.me().getManaZone()).as("破壊0体ならマナ加速も0").hasSize(manaBefore);
    }

    // ==================================================================
    // 275. 黄泉の召喚主(QTE-M-DARK-15・区分4)★実装変更なし
    //   新: 「サブフェイズ時ミニオンを墓地から<b>手札にあるかのように</b>召喚してもよい」
    //   → マスター裁定275(a): 狭い読み。「効果で出すのではなく召喚である」の念押しにすぎず、
    //      墓地を手札と同じゾーンとして扱う意味ではない。実装は変えない
    // ==================================================================

    @Test
    void 黄泉の召喚主は墓地からミニオンを召喚できる() {
        AutoGameFixture f = newGame(GRAVE_SUMMONER);
        payMana(f.me(), 1);
        f.me().getTrash().add(SKY_SWALLOW);
        game.nextPhase(f.room(), "me"); // メイン→バトル
        game.nextPhase(f.room(), "me"); // バトル→サブ

        game.summonFromGrave(f.room(), "me", 0);

        assertThat(f.fieldIds(f.me())).containsExactly(SKY_SWALLOW);
        assertThat(f.me().getTrash()).isEmpty();
    }

    /**
     * ★<b>ガードは恒久のルールである</b>(裁定275(a))。
     * 「手札にあるかのように」を広く読んで対象選択の導線を新設する、という道は採らなかった。
     * 黙って NullPointerException で落ちる代わりに、理由を返して止める。
     */
    @Test
    void 黄泉の召喚主は召喚時に対象を選ぶミニオンを墓地から召喚できない() {
        AutoGameFixture f = newGame(GRAVE_SUMMONER);
        payMana(f.me(), 5);
        f.me().getTrash().add(SHADOW_ASSASSIN);
        f.putOnField(f.you(), SKY_SWALLOW); // 対象の候補は居る
        game.nextPhase(f.room(), "me");
        game.nextPhase(f.room(), "me");

        assertThatThrownBy(() -> game.summonFromGrave(f.room(), "me", 0))
                .hasMessageContaining("墓地からは召喚できません");
    }

    @Test
    void 黄泉の召喚主のサブフェイズ以外では墓地から召喚できない() {
        AutoGameFixture f = newGame(GRAVE_SUMMONER);
        payMana(f.me(), 1);
        f.me().getTrash().add(SKY_SWALLOW);

        assertThat(f.state().getPhase()).isEqualTo(TurnPhase.MAIN);
        assertThatThrownBy(() -> game.summonFromGrave(f.room(), "me", 0))
                .isInstanceOf(IllegalStateException.class);
    }
}
