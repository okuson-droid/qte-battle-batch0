package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.manual.ManualActor;
import com.example.qte.manual.ManualDeckImport;
import com.example.qte.manual.ManualGameService;
import com.example.qte.manual.ManualCardInstance;
import com.example.qte.manual.ManualHistory;
import com.example.qte.manual.ManualLogEntry;
import com.example.qte.manual.ManualLogEvent;
import com.example.qte.manual.ManualLogKind;
import com.example.qte.manual.ManualLogRenderer;
import com.example.qte.manual.ManualOccupant;
import com.example.qte.manual.ManualOccupantRole;
import com.example.qte.manual.ManualOpRequest;
import com.example.qte.manual.ManualOperationService;
import com.example.qte.manual.ManualRoom;
import com.example.qte.manual.ManualRoomOptions;
import com.example.qte.manual.ManualRoomType;
import com.example.qte.manual.ManualSeatId;
import com.example.qte.manual.ManualSpectatorView;
import com.example.qte.manual.ManualStartMethod;
import com.example.qte.manual.ManualStartService;
import com.example.qte.manual.ManualViewpoint;
import com.example.qte.manual.ManualZone;
import com.example.qte.manual.view.ManualGameView;
import com.example.qte.manual.view.ManualSeatView;
import com.example.qte.manual.view.ManualViewBuilder;

/**
 * Batch 21a のテスト(設計書 3章 視点フィルタ / 5章 ログ / 6章 権限 / 1-4 無人部屋)。
 *
 * <h2>★このテストが守っているもの</h2>
 * 21a で足したのは<b>情報保護と操作権限</b>であり、どちらも
 * 「壊れていても画面上は普通に動いてしまう」種類の機能である。
 * 相手の手札がビューに載っていても、クライアントが描かなければ気づけない。
 * したがって<b>ビューとログの中身をサーバ側で直接確かめる</b>形にしてある。
 *
 * ★カードIDを文字列リテラルで書かない(batch17a-design-notes 3-2)。
 * すべて突合しないカード({@link ManualCardInstance#unresolved})で組み立てる。
 */
@SpringBootTest
class ManualVersusTest {

    @Autowired
    ManualOperationService operations;

    @Autowired
    ManualViewBuilder viewBuilder;

    @Autowired
    ManualLogRenderer logRenderer;

    /** ★Batch 23: 開始シーケンス(総合ルール 2-5) */
    @Autowired
    ManualStartService startService;

    @Autowired
    ManualGameService gameService;

    // ================= 3章 視点フィルタ =================

    @Test
    void 対戦部屋では相手の手札がビューに載らず枚数だけが届く() {
        ManualRoom room = versusRoom();
        ManualOccupant a = room.join("あかり", ManualSeatId.A);
        ManualOccupant b = room.join("ばんり", ManualSeatId.B);
        put(room, ManualSeatId.B, ManualZone.HAND, "相手の手札1");
        put(room, ManualSeatId.B, ManualZone.HAND, "相手の手札2");

        ManualSeatView seenByA = viewBuilder.build(room, a).seatB();
        // ★キーそのものが無いことが「見えない」の表現である(3-3)
        assertThat(seenByA.zones()).doesNotContainKey(ManualZone.HAND);
        assertThat(seenByA.counts().get(ManualZone.HAND)).isEqualTo(2);

        // 本人には見える
        ManualSeatView seenByB = viewBuilder.build(room, b).seatB();
        assertThat(seenByB.zones().get(ManualZone.HAND)).hasSize(2);
    }

    @Test
    void 公開ゾーンは相手にも中身が見える() {
        ManualRoom room = versusRoom();
        ManualOccupant a = room.join("あかり", ManualSeatId.A);
        room.join("ばんり", ManualSeatId.B);
        put(room, ManualSeatId.B, ManualZone.FIELD, "相手のミニオン");
        put(room, ManualSeatId.B, ManualZone.TRASH, "相手の墓地");

        ManualSeatView seatB = viewBuilder.build(room, a).seatB();
        assertThat(seatB.zones().get(ManualZone.FIELD)).hasSize(1);
        assertThat(seatB.zones().get(ManualZone.TRASH)).hasSize(1);
        assertThat(seatB.zones()).doesNotContainKey(ManualZone.DECK);
        assertThat(seatB.zones()).doesNotContainKey(ManualZone.TABOO);
        assertThat(seatB.zones()).doesNotContainKey(ManualZone.PRIVATE);
    }

