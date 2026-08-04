package com.example.qte.manual.view;

import java.util.List;

import com.example.qte.manual.ManualCardType;
import com.example.qte.manual.ManualCivilization;

/**
 * 配信用のカード1枚。
 *
 * ★印刷値(printedAttack / printedHp)と現在値(attack / hp)を両方載せる。
 * 画面は差分を白チップで示す必要があり(設計書 4-3)、クライアントに
 * カード定義の台帳を持たせずに済ませるには、この2組をここで並べておくのが最も安い。
 * 状態モデル側は現在値しか持たない。印刷値は配信のたびにカード定義から引き直す。
 *
 * ★突合できなかったカードは cardId / civilization / type / imageId がすべて null になる。
 * 画面は灰色タイルに name だけを出す(設計書 7-3)。
 *
 * @param stackSize 素材を含めた総枚数。1 なら単体のカード
 * @param materials 進化スタックの下段(先頭が最下段)。{@code +n} バッジを開いたときに使う
 */
public record ManualCardView(
        String instanceId,
        String cardId,
        String name,
        String imageId,
        ManualCivilization civilization,
        ManualCardType type,
        Integer cost,
        Integer printedAttack,
        Integer printedHp,
        Integer attack,
        Integer hp,
        boolean tapped,
        boolean faceDown,
        boolean used,
        List<String> labels,
        int stackSize,
        List<ManualCardView> materials) {
}
