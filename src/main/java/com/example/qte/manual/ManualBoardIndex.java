package com.example.qte.manual;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 盤面から instanceId でカードを探し、元の場所から外す。
 *
 * <h2>探索範囲は「両席のリーダー・全ゾーン・進化スタックの素材」である</h2>
 * 素材まで探すのは設計書 4-5-2 のためである。
 * 「{@code +n} バッジをクリックすると束の中身が帯として開き、そこから1枚ずつ任意のゾーンへ
 * ドラッグできる」— この帯の中のカードも、盤面上の他のカードと同じように掴める必要がある。
 *
 * ★素材は平坦である(17b 2-2)。素材の中をさらに再帰的に探さないのはそのためであり、
 * 探索は「ゾーンのカード」と「そのカードの materials」の2段で必ず尽きる。
 *
 * <h2>★取り外しは identity 比較で行う</h2>
 * {@link ManualCardInstance} は {@code equals} を上書きしていないため、
 * {@code List.remove(Object)} は同一オブジェクトだけを取り除く。
 * 複数枚をまとめて動かすとき、添字で外すと1枚外すたびに残りの添字がずれるが、
 * オブジェクトで外せばその心配が無い。
 *
 * <h2>線形探索でよい理由</h2>
 * 1試合の総カード数は 100 枚程度であり、操作は人間の手の速さでしか来ない。
 * Map の索引を別に持つと、状態を差し替える(Undo)たびに索引の作り直しが要る。
 * 索引を状態の外に置くと今度は同期の問題になる。探索は毎回作り直すのが最も安い。
 */
public final class ManualBoardIndex {

    /**
     * 盤面上の並び順。席 → ゾーン → ゾーン内の位置 → 素材内の位置。
     *
     * ★複数枚をまとめて動かすときは、クライアントが選択した順ではなくこの順に並べ替える。
     * 設計書 4-5-1 が進化の素材について「選択した順ではなくミニオンゾーンの左からの並び順で
     * 積む。順序に意味は無いが、再現性のために規則を固定しておく」と定めているのと同じ理由で、
     * 移動でも同じ規則を使う。同じ盤面から同じ選択をすれば必ず同じ結果になる。
     */
    public static final Comparator<ManualCardRef> BOARD_ORDER = Comparator
            // ★共有ゾーンのカードは席を持たない(seatId == null)。席の前に並べる(20b 3-2)
            .comparingInt((ManualCardRef ref) -> ref.seatId() == null ? -1 : ref.seatId().ordinal())
            .thenComparingInt(ref -> ref.zone() == null ? -1 : ref.zone().ordinal())
            .thenComparingInt(ManualCardRef::index)
            .thenComparingInt(ManualCardRef::materialIndex);

    private ManualBoardIndex() {
    }

    /** instanceId で1枚探す。見つからなければ空。 */
    public static Optional<ManualCardRef> find(ManualGameState state, String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return Optional.empty();
        }
        for (ManualSeatId seatId : ManualSeatId.values()) {
            ManualSeat seat = state.seat(seatId);
            ManualCardInstance leader = seat.getLeader();
            if (leader != null && leader.getInstanceId().equals(instanceId)) {
                return Optional.of(new ManualCardRef(seatId, null, -1, -1, leader, null));
            }
            for (ManualZone zone : ManualZone.values()) {
                if (zone.isShared()) {
                    continue; // 共有ゾーンは席のループの外で1度だけ見る
                }
                Optional<ManualCardRef> hit =
                        scan(seat.zone(zone), instanceId, seatId, zone);
                if (hit.isPresent()) {
                    return hit;
                }
            }
        }
        // ★共有ゾーン(20b 3-2)。席に属さないため seatId は null で返す。
        for (ManualZone zone : ManualZone.values()) {
            if (!zone.isShared()) {
                continue;
            }
            Optional<ManualCardRef> hit =
                    scan(state.getSharedZones().get(zone), instanceId, null, zone);
            if (hit.isPresent()) {
                return hit;
            }
        }
        return Optional.empty();
    }

    /** ゾーン1つを走査する。直下の1段と、その素材の1段で必ず尽きる(materials は平坦)。 */
    private static Optional<ManualCardRef> scan(List<ManualCardInstance> cards, String instanceId,
            ManualSeatId seatId, ManualZone zone) {
        for (int i = 0; i < cards.size(); i++) {
            ManualCardInstance card = cards.get(i);
            if (card.getInstanceId().equals(instanceId)) {
                return Optional.of(new ManualCardRef(seatId, zone, i, -1, card, null));
            }
            List<ManualCardInstance> materials = card.getMaterials();
            for (int j = 0; j < materials.size(); j++) {
                if (materials.get(j).getInstanceId().equals(instanceId)) {
                    return Optional.of(
                            new ManualCardRef(seatId, zone, i, j, materials.get(j), card));
                }
            }
        }
        return Optional.empty();
    }

    /** instanceId で1枚引く。見つからなければ例外。 */
    public static ManualCardRef require(ManualGameState state, String instanceId) {
        return find(state, instanceId).orElseThrow(
                () -> new IllegalArgumentException("盤面に無いカードです: " + instanceId));
    }

    /**
     * instanceId の一覧をまとめて引く。返るリストは可変であり、並べ替えてよい。
     *
     * 同じIDが2回入っていれば例外にする。1枚のカードを2度外そうとする要求は、
     * 画面側の取りこぼし(同じタイルを2重に選択した等)であって、
     * 黙って1枚として扱うと「選んだ枚数と動いた枚数が合わない」という形で人間を混乱させる。
     */
    public static List<ManualCardRef> requireAll(ManualGameState state, List<String> instanceIds) {
        if (instanceIds == null || instanceIds.isEmpty()) {
            throw new IllegalArgumentException("対象のカードが指定されていません");
        }
        Set<String> seen = new HashSet<>();
        List<ManualCardRef> refs = new ArrayList<>();
        for (String id : instanceIds) {
            if (!seen.add(id)) {
                throw new IllegalArgumentException("同じカードが2回指定されています: " + id);
            }
            refs.add(require(state, id));
        }
        return refs;
    }

    /**
     * カードを今の場所から外す。ゾーンの直下でも進化スタックの素材でも同じ呼び方でよい。
     *
     * ★最上段(束そのもの)を外すと、素材は最上段が抱えたまま一緒に付いてくる。
     * これが設計書 4-5-2 の1「ドラッグは束全体を動かすを既定とする」の実装である。
     * 素材だけを抜きたい場合は、素材の instanceId を渡す(同 2)。
     *
     * ★リーダーは外せない。{@link ManualSeat} はリーダーを1枚の専用スロットで持っており
     * (17b 2-1)、ゾーンへ移すと {@code isDeckLoaded()} が崩れる。
     * リーダーに対しても有効な操作(タップ・数値変更・札)は、外す処理を通らない。
     */
    public static void detach(ManualGameState state, ManualCardRef ref) {
        if (ref.isLeader()) {
            throw new IllegalArgumentException("リーダーはゾーンへ移動できません");
        }
        if (ref.isMaterial()) {
            ref.stackTop().getMaterials().remove(ref.card());
            return;
        }
        // ★共有ゾーンなら seatId は null。cards() が席の有無を吸収する(20b 3-2)
        state.cards(ref.seatId(), ref.zone()).remove(ref.card());
    }
}
