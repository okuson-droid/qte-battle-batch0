package com.example.qte.manual;

/**
 * 在室者の役割(設計書 6章)。
 *
 * フェイズ1で発行されるのは PLAYER だけである。SPECTATOR を今のうちに定義しておくのは、
 * 配信が在室者ごとの個別宛先(設計書 2-4)であり、フェイズ2の観戦では
 * 「役割に応じてフィルタしたビューを個別に送る」形になるためである。
 * 宛先の形と同じく、役割は後から差し込むと配信側を掘り返すことになる。
 */
public enum ManualOccupantRole {
    PLAYER, SPECTATOR
}
