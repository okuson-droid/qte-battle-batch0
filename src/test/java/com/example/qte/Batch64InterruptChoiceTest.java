package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.effect.PendingChoice;
import com.example.qte.effect.ResumePoint;
import com.example.qte.effect.TargetChoice;
import com.example.qte.game.GameActions;
import com.example.qte.game.GameService;
import com.example.qte.game.ManaCard;
import com.example.qte.game.MinionInstance;
import com.example.qte.game.PlayerState;
import com.example.qte.game.view.GameViewBuilder;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.Keyword;
import com.example.qte.support.AutoGameFixture;

/**
 * Batch 64(割り込み選択の一般化)の挙動の試験。
 *
 * <h2>このバッチが測るもの</h2>
 *
 * 10b から 63 まで、効果の解決中に生じる選択のうち8件は {@code AutoChoice} が
 * <b>本人に聞かずに</b>決めていた。59 までの設計解説はその理由を
 * 「割り込みは中断であり、ドローはあらゆる場所から呼ばれるので中断点を作れない」と
 * 書いていたが、<b>それは読み違いだった</b> ——
 * 割り込みは中断ではなく<b>後回し</b>であり、問い合わせを積んでも呼び出し元は先へ進む。
 *
 * <p>実際に詰まっていたのは次の3つで、64 はその3つを外した。
 *
 * <ul>
 * <li><b>はい/いいえの器が無い</b> …… {@link PendingChoice.Kind#CONFIRM} を足した</li>
 * <li><b>1人につき1件しか積めない</b> …… {@code PlayerState} の待ち行列にした(裁定300)</li>
 * <li><b>再開に要る値を運べない</b> …… {@code PendingChoice.payload} を足した</li>
 * </ul>
 *
 * <p>そのうえで、キュー化が生んだ新しい危険 ——
 * <b>手前の選択の解決で、後ろの選択の候補が指す先が動く</b> —— に番人を置いた
 * ({@code expectedCardIds} の照合)。
 *
 * <h2>本物の入口を通す</h2>
 *
 * {@link AutoGameFixture} の上に書き、効果は {@code GameService} の実際の入口から起こす
 * (裁定187)。選択の解決も {@code GameService.resolveChoice} を通し、
 * {@code CardEffectRegistry.resolveChoice} を直接叩かない。
 */
@SpringBootTest
class Batch64InterruptChoiceTest {

    /** 常在効果を持たないリーダー(蒼海の賢者)。既定の対戦相手 */
    private static final String PLAIN_LEADER = "QTE-M-WATER-1";

    /** 執念の暗殺者(4/3/3)。【常在】ミニオンが破壊されるたび1枚引いてもよい */
    private static final String ASSASSIN = "QTE-M-DARK-20";
    /** 英知の水晶(3/1/2)。相手が引いたとき1枚引いてもよい */
    private static final String WISDOM_CRYSTAL = "QTE-M-LIGHT-19";
    /** 不滅のネクロマンサー(4/6/1)。★Batch 64 で Ver1.1 の本文へ作り直した */
    private static final String NECROMANCER = "QTE-M-DARK-5";
    /** 冥界神ハデス(8/7/7)。【召喚時】全体破壊 → 裏向きマナの枚数だけ蘇生 */
    private static final String HADES = "QTE-M-DARK-8";
    /** サモンズライト(2/1/2)。【破壊時】墓地からコスト1のミニオンを1体出す */
    private static final String SUMMONS_LIGHT = "QTE-M-DARK-34";
    /** 死霊の収鎌(闇のウェポン)。リーダーの攻撃時に墓地から1枚手札へ */
    private static final String WRAITH_SCYTHE = "QTE-M-DARK-28";
    /** 嵐の呼び手(2/2/2)。【召喚時】3枚以上使用していたら相手のミニオン1体をバウンス */
    private static final String STORM_CALLER = "QTE-M-WIND-4";

