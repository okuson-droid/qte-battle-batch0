package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.effect.CardEffectRegistry;
import com.example.qte.effect.EffectImplementation;
import com.example.qte.effect.StatCalculator;
import com.example.qte.game.DeckFactory;
import com.example.qte.master.CardMaster;
import com.example.qte.master.CardTextKeywords;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.CardType;

/**
 * 「効果未実装」の印の判定({@link EffectImplementation})の試験(★Batch 47 で新設)。
 *
 * <h2>この試験が守っているもの</h2>
 *
 * 印は<b>プレイヤーに向かって「このカードは書いてあるとおりには動きません」と言う</b>ものである。
 * 言い間違いは2方向にありうる。
 *
 * <ul>
 * <li><b>動くカードに印を付ける</b> —— 嘘をつくことになる。裁定164 が警告した誤判定であり、
 *     素朴に {@code CardEffectRegistry} の表だけを見ると<b>ルール側に実装された38枚</b>で起きる。</li>
 * <li><b>動かないカードに印を付けない</b> —— 黙って不発になる。46b までデッキ構築で
 *     弾いていたのは、まさにこれを避けるためだった。</li>
 * </ul>
 *
 * どちらも「集計が合っている」だけでは検出できないので、<b>両方向とも実物のカードで名指しして測る</b>。
 */
@SpringBootTest
class EffectImplementationTest {

    @Autowired
    EffectImplementation implementation;

    @Autowired
    CardEffectRegistry effects;

    @Autowired
    CardMasterRepository cards;

    @Autowired
    DeckFactory deckFactory;

    // ------------------------------------------------------------------
    // 枚数(裁定162 の例外。人が決めた数を置く)
    // ------------------------------------------------------------------

    /**
     * ★この数はカードの実装が進むたびに<b>減る</b>。P2(Batch 48〜53)で効果を1枚実装したら、
     * ここも1つ減らすこと。減らし忘れたら落ちるので、進捗の記録がテストとして残る。
     *
     * <p>内訳は {@code python3 tools/report_effects.py} が出す。
     * 47 の時点では 64枚 = 進化18 + 非進化46 だった。
     * 48(風文明の8枚)で 56枚 = 進化18 + 非進化38 になった。
     * 49(水文明の6枚)で 50枚 = 進化18 + 非進化32 になった。
     * 50(闇6枚 + 光6枚)で 38枚 = 進化18 + 非進化20 になった。
     * 51(火7枚 + 土8枚)で 23枚 = 進化18 + 非進化5 になった(P2 の終わり)。
     * ★<b>52(進化エンジン + 進化6枚 + 機神兵長茶爺)で 15枚 = 進化11 + 非進化4 になった。</b>
     * 内訳は Batch 53 が引き取る8枚(進化7 + 《英術・スケアロック》)と、
     * P4 が引き取る7枚(【賢魂】を持つ進化4枚 + 非進化3枚)である。
     *
     * <p>★<b>進化の素材条件は18枚すべて登録されているが、印はそれでは消えない。</b>
     * 素材条件は「効果」ではなく<b>場に出す手段</b>だからである
     * ({@code CardEffectRegistry.evolutions} は {@code isRegistered} が見ない)。
     */
    @Test
    void 効果未実装のカードは15枚である() {
        long unimplemented = cards.getAllCards().stream().filter(implementation::isUnimplemented).count();
        assertThat(unimplemented).isEqualTo(15);
    }

    @Test
    void 印が付くカードのうち進化ミニオンは11枚で残り4枚がデッキに入る() {
        var marked = cards.getAllCards().stream().filter(implementation::isUnimplemented).toList();
        assertThat(marked).filteredOn(c -> c.type() == CardType.EVOLUTION).hasSize(11);
        assertThat(marked).filteredOn(c -> c.type() != CardType.EVOLUTION).hasSize(4);
    }

