package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.deck.DeckDefinition;
import com.example.qte.deck.DeckValidator;
import com.example.qte.game.DeckFactory;
import com.example.qte.master.CardMaster;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.CardType;
import com.example.qte.master.Civilization;

/**
 * デッキ構築の検証({@link DeckValidator})の試験(Batch 46b で新設)。
 *
 * <h2>なぜ 46b でこれを書くのか</h2>
 *
 * 46b は台帳ID169種を機械変換した。<b>変換が1件でもずれていれば、
 * そのカードは「存在しないカードID」になる</b>。コンパイルは通ってしまうので、
 * 気づくのは対戦を始めたときである。
 *
 * <p>プリセットデッキ6本(各40枚 + 禁忌8枚)を検証層に通すと、
 * {@link DeckFactory} に書かれた <b>151行ぶんのID</b>が実在し、文明が揃い、
 * スペルの効果が登録済みであることまでまとめて確かめられる。
 * 変換の答え合わせとしては、これがいちばん実物に近い。
 *
 * <p>合わせて、46b で入れた2つの決めごとも測る。
 * <ul>
 * <li><b>進化ミニオンはデッキに入れられない</b>(裁定166。P3 まで場に出す手段が無い)</li>
 * <li><b>同名5枚は必ず弾かれる</b>(★UNLIMITED_COPIES の例外表を撤廃したので、抜け道は無い)</li>
 * </ul>
 */
@SpringBootTest
class DeckValidatorTest {

    @Autowired
    DeckValidator validator;

    @Autowired
    DeckFactory deckFactory;

    @Autowired
    CardMasterRepository cards;

    // ------------------------------------------------------------------
    // 機械変換の答え合わせ
    // ------------------------------------------------------------------

