package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.effect.SoulSpellSpec;
import com.example.qte.effect.StatCalculator;
import com.example.qte.effect.TargetChoice;
import com.example.qte.game.GameService;
import com.example.qte.game.ManaCard;
import com.example.qte.game.MinionInstance;
import com.example.qte.game.PlayerState;
import com.example.qte.game.TurnPhase;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.support.AutoGameFixture;

/**
 * Batch 54 が実装した【賢魂】のエンジンと7枚の試験(★P4)。
 *
 * <h2>何を守る試験か</h2>
 *
 * 裁定152 は「賢魂：n を持つミニオンは、スペルとしても使うことができる」と定めている。
 * ここが守るのは<b>1枚のカードが2つの姿を持つ</b>という構造そのものである ——
 *
 * <ul>
 * <li><b>姿が分かれていること</b>: ミニオンとして召喚したら賢魂は発動せず、
 *     賢魂として使ったら【召喚時】は発動しない。</li>
 * <li><b>賢魂の使用はスペルの使用であること</b>: 使用回数・コスト軽減・スペル封じ・
 *     使用後の行き先が、すべて通常のスペルと同じ道具を通る。</li>
 * <li><b>キーワードも姿ごとに分かれること</b>:《白ノ霊知者》の【還元】は
 *     賢魂の姿にだけ付いている(マスター裁定 B1)。</li>
 * </ul>
 *
 * <h2>測り方の方針(48〜53 から継続)</h2>
 *
 * <ul>
 * <li>効果は<b>本物の入口</b>から起こす(裁定187)。割り込みも
 *     {@code GameService.resolveChoice} を通す。</li>
 * <li><b>「そうでない側」も測る</b>(裁定181)。「賢魂で使うとドローする」だけでは
 *     <b>召喚してもドローする</b>実装でも通るので、召喚側を並べて置く。</li>
 * <li>ドロー数は<b>山札の減り</b>で測る。</li>
 * </ul>
 */
@SpringBootTest
class SoulSpellTest {

    /** 常在効果を持たないリーダー(既定) */
    private static final String PLAIN_LEADER = "QTE-M-WATER-1";

    // ---- Batch 54 が実装した7枚 ----
    /** グレイヴガールズファン(闇・コスト5・2/4【守護】)【賢魂：１】1引いて1枚ミル */
    private static final String GRAVE_GIRLS = "QTE-M-DARK-37";
    /** スタンディングテント(闇・コスト6・1/6【守護】【召喚時】2引く)【賢魂：2】自身を場に出す */
    private static final String STANDING_TENT = "QTE-M-DARK-38";
    /** 英霊・タイガラム(光・進化・コスト7)【召喚時】守護を場に出す /【賢魂：3】2引く */
    private static final String TAIGARAMU = "QTE-M-LIGHT-32";
    /** 黒ノ霊導者(風・進化・コスト5)【賢魂：1】自分1体破壊 → 相手1体に3ダメージ */
    private static final String KURONO = "QTE-M-WIND-30";
    /** 白ノ霊知者(風・進化・コスト4)【召喚時】2引いて1体破壊 /【賢魂：2】Attack+1【還元】 */
    private static final String SHIRONO = "QTE-M-WIND-31";
    /** 愚乱怒土地(土・進化・コスト6)【賢魂：3】上2枚を見て1枚マナ・1枚手札 */
    private static final String GURANDORANDO = "QTE-M-EARTH-30";
    /** 勝阿外(土・コスト9・1/3【常在】相手はスペル不可)【賢魂：2】マナ加速 + 条件つき1引く */
    private static final String KATSUAGE = "QTE-M-EARTH-36";

