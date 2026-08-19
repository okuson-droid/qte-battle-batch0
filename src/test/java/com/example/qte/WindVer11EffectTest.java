package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.effect.RuleGuards;
import com.example.qte.effect.StatCalculator;
import com.example.qte.game.GameActions;
import com.example.qte.game.GameService;
import com.example.qte.game.MinionInstance;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.Keyword;
import com.example.qte.support.AutoGameFixture;

/**
 * Ver1.1 で追加された風文明8枚の挙動の試験(★Batch 48 で新設)。
 *
 * <h2>これが通常モード初のゲームプレイ試験である</h2>
 *
 * 46b までの JUnit はカードマスタの読み込み・キーワード抽出・デッキ構築の検証しか
 * 見ておらず、「召喚したら盤面がこうなる」を測るものが1件も無かった。
 * P2 は効果を作るフェーズなので、ここから先はそれでは何も守れない。
 *
 * <h2>測り方の方針</h2>
 *
 * <ul>
 * <li><b>本物の入口から起こす。</b> {@code GameService.playCard} /
 *     {@code specialSummon} / {@code endTurn} を呼ぶ。トリガーを直接叩くと、
 *     「発火する場所が正しいか」という<b>いちばん壊れやすいところ</b>が試験の外に出る。</li>
 * <li><b>裁定を名指しで固定する。</b> このバッチで新しく決めた3つの裁定
 *     (183: ターンのはじめは自分のターンだけ / 184: 暴レ狂ウ・オニは全体にNダメージ /
 *     185: シュテンは両者合計)は、それぞれ「そうでない側」も測る。
 *     期待値を1方向しか書かないと、実装がどちらでも通ってしまう。</li>
 * <li><b>数ではなく結果を見る。</b> 「破壊された数」を内部カウンタで確かめるのではなく、
 *     相手のLPやミニオンのHPという<b>プレイヤーに見える結果</b>で確かめる。</li>
 * </ul>
 */
@SpringBootTest
class WindVer11EffectTest {

    /** 常在効果を持たないリーダー(既定)。妖ノ長・ストクの試験だけ差し替える */
    private static final String PLAIN_LEADER = "QTE-M-WIND-1";  // 疾風の導き手(起動能力のみ)
    private static final String STOK = "QTE-M-WIND-29";         // 妖ノ長・ストク
    private static final String SHEER = "QTE-M-WIND-33";        // 透キ通ル・アヤカシ
    private static final String HAKUREI = "QTE-M-WIND-34";      // ハク霊
    private static final String KOKUREI = "QTE-M-WIND-35";      // コク霊
    private static final String GATHERING = "QTE-M-WIND-36";    // 喚ビ集ウ・アヤカシ
    private static final String SOUL_ONI = "QTE-M-WIND-37";     // 魂喰ラウ・オニ
    private static final String RAGE_ONI = "QTE-M-WIND-38";     // 暴レ狂ウ・オニ
    private static final String SHUTEN = "QTE-M-WIND-39";       // 天翔ケル霊鬼・シュテン

    /** 効果を持たない駒(スカイ・スワロー 1/1/1【速攻】)。破壊される役 */
    private static final String PAWN = "QTE-M-WIND-3";
    /** コスト2のミニオン(サイクロン・フェンサー)。透キ通ル・アヤカシの条件を満たす役 */
    private static final String COST2 = "QTE-M-WIND-5";
    /** 体力4のミニオン(嵐の守り手)。ダメージ量を測る役 */
    private static final String TOUGH = "QTE-M-WIND-19";

    @Autowired
    GameService game;

    @Autowired
    GameActions actions;

    @Autowired
    StatCalculator stats;

    @Autowired
    RuleGuards guards;

    @Autowired
    CardMasterRepository cards;

    private AutoGameFixture newGame() {
        return newGame(PLAIN_LEADER);
    }

    private AutoGameFixture newGame(String myLeaderId) {
        AutoGameFixture f = new AutoGameFixture(cards, myLeaderId, PLAIN_LEADER);
        f.fillDeck(f.me(), 20);
        f.fillDeck(f.you(), 20);
        return f;
    }

