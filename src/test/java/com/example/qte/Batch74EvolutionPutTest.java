package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.effect.TargetChoice;
import com.example.qte.game.GameService;
import com.example.qte.game.ManaCard;
import com.example.qte.game.MinionInstance;
import com.example.qte.game.PlayerState;
import com.example.qte.game.TurnPhase;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.CardType;
import com.example.qte.support.AutoGameFixture;

/**
 * Batch 74。<b>進化ミニオンの一族13枚</b>(73 の裁定依頼 A・裁定341)の試験。
 *
 * <h2>何が起きていたのか</h2>
 *
 * 総合ルール2-1 と裁定310 により<b>進化ミニオンはミニオンの一種である</b>。
 * ところが「カードから場へ出す」13枚の絞り込みは {@code == CardType.MINION} で書かれており、
 * <b>進化が候補から落ちていた</b> —— 手札4枚・墓地5枚・マナ4枚である。
 *
 * <p>★<b>これは種別の読み違いではなく、裁定226 を守るための暫定処置だった。</b>
 * 「効果による『場に出す』でも進化素材は要る」(裁定226)のに、
 * Batch 68 が素材を選ばせる仕組みを作ったのは<b>手札からの経路だけ</b>だった。
 * 墓地・マナ・山札には素材を確保する口が無く、進化を候補に入れると
 * <b>素材ゼロで場に立つ</b>。裁定308(b) はその形を「暫定である」と明記しており、
 * ★<b>73 が見つけたのは「その暫定が13枚ぶん残っていた」ということである</b>。
 *
 * <h2>74 が直した形</h2>
 *
 * <b>13枚を個別に直していない。</b>直したのは3種類だけである(裁定130)。
 *
 * <ol>
 * <li>{@code TargetCandidates} の {@code Filter.MINION_CARD} を {@code isMinion()} へ(1行)。
 *     {@code battle.js} の写しも2箇所直した(裁定195)。</li>
 * <li>各経路の合流点 —— {@code effectPutSequence}(手札・山札)・
 *     {@code requestManaSummon}(マナ)・{@code resolveTrashRevive}(墓地)を
 *     <b>出どころ非依存の1本</b>に寄せ、進化なら素材を問う割り込みを挟むようにした。</li>
 * <li>{@code GameActions} の2つの入口({@code reviveFromGrave} /
 *     {@code putManaCardIntoField})に<b>素材を受ける口</b>を作った。</li>
 * </ol>
 *
 * <h2>★★★「出す」と「召喚する」で素材の選び方が違う</h2>
 *
 * <table border="1">
 *   <caption>素材をいつ選ぶか</caption>
 *   <tr><th>経路</th><th>素材を選ぶ時点</th><th>【召喚時】</th></tr>
 *   <tr><td>手札からの進化召喚 / 特殊召喚 / 禁忌</td><td><b>使用宣言のとき</b></td><td>発動する</td></tr>
 *   <tr><td>《黄泉の召喚主》の墓地からの<b>召喚</b></td><td><b>使用宣言のとき</b>(★74)</td><td>発動する</td></tr>
 *   <tr><td>効果による「出す」(手札)</td><td>解決中の割り込み</td><td>発動する(裁定311)</td></tr>
 *   <tr><td>効果による「出す」(墓地・マナ・山札)</td><td>解決中の割り込み(★74)</td><td>発動しない</td></tr>
 * </table>
 *
 * <p>★<b>盤面が動いた後でなければ候補が決まらない</b>のが、割り込みにした理由である。
 *
 * <h2>測り方</h2>
 *
 * {@link AutoGameFixture} の上に書き、本物の入口から起こす(裁定187)。
 * ★<b>「進化が出せること」と「素材が無ければ出ないこと」を必ず対で測る</b> ——
 * 前者だけだと、裁定226 を壊しても緑のままになる。
 */
@SpringBootTest
class Batch74EvolutionPutTest {

