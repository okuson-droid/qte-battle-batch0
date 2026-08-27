package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.effect.PersistentAura;
import com.example.qte.effect.StatCalculator;
import com.example.qte.effect.TargetChoice;
import com.example.qte.game.GameActions;
import com.example.qte.game.GameService;
import com.example.qte.game.ManaCard;
import com.example.qte.game.MinionInstance;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.Civilization;
import com.example.qte.master.Keyword;
import com.example.qte.support.AutoGameFixture;

/**
 * Batch 56(作り直し② = 区分3b・4)の挙動の試験。
 *
 * <h2>この試験が測っているもの</h2>
 *
 * {@code notes/rework-triage.md} の区分3b(小さい差)・区分4(中くらい)のうち、
 * 裁定260〜267の回答が揃うまで着手できない8枚(区分3bの7件+区分4の1件)を除いた
 * 40枚の新しい挙動。旧本文で測っていた既存の試験(該当があれば)は書き換えるのが正しい
 * (裁定187 の含意。作り直しでは Ver0.4 の挙動を測る試験は落ちるのが正しい)。
 *
 * <h2>裁定待ちのため本バッチでは触っていないカード(参考)</h2>
 *
 * 突風の祝福・痛撃の炎術師・ガイル・フォックス・創世神ガイア・禁忌の冥魔剣・悪夢・
 * ボーン・コレクター(区分3b)/ ゾンストライカー(区分4)。詳細は
 * {@code notes/batch55-ruling-requests.md}(裁定260〜267)。
 */
@SpringBootTest
class Batch56ReworkTest {

    private static final String PLAIN_LEADER = "QTE-M-WATER-1"; // 常在効果を持たないリーダー
    /** コスト1・2/1・キーワードなし(フレア・ポーン)。道具として使う */
    private static final String PLAIN_MINION = "QTE-M-FIRE-2";
    /** コスト2・1/2(サイクロン・フェンサー)。マナ支払い用(スペルなので候補を汚さない) */
    private static final String MAGMA = "QTE-M-FIRE-10"; // マグマ・ストレート(スペル)

    @Autowired
    GameService game;

    @Autowired
    StatCalculator stats;

    @Autowired
    CardMasterRepository cards;

    @Autowired
    GameActions actions;

    private AutoGameFixture newGame() {
        AutoGameFixture f = new AutoGameFixture(cards, PLAIN_LEADER, PLAIN_LEADER);
        f.fillDeck(f.me(), 30);
        f.fillDeck(f.you(), 30);
        return f;
    }

    private void payMana(com.example.qte.game.PlayerState player, int count) {
        for (int i = 0; i < count; i++) {
            player.getManaZone().add(new ManaCard(MAGMA, false)); // スペルなので候補にならない
        }
    }

    private static TargetChoice hand(Integer... indexes) {
        return new TargetChoice(List.of(indexes), null, null, null, null);
    }

    private static TargetChoice none() {
        return new TargetChoice(null, null, null, null, null);
    }

    private static TargetChoice weapon(String side) {
        return new TargetChoice(null, null, null, null, List.of(side));
    }

    /** 墓地の位置を選ぶ(★Batch 57。冥府の禁皇・禁忌の代償) */
    private static TargetChoice trash(Integer... indexes) {
        return new TargetChoice(null, null, null, List.of(indexes), null);
    }

    /** 場のミニオンを選ぶ(★Batch 57) */
    private static TargetChoice minions(String... instanceIds) {
        return new TargetChoice(null, List.of(instanceIds), null, null, null);
    }

    /** 裏向きのマナを n 枚置く(★Batch 57。禁忌の代償の代償) */
    private static void giveFaceDownMana(com.example.qte.game.PlayerState player, int count) {
        for (int i = 0; i < count; i++) {
            ManaCard mana = new ManaCard(MAGMA, false);
            mana.turnFaceDown();
            player.getManaZone().add(mana);
        }
    }

    // ==================================================================
    // 火文明(区分3b・4。裁定待ちの痛撃の炎術師を除く3枚)
    // ==================================================================

    // ---- 武具昇華の炎(QTE-M-FIRE-24・区分3b) ----
    // 旧: 「自分のウェポンを1枚破壊する。そうしたら自分のリーダーを2回復。」
    // 新: 「ウェポンを1枚破壊する。そうしたら自分のリーダーを2回復。」(「自分の」が消えた)
    // → 裁定156(2)により両者のウェポンを対象にする(聖光の武装解除と同じ形)

    private static final String PLAIN_WEAPON = "QTE-M-WATER-13"; // 真珠の三叉槍(キーワードなし)

    @Test
    void 武具昇華の炎は相手のウェポンも破壊対象にできる() {
        AutoGameFixture f = newGame();
        f.me().setLp(10);
        f.you().setEquippedWeapon(cards.findById(PLAIN_WEAPON));
        payMana(f.me(), 2);
        int spell = f.giveHand(f.me(), "QTE-M-FIRE-24");
        int lpBefore = f.me().getLp();

        game.playCard(f.room(), "me", spell, List.of(weapon("OPPONENT")), false);

        assertThat(f.you().getEquippedWeapon()).as("相手のウェポンが破壊された").isNull();
        assertThat(f.me().getLp()).as("破壊できたので2回復した").isEqualTo(lpBefore + 2);
    }

    @Test
    void 武具昇華の炎は自分のウェポンも引き続き破壊できる() {
        AutoGameFixture f = newGame();
        f.me().setLp(10);
        f.me().setEquippedWeapon(cards.findById(PLAIN_WEAPON));
        payMana(f.me(), 2);
        int spell = f.giveHand(f.me(), "QTE-M-FIRE-24");
        int lpBefore = f.me().getLp();

        game.playCard(f.room(), "me", spell, List.of(weapon("SELF")), false);

        assertThat(f.me().getEquippedWeapon()).isNull();
        assertThat(f.me().getLp()).isEqualTo(lpBefore + 2);
    }

    @Test
    void 武具昇華の炎はウェポンが無ければ空撃ちで回復もしない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        int spell = f.giveHand(f.me(), "QTE-M-FIRE-24");
        int lpBefore = f.me().getLp();

        game.playCard(f.room(), "me", spell, List.of(none()), false);

