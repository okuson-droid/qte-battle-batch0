package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.game.GameService;
import com.example.qte.game.ManaCard;
import com.example.qte.game.PlayerState;
import com.example.qte.game.view.GameView;
import com.example.qte.game.view.GameViewBuilder;
import com.example.qte.game.view.PlayerView;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.support.AutoGameFixture;

/**
 * Batch 81: 一時公開ゾーンの公開範囲(裁定359・360)。
 *
 * <h2>何を守る試験か</h2>
 *
 * {@code PlayerState.revealedZone} は<b>器が1つなのに意味が2つある</b>。
 *
 * <ul>
 * <li>《降臨の伝道師》(QTE-M-LIGHT-22): 「山札の上から4枚を<b>公開</b>」——
 *     相手も観戦者も見てよい。</li>
 * <li>《愚乱怒土地》(QTE-M-EARTH-30): 「山札の上から2枚<b>見て</b>…
 *     <b>相手に見せず</b>加える」—— 相手に見せてはいけない。</li>
 * </ul>
 *
 * <p>★★★80 まで {@code GameViewBuilder} は {@code isSelf} を1度も通しておらず、
 * <b>見た2枚が相手にも観戦者にも名前つきで届いていた</b>。
 * ★<b>実害が出ていなかったのは、{@code battle.js} がこの欄を1度も読んでいなかったからにすぎない</b> ——
 * 81 が描くようになった時点で漏れる穴だった。
 *
 * <h2>★★★なぜ既存の954件が1件も赤くならなかったのか</h2>
 *
 * <b>誰もこの欄を測っていなかったからである</b>(80 の作業前で JUnit 1件・verify 0件)。
 * ★67(本文を7枚直しても826件が緑)・70(払い方の順序を入れ替えても758件が緑)・
 * 80(同一性を77箇所で落としても951件が緑)と<b>まったく同じ一族</b>である。
 * ★★<b>81 で直したときも、954件は1件も赤くならなかった</b> ——
 * だからこの試験が要る。
 *
 * <h2>測り方の方針</h2>
 *
 * <ul>
 * <li>効果は<b>本物の入口</b>から起こす(裁定187)—— {@code playCard} / {@code playSoulCard}。</li>
 * <li>見え方は<b>本物のビルダー</b>から読む —— {@code GameViewBuilder.build} の
 *     3つの視点(本人・相手・観戦者)を並べる。
 *     ★<b>「そうでない側」も測る</b>(裁定181): 本人に見えることと、
 *     相手に見えないことは<b>別の主張</b>である。</li>
 * <li>★★★<b>配信を跨げない場面はログで測る</b>(裁定360)——
 *     通常モードは効果の解決中に配信を1度も行わない
 *     ({@code GameWsController} の {@code action.apply} → {@code broadcast} は1往復)ので、
 *     <b>公開領域へ入れて同じ配信の中で取り出す場面は誰にも観測できない</b>。
 *     そこを埋めているのがログである。</li>
 * </ul>
 */
@SpringBootTest
class Batch81RevealedZoneTest {

    /** 常在効果を持たないリーダー */
    private static final String PLAIN_LEADER = "QTE-M-WATER-1";

    /** 降臨の伝道師(光)。【召喚時】山札の上から4枚を<b>公開</b> */
    private static final String MISSIONARY = "QTE-M-LIGHT-22";
    /** 光の【守護】ミニオン。★4枚のうち2枚をこれにすると、選択待ちで配信を跨ぐ */
    private static final String LIGHT_GUARD = "QTE-M-LIGHT-2";
    /** 【守護】を持たない光のミニオン(公開の束の埋め草) */
    private static final String PLAIN_LIGHT = "QTE-M-LIGHT-13";
    /** 光霊・ネフラ(光)。【召喚時】山札の上から3枚を<b>表向きにする</b> = これも公開である */
    private static final String NEPHRA = "QTE-M-LIGHT-35";

    /** 愚乱怒土地(土・進化)。【賢魂：3】山札の上から2枚<b>見て</b>…相手に見せず加える */
    private static final String GURANDORANDO = "QTE-M-EARTH-30";
    /** マナの中身(数え上げに紛れないスペル) */
    private static final String MAGMA = "QTE-M-FIRE-10";

    @Autowired
    private CardMasterRepository cards;

    @Autowired
    private GameService game;

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

    /** 本人の視点で見た、自分の一時公開ゾーン */
    private List<PlayerView.RevealedCardView> mineAsSelf(AutoGameFixture f) {
        return views.build(f.room(), "me").you().revealedCards();
    }

    /** ★相手の視点で見た、<b>わたしの</b>一時公開ゾーン(ここが漏れていた) */
    private List<PlayerView.RevealedCardView> mineAsOpponent(AutoGameFixture f) {
        return views.build(f.room(), "you").opponent().revealedCards();
    }

    /** ★観戦者の視点。★★<b>席Aが you、席Bが opponent</b> であり、me は席Aである */
    private List<PlayerView.RevealedCardView> mineAsSpectator(AutoGameFixture f) {
        GameView v = views.build(f.room(), "watcher");
        return v.you().revealedCards();
    }

