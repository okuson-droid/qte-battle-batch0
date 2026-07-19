package com.example.qte.effect;

/**
 * 効果が発動するタイミングの分類。
 *
 * ON_SUMMONとON_ENTERの区別は発注者確認済みの重要裁定:
 * 「召喚」(通常召喚・特殊召喚)ではON_SUMMONとON_ENTERの両方が発動し、
 * 効果による「出す」(墓地からの展開など)ではON_ENTERのみが発動する。
 * 【召喚時】= ON_SUMMON、【知識】などの登場時能力 = ON_ENTER。
 */
public enum TriggerType {

    /** 【召喚時】。召喚(通常・特殊)でのみ発動する */
    ON_SUMMON,

    /** 登場時。召喚か効果による「出す」かを問わず、場に出れば発動する(知識など) */
    ON_ENTER,

    /** 攻撃時(攻撃宣言したとき)。例: 波濤の突撃兵 */
    ON_ATTACK,

    /** 破壊されたとき。例: 執念の暗殺者の監視効果(闇文明実装時) */
    ON_DESTROYED,

    /** ターンエンド時。例: 連撃の巨岩のセルフバウンス(土文明実装時) */
    ON_TURN_END
}