    @Test
    void マナは表向きだけ見え裏向きは枚数として届く() {
        ManualRoom room = versusRoom();
        ManualOccupant a = room.join("あかり", ManualSeatId.A);
        room.join("ばんり", ManualSeatId.B);
        put(room, ManualSeatId.B, ManualZone.MANA, "表マナ");
        ManualCardInstance hidden = put(room, ManualSeatId.B, ManualZone.MANA, "裏マナ");
        hidden.setFaceDown(true);

        ManualSeatView seatB = viewBuilder.build(room, a).seatB();
        assertThat(seatB.zones().get(ManualZone.MANA)).hasSize(1);
        assertThat(seatB.zones().get(ManualZone.MANA).get(0).name()).isEqualTo("表マナ");
        assertThat(seatB.counts().get(ManualZone.MANA)).isEqualTo(2);
        assertThat(seatB.manaFaceDownCount()).isEqualTo(1);
        // ★MPは相手にも数値として公開する(3-3)
        assertThat(seatB.mp()).isEqualTo(2);
    }

    @Test
    void リーダーは相手にも見える() {
        ManualRoom room = versusRoom();
        ManualOccupant a = room.join("あかり", ManualSeatId.A);
        room.join("ばんり", ManualSeatId.B);
        room.getGameState().seat(ManualSeatId.B)
                .setLeader(ManualCardInstance.unresolved("相手のリーダー", null));

        assertThat(viewBuilder.build(room, a).seatB().leader()).isNotNull();
    }

    @Test
    void 全見え観戦は相手の手札まで見えるが公開のみ観戦は見えない() {
        ManualRoom room = versusRoom();
        room.join("あかり", ManualSeatId.A);
        ManualOccupant watcher = room.join("かんきゃく", null);
        put(room, ManualSeatId.A, ManualZone.HAND, "手札");

        // 既定は「公開のみ」である(入ってきた人にいきなり手札を見せない)
        assertThat(watcher.getSpectatorView()).isEqualTo(ManualSpectatorView.PUBLIC_ONLY);
        assertThat(viewBuilder.build(room, watcher).seatA().zones())
                .doesNotContainKey(ManualZone.HAND);

        watcher.setSpectatorView(ManualSpectatorView.ALL);
        assertThat(viewBuilder.build(room, watcher).seatA().zones().get(ManualZone.HAND))
                .hasSize(1);
    }

    @Test
    void 全公開部屋ではフィルタが一切効かない() {
        ManualRoom room = new ManualRoom("OPENRM");
        ManualOccupant a = room.join("ひとり", ManualSeatId.A);
        put(room, ManualSeatId.B, ManualZone.HAND, "B席の手札");

        assertThat(viewBuilder.build(room, a).seatB().zones().get(ManualZone.HAND)).hasSize(1);
        assertThat(viewBuilder.build(room, a).roomType()).isEqualTo(ManualRoomType.OPEN);
    }

    @Test
    void 観戦者の視点は在室者リストに出るがプレイヤーには出ない() {
        ManualRoom room = versusRoom();
        ManualOccupant a = room.join("あかり", ManualSeatId.A);
        ManualOccupant watcher = room.join("かんきゃく", null);
        watcher.setSpectatorView(ManualSpectatorView.ALL);

        ManualGameView view = viewBuilder.build(room, a);
        assertThat(view.occupants()).anySatisfy(o -> {
            assertThat(o.displayName()).isEqualTo("かんきゃく");
            assertThat(o.role()).isEqualTo(ManualOccupantRole.SPECTATOR);
            // ★全見えの観戦者が居ることをプレイヤーが知っていられること(2-3)
            assertThat(o.spectatorView()).isEqualTo(ManualSpectatorView.ALL);
        });
        assertThat(view.occupants()).anySatisfy(o -> {
            assertThat(o.displayName()).isEqualTo("あかり");
            assertThat(o.seatId()).isEqualTo(ManualSeatId.A);
            assertThat(o.spectatorView()).isNull();
        });
    }

    // ================= 5章 ログのマスク =================

    @Test
    void 手札から場への移動は移動先が公開なので相手にも名前が出る() {
        ManualRoom room = versusRoom();
        ManualOccupant a = room.join("あかり", ManualSeatId.A);
        room.join("ばんり", ManualSeatId.B);
        ManualCardInstance card = put(room, ManualSeatId.A, ManualZone.HAND, "見せる札");

        ManualActor actorA = ManualActor.of(room, a);
        operations.apply(room, actorA, state -> operations.move(state, actorA,
                new ManualOpRequest.Move(a.getOccupantId(), List.of(card.getInstanceId()),
                        ManualSeatId.A, ManualZone.FIELD, null, null)));

        assertThat(renderFor(room, ManualSeatId.B)).contains("見せる札");
    }

