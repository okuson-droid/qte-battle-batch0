package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.effect.TargetChoice;
import com.example.qte.game.GameService;
import com.example.qte.game.ManaCard;
import com.example.qte.game.MinionInstance;
import com.example.qte.game.PlayerState;
import com.example.qte.game.StatModifier;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.Keyword;
import com.example.qte.support.AutoGameFixture;

/**
 * 進化エンジンと、Batch 52 が実装した進化6枚 +《機神兵長茶爺》の試験(★Batch 52。P3 の1本目)。
 *
 * <h2>このバッチでいちばん壊れやすいところ</h2>
 *
 * 52 が足したのはカード8枚だけではなく、<b>ミニオンが「下にカードの束を持てる」という
 * 構造そのもの</b>である(裁定154)。試験の重心はそちらに置いてある ——
 *
 * <ul>
 * <li><b>素材は場を離れるのではなく下に置かれる</b>(マスター裁定 A4)。
 *     破壊でも消滅でもないので【破壊時】は発動しない。</li>
 * <li><b>引き継ぐのは他のカードによって付与された効果だけ</b>(裁定157(2)(3)・
 *     マスター裁定 B1〜B5)。ダメージ・タップ・攻撃回数は引き継がない。</li>
 * <li><b>場を離れるときは束も一緒に動く</b>(裁定154・マスター裁定 C1〜C3)。
 *     破壊・手札・マナ・禁忌の消滅で行き先が変わる。</li>
 * <li><b>出したターンから攻撃できる</b>(裁定157(1))。</li>
 * </ul>
 *
 * <h2>測り方の方針(48〜51 から継続)</h2>
 *
 * <ul>
 * <li>効果は<b>本物の入口</b>から起こす(裁定187)。素材の指定も
 *     {@code GameService.playCard} の引数として渡す —— 束を直に組み立てない。</li>
 * <li><b>「そうでない側」も測る</b>(裁定181)。「進化は出したターンに殴れる」だけでは
 *     <b>誰でもいつでも殴れる</b>実装でも通るので、「普通のミニオンは殴れない」を並べて置く。</li>
 * <li>ドロー数は<b>山札の減り</b>で測る。</li>
 * </ul>
 */
@SpringBootTest
class EvolutionEngineTest {

    /** 常在効果を持たないリーダー(既定) */
    private static final String PLAIN_LEADER = "QTE-M-WATER-1";

    // ---- Batch 52 が実装した進化6枚 + リーダー1枚 ----
    private static final String SHIRAKA = "QTE-M-WATER-30";   // 海淵獣シラーカ(【潜伏】【知識】)
    private static final String TOUTA = "QTE-M-FIRE-30";      // 不敗鉄人闘太(下1枚につき+2/+2)
    private static final String RENTA = "QTE-M-FIRE-31";      // 追撃鉄人連太(2回攻撃)
    private static final String SOTA = "QTE-M-FIRE-32";       // 飛翔鉄人走太(【特殊召喚】)
    private static final String MERINA = "QTE-M-DARK-32";     // サービスブレイク・メリィナ(素材2体)
    private static final String RIRAIDO = "QTE-M-EARTH-31";   // 裏雷怒乗込(攻撃時1ドロー)
    private static final String BURASUTO = "QTE-M-EARTH-32";  // 武羅須斗最終(【特殊召喚】)
    private static final String CHAJI_LEADER = "QTE-M-FIRE-29"; // 機神兵長茶爺(リーダー)

    // ---- Batch 53 送りの進化(素材条件だけは 52 で登録してある) ----
    private static final String RAKABU = "QTE-M-WATER-31";    // 海淵獣ラカブ(素材=水の潜伏持ち)

    // ---- 道具として使う既存カード ----
    /** コスト1・2/1・効果なし(フレア・ポーン)。汎用の素材・壁 */
    private static final String PLAIN_MINION = "QTE-M-FIRE-2";
    /** コスト1・1/1・【知識】(アクア・ジェリー)。水文明で【潜伏】を持たない = シラーカの素材 */
    private static final String WATER_PLAIN = "QTE-M-WATER-2";
    /** コスト1・1/1・【潜伏】(海獣タウギーナ)。シラーカの素材にはできない側 */
    private static final String WATER_STEALTH = "QTE-M-WATER-33";
    /** コスト3・4/1・【突進】(ボーン・コレクター)。闇文明の素材 */
    private static final String DARK_MINION = "QTE-M-DARK-6";
    /**
     * コスト0・1/1・【守護】+【破壊時】自分のリーダーに1ダメージ(支援盾機狸)。
     * ★<b>破壊の経路を問わない【破壊時】</b>なので、「束のカードの破壊時は発動しない」を
     * 測る素材に使う(ボーン・コレクターは戦闘破壊限定なので測れない)。
     */
    private static final String ON_DESTROY_MINION = "QTE-M-FIRE-33";
    /** コスト3・1/5・【守護】(ゴーレム・ウォール)。土文明の素材 */
    private static final String EARTH_MINION = "QTE-M-EARTH-2";
    /** コスト1・ミニオン1体に3ダメージ(マグマ・ストレート)。破壊の道具 */
    private static final String MAGMA = "QTE-M-FIRE-10";