    /**
     * ★Batch 48 で印が消えた8枚(風文明の非進化)。
     *
     * 枚数(上の2件)だけを直すと、<b>数さえ合っていれば通ってしまう</b> ——
     * 別の文明の実装が偶然8枚進んでも、風の8枚が1枚も動いていなくても同じ数になる。
     * <b>実物を名指しして測る</b>のはそのためである(裁定181: 比べる相手を間違えた検証は何も見ていない)。
     */
    @Test
    void 風文明のVer11カード8枚には印が付かない() {
        String[] wind48 = {
            "QTE-M-WIND-29", // 妖ノ長・ストク … CardEffectRegistry.fireOwnMinionDestroyed(宣言あり)
            "QTE-M-WIND-33", // 透キ通ル・アヤカシ … StatCalculator(コスト) + 表(ON_ENTER)
            "QTE-M-WIND-34", // ハク霊 … 表(ON_TURN_START / ON_DESTROYED) + RuleGuards(攻撃不可)
            "QTE-M-WIND-35", // コク霊 … 同上
            "QTE-M-WIND-36", // 喚ビ集ウ・アヤカシ … 表(ON_SUMMON)
            "QTE-M-WIND-37", // 魂喰ラウ・オニ … 表(ON_SUMMON)
            "QTE-M-WIND-38", // 暴レ狂ウ・オニ … 表(ON_SUMMON)
            "QTE-M-WIND-39", // 天翔ケル霊鬼・シュテン … 表(特殊召喚)
        };
        for (String cardId : wind48) {
            CardMaster card = cards.findById(cardId);
            assertThat(implementation.isUnimplemented(card))
                    .as(card.name() + " は Batch 48 で実装したので印を付けてはいけない")
                    .isFalse();
        }
    }

    /**
     * ★Batch 49 で印が消えた6枚(水文明の非進化)。
     *
     * 48 と同じ理由で、枚数だけでなく<b>実物を名指しして測る</b>(裁定181)。
     */
    @Test
    void 水文明のVer11カード6枚には印が付かない() {
        String[] water49 = {
            "QTE-M-WATER-29", // ロロイヨ伯爵 … CardEffectRegistry.fireAnyMinionEntered(宣言あり)
            "QTE-M-WATER-35", // 海獣リューグー … 表(ON_SUMMON)
            "QTE-M-WATER-36", // 潮獣ビシャカワ … 表(spellEffects)
            "QTE-M-WATER-37", // 潮獣コアンチ … 表(spellEffects)
            "QTE-M-WATER-38", // ギガマウス・バイト … 表(spellEffects + targetSpecs) + StatCalculator(コスト)
            "QTE-M-WATER-39", // アルキンティス … StatCalculator(ウェポンの攻撃力・宣言あり)
        };
        for (String cardId : water49) {
            CardMaster card = cards.findById(cardId);
            assertThat(implementation.isUnimplemented(card))
                    .as(card.name() + " は Batch 49 で実装したので印を付けてはいけない")
                    .isFalse();
        }
    }

    /**
     * ★Batch 50 で印が消えた12枚(闇6 + 光6)。
     *
     * 48・49 と同じ理由で、枚数だけでなく<b>実物を名指しして測る</b>(裁定181)。
     * ★このバッチは<b>2文明を1バッチにまとめた</b>ので、名指しも2つに分けている ——
     * 片方の文明が丸ごと抜け落ちても数だけは合ってしまうためである。
     */
    @Test
    void 闇文明のVer11カード6枚には印が付かない() {
        String[] dark50 = {
            "QTE-M-DARK-29", // 演舞の墓守 … CardEffectRegistry.fireMinionEnteredFromGrave(宣言あり)
            "QTE-M-DARK-33", // デビルズマイク … 表(ON_ATTACK)
            "QTE-M-DARK-34", // サモンズライト … 表(ON_SUMMON / ON_DESTROYED + targetSpecs)
            "QTE-M-DARK-35", // カムバックキーパー … fireCardPutIntoTrashFromElsewhere(宣言あり)
            "QTE-M-DARK-36", // ダークネオンステージ … 表(特殊召喚)
            "QTE-M-DARK-39", // 1stL「NEMれぬ夜のドリーミー」 … 表(ON_SUMMON) + StatCalculator(常在)
        };
        for (String cardId : dark50) {
            CardMaster card = cards.findById(cardId);
            assertThat(implementation.isUnimplemented(card))
                    .as(card.name() + " は Batch 50 で実装したので印を付けてはいけない")
                    .isFalse();
        }
    }

