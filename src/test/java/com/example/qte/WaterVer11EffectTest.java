package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.effect.StatCalculator;
import com.example.qte.effect.TargetChoice;
import com.example.qte.game.GameService;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.Keyword;
import com.example.qte.support.AutoGameFixture;

/**
 * Ver1.1 で追加された水文明6枚の挙動の試験(★Batch 49)。
 *
 * <h2>足場は 48 のものをそのまま使う</h2>
 *
 * {@link AutoGameFixture} の上に書き、効果は {@code GameService.playCard} /
 * {@code endTurn} / {@code resolveChoice} という<b>本物の入口</b>から起こす(裁定187)。
 * トリガーを {@code CardEffectRegistry} から直接叩くと、このバッチでいちばん壊れやすい
 * 「発火する場所」——ロロイヨ伯爵が反応する2箇所の登場イベント——が試験の外に出る。
 *
 * <h2>測り方の方針(48 から継続)</h2>
 *
 * <ul>
 * <li><b>裁定を名指しで固定し、「そうでない側」も測る。</b>
 *     裁定156(1)(2)(3) と、このバッチで決めた 190〜193 は、それぞれ反対側を持つ。
 *     たとえば「相手のミニオンでも誘発する」だけを測ると、
 *     <b>全部のミニオンで誘発する</b>実装でも通ってしまう ——
 *     だから「ロロイヨでないリーダーでは引かない」も並べて置く。</li>
 * <li><b>数ではなく結果を見る。</b> ドロー数は内部カウンタではなく<b>山札の減り</b>で測る
 *     (手札で測ると、プレイしたカードが手札を離れるぶんと混ざる)。</li>
 * </ul>
 */
@SpringBootTest
class WaterVer11EffectTest {

    /** 常在効果を持たないリーダー(既定)。ロロイヨ伯爵の試験だけ差し替える */
    private static final String PLAIN_LEADER = "QTE-M-WATER-1";   // 蒼海の賢者(起動能力のみ)
    private static final String LOLOIYO = "QTE-M-WATER-29";       // ロロイヨ伯爵
    private static final String RYUGU = "QTE-M-WATER-35";         // 海獣リューグー
    private static final String BISHAKAWA = "QTE-M-WATER-36";     // 潮獣ビシャカワ
    private static final String COANCHI = "QTE-M-WATER-37";       // 潮獣コアンチ
    private static final String GIGAMOUSE = "QTE-M-WATER-38";     // ギガマウス・バイト
    private static final String ARKINTIS = "QTE-M-WATER-39";      // アルキンティス

    /** 【潜伏】だけを持つ1コスト1/1(海獣タウギーナ)。潜伏を数える役・ギガマウスで出す役 */
    private static final String STEALTH = "QTE-M-WATER-33";
    /** 【潜伏】と【守護】の両方を持つ2コスト2/2(海獣ホウェライソ)。裁定156(1) を測る役 */
    private static final String BOTH = "QTE-M-WATER-34";
    /** 【守護】だけを持つ1コスト1/1(疾風の先陣)。★【知識】を持たないことが重要である */
    private static final String GUARD = "QTE-M-WIND-16";
    /** 【知識】だけを持つ1コスト1/1(アクア・ジェリー)。知識を数える役 */
    private static final String KNOWLEDGE = "QTE-M-WATER-2";
    /** キーワードを持たない1コスト2/1(フレア・ポーン)。「誘発しない」側を測る役 */
    private static final String PLAIN_MINION = "QTE-M-FIRE-2";
    /** 【召喚時】カードを2枚引く5コスト(水鏡の幻術師)。効果で「出す」と発動しないことを測る役 */
    private static final String ON_SUMMON_DRAW = "QTE-M-WATER-7";
    /** 手札の【守護】ミニオンを効果で場に出す5コストのスペル(聖なる降誕の儀式) */
    private static final String HOLY_BIRTH = "QTE-M-LIGHT-11";

    @Autowired
    GameService game;

    @Autowired
    StatCalculator stats;

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

    /** 手札1枚だけを選ぶ要求(ギガマウス・バイト)を組み立てる */
    private static TargetChoice hand(Integer... indexes) {
        return new TargetChoice(List.of(indexes), null, null, null, null);
    }

