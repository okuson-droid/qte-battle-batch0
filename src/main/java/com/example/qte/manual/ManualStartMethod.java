package com.example.qte.manual;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 先攻後攻の決め方(Batch 23 設計書 3-1 の3択)。
 *
 * ★{@link #DICE} だけ部屋の種類で意味が変わる。
 * <ul>
 *   <li>対戦部屋 — 勝った側に<b>選択権</b>を与える({@link ManualStartPhase#ORDER_CHOICE} へ)</li>
 *   <li>全公開部屋(ソロ)— 勝った側が<b>そのまま先攻</b>。
 *       両席とも同じ人が操作するため、選択モーダルをもう1枚出しても
 *       同じ人が続けて2回押すだけになる(3-1。マスター指示)</li>
 * </ul>
 *
 * ★{@link #FIRST} / {@link #SECOND} の「自分」は<b>押した人の席</b>である。
 * 席を持たない全公開部屋の在室者では、部屋の作成者席を主語にする
 * ({@code ManualStartService.subjectSeat})。
 */
@Getter
@RequiredArgsConstructor
public enum ManualStartMethod {

    /** ダイスで決める(20面)。ソロでは「ランダムで決める」 */
    DICE("ダイスで決める"),
    /** 自分が先攻をとる */
    FIRST("自分が先攻をとる"),
    /** 自分が後攻をとる */
    SECOND("自分が後攻をとる");

    private final String displayName;
}
