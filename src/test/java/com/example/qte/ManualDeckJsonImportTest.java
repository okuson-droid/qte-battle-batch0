package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import com.example.qte.manual.ManualCardMaster;
import com.example.qte.manual.ManualCardRepository;
import com.example.qte.manual.ManualCardType;
import com.example.qte.manual.ManualCivilization;
import com.example.qte.manual.ManualDeckImport;
import com.example.qte.manual.ManualDeckImporter;

/**
 * Batch 24 のテスト: JSON デッキの読み込みと、zip / JSON の自動判別。
 *
 * <h2>★このテストが守っているもの</h2>
 * デッキの標準形式が JSON(カードID突合)になった。守りたい性質は4つである。
 * (1) カードIDで正しく解決すること。(2) 解決できないIDが全体を壊さないこと
 * (灰色タイル + 警告で通る。zip 経路と同じ寛容さ)。(3) 構築ルール違反が
 * 拒否ではなく警告になること(検証は zip 経路と同じ validate を通っている)。
 * (4) 受け口が1つのまま、中身の先頭バイトで形式が判別されること。
 *
 * ★カードIDを文字列リテラルで書かない(batch17a-design-notes 3-2)。
 * すべて {@link ManualCardRepository} から動的に取得して組み立てる。
 */
@SpringBootTest
class ManualDeckJsonImportTest {

    @Autowired
    ManualDeckImporter importer;

    @Autowired
    ManualCardRepository cards;

    // ---- 組み立てヘルパー ----

    private ManualCardMaster leaderOf(ManualCivilization civ) {
        return cards.getAllCards().stream()
                .filter(c -> c.civilization() == civ && c.type() == ManualCardType.LEADER)
                .findFirst().orElseThrow();
    }

    private List<ManualCardMaster> nonLeadersOf(ManualCivilization civ, int count) {
        return cards.getAllCards().stream()
                .filter(c -> c.civilization() == civ && c.type() != ManualCardType.LEADER)
                .limit(count).toList();
    }

    /** 規定どおり(リーダー1 + メイン40 + 禁忌8)の v2 JSON を実データから組み立てる */
    private String validDeckJson() {
        ManualCivilization mainCiv = ManualCivilization.WATER;
        ManualCardMaster leader = leaderOf(mainCiv);
        List<ManualCardMaster> mains = nonLeadersOf(mainCiv, 10);
        List<ManualCardMaster> taboos = nonLeadersOf(ManualCivilization.FIRE, 8);
        StringBuilder main = new StringBuilder();
        for (ManualCardMaster c : mains) {
            if (!main.isEmpty()) {
                main.append(',');
            }
            main.append("{\"cardId\":\"%s\",\"qty\":4}".formatted(c.id()));
        }
        StringBuilder taboo = new StringBuilder();
        for (ManualCardMaster c : taboos) {
            if (!taboo.isEmpty()) {
                taboo.append(',');
            }
            taboo.append("{\"cardId\":\"%s\",\"name\":\"%s\"}".formatted(c.id(), c.name()));
        }
        return """
                {"format":"taboo-elemental-deck","version":2,"deckName":"テスト用",
                 "leader":{"cardId":"%s","name":"%s"},
                 "main":[%s],"taboo":[%s]}
                """.formatted(leader.id(), leader.name(), main, taboo);
    }

    private ManualDeckImport importJson(String json) {
        return importer.importJson(json.getBytes(StandardCharsets.UTF_8));
    }

    // ---- (1) 解決 ----

    @Test
    void 規定どおりのJSONデッキは警告なしで解決する() {
        ManualDeckImport imported = importJson(validDeckJson());
        assertThat(imported.deckName()).isEqualTo("テスト用");
        assertThat(imported.leader()).isNotNull();
        assertThat(imported.leader().master().type()).isEqualTo(ManualCardType.LEADER);
        assertThat(imported.main()).hasSize(40);
        assertThat(imported.taboo()).hasSize(8);
        assertThat(imported.unresolvedCount()).isZero();
        assertThat(imported.warnings()).isEmpty();
    }

