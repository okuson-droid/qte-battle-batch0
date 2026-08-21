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
import com.example.qte.game.PlayerState;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.Keyword;
import com.example.qte.support.AutoGameFixture;

/**
 * Batch 58(作り直し③ = 区分5「ほぼ書き直し」)の挙動の試験。
 *
 * <h2>この試験が測っているもの</h2>
 *
 * {@code notes/rework-triage.md} の区分5(15枚)のうち、<b>裁定を要さない8枚</b>の
 * 新しい挙動である。裁定268〜274 の回答待ちの7枚(フレア・ポーン・神風の大号令・
 * 英知の水晶・創世神 ゾディアックアイリス・大天使 ミカエル・マナを貪る怨霊・地響きの槌)には
 * 着手していない(裁定184)。
 *
 * <h2>旧本文を測っていた既存の試験は1件だけだった</h2>
 *
 * 8枚のうち、Ver0.4 の挙動を測っていた既存の試験は
 * {@code EffectImplementationTest.効果未実装のカードは剛火の将だけである} の1件である
 * (常在効果が実装されたので、0枚を測る形へ書き換えた)。
 * 残りの7枚は<b>そもそも測られていなかった</b> —— 「落ちなかった = 変えていない」ではない、
 * という rework-triage.md 5章の注意がそのまま当てはまる。
 *
 * <h2>本物の入口を通す</h2>
 *
 * {@link AutoGameFixture} の上に書き、効果は {@code GameService.playCard} /
 * {@code specialSummon} / {@code resolveChoice} から起こす(裁定187)。
 */
@SpringBootTest
class Batch58ReworkTest {

    /** 常在効果を持たないリーダー(蒼海の賢者)。既定の対戦相手 */
    private static final String PLAIN_LEADER = "QTE-M-WATER-1";
    /** 剛火の将(リーダー)。Ver1.1 の本文は常在の「【速攻】持ちのHP+2」1行だけ */
    private static final String FIRE_GENERAL = "QTE-M-FIRE-1";

    private static final String LAST_STAND_PYROMANCER = "QTE-M-FIRE-7";  // 背水の烈火使い
    private static final String WISDOM_HEIR = "QTE-M-WATER-19";          // 英知の継承者
    private static final String WISDOM_WINGS = "QTE-M-WATER-22";         // 知恵の双翼
    private static final String STORM_KAISER = "QTE-M-WIND-8";           // ストーム・カイザー
    private static final String RICOCHET = "QTE-M-WIND-24";              // 風弾の跳弾
    private static final String CURSE_BONE = "QTE-M-DARK-2";             // カース・ボーン
    private static final String LEYLINE = "QTE-M-EARTH-27";              // 地脈の覚醒

    /** スカイ・スワロー(1/1/1・【速攻】)。剛火の将の常在を測る物差し */
    private static final String SKY_SWALLOW = "QTE-M-WIND-3";
    /** 赫灼の重戦士(4/4/4)。【召喚時】に条件を満たすと<b>後から</b>【速攻】を得る */
    private static final String CRIMSON_HEAVY = "QTE-M-FIRE-5";
    /** フレア・ポーン(1/2/1・キーワードなし)。道具として使う */
    private static final String PLAIN_MINION = "QTE-M-FIRE-2";
    /** アクア・ジェリー(1/1/1・【知識】)。知恵の双翼の代替コスト用 */
    private static final String KNOWLEDGE_JELLY = "QTE-M-WATER-2";
    /** マグマ・ストレート(スペル)。マナの中身として使う(ミニオン候補を汚さない) */
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

    /** スペルだけでマナを作る(マナの中身が対象候補に紛れ込まないようにする) */
    private void payMana(PlayerState player, int count) {
        for (int i = 0; i < count; i++) {
            player.getManaZone().add(new ManaCard(MAGMA, false));
        }
    }

    private static TargetChoice none() {
        return new TargetChoice(null, null, null, null, null);
    }

    private static TargetChoice minions(String... instanceIds) {
        return new TargetChoice(null, List.of(instanceIds), null, null, null);
    }

    // ==================================================================
    // 火文明
    // ==================================================================