    // ==================================================================
    // ロロイヨ伯爵(QTE-M-WATER-29・リーダー)
    // 「【常在】ターンに一回【守護】のミニオンが場に出るたびカードを1枚引く。
    //   ターンに一回【潜伏】のミニオンが場に出るたびカードを1枚引く」
    // ==================================================================

    @Test
    void ロロイヨは守護のミニオンが場に出ると1枚引く() {
        AutoGameFixture f = newGame(LOLOIYO);
        f.giveMana(f.me(), 5);
        int before = f.me().getDeck().size();
        int idx = f.giveHand(f.me(), GUARD);
        game.playCard(f.room(), "me", idx, List.of(), false);
        assertThat(before - f.me().getDeck().size()).isEqualTo(1);
    }

    @Test
    void ロロイヨの守護のドローはターンに1回だけである() {
        AutoGameFixture f = newGame(LOLOIYO);
        f.giveMana(f.me(), 5);
        int before = f.me().getDeck().size();
        game.playCard(f.room(), "me", f.giveHand(f.me(), GUARD), List.of(), false);
        game.playCard(f.room(), "me", f.giveHand(f.me(), GUARD), List.of(), false);
        assertThat(before - f.me().getDeck().size())
                .as("2体目の【守護】では引かない")
                .isEqualTo(1);
    }

    /**
     * ★裁定156(1): 守護のカウントと潜伏のカウントは<b>独立</b>である。
     * 守護で1枚引いた後でも、同じターンに潜伏でもう1枚引ける。
     */
    @Test
    void ロロイヨの守護と潜伏のカウントは独立している() {
        AutoGameFixture f = newGame(LOLOIYO);
        f.giveMana(f.me(), 5);
        int before = f.me().getDeck().size();
        game.playCard(f.room(), "me", f.giveHand(f.me(), GUARD), List.of(), false);
        game.playCard(f.room(), "me", f.giveHand(f.me(), STEALTH), List.of(), false);
        assertThat(before - f.me().getDeck().size()).isEqualTo(2);
    }

    /**
     * ★裁定156(1) の本丸: <b>両方を持つ1体</b>が場に出たら、その1体で2枚引く。
     * 「1体につき1回」と読み違えた実装は、ここで1枚しか引かずに落ちる。
     */
    @Test
    void ロロイヨは守護と潜伏を両方持つ1体で2枚引く() {
        AutoGameFixture f = newGame(LOLOIYO);
        f.giveMana(f.me(), 5);
        int before = f.me().getDeck().size();
        game.playCard(f.room(), "me", f.giveHand(f.me(), BOTH), List.of(), false);
        assertThat(before - f.me().getDeck().size()).isEqualTo(2);
    }

    @Test
    void ロロイヨはキーワードを持たないミニオンでは引かない() {
        AutoGameFixture f = newGame(LOLOIYO);
        f.giveMana(f.me(), 5);
        int before = f.me().getDeck().size();
        game.playCard(f.room(), "me", f.giveHand(f.me(), PLAIN_MINION), List.of(), false);
        assertThat(before - f.me().getDeck().size()).isZero();
    }

    /**
     * ★「そうでない側」。ロロイヨ以外のリーダーでは何も起きない。
     * これが無いと、<b>全プレイヤーが引く</b>実装でも上の試験は全部通る(裁定181)。
     */
    @Test
    void ロロイヨでないリーダーは守護が出ても引かない() {
        AutoGameFixture f = newGame(); // 蒼海の賢者
        f.giveMana(f.me(), 5);
        int before = f.me().getDeck().size();
        game.playCard(f.room(), "me", f.giveHand(f.me(), GUARD), List.of(), false);
        assertThat(before - f.me().getDeck().size()).isZero();
    }