    @Test
    void 手札から山札への移動は両方非公開なので名前が隠れる() {
        ManualRoom room = versusRoom();
        ManualOccupant a = room.join("あかり", ManualSeatId.A);
        room.join("ばんり", ManualSeatId.B);
        ManualCardInstance card = put(room, ManualSeatId.A, ManualZone.HAND, "秘密の札");

        ManualActor actorA = ManualActor.of(room, a);
        operations.apply(room, actorA, state -> operations.move(state, actorA,
                new ManualOpRequest.Move(a.getOccupantId(), List.of(card.getInstanceId()),
                        ManualSeatId.A, ManualZone.DECK, null, null)));

        String forOpponent = renderFor(room, ManualSeatId.B);
        assertThat(forOpponent).doesNotContain("秘密の札");
        // ★行自体は配る。「何かが1枚動いた」は公開情報である(5-1)
        assertThat(forOpponent).contains("手札").contains("山札").contains("1枚");
        // 本人には名前が出る
        assertThat(renderFor(room, ManualSeatId.A)).contains("秘密の札");
    }

    @Test
    void 裏マナから墓地への移動は墓地が公開なので名前が出る() {
        ManualRoom room = versusRoom();
        ManualOccupant a = room.join("あかり", ManualSeatId.A);
        room.join("ばんり", ManualSeatId.B);
        ManualCardInstance card = put(room, ManualSeatId.A, ManualZone.MANA, "禁忌の代償");
        card.setFaceDown(true);

        ManualActor actorA = ManualActor.of(room, a);
        operations.apply(room, actorA, state -> operations.move(state, actorA,
                new ManualOpRequest.Move(a.getOccupantId(), List.of(card.getInstanceId()),
                        ManualSeatId.A, ManualZone.TRASH, null, null)));

        assertThat(renderFor(room, ManualSeatId.B)).contains("禁忌の代償");
    }

    @Test
    void 手札のカードの数値変更は事実だけ伝わり前後の値は隠れる() {
        ManualRoom room = versusRoom();
        ManualOccupant a = room.join("あかり", ManualSeatId.A);
        room.join("ばんり", ManualSeatId.B);
        ManualCardInstance card = put(room, ManualSeatId.A, ManualZone.HAND, "秘密の札");
        card.setAttack(3);

        ManualActor actorA = ManualActor.of(room, a);
        operations.apply(room, actorA, state -> operations.changeStats(state, actorA,
                new ManualOpRequest.Stat(a.getOccupantId(), card.getInstanceId(),
                        null, null, 2, null)));

        String forOpponent = renderFor(room, ManualSeatId.B);
        assertThat(forOpponent).doesNotContain("秘密の札").doesNotContain("5");
        assertThat(forOpponent).contains("数値を変更した");
        assertThat(renderFor(room, ManualSeatId.A)).contains("秘密の札").contains("5");
    }

    @Test
    void メモと宣言は視点によらず全員に同じ本文が出る() {
        ManualRoom room = versusRoom();
        ManualOccupant a = room.join("あかり", ManualSeatId.A);
        room.join("ばんり", ManualSeatId.B);

        ManualActor actorA = ManualActor.of(room, a);
        operations.applyDirect(room, r -> operations.note(actorA,
                new ManualOpRequest.Note(a.getOccupantId(), "スペルを解決した")));

        assertThat(renderFor(room, ManualSeatId.A)).contains("スペルを解決した");
        assertThat(renderFor(room, ManualSeatId.B)).contains("スペルを解決した");
    }

    @Test
    void ダウンロードと配信は同じレンダラを通るので完全ログの裏口が無い() {
        ManualRoom room = versusRoom();
        ManualOccupant a = room.join("あかり", ManualSeatId.A);
        ManualOccupant b = room.join("ばんり", ManualSeatId.B);
        ManualCardInstance card = put(room, ManualSeatId.A, ManualZone.HAND, "秘密の札");

        ManualActor actorA = ManualActor.of(room, a);
        operations.apply(room, actorA, state -> operations.move(state, actorA,
                new ManualOpRequest.Move(a.getOccupantId(), List.of(card.getInstanceId()),
                        ManualSeatId.A, ManualZone.DECK, null, null)));

        // 配信(ビュー)側の1行
        String broadcast = viewBuilder.build(room, b).log().get(0).text();
        // ダウンロード側の1行(ManualLobbyController が使うのと同じ経路)
        ManualLogEntry entry = room.getLog().get(0);
        String download = logRenderer.render(entry.event(), ManualViewpoint.of(room, b));
        assertThat(download).isEqualTo(broadcast);
        assertThat(download).doesNotContain("秘密の札");
    }