    @Test
    void 光文明のVer11カード6枚には印が付かない() {
        String[] light50 = {
            "QTE-M-LIGHT-29", // 英皇アントマルエル … CardEffectRegistry.fireAnyMinionEntered(宣言あり)
            "QTE-M-LIGHT-34", // 光霊・テングスン … StatCalculator(スペルのコスト+1・宣言あり)
            "QTE-M-LIGHT-35", // 光霊・ネフラ … 表(ON_SUMMON)
            "QTE-M-LIGHT-36", // 光霊・モアニール … RuleGuards(登場とダメージの置換・宣言あり)
            "QTE-M-LIGHT-37", // 英術・グラーニス … 表(spellEffects)
            "QTE-M-LIGHT-38", // 英術・バンユー … 表(spellEffects) + RuleGuards(攻撃制限)
        };
        for (String cardId : light50) {
            CardMaster card = cards.findById(cardId);
            assertThat(implementation.isUnimplemented(card))
                    .as(card.name() + " は Batch 50 で実装したので印を付けてはいけない")
                    .isFalse();
        }
    }

    /**
     * ★Batch 51 で印が消えた15枚(火7 + 土8)。
     *
     * 48〜50 と同じ理由で、枚数だけでなく<b>実物を名指しして測る</b>(裁定181)。
     * ★火の《砲台鉄機虎》《ラスト・アタック》は<b>進化を参照する分岐を持つ</b>が、
     *   進化エンジンそのものは要らない(種別を見るだけ)ので 51 で実装済みである
     *   (マスター裁定215)。参照ではなく進化スタックを要求する《機神兵長茶爺》だけが P3 に残る。
     */
    @Test
    void 火文明のVer11カード7枚には印が付かない() {
        String[] fire51 = {
            "QTE-M-FIRE-33", // 支援盾機狸 … 表(ON_DESTROYED) + RuleGuards(攻撃不可)
            "QTE-M-FIRE-34", // 乱戦鉄機狼 … 表(ON_SUMMON)
            "QTE-M-FIRE-35", // 砲台鉄機虎 … 表(特殊召喚。進化を参照)
            "QTE-M-FIRE-36", // ラスト・アタック … 表(spellEffects + targetSpecs。進化を参照)
            "QTE-M-FIRE-37", // リペア・チューナー … 表(spellEffects + targetSpecs)
            "QTE-M-FIRE-38", // アイアン・リターン … 表(spellEffects)
            "QTE-M-FIRE-39", // ドレイン・ブラスト … 表(spellEffects + targetSpecs)
        };
        for (String cardId : fire51) {
            CardMaster card = cards.findById(cardId);
            assertThat(implementation.isUnimplemented(card))
                    .as(card.name() + " は Batch 51 で実装したので印を付けてはいけない")
                    .isFalse();
        }
    }

    @Test
    void 土文明のVer11カード8枚には印が付かない() {
        String[] earth51 = {
            "QTE-M-EARTH-7",  // 百獣の王 ベヒーモス … 表(ON_SUMMON。Ver1.1 で効果が付いた)
            "QTE-M-EARTH-29", // 地上覇総長・翔山 … 表(leaderAbilities)
            "QTE-M-EARTH-33", // 分那愚利 … 表(ON_SUMMON + targetSpecs)
            "QTE-M-EARTH-34", // 勝鼓美 … 表(ON_DESTROYED)
            "QTE-M-EARTH-35", // 素手喧嘩 … 表(ON_ATTACK)
            "QTE-M-EARTH-37", // 仏恥義理 … 表(spellEffects)
            "QTE-M-EARTH-38", // 喧嘩上等 … 表(spellEffects + targetSpecs)
            "QTE-M-EARTH-39", // 俺等地上覇夜露死苦 … 表(spellEffects)
        };
        for (String cardId : earth51) {
            CardMaster card = cards.findById(cardId);
            assertThat(implementation.isUnimplemented(card))
                    .as(card.name() + " は Batch 51 で実装したので印を付けてはいけない")
                    .isFalse();
        }
    }

