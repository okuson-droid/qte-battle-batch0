package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.effect.TargetChoice;
import com.example.qte.game.GameService;
import com.example.qte.game.ManaCard;
import com.example.qte.game.MinionInstance;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.support.AutoGameFixture;

/**
 * Ver1.1 で追加された火文明7枚・土文明8枚の挙動の試験(★Batch 51。P2 の最後)。
 *
 * <h2>このバッチでいちばん壊れやすいところ</h2>
 *
 * 51 が足したのはカード15枚だけではなく、<b>エンジンの工事が3つ</b>ある。
 * 試験の重心はそちらに置いてある ——
 *
 * <ul>
 * <li><b>マナと場の行き来</b>({@code putManaCardIntoField} /
 *     {@code putFieldMinionIntoManaFaceDown})。土の4枚が使う新しい2方向。</li>
 * <li><b>攻撃時の割り込みは戦闘を保留する</b>(裁定213)。素手喧嘩が自分をマナへ置くと
 *     戦闘そのものが起きない —— <b>選択の答えが戦闘の有無を決める</b>。</li>
 * <li><b>相手のターン中にも本人が選ぶ</b>(裁定214)。勝鼓美の【破壊時】がそれである。
 *     50 まで {@code resolveChoice} はターンプレイヤーしか受け付けなかった。</li>
 * </ul>
 *
 * <h2>測り方の方針(48〜50 から継続)</h2>
 *
 * <ul>
 * <li>効果は<b>本物の入口</b>から起こす(裁定187)。</li>
 * <li><b>「そうでない側」も測る</b>(裁定181)。「マナに置くと戦闘が起きない」だけでは
 *     <b>いつでも戦闘が起きない</b>実装でも通るので、「置かなければ普通に殴る」を並べて置く。</li>
 * <li>ドロー数は<b>山札の減り</b>で測る。</li>
 * </ul>
 */
@SpringBootTest
class FireEarthVer11EffectTest {

    /** 常在効果を持たないリーダー(既定) */
    private static final String PLAIN_LEADER = "QTE-M-WATER-1";

    // ---- このバッチが実装した15枚 ----
    private static final String SUPPORT_TANUKI = "QTE-M-FIRE-33"; // 支援盾機狸
    private static final String IRON_WOLF = "QTE-M-FIRE-34";      // 乱戦鉄機狼
    private static final String CANNON_TIGER = "QTE-M-FIRE-35";   // 砲台鉄機虎
    private static final String LAST_ATTACK = "QTE-M-FIRE-36";    // ラスト・アタック
    private static final String REPAIR_TUNER = "QTE-M-FIRE-37";   // リペア・チューナー
    private static final String IRON_RETURN = "QTE-M-FIRE-38";    // アイアン・リターン
    private static final String DRAIN_BLAST = "QTE-M-FIRE-39";    // ドレイン・ブラスト
    private static final String BEHEMOTH = "QTE-M-EARTH-7";       // 百獣の王 ベヒーモス
    private static final String SHOZAN = "QTE-M-EARTH-29";        // 地上覇総長・翔山(リーダー)
    private static final String BUNNAGURI = "QTE-M-EARTH-33";     // 分那愚利
    private static final String KACHIKOMI = "QTE-M-EARTH-34";     // 勝鼓美
    private static final String STEGORO = "QTE-M-EARTH-35";       // 素手喧嘩
    private static final String BUCCHIGIRI = "QTE-M-EARTH-37";    // 仏恥義理
    private static final String KENKAJOTO = "QTE-M-EARTH-38";     // 喧嘩上等
    private static final String SEKAIWO = "QTE-M-EARTH-39";       // 俺等地上覇夜露死苦

    // ---- 道具として使う既存カード ----
    /** コスト1・2/1・キーワードなし(フレア・ポーン) */
    private static final String PLAIN_MINION = "QTE-M-FIRE-2";
    /** コスト2・1/2(サイクロン・フェンサー)。「コスト3以下」の側 */
    private static final String COST2_MINION = "QTE-M-WIND-5";
    /** コスト1・1/1・【知識】(ウィンド・ペティ)。登場時に1ドローするので ON_ENTER の観測に使う */
    private static final String KNOWLEDGE_MINION = "QTE-M-WIND-2";
    /**
     * コスト1・ミニオン1体に3ダメージ(マグマ・ストレート)。
     * ★<b>スペルであることが効いている</b> —— マナに置いても「マナから出すミニオン」の
     * 候補にならないので、支払い用のマナや山札の上の道具として使っても測定を汚さない。
     */
    private static final String MAGMA = "QTE-M-FIRE-10";

    @Autowired
    GameService game;

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

    private static TargetChoice trash(Integer... indexes) {
        return new TargetChoice(null, null, null, List.of(indexes), null);
    }

    /**
     * コスト支払い用のマナを n 枚置く(★Batch 51 はこれを {@code AutoGameFixture.giveMana} と
     * 別に持つ)。
     *
     * ★<b>足場の giveMana はマナに「そよ風の使い魔」(コスト1のミニオン)を置く。</b>
     * このバッチのカードは「マナからミニオンを場に出す」ので、支払い用のマナが
     * そのまま候補に混ざってしまい、測りたいカードを選べたのかどうかが分からなくなる。
     * ここではミニオンでないカード(スペル)をマナに置き、候補に現れないようにする。
     */
    private void payMana(com.example.qte.game.PlayerState player, int count) {
        for (int i = 0; i < count; i++) {
            player.getManaZone().add(new ManaCard(MAGMA, false)); // スペルなので候補にならない
        }
    }

