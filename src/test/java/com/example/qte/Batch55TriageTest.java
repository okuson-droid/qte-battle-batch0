package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.effect.StatCalculator;
import com.example.qte.effect.TargetChoice;
import com.example.qte.game.GameService;
import com.example.qte.game.MinionInstance;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.Keyword;
import com.example.qte.support.AutoGameFixture;

/**
 * 作り直し区分1・2(45枚)が「直さなくてよい」ことの証明(★Batch 55)。
 *
 * <h2>この試験が守っているもの</h2>
 *
 * {@code notes/rework-triage.md} の区分1(キーワードの増減だけ・35枚)と
 * 区分2(記法だけ・10枚)は「実装が要らないと見込める」側である。
 * ★<b>だが「見込み」は検証されていない。</b>着手前の機械照合で、区分2から
 * 実際に3件の食い違い(蒼海の賢者・傷痕の闘帝・冥府の禁皇の起動コスト)が見つかった
 * (rework-triage.md 2章)。「読んで納得する」のではなく、{@link AutoGameFixture} の上で
 * <b>実際に測る</b>(裁定187)。
 *
 * <h2>測っているもの</h2>
 *
 * <ol>
 * <li>区分1の35枚: 旧台帳(qte-cards.json)の {@code keywords} フィールドと、
 *     Ver1.1 テキストから {@link com.example.qte.master.CardTextKeywords#extract}
 *     が読み取るキーワード集合が、一致すること。</li>
 * <li>《ディープシー・シャーク》の【威圧】が実戦闘で効くこと(裁定要求どおり測る)。</li>
 * <li>《急流の狙撃手》《影潜む水刺客》の【貫通】が実戦闘で効くこと。
 *     比較のため、貫通を持たないミニオンでは同じ盤面で守護を無視できないことも並べて測る
 *     (裁定181: 比べる相手を間違えた検証は何も見ていない)。</li>
 * <li>《影潜む水刺客》の「自分の場の潜伏の数Attack+1」(StatCalculator に実装済み)が
 *     テキストの記法変更後も引き続き動くこと。</li>
 * <li>区分2のうちコスト以外(静空の風使い・風のマナ変換・秩序の執行官・戒律の聖堂騎士・
 *     封印されし禁忌魔人)は、旧本文と新本文で数値・条件が変わっていないことをテキストで
 *     確認済み(表記ゆれ: フェイズ→フェーズ・「1枚」の省略・句読点・改行のみ)。
 *     ★コストの明記(【起動：n】)が絡む5枚(蒼海の賢者・傷痕の闘帝・冥府の禁皇・
 *     聖光の守護聖・断罪の聖導者・疾風の導き手)は {@code tools/check_leader_abilities.py}
 *     が別に照合している。</li>
 * </ol>
 */
@SpringBootTest
class Batch55TriageTest {

    private static final String PLAIN_LEADER = "QTE-M-WATER-1"; // 蒼海の賢者(起動能力のみ)