    /**
     * ★Batch 52 で印が消えた8枚。
     *
     * 48〜51 と同じ理由で、枚数だけでなく<b>実物を名指しして測る</b>(裁定181)。
     * ★このバッチは<b>実装の置き場所が4通りに散っている</b> ——
     * 表(特殊召喚・トリガー・リーダー能力)、{@link StatCalculator} の宣言、
     * そして<b>進化の素材条件だけで完結する2枚の宣言</b>である。
     * 置き場所が違っても「どこかで名乗っていれば実装済み」であることを、ここで押さえる(裁定180)。
     */
    @Test
    void Batch52で実装した8枚には印が付かない() {
        String[] batch52 = {
            "QTE-M-WATER-30", // 海淵獣シラーカ … 効果の文が素材条件だけ(CardEffectRegistry の宣言)
            "QTE-M-FIRE-30",  // 不敗鉄人闘太 … 【常在】の値が EvolutionSpec にある(同上)
            "QTE-M-FIRE-31",  // 追撃鉄人連太 … StatCalculator(2回攻撃)
            "QTE-M-FIRE-32",  // 飛翔鉄人走太 … 表(特殊召喚)
            "QTE-M-DARK-32",  // サービスブレイク・メリィナ … StatCalculator(コスト・味方強化)
            "QTE-M-EARTH-31", // 裏雷怒乗込 … 表(ON_ATTACK)
            "QTE-M-EARTH-32", // 武羅須斗最終 … 表(特殊召喚)
            "QTE-M-FIRE-29",  // 機神兵長茶爺 … 表(リーダー起動能力)。51 が P3 へ送ったカード
        };
        for (String cardId : batch52) {
            CardMaster card = cards.findById(cardId);
            assertThat(implementation.isUnimplemented(card))
                    .as(card.name() + " は Batch 52 で実装したので印を付けてはいけない")
                    .isFalse();
        }
    }

    /**
     * ★Batch 52 が「【賢魂】待ち」として P4 へ送った7枚には、今も印が付いていなければならない。
     *
     * <b>これは任意の標本ではなくスコープの決定の記録である</b>(裁定219)。
     * ★とくに進化4枚は、<b>進化部分だけなら 52 で書けた</b> ——
     * それでも丸ごと送ったのは、進化だけを実装すると印が消えて
     * <b>【賢魂】も実装済みに見える</b>からである(裁定165: 部分実装は印で表せない)。
     * マスター裁定 E2 が案(b) を選んだ、その判断がここで固定される。
     */
    @Test
    void Batch52が賢魂待ちとしてP4へ送った7枚には今も印が付く() {
        String[] deferredToP4 = {
            "QTE-M-LIGHT-32", // 英霊・タイガラム … 進化 +【賢魂：3】
            "QTE-M-WIND-30",  // 黒ノ霊導者 … 進化 +【賢魂：1】
            "QTE-M-WIND-31",  // 白ノ霊知者 … 進化 +【賢魂：2】
            "QTE-M-EARTH-30", // 愚乱怒土地 … 進化 +【賢魂：3】
            "QTE-M-DARK-37",  // グレイヴガールズファン …【賢魂】
            "QTE-M-DARK-38",  // スタンディングテント …【賢魂】
            "QTE-M-EARTH-36", // 勝阿外 …【賢魂：2】(51 が送ったカード)
        };
        for (String cardId : deferredToP4) {
            CardMaster card = cards.findById(cardId);
            assertThat(implementation.isUnimplemented(card))
                    .as(card.name() + " は【賢魂】待ちなので印が付いたままでなければならない")
                    .isTrue();
        }
    }

