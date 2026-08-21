package com.example.qte.master;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.ObjectMapper;

import lombok.Getter;

/**
 * カードマスタの読み込みと検索。
 * DBではなく classpath 上の JSON を起動時に一度だけ読み込む。
 * JPAの Repository と役割は同じ「データの出口」だが、対象が不変マスタなので
 * 実体は単なる Map の表引きである(batch0-design-notes.md 3章参照)。
 *
 * <h2>★Batch 46b: 読むファイルが変わった</h2>
 *
 * 台帳 {@code qte-cards.json}(169枚・Ver0.4)から
 * <b>{@code manual-cards.json}(235枚・Ver1.1)</b>へ差し替えた(裁定D1 の案B)。
 * これで手動モード・デッキメーカー・通常モードの<b>カードの正が1つになった</b>
 * (設計判断28)。デッキメーカーで組んだ JSON がそのまま通常モードで使える。
 *
 * <p><b>keywords はファイルに無い。</b> Ver1.1 の定義は {@code keywords} フィールドを持たない
 * (Batch 24 hotfix2 で廃止した。<b>テキストが正</b>である)。そのため
 * {@link CardTextKeywords#extract(String)} でテキストから読む。抽出規則は Batch 46a で
 * 台帳169枚と突き合わせて凍結してあり、規則を触るときは {@code CardTextKeywordsTest} を先に見ること
 * (裁定158)。★ここで読んだ結果をどこかの表に焼き付けないこと —— 焼き付けた瞬間に
 * 「テキスト」と「表」という2つの正ができる。
 *
 * <p><b>{@code ledgerCardId} は {@link CardMaster} に持たせない。</b>
 * 退役するIDを新しい正に持ち込むと、いつまでも2つのIDが並走する。
 * 由来との対応が要るのは移行作業と、それを検める試験だけであり、
 * どちらもファイルを直に読めばよい({@code tools/check_legacy_ids.py} / {@code CardIdMappingTest})。
 *
 * <h2>★Batch 60: 台帳ファイルを削除した</h2>
 *
 * 46b の時点では {@code qte-cards.json} を<b>読まなくなっただけ</b>で、ファイルは残していた ——
 * 1バッチ分の戻り道と、抽出規則の番人({@code CardTextKeywordsTest} の169枚照合)のためである。
 * 60 で区分5(作り直し)が終わり、戻り道は要らなくなった。
 * 番人のほうは、台帳から<b>人手が付けたキーワード169件だけ</b>を抜き出して
 * {@code src/test/resources/keyword-baseline.json} に凍結してある
 * ({@code com.example.qte.support.KeywordBaseline})。
 * ★台帳を丸ごと残すと、次に読む人はそれを<b>もう1つのカード台帳</b>として読む。
 */
@Repository
public class CardMasterRepository {

    /** ★Batch 46b で台帳({@code cards/qte-cards.json}・60 で削除)から差し替えた */
    private static final String RESOURCE = "cards/manual-cards.json";

    private final Map<String, CardMaster> cardsById;

    @Getter
    private final List<CardMaster> allCards;

    public CardMasterRepository(ObjectMapper objectMapper) {
        // コンストラクタで読み込む = このBeanが存在する時点でマスタは必ずロード済み。
        // JSONが壊れていれば起動自体が失敗する(実行時まで問題を持ち越さない)。
        CardFile file = load(objectMapper);
        this.allCards = file.cards().stream().map(CardJson::toMaster).toList();
        this.cardsById = allCards.stream()
                .collect(Collectors.toUnmodifiableMap(CardMaster::id, Function.identity()));
    }

    private CardFile load(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return objectMapper.readValue(in, CardFile.class);
        } catch (IOException e) {
            throw new UncheckedIOException("カードマスタの読み込みに失敗しました", e);
        }
    }

    public CardMaster findById(String cardId) {
        CardMaster card = cardsById.get(cardId);
        if (card == null) {
            throw new IllegalArgumentException("存在しないカードID: " + cardId);
        }
        return card;
    }

    public List<CardMaster> findByCivilization(Civilization civilization) {
        return allCards.stream()
                .filter(c -> c.civilization() == civilization)
                .toList();
    }

    // ---- 以下、カード定義JSONの形をそのまま受けるためのDTO ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CardFile(List<CardJson> cards) {
    }

    /**
     * カード定義1件。
     *
     * ★{@code imageId} / {@code ledgerCardId} は
     * {@code ignoreUnknown} で読み飛ばす。エンジンが使わない項目を record に足すと、
     * 「使えるから使ってしまう」経路が開く。画像は画面側が
     * {@code /manual/api/card-library} から引く(裁定144 と同じ形)。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CardJson(
            String id,
            String name,
            String type,
            String civilization,
            Integer cost,
            Integer attack,
            Integer hp,
            String text) {

        CardMaster toMaster() {
            return new CardMaster(id, name, CardType.valueOf(type),
                    Civilization.valueOf(civilization), cost, attack, hp,
                    CardTextKeywords.extract(text), text);
        }
    }
}
