package com.example.qte.game;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * マナゾーンのカード1枚。
 * 「タップ/アンタップ」と「表向き/裏向き」は独立した2つの状態軸(設計判断10)。
 * 裏向きでも通常のMP支払いには使える。裏向きの内容は持ち主にのみ公開される。
 */
@Getter
@RequiredArgsConstructor
public class ManaCard {

    private final String cardId;

    /** ピュア・エレメント由来の一時マナはターン終了時に消滅する(設計判断21) */
    private final boolean temporary;

    private boolean faceUp = true;
    private boolean tapped = false;

    public void tap() {
        this.tapped = true;
    }

    public void untap() {
        this.tapped = false;
    }

    /** 禁忌コスト支払い等で裏向きにする */
    public void turnFaceDown() {
        this.faceUp = false;
    }

    /** 表向きに戻す(闇文明【禁忌の冥魔剣】で初めて必要になった逆操作) */
    public void turnFaceUp() {
        this.faceUp = true;
    }
}