    /**
     * ★Batch 52 が Batch 53 へ送った8枚にも、今も印が付いていなければならない。
     *
     * P3 を2つに割った境目そのものであり、53 がこの試験を空にするのが目的である(裁定219)。
     * ★<b>この8枚も「出す」ことはできる</b> —— 素材条件は18枚すべて登録してあるので、
     * デッキに入れて場に出せる。印が言っているのは「効果が起きない」ことだけである。
     */
    @Test
    void Batch52がBatch53へ送った8枚には今も印が付く() {
        String[] deferredTo53 = {
            "QTE-M-WATER-31", // 海淵獣ラカブ
            "QTE-M-WATER-32", // 海淵獣ゾクシム
            "QTE-M-DARK-30",  // リボーンライヴ・ノア
            "QTE-M-DARK-31",  // サモナーポップ・エンラ
            "QTE-M-LIGHT-30", // 英霊・ニュウキロ
            "QTE-M-LIGHT-31", // 英霊・コレキ
            "QTE-M-WIND-32",  // 灰ノ霊呼者
            "QTE-M-LIGHT-39", // 英術・スケアロック(スペル。51 が P3 へ送ったカード)
        };
        for (String cardId : deferredTo53) {
            CardMaster card = cards.findById(cardId);
            assertThat(implementation.isUnimplemented(card))
                    .as(card.name() + " は Batch 53 の範囲なので印が付いたままでなければならない")
                    .isTrue();
        }
    }

    /**
     * ★Batch 50 が「宣言あり」の経路に足した5枚。
     *
     * このバッチは<b>表に1行も載らないカードを5枚</b>増やした ——
     * リーダーの常在が3枚(演舞の墓守・カムバックキーパー・英皇アントマルエル)、
     * ルール側の判定点が2枚(光霊・テングスン・光霊・モアニール)である。
     * {@link EffectImplementation} が登録の表しか見ないようになった瞬間、
     * この5枚に印が付く —— <b>実際には正しく動いているのに</b>。
     */
    @Test
    void Batch50が足した5枚は表ではなく宣言で実装済みと判定される() {
        String[] declaredOnly = {
            "QTE-M-DARK-29",  // 演舞の墓守
            "QTE-M-DARK-35",  // カムバックキーパー
            "QTE-M-LIGHT-29", // 英皇アントマルエル
            "QTE-M-LIGHT-34", // 光霊・テングスン
            "QTE-M-LIGHT-36", // 光霊・モアニール
        };
        for (String cardId : declaredOnly) {
            assertThat(effects.isRegistered(cardId))
                    .as(cards.findById(cardId).name() + " は CardEffectRegistry の表に載っていない")
                    .isFalse();
            assertThat(EffectImplementation.ruleSideCards())
                    .as("代わりにルール側の宣言に載っている")
                    .contains(cardId);
        }
    }

    /**
     * ★ロロイヨ伯爵とアルキンティスは表に載っていない(ルール側の宣言だけで実装済みと判定される)。
     *
     * ストクと同じ形をこのバッチも2枚増やした。将来どちらかを表へ移したときに
     * 黙って通らないよう、前提として固定しておく。
     */
    @Test
    void ロロイヨとアルキンティスは表ではなく宣言で実装済みと判定される() {
        for (String cardId : new String[] { "QTE-M-WATER-29", "QTE-M-WATER-39" }) {
            assertThat(effects.isRegistered(cardId))
                    .as(cards.findById(cardId).name() + " は CardEffectRegistry の表に載っていない")
                    .isFalse();
            assertThat(EffectImplementation.ruleSideCards())
                    .as("代わりにルール側の宣言に載っている")
                    .contains(cardId);
        }
    }

    /**
     * ★妖ノ長・ストクは表に載っていない(ルール側の宣言だけで実装済みと判定される)。
     *
     * 上の試験だけだと、将来ストクを表へ移したときに何も言わずに通ってしまう。
     * このバッチが「宣言あり」の経路を1枚増やしたこと自体を、前提として固定しておく。
     */
    @Test
    void ストクは表ではなく宣言で実装済みと判定される() {
        assertThat(effects.isRegistered("QTE-M-WIND-29"))
                .as("妖ノ長・ストクは CardEffectRegistry の表に載っていない")
                .isFalse();
        assertThat(EffectImplementation.ruleSideCards())
                .as("代わりにルール側の宣言に載っている")
                .contains("QTE-M-WIND-29");
    }

    // ------------------------------------------------------------------
    // ★動くカードに印を付けない(裁定164 の本丸)
    // ------------------------------------------------------------------

