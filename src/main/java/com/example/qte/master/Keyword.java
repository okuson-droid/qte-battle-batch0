package com.example.qte.master;

import java.util.Arrays;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * キーワード能力。定義は qte-project-reference.md 5章(全キーワード確定済み)と同期。
 * カードマスタJSONには日本語表記で格納されているため、displayNameから逆引きする。
 */
@Getter
@RequiredArgsConstructor
public enum Keyword {

    /** 出したターンから攻撃できる(対象制限なし) */
    HASTE("速攻"),
    /** 出したターンから攻撃できるが、攻撃対象は相手のミニオンに限る */
    RUSH("突進"),
    /** 相手は、守護を持たない他のミニオンやリーダーに攻撃できない */
    GUARD("守護"),
    /** 相手のカードや能力の対象にならない(自分は対象にできる) */
    STEALTH("潜伏"),
    /** 相手の攻撃対象にならない */
    INTIMIDATE("威圧"),
    /** 守護を無視して他の攻撃対象を選択できる */
    PIERCE("貫通"),
    /** 登場時に1枚ドロー。召喚以外(効果による「出す」)でも発動する(ON_ENTER型) */
    KNOWLEDGE("知識"),
    /** 墓地に行った後、裏向き・アンタップ状態でマナに置かれる */
    RESTORATION("還元"),
    /** カード記載の条件・代替コストによる代替召喚。召喚として扱う(【召喚時】も発動) */
    SPECIAL_SUMMON("特殊召喚");

    private final String displayName;

    public static Keyword fromDisplayName(String displayName) {
        return Arrays.stream(values())
                .filter(k -> k.displayName.equals(displayName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知のキーワード: " + displayName));
    }
}