    // ---- 剛火の将(QTE-M-FIRE-1・リーダー・区分5) ----
    // 旧: 「起動能力(1ターンに1回): 自分のライフを2減らす。このターン中、次に手札から使用する
    //      火文明ミニオンのコストを-1する(0にはならない)。場にある【速攻】を持つカードのHPを+2する。」
    // 新: 「場にある【速攻】を持つカードのHPを+2する」
    // → 起動能力が丸ごと消え(登録は Batch 55 で削除済み)、常在効果だけが残った。
    //   規則の正は StatCalculator.rushHpBonus、加算は MinionInstance.getMaxHp。

    @Test
    void 剛火の将は速攻を持つミニオンのHPを2上げる() {
        AutoGameFixture f = newGame(FIRE_GENERAL);
        MinionInstance rush = f.putOnField(f.me(), SKY_SWALLOW);
        MinionInstance plain = f.putOnField(f.me(), PLAIN_MINION);

        assertThat(f.card(SKY_SWALLOW).hp()).as("印刷値は1のまま").isEqualTo(1);
        assertThat(rush.getMaxHp()).as("【速攻】持ちは+2される").isEqualTo(3);
        assertThat(plain.getMaxHp())
                .as("【速攻】を持たないミニオンは印刷値のまま")
                .isEqualTo(f.card(PLAIN_MINION).hp());
    }

    /**
     * ★<b>「自分の」と書いていないので相手の場にも効く</b>(裁定156(2))。
     * 旧台帳の注記にも「対象範囲は記法規約どおり両者参照」とあり、Ver1.1 でもその文は変わっていない。
     */
    @Test
    void 剛火の将の常在は相手の速攻ミニオンにも効く() {
        AutoGameFixture f = newGame(FIRE_GENERAL);
        MinionInstance mine = f.putOnField(f.me(), SKY_SWALLOW);
        MinionInstance theirs = f.putOnField(f.you(), SKY_SWALLOW);

        assertThat(mine.getMaxHp()).isEqualTo(3);
        assertThat(theirs.getMaxHp()).as("相手の【速攻】持ちにも同じだけ乗る").isEqualTo(3);
    }

    /**
     * ★リーダーが剛火の将でなければ何も起きない。
     * これを測っておかないと「HP+2 が付いた」のか「そもそも印刷値が3だった」のかを
     * 見分けられない(裁定196: 同じ結果を2つの理由で得られる試験にしない)。
     */
    @Test
    void 剛火の将でないリーダーなら速攻ミニオンのHPは印刷値のまま() {
        AutoGameFixture f = newGame();
        assertThat(f.putOnField(f.me(), SKY_SWALLOW).getMaxHp()).isEqualTo(1);
    }

    /**
     * ★<b>後から【速攻】を得たミニオンにも乗る。</b>
     * 加算量は場に出るときに写しているが、キーワードの有無は読むたびに見ているためである。
     * 《赫灼の重戦士》は【召喚時】に「自分のリーダーの体力が10以下なら【速攻】を得る」ので、
     * <b>本物の召喚の入口</b>から後付けの付与を起こせる。
     */
    @Test
    void 召喚時に速攻を得たミニオンにも剛火の将の常在が乗る() {
        AutoGameFixture f = newGame(FIRE_GENERAL);
        f.me().setLp(10); // 「体力が10以下」を満たす
        payMana(f.me(), 4);
        int idx = f.giveHand(f.me(), CRIMSON_HEAVY);

        game.playCard(f.room(), "me", idx, List.of(), false);

        MinionInstance summoned = f.me().getMinionZone().get(0);
        assertThat(summoned.hasKeyword(Keyword.HASTE)).as("【召喚時】に【速攻】を得た").isTrue();
        assertThat(summoned.getMaxHp())
                .as("後から得た【速攻】にも常在が乗る(印刷4 + 2)")
                .isEqualTo(6);
    }

    /**
     * ★<b>両者のリーダーが剛火の将なら常在が2つ重なる</b>(+4)。
     * 常在の既定の累積であり、このカードだけの特例ではない(《サービスブレイク・メリィナ》と同じ)。
     */
    @Test
    void 両者が剛火の将なら常在は累積する() {
        AutoGameFixture f = new AutoGameFixture(cards, FIRE_GENERAL, FIRE_GENERAL);
        assertThat(f.putOnField(f.me(), SKY_SWALLOW).getMaxHp()).isEqualTo(5);
    }

