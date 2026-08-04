package com.example.qte.manual;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 手動モードのゾーン(設計書 2-3)。
 *
 * ★リーダーはここに含めない。リーダーは席に1枚だけ存在し、枚数が増減せず、
 * 他のゾーンへ移動しないためである({@link ManualSeat#getLeader()} が持つ)。
 * ゾーンを「0枚以上のカードが出入りする入れ物」に限定しておくと、
 * {@link ManualSeat#zone(ManualZone)} が全ゾーンで同じ型を返せる。
 * Batch 18a の操作13項目はほとんどがゾーン間移動であり、この一様性がそのまま効く。
 *
 * WEAPON は仕様上1枚しか置かないが、他と同じくリストとして持つ。
 * 「装備済みの枠には落とせない」は画面側の規約(設計書 4-5)であり、
 * 状態モデルが枚数を強制すると、人間が一時的に2枚置いて考えることすらできなくなる。
 */
@Getter
@RequiredArgsConstructor
public enum ManualZone {

    DECK("山札"),
    HAND("手札"),
    MANA("マナ"),
    FIELD("ミニオン"),
    WEAPON("ウェポン"),
    TRASH("墓地"),
    LOST("消滅"),
    TABOO("禁忌"),
    REVEAL("一時公開");

    private final String displayName;
}
