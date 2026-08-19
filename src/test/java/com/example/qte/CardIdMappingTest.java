package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.CardType;
import com.example.qte.master.Civilization;
import com.example.qte.support.LedgerCards;
import com.example.qte.support.Ver11Cards;

import tools.jackson.databind.ObjectMapper;

/**
 * Ver1.1 移行(裁定D1)の<b>前提</b>を機械で確かめる(Batch 46a / ★46b で読む先を移した)。
 *
 * 46b で、通常モードのカードマスタを {@code qte-cards.json} から
 * {@code manual-cards.json} へ差し替え、Java にベタ書きされた台帳ID169種を
 * {@code QTE-M-<文明>-<番号>} へ<b>機械的に書き換えた</b>。
 * その書き換えが成り立つのは、次がすべて真であるときだけである。
 *
 * <ul>
 * <li>台帳のカードとVer1.1のカードが1対1で対応する(どちらにも余りが無い)</li>
 * <li>対応の出どころが {@code ledgerCardId} 1つである</li>
 * <li>Ver1.1 側の型・文明が、エンジンの列挙体で表せる</li>
 * </ul>
 *
 * ★このテストは移行<b>前</b>に置いた。移行してから「前提が崩れていた」と気づくと、
 * 何がどこまで正しく変換されたのか誰にも分からなくなるからである。
 * 移行後も残すのは、カード定義ファイルを差し替えたときに同じ前提が崩れるのを検出するためである。
 * 同じ検証は {@code tools/build_id_map.py --check} でも行える(あちらは Maven を要さない)。
 *
 * <p>★46b の変更点: 台帳側は本体のリポジトリからは読めなくなったので
 * {@link LedgerCards} 経由にした。{@code qte-cards.json} を削除するバッチで、
 * 台帳を見る2件({@code 台帳とVer11のカードが1対1で対応する} と
 * {@code 台帳に無い新カードは66枚である})も一緒に畳むこと。
 */
@SpringBootTest
class CardIdMappingTest {

    /** ★46b: Ver1.1(235枚)を読むリポジトリ。移行が実際に効いていることの確認に使う */
    @Autowired
    CardMasterRepository cards;

    @Autowired
    ObjectMapper objectMapper;

    private List<Ver11Cards.Card> ver11;
    private Set<String> ledgerIds;

    private List<Ver11Cards.Card> ver11Cards() {
        if (ver11 == null) {
            ver11 = Ver11Cards.load(objectMapper);
        }
        return ver11;
    }

    /** 退役した台帳のカードID(169件)。テスト専用の読み口から直に読む */
    private Set<String> ledgerIds() {
        if (ledgerIds == null) {
            ledgerIds = LedgerCards.load(objectMapper).stream()
                    .map(LedgerCards.Card::id)
                    .collect(Collectors.toCollection(TreeSet::new));
        }
        return ledgerIds;
    }

    @Test
    void Ver11は235枚で種別の内訳が仕様どおりである() {
        // 人が決めた数を置く(裁定110 の例外。ファイルから読んだ値と比べると
        // 「ファイルが途中で切れていても通る」ため)。
        assertThat(ver11Cards()).hasSize(235);
        Map<String, Long> byType = ver11Cards().stream()
                .collect(Collectors.groupingBy(Ver11Cards.Card::type, Collectors.counting()));
        assertThat(byType).containsOnly(
                Map.entry("LEADER", 18L),
                Map.entry("MINION", 119L),
                Map.entry("EVOLUTION", 18L),
                Map.entry("SPELL", 61L),
                Map.entry("WEAPON", 19L));
    }

    @Test
    void Ver11は6文明が均等で文明なしが1枚である() {
        Map<String, Long> byCiv = ver11Cards().stream()
                .collect(Collectors.groupingBy(Ver11Cards.Card::civilization, Collectors.counting()));
        assertThat(byCiv).containsOnly(
                Map.entry("FIRE", 39L), Map.entry("WATER", 39L), Map.entry("WIND", 39L),
                Map.entry("LIGHT", 39L), Map.entry("DARK", 39L), Map.entry("EARTH", 39L),
                Map.entry("NONE", 1L));
    }

