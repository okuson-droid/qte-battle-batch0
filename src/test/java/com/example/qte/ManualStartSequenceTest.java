package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.manual.ManualActor;
import com.example.qte.manual.ManualCardInstance;
import com.example.qte.manual.ManualCardRepository;
import com.example.qte.manual.ManualDeckImport;
import com.example.qte.manual.ManualGameService;
import com.example.qte.manual.ManualLogEntry;
import com.example.qte.manual.ManualLogEvent;
import com.example.qte.manual.ManualLogKind;
import com.example.qte.manual.ManualLogRite;
import com.example.qte.manual.ManualOccupant;
import com.example.qte.manual.ManualOpRequest;
import com.example.qte.manual.ManualOperationService;
import com.example.qte.manual.ManualRoom;
import com.example.qte.manual.ManualRoomOptions;
import com.example.qte.manual.ManualRoomType;
import com.example.qte.manual.ManualSeatId;
import com.example.qte.manual.ManualRiteDeal;
import com.example.qte.manual.ManualStartMethod;
import com.example.qte.manual.ManualStartPhase;
import com.example.qte.manual.ManualRiteKind;
import com.example.qte.manual.ManualStartService;
import com.example.qte.manual.ManualZone;
import com.example.qte.manual.view.ManualGameView;
import com.example.qte.manual.view.ManualViewBuilder;

/**
 * Batch 23 のテスト(ゲーム開始シーケンス。総合ルール 2-5 / 設計書10-1)。
 *
 * <h2>★このバッチの主戦場である(10-1)</h2>
 * 権限・状態遷移・枚数は<b>画面では確かめにくく、壊れても見た目には気づけない</b>種類の
 * 機能である。先攻が4枚のはずのところが5枚になっていても、画面はそれらしく描かれる。
 * したがって<b>サーバ側で状態を直接確かめる</b>形にしてある(21a と同じ性質)。
 *
 * ★カードIDを文字列リテラルで書かない(batch17a-design-notes 3-2)。
 * デッキは突合しないカード({@link ManualDeckImport.Entry} の master が null)で組み立てる。
 * 例外はピュア・エレメントで、あれは<b>設定ファイルから来るID</b>であり
 * テストもコードもリテラルを持たない(5-2)。
 */
@SpringBootTest
class ManualStartSequenceTest {

    @Autowired
    ManualStartService startService;

    @Autowired
    ManualGameService gameService;

    @Autowired
    ManualOperationService operations;

    @Autowired
    ManualViewBuilder viewBuilder;

    @Autowired
    ManualCardRepository cards;

    // ================= 2-3. 開始できる条件 =================

    @Test
    void 対戦部屋は両席がデッキを読み込むまで開始できない() {
        ManualRoom room = versusRoom();
        ManualOccupant a = room.join("あかり", ManualSeatId.A);
        room.join("ばんり", ManualSeatId.B);
        load(room, ManualSeatId.A);

        assertThatThrownBy(() -> startService.begin(room, ManualActor.of(room, a)))
                .hasMessageContaining("両方の席");
        assertThat(room.getStartPhase()).isEqualTo(ManualStartPhase.IDLE);

        load(room, ManualSeatId.B);
        startService.begin(room, ManualActor.of(room, a));
        assertThat(room.getStartPhase()).isEqualTo(ManualStartPhase.ORDER_METHOD);
    }

    @Test
    void デッキを1つも読み込んでいなければ開始できない() {
        ManualRoom room = openRoom();
        ManualOccupant a = room.join("ひとり", ManualSeatId.A);
        assertThatThrownBy(() -> startService.begin(room, ManualActor.of(room, a)))
                .hasMessageContaining("デッキを読み込んで");
    }

    @Test
    void 全公開部屋はデッキが1つだけでも開始方法を選べる() {
        // ★マスター指示 2026-08-06。先攻に固定すると後攻の練習ができない
        ManualRoom room = openRoom();
        ManualOccupant a = room.join("ひとり", ManualSeatId.A);
        load(room, ManualSeatId.A);

        startService.begin(room, ManualActor.of(room, a));

        assertThat(room.getStartPhase()).isEqualTo(ManualStartPhase.ORDER_METHOD);
    }

    @Test
    void デッキが1つだけのとき先攻を選ぶと4枚引く() {
        ManualRoom room = openRoom();
        ManualOccupant a = room.join("ひとり", ManualSeatId.A);
        load(room, ManualSeatId.A);
        ManualActor actor = ManualActor.of(room, a);
        startService.begin(room, actor);

        startService.chooseMethod(room, actor, ManualStartMethod.FIRST);

        assertThat(room.getFirstSeat()).isEqualTo(ManualSeatId.A);
        assertThat(hand(room, ManualSeatId.A)).hasSize(ManualGameService.FIRST_PLAYER_HAND_SIZE);
        // ★デッキを読み込んでいない席は配らず、マリガンも待たない
        assertThat(room.getMulliganPending()).containsExactly(ManualSeatId.A);
    }