    // ================= 6章 操作権限 =================

    @Test
    void 相手のカードは動かせないが相手のゾーンへ落とすことはできる() {
        ManualRoom room = versusRoom();
        ManualOccupant a = room.join("あかり", ManualSeatId.A);
        room.join("ばんり", ManualSeatId.B);
        ManualCardInstance mine = put(room, ManualSeatId.A, ManualZone.HAND, "自分の札");
        ManualCardInstance theirs = put(room, ManualSeatId.B, ManualZone.FIELD, "相手のミニオン");

        ManualActor actorA = ManualActor.of(room, a);

        // ★相手のカードを動かすのは不可(6-1)
        assertThatThrownBy(() -> operations.apply(room, actorA, state -> operations.move(state,
                actorA, new ManualOpRequest.Move(a.getOccupantId(),
                        List.of(theirs.getInstanceId()),
                        ManualSeatId.B, ManualZone.TRASH, null, null))))
                .hasMessageContaining("相手のカード");

        // ★相手のゾーンへ落とすのは可(代行操作として普通に起きる)
        operations.apply(room, actorA, state -> operations.move(state, actorA,
                new ManualOpRequest.Move(a.getOccupantId(), List.of(mine.getInstanceId()),
                        ManualSeatId.B, ManualZone.FIELD, null, null)));
        assertThat(room.getGameState().seat(ManualSeatId.B).zone(ManualZone.FIELD)).hasSize(2);
    }

    @Test
    void 相手のカードの数値と札とタップは変更できない() {
        ManualRoom room = versusRoom();
        ManualOccupant a = room.join("あかり", ManualSeatId.A);
        room.join("ばんり", ManualSeatId.B);
        ManualCardInstance theirs = put(room, ManualSeatId.B, ManualZone.FIELD, "相手のミニオン");
        ManualActor actorA = ManualActor.of(room, a);

        assertThatThrownBy(() -> operations.apply(room, actorA, state -> operations.changeStats(
                state, actorA, new ManualOpRequest.Stat(a.getOccupantId(),
                        theirs.getInstanceId(), 9, null, null, null))))
                .hasMessageContaining("相手のカード");
        assertThatThrownBy(() -> operations.apply(room, actorA, state -> operations.addLabel(
                state, actorA, new ManualOpRequest.Label(a.getOccupantId(),
                        theirs.getInstanceId(), "速攻"))))
                .hasMessageContaining("相手のカード");
        assertThatThrownBy(() -> operations.apply(room, actorA, state -> operations.tap(
                state, actorA, new ManualOpRequest.Flag(a.getOccupantId(),
                        List.of(theirs.getInstanceId()), true))))
                .hasMessageContaining("相手のカード");
    }

    @Test
    void 相手の席のドローとシャッフルとLPは操作できない() {
        ManualRoom room = versusRoom();
        ManualOccupant a = room.join("あかり", ManualSeatId.A);
        room.join("ばんり", ManualSeatId.B);
        put(room, ManualSeatId.B, ManualZone.DECK, "相手の山札");
        ManualActor actorA = ManualActor.of(room, a);

        assertThatThrownBy(() -> operations.apply(room, actorA, state -> operations.draw(state,
                actorA, new ManualOpRequest.Draw(a.getOccupantId(), ManualSeatId.B, 1))))
                .hasMessageContaining("席B");
        assertThatThrownBy(() -> operations.apply(room, actorA, state -> operations.changeLp(state,
                actorA, new ManualOpRequest.Lp(a.getOccupantId(), ManualSeatId.B, null, -3))))
                .hasMessageContaining("席B");
        assertThatThrownBy(() -> operations.apply(room, actorA, state -> operations.shuffleDeck(
                state, actorA, new ManualOpRequest.Seat(a.getOccupantId(), ManualSeatId.B))))
                .hasMessageContaining("席B");
    }

    @Test
    void 観戦者は盤面を一切操作できない() {
        ManualRoom room = versusRoom();
        room.join("あかり", ManualSeatId.A);
        ManualOccupant watcher = room.join("かんきゃく", null);
        ManualCardInstance card = put(room, ManualSeatId.A, ManualZone.FIELD, "ミニオン");
        ManualActor actor = ManualActor.of(room, watcher);

        assertThatThrownBy(() -> operations.apply(room, actor, state -> operations.move(state,
                actor, new ManualOpRequest.Move(watcher.getOccupantId(),
                        List.of(card.getInstanceId()),
                        ManualSeatId.A, ManualZone.TRASH, null, null))))
                .hasMessageContaining("観戦者");
        assertThat(room.getGameState().seat(ManualSeatId.A).zone(ManualZone.FIELD)).hasSize(1);
    }