    /** スカイ・スワロー(1/1/1・【速攻】)。コスト1の素のミニオン(蘇生の的) */
    private static final String SKY_SWALLOW = "QTE-M-WIND-3";
    /** フレア・ポーン(1/1/1)。「効果なし」と明記されたコスト1のミニオン(蘇生の的その2) */
    private static final String FLARE_PAWN = "QTE-M-FIRE-2";
    /** ゴーレム・ウォール(3/1/5・【守護】)。コスト3の素のミニオン */
    private static final String PLAIN_GUARD = "QTE-M-EARTH-2";
    /** ライト・シールド(2/1/3・【守護】)。殴られ役・並べ役 */
    private static final String LIGHT_SHIELD = "QTE-M-LIGHT-2";
    /** マグマ・ストレート(スペル・1)。マナの中身に使う(対象候補に紛れないため) */
    private static final String MAGMA = "QTE-M-FIRE-10";

    @Autowired
    GameService game;

    @Autowired
    GameActions actions;

    @Autowired
    GameViewBuilder views;

    @Autowired
    CardMasterRepository cards;

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

    /** 裏向きのマナを n 枚置く(闇の資源)。第2引数は「一時マナか」なので false である */
    private void faceDownMana(PlayerState player, int count) {
        for (int i = 0; i < count; i++) {
            ManaCard mana = new ManaCard(MAGMA, false);
            mana.turnFaceDown();
            player.getManaZone().add(mana);
        }
    }

    private static TargetChoice minions(String... instanceIds) {
        return new TargetChoice(null, List.of(instanceIds), null, null, null);
    }

    /** 候補のうち、墓地の位置 position を指すものの番号 */
    private int candidateFor(PlayerState player, String position) {
        return player.getPendingChoice().candidates().indexOf(position);
    }

    // ==================================================================
    // 1. 器: はい/いいえ(PendingChoice.Kind.CONFIRM)
    // ==================================================================