    @Test
    void デッキが1つだけのとき後攻を選ぶと5枚引いてピュアエレメントを受け取る() {
        ManualRoom room = openRoom();
        ManualOccupant a = room.join("ひとり", ManualSeatId.A);
        load(room, ManualSeatId.A);
        ManualActor actor = ManualActor.of(room, a);
        startService.begin(room, actor);

        startService.chooseMethod(room, actor, ManualStartMethod.SECOND);

        // ★空席Bが先攻になる。一人回しで「相手が先攻」を再現している状態である
        assertThat(room.getFirstSeat()).isEqualTo(ManualSeatId.B);
        assertThat(room.secondSeat()).isEqualTo(ManualSeatId.A);
        assertThat(hand(room, ManualSeatId.A)).hasSize(ManualGameService.SECOND_PLAYER_HAND_SIZE);
        assertThat(room.getMulliganPending()).containsExactly(ManualSeatId.A);

        startService.mulligan(room, actor, ManualSeatId.A, List.of());

        assertThat(room.getStartPhase()).isEqualTo(ManualStartPhase.PLAYING);
        // 後攻5枚 + ピュア・エレメント1枚
        assertThat(hand(room, ManualSeatId.A)).hasSize(6);
        assertThat(lastLog(room)).contains("ピュア・エレメントを渡した");
        // ★空席であることをログに明記する(配り忘れと読まれないため)
        assertThat(lastLog(room)).contains("席B はデッキ未読込");
    }

    @Test
    void 席Bだけ読み込んでいるとき先攻の主語は席Bになる() {
        // ★作成者席Aを主語にすると、選んだ内容と結果が逆さまになる
        ManualRoom room = openRoom();
        room.setCreatorSeat(ManualSeatId.A);
        ManualOccupant a = room.join("ひとり", ManualSeatId.A);
        load(room, ManualSeatId.B);
        ManualActor actor = ManualActor.of(room, a);

        assertThat(startService.subjectSeat(room, actor)).isEqualTo(ManualSeatId.B);

        startService.begin(room, actor);
        startService.chooseMethod(room, actor, ManualStartMethod.FIRST);

        assertThat(room.getFirstSeat()).isEqualTo(ManualSeatId.B);
        assertThat(hand(room, ManualSeatId.B)).hasSize(ManualGameService.FIRST_PLAYER_HAND_SIZE);
    }

    @Test
    void 両席を読み込んでいる全公開部屋では主語が押した席のままである() {
        ManualRoom room = openRoom();
        room.setCreatorSeat(ManualSeatId.A);
        ManualOccupant b = room.join("ひとり", ManualSeatId.B);
        loadBoth(room);

        assertThat(startService.subjectSeat(room, ManualActor.of(room, b)))
                .isEqualTo(ManualSeatId.B);
    }

    // ================= 2-4. 誰が開始方法を選ぶのか =================

    @Test
    void 対戦部屋では作成者席のプレイヤーだけが開始方法を選べる() {
        ManualRoom room = versusRoom();
        room.setCreatorSeat(ManualSeatId.A);
        ManualOccupant a = room.join("あかり", ManualSeatId.A);
        ManualOccupant b = room.join("ばんり", ManualSeatId.B);
        loadBoth(room);

        assertThatThrownBy(() -> startService.begin(room, ManualActor.of(room, b)))
                .hasMessageContaining("部屋を作った席A");
        startService.begin(room, ManualActor.of(room, a));
        assertThat(room.getStartPhase()).isEqualTo(ManualStartPhase.ORDER_METHOD);
    }

    @Test
    void 作成者席が空席ならどちらの席でも開始方法を選べる() {
        ManualRoom room = versusRoom();
        // ★作成者は席を立った / 猶予切れで消えた。occupantId で持っていたら
        //   この部屋は永久に開始できなくなる(2-4)
        room.setCreatorSeat(ManualSeatId.A);
        ManualOccupant b = room.join("ばんり", ManualSeatId.B);
        loadBoth(room);

        startService.begin(room, ManualActor.of(room, b));
        assertThat(room.getStartPhase()).isEqualTo(ManualStartPhase.ORDER_METHOD);
    }

