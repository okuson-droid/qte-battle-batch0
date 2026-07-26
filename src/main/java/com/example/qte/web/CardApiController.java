package com.example.qte.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.qte.deck.DeckValidator;
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

    /**
     * 効果を実装済みの文明の一覧(Batch 13c)。
     *
     * デッキビルダーは「未実装文明のカードを入れさせない」ためにこの一覧を使う。
     * 以前はビルダー側のJSに文明コードを書き写していたが、文明を実装するたびに
     * 更新を忘れる箇所が増えるため(実際に風と土で漏れた)、サーバの
     * {@link DeckValidator#implementedCivilizations()} を唯一の正として配信する。
     */
    @GetMapping("/api/implemented-civilizations")
    public List<CivilizationDto> implementedCivilizations() {
        return DeckValidator.implementedCivilizations().stream()
                .map(civ -> new CivilizationDto(civ.name(), civ.getDisplayName()))
                .toList();
    }

    /** 文明1件。codeは列挙体の名前(WATER等)、nameは表示名(水等) */
    public record CivilizationDto(String code, String name) {
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
