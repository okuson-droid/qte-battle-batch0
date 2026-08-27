package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.effect.PersistentAura;
import com.example.qte.effect.StatCalculator;
import com.example.qte.effect.TargetChoice;
import com.example.qte.game.GameService;
import com.example.qte.game.ManaCard;
import com.example.qte.game.PlayerState;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.CardTextKeywords;
import com.example.qte.master.Keyword;
import com.example.qte.support.AutoGameFixture;

/**
 * Batch 73(総点検の続き = 候補 D″)で見つかった食い違い8枚の試験。
 *
 * <h2>この8枚は何だったのか</h2>
 *
 * 67 が見た48枚の<b>残り188枚</b>(P5 が作り直した121枚 + Ver1.1 生まれの66枚 + 64 の1枚)を
 * 1枚ずつ本文と突き合わせた結果である。
 *
 * <table border="1">
 *   <caption>73 が直した8枚</caption>
 *   <tr><th>カード</th><th>Ver1.1 の本文</th><th>72 までの実装</th></tr>
 *   <tr><td>《アルキンティス》</td><td>自分の場の【知識】の<b>枚数</b>Attack+1</td>
 *       <td>自分が【知識】を持つことになり、装備しただけで1枚引く</td></tr>
 *   <tr><td>《詠唱の疾風騎士》</td><td>ターンエンド時、このターン5回以上唱えていたら</td>
 *       <td>相手のターンエンドでも発動する(1巡で最大4枚回収)</td></tr>
 *   <tr><td>《悪夢》</td><td>墓地にある<b>カード</b>1枚につきコスト-1</td>
 *       <td>スペルを除いた枚数しか数えない</td></tr>
 *   <tr><td>《這い寄る生霊》</td><td>【特殊召喚】【突進】【知識】(自壊は無い)</td>
 *       <td>そのターンの終わりに自壊する</td></tr>
 *   <tr><td>《降臨の伝道師》</td><td>【守護】<b>ミニオン</b>を1体場に出す</td>
 *       <td>種別を見ておらず、進化ミニオンが素材ゼロで場に立つ</td></tr>
 *   <tr><td>《詠唱の宝珠》</td><td>次の自分のターンに唱える光のスペル<b>すべて</b></td>
 *       <td>次の1枚だけ・しかも期限がターンに紐づかない</td></tr>
 *   <tr><td>《墓穴の呪い》</td><td>体力が墓地以下のミニオンを<b>2枚選び</b>破壊</td>
 *       <td>該当するものを両者の場から<b>全部</b>破壊</td></tr>
 *   <tr><td>《風のマナ変換》</td><td>自分の表向きのマナを1手札に戻す</td>
 *       <td>どれを戻すか尋ねず、末尾を自動で選ぶ</td></tr>
 * </table>
 *
 * <h2>★8枚を直しても、既存の826件は1件も落ちなかった</h2>
 *
 * 67 とまったく同じである —— <b>この8枚は1件も測られていなかった</b>。
 * だからこのクラスが要る。
 *
 * <h2>★★★67 と違ったこと: 「表」が在った</h2>
 *
 * 8枚のうち4枚は {@code notes/ver0.4-transcription-notes.md} の
 * <b>5章「転記した52枚の一覧」</b>と<b>4章のルーリング</b>に、
 * Ver1.1 で何がどう変わったかが<b>はっきり書いてあった</b>
 * (悪夢・這い寄る生霊・墓穴の呪い・詠唱の宝珠)。
 * <b>表は在ったのに、実装がそこまで届いていなかった。</b>
 *
 * <p>★★<b>いちばん怖い形が《詠唱の宝珠》である。</b>
 * Batch 56 は「旧: スペルすべて / 新: 光のスペル限定」と書いて
 * 「実装は既に一致している」と結論した —— <b>旧は「次の1枚」だった</b>。
 * 誤った前提が、確認そのものを素通りさせた。
 *
 * <h2>測り方</h2>
 *
 * {@link AutoGameFixture} の上に書き、本物の入口から起こす(裁定187)。
 * 数値は<b>試験の側に直接書く</b>(裁定298)。
 */
@SpringBootTest
class Batch73TextImplTest {

    /** 常在効果を持たないリーダー(蒼海の賢者)。既定の対戦相手 */
    private static final String PLAIN_LEADER = "QTE-M-WATER-1";

