package com.example.qte.manual;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.springframework.stereotype.Service;

/**
 * 手動モードの状態変更。
 *
 * <h2>★Batch 17b が持つのはゲーム開始だけである</h2>
 * 設計書 5-3 の操作13項目(ゾーン間移動・数値変更・札・タップ・進化スタック等)は
 * Batch 18a で、このクラスに足す。17b の役割は、それらが載る土台を用意することである。
 *
 * <h2>自動処理の範囲(設計書 5-1・5-2)</h2>
 * アプリが担うのは「同じ盤面を見ていることの保証」だけである。判断を要するものは全部切る。
 * ゲーム開始でアプリが行うのは<b>シャッフルと初期手札4枚とLP20</b>だけであり、ここに判断は無い。
 * マリガンは手動(戻したい札を山札へドラッグして引き直す)、
 * ダイス・先攻後攻の決定は行わず、ターンは人間が進める。
 * ピュア・エレメントの自動配布もしない。
 *
 * ★このクラスは呼び出し側が {@code synchronized (room.getLock())} の中で呼ぶ前提で書く。
 * 自前ではロックを取らない。ロックの範囲を決めるのは、
 * 「1つの操作」がどこからどこまでかを知っている入口(コントローラ)である。
 */
@Service
public class ManualGameService {

    /** 開始時のLP(総合ルール 2-2)。上限は強制しない */
    public static final int INITIAL_LP = 20;

    /** 開始時の手札枚数 */
    public static final int INITIAL_HAND_SIZE = 4;

    private final Random random = new SecureRandom();

    /**
     * 読み込んだデッキを席に配り、ゲームを開始できる状態にする。
     *
     * ★履歴を空にする(設計書 5-6)。読み込み前の盤面へ Undo できても意味が無い。
     * 「リセットして引き直す」(Batch 19a)もこの経路を通る。
     */
    public void loadDeck(ManualRoom room, ManualSeatId seatId, ManualDeckImport imported) {
        ManualSeat seat = room.getGameState().seat(seatId);
        applyImport(seat, imported);

        room.getHistory().clear();
        room.addLog("席%s にデッキ「%s」を読み込んだ(山札 %d 枚 / 禁忌 %d 枚)。シャッフルして %d 枚引いた"
                .formatted(seatId, seat.getDeckName() == null ? "名称なし" : seat.getDeckName(),
                        seat.zone(ManualZone.DECK).size(), seat.zone(ManualZone.TABOO).size(),
                        seat.zone(ManualZone.HAND).size()));
        for (String warning : imported.warnings()) {
            room.addLog("警告: " + warning);
        }
    }

    /**
     * デッキの内容を席へ適用する(クリア → 配布 → シャッフル → 初期手札 → LP20)。
     * {@link #loadDeck} と {@link #resetRoom} の共通部分をここに集約する。
     * ★{@code seat.setLastImport} をここで呼ぶため、通常の読み込みでもリセットでも
     * 「直近のデッキ」は必ず最新化される。
     */
    private void applyImport(ManualSeat seat, ManualDeckImport imported) {
        seat.clearAll();
        seat.setDeckName(imported.deckName());
        seat.setLastImport(imported);

        if (imported.leader() != null) {
            seat.setLeader(imported.leader().toInstance());
        }
        List<ManualCardInstance> deck = new ArrayList<>();
        for (ManualDeckImport.Entry entry : imported.main()) {
            deck.add(entry.toInstance());
        }
        Collections.shuffle(deck, random);
        seat.zone(ManualZone.DECK).addAll(deck);

        for (ManualDeckImport.Entry entry : imported.taboo()) {
            seat.zone(ManualZone.TABOO).add(entry.toInstance());
        }

        drawCards(seat, INITIAL_HAND_SIZE);
        seat.setLp(INITIAL_LP);
    }

    /**
     * リセットして引き直す(設計書 7-1・5-6)。
     *
     * デッキを読み込み済みの席(直近の {@link ManualDeckImport} を持つ席)だけを
     * シャッフルし直し、初期手札4枚・LP20 で配り直す。zip の再アップロードは要らない。
     * B席のように一度もデッキを読んでいない席は何もしない(空席のまま)。
     * ★履歴を空にする(設計書 5-6。リセット前への Undo は無意味)。
     *
     * @throws IllegalStateException 両席ともデッキ未読込のとき(リセットする対象が無い)
     */
    public void resetRoom(ManualRoom room) {
        ManualGameState state = room.getGameState();
        boolean any = false;
        for (ManualSeatId seatId : ManualSeatId.values()) {
            ManualSeat seat = state.seat(seatId);
            ManualDeckImport imported = seat.getLastImport();
            if (imported == null) {
                continue;
            }
            applyImport(seat, imported);
            any = true;
        }
        if (!any) {
            throw new IllegalStateException("まだデッキを読み込んでいないため、リセットできません");
        }
        room.getHistory().clear();
        room.addLog("リセットして引き直した");
    }

    /**
     * 山札の上から引く。山札が足りなければ引ける枚数だけ引く。
     * ★デッキ切れ敗北は判定しない(設計書 5-1)。
     *
     * @return 実際に引いた枚数
     */
    public int drawCards(ManualSeat seat, int count) {
        List<ManualCardInstance> deck = seat.zone(ManualZone.DECK);
        int drawn = 0;
        for (int i = 0; i < count && !deck.isEmpty(); i++) {
            seat.zone(ManualZone.HAND).add(deck.remove(0));
            drawn++;
        }
        return drawn;
    }

    /** 山札をシャッフルする(設計書 5-3 の11)。乱数は機械のほうが公平である。 */
    public void shuffleDeck(ManualSeat seat) {
        Collections.shuffle(seat.zone(ManualZone.DECK), random);
    }
}