    /** ★Ver1.1 で起動能力は本文から消えた。押せるボタンが残っていないことを入口から測る */
    @Test
    void 剛火の将は起動能力を持たない() {
        AutoGameFixture f = newGame(FIRE_GENERAL);
        payMana(f.me(), 5);
        assertThatThrownBy(() -> game.useLeaderAbility(f.room(), "me", List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- 背水の烈火使い(QTE-M-FIRE-7・区分5) ----
    // 旧: 「【召喚時】手札をすべて捨てる。」(【守護】4/3/5 に重いデメリット)
    // 新: 「【守護】」のみ(誘発効果が丸ごと消え、素の【守護】ミニオンになった)

    @Test
    void 背水の烈火使いは召喚しても手札を捨てない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        int idx = f.giveHand(f.me(), LAST_STAND_PYROMANCER);
        f.giveHand(f.me(), PLAIN_MINION);
        f.giveHand(f.me(), PLAIN_MINION);

        game.playCard(f.room(), "me", idx, List.of(), false);

        assertThat(f.me().getHand()).as("★旧: ここで手札が0枚になっていた").hasSize(2);
        assertThat(f.me().getTrash()).as("捨てられたカードは無い").isEmpty();
        assertThat(f.me().getMinionZone().get(0).hasKeyword(Keyword.GUARD))
                .as("【守護】はテキストから付く").isTrue();
    }

    // ==================================================================
    // 水文明
    // ==================================================================

    // ---- 英知の継承者(QTE-M-WATER-19・区分5) ----
    // 旧: 「【召喚時】【知識】を持つカードを1枚手札から捨てても良い。そうしたら【知識】を行う。」
    //     (任意。捨てて1ドローなので手札の増減は0)
    // 新: 「【召喚時】カードを4枚引く。その後カードを3枚捨てる」(必須。差し引き+1)

    @Test
    void 英知の継承者は4枚引いてから3枚捨てる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        int idx = f.giveHand(f.me(), WISDOM_HEIR);
        int deckBefore = f.me().getDeck().size();

        game.playCard(f.room(), "me", idx, List.of(), false);

        assertThat(deckBefore - f.me().getDeck().size()).as("4枚引いた").isEqualTo(4);
        assertThat(f.me().getHand()).as("捨てる前は4枚").hasSize(4);
        assertThat(f.me().getPendingChoice()).as("捨てる3枚を問い合わせている").isNotNull();
        assertThat(f.me().getPendingChoice().min()).as("必須である").isEqualTo(3);

        game.resolveChoice(f.room(), "me", List.of(0, 1, 2));

        assertThat(f.me().getHand()).as("差し引き+1(4引いて3捨てた)").hasSize(1);
        assertThat(f.me().getTrash()).as("捨てた3枚は墓地へ").hasSize(3);
    }

    /** ★捨てるのは必須である。0枚を送ると弾かれることで min=3 を測る */
    @Test
    void 英知の継承者のディスカードは省略できない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        int idx = f.giveHand(f.me(), WISDOM_HEIR);
        game.playCard(f.room(), "me", idx, List.of(), false);

        assertThatThrownBy(() -> game.resolveChoice(f.room(), "me", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- 知恵の双翼(QTE-M-WATER-22・区分5) ----
    // ★実装変更なし。Ver1.1 で変わったのは書き方だけである。
    //   旧「【特殊召喚】自分の【知識】を持つミニオンを2体手札に戻した手札から0コストとして出せる。」
    //   新「【知識】【守護】【特殊召喚】(自分の【知識】を持つミニオンを2体手札に戻したとき
    //       手札から0コストとして出せる。)」
    //   増えた【知識】【守護】は旧台帳の keywords に既にあり、本文に現れただけである(裁定158)。
    //   ★仕分けでは区分5 だったが、実際は区分2(記法だけ)であった。

    @Test
    void 知恵の双翼は知識ミニオン2体を戻して0コストで出せる() {
        AutoGameFixture f = newGame();
        MinionInstance a = f.putOnField(f.me(), KNOWLEDGE_JELLY);
        MinionInstance b = f.putOnField(f.me(), KNOWLEDGE_JELLY);
        int idx = f.giveHand(f.me(), WISDOM_WINGS);
        // マナは1枚も置かない —— 0コストで出せることが条件そのものである

        int deckBefore = f.me().getDeck().size();

        game.specialSummon(f.room(), "me", idx, List.of(minions(a.getInstanceId(), b.getInstanceId())));

        assertThat(f.fieldIds(f.me())).containsExactly(WISDOM_WINGS);
        assertThat(f.me().getHand()).as("戻した2体が手札に居る")
                .startsWith(KNOWLEDGE_JELLY, KNOWLEDGE_JELLY);
        MinionInstance wings = f.me().getMinionZone().get(0);
        assertThat(wings.hasKeyword(Keyword.KNOWLEDGE)).isTrue();
        assertThat(wings.hasKeyword(Keyword.GUARD)).isTrue();
        // ★【知識】は「登場時に1枚ドロー」のキーワードアクションである。
        //   Ver1.1 で本文に現れたが、旧台帳も keywords に持っていたので挙動は変わっていない
        //   (キーワードはテキストから作る。裁定158)。
        assertThat(deckBefore - f.me().getDeck().size()).as("【知識】の1ドロー").isEqualTo(1);
        assertThat(f.me().getHand()).hasSize(3);
    }

    @Test
    void 知恵の双翼は知識ミニオンが1体では特殊召喚できない() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), KNOWLEDGE_JELLY);
        int idx = f.giveHand(f.me(), WISDOM_WINGS);

        assertThatThrownBy(() -> game.specialSummon(f.room(), "me", idx, List.of(none())))
                .isInstanceOf(IllegalStateException.class);
    }