    /**
     * 《降臨の伝道師》を、公開した4枚のうち<b>【守護】が2枚</b>になるように使う。
     * ★2枚以上でないと選択待ちにならず、<b>配信を跨がない</b>(0-4)。
     */
    private void playMissionaryWithTwoGuards(AutoGameFixture f) {
        payMana(f.me(), 15);
        f.me().getDeck().clear();
        f.stackDeck(f.me(), LIGHT_GUARD, PLAIN_LIGHT, LIGHT_GUARD, PLAIN_LIGHT);
        f.fillDeck(f.me(), 20);
        int hand = f.giveHand(f.me(), MISSIONARY);
        game.playCard(f.room(), "me", hand, List.of(), false);
    }

    // ==================================================================
    // 1. ★★★公開(《降臨の伝道師》)—— 相手にも観戦者にも見えてよい
    // ==================================================================

    @Test
    @DisplayName("★★★公開した束は、本人にも相手にも観戦者にも中身が届く(裁定359)")
    void 公開した束は相手にも観戦者にも届く() {
        AutoGameFixture f = newGame();
        playMissionaryWithTwoGuards(f);

        assertThat(f.me().getPendingChoice())
                .as("★【守護】が2枚あるので選択待ちになる —— ここでだけ配信を跨ぐ")
                .isNotNull();
        assertThat(f.me().isRevealedPublic())
                .as("★★本文は「公開」である(裁定359)")
                .isTrue();

        assertThat(mineAsSelf(f)).as("本人には当然見える").hasSize(4);
        assertThat(mineAsOpponent(f))
                .as("★★★相手にも見える —— 本文が「公開」だからである")
                .hasSize(4);
        assertThat(mineAsSpectator(f))
                .as("★観戦者にも見える(公開情報に視点の差は無い)")
                .hasSize(4);
    }

    @Test
    @DisplayName("★★公開のビューはカードIDを運ぶ(裁定144: 面は card-library から引く)")
    void 公開のビューはカードIDを運ぶ() {
        AutoGameFixture f = newGame();
        playMissionaryWithTwoGuards(f);

        List<PlayerView.RevealedCardView> seen = mineAsSelf(f);
        assertThat(seen).extracting(PlayerView.RevealedCardView::cardId)
                .as("★<b>名前で引かせない</b>(名前はIDと同じ、の原則。Batch 44 がウェポンで決めた形)")
                .containsExactly(LIGHT_GUARD, PLAIN_LIGHT, LIGHT_GUARD, PLAIN_LIGHT);
        assertThat(seen).extracting(PlayerView.RevealedCardView::guard)
                .as("★【守護】かどうかも運ぶ(《降臨の伝道師》の表示補助)")
                .containsExactly(true, false, true, false);
        assertThat(seen).extracting(PlayerView.RevealedCardView::index)
                .as("★位置は束の中での並びである(選択の候補と同じ添字)")
                .containsExactly(0, 1, 2, 3);
    }

    // ==================================================================
    // 2. ★★★非公開(《愚乱怒土地》)—— <b>相手に見せてはいけない</b>
    // ==================================================================

    @Test
    @DisplayName("★★★「見た」束は本人にしか届かない —— 相手にも観戦者にも空で行く(裁定359)")
    void 見た束は本人にしか届かない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        f.stackDeck(f.me(), LIGHT_GUARD, PLAIN_LIGHT);
        game.playSoulCard(f.room(), "me", f.giveHand(f.me(), GURANDORANDO), List.of());

        assertThat(f.me().getPendingChoice())
                .as("★どちらをマナへ置くかの選択待ち —— ここで配信を跨ぐ")
                .isNotNull();
        assertThat(f.me().isRevealedPublic())
                .as("★★本文は「見て」であり「公開」ではない(裁定359)")
                .isFalse();

