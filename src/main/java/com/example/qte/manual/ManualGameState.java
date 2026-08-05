package com.example.qte.manual;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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

    /**
     * プレイヤー間で共有するゾーン(Batch 20b 3-1)。{@link ManualZone#isShared()} が true のものだけ。
     *
     * <h3>★席の下に置かない</h3>
     * 「常にA席の zones へ入れておき、画面だけ中央に描く」という慣習方式も採れるが、
     * それはモデルとして嘘であり、将来 {@code seatA.zones.REVEAL} を読んだ人間が
     * 「A席の公開ゾーン」と解釈して必ず間違える。共有であることを型で表す。
     *
     * <h3>データ移行が要らない理由</h3>
     * 部屋はメモリ上にのみ存在し、永続化が無い。デプロイで再起動すれば部屋ごと消えるため、
     * 既存データの読み替えを考える必要はない。
     */
    private final Map<ManualZone, List<ManualCardInstance>> sharedZones =
            new EnumMap<>(ManualZone.class);

    /** 通しのターン番号。人間が進める(設計書 5-3 の10) */
    @Setter
    private int turnNumber = 1;

    /** 表示上のフェイズ。強制はしない */
    @Setter
    private ManualPhase phase = ManualPhase.DRAW;

    public ManualGameState(String roomId) {
        this(roomId, new ManualSeat(ManualSeatId.A), new ManualSeat(ManualSeatId.B));
    }

    private ManualGameState(String roomId, ManualSeat seatA, ManualSeat seatB) {
        this.roomId = roomId;
        this.seatA = seatA;
        this.seatB = seatB;
        for (ManualZone z : ManualZone.values()) {
            if (z.isShared()) {
                sharedZones.put(z, new ArrayList<>());
            }
        }
    }

    public ManualSeat seat(ManualSeatId seatId) {
        return seatId == ManualSeatId.A ? seatA : seatB;
    }

    /**
     * ゾーンの中身を引く。共有ゾーンなら {@code seatId} は無視される(20b 3-2)。
     *
     * ★移動処理はこの1本だけを使う。呼び出し側で「共有かどうか」を分岐すると、
     * 移動元・移動先・検索・クリアの4箇所に同じ判定が散り、必ずどれかが漏れる。
     *
     * @param seatId 席。共有ゾーンのときは null でよい
     */
    public List<ManualCardInstance> cards(ManualSeatId seatId, ManualZone zone) {
        if (zone.isShared()) {
            return sharedZones.get(zone);
        }
        if (seatId == null) {
            throw new IllegalArgumentException("席が指定されていません: " + zone);
        }
        return seat(seatId).zone(zone);
    }

    /**
     * 共有ゾーンを空にする(20b・マスター確認済み)。
     *
     * ★デッキの読み込みとリセットの両方で呼ぶ。共有ゾーンは席の外にあるため
     * {@link ManualSeat#clearAll()} が届かず、放置すると山札へ戻らない個体が
     * 中央に残り続ける。{@link ManualCardInstance} は持ち主を持たないため
     * 「A席由来だけ消す」は原理的に書けない。仕切り直しの操作である以上、
     * 中央も一緒に片付くほうが盤面の一貫性を保てる。
     */
    public void clearSharedZones() {
        for (List<ManualCardInstance> zone : sharedZones.values()) {
            zone.clear();
        }
    }

    /** 深いコピー。履歴に積むスナップショットはこれで作る。 */
    public ManualGameState copy() {
        ManualGameState clone = new ManualGameState(roomId, seatA.copy(), seatB.copy());
        clone.turnNumber = turnNumber;
        clone.phase = phase;
        // ★共有ゾーンも複製する。ここを忘れると Undo が中央のカードだけ巻き戻さない(20b 3-2)
        for (Map.Entry<ManualZone, List<ManualCardInstance>> entry : sharedZones.entrySet()) {
            List<ManualCardInstance> target = clone.sharedZones.get(entry.getKey());
            for (ManualCardInstance card : entry.getValue()) {
                target.add(card.copy());
            }
        }
        return clone;
    }
}
