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

    @Test
    void ゾディアックは召喚時に相手のウェポンを破壊する() {
        AutoGameFixture f = newGame();
        f.you().setEquippedWeapon(cards.findById(PLAIN_WEAPON));
        payMana(f.me(), 9);
        int idx = f.giveHand(f.me(), "QTE-M-LIGHT-8");

        game.playCard(f.room(), "me", idx, List.of(weapon("OPPONENT")), false);

        assertThat(f.you().getEquippedWeapon()).as("相手のウェポンが破壊された").isNull();
        assertThat(f.fieldIds(f.me())).contains("QTE-M-LIGHT-8");
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
    // 旧: 「次の自分のターンに唱えるスペルすべてのコスト-1」(文明を問わない)
    // 新: 「光のスペル」限定になった
    // ★確認の結果、実装は既にcard.civilization()==LIGHTの条件で新本文どおりに一致している

    @Test
    void 詠唱の宝珠は破壊後光のスペルのコストだけ下げる() {
        AutoGameFixture f = newGame();
        f.me().getPersistentAuras().add(PersistentAura.untilNextSpell("QTE-M-LIGHT-28"));

        assertThat(stats.effectiveCost(f.state(), f.me(), f.card("QTE-M-LIGHT-9")))
                .as("光のスペルはコスト-1(2→1)").isEqualTo(1);
        assertThat(stats.effectiveCost(f.state(), f.me(), f.card(MAGMA)))
                .as("光文明でないスペルはコストが下がらない").isEqualTo(1);
    }
}
