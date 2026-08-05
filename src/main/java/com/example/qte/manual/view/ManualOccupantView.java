package com.example.qte.manual.view;

import com.example.qte.manual.ManualOccupantRole;
import com.example.qte.manual.ManualSeatId;
import com.example.qte.manual.ManualSpectatorView;

/**
 * 配信用の在室者1人(設計書 2-3 の在室者リスト)。
 *
 * ★occupantId を載せない。occupantId は配信先
 * {@code /topic/manual/{roomId}/view/{occupantId}} の一部であり、
 * 知っていることがそのまま受信の権利になる(SimpleBroker は購読を認可しない)。
 * 自分がどれかは {@code self} で示す。
 *
 * <h2>★Batch 21a で足した項目(設計書 2-3)</h2>
 * ポップオーバーに出すのは「名前・役割・観戦者の視点・切断中(残り時間)」である。
 * 観戦者の視点を出すのは設計書16 11-2 の確定事項であり、
 * <b>全見えの観戦者が居ることをプレイヤーが知っていられる</b>ようにするためである。
 * 知らないうちに手札を見られている状態を作らない。
 *
 * @param seatId                座っている席。null なら観戦者
 * @param spectatorView         観戦者の視点。★プレイヤーでは null(意味を持たない)
 * @param disconnectSecondsLeft 切断猶予の残り秒数。接続中なら null(2-4)
 */
public record ManualOccupantView(
        String displayName,
        ManualOccupantRole role,
        ManualSeatId seatId,
        ManualSpectatorView spectatorView,
        boolean connected,
        Long disconnectSecondsLeft,
        boolean self) {
}