    @Test
    void 観戦者は開始方法を選べない() {
        ManualRoom room = versusRoom();
        room.setCreatorSeat(ManualSeatId.A);
        room.join("あかり", ManualSeatId.A);
        room.join("ばんり", ManualSeatId.B);
        ManualOccupant watcher = room.join("みるひと", null);
        loadBoth(room);

        assertThatThrownBy(() -> startService.begin(room, ManualActor.of(room, watcher)))
                .hasMessageContaining("観戦者");
    }

    // ================= 3章. 先攻後攻の決定 =================

    @Test
    void ダイスは同値で止まらず必ずどちらかの席が選択権を得る() {
        // ★50回まわす(10-1)。1回でも引き分けで止まると、そこから先へ進めない部屋が生まれる
        for (int i = 0; i < 50; i++) {
            ManualRoom room = versusRoom();
            room.setCreatorSeat(ManualSeatId.A);
            ManualOccupant a = room.join("あかり", ManualSeatId.A);
            room.join("ばんり", ManualSeatId.B);
            loadBoth(room);
            ManualActor actor = ManualActor.of(room, a);
            startService.begin(room, actor);

            startService.chooseMethod(room, actor, ManualStartMethod.DICE);

            assertThat(room.getStartPhase()).isEqualTo(ManualStartPhase.ORDER_CHOICE);
            assertThat(room.getOrderChooserSeat()).isNotNull();
            assertThat(lastLog(room)).contains("20面ダイス");
        }
    }

    @Test
    void ソロのランダムは選択を挟まずそのまま先攻が決まる() {
        ManualRoom room = openRoom();
        ManualOccupant a = room.join("ひとり", ManualSeatId.A);
        loadBoth(room);
        ManualActor actor = ManualActor.of(room, a);
        startService.begin(room, actor);

        startService.chooseMethod(room, actor, ManualStartMethod.DICE);

        // ★ソロは同じ人が続けて2回押すだけになるため、選択モーダルを出さない(3-1)
        assertThat(room.getStartPhase()).isEqualTo(ManualStartPhase.MULLIGAN);
        assertThat(room.getFirstSeat()).isNotNull();
    }

    @Test
    void 先攻を選べるのはダイスで勝った席だけである() {
        ManualRoom room = versusRoom();
        room.setCreatorSeat(ManualSeatId.A);
        ManualOccupant a = room.join("あかり", ManualSeatId.A);
        ManualOccupant b = room.join("ばんり", ManualSeatId.B);
        loadBoth(room);
        ManualActor actorA = ManualActor.of(room, a);
        startService.begin(room, actorA);
        startService.chooseMethod(room, actorA, ManualStartMethod.DICE);

        ManualSeatId winner = room.getOrderChooserSeat();
        ManualOccupant loser = winner == ManualSeatId.A ? b : a;
        assertThatThrownBy(() ->
                startService.chooseOrder(room, ManualActor.of(room, loser), true))
                .hasMessageContaining("ダイスで勝った席");
    }

    @Test
    void 自分が後攻を選ぶと相手が先攻になる() {
        ManualRoom room = versusRoom();
        room.setCreatorSeat(ManualSeatId.A);
        ManualOccupant a = room.join("あかり", ManualSeatId.A);
        room.join("ばんり", ManualSeatId.B);
        loadBoth(room);
        ManualActor actor = ManualActor.of(room, a);
        startService.begin(room, actor);

        startService.chooseMethod(room, actor, ManualStartMethod.SECOND);

        assertThat(room.getFirstSeat()).isEqualTo(ManualSeatId.B);
        assertThat(room.secondSeat()).isEqualTo(ManualSeatId.A);
    }

    // ================= 4章. 初期ドローとマリガン =================

    @Test
    void 初期ドローは先攻4枚で後攻5枚である() {
        ManualRoom room = toMulligan(ManualSeatId.A);

        assertThat(hand(room, ManualSeatId.A)).hasSize(4);
        assertThat(hand(room, ManualSeatId.B)).hasSize(5);
        assertThat(room.getStartPhase()).isEqualTo(ManualStartPhase.MULLIGAN);
    }

    @Test
    void マリガンは戻した枚数と同じ枚数を引き直す() {
        ManualRoom room = toMulligan(ManualSeatId.A);
        ManualOccupant a = seated(room, ManualSeatId.A);
        List<String> back = List.of(hand(room, ManualSeatId.A).get(0).getInstanceId(),
                hand(room, ManualSeatId.A).get(1).getInstanceId());
        int deckBefore = room.getGameState().seat(ManualSeatId.A).zone(ManualZone.DECK).size();

        startService.mulligan(room, ManualActor.of(room, a), ManualSeatId.A, back);

        assertThat(hand(room, ManualSeatId.A)).hasSize(4);
        assertThat(room.getGameState().seat(ManualSeatId.A).zone(ManualZone.DECK))
                .hasSize(deckBefore);
        // ★戻した個体が手札に残っていない(シャッフルで戻る可能性はあるが、
        //   ここでは山札の枚数が元に戻ることだけを保証すれば足りる)
        assertThat(room.getMulliganDone()).contains(ManualSeatId.A);
    }

