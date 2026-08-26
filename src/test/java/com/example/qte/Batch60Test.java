package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.effect.CardEffectRegistry;
import com.example.qte.effect.TargetChoice;
import com.example.qte.game.GameService;
import com.example.qte.game.ManaCard;
import com.example.qte.game.MinionInstance;
import com.example.qte.game.PlayerState;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.support.AutoGameFixture;

/**
 * Batch 60(P6 仕上げ)の挙動の試験。
 *
 * <h2>このバッチが測るもの</h2>
 *
 * 60 は新しいカードを作るバッチではない。P5(作り直し)が終わったあとに残っていた
 * <b>積み残しの片付け</b>と、裁定277〜279 の確定の反映である。したがってここに並ぶのは
 * 「カードがテキストどおりに動くか」よりも<b>構造の番人</b>が多い。
 *
 * <ul>
 * <li><b>裁定278(c)</b> …… 墓地からの召喚に対象選択の導線が付いた。57〜59 のガードは外れた</li>
 * <li><b>裁定277(a)</b> …… 《神風の大号令》は「いるだけ必ず選ぶ」を強制しない</li>
 * <li><b>裁定279(a)</b> …… 《英知の水晶》の誘発は、誘発によるドローを数えない</li>
 * <li><b>裏向きマナ</b> …… 裏向きで置いても《豊穣の地霊主》は数える(本文どおりの読み)</li>
 * <li><b>{@code unlimitedCopies} の撤去</b> …… 同名無制限の仕組みはもうどこにも無い</li>
 * <li><b>プリセットデッキ</b> …… 6文明とも Ver1.1 の新カードを積んでいる</li>
 * </ul>
 *
 * <h2>本物の入口を通す</h2>
 *
 * {@link AutoGameFixture} の上に書き、効果は {@code GameService} の実際の入口から起こす
 * (裁定187)。{@code CardEffectRegistry.fire} を直接叩かない。
 */
@SpringBootTest
class Batch60Test {

    /** 常在効果を持たないリーダー(蒼海の賢者)。既定の対戦相手 */
    private static final String PLAIN_LEADER = "QTE-M-WATER-1";
    /** 黄泉の召喚主(リーダー)。サブフェイズに墓地から召喚できる */
    private static final String GRAVE_SUMMONER = "QTE-M-DARK-15";

    /** 執念の暗殺者(4/3/3)。【召喚時】ミニオン1体に3ダメージ = 対象を選ぶ【召喚時】 */
    private static final String SHADOW_ASSASSIN = "QTE-M-DARK-20";
    /** 腐敗の投擲者(2/2/1)。【召喚時】相手のミニオン1体に1ダメージ(側が SELF でない物差し) */
    private static final String ROT_THROWER = "QTE-M-DARK-17";
    /** 墓場の怨念集合体。【召喚時】墓地のスペルを1枚手札へ = Kind.TRASH を要求する唯一のミニオン */
    private static final String GRAVE_AGGREGATE = "QTE-M-DARK-22";
    /** スカイ・スワロー(1/1/1・【速攻】)。対象を選ばない最小のミニオン */
    private static final String SKY_SWALLOW = "QTE-M-WIND-3";
    /** ライト・シールド(2/1/3・【守護】)。殴られ役 */
    private static final String LIGHT_SHIELD = "QTE-M-LIGHT-2";
    /** マグマ・ストレート(スペル・1)。マナの中身に使う(対象候補に紛れないため) */
    private static final String MAGMA = "QTE-M-FIRE-10";
    /** 絶望の連鎖(闇のスペル・1)。墓地に置くスペルの見本 */
    private static final String DESPAIR_CHAIN = "QTE-M-DARK-9";

