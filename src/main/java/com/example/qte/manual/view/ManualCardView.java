package com.example.qte.manual.view;

import java.util.List;

import com.example.qte.manual.ManualCardType;
import com.example.qte.manual.ManualCivilization;
import com.example.qte.manual.ManualSeatId;

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
 * ★<b>このオブジェクトが届くこと自体が「見えている」を意味する</b>(Batch 21a 3-3)。
 * 非公開ゾーンのカードは裏向きスタブすら送らない。したがって
 * 「見えないカード用の空の ManualCardView」を作ってはならない。
 *
 * @param placedBySeat 共有ゾーン(PLAY / REVEAL)に置いた席(21 6-2)。
 *                     共有ゾーンの外では null。★対戦部屋では、相手が置いたカードを
 *                     ドラッグ不可にし薄い枠色で区別するために画面が使う(21c)
 * @param stackSize    素材を含めた総枚数。1 なら単体のカード
 * @param materials    進化スタックの下段(先頭が最下段)。{@code +n} バッジを開いたときに使う
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
        ManualSeatId placedBySeat,
        List<String> labels,
        int stackSize,
        List<ManualCardView> materials) {
}