    @Test
    void ゼロ枚で確定してもマリガンを1回消費する() {
        ManualRoom room = toMulligan(ManualSeatId.A);
        ManualOccupant a = seated(room, ManualSeatId.A);

        startService.mulligan(room, ManualActor.of(room, a), ManualSeatId.A, List.of());

        assertThat(room.getMulliganDone()).contains(ManualSeatId.A);
        assertThatThrownBy(() ->
                startService.mulligan(room, ManualActor.of(room, a), ManualSeatId.A, List.of()))
                .hasMessageContaining("1回だけ");
    }

    @Test
    void マリガンのログに戻したカード名は載らない() {
        ManualRoom room = toMulligan(ManualSeatId.A);
        ManualOccupant a = seated(room, ManualSeatId.A);
        ManualCardInstance target = hand(room, ManualSeatId.A).get(0);
        String name = target.getFallbackName();

        startService.mulligan(room, ManualActor.of(room, a), ManualSeatId.A,
                List.of(target.getInstanceId()));

        // ★5-2 のマスク規則: 手札も山札もどちらも非公開ゾーンである。枚数だけを残す
        String text = lastLog(room);
        assertThat(name).isNotBlank();
        assertThat(text).doesNotContain(name);
        assertThat(text).contains("1枚");
    }

    @Test
    void 相手の席のマリガンを勝手に確定できない() {
        ManualRoom room = toMulligan(ManualSeatId.A);
        ManualOccupant a = seated(room, ManualSeatId.A);
        assertThatThrownBy(() ->
                startService.mulligan(room, ManualActor.of(room, a), ManualSeatId.B, List.of()))
                .hasMessageContaining("席B");
    }

    @Test
    void 手札にないカードはマリガンで戻せない() {
        ManualRoom room = toMulligan(ManualSeatId.A);
        ManualOccupant a = seated(room, ManualSeatId.A);
        // ★設計判断27: 外から来るデータはすべて検証する
        String deckCardId = room.getGameState().seat(ManualSeatId.A)
                .zone(ManualZone.DECK).get(0).getInstanceId();
        assertThatThrownBy(() -> startService.mulligan(room, ManualActor.of(room, a),
                ManualSeatId.A, List.of(deckCardId)))
                .hasMessageContaining("手札にない");
    }

    // ================= 5章. ピュア・エレメント =================

    @Test
    void 両者のマリガンが終わると後攻へピュアエレメントが表向きで入る() {
        ManualRoom room = toMulligan(ManualSeatId.A);
        finishMulligan(room);

        assertThat(room.getStartPhase()).isEqualTo(ManualStartPhase.PLAYING);
        List<ManualCardInstance> second = hand(room, ManualSeatId.B);
        // 後攻5枚 + ピュア・エレメント1枚
        assertThat(second).hasSize(6);
        ManualCardInstance pure = second.get(second.size() - 1);
        // ★★他の手札と同じ扱いにする(マスター裁定 2026-08-06)。
        //   総合ルール 2-5 の4は「裏向きで渡す」と書くが、手札は持ち主しか見ないゾーンであり
        //   (対戦部屋では相手にカードオブジェクトが届かない)、裏向きに情報上の意味が無い。
        //   裏向きにすると持ち主にも何のカードか分からなくなる実害だけが残る。
        assertThat(pure.isFaceDown()).isFalse();
        assertThat(pure.isResolved()).isTrue();
        // ★先攻には渡らない
        assertThat(hand(room, ManualSeatId.A)).hasSize(4);
    }

    @Test
    void ピュアエレメントの設定が無くても開始は完了し配布だけがスキップされる() {
        // ★起動を失敗させない(5-2)。一人回しでは無くても困らない機能であり、
        //   設定漏れでアプリ全体が上がらなくなるほうが害が大きい
        ManualStartService noPure = new ManualStartService(gameService, cards, "");
        assertThat(noPure.isPureElementAvailable()).isFalse();

        ManualRoom room = toMulligan(ManualSeatId.A, noPure);
        ManualOccupant a = seated(room, ManualSeatId.A);
        ManualOccupant b = seated(room, ManualSeatId.B);
        noPure.mulligan(room, ManualActor.of(room, a), ManualSeatId.A, List.of());
        noPure.mulligan(room, ManualActor.of(room, b), ManualSeatId.B, List.of());

        assertThat(room.getStartPhase()).isEqualTo(ManualStartPhase.PLAYING);
        assertThat(hand(room, ManualSeatId.B)).hasSize(5);
        assertThat(lastLog(room)).contains("配布を省略");
    }

