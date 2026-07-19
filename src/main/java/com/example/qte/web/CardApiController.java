package com.example.qte.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.qte.master.CardMaster;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.Keyword;

import lombok.RequiredArgsConstructor;

/**
 * デッキビルダー用のカードマスタ提供API。
 * カードデータの正はサーバ上のJSON台帳ひとつであり、
 * ビルダーは起動時にここから読み込む(データを二重に持たない)。
 */
@RestController
@RequiredArgsConstructor
public class CardApiController {

    private final CardMasterRepository cards;

    @GetMapping("/api/cards")
    public List<CardDto> allCards() {
        return cards.getAllCards().stream().map(CardDto::from).toList();
    }

    public record CardDto(
            String id,
            String name,
            String type,
            String civilization,
            String civilizationName,
            Integer cost,
            Integer attack,
            Integer hp,
            List<String> keywords,
            String text) {

        static CardDto from(CardMaster c) {
            return new CardDto(c.id(), c.name(), c.type().name(),
                    c.civilization().name(), c.civilization().getDisplayName(),
                    c.cost(), c.attack(), c.hp(),
                    c.keywords().stream().map(Keyword::getDisplayName).toList(), c.text());
        }
    }
}
