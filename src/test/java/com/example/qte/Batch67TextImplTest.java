package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.effect.CardEffectRegistry;
import com.example.qte.effect.StatCalculator;
import com.example.qte.effect.TargetChoice;
import com.example.qte.effect.TargetSpec;
import com.example.qte.game.GameService;
import com.example.qte.game.ManaCard;
import com.example.qte.game.MinionInstance;
import com.example.qte.game.PlayerState;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.support.AutoGameFixture;

/**
 * Batch 67(本文と実装の総点検)で見つかった食い違い5枚の試験。
 *
 * <h2>この5枚は何だったのか</h2>
 *
 * Ver0.4 由来の169枚のうち、P5(Batch 55〜59)の作り直しの対象<b>外</b>とされた48枚を
 * 1枚ずつ本文と突き合わせた結果である。5枚とも同じ形をしていた ——
 * <b>Ver1.1 で本文が差し替わったのに、実装もコメントも Ver0.4 のまま残っていた</b>。
 * 《不滅のネクロマンサー》(Batch 64・裁定303)の2〜6例目である。
 *
 * <table border="1">
 *   <caption>67 が直した5枚</caption>
 *   <tr><th>カード</th><th>Ver1.1 の本文</th><th>66 までの実装</th></tr>
 *   <tr><td>《大地震》</td><td>コスト4以下を破壊</td><td>コスト3以下</td></tr>
 *   <tr><td>《聖剣 エクスカリバー》</td><td>体力を全て回復</td><td>2回復</td></tr>
 *   <tr><td>《生贄を求める邪鬼》</td><td>自分2体+相手1体を破壊</td><td>自分1体・選ばなければ自壊</td></tr>
 *   <tr><td>《禁忌の墓地利用》</td><td>ミニオンでないカード2枚</td><td>スペル限定</td></tr>
 *   <tr><td>《ツイン・ストライク》</td><td>自分の風文明ミニオン1体</td><td>文明を見ていない</td></tr>
 * </table>
 *
 * <h2>★5枚を直しても、既存の JUnit は1件も落ちなかった</h2>
 *
 * 700件を回して赤は0である。<b>「落ちなかった = 変えていない」ではない</b> ——
 * この5枚は<b>そもそも1件も測られていなかった</b>
 * ({@code notes/rework-triage.md} 5章が P5 のときに書いた注意そのままの形である)。
 * だからこのクラスが要る。
 *
 * <h2>測り方</h2>
 *
 * {@link AutoGameFixture} の上に書き、効果は {@code GameService.playCard} /
 * {@code leaderAttack} の<b>本物の入口</b>から起こす(裁定187)。
 * 数値は<b>試験の側に直接書く</b> —— 実装から読むと、実装を壊しても落ちない試験になる(裁定298)。
 */
@SpringBootTest
class Batch67TextImplTest {

    /** 常在効果を持たないリーダー(蒼海の賢者)。既定の対戦相手 */
    private static final String PLAIN_LEADER = "QTE-M-WATER-1";

    // ---- 67 が直した5枚 ----
    private static final String EARTHQUAKE = "QTE-M-EARTH-11";     // 大地震(スペル・4)
    private static final String EXCALIBUR = "QTE-M-LIGHT-14";      // 聖剣 エクスカリバー(ウェポン・4)
    private static final String SACRIFICE_FIEND = "QTE-M-DARK-3";  // 生贄を求める邪鬼(3/3/3)
    private static final String TABOO_GRAVE_USE = "QTE-M-DARK-25"; // 禁忌の墓地利用(スペル・4)
    private static final String TWIN_STRIKE = "QTE-M-WIND-11";     // ツイン・ストライク(スペル・3)