    /**
     * ★<b>この試験が 47 でいちばん効く番人である。</b>
     *
     * ここに並ぶ4枚は、どれも {@code CardEffectRegistry} の表に載っていない。
     * 実装はルール側の判定点に直接書かれている。
     * {@link EffectImplementation} が登録の表しか見ないようになった瞬間、
     * この4枚に「効果未実装」の印が付く —— <b>実際には正しく動いているのに</b>。
     */
    @Test
    void ルール側に実装されたカードには印が付かない() {
        String[] ruleSideOnly = {
            "QTE-M-LIGHT-23", // 平和の結界 … RuleGuards(Attack3以上は攻撃できない)
            "QTE-M-WATER-5",  // 知識の守護者 … StatCalculator(攻撃力=手札枚数)
            "QTE-M-LIGHT-14", // 聖剣 エクスカリバー … GameService(リーダーの攻撃時)
            "QTE-M-EARTH-13", // 大地の守護盾 … GameActions(リーダーへの攻撃の肩代わり)
        };
        for (String cardId : ruleSideOnly) {
            CardMaster card = cards.findById(cardId);
            assertThat(effects.isRegistered(cardId))
                    .as(card.name() + " は表に載っていない(この前提が崩れたら別のカードで測ること)")
                    .isFalse();
            assertThat(implementation.isUnimplemented(card))
                    .as(card.name() + " はルール側に実装があるので印を付けてはいけない")
                    .isFalse();
        }
    }

    /**
     * キーワードしか書かれていないカードには印を付けない。
     *
     * ★この3枚はコードにIDが1つも現れないが、キーワードの仕組みだけで正しく動く。
     * 「IDがコードに無い = 未実装」と数えると、この3枚が巻き添えになる。
     */
    @Test
    void キーワードだけのカードには印が付かない() {
        String[] keywordOnly = {
            "QTE-M-LIGHT-5", // ホーリー・ガーディアン … 【守護】【潜伏】(注釈の括弧つき)
            "QTE-M-WATER-6", // ディープシー・シャーク … 【突進】【威圧】(注釈の括弧つき)
            "QTE-M-FIRE-2",  // フレア・ポーン … 本文が「効果なし」
        };
        for (String cardId : keywordOnly) {
            CardMaster card = cards.findById(cardId);
            assertThat(implementation.isUnimplemented(card))
                    .as(card.name() + " は効果の文が無いので印を付けてはいけない")
                    .isFalse();
        }
    }

    // ------------------------------------------------------------------
    // ★動かないカードに印を付ける
    // ------------------------------------------------------------------

    /**
     * ★<b>プリセットに載っていることは実装済みの根拠にならない</b>(裁定175 の続き)。
     *
     * 46b までの数え方は「Java のどこかにIDが書いてあれば実装済み」だったので、
     * {@code DeckFactory} に書かれた1行のせいで印が消えるカードがあった。
     * 印の判定は<b>カードの本文と、登録・宣言だけ</b>を見なければならない。
     *
     * <p>★<b>題材の名指しをやめた(★Batch 51。裁定219)。</b>
     * 47 はここで《百獣の王 ベヒーモス》を「プリセットに入っているのに未実装の実例」として
     * 名指ししていた。51 がそれを実装したのでこの試験は落ちた ——
     * 50 でスペル側の試験に起きたこと(裁定209)が、そのままミニオン側にも起きたのである。
     * <b>「まだ未実装であること」を題材にした試験は、種別を問わず必ず陳腐化する。</b>
     *
     * <p>代わりに測るのは、プリセットに現れるカードが<b>特別扱いされていないこと</b>である。
     * 登録にも宣言にも無いカードが1枚でもプリセットに紛れていれば、それには印が付く。
     * ★1枚も紛れていない状態(=プリセットが全部実装済み)も正しい答えなので、
     * ここでは非空を要求しない —— 代わりに「プリセットを実際に読んだ」ことのほうを確かめる
     * (裁定186: 仕事をしていないことを自分で区別できなければならない)。
     */
    @Test
    void プリセットに載っていることは実装済みの根拠にならない() {
        List<CardMaster> leaders = cards.getAllCards().stream()
                .filter(c -> c.type() == CardType.LEADER).toList();
        int inspected = 0;
        for (CardMaster leader : leaders) {
            for (String cardId : deckFactory.createMainDeck(leader)) {
                inspected++;
                CardMaster card = cards.findById(cardId);
                if (!CardTextKeywords.hasEffectSentence(card.text())
                        || effects.isRegistered(cardId)
                        || EffectImplementation.ruleSideCards().contains(cardId)) {
                    continue;
                }
                assertThat(implementation.isUnimplemented(card))
                        .as(card.name() + " は登録にも宣言にも無いので、"
                                + "プリセットに載っていても印が付かなければならない")
                        .isTrue();
            }
        }
        assertThat(inspected).as("プリセットデッキを実際に読んだ(空振りでないこと)").isPositive();
    }

