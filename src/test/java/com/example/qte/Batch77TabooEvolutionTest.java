package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.game.GameService;
import com.example.qte.game.ManaCard;
import com.example.qte.game.MinionInstance;
import com.example.qte.game.PlayerState;
import com.example.qte.game.view.CardView;
import com.example.qte.game.view.GameViewBuilder;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.support.AutoGameFixture;

/**
 * 禁忌デッキからの進化召喚(★Batch 77)。
 *
 * <h2>★★★発端 —— マスターの実機確認(候補 L)</h2>
 *
 * 「禁忌デッキからゾクシムを進化させようとしたら、自分のミニオンを選択できなくて
 * 進化できませんでした。」
 *
 * <p>★<b>サーバは1文字も悪くなかった。</b>直したのは {@code battle.js} だけである ——
 * 禁忌の2つの入口(クリック・ドラッグ)が<b>素材を問う段を通っていなかった</b>。
 *
 * <h2>★★★なぜ既存の938件が1件も落ちなかったのか</h2>
 *
 * {@code EvolutionEngineTest#禁忌デッキの進化も素材を使って場に出せる} は
 * <b>Batch 52 から在って、ずっと緑だった</b>。あの試験は
 * {@code game.playTabooCard(..., List.of(material.getInstanceId()))} と
 * <b>素材を手で渡して</b>呼んでいる ——
 * <b>本物の入口(クライアント)を1度も通っていない</b>ので、
 * クライアントが素材を1度も送らなくても緑のままだった。
 *
 * <p>★<b>72 の教訓「番人は実際の入口から起こす」の再演である</b>
 * (75 も接続の記録で同じ穴を踏んだ)。
 * ★★このバッチの本体の番人は <b>{@code verify/verify.js}</b> にある ——
 * 「素材を選ぶ導線が出るか」「{@code materialIds} が実際に飛ぶか」は
 * <b>クライアントを動かさないと測れない</b>(設計判断45: 番人は回る場所で選ぶ)。
 *
 * <h2>ここ(JUnit)で測るもの</h2>
 *
 * ★<b>クライアントが読む材料が、そもそも届いているか</b>である ——
 * 76 の教訓「『見えない』と言われたら、まず届いているかを見る」の裏返しで、
 * <b>届いていることを測る番人が1つも無かった</b>。
 *
 * <ul>
 * <li>{@code GameViewBuilder.buildCardView} は <b>Batch 52 から</b>
 *     禁忌の面にも {@code evolutionMaterialIds} を添えている
 *     (398行のコメントが「禁忌デッキのカードにも添える」と明記している)。
 *     ★<b>それを測る試験は 76 まで0件だった</b> ——
 *     だから誰も「届いているのに読んでいない」に気づけなかった。</li>
 * <li>素材が空の {@code play-taboo} をサーバが断ること ——
 *     <b>クライアントが送らなければ必ず失敗する</b>ことの裏取りである(裁定181)。</li>
 * </ul>
 */
@SpringBootTest
class Batch77TabooEvolutionTest {

    /** 常在効果を持たないリーダー(既定) */
    private static final String PLAIN_LEADER = "QTE-M-WATER-1";

    /**
     * ★マスターが実機で踏んだカードそのものである。
     * 《海淵獣ゾクシム》コスト3・【進化】(水文明ではないミニオン1体)。
     */
    private static final String ZOKUSHIMU = "QTE-M-WATER-32";

    /** コスト1・2/1・火文明(フレア・ポーン)。水文明ではない = ゾクシムの素材になる */
    private static final String FIRE_MINION = "QTE-M-FIRE-2";

    /** コスト1・1/1・水文明(アクア・ジェリー)。★水文明なのでゾクシムの素材にできない側 */
    private static final String WATER_MINION = "QTE-M-WATER-2";

    /**
     * ★★★《愚乱怒土地(グランドランド)》—— <b>進化かつ【賢魂：3】</b>である。
     * 235枚のうち【賢魂】を持つ7枚のうち<b>4枚が進化</b>であり(72 の「多数派が穴に落ちる」)、
     * 禁忌に入れたときの「賢魂として使う道」と「進化として使う道」の分かれ目そのものである。
     *
     * <p>★4枚のうち<b>賢魂の側が対象を1つも要求しない</b>のはこれだけである ——
     * 《黒ノ霊導者》と《白ノ霊知者》の賢魂はミニオンを対象に取り、
     * 《英霊・タイガラム》は素材が「守護を持つ光文明」で盤面を整える手間が要る。
     * ★★<b>測りたいのは「素材を要求しないこと」なので、
     * 他の理由で断られる余地を先に消してある</b>(75 の「照合先が別の値と比べていた」の予防)。
     */
    private static final String GURANDO = "QTE-M-EARTH-30";