    /**
     * ★裁定156(2): テキストが「自分の」と書いていない誘発は両者を見る。
     * <b>相手のミニオンが場に出ても</b>ロロイヨ側が引く。
     */
    @Test
    void ロロイヨは相手のミニオンが場に出ても引く() {
        AutoGameFixture f = newGame(LOLOIYO);
        game.endTurn(f.room(), "me"); // 相手のターンへ(ここで相手が1枚引く)
        game.nextPhase(f.room(), "you"); // マナチャージフェイズ → メインフェイズ
        f.giveMana(f.you(), 5);
        int before = f.me().getDeck().size();
        game.playCard(f.room(), "you", f.giveHand(f.you(), GUARD), List.of(), false);
        assertThat(before - f.me().getDeck().size())
                .as("引くのは出した相手ではなくロロイヨ側である")
                .isEqualTo(1);
    }

    /**
     * ★裁定156(3): 「ターンに一回」は<b>毎ターンリセットされる</b> ——
     * 自分のターンで1回、相手のターンで1回である。
     *
     * <p>この試験は「ターン番号の刻印」でしか通らない。真偽値 + {@code startTurnReset()} の形に
     * 差し替えると、{@code startTurnReset} がターンプレイヤーにしか走らないため、
     * 相手のターンぶん(2枚目)が引けずに落ちる。
     */
    @Test
    void ロロイヨのターンに1回は毎ターンリセットされる() {
        AutoGameFixture f = newGame(LOLOIYO);
        f.giveMana(f.me(), 9);
        int before = f.me().getDeck().size();
        game.playCard(f.room(), "me", f.giveHand(f.me(), GUARD), List.of(), false); // 自ターン: 1枚

        game.endTurn(f.room(), "me");     // 相手のターン(自分は引かない)
        game.nextPhase(f.room(), "you");  // マナチャージフェイズ → メインフェイズ
        f.giveMana(f.you(), 5);
        game.playCard(f.room(), "you", f.giveHand(f.you(), GUARD), List.of(), false); // 相手ターン: 1枚

        game.endTurn(f.room(), "you");    // 自分のターン(beginTurn で1枚引く)
        game.nextPhase(f.room(), "me");
        game.playCard(f.room(), "me", f.giveHand(f.me(), GUARD), List.of(), false); // 次の自ターン: 1枚

        assertThat(before - f.me().getDeck().size())
                .as("誘発3枚 + 自分のターン開始のドロー1枚")
                .isEqualTo(4);
    }

    /**
     * ★マスター裁定193: 「場に出る」は召喚に限らず、効果による「出す」も含む
     * (= {@code ON_ENTER} と同じ範囲)。
     *
     * <p>聖なる降誕の儀式は手札の【守護】ミニオンを<b>コストを支払わず場に出す</b>スペルであり、
     * 召喚ではない。発火口が {@code GameService.summonToField} にしか無ければ、ここで落ちる。
     */
    @Test
    void ロロイヨは効果で場に出たミニオンでも引く() {
        AutoGameFixture f = newGame(LOLOIYO);
        f.giveMana(f.me(), 9);
        int spell = f.giveHand(f.me(), HOLY_BIRTH);
        f.giveHand(f.me(), GUARD);
        int before = f.me().getDeck().size();
        // ★★Batch 68(裁定308): 出す1体は<b>割り込み</b>で選ぶ。
        //   候補に「素材を確保できる進化」を入れるには盤面を見なければならず、
        //   宣言時の対象指定では表せないためである
        game.playCard(f.room(), "me", spell, List.of(), false);
        f.answerChoice(game, "me", f.handPosition(f.me(), GUARD));
        assertThat(f.fieldIds(f.me())).containsExactly(GUARD);
        assertThat(before - f.me().getDeck().size())
                .as("効果で出しても【守護】の登場である")
                .isEqualTo(1);
    }

    // ==================================================================
    // 海獣リューグー(QTE-M-WATER-35)
    // 「【知識】【突進】【召喚時】自分の場に【潜伏】を持つミニオンが居るならカードを1枚引く」
    // ==================================================================

