package com.example.qte.deck;

import java.util.HashSet;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.example.qte.effect.CardEffectRegistry;
import com.example.qte.master.CardMaster;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.CardType;
import com.example.qte.master.Civilization;

import lombok.RequiredArgsConstructor;

/**
 * デッキファイルの検証。総合ルール第1章(デッキ構築)をここで一元的に適用する。
 *
 * デッキファイルはユーザーのPCから来る、いくらでも書き換えられるデータである。
 * クライアント側のデッキビルダーも同じ規則で入力を制限するが、それは操作の補助にすぎず、
 * 対戦に使えるかどうかの最終判定は必ずこのクラスが行う
 * (WebSocketの操作検証と同じ考え方: 信用するのは自分の検証だけ)。
 */
@Component
@RequiredArgsConstructor
public class DeckValidator {

    public static final int MAIN_DECK_SIZE = 40;
    public static final int TABOO_DECK_SIZE = 8;
    public static final int MAX_SAME_NAME = 4;

    /** 効果を実装済みの文明。未実装文明のカードは「入れられるのに何も起きない」ため禁止する */
    private static final Set<Civilization> IMPLEMENTED =
            Set.of(Civilization.WATER, Civilization.FIRE, Civilization.DARK);

    /**
     * 同名4枚制限をカードテキストで上書きしているカード(ゾンストライカー)。
     * 「このカードは4枚以上入れられる」という例外はカード側の性質だが、
     * 判定はデッキ検証でしか行えないため、ここにIDを持つ。
     */
    private static final Set<String> UNLIMITED_COPIES = Set.of("QTE-0012");

    private final CardMasterRepository cards;
    private final CardEffectRegistry effects;

    /** 検証してリーダーを返す。違反があればIllegalArgumentExceptionを投げる */
    public CardMaster validate(DeckDefinition deck) {
        if (deck == null) {
            throw new IllegalArgumentException("デッキファイルが読み込めませんでした");
        }
        if (deck.formatVersion() != DeckDefinition.CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException(
                    "対応していないデッキファイル形式です(version=%d)".formatted(deck.formatVersion()));
        }
        CardMaster leader = requireCard(deck.leaderCardId(), "リーダー");
        if (leader.type() != CardType.LEADER) {
            throw new IllegalArgumentException("リーダーカードではありません: " + leader.name());
        }
        requireImplemented(leader);

        validateMain(deck, leader);
        validateTaboo(deck, leader);
        return leader;
    }

    /** メインデッキ: 40枚・リーダーと同一文明・同名4枚まで(1-2, 1-2-1) */
    private void validateMain(DeckDefinition deck, CardMaster leader) {
        if (deck.main() == null || deck.main().isEmpty()) {
            throw new IllegalArgumentException("メインデッキが空です");
        }
        int total = 0;
        Set<String> seen = new HashSet<>();
        for (DeckDefinition.Entry entry : deck.main()) {
            CardMaster card = requireCard(entry.cardId(), "メインデッキ");
            if (!seen.add(entry.cardId())) {
                throw new IllegalArgumentException("メインデッキに同じカードの行が重複しています: " + card.name());
            }
            if (entry.count() <= 0) {
                throw new IllegalArgumentException("枚数が不正です: " + card.name());
            }
            if (card.type() == CardType.LEADER) {
                throw new IllegalArgumentException("リーダーはメインデッキに入れられません: " + card.name());
            }
            if (card.civilization() != leader.civilization()) {
                throw new IllegalArgumentException(
                        "メインデッキはリーダーと同じ文明のカードのみです: " + card.name());
            }
            if (entry.count() > MAX_SAME_NAME && !UNLIMITED_COPIES.contains(entry.cardId())) {
                throw new IllegalArgumentException(
                        "同名カードは%d枚までです: %s".formatted(MAX_SAME_NAME, card.name()));
            }
            requireImplemented(card);
            total += entry.count();
        }
        if (total != MAIN_DECK_SIZE) {
            throw new IllegalArgumentException(
                    "メインデッキは%d枚である必要があります(現在%d枚)".formatted(MAIN_DECK_SIZE, total));
        }
    }

    /** 禁忌デッキ: 8枚・リーダーと異なる文明・同名1枚まで(1-3, 1-3-1) */
    private void validateTaboo(DeckDefinition deck, CardMaster leader) {
        if (deck.taboo() == null) {
            throw new IllegalArgumentException("禁忌デッキがありません");
        }
        if (deck.taboo().size() != TABOO_DECK_SIZE) {
            throw new IllegalArgumentException(
                    "禁忌デッキは%d枚である必要があります(現在%d枚)"
                            .formatted(TABOO_DECK_SIZE, deck.taboo().size()));
        }
        Set<String> seen = new HashSet<>();
        for (String cardId : deck.taboo()) {
            CardMaster card = requireCard(cardId, "禁忌デッキ");
            if (!seen.add(cardId)) {
                throw new IllegalArgumentException("禁忌デッキは同名カード1枚までです: " + card.name());
            }
            if (card.type() == CardType.LEADER) {
                throw new IllegalArgumentException("リーダーは禁忌デッキに入れられません: " + card.name());
            }
            if (card.civilization() == leader.civilization()) {
                throw new IllegalArgumentException(
                        "禁忌デッキはリーダーと異なる文明のカードのみです: " + card.name());
            }
            requireImplemented(card);
        }
    }

    private CardMaster requireCard(String cardId, String where) {
        if (cardId == null || cardId.isBlank()) {
            throw new IllegalArgumentException(where + "のカードIDが空です");
        }
        try {
            return cards.findById(cardId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("%sに存在しないカードがあります: %s".formatted(where, cardId));
        }
    }

    /**
     * 実装済みかの確認。効果が未実装のカードをデッキに入れると
     * 「使えるのに何も起きない」という気づきにくい不具合になるため、入口で弾く。
     */
    private void requireImplemented(CardMaster card) {
        if (!IMPLEMENTED.contains(card.civilization())) {
            throw new IllegalArgumentException(
                    "%s文明はまだ実装されていません: %s"
                            .formatted(card.civilization().getDisplayName(), card.name()));
        }
        if (card.type() == CardType.SPELL && !effects.isSpellImplemented(card.id())) {
            throw new IllegalArgumentException("効果が未実装のスペルです: " + card.name());
        }
    }
}