    /** コスト3・土文明(ゴーレム・ウォール)。★《愚乱怒土地》の素材になる */
    private static final String EARTH_MINION = "QTE-M-EARTH-2";

    /** マナに積む1枚(マグマ・ストレート)。禁忌はマナの枚数で払う */
    private static final String MAGMA = "QTE-M-FIRE-10";

    @Autowired
    private GameService game;

    @Autowired
    private CardMasterRepository cards;

    @Autowired
    private GameViewBuilder views;

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

    /** 禁忌デッキの面(所有者の視点)。★相手の視点では null なのでここでは引けない */
    private CardView tabooFace(AutoGameFixture f, int index) {
        return views.build(f.room(), "me").you().taboo().get(index);
    }

    // ==================================================================
    // 1. ★★★材料は届いている(Batch 52 から。番人はここが初めてである)
    // ==================================================================

    /**
     * ★★★<b>これが 77 の中心の番人である。</b>
     *
     * <p>クライアントは素材の候補を1つも計算しない(裁定163・234)——
     * {@code CardView.evolutionMaterialIds} が届けたものからしか選ばない。
     * したがって<b>禁忌の面にそれが載っていること</b>が、
     * 「禁忌から進化を出せる」の前提そのものである。
     *
     * <p>★<b>載っていたのに読まれていなかった</b>のが 77 の不具合である。
     * ここが赤くなったら、直すのはクライアントではなく {@code GameViewBuilder} の側である。
     */
    @Test
    void 禁忌デッキの面にも進化素材の候補が載る() {
        AutoGameFixture f = newGame();
        MinionInstance material = f.putOnField(f.me(), FIRE_MINION);
        f.me().getTabooDeck().add(ZOKUSHIMU);

        CardView face = tabooFace(f, 0);
        assertThat(face.type()).isEqualTo("EVOLUTION");
        assertThat(face.evolutionMaterialIds())
                .as("禁忌の面にも「今この瞬間、素材にできるミニオン」が載る(52 の buildCardView)")
                .containsExactly(material.getInstanceId());
        assertThat(face.evolutionMin()).isEqualTo(1);
        assertThat(face.evolutionMax()).isEqualTo(1);
        assertThat(face.evolutionText())
                .as("素材の条件文。画面はこれを案内に出す")
                .isNotNull();
    }

    /**
     * ★<b>「そうでない側」を並べて置く</b>(裁定181)。
     * 候補を無条件に「自分の場のミニオン全部」にしている実装でも上の試験は通るので、
     * <b>条件に合わないミニオンが落ちること</b>を同じ形で測る。
     */
    @Test
    void 禁忌デッキの面の候補は素材条件で絞られている() {
        AutoGameFixture f = newGame();
        MinionInstance ok = f.putOnField(f.me(), FIRE_MINION);      // 水文明ではない = 素材になる
        f.putOnField(f.me(), WATER_MINION);                          // 水文明 = ならない
        f.me().getTabooDeck().add(ZOKUSHIMU);

        assertThat(tabooFace(f, 0).evolutionMaterialIds())
                .containsExactly(ok.getInstanceId());
    }

