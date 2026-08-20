package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.effect.StatCalculator;
import com.example.qte.effect.TargetChoice;
import com.example.qte.game.GameService;
import com.example.qte.game.MinionInstance;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.Keyword;
import com.example.qte.support.AutoGameFixture;

/**
 * Ver1.1 で追加された闇文明6枚・光文明6枚の挙動の試験(★Batch 50)。
 *
 * <h2>足場は 48/49 のものをそのまま使う</h2>
 *
 * {@link AutoGameFixture} の上に書き、効果は {@code GameService.playCard} /
 * {@code specialSummon} / {@code attack} / {@code endTurn} / {@code nextPhase} という
 * <b>本物の入口</b>から起こす(裁定187)。このバッチでいちばん壊れやすいのは
 * 「発火する場所」である ——
 *
 * <ul>
 * <li>カムバックキーパーが反応する「場以外から墓地へ」は<b>4つの経路</b>を持ち、
 *     場を離れる経路とは<b>混ざってはならない</b>。</li>
 * <li>演舞の墓守が乗るのは「墓地から場へ」だけで、手札からの召喚には乗らない。</li>
 * <li>光霊・モアニールの2つの置換は、登場3経路とダメージ3経路にまたがる。</li>
 * </ul>
 *
 * どれもトリガーを直接叩いたのでは何も守れない。
 *
 * <h2>測り方の方針(48・49 から継続)</h2>
 *
 * <ul>
 * <li><b>裁定を名指しで固定し、「そうでない側」も測る</b>(裁定181)。
 *     「戻ってくる」だけを測ると<b>いつでも戻ってくる</b>実装でも通る ——
 *     だから「場で破壊されたときは戻らない」を並べて置く。</li>
 * <li><b>数ではなく結果を見る。</b> ドロー数は<b>山札の減り</b>で測る。</li>
 * </ul>
 */
@SpringBootTest
class DarkLightVer11EffectTest {

    /** 常在効果を持たないリーダー(既定)。蒼海の賢者は起動能力しか持たない */
    private static final String PLAIN_LEADER = "QTE-M-WATER-1";

    // ---- このバッチが実装した12枚 ----
    private static final String GRAVE_DANCER = "QTE-M-DARK-29";   // 演舞の墓守(リーダー)
    private static final String DEVILS_MIC = "QTE-M-DARK-33";     // デビルズマイク
    private static final String SUMMONS_LIGHT = "QTE-M-DARK-34";  // サモンズライト
    private static final String KEEPER = "QTE-M-DARK-35";         // カムバックキーパー
    private static final String NEON_STAGE = "QTE-M-DARK-36";     // ダークネオンステージ
    private static final String DREAMY = "QTE-M-DARK-39";         // 1stL「NEMれぬ夜のドリーミー」
    private static final String ANTOMARUEL = "QTE-M-LIGHT-29";    // 英皇アントマルエル(リーダー)
    private static final String TENGSUN = "QTE-M-LIGHT-34";       // 光霊・テングスン
    private static final String NEFRA = "QTE-M-LIGHT-35";         // 光霊・ネフラ
    private static final String MOANIRU = "QTE-M-LIGHT-36";       // 光霊・モアニール
    private static final String GRANIS = "QTE-M-LIGHT-37";        // 英術・グラーニス
    private static final String BANYU = "QTE-M-LIGHT-38";         // 英術・バンユー

    // ---- 道具として使う既存カード ----
    /** コスト1・2/1・キーワードなし(フレア・ポーン)。★【知識】を持たないことが重要である */
    private static final String PLAIN_MINION = "QTE-M-FIRE-2";
    /** コスト1・1/1・【守護】【突進】(疾風の先陣)。ネフラが拾う役 */
    private static final String GUARD_MINION = "QTE-M-WIND-16";
    /** コスト2・1/2(サイクロン・フェンサー)。サモンズライトの「コスト1でない」役 */
    private static final String COST2_MINION = "QTE-M-WIND-5";
    /** コスト2のスペル(スプラッシュ・ドロー)。テングスンとバンユーの測定対象 */
    private static final String SPELL_DRAW = "QTE-M-WATER-9";
    /** コスト3・手札を1枚捨てる(血の対価)。「手札から墓地へ」を起こす役 */
    private static final String BLOOD_PRICE = "QTE-M-FIRE-25";
    /** コスト3・山札の上から3枚を墓地へ(墓穴の呪い)。「山札から墓地へ」を起こす役 */
    private static final String GRAVE_CURSE = "QTE-M-DARK-24";
    /** コスト5・相手のミニオン1体を破壊(冥府への道)。「場から墓地へ」を起こす役 */
    private static final String UNDERWORLD_ROAD = "QTE-M-DARK-26";
    /** コスト6・墓地からミニオン1体を蘇生(死者蘇生)。「墓地から場へ」を起こす役 */
    private static final String RAISE_DEAD = "QTE-M-DARK-12";
    /** コスト1・自分のリーダーに2ダメージ(イグニッション・バースト)。効果ダメージ役 */
    private static final String IGNITION = "QTE-M-FIRE-9";
    /** コスト1・ミニオン1体に3ダメージ(マグマ・ストレート)。破壊数を先に稼ぐ役 */
    private static final String MAGMA = "QTE-M-FIRE-10";

