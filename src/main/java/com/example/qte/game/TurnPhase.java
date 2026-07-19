package com.example.qte.game;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** ターンの7フェイズ(総合ルール第6章)。この順序で進行する。 */
@Getter
@RequiredArgsConstructor
public enum TurnPhase {
    DRAW("ドロー"),
    UNTAP("アンタップ"),
    MANA_CHARGE("マナチャージ"),
    MAIN("メイン"),
    BATTLE("バトル"),
    SUB("サブ"),
    END("ターンエンド");

    private final String displayName;

    /** 次のフェイズ。ENDの次は相手ターンのDRAW(ターン交代はGameState側で行う)。 */
    public TurnPhase next() {
        TurnPhase[] phases = values();
        return phases[(ordinal() + 1) % phases.length];
    }
}
