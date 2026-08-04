package com.example.qte.manual;

import lombok.Getter;
import lombok.Setter;

/**
 * 手動モードの1試合の全状態。ここから両席の全ゾーンに到達できる。
 *
 * <h2>★ログを含まない(設計書 5-5・レビューE反映)</h2>
 * ログは {@link ManualRoom} が持つ。理由は Undo である。
 * このクラスは {@link #copy()} でまるごと複製され、履歴スタックに積まれる。
 * ログがこの中にあると、Undo のたびにログまで巻き戻り、
 * 「何をして、それを取り消した」という記録そのものが消えてしまう。
 * ログは追記専用であり、Undo 実行時は状態だけを戻して
 * 「操作を1つ取り消した」をログに追記する。
 *
 * <h2>スナップショット方式であること(設計書 5-6)</h2>
 * この状態はカードIDのリストと数値と短い文字列だけで構成され、関数も外部参照も持たない。
 * だからこそ丸ごとコピーが最も安く、逆操作を20個実装するコマンドパターンより割に合う。
 * ★この性質を壊さないこと。ここに {@link ManualCardMaster} への参照や
 * ラムダを持ち込んだ瞬間に、Undo の前提が崩れる。カード定義は cardId から引く。
 */
@Getter
public class ManualGameState {

    private final String roomId;

    private final ManualSeat seatA;

    private final ManualSeat seatB;

    /** 通しのターン番号。人間が進める(設計書 5-3 の10) */
    @Setter
    private int turnNumber = 1;

    /** 表示上のフェイズ。強制はしない */
    @Setter
    private ManualPhase phase = ManualPhase.DRAW;

    public ManualGameState(String roomId) {
        this.roomId = roomId;
        this.seatA = new ManualSeat(ManualSeatId.A);
        this.seatB = new ManualSeat(ManualSeatId.B);
    }

    private ManualGameState(String roomId, ManualSeat seatA, ManualSeat seatB) {
        this.roomId = roomId;
        this.seatA = seatA;
        this.seatB = seatB;
    }

    public ManualSeat seat(ManualSeatId seatId) {
        return seatId == ManualSeatId.A ? seatA : seatB;
    }

    /** 深いコピー。履歴に積むスナップショットはこれで作る。 */
    public ManualGameState copy() {
        ManualGameState clone = new ManualGameState(roomId, seatA.copy(), seatB.copy());
        clone.turnNumber = turnNumber;
        clone.phase = phase;
        return clone;
    }
}