    /** 豊穣の地霊主(リーダー)。【常在】マナにカードが置かれたとき、そのターン2回目なら1ドロー */
    private static final String HARVEST_LEADER = "QTE-M-EARTH-15";
    /** ガイア・リソース(土のスペル・4)。山札から1枚を表向きでマナへ + 【還元】= 1回で2回置かれる */
    private static final String GAIA_RESOURCE = "QTE-M-EARTH-26";
    /** 大地の恵み(土のスペル・3)。表向きに1枚置くだけ(マナ10枚以上でなければ引かない) */
    private static final String EARTH_BLESSING = "QTE-M-EARTH-9";
    /** ピュア・エレメント(無文明・0)。自分自身を裏向きの<b>一時</b>マナとして置く */
    private static final String PURE_ELEMENT = "QTE-M-NONE-01";
    /** 神風の大号令(風のスペル・4)。自分のミニオンを2体破壊し、破壊した数だけ Attack+1【還元】 */
    private static final String KAMIKAZE = "QTE-M-WIND-12";
    /** 苗木植えの精霊(土・2)。【召喚時】自分の手札を1枚表向きでマナに置く */
    private static final String NURSERY_SPIRIT = "QTE-M-EARTH-16";
    /** 山札の目印(マナに置かれる1枚目) */
    private static final String MANA_SEED = "QTE-M-WATER-2";
    /** 山札の目印(引かれる1枚) */
    private static final String DRAW_MARK = "QTE-M-WATER-9";

    @Autowired
    GameService game;

    @Autowired
    CardEffectRegistry effects;

    @Autowired
    CardMasterRepository cards;