    @Test
    void 存在しないカードIDを設定しても起動は失敗せず配布だけがスキップされる() {
        ManualStartService broken =
                new ManualStartService(gameService, cards, "QTE-M-NOT-EXIST-9999");
        assertThat(broken.isPureElementAvailable()).isFalse();
    }

    // ================= 7章. 開始中の操作ロック =================

    @Test
    void 開始フェーズ中の盤面操作はサーバが棄却する() {
        ManualRoom room = toMulligan(ManualSeatId.A);
        ManualOccupant a = seated(room, ManualSeatId.A);
        ManualActor actor = ManualActor.of(room, a);
        String cardId = hand(room, ManualSeatId.A).get(0).getInstanceId();

        // ★盤面を変える操作はすべて apply を通る。判定は1箇所で足りる(7-1)
        assertThatThrownBy(() -> operations.apply(room, actor, state ->
                operations.move(state, actor, new ManualOpRequest.Move(
                        a.getOccupantId(), List.of(cardId), ManualSeatId.A,
                        ManualZone.FIELD, null, null))))
                .hasMessageContaining("ゲーム開始の手続き中");

        assertThatThrownBy(() -> operations.apply(room, actor, state ->
                operations.tap(state, actor,
                        new ManualOpRequest.Flag(a.getOccupantId(), List.of(cardId), true))))
                .hasMessageContaining("ゲーム開始の手続き中");

        assertThatThrownBy(() -> operations.apply(room, actor, state ->
                operations.draw(state, actor,
                        new ManualOpRequest.Draw(a.getOccupantId(), ManualSeatId.A, 1))))
                .hasMessageContaining("ゲーム開始の手続き中");

        assertThatThrownBy(() -> operations.apply(room, actor, state ->
                operations.changeLp(state, actor,
                        new ManualOpRequest.Lp(a.getOccupantId(), ManualSeatId.A, null, -1))))
                .hasMessageContaining("ゲーム開始の手続き中");

        // ★Undo / Redo は apply を通らないため個別に見ている(7-1)
        assertThatThrownBy(() -> operations.undo(room, actor))
                .hasMessageContaining("ゲーム開始の手続き中");

        // 盤面は1枚も動いていない
        assertThat(hand(room, ManualSeatId.A)).hasSize(4);
    }

    @Test
    void 開始中でもリセットだけは通りフェーズが未開始へ戻る() {
        ManualRoom room = toMulligan(ManualSeatId.A);

        // ★★抜けられない画面を作らない(7-2)
        gameService.resetRoom(room);

        assertThat(room.getStartPhase()).isEqualTo(ManualStartPhase.IDLE);
        assertThat(room.getFirstSeat()).isNull();
        assertThat(room.getMulliganPending()).isEmpty();
        assertThat(room.getMulliganDone()).isEmpty();
        // リセット後は普通に操作できる
        ManualOccupant a = seated(room, ManualSeatId.A);
        ManualActor actor = ManualActor.of(room, a);
        operations.apply(room, actor, state -> operations.shuffleDeck(state, actor,
                new ManualOpRequest.Seat(a.getOccupantId(), ManualSeatId.A)));
    }

    @Test
    void 開始が完了すると履歴が空になりUndoで開始処理を戻せない() {
        ManualRoom room = toMulligan(ManualSeatId.A);
        finishMulligan(room);

        // ★開始処理は複数の状態変更を含み、途中まで戻せると意味のない中間状態が作れる(2-5)
        assertThat(room.getHistory().canUndo()).isFalse();
        ManualOccupant a = seated(room, ManualSeatId.A);
        assertThatThrownBy(() -> operations.undo(room, ManualActor.of(room, a)))
                .hasMessageContaining("取り消せる操作がありません");
    }

    @Test
    void デッキを読み直すと未開始へ戻る() {
        ManualRoom room = toMulligan(ManualSeatId.A);
        finishMulligan(room);
        assertThat(room.getStartPhase()).isEqualTo(ManualStartPhase.PLAYING);

        // ★P6: 読み直した山札の上に「開始済み」という前提だけが残るのが最悪の状態である
        gameService.loadDeck(room, ManualSeatId.A, deck("やりなおし"));

        assertThat(room.getStartPhase()).isEqualTo(ManualStartPhase.IDLE);
        assertThat(room.getFirstSeat()).isNull();
    }

    @Test
    void 開始済みの部屋は二重に開始できない() {
        ManualRoom room = toMulligan(ManualSeatId.A);
        finishMulligan(room);
        ManualOccupant a = seated(room, ManualSeatId.A);
        // ★先攻後攻はリセットされるまで固定である(1-4)
        assertThatThrownBy(() -> startService.begin(room, ManualActor.of(room, a)))
                .hasMessageContaining("すでにゲームを開始");
    }