    // ---- 道具として使うカード ----
    /** スカイ・スワロー(風・1/1/1・【速攻】)。ドローを起こさない最小の風文明ミニオン */
    private static final String SKY_SWALLOW = "QTE-M-WIND-3";
    /** 海獣タウギーナ(水・1/1/1・【潜伏】)。効果を持たない<b>風でない</b>ミニオン */
    private static final String SEA_BEAST = "QTE-M-WATER-33";
    /** ディープシー・シャーク(水・4/4/3)。コスト<b>4</b>の物差し */
    private static final String SHARK = "QTE-M-WATER-6";
    /** 水鏡の幻術師(水・5/5/3)。コスト<b>5</b>の物差し */
    private static final String MIRROR_MAGE = "QTE-M-WATER-7";
    /** 知識の守護者(水・4/0/5・【守護】)。HP5 なので「全快」と「2回復」を見分けられる */
    private static final String KNOWLEDGE_GUARDIAN = "QTE-M-WATER-5";
    /** マグマ・ストレート(火・スペル・1)。ミニオン1体に3ダメージ */
    private static final String MAGMA = "QTE-M-FIRE-10";
    /** 死神の大鎌(闇・ウェポン・1)。墓地に置く<b>ミニオンでないカード</b> */
    private static final String REAPER_SCYTHE = "QTE-M-DARK-13";
    /** 海淵獣ゾクシム(水・進化)。種別が EVOLUTION の物差し */
    private static final String EVOLUTION_CARD = "QTE-M-WATER-32";
    /** 英霊・タイガラム(光・進化・7・【守護】)。光文明の踏み倒しを両方通ってしまっていた1枚 */
    private static final String GUARD_EVOLUTION = "QTE-M-LIGHT-32";
    /** ライト・シールド(光・2/1/3・【守護】)。★Batch 68: タイガラムの唯一の素材条件を満たす */
    private static final String LIGHT_SHIELD = "QTE-M-LIGHT-2";
    /** 聖なる降誕の儀式(光・スペル・?)。コスト7以下の【守護】ミニオンを踏み倒す */
    private static final String HOLY_NATIVITY = "QTE-M-LIGHT-11";
    /** 神の福音(光・スペル・?)。光文明の【守護】ミニオンを最大2体踏み倒す */
    private static final String GOSPEL = "QTE-M-LIGHT-12";

    @Autowired
    GameService game;

    @Autowired
    CardEffectRegistry effects;

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

    private static TargetChoice minions(String... instanceIds) {
        return new TargetChoice(null, List.of(instanceIds), null, null, null);
    }

    private static TargetChoice trash(Integer... indexes) {
        return new TargetChoice(null, null, null, List.of(indexes), null);
    }

    // ==================================================================
    // 1. 大地震(QTE-M-EARTH-11)
    //   Ver1.1: 「お互いのコスト4以下のミニオンをすべて破壊。」
    //   66 まで: コスト<b>3</b>以下(Ver0.4 のまま)
    // ==================================================================

    @Test
    void 大地震はコスト4のミニオンを破壊する() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        MinionInstance mine = f.putOnField(f.me(), SHARK);    // コスト4
        MinionInstance theirs = f.putOnField(f.you(), SHARK); // コスト4

        game.playCard(f.room(), "me", f.giveHand(f.me(), EARTHQUAKE), List.of(), false);

