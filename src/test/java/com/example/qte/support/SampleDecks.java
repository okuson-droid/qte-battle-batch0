package com.example.qte.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.qte.deck.DeckDefinition;
import com.example.qte.deck.DeckValidator;
import com.example.qte.master.CardMaster;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.CardType;
import com.example.qte.master.Civilization;

/**
 * 試験用に「検証を通るデッキ」を組む(★Batch 66 で新設)。
 *
 * <h2>なぜ新設したのか</h2>
 *
 * 65 まで、この役目は本番側の {@code DeckFactory} の<b>プリセットデッキ</b>が担っていた。
 * 66 でプリセットが退役した(デッキファイルが必須になった)ので、
 * 試験だけのために本番のコードへ 430 行のデータを残すわけにいかない(裁定178)。
 *
 * <h2>★組み方はカードマスタから決める</h2>
 *
 * IDを書き並べない。<b>書き並べた瞬間、それは「本番から消したプリセットの写し」になる</b> ——
 * カードデータが変わったときに、試験だけが古い世界を測り続ける。
 * ここがやるのは次の3つだけである。
 * <ol>
 * <li>その文明の<b>デッキに入れられるカード</b>(リーダー以外)を並べる</li>
 * <li>同名4枚以内で合計40枚になるまで詰める</li>
 * <li>禁忌8枚は<b>別の文明</b>から同名1枚ずつ採る</li>
 * </ol>
 * 規則の出どころは {@link DeckValidator} の定数である ——
 * 40 も 8 も 4 もここには書かれていない(裁定298)。
 */
public final class SampleDecks {

    private SampleDecks() {
    }

    /** その文明の最初のリーダー */
    public static CardMaster firstLeaderOf(CardMasterRepository cards, Civilization civilization) {
        return cards.findByCivilization(civilization).stream()
                .filter(c -> c.type() == CardType.LEADER)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        civilization + " のリーダーが見つからない"));
    }

    /** そのリーダーで組める、検証を通るデッキ1本 */
    public static DeckDefinition deckFor(CardMasterRepository cards, CardMaster leader) {
        List<DeckDefinition.Entry> main = buildMain(cards, leader.civilization());
        List<String> taboo = buildTaboo(cards, leader.civilization());
        return new DeckDefinition("テスト", leader.id(), main, taboo);
    }

    /**
     * メインデッキ。★<b>同名の枚数は 1 から順に増やす</b> ——
     * 全部を上限まで積むと種類が少なくなり、
     * 「デッキに入りうるカードを一通り通す」という試験の目的から外れる。
     */
    private static List<DeckDefinition.Entry> buildMain(
            CardMasterRepository cards, Civilization civilization) {
        List<CardMaster> pool = deckableOf(cards, civilization);
        if (pool.isEmpty()) {
            throw new IllegalStateException(civilization + " にデッキへ入れられるカードが無い");
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        int total = 0;
        // 1周目に1枚ずつ、足りなければ2周目・3周目…と重ねる(上限は MAX_SAME_NAME)
        for (int round = 0; round < DeckValidator.MAX_SAME_NAME
                && total < DeckValidator.MAIN_DECK_SIZE; round++) {
            for (CardMaster card : pool) {
                if (total >= DeckValidator.MAIN_DECK_SIZE) {
                    break;
                }
                counts.merge(card.id(), 1, Integer::sum);
                total++;
            }
        }
        if (total != DeckValidator.MAIN_DECK_SIZE) {
            throw new IllegalStateException(
                    civilization + " で40枚に届かない(種類 " + pool.size() + ")");
        }
        List<DeckDefinition.Entry> main = new ArrayList<>();
        counts.forEach((id, count) -> main.add(new DeckDefinition.Entry(id, count)));
        return main;
    }

    /** 禁忌デッキ。リーダーと異なる文明から同名1枚ずつ */
    private static List<String> buildTaboo(CardMasterRepository cards, Civilization civilization) {
        Civilization other = DeckValidator.implementedCivilizations().stream()
                .filter(c -> c != civilization)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("禁忌に使える文明が無い"));
        List<String> taboo = deckableOf(cards, other).stream()
                .limit(DeckValidator.TABOO_DECK_SIZE)
                .map(CardMaster::id)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (taboo.size() != DeckValidator.TABOO_DECK_SIZE) {
            throw new IllegalStateException(other + " で禁忌8枚に届かない");
        }
        return taboo;
    }

    /** その文明の、デッキに入れられるカード(リーダー以外) */
    public static List<CardMaster> deckableOf(CardMasterRepository cards, Civilization civilization) {
        return cards.findByCivilization(civilization).stream()
                .filter(c -> c.type() != CardType.LEADER)
                .toList();
    }
}