    // ================= 9章. ビュー =================

    @Test
    void ビューに今のフェーズと自分が押せることが載る() {
        ManualRoom room = versusRoom();
        room.setCreatorSeat(ManualSeatId.A);
        ManualOccupant a = room.join("あかり", ManualSeatId.A);
        ManualOccupant b = room.join("ばんり", ManualSeatId.B);
        loadBoth(room);

        // 開始前: 作成者だけが押せる(★判定はクライアントで組み立て直さない)
        assertThat(viewBuilder.build(room, a).start().canBegin()).isTrue();
        assertThat(viewBuilder.build(room, b).start().canBegin()).isFalse();

        startService.begin(room, ManualActor.of(room, a));
        ManualGameView forA = viewBuilder.build(room, a);
        ManualGameView forB = viewBuilder.build(room, b);
        assertThat(forA.start().phase()).isEqualTo(ManualStartPhase.ORDER_METHOD);
        assertThat(forA.start().locking()).isTrue();
        assertThat(forA.start().canChooseMethod()).isTrue();
        assertThat(forB.start().canChooseMethod()).isFalse();
        // ★ボタンの文言と実際の結果が同じ関数を通る(設計判断34)
        assertThat(forA.start().subjectSeat()).isEqualTo(ManualSeatId.A);
        // ★待機表示は全員に出す(7-3)。盤面が固まっている理由が画面に無い状態を作らない
        assertThat(forB.start().waiting()).isNotBlank();
    }

    @Test
    void マリガン中のビューは相手の枚数を出さない() {
        ManualRoom room = toMulligan(ManualSeatId.A);
        ManualOccupant a = seated(room, ManualSeatId.A);
        ManualOccupant b = seated(room, ManualSeatId.B);
        startService.mulligan(room, ManualActor.of(room, a), ManualSeatId.A,
                List.of(hand(room, ManualSeatId.A).get(0).getInstanceId()));

        ManualGameView forB = viewBuilder.build(room, b);
        assertThat(forB.start().mulliganDone()).containsExactly(ManualSeatId.A);
        assertThat(forB.start().myMulliganSeats()).containsExactly(ManualSeatId.B);
        // ★「選択中 / 確定済み」だけを出し、何枚選んだかは出さない(P11)
        assertThat(forB.start().waiting()).contains("確定済み").contains("選択中");
        assertThat(forB.start().waiting()).doesNotContain("1枚");
    }

    // ================= ★Batch 38. 開始の儀式(構造) =================
    //
    // ★★ここで確かめているのは「演出が出るか」ではなく<b>演出の材料が正しく作られるか</b>
    //   である。見た目は verify のハーネスが見る(38 設計書7章)。
    //   材料が壊れていても画面はそれらしく描かれるので、23 と同じ理由でサーバ側から見る。

    @Test
    void 配りの儀式は先攻4枚後攻5枚を員数として運ぶ() {
        ManualRoom room = toMulligan(ManualSeatId.A);
        ManualLogRite rite = lastRite(room);
        assertThat(rite.kind()).isEqualTo(ManualRiteKind.DEAL);
        // ★ダイスを使っていないので出目は載らない
        assertThat(rite.diceA()).isNull();
        assertThat(rite.diceB()).isNull();
        assertThat(rite.dealt()).containsExactly(
                new ManualRiteDeal(ManualSeatId.A, 0, 4),
                new ManualRiteDeal(ManualSeatId.B, 0, 5));
    }

    @Test
    void 対戦部屋のダイスは配りを伴わない儀式になる() {
        ManualRoom room = versusRoom();
        room.setCreatorSeat(ManualSeatId.A);
        ManualOccupant a = room.join("あかり", ManualSeatId.A);
        room.join("ばんり", ManualSeatId.B);
        loadBoth(room);
        ManualActor actor = ManualActor.of(room, a);
        startService.begin(room, actor);
        startService.chooseMethod(room, actor, ManualStartMethod.DICE);

        ManualLogRite rite = lastRite(room);
        assertThat(rite.kind()).isEqualTo(ManualRiteKind.DICE);
        assertThat(rite.dealt()).isEmpty();
        assertThat(rite.diceA()).isBetween(1, ManualStartService.DICE_SIDES);
        assertThat(rite.diceB()).isBetween(1, ManualStartService.DICE_SIDES);
        assertThat(rite.diceA()).isNotEqualTo(rite.diceB());   // ★同値は振り直す(23 3-2)
        assertThat(rite.winner()).isNotNull();
        // ★★文言はサーバが作る。対戦部屋のダイスが与えるのは先攻ではなく<b>選択権</b>である
        assertThat(rite.label()).contains("選択権");
    }

