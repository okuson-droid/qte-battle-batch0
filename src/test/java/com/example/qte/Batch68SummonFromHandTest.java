package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.effect.CardEffectRegistry;
import com.example.qte.game.GameService;
import com.example.qte.game.ManaCard;
import com.example.qte.game.MinionInstance;
import com.example.qte.game.PlayerState;
import com.example.qte.game.TurnPhase;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.support.AutoGameFixture;

/**
 * Batch 68 ②: 効果で<b>手札から</b>場に出たミニオンは【召喚時】も発動する(裁定311)。
 *
 * <h2>何が変わったのか</h2>
 *
 * 63 から 67 までの規則は<b>「【召喚時】は召喚でのみ発動する」</b>(設計判断19)であった。
 * 効果による「出す」は経路を問わず【登場時】だけを焚いていた。
 *
 * <p>裁定311 はこれを<b>出どころ</b>で切り直した ——
 * <ul>
 * <li><b>手札から</b>効果で出た …… 【召喚時】も発動する(<b>新しい</b>)</li>
 * <li>墓地・マナ・山札から効果で出た …… 【登場時】だけ(今までどおり)</li>
 * <li>召喚(通常・進化・特殊・禁忌・墓地からの召喚)…… 両方(今までどおり)</li>
 * </ul>
 *
 * <h2>★なぜ「渡し忘れ」を機械に見張らせるのか</h2>
 *
 * 出どころは {@code GameActions.putIntoFieldByEffect} の引数
 * ({@link com.example.qte.game.FieldEntryOrigin})で運ぶ。
 * 呼び出し側が渡し忘れると<b>【召喚時】が黙って発動しなくなる</b> ——
 * しかも症状が出るのは「手札から出す効果」と「【召喚時】を持つカード」を
 * <b>組み合わせたときだけ</b>である。人の目には見えない類の壊れ方なので、
 * 実際に出して実際に焚けたかを1枚ずつ測る。
 *
 * <h2>★235枚でただ1つの例外</h2>
 *
 * 《スタンディングテント》の【賢魂：2】は本文に
 * 「そのミニオンの【召喚時】は使えない」と明記されている。
 * 67 まではこれが既定(裁定245)の言い換えにすぎなかったが、
 * 裁定311 で既定が反転したことにより<b>本物の例外</b>になった。
 */
@SpringBootTest
class Batch68SummonFromHandTest {

    /** 常在効果を持たないリーダー(蒼海の賢者) */
    private static final String PLAIN_LEADER = "QTE-M-WATER-1";

    /** 水鏡の幻術師(水・5/5/3)。【召喚時】カードを2枚引く = 発動したかが見える物差し */
    private static final String ON_SUMMON_DRAW = "QTE-M-WATER-7";
    /** ライト・シールド(光・2/1/3・【守護】)。【召喚時】を持たない普通の【守護】 */
    private static final String LIGHT_SHIELD = "QTE-M-LIGHT-2";
    /** マグマ・ストレート(火・スペル・1)。マナの中身に使う */
    private static final String MAGMA = "QTE-M-FIRE-10";

    /** 聖なる降誕の儀式(光・スペル)。手札からコスト7以下の【守護】を1体出す */
    private static final String HOLY_NATIVITY = "QTE-M-LIGHT-11";
    /** 神の福音(光・スペル・6)。手札から光の【守護】を2体まで出し、出した数だけ引く */
    private static final String GOSPEL = "QTE-M-LIGHT-12";
    /** スタンディングテント(闇・6/1/6・【守護】)。【賢魂:2】で自身を場に出す */
    private static final String STANDING_TENT = "QTE-M-DARK-38";
    /** 英霊・タイガラム(光・進化・7・【守護】)。神の福音・降誕の儀式の進化候補 */
    private static final String GUARD_EVOLUTION = "QTE-M-LIGHT-32";
    /** 知識の守護者(水・4/0/5・【守護】)。光文明ではないので《神の福音》では出せない */
    private static final String KNOWLEDGE_GUARDIAN = "QTE-M-WATER-5";
    /** 天界の守護神 ゾディアック(光・9・【守護】)。光文明で【召喚時】を持つ唯一の【守護】 */
    private static final String ZODIAC = "QTE-M-LIGHT-8";