    @Autowired
    GameService game;

    @Autowired
    StatCalculator stats;

    @Autowired
    CardMasterRepository cards;

    private AutoGameFixture newGame() {
        return newGame(PLAIN_LEADER);
    }

    private AutoGameFixture newGame(String myLeaderId) {
        AutoGameFixture f = new AutoGameFixture(cards, myLeaderId, PLAIN_LEADER);
        f.fillDeck(f.me(), 30);
        f.fillDeck(f.you(), 30);
        return f;
    }

    private static TargetChoice hand(Integer... indexes) {
        return new TargetChoice(List.of(indexes), null, null, null, null);
    }

    private static TargetChoice minions(String... instanceIds) {
        return new TargetChoice(null, List.of(instanceIds), null, null, null);
    }

    // ==================================================================
    // 演舞の墓守(QTE-M-DARK-29・リーダー)
    // 「【常在】自分の墓地から出たミニオンのAttackをそのターン+1」
    // ==================================================================

    /**
     * ★マスター裁定204: 「墓地から出た」は<b>経路を問わない</b>。
     * 死者蘇生は効果による「出す」であり、召喚ではない。
     */
    @Test
    void 墓守は墓地から出たミニオンの攻撃力を1上げる() {
        AutoGameFixture f = newGame(GRAVE_DANCER);
        f.giveMana(f.me(), 6);
        f.me().getTrash().add(PLAIN_MINION);
        game.playCard(f.room(), "me", f.giveHand(f.me(), RAISE_DEAD), List.of(minions()), false);

        assertThat(f.fieldIds(f.me())).containsExactly(PLAIN_MINION);
        MinionInstance revived = f.me().getMinionZone().get(0);
        assertThat(stats.effectiveAttack(f.state(), f.me(), revived))
                .as("印刷2 + 墓守の1")
                .isEqualTo(3);
    }

