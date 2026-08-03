package com.example.qte.manual;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 手動モードの文明。6文明 + NONE(ピュア・エレメント)。
 *
 * 宣言順がそのまま画面の並び順になる。実装順(水→火→闇→光→風→土)に合わせてある。
 * タイル色(設計書 4-2)はここに持たせない。色は表示の都合であり、
 * 変えたくなるのは CSS 側だからである(Batch 18b で battle.css に置く)。
 */
@Getter
@RequiredArgsConstructor
public enum ManualCivilization {
    WATER("水"), FIRE("火"), DARK("闇"), LIGHT("光"), WIND("風"), EARTH("土"), NONE("なし");

    private final String displayName;
}
