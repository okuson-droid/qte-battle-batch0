package com.example.qte.room;

import com.example.qte.deck.DeckDefinition;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

/** 部屋に入室したプレイヤー1人分の情報(試合開始前の受付データ)。 */
@Getter
@RequiredArgsConstructor
public class PlayerSlot {

    private final String playerId;
    private final String displayName;
    private final String leaderCardId;

    /**
     * 読み込んだデッキファイル。nullならプリセットデッキ(おまかせ)を使う。
     * 検証はLobbyControllerでの受付時に完了している。
     */
    private final DeckDefinition deck;

    /** デッキ名(表示用)。プリセットなら「おまかせ」 */
    private final String deckName;

    /** WebSocket接続・購読が完了して対戦準備ができたか */
    @Setter
    private boolean ready = false;
}