    @Test
    void 全公開部屋では席が無くても操作できる() {
        ManualRoom room = new ManualRoom("OPENRM");
        ManualOccupant watcher = room.join("みるひと", null);
        ManualCardInstance card = put(room, ManualSeatId.B, ManualZone.HAND, "B席の札");
        ManualActor actor = ManualActor.of(room, watcher);

        operations.apply(room, actor, state -> operations.move(state, actor,
                new ManualOpRequest.Move(watcher.getOccupantId(), List.of(card.getInstanceId()),
                        ManualSeatId.B, ManualZone.FIELD, null, null)));
        assertThat(room.getGameState().seat(ManualSeatId.B).zone(ManualZone.FIELD)).hasSize(1);
    }

    // ================= 6-2 共有ゾーンの所有 =================

    @Test
    void 共有ゾーンへ入れると席が記録され出ると消える() {
        ManualRoom room = versusRoom();
        ManualOccupant a = room.join("あかり", ManualSeatId.A);
        room.join("ばんり", ManualSeatId.B);
        ManualCardInstance card = put(room, ManualSeatId.A, ManualZone.HAND, "公開する札");
        ManualActor actorA = ManualActor.of(room, a);

        operations.apply(room, actorA, state -> operations.move(state, actorA,
                new ManualOpRequest.Move(a.getOccupantId(), List.of(card.getInstanceId()),
                        null, ManualZone.REVEAL, null, null)));
        assertThat(reload(room, card).getPlacedBySeat()).isEqualTo(ManualSeatId.A);

        operations.apply(room, actorA, state -> operations.move(state, actorA,
                new ManualOpRequest.Move(a.getOccupantId(), List.of(card.getInstanceId()),
                        ManualSeatId.A, ManualZone.HAND, null, null)));
        // ★出るときのクリアを忘れると、次に別の席が使えなくなる
        assertThat(reload(room, card).getPlacedBySeat()).isNull();
    }

    @Test
    void 共有ゾーンの所有はUndoでも巻き戻る() {
        ManualRoom room = versusRoom();
        ManualOccupant a = room.join("あかり", ManualSeatId.A);
        room.join("ばんり", ManualSeatId.B);
        ManualCardInstance card = put(room, ManualSeatId.A, ManualZone.HAND, "公開する札");
        ManualActor actorA = ManualActor.of(room, a);

        operations.apply(room, actorA, state -> operations.move(state, actorA,
                new ManualOpRequest.Move(a.getOccupantId(), List.of(card.getInstanceId()),
                        null, ManualZone.REVEAL, null, null)));
        assertThat(reload(room, card).getPlacedBySeat()).isEqualTo(ManualSeatId.A);

        operations.applyDirect(room, r -> operations.undo(r, actorA));
        // ★copy() が placedBySeat を運んでいないと、盤面だけ戻って所有が残る(設計書 10章)
        assertThat(room.getGameState().seat(ManualSeatId.A).zone(ManualZone.HAND)).hasSize(1);
        assertThat(reload(room, card).getPlacedBySeat()).isNull();
    }

    @Test
    void 対戦部屋では相手が共有ゾーンへ置いたカードを動かせない() {
        ManualRoom room = versusRoom();
        ManualOccupant a = room.join("あかり", ManualSeatId.A);
        ManualOccupant b = room.join("ばんり", ManualSeatId.B);
        ManualCardInstance card = put(room, ManualSeatId.A, ManualZone.HAND, "Aの札");
        ManualActor actorA = ManualActor.of(room, a);
        ManualActor actorB = ManualActor.of(room, b);

        operations.apply(room, actorA, state -> operations.move(state, actorA,
                new ManualOpRequest.Move(a.getOccupantId(), List.of(card.getInstanceId()),
                        null, ManualZone.PLAY, null, null)));

        assertThatThrownBy(() -> operations.apply(room, actorB, state -> operations.move(state,
                actorB, new ManualOpRequest.Move(b.getOccupantId(),
                        List.of(card.getInstanceId()),
                        ManualSeatId.B, ManualZone.TRASH, null, null))))
                .hasMessageContaining("置いた席");

        // 置いた本人は動かせる
        operations.apply(room, actorA, state -> operations.move(state, actorA,
                new ManualOpRequest.Move(a.getOccupantId(), List.of(card.getInstanceId()),
                        ManualSeatId.A, ManualZone.TRASH, null, null)));
        assertThat(room.getGameState().seat(ManualSeatId.A).zone(ManualZone.TRASH)).hasSize(1);
    }

