package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.master.CardMaster;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.CardType;
import com.example.qte.master.Civilization;
import com.example.qte.master.Keyword;

/**
 * Batch 0のスモークテスト: カードマスタが起動時に正しく読み込まれることの確認。
 * Eclipseでは本クラスを右クリック → Run As → JUnit Test で実行できる。
 *
 * <p>★Batch 46b で、読む先が台帳({@code qte-cards.json}・169枚)から
 * Ver1.1({@code manual-cards.json}・235枚)へ移った。このクラスは
 * <b>移行が実際に効いていること</b>を測る入口である。
 */
@SpringBootTest
class CardMasterLoadTest {

    @Autowired
    CardMasterRepository repository;

    /**
     * ★Batch 46a で 72 → 169、★Batch 46b で 169 → 235 にした。
     *
     * 台帳は Batch 0 の時点で72枚だったが、以後カードが増えても<b>ここが更新されなかった</b>。
     * つまりこのテストは長いあいだ赤のままで、自動モード側の数少ないテストが
     * 「落ちているのが当たり前」の状態になっていた。落ちているテストは番人ではない(裁定161)。
     *
     * 枚数は意図して人が書いている(裁定110 の例外)。ファイルから読んだ値と突き合わせると
     * 「ファイルが途中で切れていても通る」ため、<b>この1つだけは人が決めた数を置く</b>。
     * カードを増やしたらここも直すこと。
     */
    @Test
    void 全カードが読み込まれる() {
        assertThat(repository.getAllCards()).hasSize(235);
    }

    @Test
    void 水文明は39枚でリーダーを3枚含む() {
        var water = repository.findByCivilization(Civilization.WATER);
        assertThat(water).hasSize(39);
        assertThat(water.stream().filter(c -> c.type() == CardType.LEADER)).hasSize(3);
    }

    @Test
    void 火文明は39枚でリーダーを3枚含む() {
        var fire = repository.findByCivilization(Civilization.FIRE);
        assertThat(fire).hasSize(39);
        assertThat(fire.stream().filter(c -> c.type() == CardType.LEADER)).hasSize(3);
    }

    /**
     * ★46b: キーワードは JSON のフィールドではなく<b>テキストから読む</b>
     * ({@code CardTextKeywords})。Ver1.1 の定義に {@code keywords} は無い。
     *
     * 知識の守り手は、この移行で<b>挙動が変わる9枚のうちの1枚</b>である。
     * 台帳では 知識・還元・守護 の3つだったが、Ver1.1 の本文は
     * {@code 【知識】【還元】【守護】【突進】} であり、<b>突進が増える</b>。
     * 全件の照合は {@code CardTextKeywordsTest} が行う。ここは代表1枚の目印である。
     */
    @Test
    void キーワードはテキストから作られる() {
        CardMaster card = repository.findById("QTE-M-WATER-17"); // 知識の守り手
        assertThat(card.keywords()).containsExactlyInAnyOrder(
                Keyword.KNOWLEDGE, Keyword.RESTORATION, Keyword.GUARD, Keyword.RUSH);
    }

    /** ★46b: 進化ミニオン18枚がマスタに載る(デッキに入れられるかは DeckValidator の話) */
    @Test
    void 進化ミニオンは18枚ある() {
        assertThat(repository.getAllCards().stream().filter(c -> c.type() == CardType.EVOLUTION))
                .hasSize(18);
    }

    /**
     * ★46b: リーダーは18枚で、コストが {@code null} ではなく {@code 0} になった。
     * 台帳のリーダーは {@code cost=null} だった。現在リーダーのコストを見ている場所は無いが、
     * できたときにこの差が効いてくるので測っておく。
     */
    @Test
    void リーダーは18枚でコストが0である() {
        var leaders = repository.getAllCards().stream()
                .filter(c -> c.type() == CardType.LEADER)
                .toList();
        assertThat(leaders).hasSize(18);
        assertThat(leaders).allSatisfy(c -> assertThat(c.cost()).isZero());
    }

    @Test
    void ピュアエレメントは文明なしのコスト0スペル() {
        CardMaster card = repository.findById("QTE-M-NONE-01");
        assertThat(card.civilization()).isEqualTo(Civilization.NONE);
        assertThat(card.type()).isEqualTo(CardType.SPELL);
        assertThat(card.cost()).isZero();
    }

    /**
     * ★46b: 台帳IDでは引けない。
     *
     * 「読むファイルを変えた」ことの一番はっきりした証拠である。もし台帳IDが引けるなら、
     * どこかに古い正が残っている。★裁定116 の形(判定が落ちることを確かめる)でもある
     * —— 差し替えを戻すと、このテストだけは必ず落ちる。
     */
    @Test
    void 退役した台帳IDでは引けない() {
        assertThatThrownBy(() -> repository.findById("QTE-0027"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.findById("QTE-X001"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