    @Test
    void 全6文明のプリセットデッキが検証を通る() {
        // ★台帳ID169種の機械変換(46b)の実地確認。1件でもずれていれば
        // 「存在しないカードがあります」で落ちる。
        for (Civilization civ : DeckValidator.implementedCivilizations()) {
            CardMaster leader = firstLeaderOf(civ);
            assertThatCode(() -> validator.validate(presetDeck(leader)))
                    .as(civ.getDisplayName() + "文明のプリセットデッキ")
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void 選択できるリーダーはすべて実在して検証を通る() {
        // ロビーのリーダー選択に出る全リーダー(★46b で12枚から18枚に増えた)。
        List<CardMaster> leaders = DeckValidator.implementedCivilizations().stream()
                .flatMap(civ -> cards.findByCivilization(civ).stream())
                .filter(c -> c.type() == CardType.LEADER)
                .toList();
        assertThat(leaders).hasSize(18);
        for (CardMaster leader : leaders) {
            assertThatCode(() -> validator.validate(presetDeck(leader)))
                    .as(leader.name() + " のプリセットデッキ")
                    .doesNotThrowAnyException();
        }
    }

    // ------------------------------------------------------------------
    // 進化ミニオン(★Batch 52 で解禁。裁定166 → マスター裁定 E1)
    // ------------------------------------------------------------------

    /**
     * ★46b〜51 のあいだ、進化ミニオンはデッキ構築で弾かれていた(裁定166)。
     * 理由は「場に出す手段そのものがエンジンに無い」ことであり、入れると手札で
     * 完全な死に札になるからだった。Batch 52 が進化エンジンを作り、
     * 18枚すべての素材条件を登録したので、その理由は消えた。
     */
    @Test
    void 進化ミニオンはメインデッキに入れられる() {
        CardMaster leader = firstLeaderOf(Civilization.WATER);
        DeckDefinition deck = replaceOneMainCard(presetDeck(leader), "QTE-M-WATER-30"); // 海淵獣シラーカ
        assertThatCode(() -> validator.validate(deck)).doesNotThrowAnyException();
    }

    /**
     * ★禁忌デッキにも入れられる(マスター裁定 E1)。使い方は通常と同じで、
     * コストの支払い方だけが禁忌の作法になる。
     * 禁忌は「リーダーと異なる文明」なので、水リーダーには火の進化を入れて試す ——
     * 片方の経路だけ確かめて安心する、という取りこぼしを防ぐ。
     */
    @Test
    void 進化ミニオンは禁忌デッキにも入れられる() {
        CardMaster leader = firstLeaderOf(Civilization.WATER);
        DeckDefinition base = presetDeck(leader);
        List<String> taboo = new ArrayList<>(base.taboo());
        taboo.set(0, "QTE-M-FIRE-30"); // 不敗鉄人闘太
        DeckDefinition deck = new DeckDefinition(
                base.name(), base.leaderCardId(), base.main(), taboo);
        assertThatCode(() -> validator.validate(deck)).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------
    // 同名上限(UNLIMITED_COPIES 撤廃の確認)
    // ------------------------------------------------------------------

    @Test
    void 同名5枚は例外なく弾かれる() {
        // ★46b で「4枚以上入れられる」の例外表を撤廃した。該当カードは 0 枚なので、
        // いまや抜け道は無い。表を復活させるとこのテストが落ちる。
        CardMaster leader = firstLeaderOf(Civilization.WATER);
        DeckDefinition base = presetDeck(leader);
        List<DeckDefinition.Entry> main = new ArrayList<>(base.main());
        main.set(0, new DeckDefinition.Entry(main.get(0).cardId(), 5));
        DeckDefinition deck = new DeckDefinition(
                base.name(), base.leaderCardId(), main, base.taboo());
        assertThatThrownBy(() -> validator.validate(deck))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("同名カードは4枚まで");
    }

    /**
     * ★★これが例外表撤廃の本当の番人である。
     *
     * 「同名5枚は弾かれる」だけでは足りない —— <b>例外表を復活させても落ちない</b>。
     * 例外に載っていたのはゾンストライカー1枚なので、<b>そのカードで測らなければ
     * 測ったことにならない</b>(裁定135 と同じ形の穴。壊し方を試して見つけた)。
     */
    @Test
    void ゾンストライカーも5枚は入れられない() {
        CardMaster leader = firstLeaderOf(Civilization.DARK);
        DeckDefinition base = presetDeck(leader);
        List<DeckDefinition.Entry> main = new ArrayList<>(base.main());
        int at = -1;
        for (int i = 0; i < main.size(); i++) {
            if ("QTE-M-DARK-16".equals(main.get(i).cardId())) {
                at = i;
            }
        }
        assertThat(at).as("闇のプリセットにゾンストライカーがいる").isNotNegative();
        main.set(at, new DeckDefinition.Entry("QTE-M-DARK-16", 5));
        DeckDefinition deck = new DeckDefinition(
                base.name(), base.leaderCardId(), main, base.taboo());
        assertThatThrownBy(() -> validator.validate(deck))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("同名カードは4枚まで")
                .hasMessageContaining("ゾンストライカー");
    }

    @Test
    void 同名上限を上書きするテキストを持つカードは1枚も無い() {
        // ★例外表を撤廃してよい根拠そのものを、カードデータから測る(裁定110)。
        // Ver1.1 でこの一文を持つカードが復活したら、ここが落ちて例外の設計に戻る合図になる。
        assertThat(cards.getAllCards())
                .filteredOn(c -> c.text() != null && c.text().contains("枚以上入れられる"))
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // ★Batch 47: 効果が未実装のスペルの門を開けた(裁定D2)
    // ------------------------------------------------------------------

    /**
     * 46b までは、効果の登録が無いスペルは<b>ここで拒否されていた</b>。
     * 47 で拒否をやめ、代わりに盤面へ印を出すことにした(裁定D2)。
     *
     * <p>★印が出ることは {@link EffectImplementationTest} が測る。
     * こちらが測るのは「入口が開いたこと」だけである。
     */
    @Test
    void 効果が未実装のスペルもメインデッキに入れられる() {
        CardMaster leader = firstLeaderOf(Civilization.WATER);
        DeckDefinition deck = replaceOneMainCard(presetDeck(leader), "QTE-M-WATER-36"); // 潮獣ビシャカワ
        assertThatCode(() -> validator.validate(deck)).doesNotThrowAnyException();
    }

    @Test
    void 効果が未実装のスペルは禁忌デッキにも入れられる() {
        // ★片方の経路だけ開けて満足しないよう、禁忌側でも測る(進化の試験と同じ形)。
        CardMaster leader = firstLeaderOf(Civilization.WATER);
        DeckDefinition base = presetDeck(leader);
        List<String> taboo = new ArrayList<>(base.taboo());
        taboo.set(0, "QTE-M-FIRE-37"); // リペア・チューナー(火の未実装スペル)
        DeckDefinition deck = new DeckDefinition(
                base.name(), base.leaderCardId(), base.main(), taboo);
        assertThatCode(() -> validator.validate(deck)).doesNotThrowAnyException();
    }

    @Test
    void 文明なしのカードはデッキに入れられない() {
        // ★スペルの門を開けても、文明の検査は残っている。ピュア・エレメント(文明なし)は
        // デッキに入るカードではないので、ここで止まる。
        CardMaster leader = firstLeaderOf(Civilization.WATER);
        DeckDefinition deck = replaceOneMainCard(presetDeck(leader), "QTE-M-NONE-01");
        assertThatThrownBy(() -> validator.validate(deck))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void リーダーはメインデッキに入れられない() {
        CardMaster leader = firstLeaderOf(Civilization.WATER);
        DeckDefinition deck = replaceOneMainCard(presetDeck(leader), "QTE-M-WATER-15"); // 流転の智者
        assertThatThrownBy(() -> validator.validate(deck))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("リーダーはメインデッキに入れられません");
    }

    // ------------------------------------------------------------------
    // 組み立ての補助
    // ------------------------------------------------------------------

    private CardMaster firstLeaderOf(Civilization civilization) {
        return cards.findByCivilization(civilization).stream()
                .filter(c -> c.type() == CardType.LEADER)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        civilization + " のリーダーが見つからない"));
    }

    /** プリセット(DeckFactory)から、検証層に渡せる形のデッキファイルを組み立てる */
    private DeckDefinition presetDeck(CardMaster leader) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        deckFactory.createMainDeck(leader).forEach(id -> counts.merge(id, 1, Integer::sum));
        List<DeckDefinition.Entry> main = counts.entrySet().stream()
                .map(e -> new DeckDefinition.Entry(e.getKey(), e.getValue()))
                .toList();
        return new DeckDefinition("テスト", leader.id(), main, deckFactory.createTabooDeck(leader));
    }

    /** メインデッキの1枚だけを差し替える(合計40枚を保つ) */
    /**
     * メインデッキの1枚を {@code cardId} に差し替える。合計40枚は保つ。
     *
     * <p>★<b>Batch 60: 差し込むカードが既にデッキに居る場合に対応した。</b>
     * 60 でプリセットを Ver1.1 化した結果、その文明のカードは<b>ほぼ全種類がプリセットに入った</b> ——
     * 「デッキに無いカードを1枚足す」が成り立たなくなり、
     * 行が重複して {@code DeckValidator} に弾かれていた。
     * 既にある行なら枚数を1増やし、無ければ行を足す。
     * 減らす側も、差し込むカードと同じ行は避けて選ぶ。
     */
    private DeckDefinition replaceOneMainCard(DeckDefinition base, String cardId) {
        List<DeckDefinition.Entry> main = new ArrayList<>(base.main());
        int from = 0;
        while (main.get(from).cardId().equals(cardId)) {
            from++;
        }
        DeckDefinition.Entry donor = main.get(from);
        if (donor.count() == 1) {
            main.remove(from);
        } else {
            main.set(from, new DeckDefinition.Entry(donor.cardId(), donor.count() - 1));
        }
        int existing = -1;
        for (int i = 0; i < main.size(); i++) {
            if (main.get(i).cardId().equals(cardId)) {
                existing = i;
                break;
            }
        }
        if (existing >= 0) {
            main.set(existing, new DeckDefinition.Entry(cardId, main.get(existing).count() + 1));
        } else {
            main.add(new DeckDefinition.Entry(cardId, 1));
        }
        return new DeckDefinition(base.name(), base.leaderCardId(), main, base.taboo());
    }
}
