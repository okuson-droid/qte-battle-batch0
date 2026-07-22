package com.example.qte.effect;

/**
 * 中断した効果の「再開先」。
 *
 * <b>なぜ列挙体なのか。</b>
 * Java には効果の途中で処理を中断し、後から同じ場所へ戻る仕組み(継続)がない。
 * したがって「どこから再開するか」を値として保持するしかない。
 * 継続を {@code Consumer<EffectContext>} としてゲーム状態に持たせる案もあったが、
 * {@link com.example.qte.game.GameState} はビュー生成のたびに走査される可変データであり、
 * シリアライズできない関数を紛れ込ませると、将来の
 * 「状態のスナップショットを取る」「リプレイを保存する」といった拡張が塞がる。
 *
 * 列挙体で持てば状態は文字列と数値だけで表現でき、
 * {@link TriggerType} や {@link PersistentAura.Expiry} と同じ流儀になる。
 * 代償として、割り込みを伴うカードが増えるたびにこの列挙体が1つ増え、
 * {@code CardEffectRegistry.resolveChoice} の分岐が1つ増える。
 */
public enum ResumePoint {

    /** 降臨の伝道師(QTE-0112): 公開した束から場に出す【守護】ミニオンを選ぶ */
    MISSIONARY_SUMMON,

    /** 選択の追い風(QTE-0126): 引いた後の任意ディスカード。Batch 12b で解決処理を実装する */
    TAILWIND_DISCARD,

    /** 風のマナ変換(QTE-0127): 手札から裏向きマナへ置く1枚を選ぶ。Batch 12b */
    MANA_CONVERT_PUT,

    /** 回帰の風穴(QTE-0116): 再詠唱時の2体目を選ぶ。Batch 12b */
    WINDHOLE_SECOND,

    /** 風護の杖(QTE-0123): リーダーの攻撃時に強化する自分のミニオンを選ぶ。Batch 12b */
    GUARD_STAFF_TARGET,

    /** 詠唱の疾風騎士(QTE-0114): ターンエンド時に墓地から回収するスペルを選ぶ。Batch 12b */
    GALE_KNIGHT_RECOVER
}