    private AutoGameFixture newGame(String myLeaderId) {
        AutoGameFixture f = new AutoGameFixture(cards, myLeaderId, PLAIN_LEADER);
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

    /** サブフェイズまで進める(墓地からの召喚はサブフェイズにしかできない) */
    private void toSubPhase(AutoGameFixture f) {
        game.nextPhase(f.room(), "me"); // メイン → バトル
        game.nextPhase(f.room(), "me"); // バトル → サブ
    }

    private static TargetChoice minions(String... instanceIds) {
        return new TargetChoice(null, List.of(instanceIds), null, null, null);
    }

    private static TargetChoice trash(Integer... indexes) {
        return new TargetChoice(null, null, null, List.of(indexes), null);
    }

    private static TargetChoice none() {
        return new TargetChoice(null, null, null, null, null);
    }

    private static TargetChoice hand(Integer... indexes) {
        return new TargetChoice(List.of(indexes), null, null, null, null);
    }

    // ==================================================================
    // 裏向きマナと fireManaPlaced の非対称(51 設計解説 6-2 の積み残し)
    //
    //   《豊穣の地霊主》: 「【常在】:マナにカードが置かれたとき
    //                      そのターン中それが2回目ならカードを1枚引く」
    //
    //   54〜59 は<b>表向きの配置しか数えていなかった</b>。本文は向きを条件にしていないので、
    //   本文どおりの読みは1つに定まる —— 60 で裏向きも数えるようにした。
    //   (裁定は仰いでいる。notes/batch60-ruling-requests.md 280)
    // ==================================================================

    /**
     * ★このバッチの主眼。《ガイア・リソース》は1枚で<b>2回</b>マナに置く ——
     * 効果で山札の上を表向きに1枚(1回目)、そのあと【還元】で自分自身を裏向きに1枚(2回目)。
     * 59 までは裏向きが数えられず、この盤面で1枚も引けなかった。
     */
    @Test
    void 豊穣の地霊主は還元による裏向きの配置も2回目として数える() {
        AutoGameFixture f = newGame(HARVEST_LEADER);
        payMana(f.me(), 4);
        f.stackDeck(f.me(), MANA_SEED, DRAW_MARK);

        game.playCard(f.room(), "me", f.giveHand(f.me(), GAIA_RESOURCE), List.of(), false);

        assertThat(f.me().getHand())
                .as("★1回目=表向き / 2回目=【還元】の裏向き。2回目で1枚引く")
                .containsExactly(DRAW_MARK);
        assertThat(f.me().getManaZone().stream().map(ManaCard::getCardId))
                .contains(MANA_SEED, GAIA_RESOURCE);
    }

    @Test
    void 豊穣の地霊主は表向き1回だけでは引かない() {
        AutoGameFixture f = newGame(HARVEST_LEADER);
        payMana(f.me(), 3);
        f.stackDeck(f.me(), MANA_SEED, DRAW_MARK);

        game.playCard(f.room(), "me", f.giveHand(f.me(), EARTH_BLESSING), List.of(), false);

        assertThat(f.me().getHand()).as("1回目の配置では引かない").isEmpty();
    }

    /**
     * ★<b>一時マナも「置かれた」1回である。</b>
     * 《ピュア・エレメント》はターンの終わりに消滅するが、
     * 《豊穣の地霊主》が見ているのは<b>置かれたこと</b>であって、
     * 置かれたものが残るかどうかではない。
     */
    @Test
    void 豊穣の地霊主はピュアエレメントの一時マナも1回として数える() {
        AutoGameFixture f = newGame(HARVEST_LEADER);
        payMana(f.me(), 2);
        f.stackDeck(f.me(), DRAW_MARK);
        int seed = f.giveHand(f.me(), MANA_SEED);           // マナに置かれる手札
        int nursery = f.giveHand(f.me(), NURSERY_SPIRIT);   // 【召喚時】手札1枚を表向きでマナへ

        game.playCard(f.room(), "me", nursery, List.of(hand(seed)), false); // 1回目(表向き)
        game.playCard(f.room(), "me", f.giveHand(f.me(), PURE_ELEMENT), List.of(), false); // 2回目

        assertThat(f.me().getHand()).as("★2回目の配置で1枚引く").containsExactly(DRAW_MARK);
    }

    /**
     * 裏向きだけで2回置いても数える —— 向きの組み合わせを条件にしていないことの番人。
     * 《喧嘩上等》は相手のミニオンを裏向きでマナに置く(自分のマナゾーンではない)ので使えない。
     * ここでは【還元】を2枚続けて使う。
     */
    @Test
    void 豊穣の地霊主は裏向きだけで2回置かれても数える() {
        AutoGameFixture f = newGame(HARVEST_LEADER);
        payMana(f.me(), 8);
        f.stackDeck(f.me(), DRAW_MARK);
        // ★道具はミニオン0体で使う《神風の大号令》である —— 何も破壊しないので
        //   マナに置かれるのは【還元】の1回だけであり、1枚 = 1回と数えやすい
        //   (《ガイア・リソース》だと1枚で2回置かれてしまい、何を測っているか分からなくなる)
        game.playCard(f.room(), "me", f.giveHand(f.me(), KAMIKAZE), List.of(none()), false);
        assertThat(f.me().getHand()).as("1回目の裏向き配置では引かない").isEmpty();

        game.playCard(f.room(), "me", f.giveHand(f.me(), KAMIKAZE), List.of(none()), false);

        assertThat(f.me().getHand()).as("★2回目の裏向き配置で引く").containsExactly(DRAW_MARK);
    }

    // ==================================================================
    // ★★Batch 66: プリセットデッキの3件をここから外した。
    //
    //   60 は「プリセットが6文明とも Ver1.1 の新カード10種と進化3種を積み、
    //   進化の素材が同じデッキに居ること」を測っていた。
    //   ★<b>66 でプリセットデッキそのものが退役した</b>(通常モードはデッキファイル必須)。
    //   測る相手が消えたので、試験も一緒に外す —— 残すと
    //   「今も配られているデッキがある」と次に読む人に思わせる(裁定178・196)。
    //
    //   ★<b>失われた保証を書き残しておく</b>: 「新カードが実際にデッキへ入る形になっているか」
    //   を確かめる場所は、これで無くなった。デッキを組むのは人間(デッキメーカー)であり、
    //   組まれたデッキが検証を通ることは DeckValidatorTest が測っている。
    // ==================================================================

    // ==================================================================
    // 277. 神風の大号令(QTE-M-WIND-12)—— マスター裁定277(a)
    //   「いるだけ必ず選ぶ」は強制しない。2体いても1体・0体だけ選べる。
    //   ★<b>コードは0行である。</b>対象要求を upTo(0〜2体)にしてあるからそうなる。
    //   だからこそ試験を置く —— 次の人が「盤面に依存する最小要求数」を作りたくなったときに、
    //   それが裁定で否定された道であることを伝えるのはこの試験だけである。
    // ==================================================================

    @Test
    void 神風の大号令は2体いても1体だけ選べる() {
        AutoGameFixture f = newGame(PLAIN_LEADER);
        payMana(f.me(), 4);
        MinionInstance a = f.putOnField(f.me(), SKY_SWALLOW);
        MinionInstance b = f.putOnField(f.me(), SKY_SWALLOW);

        game.playCard(f.room(), "me", f.giveHand(f.me(), KAMIKAZE),
                List.of(minions(a.getInstanceId())), false);

        assertThat(f.me().getMinionZone())
                .as("★選ばなかった1体は場に残る(裁定277(a))").containsExactly(b);
    }

    @Test
    void 神風の大号令の対象要求は好きな数を選べる形である() {
        assertThat(effects.targetSpecOf(KAMIKAZE).requirements())
                .as("要求は1件").hasSize(1);
        assertThat(effects.targetSpecOf(KAMIKAZE).requirements().get(0).upTo())
                .as("★upTo(0〜2体)である。固定2体にすると1体しか居ない側が使えなくなる(裁定269(b))")
                .isTrue();
    }

    // ==================================================================
    // 278. 黄泉の召喚主(QTE-M-DARK-15)—— マスター裁定278(c)
    //   Batch 57〜59 は「【召喚時】に対象を選ぶミニオンは墓地から召喚できません」と
    //   理由を返して止めていた。止めていたのは<b>対象を運ぶ口が無かった</b>ためであり、
    //   ルールとしてそう決まっていたからではない。
    //   → 裁定278(c): 対象選択の導線を新設する。ガードは外れた
    // ==================================================================

    @Test
    void 黄泉の召喚主は召喚時に対象を選ぶミニオンを墓地から召喚できる() {
        AutoGameFixture f = newGame(GRAVE_SUMMONER);
        payMana(f.me(), 4);
        f.me().getTrash().add(SHADOW_ASSASSIN);
        MinionInstance victim = f.putOnField(f.you(), LIGHT_SHIELD); // 2/1/3
        toSubPhase(f);

        game.summonFromGrave(f.room(), "me", 0, List.of(minions(victim.getInstanceId())));

        assertThat(f.fieldIds(f.me())).containsExactly(SHADOW_ASSASSIN);
        assertThat(f.me().getTrash()).as("召喚したカードは墓地から消える").isEmpty();
        assertThat(f.you().getMinionZone())
                .as("【召喚時】の3ダメージがHP3の守護を落とす").doesNotContain(victim);
    }

    @Test
    void 黄泉の召喚主は対象の指定が無ければ召喚できない() {
        AutoGameFixture f = newGame(GRAVE_SUMMONER);
        payMana(f.me(), 4);
        f.me().getTrash().add(SHADOW_ASSASSIN);
        f.putOnField(f.you(), LIGHT_SHIELD);
        toSubPhase(f);

        assertThatThrownBy(() -> game.summonFromGrave(f.room(), "me", 0, List.of()))
                .hasMessageContaining("対象の指定が不足しています");
    }

    /**
     * ★<b>順序の番人。</b>60 は summonFromGrave を
     * 「検証 → 支払い → 墓地から取り除く」の順に組み替えた(通常召喚と同じ形)。
     * 検証で弾かれたときに<b>状態が1つも変わっていない</b>ことをここで測る ——
     * 逆順のままだと、拒否されたのにマナがタップされ墓地が減る。
     */
    @Test
    void 黄泉の召喚主は対象の検証で弾かれたとき盤面を1つも変えない() {
        AutoGameFixture f = newGame(GRAVE_SUMMONER);
        payMana(f.me(), 4);
        f.me().getTrash().add(SHADOW_ASSASSIN);
        f.putOnField(f.you(), LIGHT_SHIELD);
        toSubPhase(f);
        int mpBefore = f.me().getAvailableMp();

        assertThatThrownBy(() -> game.summonFromGrave(f.room(), "me", 0,
                List.of(minions("居ないミニオンのID"))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(f.me().getAvailableMp()).as("MPは減っていない").isEqualTo(mpBefore);
        assertThat(f.me().getTrash()).as("墓地から消えていない").containsExactly(SHADOW_ASSASSIN);
        assertThat(f.me().getMinionZone()).as("場には出ていない").isEmpty();
    }

    /**
     * 【潜伏】や側(Side)の検証は、墓地からの召喚でもそのまま効く ——
     * 対象の正当性を見るのは {@code validateTargets} 1箇所だからである(裁定163)。
     */
    @Test
    void 黄泉の召喚主でも対象の側の検証はそのまま効く() {
        AutoGameFixture f = newGame(GRAVE_SUMMONER);
        payMana(f.me(), 2);
        f.me().getTrash().add(ROT_THROWER); // 【召喚時】<b>相手の</b>ミニオン1体に1ダメージ
        MinionInstance mine = f.putOnField(f.me(), LIGHT_SHIELD);
        toSubPhase(f);

        assertThatThrownBy(() -> game.summonFromGrave(f.room(), "me", 0,
                List.of(minions(mine.getInstanceId()))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * ★<b>墓地から出すカード自身は、そのカードの対象には選べない</b>(★Batch 60 で新設)。
     *
     * <p>検証は墓地から取り除く<b>前</b>に行うため、その瞬間は出すカード自身も墓地に居る。
     * {@code Kind.TRASH} の要求はそれを普通の候補として見てしまうので、専用の門で塞いだ。
     * 今の235枚では《墓場の怨念集合体》の絞り込み({@code SPELL_CARD})が偶然守っているが、
     * 絞り込みが守っているだけの穴は、次の1枚で開く。
     */
    @Test
    void 墓地から出すカード自身は対象に選べない() {
        AutoGameFixture f = newGame(GRAVE_SUMMONER);
        payMana(f.me(), 10);
        f.me().getTrash().add(GRAVE_AGGREGATE); // 0番目 = 出どころ
        f.me().getTrash().add(DESPAIR_CHAIN);   // 1番目 = 本来の対象(スペル)
        toSubPhase(f);

        assertThatThrownBy(() -> game.summonFromGrave(f.room(), "me", 0, List.of(trash(0))))
                .hasMessageContaining("墓地から出すカード自身は対象に選べません");
    }

    @Test
    void 墓地から出すカードの召喚時は同じ墓地の別のカードを対象にできる() {
        AutoGameFixture f = newGame(GRAVE_SUMMONER);
        payMana(f.me(), 10);
        f.me().getTrash().add(GRAVE_AGGREGATE);
        f.me().getTrash().add(DESPAIR_CHAIN);
        toSubPhase(f);

        game.summonFromGrave(f.room(), "me", 0, List.of(trash(1)));

        assertThat(f.fieldIds(f.me())).containsExactly(GRAVE_AGGREGATE);
        assertThat(f.me().getHand()).as("【召喚時】が墓地のスペルを手札へ加える")
                .contains(DESPAIR_CHAIN);
        assertThat(f.me().getTrash()).as("出どころも対象も墓地から出ていった").isEmpty();
    }

    @Test
    void 黄泉の召喚主は対象を選ばないミニオンなら対象を空で召喚できる() {
        AutoGameFixture f = newGame(GRAVE_SUMMONER);
        payMana(f.me(), 1);
        f.me().getTrash().add(SKY_SWALLOW);
        toSubPhase(f);

        game.summonFromGrave(f.room(), "me", 0, List.of());

        assertThat(f.fieldIds(f.me())).containsExactly(SKY_SWALLOW);
    }

    /**
     * ★<b>ガードそのものが消えていることを測る。</b>
     * 「墓地からは召喚できません」という理由でカードが弾かれる道は、もう存在しない。
     * これが残っていると、次の人は「対象を選ぶ【召喚時】は今も止まっている」と読む。
     */
    @Test
    void 対象を選ぶという理由で墓地からの召喚を止める道はもう無い() {
        AutoGameFixture f = newGame(GRAVE_SUMMONER);
        payMana(f.me(), 4);
        f.me().getTrash().add(SHADOW_ASSASSIN);
        MinionInstance victim = f.putOnField(f.you(), LIGHT_SHIELD);
        toSubPhase(f);

        assertThat(effects.targetSpecOf(SHADOW_ASSASSIN).requirements())
                .as("このミニオンは今も対象を要求している").isNotEmpty();

        game.summonFromGrave(f.room(), "me", 0, List.of(minions(victim.getInstanceId())));

        assertThat(f.fieldIds(f.me())).containsExactly(SHADOW_ASSASSIN);
    }
}
