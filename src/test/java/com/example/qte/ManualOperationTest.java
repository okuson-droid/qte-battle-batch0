package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.manual.ManualActor;
import com.example.qte.manual.ManualBoardIndex;
import com.example.qte.manual.ManualCardInstance;
import com.example.qte.manual.ManualCardMaster;
import com.example.qte.manual.ManualCardRepository;
import com.example.qte.manual.ManualCardType;
import com.example.qte.manual.ManualDeclaration;
import com.example.qte.manual.ManualGameService;
import com.example.qte.manual.ManualLabels;
import com.example.qte.manual.ManualOpRequest;
import com.example.qte.manual.ManualOperationService;
import com.example.qte.manual.ManualPhase;
import com.example.qte.manual.ManualRoom;
import com.example.qte.manual.ManualSeat;
import com.example.qte.manual.ManualSeatId;
import com.example.qte.manual.ManualZone;

/**
 * Batch 18a の操作テスト(設計書 5-3 の13項目 / 4-5 の進化スタック / 5-6 の Undo)。
 *
 * ★カードIDを文字列リテラルで書かないこと(batch17a-design-notes 3-2)。
 * {@code tools/check_all.py} の項目3 が手動モードのIDを台帳に無いIDとして報告する。
 * 大半のテストは突合しないカード({@code ManualCardInstance.unresolved})で組み立て、
 * 印刷値が要るテストだけ {@link ManualCardRepository} から種別で1枚拾う。
 *
 * ★操作が失敗すると盤面は操作前のスナップショットへ差し替わる。
 * 差し替え後は同じ instanceId でも別のオブジェクトになるため、
 * 失敗をまたいで検証するときは {@link #reload} で引き直す。
 */
@SpringBootTest
class ManualOperationTest {

    /**
     * ★Batch 21a: 権限を見ない操作者。全公開部屋の既定と同じである。
     * 対戦部屋の権限・視点・ログのマスクは {@code ManualVersusTest} が受け持つ。
     */
    static final ManualActor ACTOR = ManualActor.unrestricted();

    @Autowired
    ManualOperationService operations;

    @Autowired
    ManualCardRepository cards;

    // ================= ゾーン間移動(設計書 5-3 の1) =================

    @Test
    void 手札からマナへ裏向きで移せる() {
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance target = put(room, ManualZone.HAND, "札1");

        operations.apply(room, ACTOR, state -> operations.move(state, ACTOR, new ManualOpRequest.Move(
                null, List.of(target.getInstanceId()),
                ManualSeatId.A, ManualZone.MANA, null, true)));

        assertThat(seatA(room).zone(ManualZone.HAND)).isEmpty();
        assertThat(seatA(room).zone(ManualZone.MANA)).hasSize(1);
        assertThat(reload(room, target).isFaceDown()).isTrue();
        assertThat(seatA(room).availableMp()).isEqualTo(1);
    }

    @Test
    void 複数枚は選択順ではなく盤面の並び順で挿入される() {
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance first = put(room, ManualZone.HAND, "1番目");
        ManualCardInstance second = put(room, ManualZone.HAND, "2番目");
        ManualCardInstance third = put(room, ManualZone.HAND, "3番目");

        operations.apply(room, ACTOR, state -> operations.move(state, ACTOR, new ManualOpRequest.Move(
                null,
                List.of(third.getInstanceId(), first.getInstanceId(), second.getInstanceId()),
                ManualSeatId.A, ManualZone.TRASH, null, null)));

        List<ManualCardInstance> trash = seatA(room).zone(ManualZone.TRASH);
        assertThat(trash).hasSize(3);
        assertThat(trash.get(0).getFallbackName()).isEqualTo("1番目");
        assertThat(trash.get(1).getFallbackName()).isEqualTo("2番目");
        assertThat(trash.get(2).getFallbackName()).isEqualTo("3番目");
    }

    @Test
    void 挿入位置を指定でき範囲外は丸められる() {
        ManualRoom room = new ManualRoom("TESTRM");
        put(room, ManualZone.DECK, "山1");
        put(room, ManualZone.DECK, "山2");
        ManualCardInstance moved = put(room, ManualZone.HAND, "差し込む札");

        operations.apply(room, ACTOR, state -> operations.move(state, ACTOR, new ManualOpRequest.Move(
                null, List.of(moved.getInstanceId()),
                ManualSeatId.A, ManualZone.DECK, 999, null)));

        List<ManualCardInstance> deck = seatA(room).zone(ManualZone.DECK);
        assertThat(deck).hasSize(3);
        assertThat(deck.get(2).getFallbackName()).isEqualTo("差し込む札");
    }