    @Test
    void はいいいえの問い合わせは候補1件の選択として表される() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), ASSASSIN);
        MinionInstance victim = f.putOnField(f.me(), LIGHT_SHIELD);

        actions.destroyMinion(f.room(), f.me(), victim);

        PendingChoice choice = f.me().getPendingChoice();
        assertThat(choice.kind()).isEqualTo(PendingChoice.Kind.CONFIRM);
        assertThat(choice.candidates()).as("★候補は「はい」1件だけである").hasSize(1);
        assertThat(choice.min()).as("選ばなくてもよい = いいえ").isZero();
        assertThat(choice.max()).isEqualTo(1);
    }

    /**
     * ★<b>送受信の形は1つも増えていない</b>ことの番人。
     * CONFIRM を「はい/いいえ」という新しい型で運んだら、
     * WebSocket のリクエストもビューも分岐が1つ増えていた。
     */
    @Test
    void はいいいえもビューには他の選択と同じ形で届く() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), ASSASSIN);
        actions.destroyMinion(f.room(), f.me(), f.putOnField(f.me(), LIGHT_SHIELD));

        var view = views.build(f.room(), "me").you().pendingChoice();

        assertThat(view.kind()).isEqualTo("CONFIRM");
        assertThat(view.candidates()).hasSize(1);
        assertThat(view.min()).isZero();
        assertThat(view.max()).isEqualTo(1);
        assertThat(view.queued()).as("待っているのはこの1件だけ").isEqualTo(1);
    }

    // ==================================================================
    // 2. 器: 待ち行列(裁定300 —— 1回ずつ聞く)
    // ==================================================================

    /**
     * ★★<b>このバッチが待ち行列を必要とした理由そのものである。</b>
     * 63 までの {@code requestChoice} は2件目で例外を投げた。
     * 《執念の暗殺者》は「ミニオンが破壊されるたび」なので、
     * 全体破壊が複数体を飛ばせば1回の解決の中で何度も誘発する。
     */
    @Test
    void 破壊のたびに問い合わせが1件ずつ積まれる() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), ASSASSIN);
        MinionInstance a = f.putOnField(f.me(), LIGHT_SHIELD);
        MinionInstance b = f.putOnField(f.me(), LIGHT_SHIELD);
        MinionInstance c = f.putOnField(f.me(), LIGHT_SHIELD);

        actions.destroyMinion(f.room(), f.me(), a);
        actions.destroyMinion(f.room(), f.me(), b);
        actions.destroyMinion(f.room(), f.me(), c);

        assertThat(f.me().getPendingChoiceCount()).as("★3回の破壊で3件積まれる").isEqualTo(3);
        assertThat(views.build(f.room(), "me").you().pendingChoice().queued())
                .as("残り件数はビューにも届く").isEqualTo(3);
    }

    @Test
    void 待ち行列は起きた順に1件ずつ解決される() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), ASSASSIN);
        actions.destroyMinion(f.room(), f.me(), f.putOnField(f.me(), LIGHT_SHIELD));
        actions.destroyMinion(f.room(), f.me(), f.putOnField(f.me(), LIGHT_SHIELD));
        int deckBefore = f.me().getDeck().size();

        game.resolveChoice(f.room(), "me", List.of(0)); // 1件目に[はい]
        assertThat(f.me().getPendingChoiceCount()).as("★取り出すのは先頭1件だけ").isEqualTo(1);
        assertThat(f.me().getDeck()).hasSize(deckBefore - 1);

        game.resolveChoice(f.room(), "me", List.of()); // 2件目に[いいえ]
        assertThat(f.me().getPendingChoice()).isNull();
        assertThat(f.me().getDeck()).as("2件目は引かなかった").hasSize(deckBefore - 1);
    }

    /**
     * ★<b>上限は構築ルールではなく防波堤である</b>(設計判断27)。
     * ここに引っかかるのはカード側の誘発が閉路になっているときだけである。
     */
    @Test
    void 待ち行列には上限がある() {
        AutoGameFixture f = newGame();
        for (int i = 0; i < PlayerState.MAX_PENDING_CHOICES; i++) {
            f.me().enqueuePendingChoice(PendingChoice.confirm(
                    ResumePoint.ASSASSIN_OPTIONAL_DRAW, "試験用"));
        }

        assertThatThrownBy(() -> f.me().enqueuePendingChoice(PendingChoice.confirm(
                ResumePoint.ASSASSIN_OPTIONAL_DRAW, "試験用")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("選択待ちが多すぎます");
    }

    /**
     * ★★裁定214 の対(選択待ちの間は誰も盤面を動かさない)が、
     * <b>キューになっても効いている</b>ことの番人。
     * 片方だけ守ると、答える前に候補の指す先が変わる。
     */
    @Test
    void 選択待ちが残っている間は手番の側も盤面を動かせない() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), ASSASSIN);
        actions.destroyMinion(f.room(), f.me(), f.putOnField(f.me(), LIGHT_SHIELD));
        actions.destroyMinion(f.room(), f.me(), f.putOnField(f.me(), LIGHT_SHIELD));
        game.resolveChoice(f.room(), "me", List.of()); // 1件目だけ答える

        assertThatThrownBy(() -> game.endTurn(f.room(), "me"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("先に選択を解決してください");
    }

    // ==================================================================
    // 3. 器: 位置ズレの照合(キュー化が生んだ新しい危険)
    // ==================================================================

    /**
     * ★★★<b>候補が指す「墓地の位置」は、待っている間にずれうる。</b>
     *
     * 63 までは1人1件だったので、選択待ちの間に盤面が動くことは無かった
     * (裁定214 の対が両者を止める)。キューになると<b>手前の選択の解決</b>が
     * 同じゾーンを動かすため、後ろの選択の位置が別のカードを指す。
     *
     * <p>ここでは墓地の先頭を指す問い合わせを作った後で、そのカードを墓地から抜いてしまう。
     * 照合が無ければ「1つずれた別のカード」が場に出る。
     */
    @Test
    void 待っている間に墓地が動いたら選択は何も起こさない() {
        AutoGameFixture f = newGame();
        f.me().getTrash().add(SKY_SWALLOW);
        f.me().getTrash().add(FLARE_PAWN);
        f.me().getTrash().add(PLAIN_GUARD);
        MinionInstance summons = f.putOnField(f.me(), SUMMONS_LIGHT);

        actions.destroyMinion(f.room(), f.me(), summons); // 【破壊時】: コスト1が2体 → 問い合わせ
        int pick = candidateFor(f.me(), "0"); // 墓地の0番目(スカイ・スワロー)を選ぶ
        f.me().getTrash().remove(0); // ★答える前に墓地が動いた

        game.resolveChoice(f.room(), "me", List.of(pick));

        assertThat(f.fieldIds(f.me())).as("★ずれた別のカードを出さない").isEmpty();
        assertThat(f.me().getPendingChoice()).as("問い合わせは解消している").isNull();
    }

    @Test
    void 位置が動いていなければそのまま解決する() {
        AutoGameFixture f = newGame();
        f.me().getTrash().add(SKY_SWALLOW);
        f.me().getTrash().add(FLARE_PAWN);
        MinionInstance summons = f.putOnField(f.me(), SUMMONS_LIGHT);

        actions.destroyMinion(f.room(), f.me(), summons);
        game.resolveChoice(f.room(), "me", List.of(candidateFor(f.me(), "1")));

        assertThat(f.fieldIds(f.me())).as("選んだフレア・ポーンが出る").containsExactly(FLARE_PAWN);
    }

    /** ★ミニオンの選択は instanceId なので照合の対象外である(位置ではない) */
    @Test
    void ミニオンの選択には控えを取らない() {
        AutoGameFixture f = newGame();
        f.putOnField(f.you(), LIGHT_SHIELD);
        f.putOnField(f.you(), PLAIN_GUARD);
        payMana(f.me(), 10);
        f.me().setCardsUsedThisTurn(3);

        game.playCard(f.room(), "me", f.giveHand(f.me(), STORM_CALLER), List.of(), false);

        assertThat(f.me().getPendingChoice().kind()).isEqualTo(PendingChoice.Kind.MINION);
        assertThat(f.me().getPendingChoice().expectedCardIds())
                .as("★位置を指さない選択は控えを持たない").isEmpty();
    }

    // ==================================================================
    // 4. 「してもよい」3枚(裁定299・301・302)
    // ==================================================================

    /** ★裁定302: 引けば敗北する選択肢を並べない */
    @Test
    void 山札が空なら引くかどうかを問い合わせない() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), ASSASSIN);
        f.me().getDeck().clear();

        actions.destroyMinion(f.room(), f.me(), f.putOnField(f.me(), LIGHT_SHIELD));

        assertThat(f.me().getPendingChoice()).as("★問い合わせ自体が出ない").isNull();
    }

    /**
     * ★★★<b>《不滅のネクロマンサー》は Ver1.1 の本文へ作り直した(★Batch 64)。</b>
     *
     * 旧(Ver0.4・3/3): 「自分の他のミニオンが破壊されるたび、裏向きマナ1枚を破壊して
     * そのミニオンを蘇生し【突進】を付与してもよい。」
     * 新(Ver1.1・6/1): 「出た時相手はカードを1枚引く。」
     *
     * <p>P5(Batch 55〜59)の作り直しから丸ごと抜け落ちており、
     * <b>Ver1.1 に存在しない効果が 63 まで動いていた</b>。
     * 64 の調査中に見つかり、マスターの指示でこのバッチで直した。
     */
    @Test
    void 不滅のネクロマンサーは出たとき相手が1枚引く() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        int myHand = f.me().getHand().size();
        int yourHand = f.you().getHand().size();

        game.playCard(f.room(), "me", f.giveHand(f.me(), NECROMANCER), List.of(), false);

        assertThat(f.you().getHand()).as("★引くのは相手である").hasSize(yourHand + 1);
        assertThat(f.me().getHand()).as("自分は使った1枚が減るだけ").hasSize(myHand);
    }

    /** ★「出た時」は【召喚時】ではないので、効果で場に出しても発動する(リファレンス 2-9 の3層の2番目) */
    @Test
    void 不滅のネクロマンサーは効果で場に出しても相手が引く() {
        AutoGameFixture f = newGame();
        int yourHand = f.you().getHand().size();

        actions.putIntoFieldByEffect(f.room(), f.me(), NECROMANCER);

        assertThat(f.you().getHand()).as("★召喚以外の登場でも発動する").hasSize(yourHand + 1);
    }

    /** ★★破壊の監視はもう持たない(Ver0.4 の効果が残っていたら、ここで裏向きマナが減る) */
    @Test
    void 不滅のネクロマンサーはもうミニオンの破壊を見ていない() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), NECROMANCER);
        faceDownMana(f.me(), 1);
        MinionInstance victim = f.putOnField(f.me(), LIGHT_SHIELD);

        actions.destroyMinion(f.room(), f.me(), victim);

        assertThat(f.me().getPendingChoice()).as("★問い合わせも出ない").isNull();
        assertThat(f.me().getFaceDownManaCount()).as("★裏向きマナを失わない").isEqualTo(1);
        assertThat(f.fieldIds(f.me())).as("★蘇生しない").containsExactly(NECROMANCER);
    }

    /**
     * ★★<b>{@code payload} の使い手は《英術・スケアロック》になった。</b>
     * 「どの進化カードを出そうとしているか」は候補では表せない<b>文脈</b>であり、
     * 63 までは {@code PlayerState.pendingEvolutionCardId} という
     * このカード専用のフィールドが運んでいた。器ができたので専用の箱は撤去した。
     */
    @Test
    void 進化の文脈は持ち物入れが運ぶ() {
        AutoGameFixture f = newGame();
        assertThatThrownBy(() -> PlayerState.class.getMethod("getPendingEvolutionCardId"))
                .as("★カード専用のフィールドは撤去された")
                .isInstanceOf(NoSuchMethodException.class);
        assertThat(f.me()).isNotNull();
    }

    // ==================================================================
    // 5. 蘇生の対象3枚(裁定192・299)
    // ==================================================================

    /**
     * ★★<b>冥界神ハデスは「このターン破壊された味方」を多重度どおりに候補にする。</b>
     * 同名が以前から墓地に居ても、このターン壊れた数までしか候補にならない。
     */
    @Test
    void 冥界神ハデスは蘇生する体を本人に選ばせる() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), LIGHT_SHIELD);
        f.putOnField(f.me(), PLAIN_GUARD);
        f.putOnField(f.me(), SKY_SWALLOW);
        faceDownMana(f.me(), 1); // 蘇生できるのは1体だけ
        payMana(f.me(), 8);

        game.playCard(f.room(), "me", f.giveHand(f.me(), HADES), List.of(), false);

        PendingChoice choice = f.me().getPendingChoice();
        assertThat(choice).as("★3体の中から1体を選ぶ").isNotNull();
        assertThat(choice.kind()).isEqualTo(PendingChoice.Kind.TRASH);
        assertThat(choice.candidates()).hasSize(3);
        assertThat(choice.min()).as("上限1体は必須である").isEqualTo(1);

        String guardPosition = String.valueOf(f.me().getTrash().indexOf(PLAIN_GUARD));
        game.resolveChoice(f.room(), "me", List.of(candidateFor(f.me(), guardPosition)));

        assertThat(f.fieldIds(f.me())).as("★選んだ1体だけが戻る")
                .containsExactlyInAnyOrder(HADES, PLAIN_GUARD);
    }

    /** ★候補が上限以下なら選ぶ余地が無いので問い合わせない(既存の流儀を変えていない) */
    @Test
    void 冥界神ハデスは候補が上限以下なら問い合わせない() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), LIGHT_SHIELD);
        faceDownMana(f.me(), 3); // 上限3 > 候補1
        payMana(f.me(), 8);

        game.playCard(f.room(), "me", f.giveHand(f.me(), HADES), List.of(), false);

        assertThat(f.me().getPendingChoice()).as("★選ぶ余地が無い").isNull();
        assertThat(f.fieldIds(f.me())).containsExactlyInAnyOrder(HADES, LIGHT_SHIELD);
    }

    /** ★以前から墓地に居るカードは「このターン破壊された」に数えない */
    @Test
    void 冥界神ハデスは以前から墓地に居るミニオンを候補にしない() {
        AutoGameFixture f = newGame();
        f.me().getTrash().add(PLAIN_GUARD); // このターン破壊されたのではない
        f.putOnField(f.me(), LIGHT_SHIELD);
        faceDownMana(f.me(), 3);
        payMana(f.me(), 8);

        game.playCard(f.room(), "me", f.giveHand(f.me(), HADES), List.of(), false);

        assertThat(f.fieldIds(f.me())).as("★戻るのはこのターン壊れた1体だけ")
                .containsExactlyInAnyOrder(HADES, LIGHT_SHIELD);
    }

    /**
     * ★★<b>裁定214 が外した制限の、最後の使い残しだった。</b>
     * 【破壊時】は相手のターン中にも起きる。50 はそれを理由に自動決定にしていたが、
     * 51 でその制限は外れていた —— 外れてもなお実装は自動決定のまま残っていた。
     */
    @Test
    void サモンズライトは相手のターン中でも本人が蘇生対象を選ぶ() {
        AutoGameFixture f = newGame();
        f.state().setTurnPlayerId("you"); // ★相手のターン
        f.me().getTrash().add(SKY_SWALLOW);
        f.me().getTrash().add(FLARE_PAWN);
        MinionInstance summons = f.putOnField(f.me(), SUMMONS_LIGHT);

        actions.destroyMinion(f.room(), f.me(), summons);

        assertThat(f.me().getPendingChoice()).as("★手番でなくても本人に問う").isNotNull();
        game.resolveChoice(f.room(), "me", List.of(candidateFor(f.me(), "0")));

        assertThat(f.fieldIds(f.me())).containsExactly(SKY_SWALLOW);
    }

    // ==================================================================
    // 6. 対象の自動選択2枚(裁定299)
    // ==================================================================

    /**
     * ★<b>リーダーの攻撃から起きる割り込みである。</b>
     * ウェポンの攻撃時効果はダメージの後に走るので、戦闘の保留には乗らない。
     */
    @Test
    void 死霊の収鎌は手札に戻す1枚を本人が選ぶ() {
        AutoGameFixture f = newGame();
        f.me().getTrash().add(SKY_SWALLOW);
        f.me().getTrash().add(FLARE_PAWN);
        payMana(f.me(), 10);
        game.playCard(f.room(), "me", f.giveHand(f.me(), WRAITH_SCYTHE), List.of(), false);
        game.nextPhase(f.room(), "me"); // メイン → バトル
        int handBefore = f.me().getHand().size();

        game.leaderAttack(f.room(), "me", null);

        assertThat(f.me().getPendingChoice()).as("★どの1枚かを問う").isNotNull();
        assertThat(f.state().getPendingAttack()).as("★戦闘の保留には乗らない").isNull();
        game.resolveChoice(f.room(), "me", List.of(candidateFor(f.me(), "1")));

        assertThat(f.me().getHand()).as("★選んだフレア・ポーンが戻る")
                .hasSize(handBefore + 1).contains(FLARE_PAWN);
    }

    @Test
    void 嵐の呼び手は手札に戻す相手のミニオンを本人が選ぶ() {
        AutoGameFixture f = newGame();
        MinionInstance weak = f.putOnField(f.you(), SKY_SWALLOW);
        f.putOnField(f.you(), PLAIN_GUARD);
        payMana(f.me(), 10);
        f.me().setCardsUsedThisTurn(3);

        game.playCard(f.room(), "me", f.giveHand(f.me(), STORM_CALLER), List.of(), false);

        assertThat(f.me().getPendingChoice()).as("★候補が2体あるので問う").isNotNull();
        game.resolveChoice(f.room(), "me",
                List.of(f.me().getPendingChoice().candidates().indexOf(weak.getInstanceId())));

        assertThat(f.fieldIds(f.you())).as("★63 までは攻撃力の高いほうを自動で選んでいた")
                .containsExactly(PLAIN_GUARD);
    }

    @Test
    void 嵐の呼び手は候補が1体なら問い合わせない() {
        AutoGameFixture f = newGame();
        f.putOnField(f.you(), PLAIN_GUARD);
        payMana(f.me(), 10);
        f.me().setCardsUsedThisTurn(3);

        game.playCard(f.room(), "me", f.giveHand(f.me(), STORM_CALLER), List.of(), false);

        assertThat(f.me().getPendingChoice()).as("★選ぶ余地が無い").isNull();
        assertThat(f.fieldIds(f.you())).isEmpty();
    }

    // ==================================================================
    // 7. AutoChoice の退役
    // ==================================================================

    /**
     * ★★<b>「自動決定という方針」の入れ物が消えたことの番人。</b>
     *
     * 10b から 63 まで、解決中の選択は {@code com.example.qte.effect.AutoChoice} に
     * 集めて自動で決めていた。64 で8件が本人の選択へ移り、残った1件
     * (《ホーリー・シグナル》の「最も体力の低いミニオン」)は
     * <b>方針ではなくカード固有の都合</b>になったので {@code CardEffectRegistry} へ移した。
     *
     * <p>★クラスを復活させたらこの試験が落ちる。
     * 63 の教訓(退役は経路・ファイル・案内を同時に消す)の適用先がクラスになった形である。
     */
    @Test
    void AutoChoiceは退役してもう存在しない() {
        assertThatThrownBy(() -> Class.forName("com.example.qte.effect.AutoChoice"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    /**
     * ★<b>ホーリー・シグナルの「最も体力の低いミニオン」だけは今も自動である。</b>
     * 本人に選ばせたくないからではなく、2つ目の {@code Requirement} にすると
     * {@code validateTargets} の重複防止に引っかかってカードが使用不能になるためである。
     */
    @Test
    void ホーリーシグナルの最低体力側は今も自動で決まる() {
        AutoGameFixture f = newGame();
        MinionInstance strong = f.putOnField(f.you(), PLAIN_GUARD); // 1/5
        MinionInstance fragile = f.putOnField(f.you(), SKY_SWALLOW); // 1/1
        f.putOnField(f.you(), LIGHT_SHIELD); // 1/3(巻き込まれない)
        strong.addModifier(new com.example.qte.game.StatModifier(
                com.example.qte.game.StatModifier.Stat.ATTACK,
                com.example.qte.game.StatModifier.Operation.ADD, 5,
                com.example.qte.game.StatModifier.Duration.PERMANENT, "test"));
        payMana(f.me(), 10);

        game.playCard(f.room(), "me", f.giveHand(f.me(), "QTE-M-LIGHT-10"),
                List.of(minions(strong.getInstanceId())), false);

        assertThat(f.me().getPendingChoice()).as("★問い合わせは出ない").isNull();
        assertThat(f.fieldIds(f.you()))
                .as("最高攻撃力(選択)と最低体力(自動)の2体が壊れる")
                .containsExactly(LIGHT_SHIELD);
        assertThat(f.you().getMinionZone()).doesNotContain(fragile);
    }
}