    @Test
    void ソロのランダムはダイスと配りを1件の儀式にまとめる() {
        ManualRoom room = openRoom();
        ManualOccupant a = room.join("ひとり", ManualSeatId.A);
        room.setCreatorSeat(ManualSeatId.A);
        loadBoth(room);
        ManualActor actor = ManualActor.of(room, a);
        startService.begin(room, actor);
        startService.chooseMethod(room, actor, ManualStartMethod.DICE);

        ManualLogRite rite = lastRite(room);
        // ★主たる儀式は配りである。ダイスは別の欄に載る(画面が推測しなくて済む)
        assertThat(rite.kind()).isEqualTo(ManualRiteKind.DEAL);
        assertThat(rite.diceA()).isNotNull();
        assertThat(rite.label()).contains("先攻");
        assertThat(rite.dealt()).hasSize(2);
    }

    @Test
    void マリガンの儀式は戻した枚数と引いた枚数を持つ() {
        ManualRoom room = toMulligan(ManualSeatId.A);
        List<String> back = new ArrayList<>();
        for (ManualCardInstance card : hand(room, ManualSeatId.A).subList(0, 2)) {
            back.add(card.getInstanceId());
        }
        startService.mulligan(room, ManualActor.of(room, seated(room, ManualSeatId.A)),
                ManualSeatId.A, back);

        ManualLogRite rite = lastRite(room);
        assertThat(rite.kind()).isEqualTo(ManualRiteKind.MULLIGAN);
        assertThat(rite.dealt()).containsExactly(new ManualRiteDeal(ManualSeatId.A, 2, 2));
    }

    @Test
    void ゼロ枚のマリガンでも儀式は作られる() {
        ManualRoom room = toMulligan(ManualSeatId.A);
        startService.mulligan(room, ManualActor.of(room, seated(room, ManualSeatId.A)),
                ManualSeatId.A, List.of());
        // ★「儀式が無い」と「儀式が空だった」を画面が区別できなくなるので、必ず作る
        assertThat(lastRite(room).dealt())
                .containsExactly(new ManualRiteDeal(ManualSeatId.A, 0, 0));
    }

    @Test
    void 儀式を伴わない開始ログも正当に存在する() {
        ManualRoom room = versusRoom();
        room.setCreatorSeat(ManualSeatId.A);
        ManualOccupant a = room.join("あかり", ManualSeatId.A);
        room.join("ばんり", ManualSeatId.B);
        loadBoth(room);
        startService.begin(room, ManualActor.of(room, a));
        // ★準備に入っただけの行は構造を持たない。DECLARE と違い型では強制できない(2-4)
        List<ManualLogEntry> log = room.getLog();
        assertThat(log.get(log.size() - 1).event().rite()).isNull();
    }

    @Test
    void 儀式は配ったログ行の通し番号でビューに載る() {
        ManualRoom room = toMulligan(ManualSeatId.A);
        ManualGameView view = viewBuilder.build(room, seated(room, ManualSeatId.B));
        assertThat(view.rites()).hasSize(1);
        int seq = view.rites().get(0).seq();
        // ★★宣言と同じ約束: ここの seq は必ず<b>配った行のどれか</b>を指す(裁定42)
        assertThat(view.log()).anyMatch(line -> line.seq() == seq);
        // ★相手席の配りも届く。運ぶのは席と枚数だけで、どのカードかは構造上持てない
        assertThat(view.rites().get(0).rite().dealt()).hasSize(2);
    }

    @Test
    void 開始が完了した配信だけがピュアエレメントの席を持つ() {
        ManualRoom room = toMulligan(ManualSeatId.A);
        // ★1席目のマリガンでは開始が完了しない。渡っていないので席も載らない
        startService.mulligan(room, ManualActor.of(room, seated(room, ManualSeatId.A)),
                ManualSeatId.A, List.of());
        assertThat(lastRite(room).pureSeat()).isNull();

        startService.mulligan(room, ManualActor.of(room, seated(room, ManualSeatId.B)),
                ManualSeatId.B, List.of());
        // ★★2席目で完了する。設定が有効なときだけ席が載る(演出してよいのは実際に渡ったときだけ)
        ManualLogRite done = lastRite(room);
        assertThat(room.getStartPhase()).isEqualTo(ManualStartPhase.PLAYING);
        if (startService.isPureElementAvailable()) {
            assertThat(done.pureSeat()).isEqualTo(ManualSeatId.B);
        } else {
            assertThat(done.pureSeat()).isNull();
        }
    }

