package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.manual.ManualCardMaster;
import com.example.qte.manual.ManualCardRepository;
import com.example.qte.manual.ManualCardType;
import com.example.qte.manual.ManualCivilization;

/**
 * Batch 17a のスモークテスト。
 *
 * manual-cards.json は変換スクリプトの生成物であり、人が手で書き換えないため、
 * ここで検証したいのは「JSON の内容が正しいか」ではなく
 * 「生成物とアプリの読み込みが噛み合っているか」である。
 * 枚数の期待値は設計書 batch16-manual-mode-design-v2_2.md 1-2 の表に一致させてある。
 */
@SpringBootTest
class ManualCardMasterLoadTest {

    @Autowired
    ManualCardRepository repository;

    @Test
    void 全カードが読み込まれる() {
        // 234(CSV) + 1(ピュア・エレメント)
        assertThat(repository.getAllCards()).hasSize(235);
    }

    @Test
    void 種別の内訳が設計書の表と一致する() {
        assertThat(countType(ManualCardType.LEADER)).isEqualTo(18);
        assertThat(countType(ManualCardType.MINION)).isEqualTo(119);
        assertThat(countType(ManualCardType.EVOLUTION)).isEqualTo(18);
        assertThat(countType(ManualCardType.WEAPON)).isEqualTo(19);
        // スペルは 60 + ピュア・エレメント
        assertThat(countType(ManualCardType.SPELL)).isEqualTo(61);
    }

    @Test
    void 各文明は39枚で文明なしは1枚() {
        for (ManualCivilization civ : ManualCivilization.values()) {
            int expected = civ == ManualCivilization.NONE ? 1 : 39;
            assertThat(repository.findByCivilization(civ))
                    .as(civ.getDisplayName() + "文明")
                    .hasSize(expected);
        }
    }

    @Test
    void 種別ごとに持つ数値が決まっている() {
        for (ManualCardMaster card : repository.getAllCards()) {
            switch (card.type()) {
                case MINION, EVOLUTION -> {
                    assertThat(card.attack()).as(card.id() + " " + card.name()).isNotNull();
                    assertThat(card.hp()).as(card.id() + " " + card.name()).isNotNull();
                }
                case WEAPON -> {
                    assertThat(card.attack()).as(card.id() + " " + card.name()).isNotNull();
                    assertThat(card.hp()).as(card.id() + " " + card.name()).isNull();
                }
                case SPELL, LEADER -> {
                    assertThat(card.attack()).as(card.id() + " " + card.name()).isNull();
                    assertThat(card.hp()).as(card.id() + " " + card.name()).isNull();
                }
            }
        }
    }

    @Test
    void 表面画像IDは重複しない() {
        // 重複していれば ManualCardRepository のコンストラクタで落ちるため、
        // ここまで到達している時点で一意である。件数だけ確かめておく。
        assertThat(repository.getAllCards().stream().map(ManualCardMaster::imageId).distinct())
                .hasSize(235);
    }

    @Test
    void 台帳と対応するカードが169枚ある() {
        // 既存168枚 + ピュア・エレメント。残る66枚は新カードで null。
        assertThat(repository.getAllCards().stream().filter(ManualCardMaster::isLinkedToLedger))
                .hasSize(169);
    }

    /**
     * ★ID を文字列リテラルで書かないこと。
     * tools/check_all.py の項目3 は Java 中の "QTE-..." を台帳の実在IDと照合するが、
     * 手動モードの ID(QTE-M-...)は台帳に存在しないため、書くと必ず偽陽性になる。
     * 手動モードのカードは文明・種別など性質で引く。
     */
    @Test
    void ピュアエレメントは文明なしのコスト0スペル() {
        var none = repository.findByCivilization(ManualCivilization.NONE);
        assertThat(none).hasSize(1);
        ManualCardMaster card = none.get(0);
        assertThat(card.id()).endsWith("-NONE-01");
        assertThat(card.name()).isEqualTo("ピュア・エレメント");
        assertThat(card.type()).isEqualTo(ManualCardType.SPELL);
        assertThat(card.cost()).isZero();
        assertThat(card.ledgerCardId()).isEqualTo("QTE-X001");
    }

    @Test
    void 裏面画像IDを1つ持つ() {
        assertThat(repository.getBackImageId()).isNotBlank();
    }

    @Test
    void 画像IDでカードを引ける() {
        ManualCardMaster any = repository.getAllCards().get(0);
        assertThat(repository.findByImageId(any.imageId())).contains(any);
        assertThat(repository.findByImageId("存在しない画像ID")).isEmpty();
    }
}
