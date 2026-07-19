package com.example.qte.game;

/**
 * ステータス修正の1エントリ(設計判断12: 修正は「期限」と「演算種別」を持つ)。
 *
 * 例: 溢れ出る英知 → ATTACK, ADD, (手札枚数), THIS_TURN
 *     知識の守護者 → ATTACK, SET, (手札枚数), PERMANENT
 *
 * 注意: 「手札枚数」のような動的参照の値はここに固定値として書き込まず、
 * Batch 2のエフェクトシステムが評価時に算出する。Batch 0では枠組みのみ定義する。
 *
 * @param sourceCardId 修正の発生源(デバッグ・UI表示用)
 */
public record StatModifier(
        Stat stat,
        Operation operation,
        int value,
        Duration duration,
        String sourceCardId) {

    public enum Stat {
        ATTACK, COST
    }

    public enum Operation {
        /** 加算(巨神・水刺客型) */
        ADD,
        /** 値の置き換え(知識の守護者型)。ADDより先に適用される */
        SET
    }

    public enum Duration {
        PERMANENT,
        /** このターン中のみ(溢れ出る英知)。ターン終了時に除去される */
        THIS_TURN,
        /** 次の自分ターンまで(氷結の杖の凍結など、ターンをまたぐもの) */
        UNTIL_OWNER_NEXT_TURN
    }
}
