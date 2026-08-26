package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.effect.CardEffectRegistry;
import com.example.qte.effect.TargetSpec;
import com.example.qte.effect.TriggerType;
import com.example.qte.game.GameService;
import com.example.qte.game.ManaCard;
import com.example.qte.game.MinionInstance;
import com.example.qte.game.PlayerState;
import com.example.qte.game.TurnPhase;
import com.example.qte.master.CardMaster;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.CardType;
import com.example.qte.master.Civilization;
import com.example.qte.support.AutoGameFixture;

/**
 * Batch 68 ①: 【召喚時】【登場時】の対象は<b>ミニオンが場に出てから</b>選ぶ(裁定282)。
 *
 * <h2>このバッチが変えたもの</h2>
 *
 * 66 まで、ミニオンの【召喚時】が要求する対象は<b>使用を宣言した時点</b>で選ばせていた。
 * 総合ルールに照らすとこれは誤りで、対象はミニオンが場に出てから選ぶ。
 * マスター裁定282 がそれを確定させ、68 で15枚が割り込み({@code PendingChoice})へ移った。
 *
 * <h2>★この直しが「消した」もの</h2>
 *
 * <ul>
 * <li><b>裁定258 の罠</b> …… 進化召喚の宣言時の検証は<b>素材を場から外す前</b>に走っていた。
 *     素材にしたミニオンを対象に選べる危うさが構造として残っていたが、
 *     対象を選ぶ頃には素材はもう場に居ない。</li>
 * <li><b>裁定282 の門</b>({@code GameService.requireTrashSourceNotTargeted})……
 *     墓地から出すカード自身が墓地の候補に混じる穴。
 *     カードが墓地を離れてから候補を数えるので、混じりようがない。
 *     <b>撤去した</b>(裁定196。撤去の記録は GameService に残してある)。</li>
 * <li><b>候補ゼロで召喚が弾かれる</b>(48 の落とし穴)…… 宣言時の検証を通らないので起きない。</li>
 * </ul>
 *
 * <h2>本物の入口を通す</h2>
 *
 * {@link AutoGameFixture} の上に書き、効果は {@code GameService.playCard} /
 * {@code summonFromGrave} / {@code resolveChoice} から起こす(裁定187)。
 */
@SpringBootTest
class Batch68SummonTargetTest {

    /** 常在効果を持たないリーダー(蒼海の賢者) */
    private static final String PLAIN_LEADER = "QTE-M-WATER-1";

    /** 執念の暗殺者(闇・4/3/3)。【召喚時】ミニオン1体に3ダメージ */
    private static final String SHADOW_ASSASSIN = "QTE-M-DARK-20";
    /** 腐敗の投擲者(闇・2/2/1)。【召喚時】<b>相手の</b>ミニオン1体に1ダメージ */
    private static final String ROT_THROWER = "QTE-M-DARK-17";
    /** 手札を喰らう大蟹(水・4)。【召喚時】手札1枚を捨て、そうしたらミニオン1体を手札へ戻す */
    private static final String CRAB = "QTE-M-WATER-4";
    /** 天界の守護神 ゾディアック(光・9)。【召喚時】相手のウェポンを1つ破壊 */
    private static final String ZODIAC = "QTE-M-LIGHT-8";
    /** 海淵獣ゾクシム(水・進化)。裁定307 で ON_SUMMON → ON_ENTER になった */
    private static final String ZOKUSHIMU = "QTE-M-WATER-32";
    /** 海皇 ポセイドン(水)。裁定306 でメインフェイズの検査が付いた */
    private static final String POSEIDON = "QTE-M-WATER-8";
    /** ライト・シールド(光・2/1/3・【守護】)。ゾクシムの素材にはならない光文明の普通のミニオン */
    private static final String LIGHT_SHIELD = "QTE-M-LIGHT-2";
    /** スカイ・スワロー(風・1/1/1)。対象を選ばない最小のミニオン */
    private static final String SKY_SWALLOW = "QTE-M-WIND-3";
    /** マグマ・ストレート(火・スペル・1)。マナの中身に使う */
    private static final String MAGMA = "QTE-M-FIRE-10";
    /** 真珠の三叉槍(水・ウェポン・3) */
    private static final String WEAPON = "QTE-M-WATER-13";