    @Test
    void 全公開部屋では共有ゾーンの所有を記録するが制限しない() {
        ManualRoom room = new ManualRoom("OPENRM");
        ManualOccupant a = room.join("ひとり", ManualSeatId.A);
        ManualCardInstance card = put(room, ManualSeatId.A, ManualZone.HAND, "札");
        ManualActor actorA = ManualActor.of(room, a);

        operations.apply(room, actorA, state -> operations.move(state, actorA,
                new ManualOpRequest.Move(a.getOccupantId(), List.of(card.getInstanceId()),
                        null, ManualZone.PLAY, null, null)));
        assertThat(reload(room, card).getPlacedBySeat()).isEqualTo(ManualSeatId.A);

        // ★1人で両席を操作する運用を妨げない(6-2)
        ManualOccupant other = room.join("もうひとり", ManualSeatId.B);
        ManualActor actorB = ManualActor.of(room, other);
        operations.apply(room, actorB, state -> operations.move(state, actorB,
                new ManualOpRequest.Move(other.getOccupantId(), List.of(card.getInstanceId()),
                        ManualSeatId.B, ManualZone.TRASH, null, null)));
        assertThat(room.getGameState().seat(ManualSeatId.B).zone(ManualZone.TRASH)).hasSize(1);
    }

    // ================= 6-3 Undo / Redo =================

    @Test
    void 対戦部屋のUndoは深さ1でRedoが無い() {
        ManualRoom room = versusRoom();
        ManualOccupant a = room.join("あかり", ManualSeatId.A);
        room.join("ばんり", ManualSeatId.B);
        ManualCardInstance first = put(room, ManualSeatId.A, ManualZone.HAND, "1枚目");
        ManualCardInstance second = put(room, ManualSeatId.A, ManualZone.HAND, "2枚目");
        ManualActor actorA = ManualActor.of(room, a);

        moveToField(room, actorA, a, first);
        moveToField(room, actorA, a, second);
        assertThat(room.getHistory().undoDepth()).isEqualTo(ManualHistory.VERSUS_DEPTH);

        operations.applyDirect(room, r -> operations.undo(r, actorA));
        assertThatThrownBy(() -> operations.applyDirect(room, r -> operations.redo(r, actorA)))
                .hasMessageContaining("Redo");
    }

    @Test
    void 対戦部屋のUndoは直前に操作した席しか使えない() {
        ManualRoom room = versusRoom();
        ManualOccupant a = room.join("あかり", ManualSeatId.A);
        ManualOccupant b = room.join("ばんり", ManualSeatId.B);
        ManualCardInstance card = put(room, ManualSeatId.A, ManualZone.HAND, "Aの札");
        ManualActor actorA = ManualActor.of(room, a);
        ManualActor actorB = ManualActor.of(room, b);

        moveToField(room, actorA, a, card);

        assertThatThrownBy(() -> operations.applyDirect(room, r -> operations.undo(r, actorB)))
                .hasMessageContaining("席A");
        // ★ボタンの活性(ビュー)と実際の可否が同じ判定を通っていること
        assertThat(viewBuilder.build(room, b).canUndo()).isFalse();
        assertThat(viewBuilder.build(room, a).canUndo()).isTrue();

        operations.applyDirect(room, r -> operations.undo(r, actorA));
        assertThat(room.getGameState().seat(ManualSeatId.A).zone(ManualZone.HAND)).hasSize(1);
    }

    @Test
    void 全公開部屋のUndoは深さ200のままでRedoも使える() {
        ManualRoom room = new ManualRoom("OPENRM");
        assertThat(room.getHistory().isRedoEnabled()).isTrue();
        assertThat(ManualHistory.MAX_DEPTH).isEqualTo(200);
    }

    // ================= 2章 席 =================

    @Test
    void 埋まっている席には座れず切断中でも席は保持される() {
        ManualRoom room = versusRoom();
        ManualOccupant a = room.join("あかり", ManualSeatId.A);
        ManualOccupant watcher = room.join("かんきゃく", null);

        assertThatThrownBy(() -> room.takeSeat(watcher, ManualSeatId.A))
                .hasMessageContaining("あかり");

        // ★切断猶予中も席は空かない(2-4)
        a.setConnected(false);
        a.setDisconnectedAt(Instant.now());
        assertThatThrownBy(() -> room.takeSeat(watcher, ManualSeatId.A))
                .hasMessageContaining("切断中");

        // 空いている席へは座れる(観戦からの昇格)
        room.takeSeat(watcher, ManualSeatId.B);
        assertThat(watcher.getRole()).isEqualTo(ManualOccupantRole.PLAYER);
    }