    // ---- 73 が直した8枚 ----
    private static final String ARKINTIS = "QTE-M-WATER-39";      // アルキンティス(ウェポン)
    private static final String GALE_KNIGHT = "QTE-M-WIND-18";    // 詠唱の疾風騎士
    private static final String NIGHTMARE = "QTE-M-DARK-27";      // 悪夢(ミニオン・13)
    private static final String CRAWLING_WRAITH = "QTE-M-DARK-7"; // 這い寄る生霊
    private static final String MISSIONARY = "QTE-M-LIGHT-22";    // 降臨の伝道師
    private static final String CHANT_ORB = "QTE-M-LIGHT-28";     // 詠唱の宝珠(ウェポン)
    private static final String GRAVE_CURSE = "QTE-M-DARK-24";    // 墓穴の呪い(スペル)
    private static final String MANA_CONVERT = "QTE-M-WIND-23";   // 風のマナ変換(スペル)

    // ---- 道具として使うカード ----
    /** マグマ・ストレート(火・スペル・1)。マナの中身と墓地の「スペル」の物差し */
    private static final String MAGMA = "QTE-M-FIRE-10";
    /** 死神の大鎌(闇・ウェポン・1)。墓地に置く<b>スペルでない</b>カード */
    private static final String REAPER_SCYTHE = "QTE-M-DARK-13";
    /** ゴーレム・ウォール(土・3/1/5・【守護】)。効果を持たない【守護】ミニオン */
    private static final String PLAIN_GUARD = "QTE-M-EARTH-2";
    /** 英霊・タイガラム(光・進化・7・【守護】)。67 が塞いだ穴の3枚目を測る相手 */
    private static final String GUARD_EVOLUTION = "QTE-M-LIGHT-32";
    /** スカイ・スワロー(風・1/1/1・【速攻】)。効果を持たない最小のミニオン */
    private static final String SKY_SWALLOW = "QTE-M-WIND-3";
    /** 海獣タウギーナ(水・1/1/1・【潜伏】)。効果を持たない物差し */
    private static final String SEA_BEAST = "QTE-M-WATER-33";
    /** 光のスペル。詠唱の宝珠の軽減が乗る相手 */
    private static final String LIGHT_SPELL = "QTE-M-LIGHT-9";

    @Autowired
    GameService game;

    @Autowired
    CardMasterRepository cards;

    @Autowired
    StatCalculator stats;

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

    private static TargetChoice none() {
        return new TargetChoice(null, null, null, null, null);
    }

    // ==================================================================
    // 1. アルキンティス(QTE-M-WATER-39)
    //   本文: 「【常在】自分の場の【知識】の枚数Attackを+1する。」
    //   72 まで: 自分が【知識】を持つ扱いになり、ウェポンなので装備時に1枚引いた
    // ==================================================================

    @Test
    @DisplayName("★アルキンティスは【知識】を持たない(「【知識】の枚数」は参照である)")
    void アルキンティスは知識を持たない() {
        assertThat(CardTextKeywords.extract(cards.findById(ARKINTIS).text()))
                .as("★「【知識】の枚数」は他者を数える言葉であり、自分が持つという意味ではない")
                .doesNotContain(Keyword.KNOWLEDGE);
    }