    // ==================================================================
    // 透キ通ル・アヤカシ(QTE-M-WIND-33)
    // 「自分の場にコスト2以上のミニオンが場に居るときこのカードのコストを0にする。
    //   ターンの終わりこのカードは破壊される【突進】」
    // ==================================================================

    @Test
    void 透キ通ルアヤカシは自分の場にコスト2以上が居ないと印刷コストのまま() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), PAWN); // コスト1なので条件を満たさない
        assertThat(stats.effectiveCost(f.state(), f.me(), f.card(SHEER))).isEqualTo(1);
    }

    @Test
    void 透キ通ルアヤカシは自分の場のコスト2以上でコスト0になる() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), COST2);
        assertThat(stats.effectiveCost(f.state(), f.me(), f.card(SHEER))).isEqualTo(0);
    }

    /** ★条件は「自分の場」である。相手の場のコスト2以上では下がらない */
    @Test
    void 透キ通ルアヤカシは相手の場のコスト2以上では下がらない() {
        AutoGameFixture f = newGame();
        f.putOnField(f.you(), COST2);
        assertThat(stats.effectiveCost(f.state(), f.me(), f.card(SHEER))).isEqualTo(1);
    }

    @Test
    void 透キ通ルアヤカシはターンの終わりに破壊されて墓地へ行く() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 1);
        int idx = f.giveHand(f.me(), SHEER);
        game.playCard(f.room(), "me", idx, List.of(), false);
        assertThat(f.fieldIds(f.me())).containsExactly(SHEER);

        game.endTurn(f.room(), "me");
        assertThat(f.fieldIds(f.me())).isEmpty();
        assertThat(f.me().getTrash()).contains(SHEER);
    }

    // ==================================================================
    // ハク霊(QTE-M-WIND-34) / コク霊(QTE-M-WIND-35)
    // 「【常在】ターンのはじめにこれを破壊する。これは攻撃できない
    //   【破壊時】(効果)、墓地から相方を出す」
    // ==================================================================

    @Test
    void ハク霊とコク霊は攻撃できない() {
        AutoGameFixture f = newGame();
        MinionInstance haku = f.putOnField(f.me(), HAKUREI);
        MinionInstance koku = f.putOnField(f.me(), KOKUREI);
        // リーダーへもミニオンへも攻撃できない
        assertThat(guards.minionAttackDenial(f.state(), f.me(), haku, true)).contains("ハク霊");
        assertThat(guards.minionAttackDenial(f.state(), f.me(), haku, false)).contains("ハク霊");
        assertThat(guards.minionAttackDenial(f.state(), f.me(), koku, true)).contains("コク霊");
        assertThat(guards.minionAttackDenial(f.state(), f.me(), koku, false)).contains("コク霊");
    }

    /**
     * ★裁定183 の本体。「ターンのはじめ」は<b>自分のターンのはじめだけ</b>である。
     * 相手のターンが始まっても自壊しない ―― ここが崩れると、
     * 自分のターンに出したハク霊は相手のターン開始時に消え、壁として一度も機能しない。
     */
    @Test
    void ハク霊は相手のターンのはじめには自壊しない() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), HAKUREI);
        f.me().getTrash().add(KOKUREI);

        game.endTurn(f.room(), "me"); // → 相手のターンが始まる

        assertThat(f.state().getTurnPlayerId()).isEqualTo("you");
        assertThat(f.fieldIds(f.me()))
                .as("相手のターンのはじめでは自壊しない(裁定183)")
                .containsExactly(HAKUREI);
    }

    @Test
    void ハク霊は自分のターンのはじめに自壊して1回復しコク霊を墓地から出す() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), HAKUREI);
        f.me().getTrash().add(KOKUREI);
        f.me().setLp(15);

        game.endTurn(f.room(), "me");   // 相手のターン
        game.endTurn(f.room(), "you");  // 自分のターンのはじめ

        assertThat(f.state().getTurnPlayerId()).isEqualTo("me");
        assertThat(f.fieldIds(f.me()))
                .as("ハク霊が自壊し、墓地からコク霊が出ている")
                .containsExactly(KOKUREI);
        assertThat(f.me().getTrash())
                .as("入れ替わったので、墓地にはハク霊が居てコク霊は居ない")
                .contains(HAKUREI).doesNotContain(KOKUREI);
        assertThat(f.me().getLp()).isEqualTo(16);
    }

    @Test
    void コク霊は自分のターンのはじめに自壊して相手に1ダメージを与えハク霊を出す() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), KOKUREI);
        f.me().getTrash().add(HAKUREI);

        game.endTurn(f.room(), "me");
        game.endTurn(f.room(), "you");

        assertThat(f.fieldIds(f.me())).containsExactly(HAKUREI);
        assertThat(f.you().getLp()).isEqualTo(19);
    }

    /**
     * ★相方が墓地に無ければ、自壊するだけで何も出てこない。
     * 「出す」が失敗しても【破壊時】の残りの効果(回復)は起きる。
     */
    @Test
    void ハク霊は墓地にコク霊が無ければ自壊するだけ() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), HAKUREI);
        f.me().setLp(15);

        game.endTurn(f.room(), "me");
        game.endTurn(f.room(), "you");

        assertThat(f.fieldIds(f.me())).isEmpty();
        assertThat(f.me().getLp()).isEqualTo(16);
    }

    /**
     * ★無限には増えない。ターン開始の反復は場のコピーを回すので、
     * その開始時に墓地から出てきたミニオンは、その開始時にはもう処理されない。
     * (2枚が場に並んでいても、1回のターン開始で起きる自壊はその2体分だけである。)
     */
    @Test
    void ターン開始の自壊は場のコピーを回すので出てきたミニオンは同じ開始で処理されない() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), HAKUREI);
        f.putOnField(f.me(), KOKUREI);
        f.me().getTrash().add(HAKUREI);
        f.me().getTrash().add(KOKUREI);

        game.endTurn(f.room(), "me");
        game.endTurn(f.room(), "you");

        // 2体が自壊し、墓地から2体が出てきて、そこで止まる
        assertThat(f.me().getMinionZone()).hasSize(2);
        assertThat(f.fieldIds(f.me())).containsExactlyInAnyOrder(HAKUREI, KOKUREI);
    }

    // ==================================================================
    // 喚ビ集ウ・アヤカシ(QTE-M-WIND-36)
    // 「【召喚時】自分の他のミニオンを1体破壊する。そうしたらカードを2枚引く。」
    // ==================================================================

    /**
     * ★候補が居なければ、召喚だけが済んで何も起きない。
     * ここを使用宣言時の対象指定にすると、<b>召喚そのものができなくなる</b>。
     */
    @Test
    void 喚ビ集ウアヤカシは他のミニオンが居なければ召喚できて何も起きない() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 2);
        int handBefore = f.me().getHand().size();
        int idx = f.giveHand(f.me(), GATHERING);

        game.playCard(f.room(), "me", idx, List.of(), false);

        assertThat(f.fieldIds(f.me())).containsExactly(GATHERING);
        assertThat(f.me().getPendingChoice()).isNull();
        assertThat(f.me().getHand()).hasSize(handBefore); // 出した1枚が減り、ドローは無い
    }

    @Test
    void 喚ビ集ウアヤカシは選んだ自分のミニオンを破壊して2枚引く() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 2);
        f.putOnField(f.me(), PAWN);
        int idx = f.giveHand(f.me(), GATHERING);

        game.playCard(f.room(), "me", idx, List.of(), false);
        assertThat(f.me().getPendingChoice())
                .as("破壊するミニオンの問い合わせが立つ")
                .isNotNull();
        assertThat(f.me().getPendingChoice().candidates())
                .as("候補は自分自身を含まない(『他の』ミニオン)")
                .hasSize(1);

        int handBefore = f.me().getHand().size();
        game.resolveChoice(f.room(), "me", List.of(0));

        assertThat(f.fieldIds(f.me())).containsExactly(GATHERING);
        assertThat(f.me().getTrash()).contains(PAWN);
        assertThat(f.me().getHand()).hasSize(handBefore + 2);
    }

    // ==================================================================
    // 魂喰ラウ・オニ(QTE-M-WIND-37) / 暴レ狂ウ・オニ(QTE-M-WIND-38)
    // ==================================================================

    @Test
    void 魂喰ラウオニは自分の他のミニオンを全て破壊しその数だけ相手リーダーを削る() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 5);
        f.putOnField(f.me(), PAWN);
        f.putOnField(f.me(), PAWN);
        f.putOnField(f.me(), PAWN);
        f.putOnField(f.you(), PAWN); // ★相手の場は巻き込まない
        int idx = f.giveHand(f.me(), SOUL_ONI);

        game.playCard(f.room(), "me", idx, List.of(), false);

        assertThat(f.fieldIds(f.me()))
                .as("自分自身は残る(『自分の他の』ミニオン)")
                .containsExactly(SOUL_ONI);
        assertThat(f.fieldIds(f.you()))
                .as("相手の場には手を出さない")
                .containsExactly(PAWN);
        assertThat(f.you().getLp()).isEqualTo(17); // 20 - 3
    }

    @Test
    void 魂喰ラウオニは破壊する相手が居なければリーダーを削らない() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 5);
        int idx = f.giveHand(f.me(), SOUL_ONI);

        game.playCard(f.room(), "me", idx, List.of(), false);

        assertThat(f.you().getLp()).isEqualTo(20);
    }

    /**
     * ★裁定184。「こうして破壊した数」は<b>相手のミニオン全員が受けるダメージ量</b>である
     * (体数ではない)。1体だけに集中させる読みと、全員に1ずつ配る読みの、どちらでもない。
     */
    @Test
    void 暴レ狂ウオニは相手のミニオンすべてに破壊した数のダメージを与える() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 5);
        f.putOnField(f.me(), PAWN);
        f.putOnField(f.me(), PAWN);
        MinionInstance a = f.putOnField(f.you(), TOUGH); // 体力4
        MinionInstance b = f.putOnField(f.you(), TOUGH);
        int idx = f.giveHand(f.me(), RAGE_ONI);

        game.playCard(f.room(), "me", idx, List.of(), false);

        assertThat(a.getCurrentHp())
                .as("2体破壊したので全員に2ダメージ(1体集中でも1ダメージずつでもない)")
                .isEqualTo(2);
        assertThat(b.getCurrentHp()).isEqualTo(2);
        assertThat(f.you().getLp()).isEqualTo(19); // その後リーダーに1
    }

    /** ★リーダーへの1ダメージは破壊数を条件にしていない。1体も破壊しなくても与える */
    @Test
    void 暴レ狂ウオニは1体も破壊しなくてもリーダーに1ダメージを与える() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 5);
        MinionInstance a = f.putOnField(f.you(), TOUGH);
        int idx = f.giveHand(f.me(), RAGE_ONI);

        game.playCard(f.room(), "me", idx, List.of(), false);

        assertThat(a.getCurrentHp()).as("0ダメージなので無傷").isEqualTo(4);
        assertThat(f.you().getLp()).isEqualTo(19);
    }

    // ==================================================================
    // 天翔ケル霊鬼・シュテン(QTE-M-WIND-39)
    // 「【特殊召喚】(このターンミニオンが8体以上破壊されていれば
    //   自分の手札からコスト1として出せる。)【速攻】」
    // ==================================================================

    /** ★裁定185。数えるのは両者の合計である。自分の分だけでは足りない */
    @Test
    void シュテンは両者合計8体の破壊で特殊召喚できる() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 1);
        int idx = f.giveHand(f.me(), SHUTEN);

        // 自分の場5体を破壊 —— まだ5体なので出せない
        for (int i = 0; i < 5; i++) {
            f.putOnField(f.me(), PAWN);
        }
        for (MinionInstance m : List.copyOf(f.me().getMinionZone())) {
            actions.destroyMinion(f.room(), f.me(), m);
        }
        assertThat(f.state().getMinionsDestroyedThisTurn()).isEqualTo(5);
        assertThatThrownBy(() -> game.specialSummon(f.room(), "me", idx, List.of()))
                .hasMessageContaining("特殊召喚の条件");

        // 相手の場3体を破壊 —— 合計8体になり、出せるようになる
        for (int i = 0; i < 3; i++) {
            f.putOnField(f.you(), PAWN);
        }
        for (MinionInstance m : List.copyOf(f.you().getMinionZone())) {
            actions.destroyMinion(f.room(), f.you(), m);
        }
        assertThat(f.state().getMinionsDestroyedThisTurn()).isEqualTo(8);

        game.specialSummon(f.room(), "me", idx, List.of());

        assertThat(f.fieldIds(f.me())).containsExactly(SHUTEN);
        assertThat(f.me().getAvailableMp()).as("コスト1を支払っている").isZero();
    }

    /** 出したターンから攻撃できる(【速攻】)。特殊召喚も召喚なので召喚酔いの対象である */
    @Test
    void シュテンは速攻を持つので出したターンにリーダーを攻撃できる() {
        AutoGameFixture f = newGame();
        assertThat(f.card(SHUTEN).hasKeyword(Keyword.HASTE)).isTrue();

        f.giveMana(f.me(), 1);
        int idx = f.giveHand(f.me(), SHUTEN);
        f.state().setMinionsDestroyedThisTurn(8);
        game.specialSummon(f.room(), "me", idx, List.of());

        MinionInstance shuten = f.me().getMinionZone().get(0);
        assertThat(shuten.getEnteredTurn()).isEqualTo(f.state().getTurnNumber());
        assertThat(guards.minionAttackDenial(f.state(), f.me(), shuten, true)).isNull();
    }

    /** カウンタはターンをまたがない。ターンが始まったら0に戻る */
    @Test
    void 破壊数のカウンタはターンの開始で0に戻る() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), PAWN);
        actions.destroyMinion(f.room(), f.me(), f.me().getMinionZone().get(0));
        assertThat(f.state().getMinionsDestroyedThisTurn()).isEqualTo(1);

        game.endTurn(f.room(), "me");

        assertThat(f.state().getMinionsDestroyedThisTurn()).isZero();
    }

    // ==================================================================
    // 妖ノ長・ストク(QTE-M-WIND-29)
    // 「【常在】ターンに1回自分のミニオンが破壊されたとき自分のリーダーの体力を2回復する」
    // ==================================================================

    @Test
    void ストクは自分のミニオンの破壊で2回復するがターンに1回だけ() {
        AutoGameFixture f = newGame(STOK);
        f.me().setLp(10);
        f.putOnField(f.me(), PAWN);
        f.putOnField(f.me(), PAWN);

        actions.destroyMinion(f.room(), f.me(), f.me().getMinionZone().get(0));
        assertThat(f.me().getLp()).isEqualTo(12);

        actions.destroyMinion(f.room(), f.me(), f.me().getMinionZone().get(0));
        assertThat(f.me().getLp())
                .as("同じターンの2体目では回復しない")
                .isEqualTo(12);
    }

    /**
     * ★裁定156(3)。「ターンに1回」は<b>毎ターンリセット</b>される ——
     * 自分のターンで1回、相手のターンで1回である。
     * 真偽値のターン内フラグで持つと、{@code startTurnReset} がターンプレイヤーにしか
     * 走らないため、相手のターンで回復できないままになる。
     */
    @Test
    void ストクの回復は相手のターンにも1回使える() {
        AutoGameFixture f = newGame(STOK);
        f.me().setLp(10);
        f.putOnField(f.me(), PAWN);
        f.putOnField(f.me(), PAWN);

        actions.destroyMinion(f.room(), f.me(), f.me().getMinionZone().get(0));
        assertThat(f.me().getLp()).isEqualTo(12);

        game.endTurn(f.room(), "me"); // 相手のターンへ
        assertThat(f.state().getTurnPlayerId()).isEqualTo("you");

        actions.destroyMinion(f.room(), f.me(), f.me().getMinionZone().get(0));
        assertThat(f.me().getLp())
                .as("相手のターンでも1回は回復する(裁定156(3))")
                .isEqualTo(14);
    }

    /** 相手のミニオンが破壊されても回復しない(「自分の」ミニオンと書いてある) */
    @Test
    void ストクは相手のミニオンの破壊では回復しない() {
        AutoGameFixture f = newGame(STOK);
        f.me().setLp(10);
        f.putOnField(f.you(), PAWN);

        actions.destroyMinion(f.room(), f.you(), f.you().getMinionZone().get(0));

        assertThat(f.me().getLp()).isEqualTo(10);
    }
}