    /**
     * ★<b>題材の名指しをやめた(Batch 50)。</b>
     *
     * <p>47 はここで《潮獣ビシャカワ》を測り、49 でそれを実装したので《英術・グラーニス》に
     * 差し替え、50 でそれも実装した —— <b>3バッチ続けて同じ理由で書き換えている。</b>
     * 「まだ未実装であること」を題材にした試験は、実装が進むフェーズでは
     * <b>必ず陳腐化する</b>。名指しをやめれば、この保守そのものが要らなくなる。
     *
     * <p>代わりに測るのは<b>不変条件</b>である ——
     * <b>解決処理を持たないスペルは、印が付くか、ルール側で宣言されているかの
     * どちらかでなければならない。</b> どちらでもないスペルは
     * 「デッキに入れられて、印も出ず、使おうとすると
     * 『このスペルの効果は未実装です』で弾かれる」という最悪の状態になる。
     *
     * <p>★<b>これは同語反復ではない。</b> {@code isRegistered} は9つの表を見ており、
     * {@code spellEffects} が空でも {@code targetSpecs} や {@code playConditions} に
     * 載っていれば「登録あり」と答える。つまり<b>対象指定だけ書いて解決処理を書き忘れた</b>
     * スペルは、印が付かないままここをすり抜けようとする —— それを捕まえる試験である。
     *
     * <p>★<b>空振りを第3の答えとして持つ</b>(裁定186)。解決処理を持たないスペルが
     * 0枚になるとループが1度も回らず、「何も見ていないのに緑」になる。
     * だから「1枚以上あること」を先に確かめる。
     *
     * <p>★現在この条件に当てはまるのは、未実装の8枚に加えて
     * <b>《ピュア・エレメント》</b>である —— あれは {@code spellEffects} を持たず、
     * {@code GameService.playPureElement} が直接処理する唯一のスペルであり、
     * 宣言のほうで実装済みと答える。<b>この試験を書いて初めて表に出た事実である。</b>
     */
    @Test
    void 解決処理を持たないスペルは印が付くか宣言されている() {
        var noHandler = cards.getAllCards().stream()
                .filter(c -> c.type() == CardType.SPELL)
                .filter(c -> !effects.isSpellImplemented(c.id()))
                .toList();
        assertThat(noHandler)
                .as("★空振り検出: 1枚も無ければ、この試験は何も見ていない")
                .isNotEmpty();
        for (CardMaster spell : noHandler) {
            boolean markedOrDeclared = implementation.isUnimplemented(spell)
                    || EffectImplementation.ruleSideCards().contains(spell.id());
            assertThat(markedOrDeclared)
                    .as(spell.name() + " は解決処理が無い。印を付けるか、ルール側で宣言すること")
                    .isTrue();
        }
    }

    // ------------------------------------------------------------------
    // 宣言そのものの健全性
    // ------------------------------------------------------------------

    /**
     * ルール側が名乗ったカードIDは、すべて実在するカードである。
     *
     * ★宣言は人が書く。書き間違えても、そのIDのカードが存在しないだけで
     * <b>誰も何も言わない</b>(集合に入っているだけなので)。ここで実在を確かめる。
     * 宣言の過不足そのものは {@code tools/report_effects.py} が両方向から見ている。
     */
    @Test
    void ルール側が名乗ったカードはすべて実在する() {
        assertThat(EffectImplementation.ruleSideCards()).isNotEmpty();
        for (String cardId : EffectImplementation.ruleSideCards()) {
            assertThatCode(() -> cards.findById(cardId))
                    .as("宣言されたカードID " + cardId)
                    .doesNotThrowAnyException();
        }
    }
}