    /** 常在効果を持たないリーダー(蒼海の賢者)。既定の対戦相手 */
    private static final String PLAIN_LEADER = "QTE-M-WATER-1";
    /** 黄泉の召喚主(闇・リーダー)。サブフェイズに墓地からミニオンを召喚できる */
    private static final String GRAVE_SUMMONER = "QTE-M-DARK-15";

    // ---- 素材条件がいちばん緩い進化(試験の道具として使う) ----
    /** 飛翔鉄人走太(火・進化・3/1/1)。【進化】(ミニオン1体)—— どのミニオンでも素材にできる */
    private static final String ANY_EVOLUTION = "QTE-M-FIRE-32";
    /** 裏雷怒乗込(土・進化・3/1/5)。【進化】(土文明のミニオン1体) */
    private static final String EARTH_EVOLUTION = "QTE-M-EARTH-31";
    /** 英霊・コレキ(光・進化・2/2/2)。【進化】(光文明のミニオン1体) */
    private static final String LIGHT_EVOLUTION = "QTE-M-LIGHT-31";
    /** 英霊・タイガラム(光・進化・7・【守護】)。【進化】(自分の守護を持つ光文明のミニオン1体) */
    private static final String GUARD_EVOLUTION = "QTE-M-LIGHT-32";
    /** 海淵獣シラーカ(水・進化・3/2/2)。【進化】(水文明の潜伏を持たないミニオン1体) */
    private static final String WATER_EVOLUTION = "QTE-M-WATER-30";

    // ---- 13枚のうち、経路ごとに1枚ずつ測る ----
    private static final String GIGAMOUSE = "QTE-M-WATER-38";     // ギガマウス・バイト(手札・宣言時)
    private static final String SCARELOCK = "QTE-M-LIGHT-39";     // 英術・スケアロック(手札・宣言時)
    private static final String RAISE_DEAD = "QTE-M-DARK-12";     // 死者蘇生(墓地・割り込み)
    private static final String TABOO_PRICE = "QTE-M-DARK-10";    // 禁忌の代償(墓地・宣言時)
    private static final String KENKAJOTO = "QTE-M-EARTH-38";     // 喧嘩上等(マナ)
    private static final String MISSIONARY = "QTE-M-LIGHT-22";    // 降臨の伝道師(山札)

