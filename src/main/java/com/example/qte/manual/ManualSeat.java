package com.example.qte.manual;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;

/**
 * 席1つ分の全ゾーンと数値。
 *
 * ★全ゾーンを {@code EnumMap<ManualZone, List<ManualCardInstance>>} の1本で持ち、
 * {@link #zone(ManualZone)} で引く。ゾーンごとにフィールドを分けない。
 * Batch 18a で作る操作13項目のうち大半は「ゾーンAの i 番目をゾーンBの j 番目へ移す」であり、
 * 移動元と移動先を同じ型で受け取れると、実装が {@code zone(from).remove(i)} と
 * {@code zone(to).add(j, card)} の2行に収まる。
 * ゾーンごとに専用フィールドを持つと、9×9の組み合わせを switch で書き分けることになる。
 *
 * リーダーだけはゾーンに含めない。1枚しか無く、移動もしないためである。
 */
@Getter
public class ManualSeat {

    private final ManualSeatId id;

    private final Map<ManualZone, List<ManualCardInstance>> zones = new EnumMap<>(ManualZone.class);

    /** リーダーカード。デッキ未読込なら null */
    @Setter
    private ManualCardInstance leader;

    /** LP。開始時20。上限は強制しない(設計書 5-3 の2) */
    @Setter
    private int lp = ManualGameService.INITIAL_LP;

    /** 読み込んだデッキの名前(表示用)。未読込なら null */
    @Setter
    private String deckName;

    /**
     * 直近に読み込んだデッキの突合結果(Batch 19a)。
     *
     * ★「リセットして引き直す」(設計書 7-1)を zip の再アップロード無しで行うために保持する。
     * {@link ManualGameState#copy()} には含めない。Undo 履歴に積む対象ではなく、
     * 「今このデッキが読み込まれている」という部屋の設定に近い情報だからである。
     * 巻き戻っても消えてはならないので、{@link #copy()} でも複製ではなく参照をそのまま渡す
     * (imported の中身である {@code ManualCardMaster} 等は不変ならば共有して問題ない)。
     */
    @Setter
    private ManualDeckImport lastImport;

    public ManualSeat(ManualSeatId id) {
        this.id = id;
        for (ManualZone z : ManualZone.values()) {
            zones.put(z, new ArrayList<>());
        }
    }

    /** ゾーンの中身。返るリストは可変であり、これを直接編集してよい。 */
    public List<ManualCardInstance> zone(ManualZone target) {
        return zones.get(target);
    }

    /**
     * 使えるMP。マナゾーンのアンタップ枚数そのものである(設計書 5-3)。
     *
     * ★MPを直接増減する操作は用意しない。MPはマナの状態から算出される派生値であり、
     * 直接書き換えを許すと「同じ盤面を見ていることの保証」という手動モード唯一の役割が壊れる。
     * 裏向きのマナも数に入れる。裏向きであることの不利益は禁忌コストの支払い方だけであり
     * (総合ルール 2-3)、MPとしての価値は表向きと変わらない。
     */
    public int availableMp() {
        return (int) zone(ManualZone.MANA).stream().filter(c -> !c.isTapped()).count();
    }

    /** デッキを読み込み済みか。B席(空席)との区別に使う。 */
    public boolean isDeckLoaded() {
        return leader != null || !zone(ManualZone.DECK).isEmpty();
    }

    /** 全ゾーンとリーダーを空にする。デッキの読み込み直しで使う。 */
    public void clearAll() {
        for (ManualZone z : ManualZone.values()) {
            zone(z).clear();
        }
        leader = null;
        deckName = null;
        lp = ManualGameService.INITIAL_LP;
    }

    /** 深いコピー。{@link ManualGameState#copy()} から呼ばれる。 */
    public ManualSeat copy() {
        ManualSeat clone = new ManualSeat(id);
        for (ManualZone z : ManualZone.values()) {
            for (ManualCardInstance card : zone(z)) {
                clone.zone(z).add(card.copy());
            }
        }
        clone.leader = leader == null ? null : leader.copy();
        clone.lp = lp;
        clone.deckName = deckName;
        clone.lastImport = lastImport;
        return clone;
    }
}
