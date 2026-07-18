package com.example.qte.master;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 文明(属性)。NONEはピュア・エレメント等の文明なし特殊カード。 */
@Getter
@RequiredArgsConstructor
public enum Civilization {
    FIRE("火"), WATER("水"), WIND("風"), LIGHT("光"), DARK("闇"), EARTH("土"), NONE("なし");

    private final String displayName;
}