    @Test
    void 席を立つと観戦者になり役割も追従する() {
        ManualRoom room = versusRoom();
        ManualOccupant a = room.join("あかり", ManualSeatId.A);
        assertThat(a.getRole()).isEqualTo(ManualOccupantRole.PLAYER);

        room.standUp(a);
        assertThat(a.getSeatId()).isNull();
        // ★役割は席から導く。2つを別々に持たないので食い違いようがない
        assertThat(a.getRole()).isEqualTo(ManualOccupantRole.SPECTATOR);
        assertThat(room.occupantOfSeat(ManualSeatId.A)).isEmpty();
    }

    @Test
    void 観戦を許可しない部屋では観戦者として入室できない() {
        ManualRoom room = new ManualRoom("NOWATCH", new ManualRoomOptions(
                "観戦なし", ManualRoomType.VERSUS, false, false));
        assertThatThrownBy(() -> room.join("かんきゃく", null))
                .hasMessageContaining("観戦を許可していません");
    }

    @Test
    void 対戦部屋は部屋名と入室者名を必須にする() {
        assertThatThrownBy(() -> new ManualRoomOptions(
                "  ", ManualRoomType.VERSUS, true, false))
                .hasMessageContaining("部屋名");
        ManualRoom room = versusRoom();
        assertThatThrownBy(() -> room.join("  ", ManualSeatId.A))
                .hasMessageContaining("名前");
    }

    // ================= 1-4 無人部屋 =================

    @Test
    void 無人になった時刻が記録され在室者が戻ると解除される() {
        ManualRoom room = versusRoom();
        // 作った直後は誰も居ないので、その時点から数え始める
        assertThat(room.emptyFor(Instant.now().plus(Duration.ofMinutes(6))))
                .isGreaterThanOrEqualTo(Duration.ofMinutes(5));

        ManualOccupant a = room.join("あかり", ManualSeatId.A);
        assertThat(room.emptyFor(Instant.now().plus(Duration.ofMinutes(6))))
                .isEqualTo(Duration.ZERO);

        room.leave(a.getOccupantId());
        assertThat(room.emptyFor(Instant.now().plus(Duration.ofMinutes(6))))
                .isGreaterThanOrEqualTo(Duration.ofMinutes(5));
    }

    // ================= ★Batch 23: 開始シーケンス(21c の先攻決め4件を書き換え) =================
    //
    // ★21c は「ヘッダの先攻決めボタン」を検証していた。23 でボタンもエンドポイントも
    //   廃止し、先攻を決める経路を1本にした(23 設計書 3-4・P14)ため、
    //   落ちた項目を直すのではなく<b>新しい仕様に合わせて書き換えている</b>。
    // ★開始シーケンスそのものの検証は ManualStartSequenceTest が持つ。
    //   ここに残すのは 21a の主題(視点・ログ・権限)と交わる4件だけである。

    @Test
    void 開始のログは視点で変わらずそのまま全員に出る() {
        ManualRoom room = startedRoom();
        ManualLogEntry entry = room.getLog().get(room.getLog().size() - 1);
        // ★START は plain 種別である(5-3)。出目も枚数も両者に同じ内容が見えてよい
        assertThat(entry.event().kind()).isEqualTo(ManualLogKind.START);
        assertThat(entry.event().kind().isPlain()).isTrue();
        assertThat(logRenderer.render(entry.event(),
                new ManualViewpoint(room.getType(), ManualSeatId.B, ManualSpectatorView.PUBLIC_ONLY)))
                .isEqualTo(entry.event().text());
    }

    @Test
    void 先攻の決定は開始のログ1本に統合されている() {
        ManualRoom room = startedRoom();
        // ★21c の FIRST_PLAYER は START に吸収した(3-4)。
        //   経路が2本あると「どちらが正か」が決まらなくなる
        assertThat(ManualLogKind.values())
                .noneMatch(kind -> kind.name().equals("FIRST_PLAYER"));
        assertThat(room.getLog().stream().map(e -> e.event().kind()))
                .contains(ManualLogKind.START);
        assertThat(room.getFirstSeat()).isNotNull();
    }