    @Test
    void リーダーはゾーンへ移動できない() {
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance leader = card("リーダー");
        seatA(room).setLeader(leader);

        assertThatThrownBy(() -> operations.apply(room, ACTOR, state -> operations.move(state, ACTOR,
                new ManualOpRequest.Move(null, List.of(leader.getInstanceId()),
                        ManualSeatId.A, ManualZone.TRASH, null, null))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(seatA(room).getLeader()).isNotNull();
        assertThat(seatA(room).zone(ManualZone.TRASH)).isEmpty();
    }

    @Test
    void 同じカードを2回指定すると弾く() {
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance target = put(room, ManualZone.HAND, "札1");

        assertThatThrownBy(() -> operations.apply(room, ACTOR, state -> operations.move(state, ACTOR,
                new ManualOpRequest.Move(null,
                        List.of(target.getInstanceId(), target.getInstanceId()),
                        ManualSeatId.A, ManualZone.TRASH, null, null))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(seatA(room).zone(ManualZone.HAND)).hasSize(1);
    }

    // ================= 進化スタック(設計書 4-5) =================

    @Test
    void 進化は素材3体を1枠にまとめ平坦に積む() {
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance m1 = put(room, ManualZone.FIELD, "素材1");
        ManualCardInstance m2 = put(room, ManualZone.FIELD, "素材2");
        ManualCardInstance m3 = put(room, ManualZone.FIELD, "素材3");
        ManualCardInstance evolution = put(room, ManualZone.HAND, "進化");

        operations.apply(room, ACTOR, state -> operations.evolve(state, ACTOR, new ManualOpRequest.Evolve(
                null, ManualSeatId.A, evolution.getInstanceId(),
                List.of(m3.getInstanceId(), m1.getInstanceId(), m2.getInstanceId()), null)));

        // ★ミニオン枠が 3 → 1 に減る(設計書 4-5-1)
        assertThat(seatA(room).zone(ManualZone.FIELD)).hasSize(1);
        assertThat(seatA(room).zone(ManualZone.HAND)).isEmpty();

        ManualCardInstance top = seatA(room).zone(ManualZone.FIELD).get(0);
        assertThat(top.getInstanceId()).isEqualTo(evolution.getInstanceId());
        assertThat(top.materialCount()).isEqualTo(3);
        assertThat(top.stackSize()).isEqualTo(4);
        // 素材は選択順ではなくミニオンゾーンの左からの並び順で積む
        assertThat(top.getMaterials().get(0).getFallbackName()).isEqualTo("素材1");
        assertThat(top.getMaterials().get(2).getFallbackName()).isEqualTo("素材3");
    }

    @Test
    void 進化の上に進化を重ねてもスタックは平坦なまま() {
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance m1 = put(room, ManualZone.FIELD, "素材1");
        ManualCardInstance m2 = put(room, ManualZone.FIELD, "素材2");
        ManualCardInstance m3 = put(room, ManualZone.FIELD, "素材3");
        ManualCardInstance first = put(room, ManualZone.HAND, "進化1");
        ManualCardInstance second = put(room, ManualZone.HAND, "進化2");

        operations.apply(room, ACTOR, state -> operations.evolve(state, ACTOR, new ManualOpRequest.Evolve(
                null, ManualSeatId.A, first.getInstanceId(),
                List.of(m1.getInstanceId(), m2.getInstanceId(), m3.getInstanceId()), null)));
        operations.apply(room, ACTOR, state -> operations.evolve(state, ACTOR, new ManualOpRequest.Evolve(
                null, ManualSeatId.A, second.getInstanceId(),
                List.of(first.getInstanceId()), null)));

        ManualCardInstance top = seatA(room).zone(ManualZone.FIELD).get(0);
        assertThat(top.getInstanceId()).isEqualTo(second.getInstanceId());
        // ★設計書 4-5-1: 3体を素材にすれば +3、その上にさらに重ねれば +4
        assertThat(top.materialCount()).isEqualTo(4);
        assertThat(top.stackSize()).isEqualTo(5);
        // 下になった進化ミニオンは素材を手放し、平らに並ぶ
        assertThat(top.getMaterials().get(3).getInstanceId()).isEqualTo(first.getInstanceId());
        assertThat(top.getMaterials().get(3).materialCount()).isZero();
    }

    /**
     * ★Batch 27(不具合修正)。26 まではここが「墓地1枚・materialCount 2」を期待しており、
     * 実装(束のまま運ぶ)をそのまま固定していた。素材が画面から消える不具合の本体である。
     * 設計書16 4-5-2 の「破壊時に全部墓地へ」に合わせ、期待を書き換えた。
     */
    @Test
    void 束ごと墓地へ送ると解体されて素材も墓地に並ぶ() {
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance m1 = put(room, ManualZone.FIELD, "素材1");
        ManualCardInstance m2 = put(room, ManualZone.FIELD, "素材2");
        ManualCardInstance evolution = put(room, ManualZone.HAND, "進化");

        operations.apply(room, ACTOR, state -> operations.evolve(state, ACTOR, new ManualOpRequest.Evolve(
                null, ManualSeatId.A, evolution.getInstanceId(),
                List.of(m1.getInstanceId(), m2.getInstanceId()), null)));
        operations.apply(room, ACTOR, state -> operations.move(state, ACTOR, new ManualOpRequest.Move(
                null, List.of(evolution.getInstanceId()),
                ManualSeatId.A, ManualZone.TRASH, null, null)));

        assertThat(seatA(room).zone(ManualZone.FIELD)).isEmpty();
        // ★3枚が独立したカードとして墓地に入る(枚数が正しくなる)
        List<ManualCardInstance> trash = seatA(room).zone(ManualZone.TRASH);
        assertThat(trash).hasSize(3);
        assertThat(trash).allSatisfy(c -> assertThat(c.materialCount()).isZero());
        // 素材が先・最上段が末尾。公開パイルの一番上は末尾なので進化ミニオンが見える
        assertThat(trash.get(0).getInstanceId()).isEqualTo(m1.getInstanceId());
        assertThat(trash.get(1).getInstanceId()).isEqualTo(m2.getInstanceId());
        assertThat(trash.get(2).getInstanceId()).isEqualTo(evolution.getInstanceId());
    }

    @Test
    void 解体は手札や消滅などFIELD以外のどのゾーンでも起きる() {
        for (ManualZone zone : List.of(ManualZone.HAND, ManualZone.LOST, ManualZone.DECK)) {
            ManualRoom room = new ManualRoom("TESTRM");
            ManualCardInstance m1 = put(room, ManualZone.FIELD, "素材1");
            ManualCardInstance evolution = put(room, ManualZone.HAND, "進化");

            operations.apply(room, ACTOR, state -> operations.evolve(state, ACTOR,
                    new ManualOpRequest.Evolve(null, ManualSeatId.A, evolution.getInstanceId(),
                            List.of(m1.getInstanceId()), null)));
            operations.apply(room, ACTOR, state -> operations.move(state, ACTOR,
                    new ManualOpRequest.Move(null, List.of(evolution.getInstanceId()),
                            ManualSeatId.A, zone, null, null)));

            assertThat(seatA(room).zone(zone)).as("移動先=%s", zone).hasSize(2);
            assertThat(seatA(room).zone(ManualZone.FIELD)).as("移動先=%s", zone).isEmpty();
        }
    }

    /**
     * ★席をまたぐ FIELD → FIELD は束のまま(数値を保持する v2.4 の規則と同じ切り口)。
     * 盤面の意味が変わらない移動で束をほどくと、相手の場でミニオン枠を余計に食う。
     */
    @Test
    void 相手の場へ移すときは束のままである() {
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance m1 = put(room, ManualZone.FIELD, "素材1");
        ManualCardInstance m2 = put(room, ManualZone.FIELD, "素材2");
        ManualCardInstance evolution = put(room, ManualZone.HAND, "進化");

        operations.apply(room, ACTOR, state -> operations.evolve(state, ACTOR, new ManualOpRequest.Evolve(
                null, ManualSeatId.A, evolution.getInstanceId(),
                List.of(m1.getInstanceId(), m2.getInstanceId()), null)));
        operations.apply(room, ACTOR, state -> operations.move(state, ACTOR, new ManualOpRequest.Move(
                null, List.of(evolution.getInstanceId()),
                ManualSeatId.B, ManualZone.FIELD, null, null)));

        assertThat(seatA(room).zone(ManualZone.FIELD)).isEmpty();
        List<ManualCardInstance> opponentField =
                room.getGameState().seat(ManualSeatId.B).zone(ManualZone.FIELD);
        assertThat(opponentField).hasSize(1);
        assertThat(opponentField.get(0).materialCount()).isEqualTo(2);
    }

    /**
     * ★挿入位置を指定した解体。1つの ref から複数枚が入るため、refs の添字で位置を決めると
     * 並びが崩れる(カーソルで持つ理由)。
     */
    @Test
    void 解体したカードは指定した位置へ連続して入る() {
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance first = put(room, ManualZone.TRASH, "先客1");
        ManualCardInstance second = put(room, ManualZone.TRASH, "先客2");
        ManualCardInstance m1 = put(room, ManualZone.FIELD, "素材1");
        ManualCardInstance evolution = put(room, ManualZone.HAND, "進化");

        operations.apply(room, ACTOR, state -> operations.evolve(state, ACTOR, new ManualOpRequest.Evolve(
                null, ManualSeatId.A, evolution.getInstanceId(),
                List.of(m1.getInstanceId()), null)));
        operations.apply(room, ACTOR, state -> operations.move(state, ACTOR, new ManualOpRequest.Move(
                null, List.of(evolution.getInstanceId()),
                ManualSeatId.A, ManualZone.TRASH, 1, null)));

        List<ManualCardInstance> trash = seatA(room).zone(ManualZone.TRASH);
        assertThat(trash).hasSize(4);
        assertThat(trash.get(0).getInstanceId()).isEqualTo(first.getInstanceId());
        assertThat(trash.get(1).getInstanceId()).isEqualTo(m1.getInstanceId());
        assertThat(trash.get(2).getInstanceId()).isEqualTo(evolution.getInstanceId());
        assertThat(trash.get(3).getInstanceId()).isEqualTo(second.getInstanceId());
    }

    @Test
    void 解体はUndoで元の束に戻る() {
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance m1 = put(room, ManualZone.FIELD, "素材1");
        ManualCardInstance m2 = put(room, ManualZone.FIELD, "素材2");
        ManualCardInstance evolution = put(room, ManualZone.HAND, "進化");

        operations.apply(room, ACTOR, state -> operations.evolve(state, ACTOR, new ManualOpRequest.Evolve(
                null, ManualSeatId.A, evolution.getInstanceId(),
                List.of(m1.getInstanceId(), m2.getInstanceId()), null)));
        operations.apply(room, ACTOR, state -> operations.move(state, ACTOR, new ManualOpRequest.Move(
                null, List.of(evolution.getInstanceId()),
                ManualSeatId.A, ManualZone.TRASH, null, null)));
        operations.applyDirect(room, r -> operations.undo(r, ACTOR));

        assertThat(seatA(room).zone(ManualZone.TRASH)).isEmpty();
        assertThat(seatA(room).zone(ManualZone.FIELD)).hasSize(1);
        assertThat(reload(room, evolution).materialCount()).isEqualTo(2);
    }

    @Test
    void 素材になる瞬間に印刷値へ戻る() {
        ManualCardMaster master = resolvedMinion();
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance m1 = ManualCardInstance.of(master);
        seatA(room).zone(ManualZone.FIELD).add(m1);
        ManualCardInstance m2 = put(room, ManualZone.FIELD, "素材2");
        ManualCardInstance evolution = put(room, ManualZone.HAND, "進化");
        // 素材にする前に受けたダメージ・強化
        m1.setAttack(99);
        m1.setHp(1);

        operations.apply(room, ACTOR, state -> operations.evolve(state, ACTOR, new ManualOpRequest.Evolve(
                null, ManualSeatId.A, evolution.getInstanceId(),
                List.of(m1.getInstanceId(), m2.getInstanceId()), null)));

        // ★設計書16 訂正: 素材になった瞬間、独立したミニオンとしての履歴は切れて印刷値へ戻る
        ManualCardInstance top = reload(room, evolution);
        ManualCardInstance stacked = top.getMaterials().stream()
                .filter(c -> c.getInstanceId().equals(m1.getInstanceId())).findFirst().orElseThrow();
        assertThat(stacked.getAttack()).isEqualTo(master.attack());
        assertThat(stacked.getHp()).isEqualTo(master.hp());
    }

    @Test
    void ミニオンゾーンを離れると印刷値へ戻る() {
        ManualCardMaster master = resolvedMinion();
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance minion = ManualCardInstance.of(master);
        seatA(room).zone(ManualZone.FIELD).add(minion);
        minion.setAttack(99);
        minion.setHp(1);

        operations.apply(room, ACTOR, state -> operations.move(state, ACTOR, new ManualOpRequest.Move(
                null, List.of(minion.getInstanceId()),
                ManualSeatId.A, ManualZone.TRASH, null, null)));

        // ★設計書16 訂正: FIELD → FIELD 以外の移動で印刷値へ戻る
        ManualCardInstance moved = reload(room, minion);
        assertThat(moved.getAttack()).isEqualTo(master.attack());
        assertThat(moved.getHp()).isEqualTo(master.hp());
    }

    @Test
    void ミニオンゾーンから相手のミニオンゾーンへ移しても数値は戻らない() {
        ManualCardMaster master = resolvedMinion();
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance minion = ManualCardInstance.of(master);
        seatA(room).zone(ManualZone.FIELD).add(minion);
        minion.setAttack(99);
        minion.setHp(1);

        // 「相手にこのミニオンがいる想定」で置き直す操作(設計書 4-5 のドロップ先)
        operations.apply(room, ACTOR, state -> operations.move(state, ACTOR, new ManualOpRequest.Move(
                null, List.of(minion.getInstanceId()),
                ManualSeatId.B, ManualZone.FIELD, null, null)));

        // FIELD → FIELD(席をまたいでも)は場に居続ける扱いなので戻さない
        ManualCardInstance moved = reload(room, minion);
        assertThat(moved.getAttack()).isEqualTo(99);
        assertThat(moved.getHp()).isEqualTo(1);
    }

    @Test
    void ウェポンも装備を外すと印刷値へ戻る() {
        ManualCardMaster master = resolvedWeapon();
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance weapon = ManualCardInstance.of(master);
        seatA(room).zone(ManualZone.WEAPON).add(weapon);
        weapon.setAttack(99); // 強化を受けた状態

        operations.apply(room, ACTOR, state -> operations.move(state, ACTOR, new ManualOpRequest.Move(
                null, List.of(weapon.getInstanceId()),
                ManualSeatId.A, ManualZone.TRASH, null, null)));

        // ★設計書16 v2.4: WEAPON を離れると印刷値へ戻る(FIELD と同じ規則)
        assertThat(reload(room, weapon).getAttack()).isEqualTo(master.attack());
    }

    @Test
    void ウェポンを装備し直しても数値は変わらない() {
        ManualCardMaster master = resolvedWeapon();
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance weapon = ManualCardInstance.of(master);
        seatA(room).zone(ManualZone.WEAPON).add(weapon);
        weapon.setAttack(99);

        // 同じ WEAPON ゾーン内での位置変更(装備し直し)は「離れる」に当たらない
        operations.apply(room, ACTOR, state -> operations.move(state, ACTOR, new ManualOpRequest.Move(
                null, List.of(weapon.getInstanceId()),
                ManualSeatId.A, ManualZone.WEAPON, null, null)));

        assertThat(reload(room, weapon).getAttack()).isEqualTo(99);
    }

    @Test
    void 帯から抜いた素材はミニオンゾーンへ戻せて数値は印刷値のまま() {
        ManualCardMaster master = resolvedMinion();
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance m1 = ManualCardInstance.of(master);
        seatA(room).zone(ManualZone.FIELD).add(m1);
        ManualCardInstance m2 = put(room, ManualZone.FIELD, "素材2");
        ManualCardInstance evolution = put(room, ManualZone.HAND, "進化");
        m1.setAttack(99); // evolve で印刷値へ戻る

        operations.apply(room, ACTOR, state -> operations.evolve(state, ACTOR, new ManualOpRequest.Evolve(
                null, ManualSeatId.A, evolution.getInstanceId(),
                List.of(m1.getInstanceId(), m2.getInstanceId()), null)));
        // ★帯から最上段以外を1枚だけ抜く(設計書 4-5-2 の2)
        operations.apply(room, ACTOR, state -> operations.move(state, ACTOR, new ManualOpRequest.Move(
                null, List.of(m1.getInstanceId()),
                ManualSeatId.A, ManualZone.FIELD, null, null)));

        ManualCardInstance top = reload(room, evolution);
        assertThat(top.materialCount()).isEqualTo(1);
        ManualCardInstance returned = reload(room, m1);
        assertThat(returned.getAttack()).isEqualTo(master.attack());
        assertThat(seatA(room).zone(ManualZone.FIELD)).hasSize(2);
    }

    @Test
    void 素材なしでも場に出せる() {
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance evolution = put(room, ManualZone.HAND, "不敗の進化");

        operations.apply(room, ACTOR, state -> operations.evolve(state, ACTOR, new ManualOpRequest.Evolve(
                null, ManualSeatId.A, evolution.getInstanceId(), List.of(), null)));

        assertThat(seatA(room).zone(ManualZone.FIELD)).hasSize(1);
        assertThat(reload(room, evolution).materialCount()).isZero();
    }

    @Test
    void 素材にできるのは同じ席のミニオンゾーンのカードだけである() {
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance inHand = put(room, ManualZone.HAND, "手札のミニオン");
        ManualCardInstance evolution = put(room, ManualZone.HAND, "進化");

        assertThatThrownBy(() -> operations.apply(room, ACTOR, state -> operations.evolve(state, ACTOR,
                new ManualOpRequest.Evolve(null, ManualSeatId.A, evolution.getInstanceId(),
                        List.of(inHand.getInstanceId()), null))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(seatA(room).zone(ManualZone.HAND)).hasSize(2);
        assertThat(seatA(room).zone(ManualZone.FIELD)).isEmpty();
    }

    // ================= 数値・札・フラグ(設計書 5-3 の2〜8) =================

    @Test
    void LPは上限も下限も強制しない() {
        ManualRoom room = new ManualRoom("TESTRM");

        operations.apply(room, ACTOR, state -> operations.changeLp(state, ACTOR,
                new ManualOpRequest.Lp(null, ManualSeatId.A, 40, null)));
        assertThat(seatA(room).getLp()).isEqualTo(40);

        operations.apply(room, ACTOR, state -> operations.changeLp(state, ACTOR,
                new ManualOpRequest.Lp(null, ManualSeatId.A, null, -45)));
        assertThat(seatA(room).getLp()).isEqualTo(-5);
    }

    @Test
    void 数値は直接指定と増減の両方で書き換えられる() {
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance minion = put(room, ManualZone.FIELD, "ミニオン");

        operations.apply(room, ACTOR, state -> operations.changeStats(state, ACTOR,
                new ManualOpRequest.Stat(null, minion.getInstanceId(), 4, 5, null, null)));
        assertThat(reload(room, minion).getAttack()).isEqualTo(4);

        operations.apply(room, ACTOR, state -> operations.changeStats(state, ACTOR,
                new ManualOpRequest.Stat(null, minion.getInstanceId(), null, null, null, -2)));
        assertThat(reload(room, minion).getHp()).isEqualTo(3);
    }

    @Test
    void 空欄の数値は増減できない() {
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance spell = put(room, ManualZone.HAND, "スペル");

        assertThatThrownBy(() -> operations.apply(room, ACTOR, state -> operations.changeStats(state, ACTOR,
                new ManualOpRequest.Stat(null, spell.getInstanceId(), null, null, 1, null))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(reload(room, spell).getAttack()).isNull();
    }

    @Test
    void 数値を印刷値へ戻せる() {
        ManualCardMaster master = resolvedMinion();
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance minion = ManualCardInstance.of(master);
        seatA(room).zone(ManualZone.FIELD).add(minion);
        minion.setAttack(99);
        minion.setHp(1);

        operations.apply(room, ACTOR, state -> operations.resetStats(state, ACTOR,
                new ManualOpRequest.Target(null, minion.getInstanceId())));

        assertThat(reload(room, minion).getAttack()).isEqualTo(master.attack());
        assertThat(reload(room, minion).getHp()).isEqualTo(master.hp());
    }

    @Test
    void 札は付け外しできて自由入力も通る() {
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance minion = put(room, ManualZone.FIELD, "ミニオン");
        String preset = ManualLabels.DEFAULTS.get(0);

        operations.apply(room, ACTOR, state -> operations.addLabel(state, ACTOR,
                new ManualOpRequest.Label(null, minion.getInstanceId(), preset)));
        // ★既定9種の外でも通る(設計書 5-4)。アプリは意味を解釈しない
        operations.apply(room, ACTOR, state -> operations.addLabel(state, ACTOR,
                new ManualOpRequest.Label(null, minion.getInstanceId(), "賢魂：3")));
        assertThat(reload(room, minion).getLabels()).containsExactly(preset, "賢魂：3");

        operations.apply(room, ACTOR, state -> operations.removeLabel(state, ACTOR,
                new ManualOpRequest.Label(null, minion.getInstanceId(), preset)));
        assertThat(reload(room, minion).getLabels()).containsExactly("賢魂：3");

        operations.apply(room, ACTOR, state -> operations.removeLabel(state, ACTOR,
                new ManualOpRequest.Label(null, minion.getInstanceId(), null)));
        assertThat(reload(room, minion).getLabels()).isEmpty();
    }

    @Test
    void タップと表裏は明示指定とトグルの両方ができる() {
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance mana1 = put(room, ManualZone.MANA, "マナ1");
        ManualCardInstance mana2 = put(room, ManualZone.MANA, "マナ2");

        operations.apply(room, ACTOR, state -> operations.tap(state, ACTOR, new ManualOpRequest.Flag(
                null, List.of(mana1.getInstanceId(), mana2.getInstanceId()), true)));
        assertThat(seatA(room).availableMp()).isZero();

        operations.apply(room, ACTOR, state -> operations.tap(state, ACTOR, new ManualOpRequest.Flag(
                null, List.of(mana1.getInstanceId()), null)));
        assertThat(seatA(room).availableMp()).isEqualTo(1);

        operations.apply(room, ACTOR, state -> operations.flip(state, ACTOR, new ManualOpRequest.Flag(
                null, List.of(mana1.getInstanceId()), null)));
        assertThat(reload(room, mana1).isFaceDown()).isTrue();
        // 裏向きでもアンタップならMPになる(総合ルール 2-3)
        assertThat(seatA(room).availableMp()).isEqualTo(1);
    }

    @Test
    void リーダーもタップでき使用済みフラグも切り替えられる() {
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance leader = card("リーダー");
        seatA(room).setLeader(leader);
        ManualCardInstance weapon = put(room, ManualZone.WEAPON, "ウェポン");

        operations.apply(room, ACTOR, state -> operations.tap(state, ACTOR,
                new ManualOpRequest.Flag(null, List.of(leader.getInstanceId()), true)));
        operations.apply(room, ACTOR, state -> operations.markUsed(state, ACTOR,
                new ManualOpRequest.Flag(null, List.of(weapon.getInstanceId()), null)));

        assertThat(seatA(room).getLeader().isTapped()).isTrue();
        assertThat(reload(room, weapon).isUsed()).isTrue();
    }

    // ================= ターン・フェイズ・ドロー(設計書 5-3 の10・11) =================

    @Test
    void ターンとフェイズは前後に動かせる() {
        ManualRoom room = new ManualRoom("TESTRM");

        operations.apply(room, ACTOR, state -> operations.setTurn(state, ACTOR,
                new ManualOpRequest.Turn(null, null, 3)));
        assertThat(room.getGameState().getTurnNumber()).isEqualTo(4);

        operations.apply(room, ACTOR, state -> operations.setTurn(state, ACTOR,
                new ManualOpRequest.Turn(null, null, -99)));
        assertThat(room.getGameState().getTurnNumber()).isEqualTo(1);

        operations.apply(room, ACTOR, state -> operations.setPhase(state, ACTOR,
                new ManualOpRequest.Phase(null, null, 2)));
        assertThat(room.getGameState().getPhase()).isEqualTo(ManualPhase.MANA_CHARGE);

        operations.apply(room, ACTOR, state -> operations.setPhase(state, ACTOR,
                new ManualOpRequest.Phase(null, null, -3)));
        assertThat(room.getGameState().getPhase()).isEqualTo(ManualPhase.END);
    }

    @Test
    void ドローは山札が尽きても敗北にしない() {
        ManualRoom room = new ManualRoom("TESTRM");
        put(room, ManualZone.DECK, "山1");

        operations.apply(room, ACTOR, state -> operations.draw(state, ACTOR,
                new ManualOpRequest.Draw(null, ManualSeatId.A, 3)));
        assertThat(seatA(room).zone(ManualZone.HAND)).hasSize(1);
        assertThat(seatA(room).zone(ManualZone.DECK)).isEmpty();

        operations.apply(room, ACTOR, state -> operations.draw(state, ACTOR,
                new ManualOpRequest.Draw(null, ManualSeatId.A, 1)));
        assertThat(room.getLog().get(1).event().text()).contains("山札が空");
        // ★デッキ切れ敗北は判定しない(設計書 5-1)
        assertThat(seatA(room).getLp()).isEqualTo(ManualGameService.INITIAL_LP);
    }

    // ================= Undo / Redo(設計書 5-6) =================

    @Test
    void Undoで盤面が戻りRedoで進む() {
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance target = put(room, ManualZone.HAND, "札1");

        operations.apply(room, ACTOR, state -> operations.move(state, ACTOR, new ManualOpRequest.Move(
                null, List.of(target.getInstanceId()),
                ManualSeatId.A, ManualZone.MANA, null, true)));
        assertThat(seatA(room).zone(ManualZone.MANA)).hasSize(1);

        operations.applyDirect(room, r -> operations.undo(r, ACTOR));
        assertThat(seatA(room).zone(ManualZone.HAND)).hasSize(1);
        assertThat(seatA(room).zone(ManualZone.MANA)).isEmpty();

        operations.applyDirect(room, r -> operations.redo(r, ACTOR));
        assertThat(seatA(room).zone(ManualZone.MANA)).hasSize(1);
        assertThat(reload(room, target).isFaceDown()).isTrue();
    }

    @Test
    void Undoでログは巻き戻らない() {
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance target = put(room, ManualZone.HAND, "札1");

        operations.apply(room, ACTOR, state -> operations.move(state, ACTOR, new ManualOpRequest.Move(
                null, List.of(target.getInstanceId()),
                ManualSeatId.A, ManualZone.TRASH, null, null)));
        assertThat(room.getLog()).hasSize(1);

        operations.applyDirect(room, r -> operations.undo(r, ACTOR));

        // ★ログは追記専用である(設計書 5-5・17b 2-5)
        assertThat(room.getLog()).hasSize(2);
        assertThat(room.getLog().get(1).event().text()).contains("取り消した");
        assertThat(seatA(room).zone(ManualZone.HAND)).hasSize(1);
    }

    @Test
    void 取り消せる操作が無ければ失敗する() {
        ManualRoom room = new ManualRoom("TESTRM");

        assertThatThrownBy(() -> operations.applyDirect(room, r -> operations.undo(r, ACTOR)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> operations.applyDirect(room, r -> operations.redo(r, ACTOR)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(room.getLog()).isEmpty();
    }

    @Test
    void ログのみの操作は履歴に積まない() {
        ManualRoom room = new ManualRoom("TESTRM");

        operations.applyDirect(room,
                ignored -> operations.note(ACTOR, new ManualOpRequest.Note(null, "検証メモ")));
        operations.applyDirect(room, ignored -> operations.declare(ACTOR, new ManualOpRequest.Declare(
                null, ManualSeatId.A, ManualDeclaration.WIN, "LPが0を下回った")));

        assertThat(room.getLog()).hasSize(2);
        // ★盤面に触らない操作は Undo の1手を消費しない
        assertThat(room.getHistory().canUndo()).isFalse();
    }

    @Test
    void 盤面を変える操作は1回につき1段だけ積む() {
        ManualRoom room = new ManualRoom("TESTRM");
        put(room, ManualZone.DECK, "山1");
        put(room, ManualZone.DECK, "山2");

        operations.apply(room, ACTOR, state -> operations.draw(state, ACTOR,
                new ManualOpRequest.Draw(null, ManualSeatId.A, 1)));
        operations.apply(room, ACTOR, state -> operations.draw(state, ACTOR,
                new ManualOpRequest.Draw(null, ManualSeatId.A, 1)));

        assertThat(room.getHistory().undoDepth()).isEqualTo(2);
        assertThat(room.getHistory().canRedo()).isFalse();
    }

    @Test
    void 失敗した操作は盤面もログも履歴も変えない() {
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance target = put(room, ManualZone.HAND, "札1");
        put(room, ManualZone.HAND, "札2");

        assertThatThrownBy(() -> operations.apply(room, ACTOR, state -> operations.move(state, ACTOR,
                new ManualOpRequest.Move(null,
                        List.of(target.getInstanceId(), "盤面に無いID"),
                        ManualSeatId.A, ManualZone.TRASH, null, null))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(seatA(room).zone(ManualZone.HAND)).hasSize(2);
        assertThat(seatA(room).zone(ManualZone.TRASH)).isEmpty();
        assertThat(room.getLog()).isEmpty();
        assertThat(room.getHistory().canUndo()).isFalse();
    }

    // ================= 共有ゾーン(Batch 20b 3章) =================

    @Test
    void 共有ゾーンへの移動は指定した席に依存しない() {
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance fromA = put(room, ManualZone.HAND, "A席の札");
        ManualCardInstance fromB = card("B席の札");
        room.getGameState().seat(ManualSeatId.B).zone(ManualZone.HAND).add(fromB);

        // A席を宛先に指定した移動と、B席を宛先に指定した移動が同じ入れ物へ入る
        operations.apply(room, ACTOR, state -> operations.move(state, ACTOR, new ManualOpRequest.Move(
                null, List.of(fromA.getInstanceId()),
                ManualSeatId.A, ManualZone.PLAY, null, null)));
        operations.apply(room, ACTOR, state -> operations.move(state, ACTOR, new ManualOpRequest.Move(
                null, List.of(fromB.getInstanceId()),
                ManualSeatId.B, ManualZone.PLAY, null, null)));

        assertThat(room.getGameState().getSharedZones().get(ManualZone.PLAY)).hasSize(2);
        assertThat(room.getGameState().cards(null, ManualZone.PLAY)).hasSize(2);
    }

    @Test
    void 共有ゾーンへ移すと表向きに正規化される() {
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance target = put(room, ManualZone.MANA, "裏の札");
        target.setFaceDown(true);

        operations.apply(room, ACTOR, state -> operations.move(state, ACTOR, new ManualOpRequest.Move(
                null, List.of(target.getInstanceId()),
                ManualSeatId.A, ManualZone.REVEAL, null, null)));

        assertThat(reload(room, target).isFaceDown()).isFalse();
    }

    @Test
    void 共有ゾーンの中身もUndoで戻る() {
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance target = put(room, ManualZone.HAND, "札1");

        operations.apply(room, ACTOR, state -> operations.move(state, ACTOR, new ManualOpRequest.Move(
                null, List.of(target.getInstanceId()),
                ManualSeatId.A, ManualZone.PLAY, null, null)));
        assertThat(room.getGameState().getSharedZones().get(ManualZone.PLAY)).hasSize(1);

        operations.applyDirect(room, r -> operations.undo(r, ACTOR));
        assertThat(room.getGameState().getSharedZones().get(ManualZone.PLAY)).isEmpty();
        assertThat(seatA(room).zone(ManualZone.HAND)).hasSize(1);

        operations.applyDirect(room, r -> operations.redo(r, ACTOR));
        assertThat(room.getGameState().getSharedZones().get(ManualZone.PLAY)).hasSize(1);
    }

    @Test
    void 共有ゾーンは席から引けない() {
        ManualRoom room = new ManualRoom("TESTRM");

        assertThatThrownBy(() -> seatA(room).zone(ManualZone.PLAY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ================= ウェポンの付け替え(Batch 20b 2-2) =================

    @Test
    void ウェポンを装備すると古いウェポンが墓地へ行く() {
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance old = put(room, ManualZone.WEAPON, "古い武器");
        ManualCardInstance fresh = put(room, ManualZone.HAND, "新しい武器");

        operations.apply(room, ACTOR, state -> operations.move(state, ACTOR, new ManualOpRequest.Move(
                null, List.of(fresh.getInstanceId()),
                ManualSeatId.A, ManualZone.WEAPON, null, null)));

        assertThat(seatA(room).zone(ManualZone.WEAPON)).hasSize(1);
        assertThat(seatA(room).zone(ManualZone.WEAPON).get(0).getInstanceId())
                .isEqualTo(fresh.getInstanceId());
        assertThat(seatA(room).zone(ManualZone.TRASH)).hasSize(1);
        assertThat(seatA(room).zone(ManualZone.TRASH).get(0).getInstanceId())
                .isEqualTo(old.getInstanceId());
    }

    @Test
    void 禁忌由来のウェポンは付け替えで消滅へ行く() {
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance old = put(room, ManualZone.WEAPON, "禁忌の武器");
        old.setFromTaboo(true);
        ManualCardInstance fresh = put(room, ManualZone.HAND, "新しい武器");

        operations.apply(room, ACTOR, state -> operations.move(state, ACTOR, new ManualOpRequest.Move(
                null, List.of(fresh.getInstanceId()),
                ManualSeatId.A, ManualZone.WEAPON, null, null)));

        assertThat(seatA(room).zone(ManualZone.TRASH)).isEmpty();
        assertThat(seatA(room).zone(ManualZone.LOST)).hasSize(1);
        assertThat(seatA(room).zone(ManualZone.LOST).get(0).getInstanceId())
                .isEqualTo(old.getInstanceId());
    }

    @Test
    void ウェポン枠が空なら付け替えは起きない() {
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance fresh = put(room, ManualZone.HAND, "武器");

        operations.apply(room, ACTOR, state -> operations.move(state, ACTOR, new ManualOpRequest.Move(
                null, List.of(fresh.getInstanceId()),
                ManualSeatId.A, ManualZone.WEAPON, null, null)));

        assertThat(seatA(room).zone(ManualZone.WEAPON)).hasSize(1);
        assertThat(seatA(room).zone(ManualZone.TRASH)).isEmpty();
        assertThat(seatA(room).zone(ManualZone.LOST)).isEmpty();
    }

    @Test
    void ウェポン枠の中で動かしても自分自身は墓地へ行かない() {
        ManualRoom room = new ManualRoom("TESTRM");
        ManualCardInstance weapon = put(room, ManualZone.WEAPON, "武器");

        operations.apply(room, ACTOR, state -> operations.move(state, ACTOR, new ManualOpRequest.Move(
                null, List.of(weapon.getInstanceId()),
                ManualSeatId.A, ManualZone.WEAPON, 0, null)));

        assertThat(seatA(room).zone(ManualZone.WEAPON)).hasSize(1);
        assertThat(seatA(room).zone(ManualZone.TRASH)).isEmpty();
    }

    // ================= ヘルパ =================

    /** 突合しないカードを1枚作る。★カードIDのリテラルを書かないための道具である。 */
    private ManualCardInstance card(String name) {
        return ManualCardInstance.unresolved(name, "image-" + name);
    }

    /** 印刷値のリセットを検証するために、attack/hp が空欄でない突合済みミニオンを1枚拾う。 */
    private ManualCardMaster resolvedMinion() {
        return cards.getAllCards().stream()
                .filter(candidate -> candidate.type() == ManualCardType.MINION)
                .filter(candidate -> candidate.attack() != null && candidate.hp() != null)
                .findFirst().orElseThrow();
    }

    /** 印刷値のリセットを検証するために、attack が空欄でない突合済みウェポンを1枚拾う。 */
    private ManualCardMaster resolvedWeapon() {
        return cards.getAllCards().stream()
                .filter(candidate -> candidate.type() == ManualCardType.WEAPON)
                .filter(candidate -> candidate.attack() != null)
                .findFirst().orElseThrow();
    }

    private ManualSeat seatA(ManualRoom room) {
        return room.getGameState().seat(ManualSeatId.A);
    }

    private ManualCardInstance put(ManualRoom room, ManualZone zone, String name) {
        ManualCardInstance instance = card(name);
        seatA(room).zone(zone).add(instance);
        return instance;
    }

    /** 現在の盤面から同じ instanceId のカードを引き直す。Undo や失敗の後に使う。 */
    private ManualCardInstance reload(ManualRoom room, ManualCardInstance card) {
        return ManualBoardIndex.require(room.getGameState(), card.getInstanceId()).card();
    }
}