    @Autowired
    private GameService game;

    @Autowired
    private CardEffectRegistry effects;

    @Autowired
    private CardMasterRepository cards;

    @Autowired
    private com.example.qte.game.view.GameViewBuilder views;

    private AutoGameFixture newGame() {
        AutoGameFixture f = new AutoGameFixture(cards, PLAIN_LEADER, PLAIN_LEADER);
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

    // ==================================================================
    // 1. 構造の番人 —— 「宣言時の対象を持つミニオンは1枚も無い」
    // ==================================================================

    /**
     * ★★★<b>68 でいちばん大事な番人である。</b>
     *
     * <p>{@code CardEffectRegistry.declarationTargetSpecOf} は
     * ミニオン(進化を含む)に対して<b>必ず空の {@link TargetSpec} を返す</b>。
     * 使用宣言の入口({@code GameService.playMinion} / {@code summonFromGrave} /
     * {@code GameViewBuilder})は全部これを通るので、
     * <b>1枚でも例外を作れば、この試験が落ちる</b>。
     *
     * <p>★スペルとウェポンは今までどおり宣言時に対象を選ぶ ——
     * あちらは「場に出る」ものではないので、裁定282 の対象外である。
     */
    @Test
    void ミニオンは宣言時の対象要求を1件も持たない() {
        int minions = 0;
        int spellsWithTargets = 0;
        for (Civilization civ : Civilization.values()) {
            for (CardMaster m : cards.findByCivilization(civ)) {
                if (m.type().isMinion()) {
                    minions++;
                    assertThat(effects.declarationTargetSpecOf(m.id()).requirements())
                            .as("★【%s】は宣言時の対象を持ってはいけない(裁定282)".formatted(m.name()))
                            .isEmpty();
                } else if (!effects.targetSpecOf(m.id()).requirements().isEmpty()) {
                    spellsWithTargets++;
                }
            }
        }
        assertThat(minions).as("★空振りでないことの証拠: ミニオンを実際に数えている").isGreaterThan(100);
        assertThat(spellsWithTargets)
                .as("★スペル・ウェポンの宣言時対象は残っている(消したのはミニオンだけ)")
                .isGreaterThan(10);
    }

    /**
     * ★<b>誘発の側の番人。</b>対象を要求するミニオンは
     * {@code targetSpecs} に<b>1件だけ</b>要求を持ち、
     * 【召喚時】と【登場時】を<b>同時には持たない</b>。
     *
     * <p>なぜこの2つを固定するのか ——
     * <ul>
     * <li><b>要求が1件</b>: {@code PendingChoice} は1つの要求しか運べない。
     *     2件目を足すと、割り込みは1件目しか問わず<b>2件目が黙って消える</b>。
     *     2つ問いたいカードは、本文の順序どおりに別の再開先を持たせる
     *     (《生贄を求める邪鬼》《手札を喰らう大蟹》がその形である)。</li>
     * <li><b>両方を持たない</b>: {@code targetSpecs} はカードに1本しか無いので、
     *     両方が焚かれると<b>同じ対象を2度問う</b>。</li>
     * </ul>
     */
    @Test
    void 対象を要求するミニオンは要求1件で召喚時と登場時を同時に持たない() {
        int checked = 0;
        for (Civilization civ : Civilization.values()) {
            for (CardMaster m : cards.findByCivilization(civ)) {
                if (!m.type().isMinion()
                        || effects.targetSpecOf(m.id()).requirements().isEmpty()) {
                    continue;
                }
                checked++;
                assertThat(effects.targetSpecOf(m.id()).requirements())
                        .as("★【%s】の要求は1件でなければならない".formatted(m.name()))
                        .hasSize(1);
                boolean onSummon = effects.hasTrigger(m.id(), TriggerType.ON_SUMMON);
                boolean onEnter = effects.hasTrigger(m.id(), TriggerType.ON_ENTER);
                assertThat(onSummon && onEnter)
                        .as("★【%s】は【召喚時】と【登場時】を同時に持ってはいけない".formatted(m.name()))
                        .isFalse();
                assertThat(onSummon || onEnter)
                        .as("★【%s】の対象は【召喚時】か【登場時】のものである".formatted(m.name()))
                        .isTrue();
            }
        }
        assertThat(checked).as("★対象を要求するミニオンは15枚である(空振りでないことの証拠)")
                .isEqualTo(15);
    }

    // ==================================================================
    // 2. 誘発の種別で絞る —— 【破壊時】まで巻き込まない
    // ==================================================================

    /**
     * ★★★<b>68 の実装で実際に踏んだ穴である。</b>
     *
     * <p>{@code targetSpecs} はカードに1本しか無く、そこに書いてあるのは
     * 【召喚時】【登場時】の対象である。{@code fire()} の入口で
     * <b>誘発の種別を見ずに</b>「まだ対象を選んでいない」と判定すると、
     * 同じカードの【破壊時】や【攻撃時】まで問い合わせに化ける。
     *
     * <p>《サモンズライト》は【召喚時】(相手1体に1ダメージ)と
     * 【破壊時】(墓地からコスト1を出す)の両方を持つ ——
     * 絞りを忘れると、破壊されたときに<b>蘇生の代わりにダメージ対象を問われる</b>。
     */
    @Test
    void 破壊時の誘発は召喚時の対象を問わない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 1);
        f.me().getTrash().add(SKY_SWALLOW); // コスト1 = サモンズライトの蘇生先
        MinionInstance summonsLight = f.putOnField(f.me(), "QTE-M-DARK-34");