    @Autowired
    private CardMasterRepository cards;

    @Autowired
    private GameService game;

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

    /**
     * コスト支払い用のマナを n 枚置く。
     * ★足場の {@code giveMana} はマナにミニオンを置くが、このバッチには
     * 「マナから場に出す」カードが無いので混ざる心配はない。それでも 51 に揃えて
     * スペルを置いておく —— 進化の素材候補は<b>場</b>から数えるので、
     * マナの中身が測定に影響しないことを見た目からも分かるようにするためである。
     */
    private void payMana(PlayerState player, int count) {
        for (int i = 0; i < count; i++) {
            player.getManaZone().add(new ManaCard(MAGMA, false));
        }
    }

    /** 手札のカードを進化召喚する(素材の instanceId を並べて渡す) */
    private void evolve(AutoGameFixture f, String cardId, MinionInstance... materials) {
        game.playCard(f.room(), "me", f.giveHand(f.me(), cardId), List.of(), false,
                List.of(java.util.Arrays.stream(materials)
                        .map(MinionInstance::getInstanceId).toArray(String[]::new)));
    }

    /** 場に居る進化ミニオン(このバッチの試験は常に1体しか出さない) */
    private MinionInstance evolutionOnField(PlayerState player) {
        return player.getMinionZone().stream().filter(MinionInstance::isEvolution)
                .findFirst().orElseThrow();
    }

    // ==================================================================
    // 1. 進化召喚の基本(裁定154・マスター裁定 A1〜A4)
    // ==================================================================

