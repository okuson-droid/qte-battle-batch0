package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.game.GameService;
import com.example.qte.game.ManaCard;
import com.example.qte.game.ManaPayment;
import com.example.qte.game.PlayerState;
import com.example.qte.game.view.GameViewBuilder;
import com.example.qte.game.view.PlayerView;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.support.AutoGameFixture;

/**
 * ★★★Batch 70 ①: マナの<b>払い方</b>(裁定315・316・317・319)。
 *
 * <h2>69 まで何が無かったか</h2>
 *
 * {@code GameService.payCost} は「マナゾーンの先頭から未タップのものを順にタップする」だけで、
 * <b>表裏も一時マナも1つも見ていなかった</b>。
 * ★<b>そしてその順序を測る試験は1件も無かった</b> ——
 * 70 で順序をまるごと入れ替えても、既存の758件は1件も落ちなかった。
 * 「落ちなかった = 変えていない」ではなく、<b>そもそも測られていなかった</b>のである
 * (67 で7枚のカードに起きたのと同じ形が、エンジンの側にも在った)。
 *
 * <h2>ここで測るもの</h2>
 *
 * <ol>
 *   <li><b>自動の支払いは {@link ManaPayment} の順のとおりである</b> ——
 *       ★期待する順序を書き写した試験にしない(裁定41)。
 *       「払う前に順序を採っておき、払ったあとタップされていたのがその先頭 n 枚か」で測る。
 *       こうすると、規則を変えたときに<b>試験のほうを書き換えて緑にできない</b>。</li>
 *   <li><b>順序そのものの性質</b>(一時マナが先・裏向きが表向きより先・禁忌は逆)——
 *       ★こちらは<b>裁定の文言そのもの</b>なので、値ではなく<b>並びの前後関係</b>で測る。</li>
 *   <li><b>人が選んだ支払いはそのとおりに払われ、通らない指定は弾かれる</b>(裁定319)。</li>
 *   <li><b>ビューが順序を載せている</b> —— クライアントの強調表示はこれだけを読む。</li>
 * </ol>
 */
@SpringBootTest
class Batch70ManaPaymentTest {

    /** 効果を持たない水のリーダー(盤面を汚さない) */
    private static final String PLAIN_LEADER = "QTE-M-WATER-1";
    /** コスト1のスペル(マグマ・ストレート)。マナの中身にも使う */
    private static final String MAGMA = "QTE-M-FIRE-10";
    /** コスト2・誘発も対象も持たないミニオン(ライト・シールド。【守護】だけ) */
    private static final String SHIELD = "QTE-M-LIGHT-2";
    /** コスト1・「効果なし。」のミニオン(フレア・ポーン)。禁忌デッキに入れて使う */
    private static final String PAWN = "QTE-M-FIRE-2";

    @Autowired
    private GameService game;

    @Autowired
    private GameViewBuilder views;

    @Autowired
    private CardMasterRepository cards;

    private AutoGameFixture newGame() {
        AutoGameFixture f = new AutoGameFixture(cards, PLAIN_LEADER, PLAIN_LEADER);
        f.fillDeck(f.me(), 40);
        f.fillDeck(f.you(), 40);
        return f;
    }

    /** 表向き・未タップのマナを1枚足す */
    private void faceUp(PlayerState p) {
        p.getManaZone().add(new ManaCard(MAGMA, false));
    }

    /** 裏向き・未タップのマナを1枚足す */
    private void faceDown(PlayerState p) {
        ManaCard mana = new ManaCard(MAGMA, false);
        mana.turnFaceDown();
        p.getManaZone().add(mana);
    }

    /** 一時マナ(【ピュア・エレメント】由来)を1枚足す */
    private void temporary(PlayerState p) {
        p.getManaZone().add(new ManaCard(MAGMA, true));
    }

    /** タップされているマナの位置 */
    private List<Integer> tappedIndexes(PlayerState p) {
        return java.util.stream.IntStream.range(0, p.getManaZone().size())
                .filter(i -> p.getManaZone().get(i).isTapped())
                .boxed().toList();
    }

    // ==================================================================
    // 1. 順序そのもの(裁定315・316・317)
    // ==================================================================

    /**
     * 通常の支払いは <b>一時マナ → 裏向き → 表向き</b>(裁定315・316)。
     * ★<b>並びの前後関係で測る</b> —— 期待する配列を書き写さない。
     */
    @Test
    void 通常の支払いは一時マナ裏向き表向きの順である() {
        AutoGameFixture f = newGame();
        faceUp(f.me());        // 0
        faceDown(f.me());      // 1
        temporary(f.me());     // 2
        faceUp(f.me());        // 3

        List<Integer> order = ManaPayment.normalOrder(f.me());

        assertThat(order).as("未タップのマナがすべて並ぶ").hasSize(4);
        assertThat(order.indexOf(2))
                .as("★裁定316: 一時マナは期限付きなので最優先で払う")
                .isLessThan(order.indexOf(1));
        assertThat(order.indexOf(1))
                .as("★裁定315: 裏向きから払う(表向きは禁忌の弾として温存する)")
                .isLessThan(order.indexOf(0));
        assertThat(order.indexOf(0))
                .as("★同順位はマナゾーンの並び順を保つ(同じ盤面なら毎回同じ順で払う)")
                .isLessThan(order.indexOf(3));
    }