    // 区分1: 旧台帳の keywords(表示名を Keyword enum に変換したもの)。
    // データの出どころは Ver0.4 台帳と manual-cards.json の突き合わせである(当時 tools/rework_triage.py が行った)。
    // ★台帳もツールも Batch 60 で削除した。ここに焼き付いた35枚は当時の断面の記録であり、
    //   もう作り直せない —— 数え直したくなったら notes/rework-triage.md を読むこと。
    private static final Object[][] CATEGORY1_KEYWORDS = {
        {"QTE-M-FIRE-13", Set.of(Keyword.KNOWLEDGE)},
        {"QTE-M-FIRE-6", Set.of(Keyword.HASTE)},
        {"QTE-M-FIRE-8", Set.of(Keyword.HASTE, Keyword.SPECIAL_SUMMON)},
        {"QTE-M-WATER-14", Set.of(Keyword.KNOWLEDGE)},
        {"QTE-M-WATER-16", Set.of(Keyword.KNOWLEDGE, Keyword.PIERCE)},
        // ★知識の守り手は新本文で【突進】が増えている(旧台帳keywords={守護,知識,還元})。
        // これも「キーワードの増減だけ」の一例であり、区分1の定義どおりである。
        {"QTE-M-WATER-17", Set.of(Keyword.GUARD, Keyword.KNOWLEDGE, Keyword.RESTORATION, Keyword.RUSH)},
        // ★波濤の突撃兵は新本文で【潜伏】が増えている(旧台帳keywords={突進})。
        {"QTE-M-WATER-18", Set.of(Keyword.RUSH, Keyword.STEALTH)},
        {"QTE-M-WATER-2", Set.of(Keyword.KNOWLEDGE)},
        {"QTE-M-WATER-20", Set.of(Keyword.RUSH, Keyword.STEALTH)},
        {"QTE-M-WATER-27", Set.of(Keyword.RESTORATION)},
        {"QTE-M-WATER-28", Set.of(Keyword.PIERCE)},
        {"QTE-M-WATER-3", Set.of(Keyword.GUARD)},
        {"QTE-M-WATER-4", Set.of(Keyword.GUARD)},
        {"QTE-M-WATER-5", Set.of(Keyword.GUARD)},
        {"QTE-M-WATER-6", Set.of(Keyword.INTIMIDATE, Keyword.RUSH)},
        {"QTE-M-WATER-7", Set.of(Keyword.GUARD, Keyword.RUSH)},
        {"QTE-M-WIND-16", Set.of(Keyword.GUARD, Keyword.RUSH)},
        {"QTE-M-WIND-2", Set.of(Keyword.KNOWLEDGE)},
        {"QTE-M-WIND-20", Set.of(Keyword.KNOWLEDGE, Keyword.RESTORATION)},
        {"QTE-M-WIND-3", Set.of(Keyword.HASTE)},
        {"QTE-M-WIND-5", Set.of(Keyword.RUSH)},
        // ★煌めきの盾は新本文で【知識】が増えている(旧台帳keywords={守護})。
        {"QTE-M-LIGHT-16", Set.of(Keyword.GUARD, Keyword.KNOWLEDGE)},
        {"QTE-M-LIGHT-17", Set.of(Keyword.KNOWLEDGE)},
        {"QTE-M-LIGHT-5", Set.of(Keyword.GUARD, Keyword.STEALTH)},
        {"QTE-M-DARK-7", Set.of(Keyword.KNOWLEDGE, Keyword.RUSH, Keyword.SPECIAL_SUMMON)},
        {"QTE-M-EARTH-10", Set.of(Keyword.RESTORATION)},
        {"QTE-M-EARTH-17", Set.of(Keyword.RUSH)},
        {"QTE-M-EARTH-19", Set.of(Keyword.RUSH)},
        {"QTE-M-EARTH-2", Set.of(Keyword.GUARD)},
        {"QTE-M-EARTH-22", Set.of(Keyword.HASTE)},
        {"QTE-M-EARTH-23", Set.of(Keyword.STEALTH)},
        {"QTE-M-EARTH-24", Set.of(Keyword.HASTE)},
        {"QTE-M-EARTH-26", Set.of(Keyword.RESTORATION)},
        {"QTE-M-EARTH-5", Set.of(Keyword.GUARD, Keyword.STEALTH)},
        {"QTE-M-EARTH-6", Set.of(Keyword.RUSH)},
    };

    private static final String DEEP_SEA_SHARK = "QTE-M-WATER-6";   // 【突進】【威圧】
    private static final String SNIPER = "QTE-M-WATER-16";          // 【知識】【貫通】
    private static final String SHADOW_ASSASSIN = "QTE-M-WATER-28"; // 【貫通】潜伏の数Attack+1
    private static final String GUARD_MINION = "QTE-M-WATER-3";     // 潮流の魔導士(【守護】。召喚時は5枚以上手札条件で不発)
    private static final String PLAIN_MINION = "QTE-M-FIRE-2";      // 貫通を持たない対照群
    private static final String STEALTH_MINION = "QTE-M-EARTH-23";  // 不動の絶対神 ガイア(【潜伏】)

    @Autowired
    GameService game;

    @Autowired
    StatCalculator stats;

    @Autowired
    CardMasterRepository cards;

    private AutoGameFixture newGame() {
        AutoGameFixture f = new AutoGameFixture(cards, PLAIN_LEADER, PLAIN_LEADER);
        f.fillDeck(f.me(), 30);
        f.fillDeck(f.you(), 30);
        return f;
    }

    // ==================================================================
    // 1. 区分1(35枚): テキストから読むキーワードは旧台帳と一致する
    // ==================================================================

    @Test
    void 区分1の35枚はテキストから読むキーワードが旧台帳と一致する() {
        for (Object[] row : CATEGORY1_KEYWORDS) {
            String cardId = (String) row[0];
            @SuppressWarnings("unchecked")
            Set<Keyword> expected = (Set<Keyword>) row[1];
            Set<Keyword> actual = cards.findById(cardId).keywords();
            assertThat(actual)
                    .as("%s: Ver1.1テキストから読むキーワードが旧台帳のkeywordsと一致するはず", cardId)
                    .isEqualTo(expected);
        }
    }

