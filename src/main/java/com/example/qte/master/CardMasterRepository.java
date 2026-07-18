package com.example.qte.master;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.ObjectMapper;

import lombok.Getter;

/**
 * カードマスタの読み込みと検索。
 * DBではなく classpath 上の JSON(台帳 qte-cards.json)を起動時に一度だけ読み込む。
 * JPAの Repository と役割は同じ「データの出口」だが、対象が不変マスタなので
 * 実体は単なる Map の表引きである(batch0-design-notes.md 3章参照)。
 */
@Repository
public class CardMasterRepository {

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
        try (InputStream in = new ClassPathResource("cards/qte-cards.json").getInputStream()) {
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

    // ---- 以下、台帳JSONの形をそのまま受けるためのDTO ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CardFile(List<CardJson> cards) {
    }

    /** 台帳のカード1件。confirmationPending 等の実装に不要な項目は ignoreUnknown で無視する。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CardJson(
            String id,
            String name,
            String type,
            String civilization,
            Integer cost,
            Integer attack,
            Integer hp,
            List<String> keywords,
            String text) {

        CardMaster toMaster() {
            Set<Keyword> keywordSet = keywords.stream()
                    .map(Keyword::fromDisplayName)
                    .collect(Collectors.toUnmodifiableSet());
            return new CardMaster(id, name, CardType.valueOf(type),
                    Civilization.valueOf(civilization), cost, attack, hp, keywordSet, text);
        }
    }
}