    /** 禁忌は逆で <b>表向き → 裏向き</b>。★一時マナは並ばない(裁定317) */
    @Test
    void 禁忌の支払いは表向き裏向きの順で一時マナを含まない() {
        AutoGameFixture f = newGame();
        faceDown(f.me());      // 0
        temporary(f.me());     // 1
        faceUp(f.me());        // 2

        List<Integer> order = ManaPayment.tabooOrder(f.me());

        assertThat(order)
                .as("★一時マナは禁忌のコストにできない(カードテキスト)")
                .doesNotContain(1);
        assertThat(order.indexOf(2))
                .as("★裁定317: 裏向きは墓地送りになるので、減らずに済む表向きから払う")
                .isLessThan(order.indexOf(0));
    }

    /**
     * ★禁忌の順には<b>タップ済みのマナも並ぶ</b>。
     * 禁忌の支払いはタップではなく「裏返す / 墓地へ送る」だからである
     * (69 までの {@code validateTabooCost} も同じ扱いだった)。
     */
    @Test
    void 禁忌の順にはタップ済みのマナも並ぶ() {
        AutoGameFixture f = newGame();
        faceUp(f.me());
        f.me().getManaZone().get(0).tap();

        assertThat(ManaPayment.tabooOrder(f.me())).containsExactly(0);
        assertThat(ManaPayment.normalOrder(f.me()))
                .as("★通常の支払いはタップなので、タップ済みは並ばない")
                .isEmpty();
    }

    // ==================================================================
    // 2. 実際に払われるマナが、その順の先頭 n 枚であること
    // ==================================================================

    /**
     * ★★★<b>これが 70 の中心の番人である。</b>
     *
     * <p>{@link ManaPayment} を払う直前に読んでおき、
     * 払ったあとタップされていたものと突き合わせる ——
     * <b>期待する位置を1つも書かない</b>ので、規則を変えたときに
     * この試験を書き換えて緑にすることができない(裁定41)。
     */
    @Test
    void 自動の支払いはManaPaymentの順の先頭から行われる() {
        AutoGameFixture f = newGame();
        faceUp(f.me());        // 0
        faceUp(f.me());        // 1
        faceDown(f.me());      // 2
        temporary(f.me());     // 3

        int cost = f.card(SHIELD).cost();   // ★2。値は書かずカードから読む
        List<Integer> plannedBefore = ManaPayment.normalOrder(f.me()).subList(0, cost);

        game.playCard(f.room(), "me", f.giveHand(f.me(), SHIELD), List.of(), false);

        assertThat(tappedIndexes(f.me()))
                .as("★払われたのは、払う直前に ManaPayment が示した先頭 %d 枚である", cost)
                .containsExactlyInAnyOrderElementsOf(plannedBefore);
    }

    /**
     * ★<b>そうでない側</b>(裁定181)。「先頭から順にタップする」69 の実装なら
     * 位置 0・1(表向き)が落ちるはずである —— それでは通らないことを名指しで測る。
     */
    @Test
    void 自動の支払いは表向きを温存する() {
        AutoGameFixture f = newGame();
        faceUp(f.me());        // 0
        faceUp(f.me());        // 1
        faceDown(f.me());      // 2
        temporary(f.me());     // 3

        game.playCard(f.room(), "me", f.giveHand(f.me(), SHIELD), List.of(), false);

        assertThat(tappedIndexes(f.me()))
                .as("★裁定315・316: 一時マナ(3)と裏向き(2)が先に落ち、表向き(0・1)は残る")
                .containsExactlyInAnyOrder(2, 3);
    }

    // ==================================================================
    // 3. 人が選んだ支払い(裁定319)
    // ==================================================================

    /** 指定があれば<b>そのマナ</b>が払われる(自動の順は使わない) */
    @Test
    void 指定されたマナがそのとおりに払われる() {
        AutoGameFixture f = newGame();
        faceUp(f.me());        // 0
        faceUp(f.me());        // 1
        faceDown(f.me());      // 2
        temporary(f.me());     // 3

        game.playCard(f.room(), "me", f.giveHand(f.me(), SHIELD),
                List.of(), false, List.of(), List.of(0, 1));

        assertThat(tappedIndexes(f.me()))
                .as("★自動なら 2・3 が落ちる盤面である。指定が勝っている")
                .containsExactly(0, 1);
    }