        // マグマ・ストレート(3ダメージ)で破壊する
        game.playCard(f.room(), "me", f.giveHand(f.me(), MAGMA),
                List.of(new com.example.qte.effect.TargetChoice(
                        null, List.of(summonsLight.getInstanceId()), null, null, null)), false);

        assertThat(f.me().getPendingChoice())
                .as("★【破壊時】は蘇生先を問う(候補1件なので自動で決まる)。"
                        + "【召喚時】のダメージ対象を問うてはいけない")
                .isNull();
        assertThat(f.fieldIds(f.me()))
                .as("★【破壊時】の蘇生が実際に起きている(空振りでないことの証拠)")
                .containsExactly(SKY_SWALLOW);
    }

    // ==================================================================
    // 3. 候補の作られ方(裁定302・裁定305)
    // ==================================================================

    /** 候補が0件なら問い合わせは立たない —— 成立しない選択肢は並べない(裁定302) */
    @Test
    void 候補が0件なら問い合わせを立てない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);

        game.playCard(f.room(), "me", f.giveHand(f.me(), ROT_THROWER), List.of(), false);

        assertThat(f.me().getPendingChoice()).as("相手の場が空なので問わない").isNull();
        assertThat(f.fieldIds(f.me())).as("★召喚そのものは通る(48 の落とし穴)")
                .containsExactly(ROT_THROWER);
    }

    /**
     * ★★<b>選ぶ余地が本当に無いときだけ自動で決める</b>(12b・51 からの流儀)。
     * <b>必須</b>({@code upTo} でも {@code optional} でもない)かつ候補が1件なら、
     * 問わずに決めてよい。
     *
     * <p>★逆に言えば、<b>{@code upTo} や {@code optional} の要求は候補が1件でも問う</b> ——
     * 「選ばない」も選択肢だからである(裁定302 の裏返し)。
     * 15枚のうち必須なのは《手札を喰らう大蟹》の「捨てる1枚」だけである。
     */
    @Test
    void 必須で候補が1件なら自動で決まる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        f.giveHand(f.me(), SKY_SWALLOW); // ★捨てる候補はこの1枚だけ
        f.putOnField(f.you(), LIGHT_SHIELD);

        game.playCard(f.room(), "me", f.giveHand(f.me(), CRAB), List.of(), false);

        assertThat(f.me().getTrash())
                .as("★捨てる1枚は選ぶ余地が無いので問わずに決まった").containsExactly(SKY_SWALLOW);
        assertThat(f.me().getPendingChoice().kind())
                .as("★問い合わせは2文目(戻すミニオン)へ進んでいる")
                .isEqualTo(com.example.qte.effect.PendingChoice.Kind.MINION);
    }

    /**
     * ★<b>任意の要求は候補が1件でも問う</b>(裁定302 の裏返し)。
     * 《腐敗の投擲者》の対象は {@code optional} である ——
     * 候補が0体でも召喚できるようにするための指定だが、
     * その結果「1体しか居なくても、与えないことを選べる」という形になる。
     */
    @Test
    void 任意の要求は候補が1件でも問い合わせる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        MinionInstance victim = f.putOnField(f.you(), LIGHT_SHIELD);

        game.playCard(f.room(), "me", f.giveHand(f.me(), ROT_THROWER), List.of(), false);

        assertThat(f.me().getPendingChoice()).as("★任意なので問う").isNotNull();
        f.answerChoiceNone(game, "me");
        assertThat(victim.getDamage()).as("選ばなければ何も起きない").isZero();
    }

    /**
     * ★★★<b>自分自身が候補に入る</b>(裁定305(b-1))。
     * 66 までは構造的にありえなかった —— 対象を選ぶのが場に出る<b>前</b>だったからである。
     */
    @Test
    void 召喚時の対象には自分自身も含まれる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);

        game.playCard(f.room(), "me", f.giveHand(f.me(), SHADOW_ASSASSIN), List.of(), false);

        MinionInstance self = f.me().getMinionZone().get(0);
        assertThat(f.me().getPendingChoice().candidates())
                .as("★他に誰も居なくても、自分自身が候補として現れる")
                .containsExactly(self.getInstanceId());

        f.answerChoice(game, "me", self.getInstanceId());

        assertThat(self.getDamage())
                .as("★自分自身に3ダメージが入る(側の限定が無い『ミニオン1体』。裁定156(2))")
                .isEqualTo(3);
    }

    // ==================================================================
    // 4. 2つの文を順に問う(《手札を喰らう大蟹》)
    // ==================================================================

    /**
     * 本文は「自分の手札を1枚捨てる。<b>そうしたら</b>ミニオン1体を持ち主の手札に戻す。」
     * ★2件の要求を宣言時にまとめて選ばせる形では「そうしたら」を表現できない ——
     * 捨てが成立したかは<b>捨てたあと</b>にしか分からないからである。
     */
    @Test
    void 大蟹は捨ててから戻すミニオンを問う() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        f.giveHand(f.me(), SKY_SWALLOW); // 捨てる候補
        f.giveHand(f.me(), MAGMA);       // ★2枚目を持たせて「選ぶ余地」を作る
        MinionInstance theirs = f.putOnField(f.you(), LIGHT_SHIELD);

        game.playCard(f.room(), "me", f.giveHand(f.me(), CRAB), List.of(), false);
        // 1文目: 捨てる手札。★大蟹自身は場に出ているので手札には居ない
        f.answerChoice(game, "me", f.handPosition(f.me(), SKY_SWALLOW));

        assertThat(f.me().getTrash()).as("捨てが成立した").contains(SKY_SWALLOW);
        // 2文目: 戻すミニオン。候補は大蟹自身と相手の1体 = 2件なので問い合わせが立つ
        assertThat(f.me().getPendingChoice()).as("★「そうしたら」の2文目を問う").isNotNull();
        f.answerChoice(game, "me", theirs.getInstanceId());

        assertThat(f.you().getMinionZone()).as("相手のミニオンが場を離れた").isEmpty();
        assertThat(f.you().getHand()).as("持ち主の手札に戻る").contains(LIGHT_SHIELD);
    }

    /**
     * ★<b>「そうしたら」の否定側</b>(裁定181)。手札が空なら1文目の候補が0件になり、
     * 2文目のバウンスは<b>起きない</b>。
     */
    @Test
    void 大蟹は捨てる手札が無ければ何も戻さない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        MinionInstance theirs = f.putOnField(f.you(), LIGHT_SHIELD);

        int hand = f.giveHand(f.me(), CRAB);
        game.playCard(f.room(), "me", hand, List.of(), false); // ★手札は大蟹1枚だけだった

        assertThat(f.me().getPendingChoice()).as("捨てる手札が無いので問わない").isNull();
        assertThat(f.you().getMinionZone())
                .as("★「そうしたら」なので、捨てられなければ戻さない").containsExactly(theirs);
    }

    // ==================================================================
    // 5. ウェポンの割り込み(★Batch 68 で PendingChoice.Kind に足した)
    // ==================================================================

    /**
     * ★<b>候補には「どちらの側か」が添えられる。</b>
     * ウェポンは1人1つなのでミニオンのような instanceId を持たず、
     * 候補は {@code "SELF"} / {@code "OPPONENT"} という側の名前である ——
     * 同名のウェポンを両者が装備していることは普通に起きるので、
     * 名前だけを出すと押し間違える。
     */
    @Test
    void ウェポンの割り込み候補にはどちらの側かが添えられる() {
        AutoGameFixture f = newGame();
        f.me().setEquippedWeapon(cards.findById(WEAPON));
        f.you().setEquippedWeapon(cards.findById(WEAPON)); // ★同名を両者が装備している
        payMana(f.me(), 9);

        game.playCard(f.room(), "me", f.giveHand(f.me(), ZODIAC), List.of(), false);

        var choice = views.build(f.room(), "me").you().pendingChoice();
        assertThat(choice.kind()).isEqualTo("WEAPON");
        assertThat(choice.candidates()).hasSize(1);
        assertThat(choice.candidates().get(0).label())
                .as("★カード名と側の両方が出る")
                .contains(cards.findById(WEAPON).name()).contains("相手");
    }

    // ==================================================================
    // 6. 裁定306・307・309(68 に相乗りした3件)
    // ==================================================================

    /**
     * 裁定306: 《海皇 ポセイドン》の「自分のメインフェーズ開始時」に
     * フェイズの検査が付いた。
     *
     * <p>★67 までは「このターンまだカードを使っていない」しか見ておらず、
     * 隣の《静寂の瞑想》だけが {@code phase == MAIN} を持っていた ——
     * <b>同じ語彙に対して実装が2通りあった</b>(裁定130)。
     */
    @Test
    void ポセイドンはメインフェイズ以外では特殊召喚できない() {
        AutoGameFixture f = newGame();
        for (int i = 0; i < 8; i++) {
            f.giveHand(f.me(), MAGMA); // 手札7枚以上の条件を満たす
        }
        int poseidon = f.giveHand(f.me(), POSEIDON);

        assertThat(effects.specialSummonOf(POSEIDON).condition()
                .test(f.state(), f.me(), poseidon))
                .as("メインフェイズなら条件を満たす").isTrue();

        f.state().setPhase(TurnPhase.BATTLE);
        assertThat(effects.specialSummonOf(POSEIDON).condition()
                .test(f.state(), f.me(), poseidon))
                .as("★裁定306: バトルフェイズでは満たさない").isFalse();
    }

    /**
     * 裁定307: 《海淵獣ゾクシム》の「カードを2枚引く」は誘発の印を持たない。
     * 印を持たない効果の既定は<b>【登場時】</b>である ——
     * 67 の時点で《光の召喚士》は ON_ENTER、このカードは ON_SUMMON と割れていた。
     */
    @Test
    void ゾクシムのドローは登場時である() {
        assertThat(effects.hasTrigger(ZOKUSHIMU, TriggerType.ON_ENTER))
                .as("★裁定307: 印の無い効果は【登場時】").isTrue();
        assertThat(effects.hasTrigger(ZOKUSHIMU, TriggerType.ON_SUMMON))
                .as("★【召喚時】には登録しない(67 まではこちらだった)").isFalse();
    }

    /**
     * 裁定309: 《サイクロン・リフレッシュ》の「場」に<b>装備中のウェポン</b>も含める。
     * 本文「場か手札のカードを2枚デッキに戻してシャッフルする」の「場」は
     * ミニオンゾーンだけでなくウェポンゾーンも指す(総合ルール2-2)。
     */
    @Test
    void サイクロンリフレッシュはウェポンもデッキに戻せる() {
        AutoGameFixture f = newGame();
        f.me().setEquippedWeapon(cards.findById(WEAPON));
        payMana(f.me(), f.card("QTE-M-WIND-22").cost());
        int deckBefore = f.me().getDeck().size();

        // ★合計2枚。ウェポン1枚 + 手札1枚 という組み合わせが通ることを測る
        f.giveHand(f.me(), SKY_SWALLOW);
        int cyclone = f.giveHand(f.me(), "QTE-M-WIND-22");
        int swallow = f.me().getHand().indexOf(SKY_SWALLOW);
        game.playCard(f.room(), "me", cyclone,
                List.of(new com.example.qte.effect.TargetChoice(
                                List.of(swallow), null, null, null, null),
                        new com.example.qte.effect.TargetChoice(null, List.of(), null, null, null),
                        new com.example.qte.effect.TargetChoice(null, null, null, null,
                                List.of("SELF"))),
                false);

        assertThat(f.me().getEquippedWeapon()).as("★装備中のウェポンが場を離れた").isNull();
        assertThat(f.me().getDeck()).as("山札へ戻っている").contains(WEAPON);
        assertThat(f.me().getDeck().size())
                .as("戻した2枚 + 引いた2枚 = 差し引き変わらない").isEqualTo(deckBefore + 2 - 2);
    }

    /**
     * ★<b>要求の並びは本文の並びである</b>(裁定309)。
     * ウェポンは3件目に足した —— 手札・ミニオンの位置を動かすと、
     * 既に届いている選択の対応がずれる。
     */
    @Test
    void サイクロンリフレッシュの要求は手札ミニオンウェポンの順である() {
        TargetSpec spec = effects.targetSpecOf("QTE-M-WIND-22");
        assertThat(spec.requirements()).hasSize(3);
        assertThat(spec.requirements().get(0).kind()).isEqualTo(TargetSpec.Kind.HAND);
        assertThat(spec.requirements().get(1).kind()).isEqualTo(TargetSpec.Kind.MINION);
        assertThat(spec.requirements().get(2).kind())
                .as("★裁定309 で足したのは3件目である").isEqualTo(TargetSpec.Kind.WEAPON);
        assertThat(spec.requirements().get(2).side())
                .as("★自分のウェポンだけ(本文が「自分の」を含む文脈である)")
                .isEqualTo(TargetSpec.Side.SELF);
    }

    /** 進化ミニオンは {@code CardType.isMinion()} が真である(★Batch 67 で1箇所に集めた判定) */
    @Test
    void 進化ミニオンもミニオンとして数える() {
        assertThat(CardType.EVOLUTION.isMinion()).isTrue();
        assertThat(CardType.MINION.isMinion()).isTrue();
        assertThat(CardType.SPELL.isMinion()).isFalse();
        assertThat(CardType.WEAPON.isMinion()).isFalse();
    }
}
