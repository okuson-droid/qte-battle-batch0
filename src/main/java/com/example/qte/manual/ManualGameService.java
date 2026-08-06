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
 * ゲーム開始でアプリが行うのは<b>シャッフルと初期手札とLP20</b>だけであり、ここに判断は無い。
 *
 * <h2>★Batch 23 で自動化した範囲(23 設計書 1-3)</h2>
 * 総合ルール <b>2-5(ゲーム開始前の処理)だけ</b>を自動化する。ダイス・先攻後攻の決定・
 * マリガン・ピュア・エレメントの配布は {@link ManualStartService} が引き受けるが、
 * <b>2-6(ターン進行)には一切踏み込まない</b>。ターンもフェイズも人間が進める。
 * 勝敗・コスト・デッキ切れの判断は1つも増えていない。
 * このクラスが提供するのは、その段取りが使う部品
 * ({@link #shuffleDeck} / {@link #drawCards} / {@link #dealForStart} / {@link #rollDie})だけである。
 *
 * ★このクラスは呼び出し側が {@code synchronized (room.getLock())} の中で呼ぶ前提で書く。
 * 自前ではロックを取らない。ロックの範囲を決めるのは、
 * 「1つの操作」がどこからどこまでかを知っている入口(コントローラ)である。
 */
@Service
public class ManualGameService {

    /** 開始時のLP(総合ルール 2-2)。上限は強制しない */
    public static final int INITIAL_LP = 20;

    /** デッキ読み込み直後の手札枚数(★開始シーケンスを通さないときの既定) */
    public static final int INITIAL_HAND_SIZE = 4;

    /** 先攻の初期ドロー(総合ルール 2-5 の2 / ★Batch 23 4-1) */
    public static final int FIRST_PLAYER_HAND_SIZE = 4;

    /** 後攻の初期ドロー(総合ルール 2-5 の2 / ★Batch 23 4-1) */
    public static final int SECOND_PLAYER_HAND_SIZE = 5;

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
        // ★20b: 共有ゾーンは席の外にあり clearAll() が届かない。中央に前回の個体が
        //   残ると、山札へ戻らないカードが盤面に居座る(マスター確認済み)。
        room.getGameState().clearSharedZones();

        room.getHistory().clear();
        // ★Batch 23 P6: デッキを読み直したら開始シーケンスは未開始へ戻す。
        //   読み直した山札の上に「開始済み」という前提だけが残るのが最悪の状態である。
        room.resetStart();
        // ★デッキ名と枚数は全員に見せる(21 設計書 5-3)。名前を見せたくなければ
        //   デッキファイルに名前を付けずに読み込めばよい、というのが確定した方針である。
        room.addLog(ManualLogEvent.plain(ManualLogKind.DECK, seatId,
                "席%s にデッキ「%s」を読み込んだ(山札 %d 枚 / 禁忌 %d 枚)。シャッフルして %d 枚引いた"
                        .formatted(seatId, seat.getDeckName() == null ? "名称なし" : seat.getDeckName(),
                                seat.zone(ManualZone.DECK).size(), seat.zone(ManualZone.TABOO).size(),
                                seat.zone(ManualZone.HAND).size())));
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
        applyImport(seat, imported, INITIAL_HAND_SIZE);
    }

    /**
     * 初期手札の枚数を指定して適用する(★Batch 23 4-1)。
     *
     * ★<b>新しい初期化処理を書かない</b>(23 設計書 6-3)。開始シーケンスの
     * 「シャッフル → 初期ドロー(先攻4 / 後攻5)」も、リセットもデッキ読込も、
     * 通るのはこの1本だけである。2種類の「初期化」が存在すると、どちらを通ったかで
     * 盤面の残り方が変わる。違うのは引く枚数だけであり、それは引数で表す。
     */
    private void applyImport(ManualSeat seat, ManualDeckImport imported, int handSize) {
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
            // ★20b: 由来をここで一度だけ刻む。禁忌ゾーンから出た後は現在地から復元できない
            //   ({@link ManualCardInstance#isFromTaboo()})。
            ManualCardInstance instance = entry.toInstance();
            instance.setFromTaboo(true);
            seat.zone(ManualZone.TABOO).add(instance);
        }

        drawCards(seat, handSize);
        seat.setLp(INITIAL_LP);
    }

    /**
     * 開始シーケンスの配り直し(★Batch 23 4-1)。
     * 直近に読み込んだデッキを、指定枚数の初期手札で配り直す。
     *
     * ★山札が足りなければ引ける枚数だけ引く({@link #drawCards})。
     * <b>デッキ切れ敗北は判定しない</b>(23 設計書 1-3・P16)。
     *
     * @return 実際に引いた枚数。★デッキ未読込の席は -1(呼び出し側が「配らない」を判断する)
     */
    public int dealForStart(ManualRoom room, ManualSeatId seatId, int handSize) {
        ManualSeat seat = room.getGameState().seat(seatId);
        ManualDeckImport imported = seat.getLastImport();
        if (imported == null) {
            return -1;
        }
        applyImport(seat, imported, handSize);
        return seat.zone(ManualZone.HAND).size();
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
        state.clearSharedZones(); // ★20b: 仕切り直しでは中央も片付ける
        if (!any) {
            throw new IllegalStateException("まだデッキを読み込んでいないため、リセットできません");
        }
        room.getHistory().clear();
        // ★★Batch 23 11章の最重要項目: リセットでフェーズも IDLE へ戻す。
        //   盤面だけ初期化されて PLAYING が残るのが最悪の状態である。
        //   ★リセットは開始シーケンス中でも通る唯一の操作でもある(7-2)。
        room.resetStart();
        room.addLog(ManualLogEvent.plain(ManualLogKind.DECK, null, "リセットして引き直した"));
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

    /**
     * ダイスを1つ振る(総合ルール 2-5 の先後判定 / 21 設計書 6-3・E4)。
     *
     * ★<b>乱数の出所をこのクラス1つに保つ</b>ためにここへ置いた。
     * 呼び出し側({@link ManualStartService})に {@code Random} をもう1つ持たせると、
     * 乱数源が2箇所に分かれる。
     * 「機械のほうが公平である」というシャッフルの理由がそのまま当てはまる処理であり、
     * 同じ場所に置くのが素直である。★盤面には一切触らない。
     *
     * <h3>★面数は引数である(Batch 23 3-2)</h3>
     * 21c は6面で使っていたが、23 は総合ルール 2-5 の先後判定に<b>20面</b>を使う。
     * 定数 {@code DICE_SIDES = 6} を書き換えるのではなく、呼び出し側が 20 を渡す。
     * 定数の名前はそのままで意味だけが変わるのが、最も気づきにくい壊し方だからである。
     * ★23 では 21c の呼び出し側({@code ManualOperationService.firstPlayer})ごと
     * 削除したため、6面の定数自体が残っていない(3-4。経路を1本にする)。
     */
    public int rollDie(int sides) {
        return random.nextInt(sides) + 1;
    }
}