    // ---- 素材・道具として使う既存カード ----
    /** コスト2・1/3・【守護】(ライト・シールド)。光文明の守護 = タイガラムの素材 */
    private static final String LIGHT_GUARD = "QTE-M-LIGHT-2";
    /** コスト1・1/1・【知識】(ウィンド・ペティ)。風文明の汎用素材 */
    private static final String WIND_PLAIN = "QTE-M-WIND-2";
    /** コスト0・2/1(グラウンド・ポーン)。土文明の汎用素材 */
    private static final String EARTH_PLAIN = "QTE-M-EARTH-2";
    /** コスト1・2/1・効果なし(フレア・ポーン)。汎用のミニオン・破壊の的 */
    private static final String PLAIN_MINION = "QTE-M-FIRE-2";
    /** コスト1・ミニオン1体に3ダメージ(マグマ・ストレート)。マナの中身に使う */
    private static final String MAGMA = "QTE-M-FIRE-10";
    /** コスト3・自分のスペルのコスト-1(唱導の聖騎士)。賢魂がスペル扱いかを測る */
    private static final String CHANT_PALADIN = "QTE-M-LIGHT-18";
    /** コスト2・カードを2枚引く(スプラッシュ・ドロー)。ただのスペル */
    private static final String SPLASH_DRAW = "QTE-M-WATER-9";

    @Autowired
    private CardMasterRepository cards;

    @Autowired
    private GameService game;

    @Autowired
    private StatCalculator stats;

    @Autowired
    private com.example.qte.effect.CardEffectRegistry registry;

    private AutoGameFixture newGame() {
        AutoGameFixture f = new AutoGameFixture(cards, PLAIN_LEADER, PLAIN_LEADER);
        f.fillDeck(f.me(), 40);
        f.fillDeck(f.you(), 40);
        return f;
    }

    /** コスト支払い用のマナを n 枚置く(中身はスペル。場やマナの数え上げに紛れない) */
    private void payMana(PlayerState player, int count) {
        for (int i = 0; i < count; i++) {
            player.getManaZone().add(new ManaCard(MAGMA, false));
        }
    }

    private static TargetChoice minions(String... instanceIds) {
        return new TargetChoice(null, List.of(instanceIds), null, null, null);
    }

    private static TargetChoice none() {
        return new TargetChoice(null, null, null, null, null);
    }

    private static TargetChoice hand(Integer... indexes) {
        return new TargetChoice(List.of(indexes), null, null, null, null);
    }

    /** 手札に加えたうえで賢魂として使う */
    private void useSoul(AutoGameFixture f, String cardId, TargetChoice... choices) {
        game.playSoulCard(f.room(), "me", f.giveHand(f.me(), cardId), List.of(choices));
    }

    // ================================================================
    // 1. エンジン: 2つの姿が分かれていること
    // ================================================================