    @Test
    void 進化召喚は素材を場から取り除いて自分と入れ替わる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        MinionInstance material = f.putOnField(f.me(), WATER_PLAIN);
        evolve(f, SHIRAKA, material);
        assertThat(f.fieldIds(f.me())).containsExactly(SHIRAKA);
        assertThat(f.me().getMinionZone()).hasSize(1); // 素材1体が消え進化1体が出るので増減なし
    }

    @Test
    void 素材は進化ミニオンの下に置かれる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        evolve(f, SHIRAKA, f.putOnField(f.me(), WATER_PLAIN));
        MinionInstance shiraka = evolutionOnField(f.me());
        assertThat(shiraka.getUnder()).hasSize(1);
        assertThat(shiraka.getUnder().get(0).cardId()).isEqualTo(WATER_PLAIN);
    }

    /**
     * ★素材は「場を離れた」ことにならない(マスター裁定 A4)。
     * 墓地にも消滅ゾーンにも行かず、束の中に居る。
     */
    @Test
    void 素材は墓地にも消滅ゾーンにも行かない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        evolve(f, SHIRAKA, f.putOnField(f.me(), WATER_PLAIN));
        assertThat(f.me().getTrash()).isEmpty();
        assertThat(f.me().getLostZone()).isEmpty();
    }

    /**
     * ★進化召喚は「召喚」である(マスター裁定 A1)ので、登場時(ON_ENTER)が発動する。
     * シラーカは【潜伏】【知識】を持つので、場に出た瞬間に1枚引く。
     * ★ドロー数は<b>山札の減り</b>で測る。
     */
    @Test
    void 進化召喚でも登場時効果が発動する() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        f.putOnField(f.me(), WATER_PLAIN);
        int deckBefore = f.me().getDeck().size();
        evolve(f, SHIRAKA, f.me().getMinionZone().get(0));
        assertThat(f.me().getDeck()).hasSize(deckBefore - 1); // 【知識】で1枚
    }

    /**
     * ★「そうでない側」。素材を置いただけでは何も起きない ——
     * これが無いと、上の試験は「足場が勝手に1枚引いている」でも通ってしまう(裁定181)。
     */
    @Test
    void 素材を場に置くだけではドローは起きない() {
        AutoGameFixture f = newGame();
        int deckBefore = f.me().getDeck().size();
        f.putOnField(f.me(), WATER_PLAIN);
        assertThat(f.me().getDeck()).hasSize(deckBefore);
    }

    @Test
    void 条件を満たす素材が場に居なければ進化召喚できない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        // 場が空。シラーカは水文明の潜伏を持たないミニオンを要求する
        int handIndex = f.giveHand(f.me(), SHIRAKA);
        assertThatThrownBy(() -> game.playCard(f.room(), "me", handIndex, List.of(), false, List.of()))
                .hasMessageContaining("進化素材");
    }

    @Test
    void 条件を満たさないミニオンは素材にできない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        MinionInstance stealth = f.putOnField(f.me(), WATER_STEALTH); // 【潜伏】を持つ水ミニオン
        int handIndex = f.giveHand(f.me(), SHIRAKA);
        assertThatThrownBy(() -> game.playCard(f.room(), "me", handIndex, List.of(), false,
                List.of(stealth.getInstanceId())))
                .hasMessageContaining("進化素材にできません");
    }

    /**
     * ★条件が逆のカードを並べて置く(裁定181)。同じ《海獣タウギーナ》が
     * <b>《海淵獣ラカブ》の素材にはなる</b> —— あちらは「潜伏を<b>持つ</b>」を要求する。
     * これが無いと、上の試験は「タウギーナがそもそも素材にできない」でも通る。
     */
    @Test
    void 潜伏を持つ水ミニオンはラカブの素材にはなる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        evolve(f, RAKABU, f.putOnField(f.me(), WATER_STEALTH));
        assertThat(f.fieldIds(f.me())).containsExactly(RAKABU);
    }

    @Test
    void 相手の場のミニオンは素材にできない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        MinionInstance theirs = f.putOnField(f.you(), WATER_PLAIN);
        int handIndex = f.giveHand(f.me(), SHIRAKA);
        assertThatThrownBy(() -> game.playCard(f.room(), "me", handIndex, List.of(), false,
                List.of(theirs.getInstanceId())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 素材を2体要求するカードは1体では出せない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        MinionInstance one = f.putOnField(f.me(), DARK_MINION);
        int handIndex = f.giveHand(f.me(), MERINA);
        assertThatThrownBy(() -> game.playCard(f.room(), "me", handIndex, List.of(), false,
                List.of(one.getInstanceId())))
                .hasMessageContaining("2体");
    }

    @Test
    void 同じミニオンを2回素材にはできない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        MinionInstance one = f.putOnField(f.me(), DARK_MINION);
        f.putOnField(f.me(), DARK_MINION);
        int handIndex = f.giveHand(f.me(), MERINA);
        assertThatThrownBy(() -> game.playCard(f.room(), "me", handIndex, List.of(), false,
                List.of(one.getInstanceId(), one.getInstanceId())))
                .hasMessageContaining("2回素材");
    }

    @Test
    void 素材2体は2体とも束に入る() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        MinionInstance a = f.putOnField(f.me(), DARK_MINION);
        MinionInstance b = f.putOnField(f.me(), DARK_MINION);
        evolve(f, MERINA, a, b);
        assertThat(f.me().getMinionZone()).hasSize(1);
        assertThat(evolutionOnField(f.me()).getUnder()).hasSize(2);
    }

    /**
     * ★進化の上に進化を重ねられる(マスター裁定 A3)。下の束はそのまま引き継ぐので、
     * 《追撃鉄人連太》の下は<b>2枚</b>になる(闘太 + 闘太の素材)。
     */
    @Test
    void 進化を素材にすると束がそのまま引き継がれる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 7);
        evolve(f, TOUTA, f.putOnField(f.me(), PLAIN_MINION));
        evolve(f, RENTA, evolutionOnField(f.me()));
        MinionInstance renta = evolutionOnField(f.me());
        assertThat(renta.getMaster().id()).isEqualTo(RENTA);
        assertThat(renta.getUnder()).hasSize(2);
        assertThat(renta.getUnder().stream().map(s -> s.cardId()))
                .containsExactly(PLAIN_MINION, TOUTA); // 下から順(古い束が先)
    }

    @Test
    void 進化ミニオン以外は連太の素材にできない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        MinionInstance plain = f.putOnField(f.me(), PLAIN_MINION);
        int handIndex = f.giveHand(f.me(), RENTA);
        assertThatThrownBy(() -> game.playCard(f.room(), "me", handIndex, List.of(), false,
                List.of(plain.getInstanceId())))
                .hasMessageContaining("進化素材にできません");
    }

    // ==================================================================
    // 2. 引き継ぎ(裁定157(2)(3)・マスター裁定 B1〜B5)
    // ==================================================================

    @Test
    void 素材に付与されていた攻撃力の修正を引き継ぐ() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        MinionInstance material = f.putOnField(f.me(), WATER_PLAIN);
        material.addModifier(new StatModifier(StatModifier.Stat.ATTACK,
                StatModifier.Operation.ADD, 3, StatModifier.Duration.PERMANENT, MAGMA));
        evolve(f, SHIRAKA, material);
        // シラーカの印刷 Attack は 2。付与された +3 が乗って 5 になる
        assertThat(evolutionOnField(f.me()).getEffectiveAttack())
                .isEqualTo(cards.findById(SHIRAKA).attack() + 3);
    }

    @Test
    void 素材に付与されていたキーワードを引き継ぐ() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        MinionInstance material = f.putOnField(f.me(), WATER_PLAIN);
        material.grantKeyword(Keyword.GUARD);
        evolve(f, SHIRAKA, material);
        assertThat(evolutionOnField(f.me()).hasKeyword(Keyword.GUARD)).isTrue();
    }

    /**
     * ★「そうでない側」。付与していなければ引き継がれない ——
     * これが無いと、上の2件は<b>進化ミニオンが常に守護と+3を持つ</b>実装でも通る(裁定181)。
     */
    @Test
    void 付与されていないものは引き継がれない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        evolve(f, SHIRAKA, f.putOnField(f.me(), WATER_PLAIN));
        MinionInstance shiraka = evolutionOnField(f.me());
        assertThat(shiraka.hasKeyword(Keyword.GUARD)).isFalse();
        assertThat(shiraka.getEffectiveAttack()).isEqualTo(cards.findById(SHIRAKA).attack());
    }

    /** ★「このターンの間」の付与も引き継ぐ(マスター裁定 B4)。期限の有無で扱いを変えない */
    @Test
    void このターンの間の付与も引き継ぐ() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        MinionInstance material = f.putOnField(f.me(), WATER_PLAIN);
        material.grantKeywordThisTurn(Keyword.HASTE);
        material.addModifier(new StatModifier(StatModifier.Stat.ATTACK,
                StatModifier.Operation.ADD, 1, StatModifier.Duration.THIS_TURN, MAGMA));
        evolve(f, SHIRAKA, material);
        MinionInstance shiraka = evolutionOnField(f.me());
        assertThat(shiraka.hasKeyword(Keyword.HASTE)).isTrue();
        assertThat(shiraka.getEffectiveAttack()).isEqualTo(cards.findById(SHIRAKA).attack() + 1);
        // ★期限は生きている。ターンが終われば既存の仕組みで落ちる
        shiraka.expireThisTurnModifiers();
        assertThat(shiraka.hasKeyword(Keyword.HASTE)).isFalse();
        assertThat(shiraka.getEffectiveAttack()).isEqualTo(cards.findById(SHIRAKA).attack());
    }

    /** ★素材が2体以上なら全素材分を合算する(マスター裁定 B1) */
    @Test
    void 素材2体の付与は合算して引き継ぐ() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        MinionInstance a = f.putOnField(f.me(), DARK_MINION);
        MinionInstance b = f.putOnField(f.me(), DARK_MINION);
        a.addModifier(new StatModifier(StatModifier.Stat.ATTACK,
                StatModifier.Operation.ADD, 1, StatModifier.Duration.PERMANENT, MAGMA));
        b.addModifier(new StatModifier(StatModifier.Stat.ATTACK,
                StatModifier.Operation.ADD, 2, StatModifier.Duration.PERMANENT, MAGMA));
        evolve(f, MERINA, a, b);
        assertThat(evolutionOnField(f.me()).getEffectiveAttack())
                .isEqualTo(cards.findById(MERINA).attack() + 3);
    }

    /** ★受けているダメージは引き継がない(マスター裁定 B3) */
    @Test
    void 素材が受けているダメージは引き継がない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        MinionInstance material = f.putOnField(f.me(), WATER_PLAIN);
        material.takeDamage(1); // 1/1 なので瀕死
        evolve(f, SHIRAKA, material);
        MinionInstance shiraka = evolutionOnField(f.me());
        assertThat(shiraka.getDamage()).isZero();
        assertThat(shiraka.getCurrentHp()).isEqualTo(cards.findById(SHIRAKA).hp());
    }

    /** ★タップ状態は引き継がない(マスター裁定 B2) */
    @Test
    void 素材のタップ状態は引き継がない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        MinionInstance material = f.putOnField(f.me(), WATER_PLAIN);
        material.tap();
        evolve(f, SHIRAKA, material);
        assertThat(evolutionOnField(f.me()).isTapped()).isFalse();
    }

    /**
     * ★このターンの攻撃回数は引き継がない(マスター裁定 B5)。
     * 攻撃済みのミニオンに進化を重ねると、そのターンもう一度殴れる。
     */
    @Test
    void 素材の攻撃回数は引き継がない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        MinionInstance material = f.putOnField(f.me(), WATER_PLAIN);
        material.countAttack(); // 既に1回攻撃済み
        evolve(f, SHIRAKA, material);
        assertThat(evolutionOnField(f.me()).getAttacksUsedThisTurn()).isZero();
    }

    // ==================================================================
    // 3. 召喚酔い(裁定157(1))
    // ==================================================================

    @Test
    void 進化ミニオンは出したターンにリーダーを攻撃できる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        evolve(f, SHIRAKA, f.putOnField(f.me(), WATER_PLAIN));
        MinionInstance shiraka = evolutionOnField(f.me());
        int before = f.you().getLp();
        game.nextPhase(f.room(), "me"); // メイン → バトル
        game.attack(f.room(), "me", shiraka.getInstanceId(), null);
        assertThat(f.you().getLp()).isLessThan(before);
    }

    /**
     * ★「そうでない側」。同じ盤面で普通のミニオンは出したターンに攻撃できない ——
     * これが無いと、上の試験は<b>召喚酔いの判定そのものが死んでいる</b>実装でも通る(裁定181)。
     */
    @Test
    void 普通のミニオンは出したターンに攻撃できない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 1);
        game.playCard(f.room(), "me", f.giveHand(f.me(), PLAIN_MINION), List.of(), false);
        MinionInstance summoned = f.me().getMinionZone().get(0);
        game.nextPhase(f.room(), "me");
        assertThatThrownBy(() -> game.attack(f.room(), "me", summoned.getInstanceId(), null))
                .hasMessageContaining("出たターン");
    }

    // ==================================================================
    // 4. 場を離れるときの同伴(裁定154・マスター裁定 C1〜C3)
    // ==================================================================

    @Test
    void 破壊されると束のカードも墓地へ行く() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        evolve(f, SHIRAKA, f.putOnField(f.me(), WATER_PLAIN));
        MinionInstance shiraka = evolutionOnField(f.me());
        // シラーカは 2/2。マグマ・ストレートの3ダメージで破壊される
        game.playCard(f.room(), "me", f.giveHand(f.me(), MAGMA),
                List.of(minions(shiraka.getInstanceId())), false);
        assertThat(f.me().getMinionZone()).isEmpty();
        assertThat(f.me().getTrash()).contains(SHIRAKA, WATER_PLAIN);
    }

    /**
     * ★束のカードは破壊されたのではなく<b>同伴しただけ</b>なので【破壊時】は発動しない
     * (マスター裁定 C1)。ボーン・コレクターは破壊時に1枚引くカードである。
     */
    @Test
    void 束のカードの破壊時は発動しない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        evolve(f, TOUTA, f.putOnField(f.me(), ON_DESTROY_MINION));
        MinionInstance touta = evolutionOnField(f.me());
        int lpBefore = f.me().getLp();
        // 闘太は下1枚で 2/2。マグマ・ストレートで破壊する
        game.playCard(f.room(), "me", f.giveHand(f.me(), MAGMA),
                List.of(minions(touta.getInstanceId())), false);
        assertThat(f.me().getMinionZone()).isEmpty();
        assertThat(f.me().getTrash()).contains(ON_DESTROY_MINION);
        assertThat(f.me().getLp()).isEqualTo(lpBefore); // 支援盾機狸の自傷が起きていない
    }

    /**
     * ★「そうでない側」。同じボーン・コレクターを<b>場で</b>破壊すれば【破壊時】は動く ——
     * これが無いと、上の試験は「ボーン・コレクターの破壊時が未実装」でも通る(裁定181)。
     */
    @Test
    void 場で破壊された支援盾機狸は破壊時に自分のリーダーを削る() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 1);
        MinionInstance tanuki = f.putOnField(f.me(), ON_DESTROY_MINION); // 1/1
        int lpBefore = f.me().getLp();
        game.playCard(f.room(), "me", f.giveHand(f.me(), MAGMA),
                List.of(minions(tanuki.getInstanceId())), false);
        assertThat(f.me().getMinionZone()).isEmpty();
        assertThat(f.me().getLp()).isEqualTo(lpBefore - 1);
    }

    @Test
    void 手札に戻されると束のカードも手札へ戻る() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        evolve(f, SHIRAKA, f.putOnField(f.me(), WATER_PLAIN));
        MinionInstance shiraka = evolutionOnField(f.me());
        f.me().getHand().clear();
        actions().bounceToHand(f.room(), f.me(), shiraka);
        assertThat(f.me().getMinionZone()).isEmpty();
        assertThat(f.me().getHand()).containsExactlyInAnyOrder(SHIRAKA, WATER_PLAIN);
    }

    @Test
    void マナに置かれると束のカードも裏向きでマナへ行く() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        evolve(f, SHIRAKA, f.putOnField(f.me(), WATER_PLAIN));
        MinionInstance shiraka = evolutionOnField(f.me());
        int manaBefore = f.me().getManaZone().size();
        boolean moved = actions().putFieldMinionIntoManaFaceDown(f.room(), f.me(), shiraka);
        assertThat(moved).isTrue();
        assertThat(f.me().getMinionZone()).isEmpty();
        assertThat(f.me().getManaZone()).hasSize(manaBefore + 2); // 本体 + 束1枚
        assertThat(f.me().getManaZone().stream().filter(m -> !m.isFaceUp())).hasSize(2);
    }

    /**
     * ★マナ上限で束ごと置けないなら1枚も動かさない(マスター裁定 C2)。
     * 本体だけマナに入って束が宙に浮く、という状態を作らないためである。
     */
    @Test
    void マナ上限で束ごと置けないなら場から動かさない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        evolve(f, SHIRAKA, f.putOnField(f.me(), WATER_PLAIN));
        MinionInstance shiraka = evolutionOnField(f.me());
        // マナを上限-1 まで埋める(本体1枚ぶんしか空きが無い)
        while (f.me().getManaZone().size() < PlayerState.MAX_MANA - 1) {
            f.me().getManaZone().add(new ManaCard(MAGMA, false));
        }
        boolean moved = actions().putFieldMinionIntoManaFaceDown(f.room(), f.me(), shiraka);
        assertThat(moved).isFalse();
        assertThat(f.me().getMinionZone()).containsExactly(shiraka);
        assertThat(f.me().getManaZone()).hasSize(PlayerState.MAX_MANA - 1);
    }

    // ==================================================================
    // 5. 実装した6枚 + 茶爺
    // ==================================================================

    /** 《不敗鉄人闘太》: 【常在】AttackとHPは下にあるミニオン1枚につき+2(印刷値は 0/0) */
    @Test
    void 闘太は下にあるカード1枚につきAttackとHPが2ずつ増える() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        MinionInstance a = f.putOnField(f.me(), PLAIN_MINION);
        MinionInstance b = f.putOnField(f.me(), PLAIN_MINION);
        evolve(f, TOUTA, a, b);
        MinionInstance touta = evolutionOnField(f.me());
        assertThat(touta.getUnder()).hasSize(2);
        assertThat(touta.getMaxHp()).isEqualTo(4);
        assertThat(statAttack(f, touta)).isEqualTo(4);
    }

    @Test
    void 闘太は素材1体なら2対2である() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        evolve(f, TOUTA, f.putOnField(f.me(), PLAIN_MINION));
        MinionInstance touta = evolutionOnField(f.me());
        assertThat(touta.getMaxHp()).isEqualTo(2);
        assertThat(statAttack(f, touta)).isEqualTo(2);
    }

    /**
     * ★「そうでない側」。束を数える【常在】は闘太だけのものである ——
     * これが無いと「進化ミニオンは全部 +2/+2 される」実装でも通る(裁定181)。
     */
    @Test
    void 束を数える常在は闘太だけのものである() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        evolve(f, SHIRAKA, f.putOnField(f.me(), WATER_PLAIN));
        MinionInstance shiraka = evolutionOnField(f.me());
        assertThat(shiraka.getUnder()).hasSize(1);
        assertThat(shiraka.getMaxHp()).isEqualTo(cards.findById(SHIRAKA).hp());
        assertThat(statAttack(f, shiraka)).isEqualTo(cards.findById(SHIRAKA).attack());
    }

    /** 《追撃鉄人連太》: 【常在】このカードは2回攻撃できる */
    @Test
    void 連太は1ターンに2回攻撃できる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 7);
        evolve(f, TOUTA, f.putOnField(f.me(), PLAIN_MINION));
        evolve(f, RENTA, evolutionOnField(f.me()));
        MinionInstance renta = evolutionOnField(f.me());
        game.nextPhase(f.room(), "me");
        game.attack(f.room(), "me", renta.getInstanceId(), null);
        int afterFirst = f.you().getLp();
        game.attack(f.room(), "me", renta.getInstanceId(), null);
        assertThat(f.you().getLp()).isLessThan(afterFirst);
    }

    /** 《飛翔鉄人走太》: 【特殊召喚】場にミニオンが3体以上いるとき0コストで出せる(素材は要る) */
    @Test
    void 走太は場にミニオンが3体以上なら0コストで進化召喚できる() {
        AutoGameFixture f = newGame();
        MinionInstance a = f.putOnField(f.me(), PLAIN_MINION);
        f.putOnField(f.me(), PLAIN_MINION);
        f.putOnField(f.you(), PLAIN_MINION); // ★「場に」は両者の合計
        int handIndex = f.giveHand(f.me(), SOTA);
        game.specialSummon(f.room(), "me", handIndex, List.of(), List.of(a.getInstanceId()));
        assertThat(f.fieldIds(f.me())).contains(SOTA);
        assertThat(f.me().getAvailableMp()).isZero(); // マナを1枚も置いていない = 0コスト
    }

    @Test
    void 走太は場のミニオンが2体以下なら特殊召喚できない() {
        AutoGameFixture f = newGame();
        MinionInstance a = f.putOnField(f.me(), PLAIN_MINION);
        f.putOnField(f.me(), PLAIN_MINION);
        int handIndex = f.giveHand(f.me(), SOTA);
        assertThatThrownBy(() -> game.specialSummon(f.room(), "me", handIndex, List.of(),
                List.of(a.getInstanceId())))
                .hasMessageContaining("特殊召喚の条件");
    }

    /** ★特殊召喚でも素材は要る(マスター裁定 D1) */
    @Test
    void 走太は特殊召喚でも素材を要求する() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), PLAIN_MINION);
        f.putOnField(f.me(), PLAIN_MINION);
        f.putOnField(f.me(), PLAIN_MINION);
        int handIndex = f.giveHand(f.me(), SOTA);
        assertThatThrownBy(() -> game.specialSummon(f.room(), "me", handIndex, List.of(), List.of()))
                .hasMessageContaining("進化素材");
    }

    /** 《武羅須斗最終》: 【特殊召喚】自分のマナが7枚以上のときコスト1で出せる */
    @Test
    void 武羅須斗最終はマナ7枚以上ならコスト1で進化召喚できる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 7);
        MinionInstance material = f.putOnField(f.me(), EARTH_MINION);
        int handIndex = f.giveHand(f.me(), BURASUTO);
        game.specialSummon(f.room(), "me", handIndex, List.of(), List.of(material.getInstanceId()));
        assertThat(f.fieldIds(f.me())).containsExactly(BURASUTO);
        assertThat(f.me().getAvailableMp()).isEqualTo(6); // 7枚のうち1枚だけ使った
    }

    @Test
    void 武羅須斗最終はマナが6枚以下なら特殊召喚できない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        MinionInstance material = f.putOnField(f.me(), EARTH_MINION);
        int handIndex = f.giveHand(f.me(), BURASUTO);
        assertThatThrownBy(() -> game.specialSummon(f.room(), "me", handIndex, List.of(),
                List.of(material.getInstanceId())))
                .hasMessageContaining("特殊召喚の条件");
    }

    /** 《裏雷怒乗込》: 【守護】攻撃時カードを1枚引く */
    @Test
    void 裏雷怒乗込は攻撃時に1枚引く() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        evolve(f, RIRAIDO, f.putOnField(f.me(), EARTH_MINION));
        MinionInstance riraido = evolutionOnField(f.me());
        int deckBefore = f.me().getDeck().size();
        game.nextPhase(f.room(), "me");
        game.attack(f.room(), "me", riraido.getInstanceId(), null);
        assertThat(f.me().getDeck()).hasSize(deckBefore - 1);
    }

    /**
     * 《サービスブレイク・メリィナ》:
     * 「このカードのコストは自分の場に居るミニオンの数-1される。このカードのコストは2以下にならない。」
     * ★減る量は<b>ミニオンの数そのもの</b>で、下限は<b>3</b>である(マスター裁定)。
     * ★数えるのは進化召喚で素材を外す<b>前</b>の場である。
     */
    @Test
    void メリィナのコストは場のミニオンの数だけ下がる() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), DARK_MINION);
        f.putOnField(f.me(), DARK_MINION);
        // 印刷6 - 場2体 = 4
        assertThat(effectiveCostOf(f, MERINA)).isEqualTo(4);
    }

    @Test
    void メリィナのコストは3より下がらない() {
        AutoGameFixture f = newGame();
        for (int i = 0; i < 5; i++) {
            f.putOnField(f.me(), DARK_MINION);
        }
        // 印刷6 - 場5体 = 1 だが、下限の3で止まる
        assertThat(effectiveCostOf(f, MERINA)).isEqualTo(3);
    }

    /** 《サービスブレイク・メリィナ》: 【常在】自分の他のミニオンのAttack+1(自分自身は乗らない) */
    @Test
    void メリィナは自分の他のミニオンのAttackを1上げる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        MinionInstance a = f.putOnField(f.me(), DARK_MINION);
        MinionInstance b = f.putOnField(f.me(), DARK_MINION);
        MinionInstance bystander = f.putOnField(f.me(), PLAIN_MINION);
        int before = statAttack(f, bystander);
        evolve(f, MERINA, a, b);
        assertThat(statAttack(f, bystander)).isEqualTo(before + 1);
        // ★自分自身には乗らない
        assertThat(statAttack(f, evolutionOnField(f.me())))
                .isEqualTo(cards.findById(MERINA).attack());
    }

    /** 《機神兵長茶爺》: 【起動：1】自分の進化ミニオン1枚の下に手札のミニオンを入れ、1枚引く */
    @Test
    void 茶爺は進化ミニオンの下に手札のミニオンを入れて1枚引く() {
        AutoGameFixture f = newGame(CHAJI_LEADER);
        payMana(f.me(), 6);
        evolve(f, TOUTA, f.putOnField(f.me(), PLAIN_MINION));
        MinionInstance touta = evolutionOnField(f.me());
        assertThat(touta.getMaxHp()).isEqualTo(2);
        int handIndex = f.giveHand(f.me(), PLAIN_MINION);
        int deckBefore = f.me().getDeck().size();
        game.useLeaderAbility(f.room(), "me",
                List.of(hand(handIndex), minions(touta.getInstanceId())));
        assertThat(touta.getUnder()).hasSize(2);
        assertThat(touta.getMaxHp()).isEqualTo(4); // ★束が増えた分だけ即座に育つ(常在は保存しない)
        assertThat(f.me().getDeck()).hasSize(deckBefore - 1);
    }

    @Test
    void 茶爺は進化ミニオンが自分の場に居なければ起動できない() {
        AutoGameFixture f = newGame(CHAJI_LEADER);
        payMana(f.me(), 3);
        f.putOnField(f.me(), PLAIN_MINION);
        int handIndex = f.giveHand(f.me(), PLAIN_MINION);
        assertThatThrownBy(() -> game.useLeaderAbility(f.room(), "me",
                List.of(hand(handIndex), minions(f.me().getMinionZone().get(0).getInstanceId()))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 茶爺は相手の進化ミニオンの下には入れられない() {
        AutoGameFixture f = newGame(CHAJI_LEADER);
        payMana(f.me(), 6);
        evolve(f, TOUTA, f.putOnField(f.me(), PLAIN_MINION)); // 自分の場に進化を1体(起動条件)
        MinionInstance theirs = new MinionInstance(cards.findById(TOUTA), 1);
        f.you().getMinionZone().add(theirs);
        int handIndex = f.giveHand(f.me(), PLAIN_MINION);
        assertThatThrownBy(() -> game.useLeaderAbility(f.room(), "me",
                List.of(hand(handIndex), minions(theirs.getInstanceId()))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================================================================
    // 6. 禁忌デッキからの進化召喚(マスター裁定 E1)
    // ==================================================================

    /**
     * ★禁忌デッキに入れた進化も、出し方は通常と同じである ——
     * 素材は自分の場から取り、コストの支払い方だけが禁忌の作法(マナを裏向きにする)になる。
     * ★<b>行き先は1枚ずつ自分の出自に従う</b>(マスター裁定 C3) ——
     * 禁忌由来の本体は消滅ゾーンへ、通常デッキ由来の素材は墓地へ行く。
     */
    @Test
    void 禁忌デッキの進化も素材を使って場に出せる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4); // 禁忌コスト3 + マグマ1
        MinionInstance material = f.putOnField(f.me(), EARTH_MINION);
        f.me().getTabooDeck().add(RIRAIDO); // コスト3
        game.playTabooCard(f.room(), "me", 0, List.of(0, 1, 2), List.of(),
                List.of(material.getInstanceId()));
        MinionInstance riraido = evolutionOnField(f.me());
        assertThat(riraido.getMaster().id()).isEqualTo(RIRAIDO);
        assertThat(riraido.isFromTaboo()).isTrue();
        assertThat(riraido.getUnder()).hasSize(1);
    }

    @Test
    void 禁忌由来の進化が破壊されると本体は消滅し素材は墓地へ行く() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        MinionInstance material = f.putOnField(f.me(), EARTH_MINION);
        f.me().getTabooDeck().add(RIRAIDO);
        game.playTabooCard(f.room(), "me", 0, List.of(0, 1, 2), List.of(),
                List.of(material.getInstanceId()));
        MinionInstance riraido = evolutionOnField(f.me());
        // 裏雷怒乗込は 1/5。3ダメージでは死なないので、直接破壊の入口を使う
        actions().destroyMinion(f.room(), f.me(), riraido);
        assertThat(f.me().getLostZone()).containsExactly(RIRAIDO);
        assertThat(f.me().getTrash()).contains(EARTH_MINION);
    }

    // ==================================================================
    // 7. 素材条件は18枚すべてに登録されている(デッキ解禁の前提)
    // ==================================================================

    /**
     * ★デッキ構築で進化を解禁できたのは、18枚すべてに素材条件があるからである。
     * 1枚でも欠けると、そのカードは<b>デッキに入るのに場に出せない</b>死に札になる ——
     * 裁定166 が進化を弾いていた理由がそのまま戻ってくる。
     */
    @Test
    void 進化ミニオン18枚すべてに素材条件が登録されている() {
        var evolutions = cards.getAllCards().stream()
                .filter(c -> c.type() == com.example.qte.master.CardType.EVOLUTION)
                .toList();
        assertThat(evolutions).hasSize(18);
        for (var card : evolutions) {
            assertThat(registry().evolutionOf(card.id()))
                    .as(card.name() + " に進化の素材条件が無い")
                    .isNotNull();
        }
    }

    /**
     * ★「そうでない側」。進化ミニオンでないカードには素材条件が無い ——
     * これが無いと「evolutionOf が常に何かを返す」実装でも上の試験は通る(裁定181)。
     */
    @Test
    void 進化ミニオンでないカードには素材条件が無い() {
        assertThat(registry().evolutionOf(PLAIN_MINION)).isNull();
        assertThat(registry().evolutionOf(MAGMA)).isNull();
    }

    // ==================================================================
    // 補助
    // ==================================================================

    @Autowired
    private com.example.qte.effect.CardEffectRegistry registry;

    @Autowired
    private com.example.qte.game.GameActions gameActions;

    @Autowired
    private com.example.qte.effect.StatCalculator statCalculator;

    private com.example.qte.effect.CardEffectRegistry registry() {
        return registry;
    }

    private com.example.qte.game.GameActions actions() {
        return gameActions;
    }

    private int statAttack(AutoGameFixture f, MinionInstance minion) {
        return statCalculator.effectiveAttack(f.state(), f.me(), minion);
    }

    private int effectiveCostOf(AutoGameFixture f, String cardId) {
        return statCalculator.effectiveCost(f.state(), f.me(), cards.findById(cardId));
    }

    @Test
    void 素材条件の説明文が画面に出せる形で入っている() {
        assertThatCode(() -> {
            for (var card : cards.getAllCards()) {
                var spec = registry().evolutionOf(card.id());
                if (spec != null) {
                    assertThat(spec.description()).isNotBlank();
                    assertThat(spec.minMaterials()).isGreaterThanOrEqualTo(1);
                }
            }
        }).doesNotThrowAnyException();
    }
}
