package com.example.qte.game.view;

/**
 * マナ1枚のビュー。
 * 裏向きマナの中身(cardId/name)は持ち主のビューにのみ入れ、相手のビューではnullにする。
 * 表向きのマナは公開情報なので双方に見える。
 */
public record ManaView(
        boolean faceUp,
        boolean tapped,
        boolean temporary,
        String cardId,
        String name) {
}