    @Autowired
    private GameService game;

    @Autowired
    private CardEffectRegistry effects;

    @Autowired
    private CardMasterRepository cards;

    private AutoGameFixture newGame() {
        AutoGameFixture f = new AutoGameFixture(cards, PLAIN_LEADER, PLAIN_LEADER);
        f.fillDeck(f.me(), 40);
        f.fillDeck(f.you(), 40);
        return f;
    }

    private void payMana(PlayerState player, int count) {
        for (int i = 0; i < count; i++) {
            player.getManaZone().add(new ManaCard(MAGMA, false));
        }
    }

    // ==================================================================
    // 1. 手札から出せば【召喚時】が発動する(裁定311)
    // ==================================================================

    @Test
    void 効果で手札から出したミニオンは召喚時を発動する() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        f.giveHand(f.me(), ON_SUMMON_DRAW); // ★水鏡の幻術師はコスト5だが踏み倒される
        int before = f.me().getDeck().size();

        game.playCard(f.room(), "me", f.giveHand(f.me(), HOLY_NATIVITY), List.of(), false);
        f.answerChoice(game, "me", f.handPosition(f.me(), ON_SUMMON_DRAW));

        assertThat(f.fieldIds(f.me())).contains(ON_SUMMON_DRAW);
        assertThat(before - f.me().getDeck().size())
                .as("★裁定311: 手札から出たので【召喚時】の2ドローが起きる")
                .isEqualTo(2);
    }

    /**
     * ★<b>「そうでない側」を測る</b>(裁定181)。墓地から効果で出した場合は
     * 【登場時】だけが発動する —— 裁定311 が広げたのは<b>手札から</b>だけである。
     */
    @Test
    void 効果で墓地から出したミニオンは召喚時を発動しない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6); // 《死者蘇生》はコスト6(生贄1体につき-1)
        f.me().getTrash().add(ON_SUMMON_DRAW);
        int before = f.me().getDeck().size();

        // 《死者蘇生》: 生贄は0体でよい(upTo)。蘇生する1体は割り込みで選ぶ(★Batch 64)
        game.playCard(f.room(), "me", f.giveHand(f.me(), "QTE-M-DARK-12"),
                List.of(new com.example.qte.effect.TargetChoice(
                        null, List.of(), null, null, null)), false);
        if (f.me().getPendingChoice() != null) {
            f.answerChoice(game, "me", "0");
        }

        assertThat(f.fieldIds(f.me())).as("蘇生そのものは起きている").contains(ON_SUMMON_DRAW);
        assertThat(before - f.me().getDeck().size())
                .as("★墓地からは【召喚時】が発動しない(裁定311(a) の外側)")
                .isZero();
    }

    // ==================================================================
    // 2. ★渡し忘れの番人 —— 手札から出す効果を1枚ずつ通す
    // ==================================================================

    /**
     * ★★★<b>裁定311 と裁定282 が重なる1件である。</b>
     *
     * <p>《神の福音》がコスト9の《天界の守護神 ゾディアック》を手札から踏み倒すと ——
     * <ol>
     * <li>場に出て、</li>
     * <li><b>手札から出たので【召喚時】が発動し</b>(裁定311)、</li>
     * <li>その対象(相手のウェポン)を<b>場に出てから</b>問われる(裁定282)</li>
     * </ol>
     * が順に起きる。67 までは (2) から先が丸ごと起きなかった。
     */
    @Test
    void 神の福音で出したミニオンの召喚時も対象を問う() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        f.you().setEquippedWeapon(cards.findById("QTE-M-WATER-13"));
        f.giveHand(f.me(), ZODIAC);       // 光文明・【守護】・コスト9
        f.giveHand(f.me(), LIGHT_SHIELD); // 2体目(【召喚時】を持たない)
        int before = f.me().getDeck().size();

        game.playCard(f.room(), "me", f.giveHand(f.me(), GOSPEL), List.of(), false);
        f.answerChoice(game, "me", "0", "1"); // 出す2体

        assertThat(f.me().getPendingChoice())
                .as("★出したゾディアックの【召喚時】が対象を問うている(裁定311 + 282)")
                .isNotNull();
        f.answerChoice(game, "me", "OPPONENT");

        assertThat(f.you().getEquippedWeapon()).as("★【召喚時】が実際に効いた").isNull();
        assertThat(f.fieldIds(f.me())).containsExactlyInAnyOrder(ZODIAC, LIGHT_SHIELD);
        assertThat(before - f.me().getDeck().size())
                .as("★出した数(2)だけ引く").isEqualTo(2);
    }

    /** ★《神の福音》は「2体まで」なので、1体だけ選べば1枚しか引かない(裁定191 の形) */
    @Test
    void 神の福音は出した数だけ引く() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        f.giveHand(f.me(), LIGHT_SHIELD);
        f.giveHand(f.me(), LIGHT_SHIELD);
        int before = f.me().getDeck().size();

        game.playCard(f.room(), "me", f.giveHand(f.me(), GOSPEL), List.of(), false);
        f.answerChoice(game, "me", "0");

        assertThat(f.me().getMinionZone()).hasSize(1);
        assertThat(before - f.me().getDeck().size()).as("1体しか出していないので1枚").isEqualTo(1);
    }

    /**
     * ★★★<b>裁定308 + 裁定311 が重なる1件である。</b>
     *
     * <p>《神の福音》が進化ミニオンを出すと ——
     * <ol>
     * <li>素材を問われ(裁定226・308(b) の但し書き)、</li>
     * <li>素材つきで場に出て、</li>
     * <li><b>手札から出たので【召喚時】も発動し</b>(裁定311)、</li>
     * <li>出した数だけ引く(本文の後半)</li>
     * </ol>
     * が順に起きる。
     *
     * <p>★<b>裁定245(効果で出した進化は【召喚時】を発動しない)と衝突していた。</b>
     * 番号の大きい 311 を優先しており、<b>裁定312 がそれを確定させた</b>(2026-08-26)——
     * 245 は既定の言い換えにすぎず、既定が変わったので一緒に動いた。
     * ★<b>したがって裁定245 は失効している</b>(裁定74 が 283 で失効したのと同じ形)。
     * ★★<b>この試験がその番人である。</b>赤くなったら 312 の読みが変わったということである。
     */
    @Test
    void 神の福音で出した進化ミニオンも召喚時を発動する() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        MinionInstance material = f.putOnField(f.me(), LIGHT_SHIELD); // タイガラムの素材
        f.giveHand(f.me(), GUARD_EVOLUTION);
        f.giveHand(f.me(), LIGHT_SHIELD); // ★タイガラムの【召喚時】が場に出す1体
        int before = f.me().getDeck().size();

        game.playCard(f.room(), "me", f.giveHand(f.me(), GOSPEL), List.of(), false);
        f.answerChoice(game, "me", f.handPosition(f.me(), GUARD_EVOLUTION));
        f.answerChoice(game, "me", material.getInstanceId());          // 進化素材
        f.answerChoice(game, "me", f.handPosition(f.me(), LIGHT_SHIELD)); // タイガラムの【召喚時】

        assertThat(f.fieldIds(f.me()))
                .as("★進化と、その【召喚時】が出した1体が並ぶ")
                .containsExactlyInAnyOrder(GUARD_EVOLUTION, LIGHT_SHIELD);
        assertThat(before - f.me().getDeck().size())
                .as("★福音が出したのは進化の1体だけなので1枚引く")
                .isEqualTo(1);
    }

    /**
     * ★<b>素材を確保できない進化は、そもそも候補に入らない</b>(裁定308(b) の但し書き)。
     * これが無いと「素材が無くても選べて、選んだ後に不発になる」形になり、
     * 成立しない選択肢を並べたことになる(裁定302)。
     */
    @Test
    void 素材を確保できない進化は候補に入らない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        f.giveHand(f.me(), GUARD_EVOLUTION); // ★場に素材が1体も居ない
        f.giveHand(f.me(), LIGHT_SHIELD);

        game.playCard(f.room(), "me", f.giveHand(f.me(), GOSPEL), List.of(), false);

        assertThat(f.me().getPendingChoice().candidates())
                .containsExactly(f.handPosition(f.me(), LIGHT_SHIELD));
    }

    /**
     * ★<b>使用条件と候補は同じ規則を見る</b>(裁定130)。
     * 《聖なる降誕の儀式》は「出せるミニオンが手札に無ければ使えない」——
     * 進化しか無くて素材が居ないなら、<b>使用そのものが通らない</b>。
     */
    @Test
    void 降誕の儀式は出せるミニオンが無ければ使用できない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        f.giveHand(f.me(), GUARD_EVOLUTION); // 素材が居ないので出せない

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> game.playCard(f.room(), "me",
                        f.giveHand(f.me(), HOLY_NATIVITY), List.of(), false))
                .hasMessageContaining("条件");
    }

    /** ★同じ盤面でも、素材が場に居れば使用できる(上の試験と対になる。裁定181) */
    @Test
    void 降誕の儀式は素材が居れば進化しか無くても使用できる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        MinionInstance material = f.putOnField(f.me(), LIGHT_SHIELD);
        f.giveHand(f.me(), GUARD_EVOLUTION);
        f.giveHand(f.me(), LIGHT_SHIELD); // タイガラムの【召喚時】が出す1体

        game.playCard(f.room(), "me", f.giveHand(f.me(), HOLY_NATIVITY), List.of(), false);
        f.answerChoice(game, "me", f.handPosition(f.me(), GUARD_EVOLUTION));
        f.answerChoice(game, "me", material.getInstanceId());

        assertThat(f.fieldIds(f.me())).contains(GUARD_EVOLUTION);
    }

    // ==================================================================
    // 3. ★235枚でただ1つの例外(《スタンディングテント》)
    // ==================================================================

    /**
     * ★★★<b>本文が既定を打ち消す唯一の1枚である</b>(総合ルール2-7)。
     *
     * <p>【賢魂】は手札のカードを使うので、裁定311 の既定に従えば
     * このミニオンの【召喚時】(カードを2枚引く)も発動してしまう。
     * 本文の「そのミニオンの【召喚時】は使えない」がそれを打ち消す。
     *
     * <p>★<b>この試験が無いと、次の人はこれを「渡し忘れ」だと思って直してしまう。</b>
     */
    @Test
    void スタンディングテントの賢魂で出た自身は召喚時を発動しない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        int before = f.me().getDeck().size();

        // 【賢魂:2】として使う(スペルとしての姿)
        game.playSoulCard(f.room(), "me", f.giveHand(f.me(), STANDING_TENT), List.of());

        assertThat(f.fieldIds(f.me())).as("自身が場に出る").containsExactly(STANDING_TENT);
        assertThat(before - f.me().getDeck().size())
                .as("★本文が【召喚時】を打ち消している(裁定311 の唯一の例外)")
                .isZero();
        assertThat(f.me().getMinionZone().get(0).getDamage())
                .as("★空振りでないことの証拠: 本文の2ダメージは入っている").isEqualTo(2);
    }

    /**
     * ★<b>同じカードを普通に召喚すれば【召喚時】は起きる</b>(対になる試験)。
     * 打ち消しているのは【賢魂】の本文であって、カードそのものではない。
     */
    @Test
    void スタンディングテントを召喚すれば召喚時は起きる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        int before = f.me().getDeck().size();

        game.playCard(f.room(), "me", f.giveHand(f.me(), STANDING_TENT), List.of(), false);

        assertThat(before - f.me().getDeck().size())
                .as("★召喚なら2枚引く").isEqualTo(2);
    }

    // ==================================================================
    // 4. 場が満杯・出せない場合の後始末
    // ==================================================================

    /** ★出せなかったぶんは手札に残る(66 までと同じ扱い。50 の落とし穴) */
    @Test
    void 神の福音は場が満杯なら出せなかったぶんが手札に残る() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        for (int i = 0; i < f.me().getMinionZoneLimit() - 1; i++) {
            f.putOnField(f.me(), LIGHT_SHIELD); // 残り1枠にする
        }
        f.giveHand(f.me(), LIGHT_SHIELD);
        f.giveHand(f.me(), LIGHT_SHIELD);
        int before = f.me().getDeck().size();

        game.playCard(f.room(), "me", f.giveHand(f.me(), GOSPEL), List.of(), false);
        f.answerChoice(game, "me", "0", "1");

        assertThat(f.me().getMinionZone()).as("場は上限まで埋まった")
                .hasSize(f.me().getMinionZoneLimit());
        assertThat(f.me().getHand()).as("★出せなかった1枚は手札に残る").contains(LIGHT_SHIELD);
        assertThat(before - f.me().getDeck().size())
                .as("★引くのは実際に出した数(1枚)である").isEqualTo(1);
    }

    /**
     * ★<b>フェイズを跨いでも壊れない。</b>相手のターン中に焚かれる誘発から
     * 手札を出すカードは今のところ無いが、【召喚時】が発火する場所は
     * {@code fire()} の1点に集約してあるので、経路が増えても同じ道を通る(裁定163)。
     * ここでは「登録が存在すること」だけを固定する。
     */
    @Test
    void 手札から出す効果はすべて登録されている() {
        // 効果で手札から場に出す9枚(FieldEntryOrigin の注記と同じ一覧)
        List<String> fromHand = List.of(
                "QTE-M-FIRE-17",  // 逆境の猛火者
                "QTE-M-LIGHT-8",  // 天界の守護神 ゾディアック(ウェポン破壊。手札は出さないが15枚の一員)
                "QTE-M-LIGHT-21", // 光の召喚士
                "QTE-M-LIGHT-12", // 神の福音
                "QTE-M-LIGHT-11", // 聖なる降誕の儀式
                "QTE-M-WATER-27", // ギガマウス・バイト
                "QTE-M-LIGHT-39", // 英術・スケアロック
                "QTE-M-DARK-38",  // スタンディングテント
                "QTE-M-LIGHT-32"  // 英霊・タイガラム
        );
        for (String cardId : fromHand) {
            assertThat(effects.isRegistered(cardId))
                    .as("【%s】は実装済みでなければならない".formatted(cards.findById(cardId).name()))
                    .isTrue();
        }
    }

    /** ★フェイズの検査を持つカードは、盤面を作らなくても条件だけで測れる */
    @Test
    void 手札から出す効果はメインフェイズ以外では走らない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        f.giveHand(f.me(), LIGHT_SHIELD);
        f.state().setPhase(TurnPhase.BATTLE);

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> game.playCard(f.room(), "me",
                        f.giveHand(f.me(), GOSPEL), List.of(), false))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 使わない定数への参照(未使用警告を避けつつ、物差しの意図を残す) */
    @Test
    void 知識の守護者は光文明ではないので神の福音では出せない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6);
        f.giveHand(f.me(), KNOWLEDGE_GUARDIAN); // 水文明の【守護】
        f.giveHand(f.me(), LIGHT_SHIELD);

        game.playCard(f.room(), "me", f.giveHand(f.me(), GOSPEL), List.of(), false);

        assertThat(f.me().getPendingChoice().candidates())
                .as("★光文明の絞り込みは効いている")
                .containsExactly(f.handPosition(f.me(), LIGHT_SHIELD));
    }
}
