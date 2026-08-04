package com.example.qte.manual.view;

import java.util.List;
import java.util.Map;

import com.example.qte.manual.ManualSeatId;
import com.example.qte.manual.ManualZone;

/**
 * 配信用の席1つ分。
 *
 * ★ゾーンはフィールドに展開せず Map で送る。状態モデル側が
 * {@code EnumMap<ManualZone, List<...>>} の一様な形で持っているのと同じ理由である。
 * ゾーンが増えても DTO・クライアントの両方に手を入れずに済む。
 * Jackson は enum を鍵とする Map を文字列鍵のオブジェクトとして出すため、
 * クライアントからは {@code seat.zones.FIELD} で引ける。
 *
 * @param mp マナのアンタップ枚数から算出した派生値。直接増減する操作は存在しない(設計書 5-3)
 */
public record ManualSeatView(
        ManualSeatId id,
        int lp,
        int mp,
        boolean deckLoaded,
        String deckName,
        ManualCardView leader,
        Map<ManualZone, List<ManualCardView>> zones) {
}
