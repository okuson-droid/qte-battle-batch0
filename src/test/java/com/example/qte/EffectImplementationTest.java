package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.effect.CardEffectRegistry;
import com.example.qte.effect.EffectImplementation;
import com.example.qte.game.DeckFactory;
import com.example.qte.master.CardMaster;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.CardType;
import com.example.qte.master.Civilization;

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
     * 47 の時点では 64枚 = 進化18 + 非進化46 である。
     */
    @Test
    void 効果未実装のカードは64枚である() {
        long unimplemented = cards.getAllCards().stream().filter(implementation::isUnimplemented).count();
        assertThat(unimplemented).isEqualTo(64);
    }

    @Test
    void 印が付くカードのうち進化ミニオンは18枚で残り46枚がデッキに入る() {
        var marked = cards.getAllCards().stream().filter(implementation::isUnimplemented).toList();
        assertThat(marked).filteredOn(c -> c.type() == CardType.EVOLUTION).hasSize(18);
        assertThat(marked).filteredOn(c -> c.type() != CardType.EVOLUTION).hasSize(46);
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
     * ★<b>移行が掘り出した不整合</b>(裁定175 の続き)。
     *
     * 《百獣の王 ベヒーモス》は Ver0.4 では効果を持たないバニラだったが、
     * Ver1.1 で【召喚時】が付いた。実装は追いついておらず、しかも
     * <b>土のプリセットデッキに1枚入っている</b>。46b までの数え方は
     * 「Java のどこかにIDが書いてあれば実装済み」だったので、
     * DeckFactory に書かれたこの1行のせいで<b>実装済みに化けていた</b>。
     *
     * <p>この試験は2つのことを同時に言っている ——
     * 印が正しく付くこと、そして<b>印が絵に描いた餅ではない</b>(実際に配られるデッキに現れる)こと。
     */
    @Test
    void プリセットに入っている未実装カードに印が付く() {
        CardMaster behemoth = cards.findById("QTE-M-EARTH-7");
        assertThat(implementation.isUnimplemented(behemoth))
                .as(behemoth.name() + " は効果が未実装である")
                .isTrue();
        CardMaster earthLeader = cards.findByCivilization(Civilization.EARTH).stream()
                .filter(c -> c.type() == CardType.LEADER).findFirst().orElseThrow();
        assertThat(deckFactory.createMainDeck(earthLeader))
                .as("土のプリセットデッキに入っている")
                .contains("QTE-M-EARTH-7");
    }

    @Test
    void 効果が未実装のスペルに印が付く() {
        // 46b まではデッキ構築の入口で弾かれていた13枚のうちの1枚(裁定D2 で門を開けた)
        CardMaster spell = cards.findById("QTE-M-WATER-36"); // 潮獣ビシャカワ
        assertThat(spell.type()).isEqualTo(CardType.SPELL);
        assertThat(effects.isSpellImplemented(spell.id())).isFalse();
        assertThat(implementation.isUnimplemented(spell)).isTrue();
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