    // ==================================================================
    // 2. 【威圧】—— ディープシー・シャーク(QTE-M-WATER-6)
    // ==================================================================

    @Test
    void 威圧持ちのディープシーシャークは攻撃対象にできない() {
        AutoGameFixture f = newGame();
        MinionInstance attacker = f.putOnField(f.me(), PLAIN_MINION);
        MinionInstance shark = f.putOnField(f.you(), DEEP_SEA_SHARK);
        game.nextPhase(f.room(), "me");

        assertThatThrownBy(() -> game.attack(f.room(), "me", attacker.getInstanceId(), shark.getInstanceId()))
                .as("【威圧】持ちは攻撃対象にできない")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 威圧を持たない相手ミニオンは通常どおり攻撃対象にできる() {
        AutoGameFixture f = newGame();
        MinionInstance attacker = f.putOnField(f.me(), PLAIN_MINION);
        MinionInstance normal = f.putOnField(f.you(), "QTE-M-WIND-2");
        game.nextPhase(f.room(), "me");

        // ★対照群: 威圧を持たなければ例外が飛ばずに攻撃できる(裁定181の「そうでない側」)。
        game.attack(f.room(), "me", attacker.getInstanceId(), normal.getInstanceId());
    }

    // ==================================================================
    // 3. 【貫通】—— 急流の狙撃手(QTE-M-WATER-16)・影潜む水刺客(QTE-M-WATER-28)
    // ==================================================================

    @Test
    void 貫通持ちの急流の狙撃手は相手の守護を無視してリーダーを攻撃できる() {
        AutoGameFixture f = newGame();
        MinionInstance sniper = f.putOnField(f.me(), SNIPER);
        f.putOnField(f.you(), GUARD_MINION); // 【守護】持ちが場にいる
        game.nextPhase(f.room(), "me");

        int before = f.you().getLp();
        game.attack(f.room(), "me", sniper.getInstanceId(), null);
        assertThat(f.you().getLp())
                .as("【貫通】は【守護】を無視してリーダーへ直接攻撃できる")
                .isLessThan(before);
    }

    @Test
    void 貫通を持たないミニオンは相手の守護を無視できない() {
        AutoGameFixture f = newGame();
        MinionInstance attacker = f.putOnField(f.me(), PLAIN_MINION);
        f.putOnField(f.you(), GUARD_MINION); // 【守護】持ちが場にいる
        game.nextPhase(f.room(), "me");

        // ★対照群: 貫通が無ければ、守護を無視したリーダー攻撃は拒否されるはず。
        // これが通ってしまうと、上のテストが「常に勝つ」実装でも通ってしまう(裁定181)。
        assertThatThrownBy(() -> game.attack(f.room(), "me", attacker.getInstanceId(), null))
                .as("守護持ちがいるのに、それを無視してリーダーを攻撃するのは拒否されるはず")
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * ★《影潜む水刺客》はミニオンではなく<b>ウェポン</b>である(StatCalculator のコメント参照)。
     * 攻撃はミニオンの {@code attack} ではなく、リーダーの {@code leaderAttack} を通す。
     */
    @Test
    void 貫通持ちの影潜む水刺客は相手の守護を無視して攻撃できる() {
        AutoGameFixture f = newGame();
        f.me().setEquippedWeapon(cards.findById(SHADOW_ASSASSIN));
        f.putOnField(f.me(), STEALTH_MINION); // 攻撃力0のままだとダメージが測れないため、潜伏を1体添えて+1する
        f.putOnField(f.you(), GUARD_MINION);
        game.nextPhase(f.room(), "me");

        int before = f.you().getLp();
        game.leaderAttack(f.room(), "me", null);
        assertThat(f.you().getLp()).isLessThan(before);
    }

    // ==================================================================
    // 4. 影潜む水刺客の「潜伏の数Attack+1」(StatCalculator に既存実装)は
    //    テキストの記法変更後も引き続き動く
    // ==================================================================

    @Test
    void 影潜む水刺客の攻撃力は自分の場の潜伏の数に応じて上がる() {
        AutoGameFixture f = newGame();
        f.me().setEquippedWeapon(cards.findById(SHADOW_ASSASSIN));
        int baseAttack = stats.effectiveWeaponAttack(f.state(), f.me());

        f.putOnField(f.me(), STEALTH_MINION); // 【潜伏】持ちを1体追加
        int afterOneStealth = stats.effectiveWeaponAttack(f.state(), f.me());

        assertThat(afterOneStealth)
                .as("自分の場に【潜伏】ミニオンが1体増えたぶん、ウェポンの攻撃力が+1されているはず")
                .isEqualTo(baseAttack + 1);
    }

    // ==================================================================
    // 5. 区分3a(数値だけ)のうち、ロジックの分岐を伴う2枚を代表として測る
    //    (単純な定数差し替えは CardEffectRegistry のコードそのものが証拠になる)
    // ==================================================================

    private static final String AQUA_SEARCH = "QTE-M-WATER-25";
    private static final String UNDERWORLD_ROAD = "QTE-M-DARK-26";
    private static final String LIFE_CONSUMING_FLAME = "QTE-M-FIRE-11";

    private static TargetChoice minions(String... instanceIds) {
        return new TargetChoice(null, List.of(instanceIds), null, null, null);
    }

    /** ★区分3a: アクア・サーチは捨て 1→2枚(rework-triage.md) */
    @Test
    void アクアサーチは2枚引いて2枚捨てる() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 5);
        int spell = f.giveHand(f.me(), AQUA_SEARCH);
        f.giveHand(f.me(), PLAIN_MINION);
        f.giveHand(f.me(), STEALTH_MINION);
        int before = f.me().getDeck().size();

        game.playCard(f.room(), "me", spell, List.of(), false);
        assertThat(before - f.me().getDeck().size()).isEqualTo(2);
        assertThat(f.me().getPendingChoice()).as("2枚の必須ディスカードを問い合わせている").isNotNull();
        assertThat(f.me().getPendingChoice().min()).isEqualTo(2);
        assertThat(f.me().getPendingChoice().max()).isEqualTo(2);

        int handBefore = f.me().getHand().size();
        game.resolveChoice(f.room(), "me", List.of(0, 1));
        assertThat(handBefore - f.me().getHand().size()).isEqualTo(2);
        // ★使用済みのアクア・サーチ自身も墓地へ行くため、捨てた2枚+スペル自身=3枚になる
        assertThat(f.me().getTrash()).hasSize(3).contains(AQUA_SEARCH);
    }