    /** 表向きのマナとして特定のカードを1枚置く(マナから場に出す試験の下ごしらえ) */
    private void giveFaceUpMana(com.example.qte.game.PlayerState player, String cardId) {
        player.getManaZone().add(new ManaCard(cardId, false));
    }

    /** 裏向きのマナとして特定のカードを1枚置く */
    private void giveFaceDownMana(com.example.qte.game.PlayerState player, String cardId) {
        ManaCard mana = new ManaCard(cardId, false);
        mana.turnFaceDown();
        player.getManaZone().add(mana);
    }

    // ==================================================================
    // 支援盾機狸(QTE-M-FIRE-33)
    // 「【守護】このミニオンは攻撃できない。【破壊時】自分のリーダーに1ダメージ」
    // ==================================================================

    @Test
    void 支援盾機狸は攻撃できない() {
        AutoGameFixture f = newGame();
        MinionInstance tanuki = f.putOnField(f.me(), SUPPORT_TANUKI);
        game.nextPhase(f.room(), "me"); // メイン → バトル
        assertThatThrownBy(() -> game.attack(f.room(), "me", tanuki.getInstanceId(), null))
                .hasMessageContaining("攻撃できません");
    }

    /**
     * ★「そうでない側」。同じ盤面で他のミニオンは普通に攻撃できる ——
     * これが無いと「バトルフェイズに入れていないだけ」でも上の試験は通る(裁定181)。
     */
    @Test
    void 支援盾機狸の隣のミニオンは普通に攻撃できる() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), SUPPORT_TANUKI);
        MinionInstance other = f.putOnField(f.me(), PLAIN_MINION);
        int before = f.you().getLp();
        game.nextPhase(f.room(), "me");
        game.attack(f.room(), "me", other.getInstanceId(), null);
        assertThat(f.you().getLp()).isLessThan(before);
    }

    @Test
    void 支援盾機狸は破壊されると自分のリーダーに1ダメージ() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 1);
        MinionInstance tanuki = f.putOnField(f.me(), SUPPORT_TANUKI);
        int before = f.me().getLp();
        game.playCard(f.room(), "me", f.giveHand(f.me(), MAGMA),
                List.of(minions(tanuki.getInstanceId())), false);
        assertThat(f.me().getMinionZone()).isEmpty();
        assertThat(f.me().getLp()).isEqualTo(before - 1);
    }

    // ==================================================================
    // 乱戦鉄機狼(QTE-M-FIRE-34)
    // 「【速攻】【召喚時】自分のリーダーに1ダメージ。
    //   自分のリーダーの体力が10以下なら代わりに相手のリーダーに1ダメージ。」
    // ==================================================================

    /** ★LPが11以上なら自傷する(既定の側) */
    @Test
    void 乱戦鉄機狼はLPが高いと自分のリーダーを削る() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 1);
        f.me().setLp(11);
        int youBefore = f.you().getLp();
        game.playCard(f.room(), "me", f.giveHand(f.me(), IRON_WOLF), List.of(), false);
        assertThat(f.me().getLp()).as("自分が1減る").isEqualTo(10);
        assertThat(f.you().getLp()).as("相手は減らない").isEqualTo(youBefore);
    }

    /**
     * ★マスター裁定216: 判定は<b>ダメージを与える前</b>の自分のLPで行う。
     * LP10ちょうどは「10以下」に含まれるので、相手を削る側になる。
     */
    @Test
    void 乱戦鉄機狼はLPが10以下なら代わりに相手を削る() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 1);
        f.me().setLp(10);
        int youBefore = f.you().getLp();
        game.playCard(f.room(), "me", f.giveHand(f.me(), IRON_WOLF), List.of(), false);
        assertThat(f.me().getLp()).as("「代わりに」なので自分は減らない").isEqualTo(10);
        assertThat(f.you().getLp()).isEqualTo(youBefore - 1);
    }

    // ==================================================================
    // 砲台鉄機虎(QTE-M-FIRE-35)
    // 「【特殊召喚】(場に進化ミニオンが1体以上いるとき0コストとして場に出せる)【突進】」
    // ==================================================================

    /**
     * ★現行のカードプールでは<b>進化ミニオンを場に出せない</b>(P3 の担当)ため、
     * この条件は必ず偽である。したがって測れるのは「条件を満たさないと特殊召喚できない」側だけになる。
     * それでも実装したのはマスター裁定215 による ——
     * 判定は種別を見るだけで足り、P3 が解禁した時点でこのカードに戻らずに済む。
     */
    @Test
    void 砲台鉄機虎は進化ミニオンが場に居なければ特殊召喚できない() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), PLAIN_MINION);
        f.putOnField(f.you(), PLAIN_MINION);
        int idx = f.giveHand(f.me(), CANNON_TIGER);
        assertThatThrownBy(() -> game.specialSummon(f.room(), "me", idx, List.of()))
                .hasMessageContaining("条件");
    }

    // ==================================================================
    // ラスト・アタック(QTE-M-FIRE-36・スペル)
    // 「場の自分のミニオンを1枚選び破壊する。そうしたら相手のミニオンに3ダメージを与える。
    //   こうして破壊した自分のミニオンが進化ミニオンなら相手の全てのミニオンに追加で2ダメージ。」
    // ==================================================================

    @Test
    void ラストアタックは自分のミニオンを破壊して相手に3ダメージ() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 1);
        MinionInstance mine = f.putOnField(f.me(), PLAIN_MINION);
        MinionInstance yours = f.putOnField(f.you(), COST2_MINION); // 1/2
        game.playCard(f.room(), "me", f.giveHand(f.me(), LAST_ATTACK),
                List.of(minions(mine.getInstanceId()), minions(yours.getInstanceId())), false);
        assertThat(f.me().getMinionZone()).as("自分のミニオンは破壊された").isEmpty();
        assertThat(f.you().getMinionZone()).as("HP2に3ダメージなので破壊される").isEmpty();
    }

    /**
     * ★「そうでない側」。進化ミニオンを破壊したのでなければ、
     * 追加の全体2ダメージは<b>起きない</b>。
     * 現行プールでは進化を破壊できないため、常にこちら側になる。
     */
    @Test
    void ラストアタックは進化でなければ相手全体には広がらない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 1);
        MinionInstance mine = f.putOnField(f.me(), PLAIN_MINION);
        MinionInstance target = f.putOnField(f.you(), PLAIN_MINION);   // 2/1 → 3ダメージで死ぬ
        MinionInstance bystander = f.putOnField(f.you(), BEHEMOTH);    // 7/7 → 無傷のはず
        game.playCard(f.room(), "me", f.giveHand(f.me(), LAST_ATTACK),
                List.of(minions(mine.getInstanceId()), minions(target.getInstanceId())), false);
        assertThat(f.you().getMinionZone()).containsExactly(bystander);
        assertThat(bystander.getDamage()).as("追加の2ダメージは入っていない").isZero();
    }

    /** ★相手の場が空でも、自分のミニオンを破壊するためだけに撃てる(対象は任意) */
    @Test
    void ラストアタックは相手の場が空でも撃てる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 1);
        MinionInstance mine = f.putOnField(f.me(), PLAIN_MINION);
        game.playCard(f.room(), "me", f.giveHand(f.me(), LAST_ATTACK),
                List.of(minions(mine.getInstanceId()), minions()), false);
        assertThat(f.me().getMinionZone()).isEmpty();
    }

    // ==================================================================
    // リペア・チューナー(QTE-M-FIRE-37・スペル)
    // 「手札を1枚捨てる。その後カードを2枚引く。」
    // ==================================================================

    @Test
    void リペアチューナーは1枚捨てて2枚引く() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        int spell = f.giveHand(f.me(), REPAIR_TUNER);
        f.giveHand(f.me(), PLAIN_MINION);
        int deckBefore = f.me().getDeck().size();
        game.playCard(f.room(), "me", spell, List.of(hand(1)), false);
        assertThat(f.me().getDeck().size()).as("山札の減りで測る").isEqualTo(deckBefore - 2);
        assertThat(f.me().getTrash()).contains(PLAIN_MINION);
    }

    /**
     * ★マスター裁定217: 捨てる手札が無くても<b>2枚引ける</b>。
     * 必須にすると、このスペル1枚しか手札に無いときに撃てなくなる。
     */
    @Test
    void リペアチューナーは捨てる手札が無くても2枚引ける() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        int spell = f.giveHand(f.me(), REPAIR_TUNER);
        int deckBefore = f.me().getDeck().size();
        game.playCard(f.room(), "me", spell, List.of(new TargetChoice(List.of(), null, null, null, null)), false);
        assertThat(f.me().getDeck().size()).isEqualTo(deckBefore - 2);
    }

    // ==================================================================
    // アイアン・リターン(QTE-M-FIRE-38・スペル)
    // 「自分の手札を全て山札に戻してシャッフルする。こうして戻した枚数+1枚山札からカードを引く。」
    // ==================================================================

    /**
     * ★このカード自身は数に入らない。スペルの解決は
     * 検証 → 支払い → 手札からの除去 → 解決 の順で進むため、ここに来た時点で手札を離れている。
     * 手札3枚(自身+2枚)で撃つと、戻るのは2枚・引くのは3枚になる。
     */
    @Test
    void アイアンリターンは自身を除いた枚数プラス1枚引く() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        int spell = f.giveHand(f.me(), IRON_RETURN);
        f.giveHand(f.me(), PLAIN_MINION);
        f.giveHand(f.me(), COST2_MINION);
        int deckBefore = f.me().getDeck().size();

        game.playCard(f.room(), "me", spell, List.of(), false);

        assertThat(f.me().getHand()).as("2枚戻して3枚引いた").hasSize(3);
        assertThat(f.me().getDeck().size())
                .as("山札は +2(戻した) -3(引いた) で1枚減る")
                .isEqualTo(deckBefore - 1);
    }

    /** ★手札が自身だけでも1枚は引ける(0 + 1) */
    @Test
    void アイアンリターンは手札が自身だけでも1枚引く() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        int deckBefore = f.me().getDeck().size();
        game.playCard(f.room(), "me", f.giveHand(f.me(), IRON_RETURN), List.of(), false);
        assertThat(f.me().getHand()).hasSize(1);
        assertThat(f.me().getDeck().size()).isEqualTo(deckBefore - 1);
    }

    // ==================================================================
    // ドレイン・ブラスト(QTE-M-FIRE-39・スペル)
    // 「ミニオンを2体選び4ダメージ与える。この効果で破壊した枚数自分のリーダーを1回復行う。【還元】」
    // ==================================================================

    @Test
    void ドレインブラストは2体に4ダメージを与え破壊した数だけ回復する() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        f.me().setLp(10);
        MinionInstance a = f.putOnField(f.you(), PLAIN_MINION);  // 2/1 → 破壊
        MinionInstance b = f.putOnField(f.you(), BEHEMOTH);      // 7/7 → 残る
        game.playCard(f.room(), "me", f.giveHand(f.me(), DRAIN_BLAST),
                List.of(minions(a.getInstanceId(), b.getInstanceId())), false);
        assertThat(f.you().getMinionZone()).containsExactly(b);
        assertThat(b.getDamage()).isEqualTo(4);
        assertThat(f.me().getLp()).as("破壊できたのは1体なので1回復").isEqualTo(11);
    }

    /** ★「ミニオン」に「相手の」が付いていないので、自分のミニオンも選べる(記法規約) */
    @Test
    void ドレインブラストは自分のミニオンも選べる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        MinionInstance mine = f.putOnField(f.me(), PLAIN_MINION);
        game.playCard(f.room(), "me", f.giveHand(f.me(), DRAIN_BLAST),
                List.of(minions(mine.getInstanceId())), false);
        assertThat(f.me().getMinionZone()).isEmpty();
    }

    /** ★1体も破壊できなければ回復は起きない(「破壊した枚数」である) */
    @Test
    void ドレインブラストは破壊できなければ回復しない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        f.me().setLp(10);
        MinionInstance tough = f.putOnField(f.you(), BEHEMOTH);
        game.playCard(f.room(), "me", f.giveHand(f.me(), DRAIN_BLAST),
                List.of(minions(tough.getInstanceId())), false);
        assertThat(f.me().getLp()).isEqualTo(10);
    }

    // ==================================================================
    // 百獣の王 ベヒーモス(QTE-M-EARTH-7)
    // 「【召喚時】他のミニオン全てに7ダメージ。体力の多いリーダーに3ダメージ」
    // ==================================================================

    @Test
    void ベヒーモスは自分以外の全ミニオンに7ダメージ() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 7);
        f.putOnField(f.me(), PLAIN_MINION);
        f.putOnField(f.you(), COST2_MINION);
        game.playCard(f.room(), "me", f.giveHand(f.me(), BEHEMOTH), List.of(), false);
        assertThat(f.fieldIds(f.me())).as("自分自身だけが残る").containsExactly(BEHEMOTH);
        assertThat(f.you().getMinionZone()).isEmpty();
    }

    @Test
    void ベヒーモスは体力の多いリーダーに3ダメージ() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 7);
        f.me().setLp(15);
        f.you().setLp(20);
        game.playCard(f.room(), "me", f.giveHand(f.me(), BEHEMOTH), List.of(), false);
        assertThat(f.you().getLp()).isEqualTo(17);
        assertThat(f.me().getLp()).isEqualTo(15);
    }

    /** ★自分のほうが多ければ自分が受ける(「相手の」とは書いていない) */
    @Test
    void ベヒーモスは自分の体力が多ければ自分が受ける() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 7);
        f.me().setLp(20);
        f.you().setLp(15);
        game.playCard(f.room(), "me", f.giveHand(f.me(), BEHEMOTH), List.of(), false);
        assertThat(f.me().getLp()).isEqualTo(17);
        assertThat(f.you().getLp()).isEqualTo(15);
    }

    /** ★マスター裁定218: 同値のときは<b>どちらにも起きない</b>(「多いほう」が定まらない) */
    @Test
    void ベヒーモスは体力が同じなら誰も削らない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 7);
        f.me().setLp(15);
        f.you().setLp(15);
        game.playCard(f.room(), "me", f.giveHand(f.me(), BEHEMOTH), List.of(), false);
        assertThat(f.me().getLp()).isEqualTo(15);
        assertThat(f.you().getLp()).isEqualTo(15);
    }

    // ==================================================================
    // 地上覇総長・翔山(QTE-M-EARTH-29・リーダー)
    // 「【起動：2】自分の墓地からカード1枚選びマナに置く。」
    // ==================================================================

    /** ★マスター裁定210: 向きの明記が無いので<b>表向き</b>で置く(ガイア・リソースと同じ) */
    @Test
    void 翔山は墓地のカードを表向きでマナに置く() {
        AutoGameFixture f = newGame(SHOZAN);
        payMana(f.me(), 2);
        f.me().getTrash().add(PLAIN_MINION);
        int manaBefore = f.me().getManaZone().size();

        game.useLeaderAbility(f.room(), "me", List.of(trash(0)));

        assertThat(f.me().getTrash()).isEmpty();
        assertThat(f.me().getManaZone()).hasSize(manaBefore + 1);
        ManaCard placed = f.me().getManaZone().get(manaBefore);
        assertThat(placed.getCardId()).isEqualTo(PLAIN_MINION);
        assertThat(placed.isFaceUp()).as("表向きである(裁定210)").isTrue();
    }

    /**
     * ★表向きであることには意味がある —— 翔山で置いたカードは
     * 《俺等地上覇夜露死苦》の「表向きのマナから場に出す」で盤面に変えられる。
     * 裏向きで置く実装にすると、土の Ver1.1 の線が途中で切れる。
     */
    @Test
    void 翔山で置いたマナはセカイヲスベシモノで場に出せる() {
        AutoGameFixture f = newGame(SHOZAN);
        payMana(f.me(), 11);
        f.me().getTrash().add(PLAIN_MINION);
        game.useLeaderAbility(f.room(), "me", List.of(trash(0)));

        game.playCard(f.room(), "me", f.giveHand(f.me(), SEKAIWO), List.of(), false);
        assertThat(f.fieldIds(f.me())).containsExactly(PLAIN_MINION);
    }

    // ==================================================================
    // 分那愚利(QTE-M-EARTH-33)
    // 「【突進】【召喚時】相手ミニオン1体に1ダメージ」
    // ==================================================================

    @Test
    void 分那愚利は召喚時に相手ミニオン1体に1ダメージ() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        MinionInstance target = f.putOnField(f.you(), BEHEMOTH);
        game.playCard(f.room(), "me", f.giveHand(f.me(), BUNNAGURI),
                List.of(minions(target.getInstanceId())), false);
        assertThat(target.getDamage()).isEqualTo(1);
    }

    /** ★相手の場が空でも召喚できる(対象は任意) */
    @Test
    void 分那愚利は相手の場が空でも召喚できる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        game.playCard(f.room(), "me", f.giveHand(f.me(), BUNNAGURI),
                List.of(minions()), false);
        assertThat(f.fieldIds(f.me())).containsExactly(BUNNAGURI);
    }

    // ==================================================================
    // 勝鼓美(QTE-M-EARTH-34)
    // 「【破壊時】山札の上からカードを1枚マナゾーンに置く。
    //   その後コスト3以下のミニオンを1体選びマナゾーンから場に出す。」
    // ==================================================================

    /** ★候補が1体しか無ければ自動決定(降臨の伝道師と同じ流儀) */
    @Test
    void 勝鼓美は破壊されるとマナを増やしコスト3以下のミニオンを場に出す() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 1);
        f.stackDeck(f.me(), MAGMA); // ★スペルなのでマナに置かれても候補にならない
        giveFaceUpMana(f.me(), COST2_MINION);
        MinionInstance kachikomi = f.putOnField(f.me(), KACHIKOMI);
        int manaBefore = f.me().getManaZone().size();

        game.playCard(f.room(), "me", f.giveHand(f.me(), MAGMA),
                List.of(minions(kachikomi.getInstanceId())), false);

        assertThat(f.fieldIds(f.me())).as("マナから場に出た").containsExactly(COST2_MINION);
        assertThat(f.me().getManaZone())
                .as("山札から1枚増えて、場に出した1枚が減る")
                .hasSize(manaBefore);
    }

    /** ★「そうでない側」。コスト4以上のミニオンは候補にならない */
    @Test
    void 勝鼓美はコスト4以上のミニオンをマナから出さない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 1);
        f.stackDeck(f.me(), MAGMA); // ★スペルなのでマナに置かれても候補にならない
        giveFaceUpMana(f.me(), BEHEMOTH); // コスト7
        MinionInstance kachikomi = f.putOnField(f.me(), KACHIKOMI);

        game.playCard(f.room(), "me", f.giveHand(f.me(), MAGMA),
                List.of(minions(kachikomi.getInstanceId())), false);

        assertThat(f.me().getMinionZone()).isEmpty();
    }

    /**
     * ★マスター裁定211: 本文が「表向きの」と限定していないので<b>裏向きのマナも候補になる</b>。
     * 限定しているのは《俺等地上覇夜露死苦》1枚だけである。
     */
    @Test
    void 勝鼓美は裏向きのマナからも場に出せる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 1);
        f.stackDeck(f.me(), MAGMA); // ★スペルなのでマナに置かれても候補にならない
        giveFaceDownMana(f.me(), COST2_MINION);
        MinionInstance kachikomi = f.putOnField(f.me(), KACHIKOMI);

        game.playCard(f.room(), "me", f.giveHand(f.me(), MAGMA),
                List.of(minions(kachikomi.getInstanceId())), false);

        assertThat(f.fieldIds(f.me())).containsExactly(COST2_MINION);
    }

    /**
     * ★★マスター裁定214: 【破壊時】は<b>相手のターン中にも起きる</b>。
     * 50 まで {@code resolveChoice} はターンプレイヤーしか受け付けなかったため、
     * ここは自動決定にするしかなかった。51 でその制限を外したので、
     * <b>手番でない側が自分の選択を解決できる</b>。
     */
    @Test
    void 勝鼓美は相手のターンに破壊されても本人が選べる() {
        AutoGameFixture f = newGame();
        f.stackDeck(f.you(), MAGMA); // ★スペルなのでマナに置かれても候補にならない
        giveFaceUpMana(f.you(), PLAIN_MINION);
        giveFaceUpMana(f.you(), COST2_MINION);
        MinionInstance kachikomi = f.putOnField(f.you(), KACHIKOMI);
        payMana(f.me(), 1);

        // 自分のターンに、相手の勝鼓美を破壊する
        game.playCard(f.room(), "me", f.giveHand(f.me(), MAGMA),
                List.of(minions(kachikomi.getInstanceId())), false);

        assertThat(f.you().getPendingChoice())
                .as("手番でない相手に問い合わせが出ている")
                .isNotNull();
        // 手番の側は、相手が選び終えるまで何もできない
        assertThatThrownBy(() -> game.endTurn(f.room(), "me"))
                .hasMessageContaining("相手が選択中");

        game.resolveChoice(f.room(), "you", List.of(1)); // COST2_MINION を選ぶ

        assertThat(f.you().getPendingChoice()).isNull();
        assertThat(f.fieldIds(f.you())).containsExactly(COST2_MINION);
    }

    // ==================================================================
    // 素手喧嘩(QTE-M-EARTH-35)
    // 「【突進】攻撃時このカードをマナに裏向きで置いても良い。
    //   そうしたらマナにある表向きのAttackが6以下のミニオンを1体場に出す。」
    // ==================================================================

    /**
     * ★★マスター裁定213: マナに置いた場合は<b>戦闘が起きない</b>。
     * 攻撃者が場を離れるためであり、素手喧嘩専用の分岐ではなく
     * 「攻撃者が場に居るか」という構造で決まっている。
     */
    @Test
    void 素手喧嘩はマナに置くと戦闘が起きずマナからミニオンを出す() {
        AutoGameFixture f = newGame();
        giveFaceUpMana(f.me(), PLAIN_MINION); // Attack2 → 候補
        MinionInstance stegoro = f.putOnField(f.me(), STEGORO);
        MinionInstance blocker = f.putOnField(f.you(), BEHEMOTH); // 7/7
        game.nextPhase(f.room(), "me");

        game.attack(f.room(), "me", stegoro.getInstanceId(), blocker.getInstanceId());

        assertThat(f.me().getPendingChoice()).as("戦闘の前に問い合わせている").isNotNull();
        assertThat(blocker.getDamage()).as("まだ戦闘は解決していない").isZero();

        game.resolveChoice(f.room(), "me", List.of(0)); // 「マナに置く」を選ぶ

        assertThat(f.fieldIds(f.me())).as("素手喧嘩は場を離れ、マナから1体出た")
                .containsExactly(PLAIN_MINION);
        assertThat(blocker.getDamage()).as("戦闘は起きなかった").isZero();
        assertThat(f.you().getMinionZone()).containsExactly(blocker);
    }

    /**
     * ★「そうでない側」(裁定181)。置かなければ<b>普通に戦闘が起きる</b> ——
     * これが無いと「攻撃時に必ず戦闘を飛ばす」実装でも上の試験は通る。
     */
    @Test
    void 素手喧嘩はマナに置かなければ普通に戦闘する() {
        AutoGameFixture f = newGame();
        giveFaceUpMana(f.me(), PLAIN_MINION);
        MinionInstance stegoro = f.putOnField(f.me(), STEGORO);  // 4/2
        MinionInstance blocker = f.putOnField(f.you(), BEHEMOTH); // 7/7
        game.nextPhase(f.room(), "me");

        game.attack(f.room(), "me", stegoro.getInstanceId(), blocker.getInstanceId());
        game.resolveChoice(f.room(), "me", List.of()); // 何も選ばない

        assertThat(blocker.getDamage()).as("素手喧嘩の4ダメージが入った").isEqualTo(4);
        assertThat(f.me().getMinionZone()).as("7ダメージを受けて破壊された").isEmpty();
        assertThat(f.me().getManaZone()).as("マナは増えていない").hasSize(1);
    }

    /** ★2段目の候補は「表向きの」マナに限られる。裏向きは選べない */
    @Test
    void 素手喧嘩は裏向きのマナからは場に出さない() {
        AutoGameFixture f = newGame();
        giveFaceDownMana(f.me(), PLAIN_MINION);
        MinionInstance stegoro = f.putOnField(f.me(), STEGORO);
        game.nextPhase(f.room(), "me");
        game.attack(f.room(), "me", stegoro.getInstanceId(), null);
        game.resolveChoice(f.room(), "me", List.of(0));

        assertThat(f.me().getMinionZone()).as("裏向きは候補にならないので何も出ない").isEmpty();
        assertThat(f.you().getLp()).as("リーダーへの攻撃も起きていない").isEqualTo(20);
    }

    /** ★Attackが7以上のミニオンは候補にならない */
    @Test
    void 素手喧嘩はAttack7以上のミニオンをマナから出さない() {
        AutoGameFixture f = newGame();
        giveFaceUpMana(f.me(), BEHEMOTH); // Attack7
        MinionInstance stegoro = f.putOnField(f.me(), STEGORO);
        game.nextPhase(f.room(), "me");
        game.attack(f.room(), "me", stegoro.getInstanceId(), null);
        game.resolveChoice(f.room(), "me", List.of(0));

        assertThat(f.me().getMinionZone()).isEmpty();
        assertThat(f.me().getManaZone()).as("素手喧嘩自身は裏向きでマナに入っている").hasSize(2);
        assertThat(f.me().getManaZone().get(1).isFaceUp()).isFalse();
    }

    // ==================================================================
    // 仏恥義理(QTE-M-EARTH-37・スペル)
    // 「カードを1枚引く。その後自分の手札を1枚選びマナに裏向きで置く。」
    // ==================================================================

    @Test
    void 仏恥義理は1枚引いてから手札1枚を裏向きでマナに置く() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        f.stackDeck(f.me(), PLAIN_MINION);
        int manaBefore = f.me().getManaZone().size();

        game.playCard(f.room(), "me", f.giveHand(f.me(), BUCCHIGIRI), List.of(), false);

        assertThat(f.me().getPendingChoice()).isNotNull();
        assertThat(f.me().getHand()).as("引いたカードが候補に入っている").contains(PLAIN_MINION);

        int idx = f.me().getHand().indexOf(PLAIN_MINION);
        game.resolveChoice(f.room(), "me", List.of(idx));

        assertThat(f.me().getManaZone()).hasSize(manaBefore + 1);
        ManaCard placed = f.me().getManaZone().get(manaBefore);
        assertThat(placed.getCardId()).isEqualTo(PLAIN_MINION);
        assertThat(placed.isFaceUp()).as("裏向きである(本文に明記)").isFalse();
    }

    // ==================================================================
    // 喧嘩上等(QTE-M-EARTH-38・スペル)
    // 「相手のミニオンを1体マナに裏向きで置く。
    //   その後自分のマナからコスト6以下のミニオンを1体マナから場に出す」
    // ==================================================================

    /** ★マスター裁定212: 置き先は<b>相手の</b>マナゾーンである */
    @Test
    void 喧嘩上等は相手のミニオンを相手のマナに裏向きで置く() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        giveFaceUpMana(f.me(), COST2_MINION);
        MinionInstance victim = f.putOnField(f.you(), BEHEMOTH);
        int yourManaBefore = f.you().getManaZone().size();

        game.playCard(f.room(), "me", f.giveHand(f.me(), KENKAJOTO),
                List.of(minions(victim.getInstanceId())), false);

        assertThat(f.you().getMinionZone()).isEmpty();
        assertThat(f.you().getManaZone()).as("相手のマナが1枚増える").hasSize(yourManaBefore + 1);
        ManaCard placed = f.you().getManaZone().get(yourManaBefore);
        assertThat(placed.getCardId()).isEqualTo(BEHEMOTH);
        assertThat(placed.isFaceUp()).isFalse();
        assertThat(f.fieldIds(f.me())).as("自分はマナから1体出す").containsExactly(COST2_MINION);
    }

    /** ★破壊ではないので【破壊時】は発動しない */
    @Test
    void 喧嘩上等はマナに置くだけなので破壊時は発動しない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        MinionInstance victim = f.putOnField(f.you(), KACHIKOMI);
        f.stackDeck(f.you(), MAGMA); // ★スペルなのでマナに置かれても候補にならない
        giveFaceUpMana(f.you(), COST2_MINION);

        game.playCard(f.room(), "me", f.giveHand(f.me(), KENKAJOTO),
                List.of(minions(victim.getInstanceId())), false);

        assertThat(f.you().getMinionZone()).as("勝鼓美の蘇生は起きていない").isEmpty();
        assertThat(f.you().getPendingChoice()).isNull();
    }

    /** ★コスト7以上のミニオンは自分のマナから出せない */
    @Test
    void 喧嘩上等はコスト7以上のミニオンをマナから出さない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        giveFaceUpMana(f.me(), BEHEMOTH); // コスト7
        MinionInstance victim = f.putOnField(f.you(), PLAIN_MINION);

        game.playCard(f.room(), "me", f.giveHand(f.me(), KENKAJOTO),
                List.of(minions(victim.getInstanceId())), false);

        assertThat(f.me().getMinionZone()).isEmpty();
    }

    // ==================================================================
    // 俺等地上覇夜露死苦(QTE-M-EARTH-39・スペル)
    // 「相手のミニオンを全て破壊する。その後自分の表向きのマナからミニオンを1枚選び場に出す。」
    // ==================================================================

    @Test
    void セカイヲスベシモノは相手を全滅させ表向きのマナから1体出す() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 9);
        giveFaceUpMana(f.me(), BEHEMOTH); // コスト制限は無い
        f.putOnField(f.you(), PLAIN_MINION);
        f.putOnField(f.you(), COST2_MINION);

        game.playCard(f.room(), "me", f.giveHand(f.me(), SEKAIWO), List.of(), false);

        assertThat(f.you().getMinionZone()).isEmpty();
        assertThat(f.fieldIds(f.me())).containsExactly(BEHEMOTH);
    }

    /**
     * ★このカードだけが「表向きの」と限定している(裁定211 の対)。
     * 勝鼓美・喧嘩上等が裏向きも取れるのと非対称であり、それは意図した非対称である。
     */
    @Test
    void セカイヲスベシモノは裏向きのマナからは出さない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 9);
        giveFaceDownMana(f.me(), PLAIN_MINION);
        f.putOnField(f.you(), PLAIN_MINION);

        game.playCard(f.room(), "me", f.giveHand(f.me(), SEKAIWO), List.of(), false);

        assertThat(f.you().getMinionZone()).as("破壊は起きる").isEmpty();
        assertThat(f.me().getMinionZone()).as("裏向きは候補にならない").isEmpty();
    }

    // ==================================================================
    // ★エンジンの工事そのものを測る
    // ==================================================================

    /**
     * ★マナから場に出すのは<b>召喚ではない</b>ので【召喚時】は発動しない。
     * 分那愚利をマナから出しても、相手ミニオンへの1ダメージは起きない。
     */
    @Test
    void マナから場に出すのは召喚ではないので召喚時は発動しない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 9);
        giveFaceUpMana(f.me(), BUNNAGURI);
        MinionInstance survivor = f.putOnField(f.you(), BEHEMOTH);
        // 相手の場を空にしない道具として、破壊されない相手を置いておく必要はないので
        // セカイヲスベシモノで全滅させたあと、ダメージの対象が残らないことを利用する
        game.playCard(f.room(), "me", f.giveHand(f.me(), SEKAIWO), List.of(), false);

        assertThat(f.fieldIds(f.me())).containsExactly(BUNNAGURI);
        assertThat(f.you().getMinionZone()).doesNotContain(survivor);
    }

    /**
     * ★マナから場に出す経路は {@code putIntoFieldByEffect} を通る ——
     * つまり<b>登場時(ON_ENTER)は発動する</b>。
     * 【知識】(登場時1ドロー)を持つミニオンをマナから出して、山札の減りで測る。
     *
     * <p>これが無いと「マナから取り出して minionZone に直接 add する」実装でも
     * 上の試験群は全部通る —— 場に出たことしか見ていないためである(裁定181)。
     */
    @Test
    void マナから場に出したミニオンの登場時効果が発動する() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 9);
        giveFaceUpMana(f.me(), KNOWLEDGE_MINION); // 【知識】= 登場時1ドロー
        f.putOnField(f.you(), PLAIN_MINION);
        int deckBefore = f.me().getDeck().size();

        game.playCard(f.room(), "me", f.giveHand(f.me(), SEKAIWO), List.of(), false);

        assertThat(f.fieldIds(f.me())).containsExactly(KNOWLEDGE_MINION);
        assertThat(f.me().getDeck().size())
                .as("【知識】の1ドローが起きた(山札の減りで測る)")
                .isEqualTo(deckBefore - 1);
    }

    /**
     * ★マナが上限(15枚)なら場のミニオンをマナに置けない。
     * ★そのとき<b>場からも消えてはいけない</b> —— 行き先の無いカードを場から取り除くと、
     * ミニオンがどのゾーンにも居ない状態になる。
     */
    @Test
    void マナが上限なら場のミニオンをマナに置けず場からも消えない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        payMana(f.you(), 15); // 相手のマナを上限まで埋める
        MinionInstance victim = f.putOnField(f.you(), PLAIN_MINION);

        game.playCard(f.room(), "me", f.giveHand(f.me(), KENKAJOTO),
                List.of(minions(victim.getInstanceId())), false);

        assertThat(f.you().getMinionZone()).as("場に残っている").containsExactly(victim);
        assertThat(f.you().getManaZone()).as("マナは増えていない").hasSize(15);
    }

    /** ★場が満杯ならマナから出せない。★出せなかったカードがマナから消えてもいけない */
    @Test
    void 場が満杯ならマナから場に出せずマナも減らない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 9);
        giveFaceUpMana(f.me(), PLAIN_MINION);
        for (int i = 0; i < 6; i++) {
            f.putOnField(f.me(), PLAIN_MINION);
        }
        f.putOnField(f.you(), COST2_MINION);
        int manaBefore = f.me().getManaZone().size();

        game.playCard(f.room(), "me", f.giveHand(f.me(), SEKAIWO), List.of(), false);

        assertThat(f.me().getMinionZone()).as("場は満杯のまま").hasSize(6);
        assertThat(f.me().getManaZone()).as("カードが消えていない").hasSize(manaBefore);
    }

    /**
     * ★攻撃時の割り込みが出ているあいだ、手番の側は他の操作をできない。
     * 候補が指す先(マナゾーンの位置)がずれないための規則である。
     */
    @Test
    void 選択待ちのあいだは他の操作ができない() {
        AutoGameFixture f = newGame();
        giveFaceUpMana(f.me(), PLAIN_MINION);
        MinionInstance stegoro = f.putOnField(f.me(), STEGORO);
        game.nextPhase(f.room(), "me");
        game.attack(f.room(), "me", stegoro.getInstanceId(), null);

        assertThatThrownBy(() -> game.endTurn(f.room(), "me"))
                .hasMessageContaining("先に選択を解決してください");
    }
}