        assertThat(f.me().getLp()).as("破壊できなかったので回復しない").isEqualTo(lpBefore);
    }

    // ---- 鳳凰神 ヴォルカニクスレヴォ(QTE-M-FIRE-22・区分4) ----
    // 旧: 累計5以上回復で「0コスト」として特殊召喚できる
    // 新: 累計5以上回復で「1コスト」として特殊召喚できる(【速攻】も追加。
    //     速攻はCardTextKeywordsがテキストから自動で拾うのでコード変更は不要)

    @Test
    void 鳳凰神ヴォルカニクスレヴォは累計5回復していれば1コストで特殊召喚できる() {
        AutoGameFixture f = newGame();
        f.me().recordHealedAmount(5, Civilization.FIRE);
        payMana(f.me(), 1);
        int idx = f.giveHand(f.me(), "QTE-M-FIRE-22");

        game.specialSummon(f.room(), "me", idx, List.of());

        assertThat(f.fieldIds(f.me())).contains("QTE-M-FIRE-22");
        assertThat(f.me().getAvailableMp()).as("1コスト分のMPが使われた").isEqualTo(0);
    }

    @Test
    void 鳳凰神ヴォルカニクスレヴォは1コスト分のマナが無ければ特殊召喚できない() {
        AutoGameFixture f = newGame();
        f.me().recordHealedAmount(5, Civilization.FIRE);
        int idx = f.giveHand(f.me(), "QTE-M-FIRE-22");

        assertThatThrownBy(() -> game.specialSummon(f.room(), "me", idx, List.of()))
                .hasMessageContaining("MP");
    }

    // ---- 覚醒の炎童(QTE-M-FIRE-20・区分4) ----
    // 旧: 「【特殊召喚】自分のリーダーの体力が10以下のときコスト0にする」だけ
    // 新: 「【知識】【特殊召喚】…(条件は同じ)…【召喚時】自分のリーダーの体力を1回復する」が追加
    //     【知識】は本カードのキーワードとして別枠(fire()のON_ENTER自動処理が1ドローする)。
    //     【召喚時】は通常召喚でも特殊召喚でも発動する(ON_SUMMON。GameService参照)

    @Test
    void 覚醒の炎童は通常召喚でも召喚時に1回復し知識で1枚引く() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 4);
        f.me().setLp(15);
        int deckBefore = f.me().getDeck().size();
        int idx = f.giveHand(f.me(), "QTE-M-FIRE-20");

        game.playCard(f.room(), "me", idx, List.of(), false);

        assertThat(f.me().getLp()).as("召喚時に1回復").isEqualTo(16);
        assertThat(f.me().getDeck().size()).as("知識で山札が1枚減る(1ドロー)").isEqualTo(deckBefore - 1);
    }

    @Test
    void 覚醒の炎童は体力10以下なら0コストで特殊召喚しても召喚時に1回復する() {
        AutoGameFixture f = newGame();
        f.me().setLp(9);
        int deckBefore = f.me().getDeck().size();
        int idx = f.giveHand(f.me(), "QTE-M-FIRE-20");

        game.specialSummon(f.room(), "me", idx, List.of());

        assertThat(f.me().getLp()).as("9から特殊召喚コスト0・召喚時1回復で10").isEqualTo(10);
        assertThat(f.me().getDeck().size()).isEqualTo(deckBefore - 1);
    }

    // ==================================================================
    // 水文明(区分3b・4。裁定待ちの3枚は無し)
    // ==================================================================

    /** コスト1・1/1・【知識】(ウィンド・ペティ)。知識を持つ道具 */
    private static final String KNOWLEDGE_MINION = "QTE-M-WIND-2";

    // ---- 双流の幻術師(QTE-M-WATER-21・区分3b) ----
    // 旧: 「場に居るミニオンの数」Cost-1(両者・全ミニオン)
    // 新: 「場に居る【知識】を持つミニオンの数」Cost-1 に戻った(旧台帳の参照に復帰)

    @Test
    void 双流の幻術師は知識を持つミニオンの数だけコストが下がる() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), KNOWLEDGE_MINION); // 知識あり
        f.putOnField(f.you(), PLAIN_MINION);    // 知識なし

        assertThat(stats.effectiveCost(f.state(), f.me(), f.card("QTE-M-WATER-21")))
                .as("知識を持つのは1体だけなのでコスト-1のみ").isEqualTo(6);
    }

    @Test
    void 双流の幻術師は知識を持たないミニオンだけならコストが下がらない() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), PLAIN_MINION);
        f.putOnField(f.you(), PLAIN_MINION);

        assertThat(stats.effectiveCost(f.state(), f.me(), f.card("QTE-M-WATER-21")))
                .as("知識を持つミニオンが居ないのでコストは印刷値のまま").isEqualTo(7);
    }

    // ---- 流転の智者(QTE-M-WATER-15・区分4) ----
    // 旧: 「起動能力(1ターンに1回): コスト2支払っても良い。そうしたら、マナを1枚手札に戻して2ドロー」
    // 新: 「【起動：2】マナを1枚手札に戻して2ドロー」
    // ★確認の結果、実装は既に mpCost=2・マナ1枚手札戻し+2ドローで一致しており、
    // 「1ターンに1回」もリーダー起動能力に共通の構造的制約(GameService.useLeaderAbility)
    // として既に効いている。表記が整理されただけで、実装変更は不要(rework-triage.mdの
    // 「境目には数枚の誤りが混じる」の実例)。この試験はその一致を確認するだけのもの

    @Test
    void 流転の智者は起動コスト2でマナ1枚を手札に戻し2ドローする() {
        AutoGameFixture f = new AutoGameFixture(cards, "QTE-M-WATER-15", PLAIN_LEADER);
        f.fillDeck(f.me(), 30);
        f.fillDeck(f.you(), 30);
        payMana(f.me(), 2);
        f.me().getManaZone().add(new ManaCard(PLAIN_MINION, false));
        int manaIndex = f.me().getManaZone().size() - 1;
        int deckBefore = f.me().getDeck().size();
        int handBefore = f.me().getHand().size();

        game.useLeaderAbility(f.room(), "me", List.of(new TargetChoice(null, null, List.of(manaIndex), null, null)));

        // 手札はマナ戻し1枚+2ドローで+3
        assertThat(f.me().getHand().size()).as("マナ1枚が手札へ戻り、2ドローも加わる").isEqualTo(handBefore + 3);
        assertThat(f.me().getDeck().size()).as("2ドロー").isEqualTo(deckBefore - 2);
    }

    @Test
    void 流転の智者の起動能力は1ターンに1回しか使えない() {
        AutoGameFixture f = new AutoGameFixture(cards, "QTE-M-WATER-15", PLAIN_LEADER);
        f.fillDeck(f.me(), 30);
        f.fillDeck(f.you(), 30);
        payMana(f.me(), 4);
        f.me().getManaZone().add(new ManaCard(PLAIN_MINION, false));
        f.me().getManaZone().add(new ManaCard(PLAIN_MINION, false));
        int idx1 = f.me().getManaZone().size() - 1;
        game.useLeaderAbility(f.room(), "me", List.of(new TargetChoice(null, null, List.of(idx1), null, null)));

        int idx2 = f.me().getManaZone().size() - 1;
        assertThatThrownBy(() -> game.useLeaderAbility(f.room(), "me",
                List.of(new TargetChoice(null, null, List.of(idx2), null, null))))
                .hasMessageContaining("1ターンに1回");
    }

    // ---- 智将 ポセイドン・コア(QTE-M-WATER-23・区分4) ----
    // 旧: 合計体力12以上→0コスト特殊召喚。【召喚時】自分のミニオンは【突進】を得る
    // 新: 合計体力9以上→0コスト特殊召喚(条件緩和)。【召喚時】自分の【知識】ミニオン
    //     2体につき1枚引く(効果そのものが別物に変わった)

    @Test
    void ポセイドンコアは合計体力9以上で特殊召喚でき召喚時に知識2体につき1枚引く() {
        AutoGameFixture f = newGame();
        // 場に出す前の合計: プレサージュ(HP3)+知恵の双翼(HP4)+死の知識人(HP3)=10。
        // 旧条件(12以上)は満たさないが新条件(9以上)は満たす
        MinionInstance presage = f.putOnField(f.me(), "QTE-M-WATER-24"); // 深海神プレサージュ
        MinionInstance wings = f.putOnField(f.me(), "QTE-M-WATER-22");   // 知恵の双翼
        MinionInstance knower = f.putOnField(f.me(), "QTE-M-DARK-19");   // 死の知識人
        assertThat(presage.hasKeyword(com.example.qte.master.Keyword.KNOWLEDGE)).isTrue();
        assertThat(wings.hasKeyword(com.example.qte.master.Keyword.KNOWLEDGE)).isTrue();
        assertThat(knower.hasKeyword(com.example.qte.master.Keyword.KNOWLEDGE)).isTrue();
        int totalHp = f.me().getMinionZone().stream()
                .filter(m -> m.hasKeyword(com.example.qte.master.Keyword.KNOWLEDGE))
                .mapToInt(MinionInstance::getCurrentHp).sum();
        assertThat(totalHp).as("旧条件12未満・新条件9以上になっている前提").isBetween(9, 11);

        int deckBefore = f.me().getDeck().size();
        int idx = f.giveHand(f.me(), "QTE-M-WATER-23");
        game.specialSummon(f.room(), "me", idx, List.of());

        assertThat(f.fieldIds(f.me())).contains("QTE-M-WATER-23");
        // このカード自身も印刷値として【知識】を持つため、fire()のON_ENTER自動処理による
        // 標準ドロー1枚 + 場の知識持ち4体(元の3体+自身)による【召喚時】の2枚 = 計3枚
        // (覚醒の炎童と同じく、【知識】は別枠のキーワードであり【召喚時】と重複しても正しい)
        assertThat(f.me().getDeck().size()).as("知識の自動1枚+召喚時2枚=計3枚引く")
                .isEqualTo(deckBefore - 3);
    }

    @Test
    void ポセイドンコアは合計体力9未満だと特殊召喚できない() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), KNOWLEDGE_MINION); // HP1のみ
        int idx = f.giveHand(f.me(), "QTE-M-WATER-23");

        assertThatThrownBy(() -> game.specialSummon(f.room(), "me", idx, List.of()))
                .hasMessageContaining("条件");
    }

    // ---- 静寂の瞑想(QTE-M-WATER-26・区分4) ----
    // 旧: 「3枚引く。このターンカードを使用できない。」+「メインフェーズの最初にしか使えない」
    // 新: 「2枚引く。」+「メインフェーズの最初にしか使えない」(使用できない制限が消えた)

    @Test
    void 静寂の瞑想は2枚引きその後もカードを使用できる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 1);
        int deckBefore = f.me().getDeck().size();
        int spell = f.giveHand(f.me(), "QTE-M-WATER-26");

        game.playCard(f.room(), "me", spell, List.of(), false);

        assertThat(f.me().getDeck().size()).as("2枚引く").isEqualTo(deckBefore - 2);
        assertThat(f.me().isCannotUseCardsThisTurn()).as("使用制限は消えた").isFalse();
        payMana(f.me(), 1);
        int magma = f.giveHand(f.me(), MAGMA);
        MinionInstance target = f.putOnField(f.you(), PLAIN_MINION);
        assertThatCode(() -> game.playCard(f.room(), "me", magma,
                List.of(new TargetChoice(null, List.of(target.getInstanceId()), null, null, null)), false))
                .as("静寂の瞑想の後でも普通にカードを使える").doesNotThrowAnyException();
    }

    @Test
    void 静寂の瞑想はメインフェイズの最初以外では使えない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 1);
        f.me().setPlayedCardThisTurn(true); // 既に1枚使った後を模す
        int spell = f.giveHand(f.me(), "QTE-M-WATER-26");

        assertThatThrownBy(() -> game.playCard(f.room(), "me", spell, List.of(), false))
                .hasMessageContaining("条件");
    }

    // ==================================================================
    // 風文明(区分3b・4。裁定待ちのガイル・フォックス・突風の祝福を除く7枚)
    // ==================================================================

    /** コスト3・2/1・キーワードなし(サイクロン・フェンサー)。「コスト3以下」の道具 */
    private static final String COST2_MINION = "QTE-M-WIND-5";

    // ---- 嵐の守り手(QTE-M-WIND-19・区分3b) ----
    // 旧: 「自分の場に体力3以上のミニオンが3体以上」で0コスト特殊召喚
    // 新: 「自分の場に体力3以下のミニオンがちょうど3体」で1コスト特殊召喚。【守護】が付いた

    @Test
    void 嵐の守り手は体力3以下のミニオンがちょうど3体なら1コストで特殊召喚できる() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), PLAIN_MINION);  // HP1
        f.putOnField(f.me(), PLAIN_MINION);  // HP1
        f.putOnField(f.me(), COST2_MINION);  // HP1
        payMana(f.me(), 1);
        int idx = f.giveHand(f.me(), "QTE-M-WIND-19");

        game.specialSummon(f.room(), "me", idx, List.of());

        assertThat(f.fieldIds(f.me())).contains("QTE-M-WIND-19");
        assertThat(f.me().getAvailableMp()).isEqualTo(0);
    }

    @Test
    void 嵐の守り手は体力3以下のミニオンが4体だと特殊召喚できない() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), PLAIN_MINION);
        f.putOnField(f.me(), PLAIN_MINION);
        f.putOnField(f.me(), COST2_MINION);
        f.putOnField(f.me(), COST2_MINION);
        payMana(f.me(), 1);
        int idx = f.giveHand(f.me(), "QTE-M-WIND-19");

        assertThatThrownBy(() -> game.specialSummon(f.room(), "me", idx, List.of()))
                .hasMessageContaining("条件");
    }

    @Test
    void 嵐の守り手は体力3を超えるミニオンが混ざると特殊召喚できない() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), PLAIN_MINION);
        f.putOnField(f.me(), PLAIN_MINION);
        MinionInstance tough = f.putOnField(f.me(), "QTE-M-WIND-19"); // 体力4(嵐の守り手自身の印刷値)
        assertThat(tough.getCurrentHp()).isGreaterThan(3);
        payMana(f.me(), 1);
        int idx = f.giveHand(f.me(), "QTE-M-WIND-19");

        assertThatThrownBy(() -> game.specialSummon(f.room(), "me", idx, List.of()))
                .hasMessageContaining("条件");
    }

    // ---- 風神ヴァーユ(QTE-M-WIND-21・区分4) ----
    // 旧: 自分の墓地に【守護】持ちが4枚以上→1コスト特殊召喚
    // 新: 自分の墓地に風文明のカードが6枚以上→1コスト特殊召喚(参照が別物に変わった)

    @Test
    void 風神ヴァーユは墓地の風文明カードが6枚以上なら1コストで特殊召喚できる() {
        AutoGameFixture f = newGame();
        for (int i = 0; i < 6; i++) {
            f.me().getTrash().add("QTE-M-WIND-2"); // 風文明のミニオン(ウィンド・ペティ)
        }
        payMana(f.me(), 1);
        int idx = f.giveHand(f.me(), "QTE-M-WIND-21");

        game.specialSummon(f.room(), "me", idx, List.of());

        assertThat(f.fieldIds(f.me())).contains("QTE-M-WIND-21");
    }

    @Test
    void 風神ヴァーユは守護持ちが多いだけでは特殊召喚できない() {
        AutoGameFixture f = newGame();
        // 【守護】持ちを4枚墓地に置く(旧条件は満たすが新条件=風文明6枚は満たさない)
        for (int i = 0; i < 4; i++) {
            f.me().getTrash().add("QTE-M-WATER-3"); // 潮流の魔導士(【守護】・水文明)
        }
        int idx = f.giveHand(f.me(), "QTE-M-WIND-21");

        assertThatThrownBy(() -> game.specialSummon(f.room(), "me", idx, List.of()))
                .hasMessageContaining("条件");
    }

    // ---- 詠唱の風詠士(QTE-M-WIND-15・区分4) ----
    // 旧: 「そのターン中3枚目に使うミニオンかスペル」のコスト-1(常在)
    // 新: 「そのターン中3枚目に使うカード」のコスト-1(ミニオン・スペルの限定が外れた)

    @Test
    void 詠唱の風詠士は3枚目に使うウェポンのコストも下がる() {
        AutoGameFixture f = new AutoGameFixture(cards, "QTE-M-WIND-15", PLAIN_LEADER);
        f.fillDeck(f.me(), 30);
        f.fillDeck(f.you(), 30);
        payMana(f.me(), 20);
        // 1・2枚目: 適当なミニオンを使ってカウンタを進める
        game.playCard(f.room(), "me", f.giveHand(f.me(), PLAIN_MINION), List.of(), false);
        game.playCard(f.room(), "me", f.giveHand(f.me(), PLAIN_MINION), List.of(), false);
        // 3枚目: ウェポン(旧文言なら対象外だったはず)
        assertThat(stats.effectiveCost(f.state(), f.me(), f.card("QTE-M-WIND-14")))
                .as("3枚目のウェポンにもコスト-1が効く").isEqualTo(2);
        game.playCard(f.room(), "me", f.giveHand(f.me(), "QTE-M-WIND-14"), List.of(), false);
        assertThat(f.me().getEquippedWeapon().id()).isEqualTo("QTE-M-WIND-14");
    }

    // ---- 選択の追い風(QTE-M-WIND-25・区分4) ----
    // 旧: 1枚引く。その後「守護を持つ」カードを1枚捨てても良い。そうしたらもう1枚引く
    // 新: 1枚引く。その後カードを1枚捨てても良い(守護限定が外れた)。そうしたらもう1枚引く

    @Test
    void 選択の追い風は守護を持たないカードも捨てて追加ドローできる() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        // 手札に守護を持たないカードだけを用意しておく(PLAIN_MINIONは守護なし)
        f.giveHand(f.me(), PLAIN_MINION);
        int spell = f.giveHand(f.me(), "QTE-M-WIND-25");
        int deckBefore = f.me().getDeck().size();

        game.playCard(f.room(), "me", spell, List.of(), false);
        // 選択待ちになっているはずなので、手札にある守護なしカードの位置を選ぶ
        int plainIndex = f.me().getHand().indexOf(PLAIN_MINION);
        assertThat(plainIndex).as("守護を持たないカードも選択候補に残っている").isGreaterThanOrEqualTo(0);
        game.resolveChoice(f.room(), "me", List.of(plainIndex));

        // 1枚目のドロー + 選んで捨てた後のドロー = 山札は2枚減る
        assertThat(f.me().getDeck().size()).as("2回ドローした").isEqualTo(deckBefore - 2);
        assertThat(f.me().getHand()).as("捨てたPLAIN_MINIONは手札から消える").doesNotContain(PLAIN_MINION);
    }

    // ---- 回帰の風穴(QTE-M-WIND-26・区分4) ----
    // 旧: 「ミニオンを1体手札に戻す。コスト+1してもよい。そうした場合もう一度唱え…」
    // 新: 「コスト+1してもよい。そうした場合もう一度唱え…。ミニオンを1体手札に戻す。」
    // ★確認の結果、コスト支払いは唱える時点で完了しており、効果本文中の記述順を
    // 入れ替えても実際の解決順(バウンス→強化なら再詠唱)は変わらない。実装変更は不要

    @Test
    void 回帰の風穴は通常使用でミニオンを1体手札に戻すだけ() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        MinionInstance target = f.putOnField(f.you(), PLAIN_MINION);
        int spell = f.giveHand(f.me(), "QTE-M-WIND-26");

        game.playCard(f.room(), "me", spell,
                List.of(new TargetChoice(null, List.of(target.getInstanceId()), null, null, null)), false);

        assertThat(f.fieldIds(f.you())).doesNotContain(PLAIN_MINION);
        assertThat(f.you().getHand()).contains(PLAIN_MINION);
    }

    // ---- 風護の杖(QTE-M-WIND-28・区分3b) ----
    // 旧: 「攻撃時自分のミニオンを1体選ぶ。そのミニオンの体力を+1し守護を与える。」
    // 新: 「【知識】攻撃時自分のミニオンを1体選ぶ。そのミニオンの体力を+1し【守護】を与える。」
    // ★【知識】は印刷キーワードの追加のみ(CardTextKeywordsが自動で拾う)。攻撃時効果は無変更

    @Test
    void 風護の杖は装備して攻撃するとミニオンの体力を1増やし守護を与える() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        MinionInstance ally = f.putOnField(f.me(), PLAIN_MINION);
        int before = ally.getMaxHp();
        game.playCard(f.room(), "me", f.giveHand(f.me(), "QTE-M-WIND-28"), List.of(), false);
        game.nextPhase(f.room(), "me"); // メイン→バトル

        // 相手の場は空なのでリーダーへの直接攻撃(targetInstanceId=null)。
        // 自分のミニオンは1体だけなので、風護の杖の対象選択は自動決定される
        game.leaderAttack(f.room(), "me", null);

        assertThat(ally.getMaxHp()).as("体力+1").isEqualTo(before + 1);
        assertThat(ally.hasKeyword(com.example.qte.master.Keyword.GUARD)).as("守護を得る").isTrue();
    }

    // ==================================================================
    // 光文明(区分3b・4。裁定待ちの3枚[英知の水晶・創世神ゾディアックアイリス・
    // 大天使ミカエル]を除く9枚)
    // ==================================================================

    private static final String LIGHT_LEADER = "QTE-M-LIGHT-1"; // 聖光の守護聖(光文明のリーダー)

    // ---- 聖域の案内人(QTE-M-LIGHT-3・区分3b) ----
    // 旧: 「自分の場に【守護】を持つミニオンがいるなら、もう一度【知識】」
    // 新: このカード自身に【守護】が付き、「他の」が明記された。自身を除外しないと
    // 常に真になってしまうため、自身を除いて数えるよう直した

    @Test
    void 聖域の案内人は他に守護を持つミニオンがいればもう一度知識で1枚多く引く() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), "QTE-M-LIGHT-2"); // 他の守護持ち(ライト・シールド)
        payMana(f.me(), 3);
        int deckBefore = f.me().getDeck().size();
        int idx = f.giveHand(f.me(), "QTE-M-LIGHT-3");

        game.playCard(f.room(), "me", idx, List.of(), false);

        // 自身の【知識】による1ドロー + 他に守護がいるため追加の1ドロー = 計2枚
        assertThat(f.me().getDeck().size()).as("知識2回分=2枚引く").isEqualTo(deckBefore - 2);
    }

    @Test
    void 聖域の案内人は自身の守護だけでは追加の知識は発動しない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 3);
        int deckBefore = f.me().getDeck().size();
        int idx = f.giveHand(f.me(), "QTE-M-LIGHT-3");

        game.playCard(f.room(), "me", idx, List.of(), false);

        // 場には自分自身しかいない(自身は「他の」から除外される)ので知識1回分だけ
        assertThat(f.me().getDeck().size()).as("知識1回分=1枚だけ引く").isEqualTo(deckBefore - 1);
    }

    // ---- 天界の守護神 ゾディアック(QTE-M-LIGHT-8・区分4) ----
    // 旧: 「このミニオンが場にいる限り、相手のリーダーは攻撃できない」のみ
    // 新: 「【召喚時】相手のウェポンを1つ選び破壊する」が追加された
    // ★リーダー攻撃を封じる常在部分は既にRuleGuardsに実装済みで無変更。今回追加したのは
    // 【召喚時】のウェポン破壊のみ

    /**
     * ★★★Batch 68(裁定282)で<b>2手</b>になった ——
     * 【召喚時】の対象はゾディアックが場に出てから選ぶ。
     *
     * <p>★このカードの要求は {@code upTo}(0〜1枚)なので、
     * 相手がウェポンを1つしか装備していなくても<b>自動では決まらない</b> ——
     * 「破壊しない」も選択肢だからである(裁定302)。
     * ★{@code ResumePoint.SUMMON_TARGETS} は割り込みの新しい
     * {@code PendingChoice.Kind.WEAPON}(★Batch 68 で足した)を通る初めての経路である。
     */
    @Test
    void ゾディアックは召喚時に相手のウェポンを破壊する() {
        AutoGameFixture f = newGame();
        f.you().setEquippedWeapon(cards.findById(PLAIN_WEAPON));
        payMana(f.me(), 9);
        int idx = f.giveHand(f.me(), "QTE-M-LIGHT-8");

        game.playCard(f.room(), "me", idx, List.of(), false);

        assertThat(f.fieldIds(f.me())).as("先に場へ出る").contains("QTE-M-LIGHT-8");
        assertThat(f.me().getPendingChoice().candidates())
                .as("★候補は相手側だけ(Side.OPPONENT)").containsExactly("OPPONENT");

        f.answerChoice(game, "me", "OPPONENT");

        assertThat(f.you().getEquippedWeapon()).as("相手のウェポンが破壊された").isNull();
    }

    /**
     * ★<b>「そうでない側」も測る</b>(裁定181)。同じ問い合わせで
     * 「何も選ばない」と答えれば、相手のウェポンは残る(★Batch 68。裁定302)。
     */
    @Test
    void ゾディアックの召喚時は選ばなければ何も壊さない() {
        AutoGameFixture f = newGame();
        f.you().setEquippedWeapon(cards.findById(PLAIN_WEAPON));
        payMana(f.me(), 9);

        game.playCard(f.room(), "me", f.giveHand(f.me(), "QTE-M-LIGHT-8"), List.of(), false);
        f.answerChoiceNone(game, "me");

        assertThat(f.you().getEquippedWeapon()).as("選ばなければ残る").isNotNull();
    }

    @Test
    void ゾディアックが場にいる間は相手のリーダーは攻撃できない() {
        AutoGameFixture f = newGame();
        f.putOnField(f.you(), "QTE-M-LIGHT-8"); // 相手の場にゾディアックがいる状態を模す
        f.me().setEquippedWeapon(cards.findById(PLAIN_WEAPON));
        game.nextPhase(f.room(), "me"); // メイン→バトル

        assertThatThrownBy(() -> game.leaderAttack(f.room(), "me", null))
                .hasMessageContaining("ゾディアック");
    }

    // ---- ホーリー・シグナル(QTE-M-LIGHT-10・区分4) ----
    // 旧: 相手の場で最も攻撃力の高いミニオン1体を破壊
    // 新: それに加えて、最も体力の低いミニオン1体も同時に破壊する
    // ★バグ修正: 「最も体力の低い」側をTargetSpec.Requirementにすると、相手の場が
    // 1体しかいないなど同じミニオンが両方の条件を満たすケースで
    // 「同じミニオンを重複して選べません」の例外になり使用不能になっていた。
    // AutoChoice.lowestCurrentHpで自動決定する形に直した(詳細はAutoChoice.javaのJavadoc)

    @Test
    void ホーリーシグナルは相手のミニオンが1体だけでも重複エラーにならず破壊できる() {
        AutoGameFixture f = newGame();
        MinionInstance only = f.putOnField(f.you(), PLAIN_MINION); // 唯一の1体=攻撃力最大かつ体力最小
        payMana(f.me(), 3);
        int spell = f.giveHand(f.me(), "QTE-M-LIGHT-10");

        assertThatCode(() -> game.playCard(f.room(), "me", spell,
                List.of(new TargetChoice(null, List.of(only.getInstanceId()), null, null, null)), false))
                .as("同じミニオンが両条件を満たしても例外にならない").doesNotThrowAnyException();

        assertThat(f.fieldIds(f.you())).as("唯一の1体が破壊された").doesNotContain(PLAIN_MINION);
    }

    @Test
    void ホーリーシグナルは攻撃力最大と体力最小が別のミニオンなら両方破壊する() {
        AutoGameFixture f = newGame();
        MinionInstance highestAttack = f.putOnField(f.you(), "QTE-M-WATER-3"); // 2/2(攻撃力最大)
        MinionInstance lowestHp = f.putOnField(f.you(), "QTE-M-WIND-2");       // 1/1(体力最小)
        MinionInstance survivor = f.putOnField(f.you(), "QTE-M-DARK-19");      // 1/3(どちらでもない)
        payMana(f.me(), 3);
        int spell = f.giveHand(f.me(), "QTE-M-LIGHT-10");

        game.playCard(f.room(), "me", spell,
                List.of(new TargetChoice(null, List.of(highestAttack.getInstanceId()), null, null, null)), false);

        assertThat(f.fieldIds(f.you()))
                .as("攻撃力最大と体力最小の2体が破壊され、それ以外は残る")
                .doesNotContain("QTE-M-WATER-3", "QTE-M-WIND-2")
                .contains("QTE-M-DARK-19");
        assertThat(lowestHp).isNotNull();
        assertThat(survivor).isNotNull();
    }

    // ---- 唱導の聖騎士(QTE-M-LIGHT-18・区分4) ----
    // 旧: 「自分のスペルのコスト-1」(無条件)
    // 新: 「自分のリーダーが光文明なら」の条件が付いた
    // ★確認の結果、実装は既にleaderIsLightの条件で新本文どおりに一致しており、
    // この試験はその一致を確認するだけのもの

    @Test
    void 唱導の聖騎士は自分のリーダーが光文明のときだけスペルのコストを下げる() {
        AutoGameFixture light = new AutoGameFixture(cards, LIGHT_LEADER, PLAIN_LEADER);
        light.fillDeck(light.me(), 30);
        light.fillDeck(light.you(), 30);
        light.putOnField(light.me(), "QTE-M-LIGHT-18");
        assertThat(stats.effectiveCost(light.state(), light.me(), light.card("QTE-M-LIGHT-9")))
                .as("光文明リーダーなのでコスト-1").isEqualTo(1);

        AutoGameFixture nonLight = newGame(); // PLAIN_LEADER(水文明)
        nonLight.putOnField(nonLight.me(), "QTE-M-LIGHT-18");
        assertThat(stats.effectiveCost(nonLight.state(), nonLight.me(), nonLight.card("QTE-M-LIGHT-9")))
                .as("光文明リーダーでないのでコストは印刷値のまま").isEqualTo(2);
    }

    // ---- 戒律のガーディアン(QTE-M-LIGHT-20・区分4) ----
    // 旧: 「自分のスペルのコスト-1、自分の【守護】のコスト-1」(文明を問わない)
    // 新: どちらも「光文明」限定になった
    // ★確認の結果、実装は既にcard.civilization()==LIGHTの条件で新本文どおりに一致している

    @Test
    void 戒律のガーディアンは光文明のスペルと光文明の守護ミニオンだけコストを下げる() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), "QTE-M-LIGHT-20");

        assertThat(stats.effectiveCost(f.state(), f.me(), f.card("QTE-M-LIGHT-9")))
                .as("光文明のスペルはコスト-1(2→1)").isEqualTo(1);
        assertThat(stats.effectiveCost(f.state(), f.me(), f.card("QTE-M-LIGHT-2")))
                .as("光文明の守護ミニオンはコスト-1(2→1)").isEqualTo(1);
        assertThat(stats.effectiveCost(f.state(), f.me(), f.card(MAGMA)))
                .as("光文明でないスペルはコストが下がらない").isEqualTo(1);
    }

    // ---- 断罪の大天使(QTE-M-LIGHT-24・区分4) ----
    // 旧: 「相手が2枚目以降のカードを引くとき体力を1失う」(ダメージ効果)
    // 新: 「相手がカードを引いたとき、それが3枚目以降のカードなら、相手は引く代わりに
    //     そのカードを墓地に置く」(ドローの置換効果に変わった)

    @Test
    void 断罪の大天使は相手の3枚目以降のドローを墓地送りに置換する() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), "QTE-M-LIGHT-24");
        int handBefore = f.you().getHand().size();
        int trashBefore = f.you().getTrash().size();
        int deckBefore = f.you().getDeck().size();

        actions.drawCards(f.room(), f.you(), 3);

        assertThat(f.you().getHand().size()).as("1・2枚目は普通に手札へ入る").isEqualTo(handBefore + 2);
        assertThat(f.you().getTrash().size()).as("3枚目は引く代わりに墓地へ置かれる")
                .isEqualTo(trashBefore + 1);
        assertThat(f.you().getDeck().size()).as("山札からは3枚とも減っている").isEqualTo(deckBefore - 3);
    }

    // ---- 聖光の武装解除(QTE-M-LIGHT-26・区分3b) ----
    // 旧: 「ウェポンを1枚破壊する。【還元】」
    // 新: 「ウェポンを1枚破壊する。そうしたらカードを1枚引く。【還元】」
    //     (「そうしたらカードを1枚引く」が追加された。武具昇華の炎と同じ条件付き形)

    @Test
    void 聖光の武装解除はウェポンを破壊できたときだけ1枚引く() {
        AutoGameFixture f = newGame();
        f.you().setEquippedWeapon(cards.findById(PLAIN_WEAPON));
        payMana(f.me(), 2);
        int spell = f.giveHand(f.me(), "QTE-M-LIGHT-26");
        int deckBefore = f.me().getDeck().size();

        game.playCard(f.room(), "me", spell, List.of(weapon("OPPONENT")), false);

        assertThat(f.you().getEquippedWeapon()).isNull();
        assertThat(f.me().getDeck().size()).as("破壊できたので1枚引く").isEqualTo(deckBefore - 1);
    }

    @Test
    void 聖光の武装解除は空撃ちのときは引かない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 2);
        int spell = f.giveHand(f.me(), "QTE-M-LIGHT-26");
        int deckBefore = f.me().getDeck().size();

        game.playCard(f.room(), "me", spell, List.of(none()), false);

        assertThat(f.me().getDeck().size()).as("破壊できなかったので引かない").isEqualTo(deckBefore);
    }

    // ---- 詠唱の宝珠(QTE-M-LIGHT-28・区分3b) ----
    //
    // ★★★Batch 73 で、ここの前提そのものが誤っていたことが分かった。
    //
    // 56 はこう書いていた ——
    //   「旧: 次の自分のターンに唱えるスペルすべてのコスト-1(文明を問わない)
    //     新: 『光のスペル』限定になった
    //     ★確認の結果、実装は既に新本文どおりに一致している」
    //
    // ★<b>旧は「すべて」ではなく「次の1枚」だった。</b>
    //   Ver1.1 の変更は<b>2つ</b>あり(ver0.4-transcription-notes 5章の台帳 0106)、
    //   「次の1枚 → 次の自ターン中の全スペル」と「光文明への限定」である。
    //   56 は<b>旧を「すべて」と思い込んでいた</b>ので、
    //   残る差は文明だけだと結論し、枚数の側を1度も見なかった。
    //   ★★★<b>誤った前提は、正しい確認を素通りさせる。</b>
    //
    // 73 で枚数と期限を本文どおりに直した。番人は Batch73TextImplTest にある。

    @Test
    void 詠唱の宝珠は破壊後光のスペルのコストだけ下げる() {
        AutoGameFixture f = newGame();
        // ★Batch 73: 期限は「次の自分のターンの終了時」になった(ON_NEXT_SPELL は消えた)
        f.me().getPersistentAuras().add(PersistentAura.untilEndOfTurn(
                "QTE-M-LIGHT-28", f.state().getTurnNumber() + 2));

        assertThat(stats.effectiveCost(f.state(), f.me(), f.card("QTE-M-LIGHT-9")))
                .as("光のスペルはコスト-1(2→1)").isEqualTo(1);
        assertThat(stats.effectiveCost(f.state(), f.me(), f.card(MAGMA)))
                .as("光文明でないスペルはコストが下がらない").isEqualTo(1);
    }

    // ==================================================================
    // 闇文明(★Batch 57 = 56後半。区分3b・4。裁定待ちの4枚を除く9枚)
    // ==================================================================

    /** 【守護】だけを持つ素のミニオン(ゴーレム・ウォール 3/1/5)。道具として使う */
    private static final String PLAIN_GUARD = "QTE-M-EARTH-2";
    private static final String ZOMB_STRIKER = "QTE-M-DARK-16";

    // ---- 執念の暗殺者(QTE-M-DARK-20・区分3b) ----
    // 旧: 「【召喚時】ミニオン1体に3ダメージ。自分のミニオンが破壊されるたび1枚引いてもよい。」
    // 新: 「【召喚時】ミニオン1体に3ダメージ。【常在】ミニオンが破壊されるたび山札から1枚引いてもよい。」
    // → 「自分の」が消えたので、裁定156(2)により相手のミニオンの破壊でも引ける

    // ★★Batch 64: 「引いてもよい」は自動判断ではなく<b>本人への問い合わせ</b>になった(裁定299)。
    //   破壊しただけでは引かず、[はい] と答えて初めて1枚増える。

    @Test
    void 執念の暗殺者は相手のミニオンが破壊されても引く() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), "QTE-M-DARK-20");
        MinionInstance victim = f.putOnField(f.you(), PLAIN_MINION);
        int deckBefore = f.me().getDeck().size();
        int handBefore = f.me().getHand().size();

        actions.destroyMinion(f.room(), f.you(), victim);

        assertThat(f.me().getPendingChoice()).as("★64: 破壊しただけではまだ引かない").isNotNull();
        assertThat(f.me().getDeck().size()).isEqualTo(deckBefore);
        game.resolveChoice(f.room(), "me", List.of(0)); // [はい]

        assertThat(f.me().getDeck().size()).as("新: 相手のミニオンの破壊でも1枚引く")
                .isEqualTo(deckBefore - 1);
        assertThat(f.me().getHand().size()).isEqualTo(handBefore + 1);
    }

    @Test
    void 執念の暗殺者は自分のミニオンの破壊でも引き続き引く() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), "QTE-M-DARK-20");
        MinionInstance victim = f.putOnField(f.me(), PLAIN_MINION);
        int deckBefore = f.me().getDeck().size();

        actions.destroyMinion(f.room(), f.me(), victim);
        game.resolveChoice(f.room(), "me", List.of(0)); // [はい]

        assertThat(f.me().getDeck().size()).as("旧からの挙動も残っている").isEqualTo(deckBefore - 1);
    }

    /** ★Batch 64: [いいえ] と答えれば引かない —— 「してもよい」が本当に任意になった */
    @Test
    void 執念の暗殺者はいいえと答えれば引かない() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), "QTE-M-DARK-20");
        MinionInstance victim = f.putOnField(f.me(), PLAIN_MINION);
        int deckBefore = f.me().getDeck().size();

        actions.destroyMinion(f.room(), f.me(), victim);
        game.resolveChoice(f.room(), "me", List.of()); // [いいえ]

        assertThat(f.me().getDeck().size()).as("★引かない").isEqualTo(deckBefore);
        assertThat(f.me().getPendingChoice()).as("問い合わせは解消している").isNull();
    }

    @Test
    void 執念の暗殺者のドローは相手には起きない() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), "QTE-M-DARK-20");
        MinionInstance victim = f.putOnField(f.you(), PLAIN_MINION);
        int opponentDeckBefore = f.you().getDeck().size();

        actions.destroyMinion(f.room(), f.you(), victim);

        assertThat(f.you().getDeck().size())
                .as("引くのはカードの持ち主であって、破壊された側ではない")
                .isEqualTo(opponentDeckBefore);
    }

    // ---- 墓場の怨念集合体(QTE-M-DARK-22・区分3b) ----
    // 旧: 「自分の墓地にあるスペル以外のカード1枚につきAttack+1。【召喚時】…」
    // 新: 「自分の墓地にあるスペル以外のカード1枚につきCost-1,Attack+1。【召喚時】… 【守護】」

    @Test
    void 墓場の怨念集合体は墓地のスペル以外の数だけコストも下がる() {
        AutoGameFixture f = newGame();
        int printed = f.card("QTE-M-DARK-22").cost();
        f.me().getTrash().addAll(List.of(PLAIN_MINION, PLAIN_MINION, PLAIN_MINION));

        assertThat(stats.effectiveCost(f.state(), f.me(), f.card("QTE-M-DARK-22")))
                .as("新: 墓地のスペル以外3枚でコスト-3").isEqualTo(printed - 3);
    }

    @Test
    void 墓場の怨念集合体のコスト軽減はスペルを数えない() {
        AutoGameFixture f = newGame();
        int printed = f.card("QTE-M-DARK-22").cost();
        f.me().getTrash().addAll(List.of(MAGMA, MAGMA));

        assertThat(stats.effectiveCost(f.state(), f.me(), f.card("QTE-M-DARK-22")))
                .as("スペルだけの墓地では軽減されない").isEqualTo(printed);
    }

    @Test
    void 墓場の怨念集合体はAttack加算を保ったまま守護を持つ() {
        AutoGameFixture f = newGame();
        f.me().getTrash().addAll(List.of(PLAIN_MINION, PLAIN_MINION));
        MinionInstance self = f.putOnField(f.me(), "QTE-M-DARK-22");

        assertThat(stats.effectiveAttack(f.state(), f.me(), self))
                .as("Attack加算は据え置き(印刷0+2)").isEqualTo(2);
        assertThat(self.hasKeyword(Keyword.GUARD)).as("新: 【守護】が付いた").isTrue();
    }

    // ---- 群がる死霊王(QTE-M-DARK-21・区分3b) ----
    // 旧: 「自分の墓地にある「ゾンストライカー」の数だけコストを-1する。」(印刷コスト6)
    // 新: 「自分の墓地にある「ゾンストライカー」の数コスト-2する。」(印刷コスト8)

    @Test
    void 群がる死霊王はゾンストライカー1枚につきコストが2下がる() {
        AutoGameFixture f = newGame();
        int printed = f.card("QTE-M-DARK-21").cost();
        f.me().getTrash().addAll(List.of(ZOMB_STRIKER, ZOMB_STRIKER));

        assertThat(stats.effectiveCost(f.state(), f.me(), f.card("QTE-M-DARK-21")))
                .as("新: 2枚で-4(旧は-2)").isEqualTo(printed - 4);
    }

    @Test
    void 群がる死霊王はゾンストライカー以外を数えない() {
        AutoGameFixture f = newGame();
        int printed = f.card("QTE-M-DARK-21").cost();
        f.me().getTrash().addAll(List.of(PLAIN_MINION, PLAIN_MINION));

        assertThat(stats.effectiveCost(f.state(), f.me(), f.card("QTE-M-DARK-21")))
                .isEqualTo(printed);
    }

    // ---- 死者蘇生(QTE-M-DARK-12・区分3b)★実装変更なし ----
    // 旧・新の差は送りがな(「好きな数の」「破壊した数だけ」)と読点だけである

    @Test
    void 死者蘇生は生贄の数だけ軽くなり突進付きで蘇生する() {
        AutoGameFixture f = newGame();
        MinionInstance a = f.putOnField(f.me(), PLAIN_MINION);
        MinionInstance b = f.putOnField(f.me(), PLAIN_MINION);
        f.me().getTrash().add(PLAIN_GUARD);
        payMana(f.me(), f.card("QTE-M-DARK-12").cost() - 2);
        int spell = f.giveHand(f.me(), "QTE-M-DARK-12");

        game.playCard(f.room(), "me", spell,
                List.of(minions(a.getInstanceId(), b.getInstanceId())), false);

        // ★★Batch 64: どの1体を蘇生するかは本人が選ぶ(裁定299)。
        // 生贄2体も墓地に落ちているので候補は3つあり、選ぶ余地があるぶん問い合わせが出る
        String guardPosition = String.valueOf(f.me().getTrash().indexOf(PLAIN_GUARD));
        game.resolveChoice(f.room(), "me",
                List.of(f.me().getPendingChoice().candidates().indexOf(guardPosition)));

        assertThat(f.fieldIds(f.me())).as("生贄2体が消え、蘇生した1体だけが残る")
                .containsExactly(PLAIN_GUARD);
        assertThat(f.me().getMinionZone().get(0).hasKeyword(Keyword.RUSH))
                .as("蘇生したミニオンは【突進】を持つ").isTrue();
    }

    // ---- 冥府の禁皇(QTE-M-DARK-1・区分4) ----
    // 旧: 「起動能力(1ターンに1回): 自分のマナゾーンの「裏向きのカード」を1枚選び手札に戻す。
    //      そうした場合、カードを2枚引く。」
    // 新: 「【起動：１】自分の墓地のカードを1枚選び手札に戻す。
    //      そうした場合、山札の上から2枚を墓地に置く」
    // → 参照ゾーンがマナ裏向き→墓地、後半が2ドロー→セルフミル2枚に変わった

    private AutoGameFixture newGameWithLeader(String myLeaderId) {
        AutoGameFixture f = new AutoGameFixture(cards, myLeaderId, PLAIN_LEADER);
        f.fillDeck(f.me(), 30);
        f.fillDeck(f.you(), 30);
        return f;
    }

    @Test
    void 冥府の禁皇は墓地のカードを手札に戻し山札の上から2枚を墓地に置く() {
        AutoGameFixture f = newGameWithLeader("QTE-M-DARK-1");
        payMana(f.me(), 1);
        giveFaceDownMana(f.me(), 1);
        f.me().getTrash().add(PLAIN_GUARD);
        int deckBefore = f.me().getDeck().size();

        game.useLeaderAbility(f.room(), "me", List.of(trash(0)));

        assertThat(f.me().getHand()).as("新: 墓地のカードが手札に戻る").containsExactly(PLAIN_GUARD);
        assertThat(f.me().getDeck().size()).as("新: 山札の上から2枚が墓地へ(ドローではない)")
                .isEqualTo(deckBefore - 2);
        assertThat(f.me().getTrash()).as("戻した1枚が消え、ミルした2枚が入る").hasSize(2);
        assertThat(f.me().getFaceDownManaCount()).as("旧の参照先だった裏向きマナはもう触らない")
                .isEqualTo(1);
    }

    @Test
    void 冥府の禁皇は墓地が空なら使用できない() {
        AutoGameFixture f = newGameWithLeader("QTE-M-DARK-1");
        payMana(f.me(), 1);
        giveFaceDownMana(f.me(), 2);

        assertThatThrownBy(() -> game.useLeaderAbility(f.room(), "me", List.of(trash())))
                .as("旧は裏向きマナがあれば撃てたが、新は墓地が空だと撃てない")
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- 獄門の裁定者(QTE-M-DARK-23・区分4) ----
    // 旧: 「【守護】このミニオンがダメージを受けた時、相手のリーダーに2ダメージ。」(9/5/7)
    // 新: 上記に加えて「このミニオンはリーダーを攻撃できない。」(9/9/9)

    @Test
    void 獄門の裁定者はリーダーを攻撃できない() {
        AutoGameFixture f = newGame();
        MinionInstance judge = f.putOnField(f.me(), "QTE-M-DARK-23");
        f.state().setPhase(com.example.qte.game.TurnPhase.BATTLE);

        assertThatThrownBy(() -> game.attack(f.room(), "me", judge.getInstanceId(), null))
                .as("新: リーダーへの攻撃だけが止まる")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("獄門の裁定者");
    }

    @Test
    void 獄門の裁定者はミニオンには攻撃でき被ダメージで相手リーダーを焼く() {
        AutoGameFixture f = newGame();
        MinionInstance judge = f.putOnField(f.me(), "QTE-M-DARK-23");
        MinionInstance target = f.putOnField(f.you(), PLAIN_MINION); // 2/1
        f.state().setPhase(com.example.qte.game.TurnPhase.BATTLE);
        int lpBefore = f.you().getLp();

        game.attack(f.room(), "me", judge.getInstanceId(), target.getInstanceId());

        assertThat(f.you().getMinionZone()).as("ミニオンへの攻撃は通る").isEmpty();
        assertThat(f.you().getLp()).as("反撃ダメージを受けたので相手リーダーに2ダメージ")
                .isEqualTo(lpBefore - 2);
    }

    // ---- 禁忌の代償(QTE-M-DARK-10・区分4) ----
    // 旧: 「自分のマナゾーンの「裏向きのカード」1枚を破壊する。相手のミニオン1体を破壊する。」
    // 新: 「…1枚を破壊する。その後自分の墓地からコスト4以下のミニオンを1体選び場に出す。」

    @Test
    void 禁忌の代償は裏向きマナを砕いて墓地のコスト4以下を場に出す() {
        AutoGameFixture f = newGame();
        giveFaceDownMana(f.me(), 1);
        payMana(f.me(), f.card("QTE-M-DARK-10").cost());
        f.me().getTrash().add(PLAIN_GUARD);
        MinionInstance survivor = f.putOnField(f.you(), PLAIN_MINION);
        int spell = f.giveHand(f.me(), "QTE-M-DARK-10");

        game.playCard(f.room(), "me", spell, List.of(trash(0)), false);

        assertThat(f.me().getFaceDownManaCount()).as("代償の裏向きマナ1枚は砕かれる").isZero();
        assertThat(f.fieldIds(f.me())).as("新: 墓地のミニオンが場に出る").containsExactly(PLAIN_GUARD);
        assertThat(f.you().getMinionZone()).as("旧の相手ミニオン破壊はもう起きない")
                .containsExactly(survivor);
    }

    @Test
    void 禁忌の代償は墓地にコスト4以下のミニオンが無ければ使用できない() {
        AutoGameFixture f = newGame();
        giveFaceDownMana(f.me(), 1);
        payMana(f.me(), f.card("QTE-M-DARK-10").cost());
        f.me().getTrash().add(MAGMA); // スペルしかない墓地
        int spell = f.giveHand(f.me(), "QTE-M-DARK-10");

        assertThatThrownBy(() -> game.playCard(f.room(), "me", spell, List.of(trash()), false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 禁忌の代償は裏向きマナが無ければ使用できない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), f.card("QTE-M-DARK-10").cost());
        f.me().getTrash().add(PLAIN_GUARD);
        int spell = f.giveHand(f.me(), "QTE-M-DARK-10");

        assertThatThrownBy(() -> game.playCard(f.room(), "me", spell, List.of(trash(0)), false))
                .as("代償の形は旧から据え置き").isInstanceOf(IllegalStateException.class);
    }

    // ---- 絶望の連鎖(QTE-M-DARK-9・区分4) ----
    // 旧: 「自分のミニオン1体を破壊する。相手のミニオン1体を破壊する。」
    // 新: 「…そうしたら相手のミニオン1体を破壊する。
    //      このターンミニオンが3体以上破壊されていたならカードを1枚引く。」

    @Test
    void 絶望の連鎖は破壊が2体だけならドローしない() {
        AutoGameFixture f = newGame();
        MinionInstance mine = f.putOnField(f.me(), PLAIN_MINION);
        MinionInstance theirs = f.putOnField(f.you(), PLAIN_MINION);
        payMana(f.me(), f.card("QTE-M-DARK-9").cost());
        int spell = f.giveHand(f.me(), "QTE-M-DARK-9");
        int deckBefore = f.me().getDeck().size();

        game.playCard(f.room(), "me", spell,
                List.of(minions(mine.getInstanceId()), minions(theirs.getInstanceId())), false);

        assertThat(f.me().getMinionZone()).isEmpty();
        assertThat(f.you().getMinionZone()).isEmpty();
        assertThat(f.me().getDeck().size()).as("このターンの破壊は2体なので引かない")
                .isEqualTo(deckBefore);
    }

    @Test
    void 絶望の連鎖はこのターン3体以上破壊されていたら1枚引く() {
        AutoGameFixture f = newGame();
        MinionInstance sacrificed = f.putOnField(f.me(), PLAIN_MINION);
        actions.destroyMinion(f.room(), f.me(), sacrificed); // 事前に1体(=このターン1体目)
        MinionInstance mine = f.putOnField(f.me(), PLAIN_MINION);
        MinionInstance theirs = f.putOnField(f.you(), PLAIN_MINION);
        payMana(f.me(), f.card("QTE-M-DARK-9").cost());
        int spell = f.giveHand(f.me(), "QTE-M-DARK-9");
        int deckBefore = f.me().getDeck().size();

        game.playCard(f.room(), "me", spell,
                List.of(minions(mine.getInstanceId()), minions(theirs.getInstanceId())), false);

        assertThat(f.state().getMinionsDestroyedThisTurn())
                .as("数えるのは両者の合計(裁定156(2)・205)").isEqualTo(3);
        assertThat(f.me().getDeck().size()).as("新: 3体以上なので1枚引く").isEqualTo(deckBefore - 1);
    }

    @Test
    void 絶望の連鎖は相手のミニオンが居なくても自分の1体だけで撃てる() {
        AutoGameFixture f = newGame();
        MinionInstance mine = f.putOnField(f.me(), PLAIN_MINION);
        payMana(f.me(), f.card("QTE-M-DARK-9").cost());
        int spell = f.giveHand(f.me(), "QTE-M-DARK-9");

        assertThatCode(() -> game.playCard(f.room(), "me", spell,
                List.of(minions(mine.getInstanceId()), none()), false))
                .doesNotThrowAnyException();
        assertThat(f.me().getMinionZone()).isEmpty();
    }

    // ==================================================================
    // 土文明(★Batch 57 = 56後半。区分3b・4。裁定待ちの創世神ガイアを除く8枚)
    // ==================================================================

    // ---- アースクエイク・ジャイアント(QTE-M-EARTH-4・区分3b)★実装変更なし ----
    // 旧・新の差は【召喚時】の印が明記されたことだけである

    @Test
    void アースクエイクジャイアントは召喚時に相手の守護だけを全破壊する() {
        AutoGameFixture f = newGame();
        f.putOnField(f.you(), PLAIN_GUARD);
        f.putOnField(f.you(), PLAIN_GUARD);
        MinionInstance survivor = f.putOnField(f.you(), PLAIN_MINION);
        f.putOnField(f.me(), PLAIN_GUARD); // 自分の守護は巻き込まれない
        payMana(f.me(), f.card("QTE-M-EARTH-4").cost());
        int hand = f.giveHand(f.me(), "QTE-M-EARTH-4");

        game.playCard(f.room(), "me", hand, List.of(), false);

        assertThat(f.you().getMinionZone()).containsExactly(survivor);
        assertThat(f.fieldIds(f.me())).contains(PLAIN_GUARD);
    }

    // ---- 大地の精霊 グラン(QTE-M-EARTH-3・区分3b/4)★実装変更なし ----

    @Test
    void 大地の精霊グランは召喚時に山札の上を表向きでマナに置く() {
        AutoGameFixture f = newGame();
        payMana(f.me(), f.card("QTE-M-EARTH-3").cost());
        int manaBefore = f.me().getManaZone().size();
        int deckBefore = f.me().getDeck().size();
        int hand = f.giveHand(f.me(), "QTE-M-EARTH-3");

        game.playCard(f.room(), "me", hand, List.of(), false);

        assertThat(f.me().getManaZone()).hasSize(manaBefore + 1);
        assertThat(f.me().getDeck().size()).isEqualTo(deckBefore - 1);
    }

    // ---- 天変地異のタイタン(QTE-M-EARTH-21・区分3b)★実装変更なし ----

    @Test
    void 天変地異のタイタンは召喚時に相手全体7ダメージと2ドローを行う() {
        AutoGameFixture f = newGame();
        f.putOnField(f.you(), PLAIN_MINION);
        f.putOnField(f.you(), PLAIN_GUARD);
        payMana(f.me(), f.card("QTE-M-EARTH-21").cost());
        int deckBefore = f.me().getDeck().size();
        int hand = f.giveHand(f.me(), "QTE-M-EARTH-21");

        game.playCard(f.room(), "me", hand, List.of(), false);

        assertThat(f.you().getMinionZone()).as("HP7以下は全滅する").isEmpty();
        assertThat(f.me().getDeck().size()).isEqualTo(deckBefore - 2);
    }

    // ---- 安らぎのガーディアン(QTE-M-EARTH-20・区分3b) ----
    // 旧: 「リーダーのHPを2回復。ターンエンド時リーダーのHPを4回復。」
    // 新: 「【守護】【召喚時】リーダーのHPを2回復 自分のターンエンド時リーダーのHPを4回復」
    // → ON_TURN_END は両者の場を回すため、旧は相手のターンの終わりにも4回復していた

    @Test
    void 安らぎのガーディアンは自分のターンエンドにだけ4回復する() {
        AutoGameFixture f = newGame();
        f.putOnField(f.me(), "QTE-M-EARTH-20");
        f.me().setLp(5);

        game.endTurn(f.room(), "me");
        assertThat(f.me().getLp()).as("自分のターンエンドでは回復する").isEqualTo(9);

        game.endTurn(f.room(), "you");
        assertThat(f.me().getLp()).as("新: 相手のターンエンドでは回復しない(旧は8回復していた)")
                .isEqualTo(9);
    }

    @Test
    void 安らぎのガーディアンは守護を持つ() {
        AutoGameFixture f = newGame();
        assertThat(f.putOnField(f.me(), "QTE-M-EARTH-20").hasKeyword(Keyword.GUARD))
                .as("新: 【守護】が付いた").isTrue();
    }

    // ---- 苗木植えの精霊(QTE-M-EARTH-16・区分3b)★実装変更なし ----

    @Test
    void 苗木植えの精霊は召喚時に手札1枚を表向きでマナに置く() {
        AutoGameFixture f = newGame();
        payMana(f.me(), f.card("QTE-M-EARTH-16").cost());
        f.giveHand(f.me(), PLAIN_GUARD); // マナに置かれる側(自分自身は対象にできない)
        int hand = f.giveHand(f.me(), "QTE-M-EARTH-16");
        int manaBefore = f.me().getManaZone().size();

        game.playCard(f.room(), "me", hand, List.of(hand(0)), false);

        assertThat(f.me().getManaZone()).hasSize(manaBefore + 1);
        assertThat(f.me().getHand()).as("マナに置いた手札は手札から消える").isEmpty();
    }

    // ---- 豊穣の地霊主(QTE-M-EARTH-15・区分3b)★実装変更なし ----
    // 旧・新の差は【常在】の印だけである

    @Test
    void 豊穣の地霊主はそのターン2回目のマナ配置で1枚引く() {
        AutoGameFixture f = newGameWithLeader("QTE-M-EARTH-15");
        int deckBefore = f.me().getDeck().size();

        actions.placeCardInManaFaceUp(f.room(), f.me(), PLAIN_MINION);
        assertThat(f.me().getDeck().size()).as("1回目では引かない").isEqualTo(deckBefore);

        actions.placeCardInManaFaceUp(f.room(), f.me(), PLAIN_MINION);
        assertThat(f.me().getDeck().size()).as("2回目で1枚引く").isEqualTo(deckBefore - 1);

        actions.placeCardInManaFaceUp(f.room(), f.me(), PLAIN_MINION);
        assertThat(f.me().getDeck().size()).as("3回目では引かない").isEqualTo(deckBefore - 1);
    }

    // ---- ガイア・ハンマー(QTE-M-EARTH-14・区分4)★実装変更なし ----
    // 【召喚時】の印が付いたが、ウェポンにとっての「場に出る」は装備(ON_EQUIP)である

    @Test
    void ガイアハンマーは装備時に山札の上を表向きでマナに置く() {
        AutoGameFixture f = newGame();
        payMana(f.me(), f.card("QTE-M-EARTH-14").cost());
        int manaBefore = f.me().getManaZone().size();
        int deckBefore = f.me().getDeck().size();
        int hand = f.giveHand(f.me(), "QTE-M-EARTH-14");

        game.playCard(f.room(), "me", hand, List.of(), false);

        assertThat(f.me().getEquippedWeapon().id()).isEqualTo("QTE-M-EARTH-14");
        assertThat(f.me().getManaZone()).hasSize(manaBefore + 1);
        assertThat(f.me().getDeck().size()).isEqualTo(deckBefore - 1);
    }

    // ---- 大地の恵み(QTE-M-EARTH-9・区分4) ----
    // 旧: 「自分の山札の上から1枚を表向きでマナに置く。」
    // 新: 上記に加えて「自分のマナが10枚以上ならカードを1枚引く。」

    @Test
    void 大地の恵みはマナが10枚以上になったら1枚引く() {
        AutoGameFixture f = newGame();
        payMana(f.me(), 9);
        int spell = f.giveHand(f.me(), "QTE-M-EARTH-9");
        int deckBefore = f.me().getDeck().size();

        game.playCard(f.room(), "me", spell, List.of(), false);

        assertThat(f.me().getManaZone()).as("9枚 + 置いた1枚 = 10枚").hasSize(10);
        assertThat(f.me().getDeck().size()).as("新: マナ加速1枚 + ドロー1枚で山札-2")
                .isEqualTo(deckBefore - 2);
    }

    @Test
    void 大地の恵みはマナが10枚に届かなければ引かない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), f.card("QTE-M-EARTH-9").cost());
        int spell = f.giveHand(f.me(), "QTE-M-EARTH-9");
        int deckBefore = f.me().getDeck().size();

        game.playCard(f.room(), "me", spell, List.of(), false);

        assertThat(f.me().getDeck().size()).as("マナ加速の1枚だけ").isEqualTo(deckBefore - 1);
    }

    // ---- 黄泉の召喚主(QTE-M-DARK-15・区分4)----
    // 旧: 「サブフェイズ時、ミニオンを墓地から召喚してもよい(コストは支払う)。」
    // 新: 「サブフェイズ時ミニオンを墓地から<b>手札にあるかのように</b>召喚してもよい(コストは支払う)」
    //
    // 「手札にあるかのように」がどこまでを指すかが2通り以上に読めるため、実装で決めずに
    // 裁定へ回した(裁定184)。ただしこの入口には Ver.0.4 から潜っていた穴があり、
    // 対象を選ぶ【召喚時】を持つミニオンを墓地から召喚すると NullPointerException で
    // 落ちていた。56 はそれを「理由を返して止める」暫定のガードに変えた。
    //
    // ★<b>Batch 60(裁定278(c)): そのガードは外れた。</b>対象選択の導線を新設したので、
    // 対象を選ぶ【召喚時】も墓地から通る。ここに残すのは「対象を選ばないミニオンは
    // 従来どおり出せる」のほうだけで、外れた側の試験は Batch60Test へ移してある
    // (拒否ではなく成功を測る形に書き換わっている)。

    @Test
    void 黄泉の召喚主は対象を選ばないミニオンなら従来どおり墓地から召喚できる() {
        AutoGameFixture f = newGameWithLeader("QTE-M-DARK-15");
        payMana(f.me(), 8);
        f.me().getTrash().add(PLAIN_GUARD);
        f.state().setPhase(com.example.qte.game.TurnPhase.SUB);

        game.summonFromGrave(f.room(), "me", 0, List.of());

        assertThat(f.fieldIds(f.me())).containsExactly(PLAIN_GUARD);
        assertThat(f.me().getTrash()).isEmpty();
    }

    // ==================================================================
    // ★Batch 57 で見つかった実装の穴(カードの作り直しとは別)
    // ==================================================================

    // 壊し検証(tools/batch56_break_check.py ケース1)が見つけた穴。
    // Kind.WEAPON の要求だけ、Requirement.side() がサーバで検証されていなかった。
    // TargetSpec の Javadoc は「サーバはこれに照らして選択の正当性を検証する」と
    // 謳っており、ウェポンだけがその約束を守っていなかった。

    /**
     * ★★Batch 68: 物差しを<b>ミニオンからスペルへ取り替えた</b>。
     *
     * <p>66 まではゾディアック(ミニオンの【召喚時】)で測っていたが、
     * 裁定282 でミニオンの宣言時対象は無くなり、この道はミニオンからは通らなくなった。
     * ★<b>測っている性質は変わっていない</b> —— 宣言時に届いた {@code Kind.WEAPON} の選択を
     * サーバが {@code Requirement.side()} に照らして弾くか、である。
     * 《サイクロン・リフレッシュ》は {@code Side.SELF} のウェポンを要求する
     * (★裁定309 で 68 が足した3件目の要求)ので、そこへ相手側を送って測る。
     */
    @Test
    void ウェポンの対象は要求された側でなければサーバが弾く() {
        AutoGameFixture f = newGame();
        f.me().setEquippedWeapon(cards.findById(PLAIN_WEAPON));
        f.you().setEquippedWeapon(cards.findById(PLAIN_WEAPON));
        payMana(f.me(), f.card("QTE-M-WIND-22").cost());
        int hand = f.giveHand(f.me(), "QTE-M-WIND-22"); // 自分のウェポン限定(Side.SELF)

        assertThatThrownBy(() -> game.playCard(f.room(), "me", hand,
                List.of(new TargetChoice(List.of(), null, null, null, null), // 1件目: 手札(空)
                        new TargetChoice(null, List.of(), null, null, null), // 2件目: ミニオン(空)
                        weapon("OPPONENT")), false))                          // 3件目: ★相手側
                .as("Side.SELF の要求に OPPONENT を送っても通ってはいけない")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(f.you().getEquippedWeapon()).as("盤面は動いていない").isNotNull();
    }

    @Test
    void ウェポンの対象がSideANYなら両方選べる() {
        AutoGameFixture f = newGame();
        f.me().setEquippedWeapon(cards.findById(PLAIN_WEAPON));
        payMana(f.me(), 2);
        int spell = f.giveHand(f.me(), "QTE-M-FIRE-24"); // 武具昇華の炎(Side.ANY)

        assertThatCode(() -> game.playCard(f.room(), "me", spell, List.of(weapon("SELF")), false))
                .doesNotThrowAnyException();
        assertThat(f.me().getEquippedWeapon()).isNull();
    }
}
