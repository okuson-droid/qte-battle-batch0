package com.example.qte.room;

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

    /** WebSocket接続・購読が完了して対戦準備ができたか */
    @Setter
    private boolean ready = false;
}
