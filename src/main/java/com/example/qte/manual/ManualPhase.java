package com.example.qte.manual;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 手動モードのフェイズ表示(総合ルール 2-6 の7フェイズ)。
 *
 * ★通常モードの {@link com.example.qte.game.TurnPhase} と値は同じだが、別に定義する。
 * Batch 17a が {@code ManualCardType} / {@code ManualCivilization} を分けたのと同じ理由で、
 * 手動モードはフェイズを強制しない(設計書 5-1「フェイズ強制は切る」)。
 * 通常モードの TurnPhase は「今このフェイズだから、この操作は拒否する」という判定の入力であり、
 * 同じ型を共有すると、片方が判定に使う値をもう片方が単なる表示ラベルとして書き換えることになる。
 *
 * ここでのフェイズは人間が進める表示でしかなく、順序も自由に前後できる(設計書 5-3 の10)。
 */
@Getter
@RequiredArgsConstructor
public enum ManualPhase {

    DRAW("ドロー"),
    UNTAP("アンタップ"),
    MANA_CHARGE("マナチャージ"),
    MAIN("メイン"),
    BATTLE("バトル"),
    SUB("サブ"),
    END("ターンエンド");

    private final String displayName;

    /** 1つ進める。ENDの次はDRAWに戻る(ターン番号の増加は行わない)。 */
    public ManualPhase forward() {
        ManualPhase[] phases = ManualPhase.values();
        return phases[(ordinal() + 1) % phases.length];
    }

    /** 1つ戻す。DRAWの前はENDになる。 */
    public ManualPhase backward() {
        ManualPhase[] phases = ManualPhase.values();
        return phases[(ordinal() + phases.length - 1) % phases.length];
    }
}