    @Test
    void qtyは同じカードをその枚数に展開する() {
        ManualCardMaster leader = leaderOf(ManualCivilization.WATER);
        ManualCardMaster one = nonLeadersOf(ManualCivilization.WATER, 1).get(0);
        ManualDeckImport imported = importJson("""
                {"format":"taboo-elemental-deck","version":2,
                 "leader":{"cardId":"%s"},
                 "main":[{"cardId":"%s","qty":3}],"taboo":[]}
                """.formatted(leader.id(), one.id()));
        assertThat(imported.main()).hasSize(3);
        assertThat(imported.main()).allSatisfy(
                e -> assertThat(e.master().id()).isEqualTo(one.id()));
    }

    @Test
    void 過渡期の揺れも読める_leaderId文字列とtabooのID文字列() {
        ManualCardMaster leader = leaderOf(ManualCivilization.FIRE);
        ManualCardMaster taboo = nonLeadersOf(ManualCivilization.WATER, 1).get(0);
        ManualDeckImport imported = importJson("""
                {"format":"taboo-elemental-deck","version":2,
                 "leaderId":"%s","main":[],"taboo":["%s"]}
                """.formatted(leader.id(), taboo.id()));
        assertThat(imported.leader().master().id()).isEqualTo(leader.id());
        assertThat(imported.taboo()).hasSize(1);
        assertThat(imported.taboo().get(0).master().id()).isEqualTo(taboo.id());
    }

    // ---- (2) 解決できないIDへの寛容さ ----

    @Test
    void 不明なカードIDは灰色タイルとして警告付きで通る() {
        ManualCardMaster leader = leaderOf(ManualCivilization.WATER);
        ManualDeckImport imported = importJson("""
                {"format":"taboo-elemental-deck","version":2,
                 "leader":{"cardId":"%s"},
                 "main":[{"cardId":"NO-SUCH-CARD","name":"謎のカード","qty":1}],"taboo":[]}
                """.formatted(leader.id()));
        assertThat(imported.unresolvedCount()).isEqualTo(1);
        assertThat(imported.main().get(0).displayName()).isEqualTo("謎のカード");
        assertThat(imported.warnings()).anyMatch(w -> w.contains("謎のカード"));
    }

    // ---- (3) 構築ルール違反は警告に留まる ----

    @Test
    void 規定枚数違反と文明違反は警告に留まり読み込みは成立する() {
        ManualCardMaster leader = leaderOf(ManualCivilization.WATER);
        ManualCardMaster wrongCiv = nonLeadersOf(ManualCivilization.FIRE, 1).get(0);
        ManualDeckImport imported = importJson("""
                {"format":"taboo-elemental-deck","version":2,
                 "leader":{"cardId":"%s"},
                 "main":[{"cardId":"%s","qty":5}],"taboo":[]}
                """.formatted(leader.id(), wrongCiv.id()));
        assertThat(imported.main()).hasSize(5);
        assertThat(imported.warnings())
                .anyMatch(w -> w.contains("メインデッキが 40 枚ではない"))
                .anyMatch(w -> w.contains("禁忌デッキが 8 枚ではない"))
                .anyMatch(w -> w.contains("同名上限"))
                .anyMatch(w -> w.contains("異なる文明"));
    }

    // ---- (4) 形式の自動判別 ----

    @Test
    void importAutoはJSONを先頭バイトで判別する() {
        ManualDeckImport imported = importer.importAuto(
                validDeckJson().getBytes(StandardCharsets.UTF_8));
        assertThat(imported.main()).hasSize(40);
        assertThat(imported.unresolvedCount()).isZero();
    }

    @Test
    void importAutoはzipをPKマジックで判別する() throws IOException {
        byte[] zip;
        try (InputStream in = new ClassPathResource("decks/sample-deck.zip").getInputStream()) {
            zip = in.readAllBytes();
        }
        ManualDeckImport imported = importer.importAuto(zip);
        assertThat(imported.totalCards()).isEqualTo(49);
        assertThat(imported.unresolvedCount()).isZero();
    }

    @Test
    void JSONでもzipでもないものは拒否する() {
        assertThatThrownBy(() -> importer.importAuto("こんにちは".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("読めませんでした");
        assertThatThrownBy(() -> importer.importAuto(
                "{\"format\":\"別物\"}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("形式が違います");
    }
}