        assertThat(f.me().getMinionZone())
                .as("★コスト4は破壊される(66 までは 3以下しか見ていなかった)")
                .doesNotContain(mine);
        assertThat(f.you().getMinionZone()).as("相手のコスト4も破壊される")
                .doesNotContain(theirs);
    }

    @Test
    void 大地震はコスト5のミニオンを破壊しない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        MinionInstance survivor = f.putOnField(f.you(), MIRROR_MAGE); // コスト5

        game.playCard(f.room(), "me", f.giveHand(f.me(), EARTHQUAKE), List.of(), false);

        assertThat(f.you().getMinionZone())
                .as("★空振りでないことの証拠: 上限は 4 であって「全部」ではない")
                .contains(survivor);
    }

    // ==================================================================
    // 2. 聖剣 エクスカリバー(QTE-M-LIGHT-14)
    //   Ver1.1: 「自分のリーダーが攻撃した時、自分の場にいる【守護】を持つ
    //            ミニオンすべての体力を<b>全て回復</b>。」
    //   66 まで: <b>2回復</b>(Ver0.4 のまま)
    // ==================================================================

    @Test
    void エクスカリバーは守護ミニオンの体力を全快させる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        MinionInstance guard = f.putOnField(f.me(), KNOWLEDGE_GUARDIAN); // 0/5・【守護】
        int maxHp = guard.getMaxHp();

        // 本物の入口で3ダメージを与える(HP 5 → 2)
        game.playCard(f.room(), "me", f.giveHand(f.me(), MAGMA),
                List.of(minions(guard.getInstanceId())), false);
        assertThat(guard.getCurrentHp()).as("前提: 3ダメージを受けている").isEqualTo(maxHp - 3);

        game.playCard(f.room(), "me", f.giveHand(f.me(), EXCALIBUR), List.of(), false);
        game.nextPhase(f.room(), "me"); // メイン → バトル
        game.leaderAttack(f.room(), "me", null);

        assertThat(guard.getCurrentHp())
                .as("★全快する(66 までは2回復だったので %d のはずだった)", maxHp - 1)
                .isEqualTo(maxHp);
    }

    @Test
    void エクスカリバーは守護を持たないミニオンを回復しない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        MinionInstance plain = f.putOnField(f.me(), SHARK); // 4/3・守護なし
        int maxHp = plain.getMaxHp();
        plain.takeDamage(2);

        game.playCard(f.room(), "me", f.giveHand(f.me(), EXCALIBUR), List.of(), false);
        game.nextPhase(f.room(), "me");
        game.leaderAttack(f.room(), "me", null);

        assertThat(plain.getCurrentHp())
                .as("★空振りでないことの証拠: 回復するのは【守護】持ちだけである")
                .isEqualTo(maxHp - 2);
    }

    // ==================================================================
    // 3. 生贄を求める邪鬼(QTE-M-DARK-3)
    //   Ver1.1: 「【召喚時】自分のミニオン2体を破壊する。相手のミニオンを1体破壊する。」
    //   66 まで: 「自分の他のミニオン1体を破壊しなければ、このミニオンを破壊する」
    //            (Ver0.4 のまま。<b>相手への破壊が丸ごと無かった</b>)
    // ==================================================================

    /**
     * ★★★Batch 68(裁定282・305): 本文の<b>2つの文を順に問う</b>形になった。
     * 67 は「自分から2体」「相手から1体」を2件の要求として宣言時に選ばせていたが、
     * 1件の {@code PendingChoice} は1つの要求しか運べない。
     */
    @Test
    void 生贄を求める邪鬼は自分2体と相手1体を破壊する() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        MinionInstance mine1 = f.putOnField(f.me(), SKY_SWALLOW);
        MinionInstance mine2 = f.putOnField(f.me(), SKY_SWALLOW);
        MinionInstance theirs = f.putOnField(f.you(), SKY_SWALLOW);

        game.playCard(f.room(), "me", f.giveHand(f.me(), SACRIFICE_FIEND), List.of(), false);
        // 1文目: 破壊する自分のミニオン。★邪鬼自身も候補に入る(裁定305(b-1))が、ここでは選ばない
        f.answerChoice(game, "me", mine1.getInstanceId(), mine2.getInstanceId());
        // 2文目は候補が1体しかないので自動で決まる(裁定302)

        assertThat(f.me().getMinionZone()).as("自分の2体は破壊される")
                .doesNotContain(mine1).doesNotContain(mine2);
        assertThat(f.you().getMinionZone())
                .as("★相手のミニオンも破壊される(66 までは1体も壊せなかった)")
                .doesNotContain(theirs);
        assertThat(f.me().getMinionZone())
                .as("★自分自身は場に残る(自壊するカードではなくなった)")
                .hasSize(1);
    }

    /**
     * ★★★<b>裁定305(b-1)(c-1)がここで初めて成立する。</b>
     * 「自分のミニオン2体」にはこのミニオン<b>自身</b>も含まれ、
     * 自身を壊しても<b>相手への破壊は続く</b>。
     *
     * <p>66 までは構造的に測れなかった —— 対象を選ぶのが<b>場に出る前</b>だったので、
     * 自身は候補にすら現れなかったのである(裁定282 が変えたのはまさにここ)。
     */
    @Test
    void 生贄を求める邪鬼は自分自身を壊しても相手への破壊が続く() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        MinionInstance theirs = f.putOnField(f.you(), SKY_SWALLOW);

        game.playCard(f.room(), "me", f.giveHand(f.me(), SACRIFICE_FIEND), List.of(), false);
        MinionInstance fiend = f.me().getMinionZone().get(0);
        assertThat(f.me().getPendingChoice().candidates())
                .as("★自分自身が候補に入る(裁定305(b-1))").contains(fiend.getInstanceId());

        f.answerChoice(game, "me", fiend.getInstanceId());

        assertThat(f.me().getMinionZone()).as("自身は破壊された").isEmpty();
        assertThat(f.you().getMinionZone())
                .as("★それでも相手への破壊は続く(裁定305(c-1))").doesNotContain(theirs);
    }

    @Test
    void 生贄を求める邪鬼は場が空でも召喚できる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);

        game.playCard(f.room(), "me", f.giveHand(f.me(), SACRIFICE_FIEND), List.of(), false);
        // ★自身が唯一の候補になる。「選ばない」と答える(upTo なので許される)
        f.answerChoiceNone(game, "me");

        assertThat(f.me().getMinionZone())
                .as("★候補が0体でも召喚そのものは通る(裁定191: あるだけ破壊する)")
                .hasSize(1);
    }

    /**
     * ★★★Batch 68(裁定282): 要求は<b>1件に減った</b>。
     *
     * <p>67 は自分の場と相手の場を2件の要求として並べていた。
     * 対象が割り込みへ移り、1件の {@code PendingChoice} が1つの要求しか運べないため、
     * <b>2文目は独立した再開先</b>({@code ResumePoint.SACRIFICE_FIEND_OPPONENT})になった。
     * ★<b>測っている性質は減っていない</b> —— 「自分は2体まで」「相手の場も見る」は
     * 上の2つの試験が実際の破壊で測る。ここは<b>要求が1件であること</b>を固定して、
     * 次の人が2件目を足し戻すのを止める(足すと割り込みが1件目しか問わなくなる)。
     */
    @Test
    void 生贄を求める邪鬼の要求は1件で自分の場を2体まで見る() {
        TargetSpec spec = effects.targetSpecOf(SACRIFICE_FIEND);
        assertThat(spec.requirements())
                .as("★要求は1件(2文目は割り込みの再開先が扱う)").hasSize(1);
        assertThat(spec.requirements().get(0).side()).isEqualTo(TargetSpec.Side.SELF);
        assertThat(spec.requirements().get(0).count())
                .as("★自分は2体まで(66 までは1体だった)").isEqualTo(2);
        assertThat(spec.requirements().get(0).upTo())
                .as("★居るだけ破壊する(裁定191)。固定数だと場が空のとき召喚が弾かれる")
                .isTrue();
    }

    // ==================================================================
    // 4. 禁忌の墓地利用(QTE-M-DARK-25)
    //   Ver1.1: 「自分の墓地にある<b>ミニオンでないカード</b>を2枚選び
    //            マナゾーンに裏向きで置く。」
    //   66 まで: <b>スペル</b>限定(Ver0.4 のまま。ウェポンを拾えなかった)
    // ==================================================================

    @Test
    void 禁忌の墓地利用は墓地のウェポンをマナに置ける() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        f.me().getTrash().add(REAPER_SCYTHE); // 墓地[0] = ウェポン
        f.me().getTrash().add(MAGMA);         // 墓地[1] = スペル
        int manaBefore = f.me().getManaZone().size();

        game.playCard(f.room(), "me", f.giveHand(f.me(), TABOO_GRAVE_USE),
                List.of(trash(0, 1)), false);

        assertThat(f.me().getManaZone()).as("2枚ともマナに置かれる")
                .hasSize(manaBefore + 2);
        assertThat(f.me().getTrash())
                .as("★ウェポンも墓地から動く(66 までは候補にすら入らなかった)")
                .doesNotContain(REAPER_SCYTHE);
    }

    @Test
    void 禁忌の墓地利用は墓地のミニオンを選べない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        // ★スペルを1枚混ぜてある。ミニオンだけだと<b>使用条件</b>のほうで先に弾かれ、
        //   絞り込みが効いているかを見られない(下の試験がそちらを測る)
        f.me().getTrash().add(MAGMA);       // 墓地[0] = スペル
        f.me().getTrash().add(SKY_SWALLOW); // 墓地[1] = ミニオン
        int handIndex = f.giveHand(f.me(), TABOO_GRAVE_USE);

        assertThatThrownBy(() ->
                game.playCard(f.room(), "me", handIndex, List.of(trash(1)), false))
                .as("★空振りでないことの証拠: 「ミニオンでない」は本当に絞り込んでいる")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ミニオンでないカード");
    }

    @Test
    void 禁忌の墓地利用は墓地の進化ミニオンも選べない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        f.me().getTrash().add(MAGMA);         // 墓地[0] = スペル(使用条件を満たすため)
        f.me().getTrash().add(EVOLUTION_CARD); // 墓地[1] = 進化ミニオン
        int handIndex = f.giveHand(f.me(), TABOO_GRAVE_USE);

        assertThatThrownBy(() ->
                game.playCard(f.room(), "me", handIndex, List.of(trash(1)), false))
                .as("★<b>進化ミニオンもミニオンである</b>(総合ルール2-1)。"
                        + "「ミニオンでない」を != MINION と書くと、ここが通ってしまう")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ミニオンでないカード");
    }

    @Test
    void 禁忌の墓地利用は墓地がミニオンだけなら使用できない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        f.me().getTrash().add(SKY_SWALLOW);
        int handIndex = f.giveHand(f.me(), TABOO_GRAVE_USE);

        assertThatThrownBy(() ->
                game.playCard(f.room(), "me", handIndex, List.of(none()), false))
                .as("★使用条件も同じ語彙で見ている(片方だけ直すと食い違う。裁定130)")
                .isInstanceOf(RuntimeException.class);
    }

    // ==================================================================
    // 5. ツイン・ストライク(QTE-M-WIND-11)
    //   Ver1.1: 「このターン中、自分の<b>風文明</b>ミニオン1体に
    //            「1ターンに2回攻撃できる」を付与。」
    //   66 まで: 文明を見ていなかった(Ver0.4 のまま)
    // ==================================================================

    @Test
    void ツインストライクは風文明のミニオンに2回攻撃を与える() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        MinionInstance windMinion = f.putOnField(f.me(), SKY_SWALLOW);
        assertThat(stats.maxAttacks(f.state(), f.me(), windMinion))
                .as("前提: もとは1回").isEqualTo(1);

        game.playCard(f.room(), "me", f.giveHand(f.me(), TWIN_STRIKE),
                List.of(minions(windMinion.getInstanceId())), false);

        assertThat(stats.maxAttacks(f.state(), f.me(), windMinion)).isEqualTo(2);
    }

    @Test
    void ツインストライクは風文明でないミニオンを選べない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        MinionInstance waterMinion = f.putOnField(f.me(), SEA_BEAST); // 水文明
        int handIndex = f.giveHand(f.me(), TWIN_STRIKE);

        assertThatThrownBy(() -> game.playCard(f.room(), "me", handIndex,
                List.of(minions(waterMinion.getInstanceId())), false))
                .as("★66 までは禁忌デッキから来た他文明のミニオンにも付いていた")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("風文明");
    }

    // ==================================================================
    // 6. 聖なる降誕の儀式(QTE-M-LIGHT-11)・神の福音(QTE-M-LIGHT-12)
    //   本文はどちらも「【守護】を持つ<b>ミニオン</b>」である。
    //   66 までの実装は種別を見ておらず、《英霊・タイガラム》(光文明・【守護】・コスト7)が
    //   <b>素材なしで場に出ていた</b>(裁定226 に反する)。
    //   67 は Filter.MINION_CARD を足して進化ミニオンを候補から外した ——
    //   ただし「今はこうするしかない」として、本文の読みは裁定308 で伺った。
    //
    // ★★★Batch 68(裁定308 b): マスターは<b>進化ミニオンも選べる</b>と決めた。
    //   「ただし必要な素材がない場合は進化ミニオンを選択できない」という但し書き付きである。
    //   → 67 の絞り込みを外し、代わりに<b>盤面を見る候補作り</b>へ移した。
    //     素材の有無は属性ではなく<b>規則</b>なので、宣言時の TargetSpec では表せない(裁定234)。
    // ==================================================================

    /**
     * ★★★裁定308(b): 素材を確保できる進化ミニオンは<b>選べる</b>。
     * 出るときには素材を問われ、素材つきで場に立つ(裁定226)。
     */
    @Test
    void 聖なる降誕の儀式は素材を確保できる進化ミニオンを出せる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        MinionInstance material = f.putOnField(f.me(), LIGHT_SHIELD); // タイガラムの素材
        f.giveHand(f.me(), GUARD_EVOLUTION);

        game.playCard(f.room(), "me", f.giveHand(f.me(), HOLY_NATIVITY), List.of(), false);
        f.answerChoice(game, "me", f.handPosition(f.me(), GUARD_EVOLUTION));
        f.answerChoice(game, "me", material.getInstanceId()); // 進化素材

        assertThat(f.fieldIds(f.me()))
                .as("★裁定308(b): 進化ミニオンも出せるようになった").contains(GUARD_EVOLUTION);
        assertThat(f.me().getMinionZone())
                .as("★素材は場から消えて進化の下に入る(裁定226)").hasSize(1);
    }

    /**
     * ★★★裁定308(b) の<b>但し書き</b>: 必要な素材が無ければ進化ミニオンは選べない。
     * ★<b>「そうでない側」を測る</b>(裁定181)—— これが無いと
     * 「進化は無条件で選べる」実装でも上の試験は通ってしまう。
     */
    @Test
    void 聖なる降誕の儀式は素材を確保できない進化ミニオンを選べない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        f.giveHand(f.me(), KNOWLEDGE_GUARDIAN); // 普通の【守護】(使用条件を満たすため)
        f.giveHand(f.me(), GUARD_EVOLUTION);    // ★素材が場に1体も居ない

        game.playCard(f.room(), "me", f.giveHand(f.me(), HOLY_NATIVITY), List.of(), false);

        assertThat(f.me().getPendingChoice().candidates())
                .as("★素材を確保できない進化は候補に現れない(裁定308(b) の但し書き)")
                .containsExactly(f.handPosition(f.me(), KNOWLEDGE_GUARDIAN));
    }

    @Test
    void 神の福音は素材を確保できない進化ミニオンを選べない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 6); // 《神の福音》はコスト6
        f.giveHand(f.me(), LIGHT_SHIELD);    // 光文明の【守護】(普通のミニオン)
        f.giveHand(f.me(), GUARD_EVOLUTION); // ★素材が場に1体も居ない

        game.playCard(f.room(), "me", f.giveHand(f.me(), GOSPEL), List.of(), false);

        assertThat(f.me().getPendingChoice().candidates())
                .as("★《聖なる降誕の儀式》と同じ規則で候補が作られる(裁定130)")
                .containsExactly(f.handPosition(f.me(), LIGHT_SHIELD));
    }

    @Test
    void 聖なる降誕の儀式は普通の守護ミニオンなら出せる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 5);
        f.giveHand(f.me(), KNOWLEDGE_GUARDIAN); // 4/0/5・【守護】

        game.playCard(f.room(), "me", f.giveHand(f.me(), HOLY_NATIVITY), List.of(), false);
        f.answerChoice(game, "me", f.handPosition(f.me(), KNOWLEDGE_GUARDIAN));

        assertThat(f.fieldIds(f.me()))
                .as("★空振りでないことの証拠: 進化を戻しても普通のミニオンは通る")
                .contains(KNOWLEDGE_GUARDIAN);
    }

    @Test
    void ツインストライクの要求は風文明で絞り込まれている() {
        TargetSpec spec = effects.targetSpecOf(TWIN_STRIKE);
        assertThat(spec.requirements().get(0).filters())
                .as("★絞り込みは Filter で行う(効果側で不発にしない。裁定302)")
                .contains(TargetSpec.Filter.WIND_CIVILIZATION);
    }
}