        assertThat(mineAsSelf(f))
                .as("★本人は見える —— 見たのは本人だからである")
                .hasSize(2);
        assertThat(mineAsOpponent(f))
                .as("★★★<b>これが 80 まで漏れていた。</b>本文は「相手に見せず」である")
                .isEmpty();
        assertThat(mineAsSpectator(f))
                .as("★★観戦者も相手と同じ扱いである(観戦者に「自分」は無い)")
                .isEmpty();
    }

    @Test
    @DisplayName("★★束を取り出すと、公開の旗も同時に降りる(出口は GameActions の1本である)")
    void 束を取り出すと公開の旗も降りる() {
        AutoGameFixture f = newGame();
        playMissionaryWithTwoGuards(f);
        assertThat(f.me().isRevealedPublic()).isTrue();

        game.resolveChoice(f.room(), "me", List.of(0));   // 1枚目の【守護】を場に出す

        assertThat(f.me().getRevealedZone())
                .as("★束は空になる")
                .isEmpty();
        assertThat(f.me().isRevealedPublic())
                .as("★★<b>旗も降りる</b> —— 置く口と取り出す口が1本ずつだから、書き忘れようがない")
                .isFalse();
        assertThat(mineAsOpponent(f))
                .as("★相手の画面からも消える")
                .isEmpty();
    }

    // ==================================================================
    // 3. ★★★ログ(裁定360)—— 配信を跨げない場面を埋めているのはここである
    // ==================================================================

    @Test
    @DisplayName("★★★公開のログにはカード名が並ぶ(裁定360)")
    void 公開のログにはカード名が並ぶ() {
        AutoGameFixture f = newGame();
        playMissionaryWithTwoGuards(f);

        String line = revealLine(f);
        assertThat(line).as("★「公開」と書く").contains("公開しました");
        assertThat(line)
                .as("★★★<b>名前を並べる</b> —— 相手も観戦者もこれで何が公開されたかを読む")
                .contains("【" + cards.findById(LIGHT_GUARD).name() + "】")
                .contains("【" + cards.findById(PLAIN_LIGHT).name() + "】");
    }

    @Test
    @DisplayName("★★★「見た」ときのログには名前を並べない。文言も「公開」ではない(裁定360)")
    void 見たときのログには名前を並べない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        f.stackDeck(f.me(), LIGHT_GUARD, PLAIN_LIGHT);
        game.playSoulCard(f.room(), "me", f.giveHand(f.me(), GURANDORANDO), List.of());

        String line = revealLine(f);
        assertThat(line)
                .as("★★本文は「見て」である —— 80 までここは「公開しました」と書いていた")
                .contains("見ました").doesNotContain("公開しました");
        assertThat(line)
                .as("★★★<b>名前は1つも書かない</b> —— ログは相手も観戦者も読む")
                .doesNotContain("【" + cards.findById(LIGHT_GUARD).name() + "】");
    }

    @Test
    @DisplayName("★★★【守護】が0枚でも、公開したことと中身はログに残る(裁定360・片肺の埋め)")
    void 守護が0枚でも公開の中身はログに残る() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 15);
        f.me().getDeck().clear();
        f.stackDeck(f.me(), PLAIN_LIGHT, PLAIN_LIGHT, PLAIN_LIGHT, PLAIN_LIGHT);
        f.fillDeck(f.me(), 20);
        game.playCard(f.room(), "me", f.giveHand(f.me(), MISSIONARY), List.of(), false);

        assertThat(f.me().getPendingChoice())
                .as("★【守護】が0枚なので選択待ちにならない —— <b>配信を跨がない</b>")
                .isNull();
        assertThat(f.me().getRevealedZone())
                .as("★★束は同じ配信の中で取り出されている(通っても、誰にも見えない)")
                .isEmpty();
        assertThat(revealLine(f))
                .as("★★★<b>だからログが唯一の証人である</b>(設計解説 0-4)")
                .contains("公開しました")
                .contains("【" + cards.findById(PLAIN_LIGHT).name() + "】");
    }

    /**
     * ★★★{@code revealFromTopOfDeck} の入口は<b>3つ</b>ある(裁定360)。
     *
     * <p>《降臨の伝道師》と《愚乱怒土地》は上で測っている。★<b>3つ目が《光霊・ネフラ》である</b> ——
     * 本文は「山札の上から3枚を<b>表向きにする</b>」であり、これも公開である。
     *
     * <p>★★<b>規則が n 入口ぶんあるなら、番人も n 入口ぶん要る</b>(77・79・80 の教訓)。
     * ★<b>この番人は壊し検証が要求した</b> —— 軸を「入口ごと」に立てたら、
     * 3つ目の入口に当てる先が無かった。
     *
     * <p>★★★<b>ここは公開領域を1度も通らない</b>(同じ配信の中で全部の行き先が決まる)。
     * <b>だからこそ、ログだけがこの公開の証人である</b>(設計解説 0-4)。
     */
    @Test
    @DisplayName("★★★《光霊・ネフラ》の「表向きにする」も公開である —— 名前がログに並ぶ(裁定360)")
    void 光霊ネフラの表向きも公開でありログに名前が並ぶ() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 15);
        f.me().getDeck().clear();
        f.stackDeck(f.me(), PLAIN_LIGHT, PLAIN_LIGHT, PLAIN_LIGHT);
        f.fillDeck(f.me(), 20);
        game.playCard(f.room(), "me", f.giveHand(f.me(), NEPHRA), List.of(), false);

        assertThat(f.me().getRevealedZone())
                .as("★公開領域は通らない —— 行き先が全部その場で決まるからである")
                .isEmpty();
        assertThat(revealLine(f))
                .as("★★★本文は「表向きにする」= 公開である。名前を並べる")
                .contains("公開しました")
                .contains("【" + cards.findById(PLAIN_LIGHT).name() + "】");
    }

    /** 「山札の上から…」の行。★<b>無ければ落とす</b>(黙って null を返さない) */
    private String revealLine(AutoGameFixture f) {
        return f.room().getLog().stream()
                .filter(l -> l.contains("山札の上から"))
                .reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("★山札の公開のログが1行も無い"));
    }
}
