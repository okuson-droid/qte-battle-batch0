package com.example.qte.manual.view;

import java.util.List;
import java.util.Map;

import com.example.qte.manual.ManualPhase;
import com.example.qte.manual.ManualZone;

/**
 * 在室者1人に送る盤面ビュー。
 *
 * ★宛先が在室者ごとに分かれているため、この1件が「その人の見えるものすべて」である
 * (設計書 2-4)。フェイズ1は全公開なのでフィルタは効いていないが、
 * フェイズ2の対戦モードでは、同じ盤面から人によって中身の違うこの型が作られる。
 *
 * @param occupantId 受け取る本人のID。クライアントが購読先を組み立てるのに使う
 * @param backImageId 裏面画像のID。裏向きのカードはこれ1つで描ける
 * @param canUndo / canRedo 履歴の状態。ボタンの活性を決めるためだけの値である
 * @param shared プレイヤー間で共有するゾーン(PLAY / REVEAL)。席に属さない(20b 3-2)
 */
public record ManualGameView(
        String roomId,
        String occupantId,
        String backImageId,
        int turnNumber,
        ManualPhase phase,
        ManualSeatView seatA,
        ManualSeatView seatB,
        Map<ManualZone, List<ManualCardView>> shared,
        List<ManualOccupantView> occupants,
        List<ManualLogView> log,
        boolean canUndo,
        boolean canRedo) {
}
