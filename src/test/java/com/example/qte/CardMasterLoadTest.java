package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.master.CardMaster;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.CardType;
import com.example.qte.master.Civilization;
import com.example.qte.master.Keyword;

/**
 * Batch 0のスモークテスト: カードマスタ台帳が起動時に正しく読み込まれることの確認。
 * Eclipseでは本クラスを右クリック → Run As → JUnit Test で実行できる。
 */
@SpringBootTest
class CardMasterLoadTest {

    @Autowired
    CardMasterRepository repository;

    /**
     * ★Batch 46a で 72 → 169 に修正した。
     *
     * 台帳は Batch 0 の時点で72枚だったが、以後カードが増えても<b>ここが更新されなかった</b>。
     * つまりこのテストは長いあいだ赤のままで、自動モード側の数少ないテストが
     * 「落ちているのが当たり前」の状態になっていた。落ちているテストは番人ではない。
     *
     * 枚数は意図して人が書いている(裁定110 の例外)。ファイルから読んだ値と突き合わせると
     * 「ファイルが途中で切れていても通る」ため、<b>この1つだけは人が決めた数を置く</b>。
     * カードを増やしたらここも直すこと。★46b で 235 になる。
     */
    @Test
    void 台帳の全カードが読み込まれる() {
        assertThat(repository.getAllCards()).hasSize(169);
    }

    @Test
    void 水文明は28枚でリーダーを2枚含む() {
        var water = repository.findByCivilization(Civilization.WATER);
        assertThat(water).hasSize(28);
        assertThat(water.stream().filter(c -> c.type() == CardType.LEADER)).hasSize(2);
    }

    @Test
    void キーワードが正しくマッピングされる() {
        CardMaster card = repository.findById("QTE-0027"); // 知識の守り手
        assertThat(card.keywords()).containsExactlyInAnyOrder(
                Keyword.KNOWLEDGE, Keyword.RESTORATION, Keyword.GUARD);
    }

    @Test
    void 火文明は28枚でリーダーを2枚含む() {
        var fire = repository.findByCivilization(Civilization.FIRE);
        assertThat(fire).hasSize(28);
        assertThat(fire.stream().filter(c -> c.type() == CardType.LEADER)).hasSize(2);
    }

    @Test
    void ピュアエレメントは文明なしのコスト0スペル() {
        CardMaster card = repository.findById("QTE-X001");
        assertThat(card.civilization()).isEqualTo(Civilization.NONE);
        assertThat(card.type()).isEqualTo(CardType.SPELL);
        assertThat(card.cost()).isZero();
    }
}