    // ---- 素材や物差しに使う、効果を持たないミニオン ----
    /** ゴーレム・ウォール(土・3/1/5・【守護】) */
    private static final String EARTH_MINION = "QTE-M-EARTH-2";
    /** 煌めきの盾(光・1/0/4・【守護】)。光の【守護】ミニオン */
    private static final String LIGHT_GUARD = "QTE-M-LIGHT-16";
    /** 海獣タウギーナ(水・1/1/1・【潜伏】) */
    private static final String SEA_BEAST = "QTE-M-WATER-33";
    /** 急流の狙撃手(水・2/2/1・【知識】【貫通】)。<b>潜伏を持たない</b>水ミニオン */
    private static final String WATER_MINION = "QTE-M-WATER-16";
    /** スカイ・スワロー(風・1/1/1・【速攻】) */
    private static final String SKY_SWALLOW = "QTE-M-WIND-3";
    /** マグマ・ストレート(火・スペル・1)。マナの中身の物差し */
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
        f.fillDeck(f.me(), 40);
        f.fillDeck(f.you(), 40);
        return f;
    }

    private void payMana(PlayerState player, int count) {
        for (int i = 0; i < count; i++) {
            player.getManaZone().add(new ManaCard(MAGMA, false));
        }
    }

    private static TargetChoice none() {
        return new TargetChoice(null, null, null, null, null);
    }

    private static TargetChoice hand(int... indexes) {
        List<Integer> list = new java.util.ArrayList<>();
        for (int i : indexes) {
            list.add(i);
        }
        return new TargetChoice(list, null, null, null, null);
    }

    private static TargetChoice trash(int... indexes) {
        List<Integer> list = new java.util.ArrayList<>();
        for (int i : indexes) {
            list.add(i);
        }
        return new TargetChoice(null, null, null, list, null);
    }

    private static TargetChoice minions(String... instanceIds) {
        return new TargetChoice(null, List.of(instanceIds), null, null, null);
    }

    // ==================================================================
    // 0. 大前提 —— 進化ミニオンはミニオンである
    // ==================================================================

    @Test
    @DisplayName("★★★18枚の進化ミニオンはすべて CardType.isMinion() が真である(裁定310)")
    void 進化ミニオンはミニオンである() {
        List<String> evolutions = java.util.Arrays
                .stream(com.example.qte.master.Civilization.values())
                .flatMap(civ -> cards.findByCivilization(civ).stream())
                .filter(c -> c.type() == CardType.EVOLUTION)
                .map(c -> c.id())
                .toList();
        assertThat(evolutions).as("Ver1.1 の進化ミニオンは18枚である").hasSize(18);
        assertThat(evolutions).allSatisfy(id ->
                assertThat(cards.findById(id).type().isMinion())
                        .as("【%s】はミニオンである", id).isTrue());
    }

    // ==================================================================
    // 1. 手札 —— 宣言時に選ぶ経路(《ギガマウス・バイト》QTE-M-WATER-38)
    //    「手札から水文明のミニオンを3体場に出す」
    // ==================================================================

    @Test
    @DisplayName("★★★ギガマウス・バイトは手札の進化ミニオンも出せる(素材を問う)")
    void ギガマウスは進化も出せる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 15);
        // ★《海淵獣シラーカ》の素材は「水文明の<b>潜伏を持たない</b>ミニオン1体」である
        MinionInstance material = f.putOnField(f.me(), WATER_MINION);
        int evolutionIndex = f.giveHand(f.me(), WATER_EVOLUTION);
        int hand = f.giveHand(f.me(), GIGAMOUSE);

        game.playCard(f.room(), "me", hand, List.of(hand(evolutionIndex)), false);

        assertThat(f.me().getPendingChoice())
                .as("★進化なので素材を問う(裁定226)").isNotNull();
        f.answerChoice(game, "me", material.getInstanceId());

        assertThat(f.fieldIds(f.me()))
                .as("★73 までは候補にすら入らなかった").containsExactly(WATER_EVOLUTION);
        assertThat(f.me().getMinionZone().get(0).getUnder())
                .as("★素材は下に入る(進化召喚と同じ束になる)").hasSize(1);
    }

    @Test
    @DisplayName("★★素材が場に居なければ、進化は場に出ない(裁定226 を守る)")
    void ギガマウスは素材が無ければ進化を出せない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 15);
        // 素材になる水文明のミニオンを場に置かない
        int evolutionIndex = f.giveHand(f.me(), WATER_EVOLUTION);
        int hand = f.giveHand(f.me(), GIGAMOUSE);

        game.playCard(f.room(), "me", hand, List.of(hand(evolutionIndex)), false);

        assertThat(f.me().getPendingChoice()).as("問う相手が居ない").isNull();
        assertThat(f.fieldIds(f.me()))
                .as("★★素材ゼロで場に立ってはいけない(73 が《降臨の伝道師》で見つけた形)")
                .isEmpty();
        assertThat(f.me().getHand())
                .as("出せなかったカードは手札へ戻る").contains(WATER_EVOLUTION);
    }

    @Test
    @DisplayName("通常のミニオンはこれまでどおり【突進】を得て出る(壊していないことの確認)")
    void ギガマウスは通常のミニオンをこれまでどおり出す() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 15);
        int a = f.giveHand(f.me(), SEA_BEAST);
        int hand = f.giveHand(f.me(), GIGAMOUSE);

        game.playCard(f.room(), "me", hand, List.of(hand(a)), false);

        assertThat(f.fieldIds(f.me())).containsExactly(SEA_BEAST);
        assertThat(f.me().getMinionZone().get(0)
                .hasKeyword(com.example.qte.master.Keyword.RUSH))
                .as("【突進】の付与は afterOnePutByEffect が持つ").isTrue();
    }

    // ==================================================================
    // 2. 手札 —— 2段構えの経路(《英術・スケアロック》QTE-M-LIGHT-39)
    //    「光文明のコスト3以下のミニオンを1体出す。その後【進化】を1体出す」
    // ==================================================================

    @Test
    @DisplayName("★★★スケアロックは1体目にも進化を選べる(素材の割り込みを跨いで2体目へ進む)")
    void スケアロックは1体目に進化を選べる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 15);
        MinionInstance material = f.putOnField(f.me(), LIGHT_GUARD); // 光文明・素材になる
        int firstIndex = f.giveHand(f.me(), LIGHT_EVOLUTION);        // 光・進化・コスト2
        int hand = f.giveHand(f.me(), SCARELOCK);

        game.playCard(f.room(), "me", hand, List.of(hand(firstIndex)), false);

        assertThat(f.me().getPendingChoice()).as("1体目の素材を問う").isNotNull();
        f.answerChoice(game, "me", material.getInstanceId());

        assertThat(f.fieldIds(f.me())).containsExactly(LIGHT_EVOLUTION);
        // ★2体目(手札の【進化】光文明)を問う段は、素材の割り込みの<b>後</b>に来なければならない
        assertThat(f.me().getPendingChoice())
                .as("★★1体目が進化でも、2体目を問う段はちゃんと来る"
                        + "(効果の本文側に置いたままだと『1体目を出す前に2体目を問う』ことになる)")
                .isNull();  // 手札に【進化】の光ミニオンがもう無いので、問いは立たない
    }

    // ==================================================================
    // 3. 墓地 —— 割り込みで選ぶ経路(《死者蘇生》QTE-M-DARK-12)
    // ==================================================================

    @Test
    @DisplayName("★★★死者蘇生は墓地の進化ミニオンも蘇生できる(素材を問う)")
    void 死者蘇生は進化も蘇生できる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 15);
        MinionInstance material = f.putOnField(f.me(), SKY_SWALLOW);
        f.me().getTrash().add(ANY_EVOLUTION);   // 【進化】(ミニオン1体)
        int hand = f.giveHand(f.me(), RAISE_DEAD);

        // 生贄は選ばない(コスト軽減なし)
        game.playCard(f.room(), "me", hand, List.of(none()), false);

        // 墓地の候補は1体だけなので蘇生対象は自動決定 → そのまま素材を問う
        assertThat(f.me().getPendingChoice())
                .as("★進化なので素材を問う").isNotNull();
        f.answerChoice(game, "me", material.getInstanceId());

        assertThat(f.fieldIds(f.me())).containsExactly(ANY_EVOLUTION);
        assertThat(f.me().getTrash()).doesNotContain(ANY_EVOLUTION);
        assertThat(f.me().getMinionZone().get(0)
                .hasKeyword(com.example.qte.master.Keyword.RUSH))
                .as("★死者蘇生は【突進】を付ける —— 進化でも同じである").isTrue();
    }

    @Test
    @DisplayName("★★素材が場に居なければ、墓地の進化は場に出ない(裁定226 を守る)")
    void 死者蘇生は素材が無ければ進化を出せない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 15);
        f.me().getTrash().add(ANY_EVOLUTION);
        int hand = f.giveHand(f.me(), RAISE_DEAD);

        game.playCard(f.room(), "me", hand, List.of(none()), false);

        assertThat(f.me().getPendingChoice()).isNull();
        assertThat(f.me().getMinionZone()).isEmpty();
        assertThat(f.me().getTrash())
                .as("★出せなかったのだから、墓地に残っていなければならない")
                .contains(ANY_EVOLUTION);
    }

    // ==================================================================
    // 4. 墓地 —— 宣言時に選ぶ経路(《禁忌の代償》QTE-M-DARK-10)
    // ==================================================================

    @Test
    @DisplayName("★★禁忌の代償は墓地の進化ミニオン(コスト4以下)も候補にする")
    void 禁忌の代償は進化も出せる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 15);
        f.me().getManaZone().get(0).turnFaceDown();   // 代償にする裏向きマナ
        MinionInstance material = f.putOnField(f.me(), SKY_SWALLOW);
        f.me().getTrash().add(ANY_EVOLUTION);         // コスト3
        int hand = f.giveHand(f.me(), TABOO_PRICE);

        game.playCard(f.room(), "me", hand, List.of(trash(0)), false);

        assertThat(f.me().getPendingChoice()).isNotNull();
        f.answerChoice(game, "me", material.getInstanceId());

        assertThat(f.fieldIds(f.me())).containsExactly(ANY_EVOLUTION);
    }

    // ==================================================================
    // 5. マナ(《喧嘩上等》QTE-M-EARTH-38)
    // ==================================================================

    @Test
    @DisplayName("★★★喧嘩上等はマナの進化ミニオン(コスト6以下)も場に出せる")
    void 喧嘩上等は進化も出せる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 15);
        f.me().getManaZone().add(new ManaCard(EARTH_EVOLUTION, false)); // 土・進化・コスト3
        MinionInstance material = f.putOnField(f.me(), EARTH_MINION);   // 土文明・素材になる
        int hand = f.giveHand(f.me(), KENKAJOTO);

        game.playCard(f.room(), "me", hand, List.of(none()), false);

        assertThat(f.me().getPendingChoice())
                .as("★マナの候補は進化1枚だけ(マグマはスペル)なので自動決定 → 素材を問う")
                .isNotNull();
        f.answerChoice(game, "me", material.getInstanceId());

        assertThat(f.fieldIds(f.me())).containsExactly(EARTH_EVOLUTION);
        assertThat(f.me().getManaZone())
                .as("★出したカードはマナゾーンから消える")
                .noneMatch(m -> EARTH_EVOLUTION.equals(m.getCardId()));
    }

    @Test
    @DisplayName("★★素材が場に居なければ、マナの進化は場に出ない(裁定226 を守る)")
    void 喧嘩上等は素材が無ければ進化を出せない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 15);
        f.me().getManaZone().add(new ManaCard(EARTH_EVOLUTION, false));
        int hand = f.giveHand(f.me(), KENKAJOTO);

        game.playCard(f.room(), "me", hand, List.of(none()), false);

        assertThat(f.me().getMinionZone()).isEmpty();
        assertThat(f.me().getManaZone())
                .as("★出せなかったのだから、マナに残っていなければならない")
                .anyMatch(m -> EARTH_EVOLUTION.equals(m.getCardId()));
    }

    // ==================================================================
    // 6. 山札(《降臨の伝道師》QTE-M-LIGHT-22)
    //    ★73 が「暫定」として進化を弾いた場所である
    // ==================================================================

    @Test
    @DisplayName("★★★降臨の伝道師は、素材が居るなら【守護】の進化ミニオンも場に出す")
    void 降臨の伝道師は素材があれば進化も出す() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 15);
        MinionInstance material = f.putOnField(f.me(), LIGHT_GUARD); // 光の【守護】= 素材になる
        f.me().getDeck().clear();
        f.me().getDeck().addLast(GUARD_EVOLUTION);  // 英霊・タイガラム(光・【守護】・進化)
        f.fillDeck(f.me(), 20);
        int hand = f.giveHand(f.me(), MISSIONARY);

        game.playCard(f.room(), "me", hand, List.of(), false);

        assertThat(f.me().getPendingChoice())
                .as("★公開した4枚に【守護】は1体だけなので出す体は自動決定 → 素材を問う")
                .isNotNull();
        f.answerChoice(game, "me", material.getInstanceId());

        assertThat(f.fieldIds(f.me()))
                .as("★73 は暫定でここを塞いでいた")
                .containsExactlyInAnyOrder(MISSIONARY, GUARD_EVOLUTION);
        assertThat(f.me().getMinionZone().stream()
                .filter(m -> GUARD_EVOLUTION.equals(m.getMaster().id()))
                .findFirst().orElseThrow().getDamage())
                .as("★出したミニオンは3ダメージを受ける —— 割り込みを跨いでも同じである")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("★★★素材が場に居なければ、進化は公開の候補にすら入らない(73 の暫定と同じ結果)")
    void 降臨の伝道師は素材が無ければ進化を候補にしない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 15);
        // ★《裏雷怒乗込》は【守護】を持つ<b>土文明</b>の進化であり、素材は「土文明のミニオン1体」。
        //   自分の場は空(《降臨の伝道師》自身は光文明なので素材にならない)なので、
        //   素材を確保できず候補に入らない。
        //   ★★<b>タイガラムを使うとこの試験は成立しない</b> ——
        //     《降臨の伝道師》自身が【守護】を持つ光文明のミニオンであり、
        //     場に出た自分自身が素材になってしまう。
        //   ★★★<b>「候補に入らない」ことは、それだけでは観測できない</b>(74 の壊し検証で分かった)——
        //     候補に入れても、出す段で素材が足りずに不発になるので<b>結果が同じ</b>になる。
        //     観測できるのは<b>他に出せる【守護】が居るとき</b>である ——
        //     候補が1体なら自動決定、2体なら問い合わせが立つ。
        //     <b>「壊しても落ちない」を見たら、まず番人が何を見ているかを疑う</b>(11 の形)。
        f.me().getDeck().clear();
        f.me().getDeck().addLast(EARTH_EVOLUTION);   // 素材が確保できない進化
        //   ★<b>体力5の《ゴーレム・ウォール》を使う。</b>本文の3ダメージで壊れる体力だと、
        //     出したこと自体が場から消えてしまい何を測っているか分からなくなる
        //     (《煌めきの盾》は体力3なので、この試験には使えない)。
        f.me().getDeck().addLast(EARTH_MINION);      // ふつうの【守護】ミニオン(体力5)
        f.fillDeck(f.me(), 20);
        int hand = f.giveHand(f.me(), MISSIONARY);

        game.playCard(f.room(), "me", hand, List.of(), false);

        assertThat(f.me().getPendingChoice())
                .as("★★候補は【ゴーレム・ウォール】1体だけなので問わない —— "
                        + "素材を確保できない進化を候補に入れると、ここで問い合わせが立つ(裁定308(b))")
                .isNull();
        assertThat(f.fieldIds(f.me()))
                .as("★手札から選ばせる経路と同じ規則である")
                .containsExactlyInAnyOrder(MISSIONARY, EARTH_MINION);
    }

    // ==================================================================
    // 7. 墓地からの「召喚」(《黄泉の召喚主》QTE-M-DARK-15)
    //    ★★★これだけは「出す」ではなく<b>召喚</b>である —— 素材は宣言のときに選ぶ
    // ==================================================================

    @Test
    @DisplayName("★★★黄泉の召喚主は墓地から進化ミニオンを召喚できる(素材は宣言のときに選ぶ)")
    void 黄泉の召喚主は進化を召喚できる() {
        AutoGameFixture f = newGame(GRAVE_SUMMONER);
        payMana(f.me(), 15);
        f.state().setPhase(TurnPhase.SUB);
        MinionInstance material = f.putOnField(f.me(), SKY_SWALLOW);
        f.me().getTrash().add(ANY_EVOLUTION);

        game.summonFromGrave(f.room(), "me", 0, List.of(),
                List.of(material.getInstanceId()));

        assertThat(f.fieldIds(f.me()))
                .as("★本文は「ミニオンを墓地から召喚してもよい」である")
                .containsExactly(ANY_EVOLUTION);
        assertThat(f.me().getMinionZone().get(0).getUnder())
                .as("★素材は下に入る").hasSize(1);
        assertThat(f.me().getTrash()).doesNotContain(ANY_EVOLUTION);
    }

    @Test
    @DisplayName("★★素材を送ってこなければ、墓地からの進化召喚は成立しない(裁定226 を守る)")
    void 黄泉の召喚主は素材無しでは進化を召喚できない() {
        AutoGameFixture f = newGame(GRAVE_SUMMONER);
        payMana(f.me(), 15);
        f.state().setPhase(TurnPhase.SUB);
        f.putOnField(f.me(), SKY_SWALLOW);
        f.me().getTrash().add(ANY_EVOLUTION);

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> game.summonFromGrave(f.room(), "me", 0, List.of(), List.of()))
                .as("★素材の検証は resolveMaterials 1本である(通常の進化召喚と同じ)")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("進化素材");
        assertThat(f.me().getTrash()).contains(ANY_EVOLUTION);
    }

    @Test
    @DisplayName("通常のミニオンはこれまでどおり墓地から召喚できる(壊していないことの確認)")
    void 黄泉の召喚主は通常のミニオンを召喚できる() {
        AutoGameFixture f = newGame(GRAVE_SUMMONER);
        payMana(f.me(), 15);
        f.state().setPhase(TurnPhase.SUB);
        f.me().getTrash().add(SKY_SWALLOW);

        game.summonFromGrave(f.room(), "me", 0, List.of());

        assertThat(f.fieldIds(f.me())).containsExactly(SKY_SWALLOW);
    }

    // ==================================================================
    // 8. 場が満杯でも進化は出せる(素材を取り除いてその上に乗るため)
    // ==================================================================

    @Test
    @DisplayName("★★場が満杯でも、効果で出す進化ミニオンは素材の上に乗れる")
    void 場が満杯でも効果で出す進化は乗れる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 15);
        MinionInstance material = null;
        for (int i = 0; i < PlayerState.DEFAULT_MINION_ZONE_LIMIT; i++) {
            material = f.putOnField(f.me(), SKY_SWALLOW);
        }
        assertThat(f.me().isMinionZoneFull()).isTrue();
        f.me().getTrash().add(ANY_EVOLUTION);
        int hand = f.giveHand(f.me(), RAISE_DEAD);

        game.playCard(f.room(), "me", hand, List.of(none()), false);
        assertThat(f.me().getPendingChoice()).isNotNull();
        f.answerChoice(game, "me", material.getInstanceId());

        assertThat(f.fieldIds(f.me()))
                .as("★素材1体が場から抜けて進化1体が乗るので、体数は変わらない")
                .hasSize(PlayerState.DEFAULT_MINION_ZONE_LIMIT)
                .contains(ANY_EVOLUTION);
    }

    // ==================================================================
    // 9. 「ミニオンでないカード」は今までどおり弾かれる(広げすぎていないことの確認)
    // ==================================================================

    @Test
    @DisplayName("★墓地のスペルは、進化を通したあとも召喚できないままである")
    void 墓地のスペルは召喚できない() {
        AutoGameFixture f = newGame(GRAVE_SUMMONER);
        payMana(f.me(), 15);
        f.state().setPhase(TurnPhase.SUB);
        f.me().getTrash().add(MAGMA);   // スペル

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> game.summonFromGrave(f.room(), "me", 0, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ミニオン");
    }

    @Test
    @DisplayName("★★禁忌の代償は、進化を通したあとも墓地のスペルを選べないままである")
    void 禁忌の代償は墓地のスペルを選べない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 15);
        f.me().getManaZone().get(0).turnFaceDown();
        f.me().getTrash().add(MAGMA);          // スペル
        f.me().getTrash().add(ANY_EVOLUTION);  // 進化ミニオン(選べる側)
        int hand = f.giveHand(f.me(), TABOO_PRICE);

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> game.playCard(f.room(), "me", hand, List.of(trash(0)), false))
                .as("★MINION_CARD は「ミニオンの一種か」を見る規則であって、"
                        + "「何も見ない」ではない(裁定341 で広げすぎていないことの証拠)")
                .hasMessageContaining("ミニオンカード");
    }

    @Test
    @DisplayName("★マナのスペルは、進化を通したあとも場に出せないままである")
    void マナのスペルは場に出せない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 15);   // 中身はすべてマグマ(スペル)
        int hand = f.giveHand(f.me(), KENKAJOTO);

        game.playCard(f.room(), "me", hand, List.of(none()), false);

        assertThat(f.me().getMinionZone())
                .as("★候補が0枚なので不発である(裁定302)").isEmpty();
    }
}