    /** 枚数が合わない指定は弾く(設計判断27: 届いた値をそのまま信じない) */
    @Test
    void 枚数の合わない指定は弾かれる() {
        AutoGameFixture f = newGame();
        faceUp(f.me());
        faceUp(f.me());
        int hand = f.giveHand(f.me(), SHIELD);

        assertThatThrownBy(() -> game.playCard(f.room(), "me", hand,
                List.of(), false, List.of(), List.of(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2枚");
        assertThat(tappedIndexes(f.me())).as("弾かれた側で盤面が動いていない").isEmpty();
    }

    /** タップ済み・重複・範囲外の指定も弾く */
    @Test
    void 通らないマナの指定は弾かれる() {
        AutoGameFixture f = newGame();
        faceUp(f.me());
        faceUp(f.me());
        faceUp(f.me());
        f.me().getManaZone().get(0).tap();
        int hand = f.giveHand(f.me(), SHIELD);

        assertThatThrownBy(() -> game.playCard(f.room(), "me", hand,
                List.of(), false, List.of(), List.of(0, 1)))
                .as("タップ済みのマナは払いに使えない")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> game.playCard(f.room(), "me", hand,
                List.of(), false, List.of(), List.of(1, 1)))
                .as("同じマナを2回は払えない")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> game.playCard(f.room(), "me", hand,
                List.of(), false, List.of(), List.of(1, 9)))
                .as("範囲外の位置は払えない")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================================================================
    // 4. 禁忌の自動支払い(裁定317・321)
    // ==================================================================

    /**
     * 禁忌は<b>指定を省いても払える</b>ようになった(裁定317)。
     * ★表向きから払うので、この盤面ではマナが1枚も減らない。
     */
    @Test
    void 禁忌は指定を省くと表向きから自動で払われる() {
        AutoGameFixture f = newGame();
        faceDown(f.me());      // 0
        faceUp(f.me());        // 1
        f.me().getTabooDeck().add(PAWN);   // コスト1・効果を持たないミニオン

        game.playTabooCard(f.room(), "me", 0, List.of(), List.of());

        assertThat(f.me().getManaZone()).as("★マナは1枚も減っていない").hasSize(2);
        assertThat(f.me().getManaZone().get(1).isFaceUp())
                .as("★裁定317: 表向き(1)が裏返された")
                .isFalse();
        assertThat(f.me().getManaZone().get(0).isFaceUp())
                .as("裏向き(0)は触られていない")
                .isFalse();
        assertThat(f.me().getTrash())
                .as("★墓地送りは起きていない(取り返しのつかない支払いを勝手にしない)")
                .doesNotContain(MAGMA);   // マナの中身は MAGMA である
    }

    /**
     * ★裏向きしか無ければ墓地送りになる —— <b>これが警告の対象である</b>(裁定317)。
     * ★警告そのものは画面の話なので verify が測る。ここでは<b>実際に減ること</b>を固定する。
     */
    @Test
    void 裏向きしか無い禁忌の支払いはマナが減る() {
        AutoGameFixture f = newGame();
        faceDown(f.me());
        f.me().getTabooDeck().add(PAWN);

        game.playTabooCard(f.room(), "me", 0, List.of(), List.of());

        assertThat(f.me().getManaZone()).as("★マナが永久に1枚減る").isEmpty();
        assertThat(f.me().getTrash())
                .as("★墓地へ行ったのはマナの中身(MAGMA)である")
                .contains(MAGMA);
    }

    /** 払えるマナが足りなければ、自動でも止まる */
    @Test
    void 禁忌の自動支払いはマナが足りなければ止まる() {
        AutoGameFixture f = newGame();
        temporary(f.me());   // 一時マナは禁忌に使えない
        f.me().getTabooDeck().add(PAWN);

        assertThatThrownBy(() -> game.playTabooCard(f.room(), "me", 0, List.of(), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("使えるマナが足りません");
    }

    // ==================================================================
    // 5. ビューが順序を載せていること(クライアントは規則を持たない)
    // ==================================================================

    /**
     * ★★クライアントの強調表示はこの2本だけを読む。
     * <b>載っていなければ、ドラッグ中に何も光らない</b>(=写しを書きたくなる)。
     */
    @Test
    void ビューは自分にだけ支払いの順を載せる() {
        AutoGameFixture f = newGame();
        faceUp(f.me());
        faceDown(f.me());
        temporary(f.me());
        faceUp(f.you());

        PlayerView mine = views.build(f.room(), "me").you();
        PlayerView theirs = views.build(f.room(), "me").opponent();

        assertThat(mine.manaPayOrder())
                .as("★通常の支払いの順が載っている")
                .containsExactlyElementsOf(ManaPayment.normalOrder(f.me()));
        assertThat(mine.tabooPayOrder())
                .as("★禁忌の支払いの順が載っている")
                .containsExactlyElementsOf(ManaPayment.tabooOrder(f.me()));
        assertThat(theirs.manaPayOrder())
                .as("★相手のビューには入れない —— どのマナから払うつもりかは手の内である")
                .isEmpty();
        assertThat(theirs.tabooPayOrder()).isEmpty();
    }
}