    @Test
    @DisplayName("★アルキンティスを装備しても1枚も引かない")
    void アルキンティスは装備しても引かない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 10);
        int weapon = f.giveHand(f.me(), ARKINTIS);
        int handBefore = f.me().getHand().size();
        int deckBefore = f.me().getDeck().size();

        game.playCard(f.room(), "me", weapon, List.of(none()), false);

        assertThat(f.me().getDeck().size())
                .as("★ウェポンの【知識】は装備時に発動する —— 持っていないのだから引かない")
                .isEqualTo(deckBefore);
        assertThat(f.me().getHand()).hasSize(handBefore - 1);
    }

    @Test
    @DisplayName("参照の除外は、同じ本文の中の「付与」を巻き込まない")
    void 戒律のガーディアンは守護を持ったままである() {
        // 《戒律のガーディアン》(LIGHT-20)は「【守護】自分の光文明スペルのコスト-1、
        // 自分の光文明の【守護】のコスト-1。」—— 先頭が付与、2つ目が参照である。
        // ★出現ごとに判定する書き方がこれを守っている(1枚の中に両方が同居する)
        assertThat(CardTextKeywords.extract(cards.findById("QTE-M-LIGHT-20").text()))
                .as("★先頭の【守護】は自分が持つ。169枚の凍結(keyword-baseline)もこれを要求している")
                .contains(Keyword.GUARD);
    }

    // ==================================================================
    // 2. 詠唱の疾風騎士(QTE-M-WIND-18)
    //   本文: 「ターンエンド時このターン5回以上スペルを撃っていたら
    //          墓地にあるスペルを2枚まで手札に戻す。」
    //   72 まで: 相手のターンエンドでも発動していた(カウンタが残るため)
    // ==================================================================

    @Test
    @DisplayName("★詠唱の疾風騎士は相手のターンエンドでは回収しない")
    void 疾風騎士は相手のターンエンドでは回収しない() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), GALE_KNIGHT);
        // ★墓地のスペルは2枚にする —— 3枚以上だと「どれを回収するか」の問い合わせが出て、
        //   答えるまでターンが渡らない(この試験が見たいのはそこではない)
        f.me().getTrash().addAll(List.of(MAGMA, MAGMA));
        f.me().setSpellsCastThisTurn(5);

        // 自分のターンを終える —— 条件を満たしているので2枚とも回収される
        game.endTurn(f.room(), "me");
        assertThat(f.me().getTrash())
                .as("自分のターンエンドでは条件を満たすので回収する")
                .isEmpty();

        // ★★<b>手札の枚数では測れない。</b>ターンが一周すると自分がドローするからである。
        //   墓地に積み直して、<b>相手のターンエンドで減らないこと</b>を見る。
        // ★このとき spellsCastThisTurn は<b>まだ 5 のまま残っている</b>
        //   (startTurnReset はターンプレイヤーにしか走らない)——
        //   つまり「条件は満たして見える」状態であり、止めているのは自ターン判定だけである。
        f.me().getTrash().addAll(List.of(MAGMA, MAGMA));

        game.endTurn(f.room(), "you");

        assertThat(f.me().getTrash())
                .as("★相手のターンエンドでは「このターン5回以上」を満たしていない —— 回収しない")
                .hasSize(2);
    }

    // ==================================================================
    // 3. 悪夢(QTE-M-DARK-27)
    //   本文: 「墓地にあるカード1枚につきコスト-1」
    //   72 まで: スペルを除いた枚数しか数えなかった
    // ==================================================================

    @Test
    @DisplayName("★悪夢は墓地のスペルも数える(種別を絞らない)")
    void 悪夢は墓地のスペルも数える() {
        AutoGameFixture f = newGame();
        int printed = cards.findById(NIGHTMARE).cost();
        // 墓地にスペル3枚とスペルでないカード1枚
        f.me().getTrash().addAll(List.of(MAGMA, MAGMA, MAGMA, REAPER_SCYTHE));

        assertThat(stats.effectiveCost(f.state(), f.me(), f.card(NIGHTMARE)))
                .as("★墓地4枚ぶん下がる。72 までは「スペルでない1枚」しか数えなかった")
                .isEqualTo(printed - 4);
    }

    // ==================================================================
    // 4. 這い寄る生霊(QTE-M-DARK-7)
    //   本文: 【特殊召喚】【突進】【知識】。★自壊の一文は Ver1.1 で削除された
    //   72 まで: 特殊召喚で出すとターンの終わりに自壊した
    // ==================================================================

    @Test
    @DisplayName("★這い寄る生霊は特殊召喚で出してもターン終了で自壊しない")
    void 這い寄る生霊は自壊しない() {
        AutoGameFixture f = newGame();
        // 【特殊召喚】の条件「自分のターン中に自分のミニオンが破壊された」を満たす
        var doomed = f.putOnField(f.me(), SKY_SWALLOW);
        f.me().getMinionZone().remove(doomed);
        f.me().setOwnMinionDestroyedThisTurn(true);
        int hand = f.giveHand(f.me(), CRAWLING_WRAITH);

        game.specialSummon(f.room(), "me", hand, List.of());
        assertThat(f.fieldIds(f.me())).as("コスト0で場に出る").contains(CRAWLING_WRAITH);

        game.endTurn(f.room(), "me");

        assertThat(f.fieldIds(f.me()))
                .as("★Ver1.1 の本文に自壊は無い(ver0.4-transcription-notes 台帳 0085)")
                .contains(CRAWLING_WRAITH);
    }

    // ==================================================================
    // 5. 降臨の伝道師(QTE-M-LIGHT-22)
    //   本文: 「【召喚時】山札の上から4枚を公開。【守護】ミニオンを1体場に出し…」
    //   72 まで: 種別を見ておらず、進化ミニオンが素材ゼロで場に立った
    // ==================================================================

    @Test
    @DisplayName("★★降臨の伝道師は進化ミニオンを素材なしで場に出さない(67 の穴の3枚目)")
    void 降臨の伝道師は進化を出さない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 15);
        // 公開される4枚を「守護の進化1枚 + 効果を持たない3枚」にする
        f.stackDeck(f.me(), GUARD_EVOLUTION, SEA_BEAST, SEA_BEAST, SEA_BEAST);
        int hand = f.giveHand(f.me(), MISSIONARY);

        game.playCard(f.room(), "me", hand, List.of(none()), false);

        assertThat(f.fieldIds(f.me()))
                .as("★《英霊・タイガラム》は光・【守護】・コスト7 の<b>進化</b>である。"
                        + "素材ゼロで出るのは裁定226 に反する")
                .doesNotContain(GUARD_EVOLUTION);
    }

    @Test
    @DisplayName("進化でない【守護】ミニオンは今までどおり出る(常に出さない実装を排除する)")
    void 降臨の伝道師は普通の守護なら出す() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 15);
        f.stackDeck(f.me(), PLAIN_GUARD, SEA_BEAST, SEA_BEAST, SEA_BEAST);
        int hand = f.giveHand(f.me(), MISSIONARY);

        game.playCard(f.room(), "me", hand, List.of(none()), false);

        assertThat(f.fieldIds(f.me())).contains(PLAIN_GUARD);
    }

    // ==================================================================
    // 6. 詠唱の宝珠(QTE-M-LIGHT-28)
    //   本文: 「このカードが破壊されたとき次の自分のターン唱える光のスペルのコスト-1。」
    //   72 まで: 次の1枚だけ・期限がターンに紐づかない
    // ==================================================================

    @Test
    @DisplayName("★詠唱の宝珠の軽減は、次の自分のターンの終了時まで残る")
    void 詠唱の宝珠は次の自分のターンの終わりまで残る() {
        AutoGameFixture f = newGame();
        int now = f.state().getTurnNumber();
        f.me().getPersistentAuras().add(PersistentAura.untilEndOfTurn(CHANT_ORB, now + 2));

        int discounted = stats.effectiveCost(f.state(), f.me(), f.card(LIGHT_SPELL));
        assertThat(discounted)
                .as("光のスペルは-1される")
                .isEqualTo(cards.findById(LIGHT_SPELL).cost() - 1);

        // ★1枚唱えても消えない —— そこが 72 までとの差である
        f.me().getPersistentAuras().stream()
                .filter(a -> CHANT_ORB.equals(a.cardId()))
                .findFirst()
                .orElseThrow();
        assertThat(stats.effectiveCost(f.state(), f.me(), f.card(LIGHT_SPELL)))
                .as("★「次の1枚だけ」ではない(ver0.4-transcription-notes 4章 #9)")
                .isEqualTo(discounted);
    }

    @Test
    @DisplayName("★詠唱の宝珠の期限は「次の自分のターン」である(相手の手番なら1つ先)")
    void 詠唱の宝珠の期限はターン番号で決まる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 15);
        int hand = f.giveHand(f.me(), CHANT_ORB);
        game.playCard(f.room(), "me", hand, List.of(none()), false);
        assertThat(f.me().getEquippedWeapon()).isNotNull();

        // 付け替えで場を離れる(破壊と同じ経路を通る)
        int another = f.giveHand(f.me(), REAPER_SCYTHE);
        int turnWhenLeft = f.state().getTurnNumber();
        game.playCard(f.room(), "me", another, List.of(none()), false);

        var aura = f.me().getPersistentAuras().stream()
                .filter(a -> CHANT_ORB.equals(a.cardId()))
                .findFirst()
                .orElseThrow();
        assertThat(aura.expiry())
                .as("★ON_NEXT_SPELL は消えた。期限はターン番号で持つ")
                .isEqualTo(PersistentAura.Expiry.AFTER_TURN_NUMBER);
        assertThat(aura.expiresAfterTurn())
                .as("★自分の手番中に外れたのだから、次の自分のターンは2つ先である")
                .isEqualTo(turnWhenLeft + 2);
    }

    // ==================================================================
    // 7. 墓穴の呪い(QTE-M-DARK-24)
    //   本文: 「自分の山札の上から2枚を墓地に置く。墓地以下の体力のミニオンを2枚選び破壊。」
    //   72 まで: 該当するものを両者の場から全部破壊した
    // ==================================================================

    @Test
    @DisplayName("★★墓穴の呪いは、候補が3体以上でも2体しか壊さない")
    void 墓穴の呪いは2体までしか壊さない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 15);
        // 体力1のミニオンを相手に4体。墓地は2枚ミルされるので閾値は 2 以上になる
        for (int i = 0; i < 4; i++) {
            f.putOnField(f.you(), SEA_BEAST);
        }
        int hand = f.giveHand(f.me(), GRAVE_CURSE);
        List<String> before = List.copyOf(
                f.you().getMinionZone().stream().map(m -> m.getInstanceId()).toList());

        game.playCard(f.room(), "me", hand, List.of(none()), false);

        assertThat(f.me().getPendingChoice())
                .as("★候補が3体以上なので、どれを壊すかを本人に問う(裁定299)")
                .isNotNull();
        f.answerChoice(game, "me", before.get(0), before.get(1));

        assertThat(f.you().getMinionZone())
                .as("★72 までは4体すべてが吹き飛んでいた(Ver0.4 の姿)")
                .hasSize(2);
    }

    @Test
    @DisplayName("候補が2体以下なら問わずに壊す(選ぶ余地が無い)")
    void 墓穴の呪いは候補が2体以下なら問わない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 15);
        f.putOnField(f.you(), SEA_BEAST);
        f.putOnField(f.you(), SEA_BEAST);
        int hand = f.giveHand(f.me(), GRAVE_CURSE);

        game.playCard(f.room(), "me", hand, List.of(none()), false);

        assertThat(f.me().getPendingChoice()).isNull();
        assertThat(f.you().getMinionZone()).isEmpty();
    }

    // ==================================================================
    // 8. 風のマナ変換(QTE-M-WIND-23)
    //   本文: 「自分の表向きのマナを1手札に戻す。その後自分の手札から1枚を裏向きでマナに置く。」
    //   72 まで: どれを戻すか尋ねず、末尾(最後に置かれた表向きのマナ)を自動で選んだ
    // ==================================================================

    @Test
    @DisplayName("★風のマナ変換は、手札に戻す表向きのマナを本人が選ぶ(末尾でなくてよい)")
    void 風のマナ変換は戻すマナを選べる() {
        AutoGameFixture f = newGame();
        // 表向きのマナを3枚。中身を変えて、どれが戻ったかを見分けられるようにする
        f.me().getManaZone().add(new ManaCard(SKY_SWALLOW, false));
        f.me().getManaZone().add(new ManaCard(SEA_BEAST, false));
        f.me().getManaZone().add(new ManaCard(PLAIN_GUARD, false));
        for (int i = 0; i < 5; i++) {
            ManaCard faceDown = new ManaCard(MAGMA, false);
            faceDown.turnFaceDown();
            f.me().getManaZone().add(faceDown);
        }
        int hand = f.giveHand(f.me(), MANA_CONVERT);

        game.playCard(f.room(), "me", hand, List.of(none()), false);

        assertThat(f.me().getPendingChoice())
                .as("★表向きが2枚以上あるなら、どれを戻すかは本人が決める(裁定299)")
                .isNotNull();
        // ★<b>先頭</b>を選ぶ —— 72 までの実装は末尾しか返さなかった
        f.answerChoice(game, "me", "0");

        assertThat(f.me().getHand())
                .as("★選んだ1枚(先頭)が手札に戻る")
                .contains(SKY_SWALLOW);
        assertThat(f.me().getManaZone().stream().map(ManaCard::getCardId))
                .as("末尾は戻っていない")
                .contains(PLAIN_GUARD);
    }

    @Test
    @DisplayName("表向きが1枚だけなら問わずに戻す(選ぶ余地が無い)")
    void 風のマナ変換は表向きが1枚なら問わない() {
        AutoGameFixture f = newGame();
        // ★表向きは1枚だけにする。ManaCard の第2引数は temporary であって faceUp ではないので、
        //   裏向きにするには turnFaceDown() を呼ぶ
        f.me().getManaZone().add(new ManaCard(SKY_SWALLOW, false));
        for (int i = 0; i < 5; i++) {
            ManaCard faceDown = new ManaCard(MAGMA, false);
            faceDown.turnFaceDown();
            f.me().getManaZone().add(faceDown);
        }
        int hand = f.giveHand(f.me(), MANA_CONVERT);

        game.playCard(f.room(), "me", hand, List.of(none()), false);

        assertThat(f.me().getHand())
                .as("1枚しか無いのだからそれが戻る")
                .contains(SKY_SWALLOW);
        assertThat(f.me().getPendingChoice())
                .as("★戻すほうは問わないが、置くほう(後半)は問う —— 手札が残っているため")
                .isNotNull();
    }
}