    /**
     * ★「そうでない側」。墓守以外のリーダーでは何も乗らない。
     * これが無いと、<b>蘇生したら誰でも+1</b>の実装でも上の試験は通る(裁定181)。
     */
    @Test
    void 墓守でないリーダーでは蘇生しても攻撃力は上がらない() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 6);
        f.me().getTrash().add(PLAIN_MINION);
        game.playCard(f.room(), "me", f.giveHand(f.me(), RAISE_DEAD), List.of(minions()), false);
        assertThat(stats.effectiveAttack(f.state(), f.me(), f.me().getMinionZone().get(0)))
                .isEqualTo(2);
    }

    /** ★「そのターン」なので、ターンが終われば元に戻る(THIS_TURN の修正である) */
    @Test
    void 墓守の加算はそのターンで切れる() {
        AutoGameFixture f = newGame(GRAVE_DANCER);
        f.giveMana(f.me(), 6);
        f.me().getTrash().add(PLAIN_MINION);
        game.playCard(f.room(), "me", f.giveHand(f.me(), RAISE_DEAD), List.of(minions()), false);
        MinionInstance revived = f.me().getMinionZone().get(0);
        assertThat(stats.effectiveAttack(f.state(), f.me(), revived)).isEqualTo(3);

        game.endTurn(f.room(), "me");
        assertThat(stats.effectiveAttack(f.state(), f.me(), revived))
                .as("ターンが終われば元の印刷値に戻る")
                .isEqualTo(2);
    }

    /** ★乗るのは「墓地から出た」ミニオンだけである。手札からの召喚には乗らない */
    @Test
    void 墓守は手札から召喚したミニオンには乗らない() {
        AutoGameFixture f = newGame(GRAVE_DANCER);
        f.giveMana(f.me(), 3);
        game.playCard(f.room(), "me", f.giveHand(f.me(), PLAIN_MINION), List.of(), false);
        assertThat(stats.effectiveAttack(f.state(), f.me(), f.me().getMinionZone().get(0)))
                .isEqualTo(2);
    }

    // ==================================================================
    // デビルズマイク(QTE-M-DARK-33)
    // 「攻撃時、相手のリーダーに1ダメージ」
    // ==================================================================

    /** ★攻撃対象がリーダーでも発動する(戦闘1 + 効果1 = 2)。対象を限定していないため */
    @Test
    void デビルズマイクはリーダーを攻撃すると合計2ダメージになる() {
        AutoGameFixture f = newGame();
        MinionInstance mic = f.putOnField(f.me(), DEVILS_MIC);
        game.nextPhase(f.room(), "me"); // メイン → バトル
        int before = f.you().getLp();
        game.attack(f.room(), "me", mic.getInstanceId(), null);
        assertThat(before - f.you().getLp()).isEqualTo(2);
    }

    /** ★ミニオンを攻撃したときも、相手の<b>リーダー</b>に1ダメージが入る */
    @Test
    void デビルズマイクはミニオンを攻撃しても相手リーダーに1ダメージ() {
        AutoGameFixture f = newGame();
        MinionInstance mic = f.putOnField(f.me(), DEVILS_MIC);
        MinionInstance target = f.putOnField(f.you(), PLAIN_MINION);
        game.nextPhase(f.room(), "me");
        int before = f.you().getLp();
        game.attack(f.room(), "me", mic.getInstanceId(), target.getInstanceId());
        assertThat(before - f.you().getLp())
                .as("戦闘ダメージはミニオンへ行くので、リーダーへは効果の1点だけ")
                .isEqualTo(1);
    }

    // ==================================================================
    // サモンズライト(QTE-M-DARK-34)
    // 「【召喚時】相手のミニオン1体に1ダメージ。
    //   【破壊時】自分の墓地からコスト1のミニオンを1体場に出す」
    // ==================================================================

    @Test
    void サモンズライトは召喚時に相手のミニオン1体へ1ダメージ() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 3);
        MinionInstance target = f.putOnField(f.you(), PLAIN_MINION); // 2/1
        game.playCard(f.room(), "me", f.giveHand(f.me(), SUMMONS_LIGHT),
                List.of(minions(target.getInstanceId())), false);
        assertThat(f.you().getMinionZone()).as("HP1に1ダメージで破壊される").isEmpty();
    }

    /**
     * ★対象は optional である。相手の場が空でも<b>召喚そのものは通る</b>
     * (必須にすると、候補ゼロで召喚が弾かれる。腐敗の投擲者と同じ理由)。
     */
    @Test
    void サモンズライトは相手の場が空でも召喚できる() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 3);
        game.playCard(f.room(), "me", f.giveHand(f.me(), SUMMONS_LIGHT), List.of(minions()), false);
        assertThat(f.fieldIds(f.me())).containsExactly(SUMMONS_LIGHT);
    }

    /** ★【破壊時】は戦闘破壊でも起きる。墓地のコスト1ミニオンが場に出る */
    @Test
    void サモンズライトは破壊されると墓地のコスト1ミニオンを場に出す() {
        AutoGameFixture f = newGame();
        MinionInstance mine = f.putOnField(f.me(), SUMMONS_LIGHT); // 1/2
        MinionInstance yours = f.putOnField(f.you(), PLAIN_MINION); // 2/1
        f.me().getTrash().add(PLAIN_MINION);
        game.nextPhase(f.room(), "me");
        game.attack(f.room(), "me", mine.getInstanceId(), yours.getInstanceId());

        assertThat(f.fieldIds(f.me()))
                .as("サモンズライトは相打ちで消え、墓地のコスト1が入れ替わりに出る")
                .containsExactly(PLAIN_MINION);
    }

    /** ★出せるのは<b>コスト1</b>のミニオンだけである(印刷コストで判定する) */
    @Test
    void サモンズライトはコスト2のミニオンを出さない() {
        AutoGameFixture f = newGame();
        MinionInstance mine = f.putOnField(f.me(), SUMMONS_LIGHT);
        MinionInstance yours = f.putOnField(f.you(), PLAIN_MINION);
        f.me().getTrash().add(COST2_MINION);
        game.nextPhase(f.room(), "me");
        game.attack(f.room(), "me", mine.getInstanceId(), yours.getInstanceId());
        assertThat(f.me().getMinionZone()).isEmpty();
    }

    @Test
    void サモンズライトは墓地が空なら何も出さない() {
        AutoGameFixture f = newGame();
        MinionInstance mine = f.putOnField(f.me(), SUMMONS_LIGHT);
        MinionInstance yours = f.putOnField(f.you(), PLAIN_MINION);
        game.nextPhase(f.room(), "me");
        game.attack(f.room(), "me", mine.getInstanceId(), yours.getInstanceId());
        assertThat(f.me().getMinionZone()).isEmpty();
    }

    // ==================================================================
    // カムバックキーパー(QTE-M-DARK-35)
    // 「場以外から自分の墓地に置かれたときに墓地からこのカードを場に出す。【守護】」
    // ==================================================================

    /** ★経路1: 手札から捨てられた */
    @Test
    void キーパーは手札から捨てられると場に戻る() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 3);
        int spell = f.giveHand(f.me(), BLOOD_PRICE);
        int keeper = f.giveHand(f.me(), KEEPER);
        game.playCard(f.room(), "me", spell, List.of(hand(keeper)), false);

        assertThat(f.fieldIds(f.me())).containsExactly(KEEPER);
        assertThat(f.me().getTrash()).doesNotContain(KEEPER);
    }

    /**
     * ★経路2: 山札から墓地へ(ミル)。
     *
     * <p>墓穴の呪いは「山札の上から3枚を墓地に置き、墓地の枚数以下のHPのミニオンを破壊」する。
     * 3枚のうち先頭がキーパーなので、キーパーは墓地を経由して場に戻り、
     * 残り2枚だけが墓地に残る(=しきい値2)。キーパーのHPは4なので、
     * <b>戻った直後の全体破壊には巻き込まれない</b>。
     */
    @Test
    void キーパーは山札から墓地に置かれても場に戻る() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 3);
        f.stackDeck(f.me(), KEEPER, PLAIN_MINION, PLAIN_MINION);
        game.playCard(f.room(), "me", f.giveHand(f.me(), GRAVE_CURSE), List.of(), false);
        assertThat(f.fieldIds(f.me())).containsExactly(KEEPER);
    }

    /**
     * ★<b>この試験がいちばん効く番人である。</b>
     * 「場以外から」を落とすと、<b>破壊されても永久に戻ってくる</b>ミニオンになる。
     */
    @Test
    void キーパーは場で破壊されたときは戻らない() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 5);
        MinionInstance keeper = f.putOnField(f.you(), KEEPER);
        game.playCard(f.room(), "me", f.giveHand(f.me(), UNDERWORLD_ROAD),
                List.of(minions(keeper.getInstanceId())), false);

        assertThat(f.you().getMinionZone()).isEmpty();
        assertThat(f.you().getTrash()).as("墓地に留まる").contains(KEEPER);
    }

    /**
     * ★マスター裁定203 の「そうでない側」。反応するのは<b>このカード自身</b>が
     * 置かれたときだけである。墓地に居るキーパーは、他のカードが捨てられても戻らない。
     */
    @Test
    void キーパーは他のカードが捨てられても戻らない() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 3);
        f.me().getTrash().add(KEEPER); // 直接置く(発火口を通さない)
        int spell = f.giveHand(f.me(), BLOOD_PRICE);
        int other = f.giveHand(f.me(), PLAIN_MINION);
        game.playCard(f.room(), "me", spell, List.of(hand(other)), false);

        assertThat(f.me().getMinionZone()).isEmpty();
        assertThat(f.me().getTrash()).contains(KEEPER);
    }

    /** ★場が満杯なら戻れない(墓地に留まる) */
    @Test
    void キーパーは場が満杯なら戻らない() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 3);
        for (int i = 0; i < 6; i++) {
            f.putOnField(f.me(), PLAIN_MINION);
        }
        int spell = f.giveHand(f.me(), BLOOD_PRICE);
        int keeper = f.giveHand(f.me(), KEEPER);
        game.playCard(f.room(), "me", spell, List.of(hand(keeper)), false);

        assertThat(f.me().getMinionZone()).hasSize(6);
        assertThat(f.me().getTrash()).contains(KEEPER);
    }

    // ==================================================================
    // ダークネオンステージ(QTE-M-DARK-36)
    // 「【特殊召喚】(自分の場1枚、自分の手札を2枚捨てることでこのカードを0コストとして場に出す)」
    // ==================================================================

    /** ★マスター裁定198: 「自分の場1枚」は<b>自分の場のミニオン1体を破壊する</b>ことである */
    @Test
    void ネオンステージは自分のミニオン1体と手札2枚を代償に0コストで出る() {
        AutoGameFixture f = newGame();
        MinionInstance victim = f.putOnField(f.me(), PLAIN_MINION);
        int stage = f.giveHand(f.me(), NEON_STAGE);
        int a = f.giveHand(f.me(), PLAIN_MINION);
        int b = f.giveHand(f.me(), PLAIN_MINION);

        game.specialSummon(f.room(), "me", stage,
                List.of(minions(victim.getInstanceId()), hand(a, b)));

        assertThat(f.fieldIds(f.me())).containsExactly(NEON_STAGE);
        assertThat(f.me().getTrash()).as("生贄1体 + 捨てた2枚").hasSize(3);
        assertThat(f.me().getManaZone()).as("マナを1枚も使っていない").isEmpty();
    }

    @Test
    void ネオンステージは自分の場が空なら特殊召喚できない() {
        AutoGameFixture f = newGame();
        int stage = f.giveHand(f.me(), NEON_STAGE);
        int a = f.giveHand(f.me(), PLAIN_MINION);
        int b = f.giveHand(f.me(), PLAIN_MINION);
        assertThatThrownBy(() -> game.specialSummon(f.room(), "me", stage,
                List.of(minions(), hand(a, b))))
                .isInstanceOf(IllegalStateException.class);
    }

    /** ★捨てる2枚は<b>このカード自身を除いて</b>数える(自分を代償にできてはいけない) */
    @Test
    void ネオンステージは自身を除いて手札が2枚なければ特殊召喚できない() {
        AutoGameFixture f = newGame();
        MinionInstance victim = f.putOnField(f.me(), PLAIN_MINION);
        int stage = f.giveHand(f.me(), NEON_STAGE);
        int a = f.giveHand(f.me(), PLAIN_MINION); // 他は1枚だけ
        assertThatThrownBy(() -> game.specialSummon(f.room(), "me", stage,
                List.of(minions(victim.getInstanceId()), hand(a))))
                .isInstanceOf(IllegalStateException.class);
    }

    // ==================================================================
    // 1stL「NEMれぬ夜のドリーミー」(QTE-M-DARK-39)
    // 「【召喚時】他のミニオンを全て破壊する。……
    //   【常在】このターン中破壊されたミニオン1体につきこのターンの間Attack+1」
    // ==================================================================

    @Test
    void ドリーミーは召喚時に自分と相手の他のミニオンを全て破壊する() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 7);
        f.putOnField(f.me(), PLAIN_MINION);
        f.putOnField(f.you(), PLAIN_MINION);
        f.putOnField(f.you(), PLAIN_MINION);
        game.playCard(f.room(), "me", f.giveHand(f.me(), DREAMY), List.of(), false);

        assertThat(f.fieldIds(f.me())).containsExactly(DREAMY);
        assertThat(f.you().getMinionZone()).isEmpty();
    }

    /** ★自身の【召喚時】で破壊した数がそのまま攻撃力になる(印刷0 + 3体) */
    @Test
    void ドリーミーは破壊した数だけ攻撃力が上がる() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 7);
        f.putOnField(f.me(), PLAIN_MINION);
        f.putOnField(f.you(), PLAIN_MINION);
        f.putOnField(f.you(), PLAIN_MINION);
        game.playCard(f.room(), "me", f.giveHand(f.me(), DREAMY), List.of(), false);
        MinionInstance dreamy = f.me().getMinionZone().get(0);
        assertThat(stats.effectiveAttack(f.state(), f.me(), dreamy)).isEqualTo(3);
    }

    /**
     * ★マスター裁定205: 数えるのは<b>このターンに破壊された全ミニオン</b>であり、
     * ドリーミー自身が破壊した数ではない。召喚より前の破壊も含む。
     */
    @Test
    void ドリーミーは召喚より前に破壊されたミニオンも数える() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 8);
        MinionInstance victim = f.putOnField(f.you(), PLAIN_MINION); // 2/1
        game.playCard(f.room(), "me", f.giveHand(f.me(), MAGMA),
                List.of(minions(victim.getInstanceId())), false); // 3ダメージで破壊
        assertThat(f.you().getMinionZone()).isEmpty();

        game.playCard(f.room(), "me", f.giveHand(f.me(), DREAMY), List.of(), false);
        MinionInstance dreamy = f.me().getMinionZone().get(0);
        assertThat(stats.effectiveAttack(f.state(), f.me(), dreamy))
                .as("自身は0体しか破壊していないが、このターンの破壊は1体ある")
                .isEqualTo(1);
    }

    /** ★「このターンの間」なので、ターンが変われば0に戻る(カウンタは試合単位でリセットされる) */
    @Test
    void ドリーミーの攻撃力はターンが変わると戻る() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 7);
        f.putOnField(f.you(), PLAIN_MINION);
        game.playCard(f.room(), "me", f.giveHand(f.me(), DREAMY), List.of(), false);
        MinionInstance dreamy = f.me().getMinionZone().get(0);
        assertThat(stats.effectiveAttack(f.state(), f.me(), dreamy)).isEqualTo(1);

        game.endTurn(f.room(), "me");
        assertThat(stats.effectiveAttack(f.state(), f.me(), dreamy)).isZero();
    }

    /** ★10体未満なら【速攻】は得ない(現実的な盤面では滅多に満たない条件である) */
    @Test
    void ドリーミーは破壊が10体未満なら速攻を得ない() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 7);
        f.putOnField(f.you(), PLAIN_MINION);
        game.playCard(f.room(), "me", f.giveHand(f.me(), DREAMY), List.of(), false);
        assertThat(f.me().getMinionZone().get(0).hasKeyword(Keyword.HASTE)).isFalse();
    }

    // ==================================================================
    // 英皇アントマルエル(QTE-M-LIGHT-29・リーダー)
    // 「【常在】場にミニオンが出た時自分の手札が6枚以下だったらカードを1枚引く」
    // ==================================================================

    /** ★「ターンに一回」と書かれていないので<b>回数制限は無い</b>(ロロイヨとの違い) */
    @Test
    void アントマルエルは場にミニオンが出るたび引く() {
        AutoGameFixture f = newGame(ANTOMARUEL);
        f.giveMana(f.me(), 5);
        int before = f.me().getDeck().size();
        game.playCard(f.room(), "me", f.giveHand(f.me(), PLAIN_MINION), List.of(), false);
        game.playCard(f.room(), "me", f.giveHand(f.me(), PLAIN_MINION), List.of(), false);
        assertThat(before - f.me().getDeck().size())
                .as("2体目でも引く")
                .isEqualTo(2);
    }

    /** ★手札が7枚以上なら引かない(判定は引く直前のアントマルエル側の手札) */
    @Test
    void アントマルエルは手札が7枚以上なら引かない() {
        AutoGameFixture f = newGame(ANTOMARUEL);
        f.giveMana(f.me(), 5);
        for (int i = 0; i < 7; i++) {
            f.giveHand(f.me(), PLAIN_MINION);
        }
        int idx = f.giveHand(f.me(), PLAIN_MINION); // 手札8枚。出すと7枚残る
        int before = f.me().getDeck().size();
        game.playCard(f.room(), "me", idx, List.of(), false);
        assertThat(before - f.me().getDeck().size()).isZero();
    }

    /** ★「自分の」と書いていない誘発は両者を見る(裁定156(2))。相手の召喚でもこちらが引く */
    @Test
    void アントマルエルは相手のミニオンが場に出ても引く() {
        AutoGameFixture f = newGame(ANTOMARUEL);
        game.endTurn(f.room(), "me");
        game.nextPhase(f.room(), "you");
        f.giveMana(f.you(), 5);
        int before = f.me().getDeck().size();
        game.playCard(f.room(), "you", f.giveHand(f.you(), PLAIN_MINION), List.of(), false);
        assertThat(before - f.me().getDeck().size())
                .as("引くのは出した相手ではなくアントマルエル側である")
                .isEqualTo(1);
    }

    /** ★「そうでない側」。これが無いと<b>全プレイヤーが引く</b>実装でも上が通る(裁定181) */
    @Test
    void アントマルエルでないリーダーはミニオンが出ても引かない() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 5);
        int before = f.me().getDeck().size();
        game.playCard(f.room(), "me", f.giveHand(f.me(), PLAIN_MINION), List.of(), false);
        assertThat(before - f.me().getDeck().size()).isZero();
    }

    // ==================================================================
    // 光霊・テングスン(QTE-M-LIGHT-34)
    // 「【常在】相手はスペルを唱えるコスト+1される。」
    // ==================================================================

    @Test
    void テングスンは相手のスペルのコストを1上げる() {
        AutoGameFixture f = newGame();
        f.putOnField(f.you(), TENGSUN);
        assertThat(stats.effectiveCost(f.state(), f.me(), f.card(SPELL_DRAW))).isEqualTo(3);
    }

    /** ★「相手は」なので、自分の場に居ても自分のスペルは重くならない */
    @Test
    void テングスンは自分の場に居ても自分のスペルを重くしない() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), TENGSUN);
        assertThat(stats.effectiveCost(f.state(), f.me(), f.card(SPELL_DRAW))).isEqualTo(2);
    }

    /** ★2体並べば累積する(唱導の聖騎士の-1が累積するのと対称) */
    @Test
    void テングスンは2体並ぶとコストが2上がる() {
        AutoGameFixture f = newGame();
        f.putOnField(f.you(), TENGSUN);
        f.putOnField(f.you(), TENGSUN);
        assertThat(stats.effectiveCost(f.state(), f.me(), f.card(SPELL_DRAW))).isEqualTo(4);
    }

    /** ★上がるのは<b>スペルだけ</b>である。ミニオンやウェポンは重くならない */
    @Test
    void テングスンはミニオンのコストを上げない() {
        AutoGameFixture f = newGame();
        f.putOnField(f.you(), TENGSUN);
        assertThat(stats.effectiveCost(f.state(), f.me(), f.card(COST2_MINION))).isEqualTo(2);
    }

    // ==================================================================
    // 光霊・ネフラ(QTE-M-LIGHT-35)
    // 「【召喚時】自分の山札の上から3枚表向きにする。
    //   その中の【守護】を持っているもしくはスペルのカードを全て手札に加える。」
    // ==================================================================

    /** ★マスター裁定199: 条件を満たさなかった残りは<b>山札の下</b>へ置く */
    @Test
    void ネフラは守護とスペルを手札に加え残りを山札の下に置く() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 3);
        f.stackDeck(f.me(), GUARD_MINION, PLAIN_MINION, SPELL_DRAW);
        game.playCard(f.room(), "me", f.giveHand(f.me(), NEFRA), List.of(), false);

        assertThat(f.me().getHand())
                .as("【守護】とスペルだけが手札へ")
                .containsExactlyInAnyOrder(GUARD_MINION, SPELL_DRAW);
        assertThat(f.me().getDeck().peekLast())
                .as("条件を満たさなかった1枚は山札の下へ")
                .isEqualTo(PLAIN_MINION);
    }

    // ==================================================================
    // 光霊・モアニール(QTE-M-LIGHT-36)
    // 「【常在】相手は自身のマナよりコストの大きいミニオンを場に出すとき、代わりに山札の下に置く。
    //   自分のリーダーがダメージを受けるとき代わりにこのカードを破壊する。」
    // ==================================================================

    /**
     * ★マスター裁定201: 「自身のマナ」は<b>マナゾーンの枚数</b>である。
     * マナ6枚でコスト7のミニオンを蘇生しようとすると、場に出ずに山札の下へ行く。
     */
    @Test
    void モアニールはマナより重いミニオンを山札の下に送る() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 6);
        f.me().getTrash().add(DREAMY); // コスト7
        f.putOnField(f.you(), MOANIRU);
        game.playCard(f.room(), "me", f.giveHand(f.me(), RAISE_DEAD), List.of(minions()), false);

        assertThat(f.me().getMinionZone()).isEmpty();
        assertThat(f.me().getDeck().peekLast()).isEqualTo(DREAMY);
    }

    /** ★「そうでない側」。モアニールが居なければ普通に場に出る */
    @Test
    void モアニールが居なければ重いミニオンも普通に場に出る() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 6);
        f.me().getTrash().add(DREAMY);
        game.playCard(f.room(), "me", f.giveHand(f.me(), RAISE_DEAD), List.of(minions()), false);
        assertThat(f.fieldIds(f.me())).containsExactly(DREAMY);
    }

    /**
     * ★置換は<b>召喚の経路にも掛かる</b>。ダークネオンステージは0コストで出るが、
     * 比べるのは<b>印刷コスト5</b>であり、マナ0のプレイヤーでは場に出られない。
     * 代償(生贄と手札2枚)は支払い済みである —— 置換されるのは「場に出る」ことだけである。
     */
    @Test
    void モアニールは特殊召喚で出るミニオンも山札の下に送る() {
        AutoGameFixture f = newGame();
        f.putOnField(f.you(), MOANIRU);
        MinionInstance victim = f.putOnField(f.me(), PLAIN_MINION);
        int stage = f.giveHand(f.me(), NEON_STAGE);
        int a = f.giveHand(f.me(), PLAIN_MINION);
        int b = f.giveHand(f.me(), PLAIN_MINION);

        game.specialSummon(f.room(), "me", stage,
                List.of(minions(victim.getInstanceId()), hand(a, b)));

        assertThat(f.me().getMinionZone()).isEmpty();
        assertThat(f.me().getDeck().peekLast()).isEqualTo(NEON_STAGE);
    }

    /** ★マスター裁定202: 効果ダメージも肩代わりする。ダメージは0になり、モアニールが破壊される */
    @Test
    void モアニールは効果ダメージを肩代わりして自身が破壊される() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 1);
        f.me().setLp(10);
        f.putOnField(f.me(), MOANIRU);
        game.playCard(f.room(), "me", f.giveHand(f.me(), IGNITION), List.of(), false);

        assertThat(f.me().getLp()).as("自傷2ダメージが通らない").isEqualTo(10);
        assertThat(f.me().getMinionZone()).isEmpty();
    }

    /** ★戦闘ダメージも肩代わりする(裁定202 は「すべてのダメージ」である) */
    @Test
    void モアニールは戦闘ダメージも肩代わりする() {
        AutoGameFixture f = newGame();
        MinionInstance attacker = f.putOnField(f.me(), PLAIN_MINION);
        f.putOnField(f.you(), MOANIRU);
        game.nextPhase(f.room(), "me");
        int before = f.you().getLp();
        game.attack(f.room(), "me", attacker.getInstanceId(), null);

        assertThat(f.you().getLp()).isEqualTo(before);
        assertThat(f.you().getMinionZone()).isEmpty();
    }

    /** ★「そうでない側」。モアニールが居なければダメージは普通に通る */
    @Test
    void モアニールが居なければ効果ダメージは普通に通る() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 1);
        f.me().setLp(10);
        game.playCard(f.room(), "me", f.giveHand(f.me(), IGNITION), List.of(), false);
        assertThat(f.me().getLp()).isEqualTo(8);
    }

    // ==================================================================
    // 英術・グラーニス(QTE-M-LIGHT-37・スペル)
    // 「自分のリーダーの体力を2回復する。【還元】」
    // ==================================================================

    @Test
    void グラーニスは2回復して還元でマナに置かれる() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 2);
        f.me().setLp(10);
        game.playCard(f.room(), "me", f.giveHand(f.me(), GRANIS), List.of(), false);

        assertThat(f.me().getLp()).isEqualTo(12);
        assertThat(f.me().getManaZone()).as("【還元】で墓地ではなくマナへ").hasSize(3);
        assertThat(f.me().getTrash()).doesNotContain(GRANIS);
    }

    // ==================================================================
    // 英術・バンユー(QTE-M-LIGHT-38・スペル)
    // 「相手は次の相手のターン中スペルを唱えられない。
    //   相手のミニオンは次の相手のターン1度しか攻撃できない。」
    // ==================================================================

    /** バンユーを撃ってから相手のバトルフェイズまで進める */
    private AutoGameFixture castBanyuAndPassTurn() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 5);
        game.playCard(f.room(), "me", f.giveHand(f.me(), BANYU), List.of(), false);
        game.endTurn(f.room(), "me");
        game.nextPhase(f.room(), "you"); // マナチャージ → メイン
        return f;
    }

    @Test
    void バンユーは次の相手のターンのスペルを封じる() {
        AutoGameFixture f = castBanyuAndPassTurn();
        f.giveMana(f.you(), 5);
        int spell = f.giveHand(f.you(), SPELL_DRAW);
        assertThatThrownBy(() -> game.playCard(f.room(), "you", spell, List.of(), false))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * ★マスター裁定200: 「1度しか攻撃できない」は<b>相手の場全体で合計1回</b>である。
     * 「ミニオン1体につき1回」と読み違えた実装は、2体目が攻撃できてしまいここで落ちる。
     */
    @Test
    void バンユーは相手の場全体で攻撃を1回までに制限する() {
        AutoGameFixture f = castBanyuAndPassTurn();
        MinionInstance a = f.putOnField(f.you(), PLAIN_MINION);
        MinionInstance b = f.putOnField(f.you(), PLAIN_MINION);
        game.nextPhase(f.room(), "you"); // メイン → バトル

        game.attack(f.room(), "you", a.getInstanceId(), null);
        assertThatThrownBy(() -> game.attack(f.room(), "you", b.getInstanceId(), null))
                .as("別のミニオンでも2回目は攻撃できない")
                .isInstanceOf(IllegalStateException.class);
    }

    /** ★制限は「次の相手のターン」だけである。その次のターンには元に戻る */
    @Test
    void バンユーの制限は次のターンだけで切れる() {
        AutoGameFixture f = castBanyuAndPassTurn();
        MinionInstance a = f.putOnField(f.you(), PLAIN_MINION);
        MinionInstance b = f.putOnField(f.you(), PLAIN_MINION);
        game.nextPhase(f.room(), "you");
        game.attack(f.room(), "you", a.getInstanceId(), null);

        game.endTurn(f.room(), "you"); // わたしのターン
        game.endTurn(f.room(), "me");  // ふたたび相手のターン
        game.nextPhase(f.room(), "you");
        game.nextPhase(f.room(), "you");

        int before = f.me().getLp();
        game.attack(f.room(), "you", a.getInstanceId(), null);
        game.attack(f.room(), "you", b.getInstanceId(), null);
        assertThat(before - f.me().getLp()).as("2体とも攻撃できる").isEqualTo(4);
    }

    /** ★止まるのは<b>相手</b>だけである。撃った側のミニオンは何回でも攻撃できる */
    @Test
    void バンユーは自分のミニオンの攻撃を止めない() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 5);
        MinionInstance a = f.putOnField(f.me(), PLAIN_MINION);
        MinionInstance b = f.putOnField(f.me(), PLAIN_MINION);
        game.playCard(f.room(), "me", f.giveHand(f.me(), BANYU), List.of(), false);
        game.nextPhase(f.room(), "me"); // メイン → バトル

        int before = f.you().getLp();
        game.attack(f.room(), "me", a.getInstanceId(), null);
        game.attack(f.room(), "me", b.getInstanceId(), null);
        assertThat(before - f.you().getLp()).isEqualTo(4);
    }
}