    // ================= ★38 追補. 山札のシャッフル(マスター指示) =================

    @Test
    void 山札のシャッフルは儀式として記録される() {
        ManualRoom room = openRoom();
        ManualOccupant a = room.join("ひとり", ManualSeatId.A);
        load(room, ManualSeatId.A);
        ManualActor actor = ManualActor.of(room, a);
        operations.apply(room, actor, state -> operations.shuffleDeck(state, actor,
                new ManualOpRequest.Seat(a.getOccupantId(), ManualSeatId.A)));

        List<ManualLogEntry> log = room.getLog();
        ManualLogEvent event = log.get(log.size() - 1).event();
        assertThat(event.kind()).isEqualTo(ManualLogKind.SHUFFLE);
        // ★★シャッフルは盤面に何も起こさない。差分では語れないので、構造が唯一の材料である
        ManualLogRite rite = event.rite();
        assertThat(rite).isNotNull();
        assertThat(rite.kind()).isEqualTo(ManualRiteKind.SHUFFLE);
        // ★員数は 0 / 0 である。運ぶのは「どの席の山札を混ぜたか」だけ
        assertThat(rite.dealt()).containsExactly(new ManualRiteDeal(ManualSeatId.A, 0, 0));
        assertThat(rite.pureSeat()).isNull();
    }

    // ================= 補助 =================

    /** 直近のログ行が持つ儀式。★無ければテストを落とす(儀式が消えたことに気づくため)。 */
    private ManualLogRite lastRite(ManualRoom room) {
        List<ManualLogEntry> log = room.getLog();
        ManualLogRite rite = log.get(log.size() - 1).event().rite();
        assertThat(rite).isNotNull();
        return rite;
    }

    private ManualRoom versusRoom() {
        return new ManualRoom("VSSTART", new ManualRoomOptions(
                "対戦部屋", ManualRoomType.VERSUS, true, false));
    }

    private ManualRoom openRoom() {
        return new ManualRoom("OPENSTART");
    }

    /**
     * 突合しないカードだけのデッキ(★カードIDをリテラルで書かないため。17a 3-2)。
     * 20枚あれば 先攻4 / 後攻5 とマリガンの引き直しに足りる。
     */
    private ManualDeckImport deck(String name) {
        List<ManualDeckImport.Entry> main = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            main.add(new ManualDeckImport.Entry(null, "%s-カード%d".formatted(name, i), null));
        }
        return new ManualDeckImport(name,
                new ManualDeckImport.Entry(null, name + "-リーダー", null),
                main, List.of(), List.of());
    }

    private void load(ManualRoom room, ManualSeatId seatId) {
        gameService.loadDeck(room, seatId, deck("デッキ" + seatId));
    }

    private void loadBoth(ManualRoom room) {
        for (ManualSeatId seatId : ManualSeatId.values()) {
            load(room, seatId);
        }
    }

    /** 対戦部屋を MULLIGAN まで進める。先攻は指定した席に固定する(ダイスを使わない)。 */
    private ManualRoom toMulligan(ManualSeatId firstSeat) {
        return toMulligan(firstSeat, startService);
    }

    private ManualRoom toMulligan(ManualSeatId firstSeat, ManualStartService service) {
        ManualRoom room = versusRoom();
        room.setCreatorSeat(ManualSeatId.A);
        ManualOccupant a = room.join("あかり", ManualSeatId.A);
        room.join("ばんり", ManualSeatId.B);
        loadBoth(room);
        ManualActor actor = ManualActor.of(room, a);
        service.begin(room, actor);
        service.chooseMethod(room, actor,
                firstSeat == ManualSeatId.A ? ManualStartMethod.FIRST : ManualStartMethod.SECOND);
        return room;
    }

    private void finishMulligan(ManualRoom room) {
        for (ManualSeatId seatId : ManualSeatId.values()) {
            ManualOccupant occupant = seated(room, seatId);
            startService.mulligan(room, ManualActor.of(room, occupant), seatId, List.of());
        }
    }

    private ManualOccupant seated(ManualRoom room, ManualSeatId seatId) {
        return room.occupantOfSeat(seatId).orElseThrow();
    }

    private List<ManualCardInstance> hand(ManualRoom room, ManualSeatId seatId) {
        return room.getGameState().seat(seatId).zone(ManualZone.HAND);
    }

    /** 直近のログ本文。★{@link ManualLogKind#START} は plain なので text() がそのまま出る。 */
    private String lastLog(ManualRoom room) {
        List<ManualLogEntry> log = room.getLog();
        ManualLogEntry last = log.get(log.size() - 1);
        assertThat(last.event().kind()).isEqualTo(ManualLogKind.START);
        return last.event().text();
    }
}