    @Test
    void リューグーは潜伏が居なければ知識の1枚しか引かない() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 5);
        int before = f.me().getDeck().size();
        game.playCard(f.room(), "me", f.giveHand(f.me(), RYUGU), List.of(), false);
        assertThat(before - f.me().getDeck().size())
                .as("【知識】の登場時ドローだけ")
                .isEqualTo(1);
    }

    @Test
    void リューグーは自分の場に潜伏が居ると2枚引く() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 5);
        f.putOnField(f.me(), STEALTH);
        int before = f.me().getDeck().size();
        game.playCard(f.room(), "me", f.giveHand(f.me(), RYUGU), List.of(), false);
        assertThat(before - f.me().getDeck().size())
                .as("【知識】1枚 + 【召喚時】1枚")
                .isEqualTo(2);
    }

    /** ★「自分の場に」と書いてある。相手の場の【潜伏】では引かない */
    @Test
    void リューグーは相手の場の潜伏では引かない() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 5);
        f.putOnField(f.you(), STEALTH);
        int before = f.me().getDeck().size();
        game.playCard(f.room(), "me", f.giveHand(f.me(), RYUGU), List.of(), false);
        assertThat(before - f.me().getDeck().size()).isEqualTo(1);
    }

    // ==================================================================
    // 潮獣ビシャカワ(QTE-M-WATER-36・スペル)
    // 「自分の【潜伏】の数自分のリーダーのHPを1回復を行う。」
    // ==================================================================

    @Test
    void ビシャカワは自分の潜伏の数だけ回復する() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 5);
        f.me().setLp(10);
        f.putOnField(f.me(), STEALTH);
        f.putOnField(f.me(), BOTH); // 【潜伏】【守護】。潜伏を持つので数える
        game.playCard(f.room(), "me", f.giveHand(f.me(), BISHAKAWA), List.of(), false);
        assertThat(f.me().getLp()).isEqualTo(12);
    }

    @Test
    void ビシャカワは潜伏が居なければ回復しない() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 5);
        f.me().setLp(10);
        f.putOnField(f.me(), GUARD); // 【守護】だけ
        game.playCard(f.room(), "me", f.giveHand(f.me(), BISHAKAWA), List.of(), false);
        assertThat(f.me().getLp()).isEqualTo(10);
    }

    /** ★数えるのは自分の場だけである(影潜む水刺客と同じ参照) */
    @Test
    void ビシャカワは相手の場の潜伏を数えない() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 5);
        f.me().setLp(10);
        f.putOnField(f.me(), STEALTH);
        f.putOnField(f.you(), STEALTH);
        f.putOnField(f.you(), STEALTH);
        game.playCard(f.room(), "me", f.giveHand(f.me(), BISHAKAWA), List.of(), false);
        assertThat(f.me().getLp()).isEqualTo(11);
    }

    // ==================================================================
    // 潮獣コアンチ(QTE-M-WATER-37・スペル)
    // 「自分のリーダーのHPを2回復。カードを1枚引き、カードを1枚捨てる。」
    // ==================================================================

    @Test
    void コアンチは2回復して1枚引き選んだ1枚を捨てる() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 5);
        f.me().setLp(10);
        int spell = f.giveHand(f.me(), COANCHI);
        f.giveHand(f.me(), STEALTH);
        int before = f.me().getDeck().size();

        game.playCard(f.room(), "me", spell, List.of(), false);
        assertThat(f.me().getLp()).as("回復は引く前に済んでいる").isEqualTo(12);
        assertThat(before - f.me().getDeck().size()).isEqualTo(1);
        assertThat(f.me().getPendingChoice())
                .as("捨てる1枚を問い合わせている(必須)")
                .isNotNull();

        game.resolveChoice(f.room(), "me", List.of(0)); // 手札の先頭(海獣タウギーナ)を捨てる
        assertThat(f.me().getTrash()).contains(STEALTH);
        assertThat(f.me().getHand()).doesNotContain(STEALTH);
        assertThat(f.me().getPendingChoice()).isNull();
    }

    /**
     * ★捨てるのは必須(min=1)である。選ばない選択肢が無いことを、
     * 「0枚を送ると弾かれる」で測る —— アクア・サーチと同じ形である。
     */
    @Test
    void コアンチのディスカードは省略できない() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 5);
        int spell = f.giveHand(f.me(), COANCHI);
        f.giveHand(f.me(), STEALTH);
        game.playCard(f.room(), "me", spell, List.of(), false);
        assertThatThrownBy(() -> game.resolveChoice(f.room(), "me", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================================================================
    // ギガマウス・バイト(QTE-M-WATER-38・スペル)
    // 「自分の手札の枚数このカードのコスト-1
    //   自分の手札から水文明のミニオンを3体場に出す。それは【突進】を得る。」
    // ==================================================================

    /**
     * ★マスター裁定190: 数える手札には<b>このカード自身を含む</b>。
     * 手札4枚(ギガマウス + 3枚)なら 15 - 4 = 11 である。
     * 自身を除外する実装なら 12 になり、ここで落ちる。
     */
    @Test
    void ギガマウスのコストは手札の枚数だけ下がり自身も数える() {
        AutoGameFixture f = newGame();
        f.giveHand(f.me(), GIGAMOUSE);
        f.giveHand(f.me(), STEALTH);
        f.giveHand(f.me(), STEALTH);
        f.giveHand(f.me(), STEALTH);
        assertThat(f.me().getHand()).hasSize(4);
        assertThat(stats.effectiveCost(f.state(), f.me(), f.card(GIGAMOUSE))).isEqualTo(11);
    }

    @Test
    void ギガマウスのコストは0を下回らない() {
        AutoGameFixture f = newGame();
        for (int i = 0; i < 20; i++) {
            f.giveHand(f.me(), STEALTH);
        }
        f.giveHand(f.me(), GIGAMOUSE);
        assertThat(stats.effectiveCost(f.state(), f.me(), f.card(GIGAMOUSE))).isZero();
    }

    @Test
    void ギガマウスは選んだ水ミニオン3体を突進つきで場に出す() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 11);
        int spell = f.giveHand(f.me(), GIGAMOUSE);
        int a = f.giveHand(f.me(), STEALTH);
        int b = f.giveHand(f.me(), STEALTH);
        int c = f.giveHand(f.me(), STEALTH);

        game.playCard(f.room(), "me", spell, List.of(hand(a, b, c)), false);

        assertThat(f.fieldIds(f.me())).containsExactly(STEALTH, STEALTH, STEALTH);
        assertThat(f.me().getMinionZone())
                .allMatch(m -> m.hasKeyword(Keyword.RUSH), "全員が【突進】を得ている");
        assertThat(f.me().getHand()).isEmpty();
    }

    /**
     * ★マスター裁定191: 手札の水ミニオンが3体に満たなくても<b>居るだけ出す</b>。
     * 「3体揃っていないと使えない」ではない。
     */
    @Test
    void ギガマウスは水ミニオンが2体しかなければ2体だけ出す() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 13);
        int spell = f.giveHand(f.me(), GIGAMOUSE);
        int a = f.giveHand(f.me(), STEALTH);
        int b = f.giveHand(f.me(), STEALTH);
        game.playCard(f.room(), "me", spell, List.of(hand(a, b)), false);
        assertThat(f.fieldIds(f.me())).containsExactly(STEALTH, STEALTH);
    }

    /** ★候補ゼロでも使用できる(空撃ちになるだけ)。裁定191 の下限 */
    @Test
    void ギガマウスは水ミニオンが手札に無くても使用できる() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 14);
        int spell = f.giveHand(f.me(), GIGAMOUSE);
        game.playCard(f.room(), "me", spell, List.of(hand()), false);
        assertThat(f.fieldIds(f.me())).isEmpty();
        assertThat(f.me().getTrash()).contains(GIGAMOUSE);
    }

    /** ★水文明でない手札は選べない(Filter.WATER_CIVILIZATION)。サーバが弾く */
    @Test
    void ギガマウスで水文明でないミニオンは選べない() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 13);
        int spell = f.giveHand(f.me(), GIGAMOUSE);
        int other = f.giveHand(f.me(), GUARD); // 風文明
        assertThatThrownBy(() -> game.playCard(f.room(), "me", spell, List.of(hand(other)), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(f.fieldIds(f.me())).as("弾かれたので盤面は変わらない").isEmpty();
    }

    /**
     * ★★★<b>Batch 68(裁定311)で結論がひっくり返った試験である。</b>
     *
     * <p>67 までは「効果による『出す』なので【召喚時】は発動しない」を測っていた
     * (設計判断19)。裁定311 は<b>手札から場に出た場合はすべて発動する</b>と定めたので、
     * 《ギガマウス・バイト》が手札から出したミニオンの【召喚時】は<b>発動する</b>。
     *
     * <p>★<b>消した試験ではなく、向きを変えた試験として残す</b>(裁定196)。
     * 名前ごと消すと、次の人は「この経路は誰も測っていない」と読む。
     * 水鏡の幻術師の「【召喚時】カードを2枚引く」が起きることで測る。
     */
    @Test
    void ギガマウスで出したミニオンの召喚時効果も発動する() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 13);
        int spell = f.giveHand(f.me(), GIGAMOUSE);
        int mirror = f.giveHand(f.me(), ON_SUMMON_DRAW);
        int before = f.me().getDeck().size();
        game.playCard(f.room(), "me", spell, List.of(hand(mirror)), false);
        assertThat(f.fieldIds(f.me())).containsExactly(ON_SUMMON_DRAW);
        assertThat(before - f.me().getDeck().size())
                .as("★裁定311: 手札から出たので【召喚時】の2ドローが起きる")
                .isEqualTo(2);
    }

    /** ★場が満杯で出せなかったぶんは手札に戻る(神の福音と同じ扱い) */
    @Test
    void ギガマウスは場が満杯なら出せなかったぶんを手札に戻す() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 12);
        for (int i = 0; i < 5; i++) {
            f.putOnField(f.me(), PLAIN_MINION); // 上限6のうち5枠を埋める
        }
        int spell = f.giveHand(f.me(), GIGAMOUSE);
        int a = f.giveHand(f.me(), STEALTH);
        int b = f.giveHand(f.me(), STEALTH);
        int c = f.giveHand(f.me(), STEALTH);
        game.playCard(f.room(), "me", spell, List.of(hand(a, b, c)), false);
        assertThat(f.me().getMinionZone()).hasSize(6);
        assertThat(f.me().getHand())
                .as("出せなかった2枚が手札に戻っている")
                .containsExactly(STEALTH, STEALTH);
    }

    // ==================================================================
    // アルキンティス(QTE-M-WATER-39・ウェポン)
    // 「【常在】自分の場の【知識】の枚数Attackを+1する」
    // ==================================================================

    @Test
    void アルキンティスは自分の場の知識の数だけ攻撃力が上がる() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 5);
        game.playCard(f.room(), "me", f.giveHand(f.me(), ARKINTIS), List.of(), false);
        assertThat(stats.effectiveWeaponAttack(f.state(), f.me()))
                .as("印刷Attackは0")
                .isZero();
        f.putOnField(f.me(), KNOWLEDGE);
        f.putOnField(f.me(), KNOWLEDGE);
        assertThat(stats.effectiveWeaponAttack(f.state(), f.me())).isEqualTo(2);
    }

    /**
     * ★【常在】は保存しない —— 評価するたびに場を見る(計画 2-1 の (a))。
     * 数え終えた後に場が減れば、攻撃力も戻る。
     */
    @Test
    void アルキンティスの攻撃力は場が減れば戻る() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 5);
        game.playCard(f.room(), "me", f.giveHand(f.me(), ARKINTIS), List.of(), false);
        f.putOnField(f.me(), KNOWLEDGE);
        assertThat(stats.effectiveWeaponAttack(f.state(), f.me())).isEqualTo(1);
        f.me().getMinionZone().clear();
        assertThat(stats.effectiveWeaponAttack(f.state(), f.me())).isZero();
    }

    /** ★「自分の場」である。相手の場の【知識】も、知識を持たない自分のミニオンも数えない */
    @Test
    void アルキンティスは相手の知識と知識でない味方を数えない() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 5);
        game.playCard(f.room(), "me", f.giveHand(f.me(), ARKINTIS), List.of(), false);
        f.putOnField(f.me(), STEALTH);       // 【潜伏】。知識ではない
        f.putOnField(f.you(), KNOWLEDGE);
        f.putOnField(f.you(), KNOWLEDGE);
        assertThat(stats.effectiveWeaponAttack(f.state(), f.me())).isZero();
    }
}