    @Test
    void 台帳とVer11のカードが1対1で対応する() {
        Map<String, String> byLedgerId = new LinkedHashMap<>();
        Set<String> duplicated = new TreeSet<>();
        Set<String> notInLedger = new TreeSet<>();
        for (Ver11Cards.Card card : ver11Cards()) {
            String ledgerId = card.ledgerCardId();
            if (ledgerId == null) {
                continue;
            }
            if (byLedgerId.put(ledgerId, card.id()) != null) {
                duplicated.add(ledgerId);
            }
            if (!ledgerIds().contains(ledgerId)) {
                notInLedger.add(ledgerId + " (" + card.id() + ")");
            }
        }
        assertThat(duplicated).as("2枚の Ver1.1 カードが同じ台帳カードを指している").isEmpty();
        assertThat(notInLedger).as("台帳に存在しない ledgerCardId").isEmpty();

        Set<String> unreferenced = ledgerIds().stream()
                .filter(id -> !byLedgerId.containsKey(id))
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(unreferenced).as("どの Ver1.1 カードからも指されていない台帳カード").isEmpty();
        assertThat(byLedgerId).hasSize(ledgerIds().size());
    }

    @Test
    void 台帳に無い新カードは66枚である() {
        List<Ver11Cards.Card> fresh = ver11Cards().stream()
                .filter(c -> c.ledgerCardId() == null)
                .toList();
        assertThat(fresh).hasSize(66);
        // ★進化18枚はすべて新カードである(台帳に進化という概念が無い)。
        assertThat(fresh.stream().filter(c -> "EVOLUTION".equals(c.type()))).hasSize(18);
    }

    @Test
    void 種別はすべてエンジンの列挙体で表せる() {
        // ★46a では「表せないのは EVOLUTION だけ」という形で、46b で列挙体に足す根拠を測っていた。
        // 46b で CardType に EVOLUTION を足したので、いまは<b>1つも残っていない</b>ことを測る。
        // ここが増えたら、カードデータが列挙体より先に進んでいる合図である。
        Set<String> known = Arrays.stream(CardType.values()).map(Enum::name)
                .collect(Collectors.toSet());
        Set<String> unknown = ver11Cards().stream().map(Ver11Cards.Card::type)
                .filter(t -> !known.contains(t))
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(unknown).isEmpty();
        assertThat(known).contains("EVOLUTION");
    }

    @Test
    void 文明はすべてエンジンの列挙体で表せる() {
        Set<String> known = Arrays.stream(Civilization.values()).map(Enum::name)
                .collect(Collectors.toSet());
        Set<String> unknown = ver11Cards().stream().map(Ver11Cards.Card::civilization)
                .filter(c -> !known.contains(c))
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(unknown).isEmpty();
    }

    @Test
    void 画像IDは全枚数そろっていて重複しない() {
        // ★画像IDは手動モードのデッキ取り込みの突合キーであり(設計書 1-3)、
        // 移行後は通常モードの面もこのIDで画像を出す。欠けと重複はどちらも致命傷になる。
        Set<String> imageIds = new TreeSet<>();
        Set<String> duplicated = new TreeSet<>();
        for (Ver11Cards.Card card : ver11Cards()) {
            assertThat(card.imageId()).as("画像IDが無い: " + card.id()).isNotBlank();
            if (!imageIds.add(card.imageId())) {
                duplicated.add(card.id());
            }
        }
        assertThat(duplicated).as("画像IDが重複しているカード").isEmpty();
        assertThat(imageIds).hasSize(235);
    }

    @Test
    void カードIDは重複せず命名規則に従う() {
        Set<String> ids = new TreeSet<>();
        Set<String> duplicated = new TreeSet<>();
        Set<String> malformed = new TreeSet<>();
        for (Ver11Cards.Card card : ver11Cards()) {
            if (!ids.add(card.id())) {
                duplicated.add(card.id());
            }
            // QTE-M-<文明>-<番号>。46b の機械変換はこの形を前提にする。
            if (!card.id().matches("QTE-M-[A-Z]+-\\d+")) {
                malformed.add(card.id());
            }
        }
        assertThat(duplicated).as("カードIDの重複").isEmpty();
        assertThat(malformed).as("命名規則から外れたカードID").isEmpty();
        assertThat(ids).hasSize(235);
    }

    @Test
    void リーダーは18枚でコストを持たない扱いである() {
        // ★台帳のリーダーは cost=null、Ver1.1 のリーダーは cost=0 である。
        // 46b でマスタを差し替えると CardMaster.cost() が null から 0 に変わるので、
        // 「リーダーのコストを見ている場所が無い」ことをここに書き留めておく
        // (見ている場所ができたら、この差が効いてくる)。
        List<Ver11Cards.Card> leaders = ver11Cards().stream()
                .filter(c -> "LEADER".equals(c.type()))
                .toList();
        assertThat(leaders).hasSize(18);
        assertThat(leaders).allSatisfy(c -> {
            assertThat(c.cost()).isZero();
            assertThat(c.attack()).isNull();
            assertThat(c.hp()).isNull();
        });
    }
}