    /** ★区分3a: 冥府への道は破壊 1体→2体(rework-triage.md) */
    @Test
    void 冥府への道は相手のミニオンを2体破壊する() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 5);
        MinionInstance a = f.putOnField(f.you(), PLAIN_MINION);
        MinionInstance b = f.putOnField(f.you(), "QTE-M-WIND-2");
        int spell = f.giveHand(f.me(), UNDERWORLD_ROAD);

        game.playCard(f.room(), "me", spell, List.of(minions(a.getInstanceId(), b.getInstanceId())), false);
        assertThat(f.you().getMinionZone()).isEmpty();
    }

    /** ★区分3a: 相手の場が1体しかない場合は、2体を要求できないため使用そのものが弾かれる */
    @Test
    void 冥府への道は相手のミニオンが1体だと使用できない() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 5);
        f.putOnField(f.you(), PLAIN_MINION);
        int spell = f.giveHand(f.me(), UNDERWORLD_ROAD);

        assertThatThrownBy(() -> game.playCard(f.room(), "me", spell, List.of(), false))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * ★区分3a: 命を削る烈火は全体ダメージ 2→3(rework-triage.md)。
     * ★HP1のミニオンでは2ダメージでも3ダメージでも「破壊される」結果は同じで見分けがつかない
     * (裁定196: 同じ結果を2つの理由で得られる試験は何も測っていない)。
     * HP3のディープシー・シャーク(QTE-M-WATER-6)を使い、実際の残りHPを直接測る。
     */
    @Test
    void 命を削る烈火は相手の場全体に3ダメージ() {
        AutoGameFixture f = newGame();
        f.giveMana(f.me(), 5);
        MinionInstance target = f.putOnField(f.you(), DEEP_SEA_SHARK); // HP3
        int leaderBefore = f.me().getLp();
        int spell = f.giveHand(f.me(), LIFE_CONSUMING_FLAME);

        game.playCard(f.room(), "me", spell, List.of(), false);
        assertThat(leaderBefore - f.me().getLp()).as("自分のリーダーへの自傷は3のまま").isEqualTo(3);
        assertThat(f.you().getMinionZone()).as("3ダメージ(HP3)で破壊されている").doesNotContain(target);
    }
}