    /**
     * ★素材が1体も居なければ候補は空である。
     * ★★<b>画面はこれを見て「進化素材が足りません」を出す</b>
     * ({@code beginEvolutionSelection} が始めずに戻る側)。
     */
    @Test
    void 素材が場に居なければ禁忌の面の候補は空である() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), WATER_MINION);   // 水文明しか居ない
        f.me().getTabooDeck().add(ZOKUSHIMU);

        assertThat(tabooFace(f, 0).evolutionMaterialIds()).isEmpty();
    }

    /**
     * ★★★<b>進化かつ【賢魂】</b>のカードは、禁忌の面に<b>両方の姿</b>を載せる。
     *
     * <p>クライアントはこの2つを見て道を分ける ——
     * スペル枠へ落とせば {@code play-taboo-soul}(素材を取らない)、
     * 場へ落とせば {@code play-taboo}(素材を取る)。
     * ★<b>片方しか載っていなければ、その分岐は書きようがない。</b>
     */
    @Test
    void 進化かつ賢魂の禁忌は素材の候補と賢魂のコストを両方載せる() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), EARTH_MINION);
        f.me().getTabooDeck().add(GURANDO);

        CardView face = tabooFace(f, 0);
        assertThat(face.type()).isEqualTo("EVOLUTION");
        assertThat(face.soulCost()).as("【賢魂：n】としての姿").isNotNull();
        assertThat(face.evolutionMaterialIds()).as("進化としての姿").isNotEmpty();
    }

    // ==================================================================
    // 2. ★サーバは素材の無い禁忌の進化召喚を断る(裁定181 の裏取り)
    // ==================================================================

    /**
     * ★★★<b>クライアントが素材を送らなければ、必ずここで落ちる。</b>
     *
     * <p>これがマスターの実機で起きていたことそのものである ——
     * 76 までの {@code battle.js} は禁忌の進化で {@code materialIds} を1度も送らず、
     * サーバがこの例外で断っていた。
     * <b>画面には素材を選ぶ導線が1つも無い</b>ので、遊ぶ人には
     * 「自分のミニオンを選択できなくて進化できない」としか見えなかった。
     *
     * <p>★<b>この番人は「直っていないと落ちる」側ではない</b>(サーバは元から正しい)——
     * <b>クライアントを直す理由が消えていないこと</b>を測る番人である。
     * ここが緑でなくなったら、それはサーバが素材を要求しなくなった日であり、
     * <b>77 のクライアント側の分岐は不要になる</b>。
     */
    @Test
    void 素材を送らない禁忌の進化召喚はサーバが断る() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        f.putOnField(f.me(), FIRE_MINION);
        f.me().getTabooDeck().add(ZOKUSHIMU);

        assertThatThrownBy(() -> game.playTabooCard(f.room(), "me", 0,
                List.of(0, 1, 2), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("進化素材");
    }

    /**
     * ★<b>そうでない側</b>(裁定181): 素材を1体添えれば通る。
     * ★★これは {@code EvolutionEngineTest} が 52 から測っているものと同じ性質だが、
     * <b>77 が直した経路が最後にたどり着く先</b>なので、ここにも1件置いてある ——
     * 上の「断る」だけだと、<b>常に断る</b>実装でも緑になる。
     */
    @Test
    void 素材を1体添えれば禁忌の進化召喚は通る() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        MinionInstance material = f.putOnField(f.me(), FIRE_MINION);
        f.me().getTabooDeck().add(ZOKUSHIMU);

        assertThatCode(() -> game.playTabooCard(f.room(), "me", 0,
                List.of(0, 1, 2), List.of(), List.of(material.getInstanceId())))
                .doesNotThrowAnyException();

        assertThat(f.fieldIds(f.me()))
                .as("ゾクシムが場に出て、素材はその下に潜っている")
                .containsExactly(ZOKUSHIMU);
        assertThat(f.me().getMinionZone().get(0).getUnder())
                .extracting(com.example.qte.game.StackedCard::cardId)
                .containsExactly(FIRE_MINION);
    }

    /**
     * ★★素材にできないミニオンを指定したら断る。
     * ★<b>候補を作る述語と、断る述語が同じ1本であること</b>の裏取りである(裁定130)——
     * ずれていると「画面では選べるのにサーバが断る」が生まれる。
     */
    @Test
    void 候補に載っていないミニオンを素材に指定すると断る() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        MinionInstance water = f.putOnField(f.me(), WATER_MINION);
        f.me().getTabooDeck().add(ZOKUSHIMU);

        assertThat(tabooFace(f, 0).evolutionMaterialIds())
                .as("画面の候補には載らない")
                .doesNotContain(water.getInstanceId());
        assertThatThrownBy(() -> game.playTabooCard(f.room(), "me", 0,
                List.of(0, 1, 2), List.of(), List.of(water.getInstanceId())))
                .as("サーバも同じ述語で断る")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================================================================
    // 3. ★賢魂の道は素材を取らない(掛ける場所を広く取らない・72 の教訓・幅)
    // ==================================================================

    /**
     * ★★★<b>賢魂として使う道に素材は要らない。</b>
     *
     * <p>【賢魂】はミニオンを<b>スペルとして</b>使う2つ目の姿であり(裁定152)、
     * 場には出ない —— 進化の素材を取りようがない。
     * ★{@code playTabooSoulCard} は {@code materialIds} を<b>引数に持たない</b>ので、
     * この性質は<b>型で保証されている</b>。
     *
     * <p>★★クライアント側は {@code action === 'play-taboo'} と
     * {@code !asSoul} で道を分けており、そちらは verify が見張る ——
     * <b>ここで測れるのは「サーバが素材を要求しないこと」だけである</b>
     * (74 の《聖光の守護聖》と同じで、本物の入口がこちら側に無い)。
     */
    @Test
    void 進化かつ賢魂の禁忌を賢魂として使うと素材を1体も要求しない() {
        AutoGameFixture f = newGame();
        // ★退けるマナは<b>賢魂のコスト n = 3 枚</b>である(裁定 A6)。印刷コスト6ではない
        payMana(f.me(), 3);
        f.me().getTabooDeck().add(GURANDO);
        // ★場は空である —— 素材が1体も居ないのに通ることが、要求していないことの証拠になる
        assertThat(f.me().getMinionZone()).isEmpty();

        assertThatCode(() -> game.playTabooSoulCard(f.room(), "me", 0,
                List.of(0, 1, 2), List.of()))
                .doesNotThrowAnyException();
    }
}