    // ==================================================================
    // 風文明
    // ==================================================================

    // ---- ストーム・カイザー(QTE-M-WIND-8・区分5) ----
    // 旧: 「このターン中に自分がカードを4枚以上使用している時、コストを支払わずに場に出せる。」
    // 新: 「【速攻】/【特殊召喚】(このターン中に自分がカードを5枚以上使用している時、
    //       コストを1払って場に出せる)」

    @Test
    void ストームカイザーは5枚使用でコスト1で出せる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        f.me().setCardsUsedThisTurn(5);
        int idx = f.giveHand(f.me(), STORM_KAISER);

        game.specialSummon(f.room(), "me", idx, List.of(none()));

        assertThat(f.fieldIds(f.me())).containsExactly(STORM_KAISER);
        assertThat(f.me().getMinionZone().get(0).hasKeyword(Keyword.HASTE))
                .as("【速攻】が付いた(Ver1.1 の追加)").isTrue();
        long untapped = f.me().getManaZone().stream().filter(m -> !m.isTapped()).count();
        assertThat(untapped).as("★旧: 0コストだったのでマナは減らなかった").isEqualTo(2);
    }

    /** ★条件は 4枚 → 5枚 に上がった。4枚では出せないことで、境目そのものを測る */
    @Test
    void ストームカイザーは4枚使用では特殊召喚できない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        f.me().setCardsUsedThisTurn(4);
        int idx = f.giveHand(f.me(), STORM_KAISER);

        assertThatThrownBy(() -> game.specialSummon(f.room(), "me", idx, List.of(none())))
                .isInstanceOf(IllegalStateException.class);
    }

    /** ★代替コストは0ではない。マナが足りなければ出せない */
    @Test
    void ストームカイザーはマナが0なら特殊召喚できない() {
        AutoGameFixture f = newGame();
        f.me().setCardsUsedThisTurn(5);
        int idx = f.giveHand(f.me(), STORM_KAISER);

        assertThatThrownBy(() -> game.specialSummon(f.room(), "me", idx, List.of(none())))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- 風弾の跳弾(QTE-M-WIND-24・区分5) ----
    // 旧: 「自分のミニオンを1体<b>手札に戻す</b>。そうしたら相手のミニオン1体に<b>2</b>ダメージ。
    //      このカードのコストを<b>+3</b>してもよい。…墓地に置く代わりに手札に戻す。」
    // 新: 「このカードのコストを<b>+2</b>してもよい。…墓地に置く代わり手札に戻す。
    //      自分のミニオンを1枚<b>破壊する</b>。そうしたら相手のミニオン1体に<b>3</b>ダメージ。」

    @Test
    void 風弾の跳弾は自分のミニオンを破壊して相手に3ダメージ与える() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 1);
        MinionInstance mine = f.putOnField(f.me(), PLAIN_MINION);
        MinionInstance theirs = f.putOnField(f.you(), LAST_STAND_PYROMANCER); // 3/5。3ダメージで残る
        int spell = f.giveHand(f.me(), RICOCHET);

        game.playCard(f.room(), "me", spell,
                List.of(minions(mine.getInstanceId()), minions(theirs.getInstanceId())), false);

        assertThat(f.me().getMinionZone()).as("★旧: 手札に戻っていた。新: 破壊される").isEmpty();
        assertThat(f.me().getHand()).as("破壊なので手札には戻らない").isEmpty();
        assertThat(f.me().getTrash()).as("破壊された自分のミニオンが墓地に居る")
                .contains(PLAIN_MINION);
        assertThat(theirs.getCurrentHp()).as("★旧: 2ダメージだった。新: 3ダメージ")
                .isEqualTo(f.card(LAST_STAND_PYROMANCER).hp() - 3);
    }

    /** ★追加コストは +3 → +2 に下がった。合計3マナで手札に戻ってくる */
    @Test
    void 風弾の跳弾は強化使用で合計3マナになり手札に戻る() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        MinionInstance mine = f.putOnField(f.me(), PLAIN_MINION);
        MinionInstance theirs = f.putOnField(f.you(), LAST_STAND_PYROMANCER);
        int spell = f.giveHand(f.me(), RICOCHET);

        game.playCard(f.room(), "me", spell,
                List.of(minions(mine.getInstanceId()), minions(theirs.getInstanceId())), true);

        long untapped = f.me().getManaZone().stream().filter(m -> !m.isTapped()).count();
        assertThat(untapped).as("★旧: 1+3=4マナだった。新: 1+2=3マナ").isEqualTo(2);
        assertThat(f.me().getHand()).as("墓地に置く代わりに手札へ戻る").containsExactly(RICOCHET);
        assertThat(f.me().getTrash()).doesNotContain(RICOCHET);
    }

    // ==================================================================
    // 闇文明
    // ==================================================================

    // ---- カース・ボーン(QTE-M-DARK-2・区分5) ----
    // 旧: 「【召喚時】自分のマナゾーンの表向きのカード1枚を、裏向きにする。
    //      裏向きにできなかったとき破壊する。」(1/2/1)
    // 新: 「【召喚時】自分のミニオンを1体破壊する。破壊したミニオンのコストと同じ数
    //      山札の上から墓地に置く。【還元】」(2/1/1)

    @Test
    void カースボーンは選んだ自分のミニオンを破壊しそのコスト分セルフミルする() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        MinionInstance victim = f.putOnField(f.me(), LAST_STAND_PYROMANCER); // コスト4
        int idx = f.giveHand(f.me(), CURSE_BONE);
        int deckBefore = f.me().getDeck().size();

        game.playCard(f.room(), "me", idx, List.of(), false);
        assertThat(f.me().getPendingChoice()).as("破壊する1体を問い合わせている").isNotNull();
        int victimIndex = f.me().getPendingChoice().candidates().indexOf(victim.getInstanceId());
        game.resolveChoice(f.room(), "me", List.of(victimIndex));

        assertThat(f.fieldIds(f.me())).as("カース・ボーンだけが残る").containsExactly(CURSE_BONE);
        assertThat(deckBefore - f.me().getDeck().size())
                .as("破壊したミニオンの印刷コスト(4)と同じ枚数だけ山札が減る").isEqualTo(4);
        assertThat(f.me().getTrash()).as("破壊された1体 + ミルした4枚").hasSize(5);
    }

    /**
     * ★候補には<b>自分自身</b>が含まれる。他に何も居なければ自分を破壊するしかない。
     * ★そのとき【還元】が効くので、カース・ボーンは<b>墓地ではなく裏向きでマナへ</b>行く。
     */
    @Test
    void カースボーンは他にミニオンが居なければ自分を破壊しマナへ還元される() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        int idx = f.giveHand(f.me(), CURSE_BONE);
        int deckBefore = f.me().getDeck().size();
        int manaBefore = f.me().getManaZone().size();

        game.playCard(f.room(), "me", idx, List.of(), false);

        assertThat(f.me().getPendingChoice()).as("候補が1体なら問い合わせない").isNull();
        assertThat(f.me().getMinionZone()).isEmpty();
        assertThat(deckBefore - f.me().getDeck().size())
                .as("自身の印刷コスト(2)と同じ枚数だけミルする").isEqualTo(2);
        assertThat(f.me().getTrash()).as("ミルした2枚だけ(自身は墓地に行かない)").hasSize(2);
        assertThat(f.me().getManaZone()).hasSize(manaBefore + 1);
        assertThat(f.me().getManaZone().get(manaBefore).getCardId())
                .as("【還元】で裏向きマナへ").isEqualTo(CURSE_BONE);
        assertThat(f.me().getManaZone().get(manaBefore).isFaceUp()).isFalse();
    }

    // ==================================================================
    // 土文明
    // ==================================================================

    // ---- 地脈の覚醒(QTE-M-EARTH-27・区分5) ----
    // 旧: 本文が<b>空欄</b>。【還元】だけを持つマナ加速だった。
    // 新: 「自分のマナからカードを1枚手札に加える【還元】
    //      (「地脈の覚醒」の効果はターンに1回のみ発動する)」

    @Test
    void 地脈の覚醒はマナから1枚を手札に加える() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        f.me().getManaZone().add(new ManaCard(PLAIN_MINION, false)); // これを回収する
        int spell = f.giveHand(f.me(), LEYLINE);

        game.playCard(f.room(), "me", spell, List.of(), false);
        assertThat(f.me().getPendingChoice()).as("手札に加える1枚を問い合わせている").isNotNull();
        int target = f.me().getPendingChoice().candidates().indexOf("2");
        game.resolveChoice(f.room(), "me", List.of(target));

        assertThat(f.me().getHand()).as("選んだマナが手札に加わる").containsExactly(PLAIN_MINION);
        assertThat(f.me().getManaZone().stream().map(ManaCard::getCardId))
                .as("【還元】で自身がマナへ入るので枚数は元に戻る").containsExactly(MAGMA, MAGMA, LEYLINE);
    }

    /**
     * ★「効果はターンに1回のみ発動する」。2枚目は<b>使用できる</b>が効果は発動しない ——
     * 本文は「効果は…発動しない」であって「使用できない」ではないためである。
     * 【還元】は効果ではないので、2枚目もマナへ行く。
     */
    @Test
    void 地脈の覚醒は同じターンに2枚目を使っても効果は発動しない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        f.me().getManaZone().add(new ManaCard(PLAIN_MINION, false));
        int first = f.giveHand(f.me(), LEYLINE);
        f.giveHand(f.me(), LEYLINE);

        game.playCard(f.room(), "me", first, List.of(), false);
        int target = f.me().getPendingChoice().candidates().indexOf("4");
        game.resolveChoice(f.room(), "me", List.of(target));
        assertThat(f.me().getHand()).containsExactly(LEYLINE, PLAIN_MINION);

        // 2枚目(手札の先頭)を同じターンに使う
        game.playCard(f.room(), "me", 0, List.of(), false);

        assertThat(f.me().getPendingChoice()).as("問い合わせ自体が起きない").isNull();
        assertThat(f.me().getHand()).as("手札は増えない(効果が発動していない)")
                .containsExactly(PLAIN_MINION);
        assertThat(f.me().getManaZone().stream().map(ManaCard::getCardId))
                .as("【還元】は効くので2枚目もマナへ").endsWith(LEYLINE, LEYLINE);
    }

    /** ★ターンが変われば また発動できる(裁定156(3)。ターン番号を刻んで持っている) */
    @Test
    void 地脈の覚醒はターンが変われば再び発動できる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        f.me().getManaZone().add(new ManaCard(PLAIN_MINION, false));
        int first = f.giveHand(f.me(), LEYLINE);
        f.giveHand(f.me(), LEYLINE);

        game.playCard(f.room(), "me", first, List.of(), false);
        game.resolveChoice(f.room(), "me",
                List.of(f.me().getPendingChoice().candidates().indexOf("4")));

        f.state().setTurnNumber(f.state().getTurnNumber() + 2); // 一周してきた自分のターン
        game.playCard(f.room(), "me", 0, List.of(), false);

        assertThat(f.me().getPendingChoice()).as("別のターンなので再び問い合わせる").isNotNull();
    }

    /** マナの中身を直接指定する経路(流転の智者と同じ Kind.MANA)を汚さないための番人 */
    @Test
    void 地脈の覚醒は使用宣言時の対象指定を要求しない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        int spell = f.giveHand(f.me(), LEYLINE);

        // 対象を渡さずに使用できる(割り込みで問い合わせる形であることの裏付け)
        game.playCard(f.room(), "me", spell, List.of(), false);
        assertThat(f.me().getPendingChoice()).isNotNull();
    }
}
