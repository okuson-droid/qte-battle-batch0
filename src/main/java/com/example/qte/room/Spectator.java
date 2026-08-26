package com.example.qte.room;

/**
 * 観戦者1人(★Batch 66)。
 *
 * <p>★<b>{@link PlayerSlot} と別の型にする。</b>観戦者は席に着かないので
 * デッキも LP も持たず、{@code GameState} にも現れない。同じ型で
 * 「席が null なら観戦者」と表すと、席を見るのを忘れた分岐が
 * <b>観戦者をプレイヤーとして扱う</b>。型が違えば、その取り違えは
 * コンパイルの段階で止まる。
 *
 * <p>{@code spectatorId} は推測不能な UUID であり、配信の宛先
 * {@code /topic/room/{roomId}/player/{spectatorId}} を兼ねる
 * (プレイヤーと同じ仕組みである。宛先の名前に {@code player} が
 * 残っているのは、既に配信経路として確立しているからで、
 * 「席に着いた人だけの宛先」という意味ではない)。
 */
public record Spectator(String spectatorId, String displayName) {
}