    /**
     * ★賢魂として使うとき払うのは n であり、ミニオンとしての印刷コストではない。
     *
     * 《グレイヴガールズファン》は印刷コスト5、賢魂：1 である。
     * マナを2枚しか置かない盤面で使えることが、n を払っている証拠になる ——
     * 5 を払う実装なら、この試験は「MPが足りません」で落ちる。
     */
    @Test
    void 賢魂として使うときのコストはnである() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        useSoul(f, GRAVE_GIRLS);
        assertThat(f.me().getAvailableMp()).isEqualTo(1);
        assertThat(f.me().getMinionZone()).isEmpty(); // 場には出ない
    }

    /**
     * ★<b>ミニオンとして召喚した場合、賢魂の効果は発動しない</b>(裁定152)。
     *
     * 「そうでない側」の試験である(裁定181)。上の試験だけでは、
     * <b>どちらの使い方でもドローする</b>実装でも通ってしまう。
     */
    @Test
    void ミニオンとして召喚しても賢魂の効果は発動しない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        int deckBefore = f.me().getDeck().size();
        game.playCard(f.room(), "me", f.giveHand(f.me(), GRAVE_GIRLS), List.of(), false);
        assertThat(f.fieldIds(f.me())).containsExactly(GRAVE_GIRLS);
        // 賢魂は「1枚引いて1枚ミル」。どちらも起きないので山札は動かない
        assertThat(f.me().getDeck().size()).isEqualTo(deckBefore);
        assertThat(f.me().getTrash()).isEmpty();
    }

    /**
     * ★賢魂として使ったカードは、通常のスペルと同じく墓地へ行く(マスター裁定 A1)。
     */
    @Test
    void 賢魂として使い終わったカードは墓地へ行く() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        useSoul(f, GRAVE_GIRLS);
        // 墓地には「使い終わったこのカード」と「ミルした1枚」の2枚が入る
        assertThat(f.me().getTrash()).contains(GRAVE_GIRLS);
    }

    /**
     * ★<b>【還元】は賢魂としての姿にだけ付いている</b>(マスター裁定 B1)。
     *
     * 《白ノ霊知者》のテキストは【賢魂：2】より後ろに【還元】を書いている。
     * スペルとして使い終われば裏向きでマナに置かれる。
     */
    @Test
    void 還元を持つ賢魂は使用後に裏向きでマナへ置かれる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        MinionInstance ally = f.putOnField(f.me(), PLAIN_MINION);
        int manaBefore = f.me().getManaZone().size();

        useSoul(f, SHIRONO, minions(ally.getInstanceId()));

        assertThat(f.me().getTrash()).doesNotContain(SHIRONO);
        assertThat(f.me().getManaZone()).hasSize(manaBefore + 1);
        ManaCard placed = f.me().getManaZone().get(f.me().getManaZone().size() - 1);
        assertThat(placed.getCardId()).isEqualTo(SHIRONO);
        assertThat(placed.isFaceUp()).isFalse();
    }

    /**
     * ★<b>ミニオンとしての姿は【還元】を持たない</b>(マスター裁定 B1 の裏側)。
     *
     * これが 54 で変わった振る舞いである —— 53 まで、抽出層は本文全体を見ていたので
     * 《白ノ霊知者》は本体も【還元】を持っていた。
     */
    @Test
    void 白ノ霊知者はミニオンとしては還元を持たない() {
        assertThat(f_keywords(SHIRONO)).doesNotContain(com.example.qte.master.Keyword.RESTORATION);
        assertThat(com.example.qte.master.CardTextKeywords.soulKeywords(cards.findById(SHIRONO).text()))
                .contains(com.example.qte.master.Keyword.RESTORATION);
    }

    private java.util.Set<com.example.qte.master.Keyword> f_keywords(String cardId) {
        return cards.findById(cardId).keywords();
    }

    /**
     * ★賢魂は<b>スペルの使用</b>として数える(マスター裁定 A2(1))。
     * 《詠唱の疾風騎士》のコスト軽減や、リーダー《詠唱の風詠士》の「3枚目」が参照する。
     */
    @Test
    void 賢魂の使用はスペルの使用として数える() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        assertThat(f.me().getSpellsCastThisTurn()).isZero();
        useSoul(f, GRAVE_GIRLS);
        assertThat(f.me().getSpellsCastThisTurn()).isEqualTo(1);
        assertThat(f.me().getCardsUsedThisTurn()).isEqualTo(1);
    }

    /**
     * ★賢魂は<b>スペルのコスト軽減を受ける</b>(裁定152・マスター裁定 A2(4))。
     * 《唱導の聖騎士》(自分のスペルのコスト-1)が場に居ると、賢魂：3 が 2 になる。
     */
    @Test
    void 賢魂はスペルのコスト軽減を受ける() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), CHANT_PALADIN);
        int printed = com.example.qte.master.CardTextKeywords.soulCost(cards.findById(TAIGARAMU).text());
        assertThat(printed).isEqualTo(3);
        assertThat(stats.effectiveSoulCost(f.state(), f.me(), f.card(TAIGARAMU), printed)).isEqualTo(2);
        // ★ミニオンとしての印刷コスト(7)は軽減されない —— あちらはスペルではない
        assertThat(stats.effectiveCost(f.state(), f.me(), f.card(TAIGARAMU))).isEqualTo(7);
    }

    /**
     * ★賢魂はサブフェイズでも使える(マスター裁定 A4)。スペルの使用だからである。
     * ミニオンとしての召喚はメインフェイズだけなので、こちらは弾かれる。
     */
    @Test
    void 賢魂はサブフェイズでも使えるがミニオンとしての召喚はできない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 9);
        f.state().setPhase(TurnPhase.SUB);
        int handIndex = f.giveHand(f.me(), GRAVE_GIRLS);
        assertThatThrownBy(() -> game.playCard(f.room(), "me", handIndex, List.of(), false))
                .isInstanceOf(IllegalStateException.class);
        game.playSoulCard(f.room(), "me", handIndex, List.of());
        assertThat(f.me().getTrash()).contains(GRAVE_GIRLS);
    }

    /**
     * ★<b>賢魂として使うなら進化素材は要らない</b>(マスター裁定 A5)。
     * 召喚ではなくスペルの使用だからである(裁定226 は「召喚である」ことが理由だった)。
     */
    @Test
    void 進化ミニオンを賢魂として使うとき素材は要らない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 1);
        assertThat(f.me().getMinionZone()).isEmpty(); // 素材になりうるミニオンが1体も居ない
        useSoul(f, KURONO, none(), none());
        assertThat(f.me().getTrash()).contains(KURONO);
    }

    /**
     * ★【賢魂】を持たないカードにこの入口を使うことはできない。
     *
     * ★<b>文言まで測るのは、2つの理由を混ぜないためである</b>(★壊し検証14 が教えた) ——
     * 「そもそも【賢魂】を持たない」と「【賢魂】はあるが効果が未実装」は別の話であり、
     * 前者の検査を外しても後者の検査が拾ってしまう。
     * 「【賢魂】」だけを見る試験では、<b>前者の検査を丸ごと消しても落ちなかった</b>。
     */
    @Test
    void 賢魂を持たないカードは賢魂として使えない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        int handIndex = f.giveHand(f.me(), SPLASH_DRAW);
        assertThatThrownBy(() -> game.playSoulCard(f.room(), "me", handIndex, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("このカードは【賢魂】を持ちません");
    }

    /**
     * ★<b>登録とテキストは食い違ってはいけない</b>(★Batch 54)。
     *
     * {@code soulSpells} に載っているカードは、必ずテキストに【賢魂：n】を持つ ——
     * n の出どころはテキスト1つだからである({@link SoulSpellSpec} の説明)。
     * 食い違うと、登録はあるのにコストが読めない(または逆)という状態が生まれる。
     */
    @Test
    void 賢魂の登録とテキストは一致している() {
        for (com.example.qte.master.CardMaster card : cards.getAllCards()) {
            boolean registered = registry.soulSpellOf(card.id()) != null;
            boolean inText = com.example.qte.master.CardTextKeywords.hasSoul(card.text());
            assertThat(registered)
                    .as(card.name() + ": 登録=" + registered + " / テキスト=" + inText)
                    .isEqualTo(inText);
        }
    }

    /**
     * ★禁忌デッキからも賢魂として使える(マスター裁定 A6)。<b>退けるマナは n 枚</b>である。
     * ★使い終わったカードは<b>消滅する</b>(総合ルール3-6)。墓地には行かない。
     */
    @Test
    void 禁忌デッキの賢魂はn枚のマナを退けて使える() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        f.me().getTabooDeck().add(GRAVE_GIRLS);

        game.playTabooSoulCard(f.room(), "me", 0, List.of(0), List.of());

        assertThat(f.me().getTabooDeck()).isEmpty();
        assertThat(f.me().getLostZone()).containsExactly(GRAVE_GIRLS);
        assertThat(f.me().getTrash()).doesNotContain(GRAVE_GIRLS);
        // 退けたのは1枚(賢魂：１)であり、印刷コストの5枚ではない
        assertThat(f.me().getManaZone().stream().filter(ManaCard::isFaceUp).count()).isEqualTo(2);
    }

    // ================================================================
    // 2. 7枚のカード
    // ================================================================

    /** グレイヴガールズファン: 1枚引き、その後山札の上から1枚を墓地に置く */
    @Test
    void グレイヴガールズファンの賢魂は1枚引いて1枚墓地に置く() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 1);
        f.stackDeck(f.me(), PLAIN_MINION, LIGHT_GUARD);
        int deckBefore = f.me().getDeck().size();

        useSoul(f, GRAVE_GIRLS);

        assertThat(f.me().getDeck().size()).isEqualTo(deckBefore - 2); // 1引いて1ミル
        assertThat(f.me().getHand()).contains(PLAIN_MINION);
        assertThat(f.me().getTrash()).contains(LIGHT_GUARD);
    }

    /**
     * ★スタンディングテントの賢魂は<b>使用しているカード自身を場に出す</b>(マスター裁定 B6-1)。
     * 【召喚時】の2ドローは起きず、2ダメージだけを受けた状態で立つ。
     */
    @Test
    void スタンディングテントの賢魂は自身を場に出し召喚時は発動しない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        int deckBefore = f.me().getDeck().size();

        useSoul(f, STANDING_TENT);

        assertThat(f.fieldIds(f.me())).containsExactly(STANDING_TENT);
        assertThat(f.me().getDeck().size()).isEqualTo(deckBefore); // 2ドローは起きない
        assertThat(f.me().getTrash()).doesNotContain(STANDING_TENT); // 場に居るので墓地には行かない
        MinionInstance placed = f.me().getMinionZone().get(0);
        assertThat(placed.getDamage()).isEqualTo(2);
    }

    /** ★「そうでない側」: ミニオンとして召喚すれば【召喚時】の2ドローは起きる */
    @Test
    void スタンディングテントをミニオンとして召喚すると2枚引く() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        int deckBefore = f.me().getDeck().size();
        game.playCard(f.room(), "me", f.giveHand(f.me(), STANDING_TENT), List.of(), false);
        assertThat(f.me().getDeck().size()).isEqualTo(deckBefore - 2);
        assertThat(f.me().getMinionZone().get(0).getDamage()).isZero();
    }

    /**
     * ★場が満杯なら場に出ず、このカードは墓地へ行く(マスター裁定 B6-2)。
     * ★<b>宙に浮かない</b>ことがここの主眼である ——
     * 手札にも場にも墓地にも居ないカードが生まれてはいけない。
     */
    @Test
    void スタンディングテントの賢魂は場が満杯なら墓地へ行く() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        for (int i = 0; i < f.me().getMinionZoneLimit(); i++) {
            f.putOnField(f.me(), PLAIN_MINION);
        }
        useSoul(f, STANDING_TENT);
        assertThat(f.fieldIds(f.me())).doesNotContain(STANDING_TENT);
        assertThat(f.me().getTrash()).containsExactly(STANDING_TENT);
        assertThat(f.me().getHand()).doesNotContain(STANDING_TENT);
    }

    /**
     * ★タイガラムの【召喚時】は、手札から【守護】を持つ進化ではないミニオンを1体出す。
     * ★効果による「出す」なので、出したミニオンの【召喚時】は発動しない(裁定245)。
     */
    @Test
    void タイガラムの召喚時は手札から守護ミニオンを場に出す() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 7);
        MinionInstance material = f.putOnField(f.me(), LIGHT_GUARD);
        int guardIndex = f.giveHand(f.me(), LIGHT_GUARD);

        game.playCard(f.room(), "me", f.giveHand(f.me(), TAIGARAMU), List.of(hand(guardIndex)),
                false, List.of(material.getInstanceId()));

        assertThat(f.fieldIds(f.me())).containsExactlyInAnyOrder(TAIGARAMU, LIGHT_GUARD);
        assertThat(f.me().getHand()).doesNotContain(LIGHT_GUARD);
    }

    /** ★タイガラムの賢魂は2枚引く(進化素材も対象も要らない) */
    @Test
    void タイガラムの賢魂は2枚引く() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        int deckBefore = f.me().getDeck().size();
        useSoul(f, TAIGARAMU, none());
        assertThat(f.me().getDeck().size()).isEqualTo(deckBefore - 2);
    }

    /** ★黒ノ霊導者の賢魂: 自分1体を破壊し、そうしたら相手1体に3ダメージ */
    @Test
    void 黒ノ霊導者の賢魂は自分を破壊してから相手に3ダメージを与える() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 1);
        MinionInstance sacrifice = f.putOnField(f.me(), PLAIN_MINION);
        MinionInstance victim = f.putOnField(f.you(), LIGHT_GUARD); // 1/3

        useSoul(f, KURONO, minions(sacrifice.getInstanceId()), minions(victim.getInstanceId()));

        assertThat(f.me().getMinionZone()).isEmpty();
        assertThat(f.you().getMinionZone()).isEmpty(); // HP3に3ダメージで破壊される
    }

    /**
     * ★自分のミニオンが1体も居なくても使用でき、何も起こらない(マスター裁定 B3-1)。
     * ★相手のミニオンには<b>ダメージが入らない</b> —— 「そうしたら」を満たさないためである。
     */
    @Test
    void 黒ノ霊導者の賢魂は自分のミニオンが居なくても使えるが何も起こらない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 1);
        MinionInstance victim = f.putOnField(f.you(), LIGHT_GUARD);

        useSoul(f, KURONO, none(), minions(victim.getInstanceId()));

        assertThat(victim.getDamage()).isZero();
        assertThat(f.me().getTrash()).contains(KURONO);
    }

    /**
     * ★白ノ霊知者の【召喚時】: 2枚引き、<b>割り込みで</b>破壊するミニオンを選ぶ。
     * ★候補は両者の場である(裁定156(2))。ここでは相手のミニオンを選ぶ。
     */
    @Test
    void 白ノ霊知者の召喚時は2枚引いてから割り込みで1体破壊する() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        MinionInstance material = f.putOnField(f.me(), WIND_PLAIN);
        MinionInstance victim = f.putOnField(f.you(), PLAIN_MINION);
        int deckBefore = f.me().getDeck().size();

        game.playCard(f.room(), "me", f.giveHand(f.me(), SHIRONO), List.of(), false,
                List.of(material.getInstanceId()));

        assertThat(f.me().getDeck().size()).isEqualTo(deckBefore - 2);
        var choice = f.me().getPendingChoice();
        assertThat(choice).isNotNull();
        // 候補は両者の場 = 進化した自身 + 相手の1体
        assertThat(choice.candidates()).hasSize(2)
                .contains(victim.getInstanceId());
        game.resolveChoice(f.room(), "me",
                List.of(choice.candidates().indexOf(victim.getInstanceId())));
        assertThat(f.you().getMinionZone()).isEmpty();
        assertThat(f.me().getMinionZone()).hasSize(1); // 進化した自身は残る
    }

    /** ★白ノ霊知者の賢魂: 自分のミニオン1体の攻撃力+1(その後、還元でマナへ) */
    @Test
    void 白ノ霊知者の賢魂は自分のミニオンの攻撃力を1上げる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        MinionInstance ally = f.putOnField(f.me(), PLAIN_MINION); // 2/1
        int before = stats.effectiveAttack(f.state(), f.me(), ally);

        useSoul(f, SHIRONO, minions(ally.getInstanceId()));

        assertThat(stats.effectiveAttack(f.state(), f.me(), ally)).isEqualTo(before + 1);
    }

    /** ★対象が必須なので、自分のミニオンが1体も居なければ使えない(マスター裁定 A7) */
    @Test
    void 白ノ霊知者の賢魂は自分のミニオンが居ないと使えない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        int handIndex = f.giveHand(f.me(), SHIRONO);
        assertThatThrownBy(() -> game.playSoulCard(f.room(), "me", handIndex, List.of(none())))
                .isInstanceOf(RuntimeException.class);
        assertThat(f.me().getHand()).contains(SHIRONO); // 弾かれたので盤面は動いていない
    }

    /**
     * ★愚乱怒土地の賢魂: 山札の上2枚を見て、1枚を裏向きでマナへ、もう1枚を手札へ。
     * ★選択は割り込みであり、選んだほうがマナへ行く。
     */
    @Test
    void 愚乱怒土地の賢魂は2枚見て1枚をマナに1枚を手札に加える() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        f.stackDeck(f.me(), LIGHT_GUARD, PLAIN_MINION);
        int manaBefore = f.me().getManaZone().size();

        useSoul(f, GURANDORANDO);

        var choice = f.me().getPendingChoice();
        assertThat(choice).isNotNull();
        assertThat(choice.candidates()).containsExactly("0", "1");
        game.resolveChoice(f.room(), "me", List.of(0)); // 1枚目(ライト・シールド)をマナへ

        assertThat(f.me().getManaZone()).hasSize(manaBefore + 1);
        ManaCard placed = f.me().getManaZone().get(f.me().getManaZone().size() - 1);
        assertThat(placed.getCardId()).isEqualTo(LIGHT_GUARD);
        assertThat(placed.isFaceUp()).isFalse();
        assertThat(f.me().getHand()).contains(PLAIN_MINION);
        assertThat(f.me().getRevealedZone()).isEmpty();
    }

    /**
     * ★勝阿外の賢魂: 山札の上1枚を裏向きでマナへ置き、
     * <b>置いた後の</b>マナが3枚以下なら1枚引く(マスター裁定 B8-4)。
     */
    @Test
    void 勝阿外の賢魂はマナを増やしマナが3枚以下なら1枚引く() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2); // 支払いで2枚とも使うが、マナゾーンの枚数は2のまま
        f.stackDeck(f.me(), LIGHT_GUARD, PLAIN_MINION);
        int deckBefore = f.me().getDeck().size();

        useSoul(f, KATSUAGE);

        // マナは 2 → 3(3枚以下なので1枚引く)。山札は「マナへ1枚 + ドロー1枚」で2枚減る
        assertThat(f.me().getManaZone()).hasSize(3);
        assertThat(f.me().getManaZone().get(2).isFaceUp()).isFalse();
        assertThat(f.me().getDeck().size()).isEqualTo(deckBefore - 2);
        assertThat(f.me().getHand()).contains(PLAIN_MINION);
    }

    /**
     * ★マナが4枚以上になるならドローしない(条件の「そうでない側」)。
     *
     * ★<b>マナ3枚の盤面から始めるのが要点である。</b> 置く前は3枚(条件を満たす)、
     * 置いた後は4枚(満たさない)なので、<b>判定をどちらで行っているか</b>がここで初めて分かれる
     * (マスター裁定 B8-4: 置いた後で見る)。5枚から始めるとどちらでも同じ結果になり、
     * 何も測っていないことになる(裁定181)。
     */
    @Test
    void 勝阿外の賢魂はマナが4枚以上になるとドローしない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        int deckBefore = f.me().getDeck().size();
        useSoul(f, KATSUAGE);
        assertThat(f.me().getManaZone()).hasSize(4);
        assertThat(f.me().getDeck().size()).isEqualTo(deckBefore - 1); // マナへ置いた1枚だけ
    }

    /** ★勝阿外の【常在】: 相手の手札1枚につき、このミニオンの攻撃力+1 */
    @Test
    void 勝阿外の攻撃力は相手の手札の枚数だけ上がる() {
        AutoGameFixture f = newGame();
        MinionInstance katsuage = f.putOnField(f.me(), KATSUAGE); // 印刷 1/3
        assertThat(stats.effectiveAttack(f.state(), f.me(), katsuage)).isEqualTo(1);
        f.giveHand(f.you(), PLAIN_MINION);
        f.giveHand(f.you(), PLAIN_MINION);
        assertThat(stats.effectiveAttack(f.state(), f.me(), katsuage)).isEqualTo(3);
    }

    /**
     * ★勝阿外の【常在】: 相手はスペルを唱えられない。
     * ★<b>賢魂としての使用も止まる</b>(マスター裁定 A2(3)・B8-2)。
     * ★<b>自分は止まらない</b> —— 「相手は」と書いてあるからである(裁定181 の「そうでない側」)。
     */
    @Test
    void 勝阿外が場に居ると相手はスペルも賢魂も使えない() {
        AutoGameFixture f = newGame();
        f.putOnField(f.you(), KATSUAGE); // 相手の場に置く
        payMana(f.me(), 5);
        int spellIndex = f.giveHand(f.me(), SPLASH_DRAW);
        assertThatThrownBy(() -> game.playCard(f.room(), "me", spellIndex, List.of(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("勝阿外");
        int soulIndex = f.giveHand(f.me(), GRAVE_GIRLS);
        assertThatThrownBy(() -> game.playSoulCard(f.room(), "me", soulIndex, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("勝阿外");
    }

    /** ★自分の場の勝阿外は自分を止めない */
    @Test
    void 自分の場の勝阿外は自分のスペルを止めない() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), KATSUAGE);
        payMana(f.me(), 5);
        game.playCard(f.room(), "me", f.giveHand(f.me(), SPLASH_DRAW), List.of(), false);
        assertThat(f.me().getTrash()).contains(SPLASH_DRAW);
    }

    // ================================================================
    // 3. テキストの読み方(【賢魂：n】でテキストが2つに割れること)
    // ================================================================

    /**
     * ★n はカードテキストが唯一の出どころである(裁定158 の延長)。
     * ★<b>《グレイヴガールズファン》だけ全角の「１」で書かれている</b> ——
     * 読む側が両方を取ることを、実際のカードデータで測る。
     */
    @Test
    void 賢魂のコストは全角でも半角でも読める() {
        assertThat(com.example.qte.master.CardTextKeywords
                .soulCost(cards.findById(GRAVE_GIRLS).text())).isEqualTo(1); // 全角「１」
        assertThat(com.example.qte.master.CardTextKeywords
                .soulCost(cards.findById(KATSUAGE).text())).isEqualTo(2);    // 半角「2」
        assertThat(com.example.qte.master.CardTextKeywords
                .soulCost(cards.findById(SPLASH_DRAW).text())).isNull();
    }

    /**
     * ★【賢魂：n】より前のキーワードはミニオンの姿、後ろは賢魂の姿に属する。
     * 7枚すべてで境目が正しく取れていることを測る。
     */
    @Test
    void 賢魂の手前のキーワードはミニオンの姿に属する() {
        // 【守護】は賢魂より前にある(グレイヴガールズファン・スタンディングテント・タイガラム・黒ノ霊導者)
        for (String cardId : List.of(GRAVE_GIRLS, STANDING_TENT, TAIGARAMU, KURONO)) {
            assertThat(cards.findById(cardId).keywords())
                    .as(cards.findById(cardId).name())
                    .contains(com.example.qte.master.Keyword.GUARD);
        }
        // 【威圧】も賢魂より前(愚乱怒土地)
        assertThat(cards.findById(GURANDORANDO).keywords())
                .contains(com.example.qte.master.Keyword.INTIMIDATE);
        // ★賢魂より後ろにあるキーワードは1枚だけ(白ノ霊知者の【還元】)
        assertThat(cards.getAllCards().stream()
                .filter(c -> !com.example.qte.master.CardTextKeywords
                        .soulKeywords(c.text()).isEmpty())
                .map(com.example.qte.master.CardMaster::id).toList())
                .containsExactly(SHIRONO);
    }
}