    @Test
    void 観戦者はゲームを開始できない() {
        ManualRoom room = versusRoom();
        room.setCreatorSeat(ManualSeatId.A);
        room.join("あかり", ManualSeatId.A);
        room.join("ばんり", ManualSeatId.B);
        ManualOccupant watcher = room.join("みるひと", null);
        for (ManualSeatId seatId : ManualSeatId.values()) {
            gameService.loadDeck(room, seatId, testDeck("デッキ" + seatId));
        }
        assertThatThrownBy(() -> startService.begin(room, ManualActor.of(room, watcher)))
                .hasMessageContaining("観戦者");
    }

    @Test
    void 開始シーケンス中でもメモと勝敗宣言は通る() {
        ManualRoom room = versusRoom();
        room.setCreatorSeat(ManualSeatId.A);
        ManualOccupant a = room.join("あかり", ManualSeatId.A);
        room.join("ばんり", ManualSeatId.B);
        for (ManualSeatId seatId : ManualSeatId.values()) {
            gameService.loadDeck(room, seatId, testDeck("デッキ" + seatId));
        }
        ManualActor actor = ManualActor.of(room, a);
        startService.begin(room, actor);

        // ★7-2「止めないもの」。棄却は apply(盤面を変える操作)だけに掛かっている
        assertThat(operations.note(actor,
                new ManualOpRequest.Note(a.getOccupantId(), "開始前のメモ")).kind())
                .isEqualTo(ManualLogKind.NOTE);
        assertThat(operations.declare(actor, new ManualOpRequest.Declare(
                a.getOccupantId(), ManualSeatId.A,
                com.example.qte.manual.ManualDeclaration.CONCEDE, null)).kind())
                .isEqualTo(ManualLogKind.DECLARE);
    }

    // ================= 補助 =================

    private ManualRoom versusRoom() {
        return new ManualRoom("VSROOM", new ManualRoomOptions(
                "対戦部屋", ManualRoomType.VERSUS, true, false));
    }

    /**
     * ★Batch 23: 開始シーケンスを PLAYING まで進めた部屋。
     * ★カードIDをリテラルで書かないため、突合しないカードだけのデッキを使う(17a 3-2)。
     */
    private ManualRoom startedRoom() {
        ManualRoom room = versusRoom();
        room.setCreatorSeat(ManualSeatId.A);
        ManualOccupant a = room.join("あかり", ManualSeatId.A);
        ManualOccupant b = room.join("ばんり", ManualSeatId.B);
        for (ManualSeatId seatId : ManualSeatId.values()) {
            gameService.loadDeck(room, seatId, testDeck("デッキ" + seatId));
        }
        ManualActor actor = ManualActor.of(room, a);
        startService.begin(room, actor);
        startService.chooseMethod(room, actor, ManualStartMethod.FIRST);
        startService.mulligan(room, actor, ManualSeatId.A, List.of());
        startService.mulligan(room, ManualActor.of(room, b), ManualSeatId.B, List.of());
        return room;
    }

    private ManualDeckImport testDeck(String name) {
        List<ManualDeckImport.Entry> main = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            main.add(new ManualDeckImport.Entry(null, "%s-カード%d".formatted(name, i), null));
        }
        return new ManualDeckImport(name,
                new ManualDeckImport.Entry(null, name + "-リーダー", null),
                main, List.of(), List.of());
    }

    /** 突合しないカードを1枚ゾーンへ置く。★カードIDをリテラルで書かないため(17a 3-2)。 */
    private ManualCardInstance put(ManualRoom room, ManualSeatId seatId, ManualZone zone,
            String name) {
        ManualCardInstance card = ManualCardInstance.unresolved(name, null);
        room.getGameState().seat(seatId).zone(zone).add(card);
        return card;
    }

    /** 現在の盤面から同じ instanceId のカードを引き直す。Undo の後に使う。 */
    private ManualCardInstance reload(ManualRoom room, ManualCardInstance card) {
        return com.example.qte.manual.ManualBoardIndex
                .require(room.getGameState(), card.getInstanceId()).card();
    }

    private void moveToField(ManualRoom room, ManualActor actor, ManualOccupant occupant,
            ManualCardInstance card) {
        operations.apply(room, actor, state -> operations.move(state, actor,
                new ManualOpRequest.Move(occupant.getOccupantId(), List.of(card.getInstanceId()),
                        actor.seat(), ManualZone.FIELD, null, null)));
    }

    /** その席の視点でレンダリングした、直近のログ1行。 */
    private String renderFor(ManualRoom room, ManualSeatId seatId) {
        List<ManualLogEntry> log = room.getLog();
        ManualLogEntry last = log.get(log.size() - 1);
        return logRenderer.render(last.event(),
                new ManualViewpoint(room.getType(), seatId, ManualSpectatorView.PUBLIC_ONLY));
    }
}
