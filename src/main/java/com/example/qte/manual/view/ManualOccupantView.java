package com.example.qte.manual.view;

import com.example.qte.manual.ManualOccupantRole;

/**
 * 配信用の在室者1人。
 *
 * ★occupantId を載せない。occupantId は配信先
 * {@code /topic/manual/{roomId}/view/{occupantId}} の一部であり、
 * 知っていることがそのまま受信の権利になる(SimpleBroker は購読を認可しない)。
 * 在室者リスト(設計書 11-2)に必要なのは名前と役割だけである。
 * 自分がどれかは {@code self} で示す。
 */
public record ManualOccupantView(
        String displayName,
        ManualOccupantRole role,
        boolean connected,
        boolean self) {
}
