package com.example.qte.manual;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * ゲーム開始シーケンス(総合ルール 2-5 / Batch 23 設計書)。
 *
 * <h2>★自動化するのは総合ルール 2-5 だけである(設計書 1-3)</h2>
 * 手動モードの憲法は設計書16 5-1「<b>手動モードで判断を実装しない</b>」である。
 * 本クラスはそこに「ゲーム開始前の処理」を加えるが、原則を捨てるのではなく境界を引き直す。
 *
 * <p>5-1 が名指しで禁じているのは、コストの支払い・召喚時効果・戦闘の解決・攻撃可否・
 * フェイズ強制・勝敗判定・デッキ切れである。共通するのは
 * <b>「ゲームの状況を読んで、何が起きるかをアプリが決める」</b>ことである。
 * 総合ルール 2-5 の手順は<b>盤面の状態に一切依存しない決まりきった段取り</b>であり、
 * 選択の余地があるのは次の2箇所だけで、どちらも人間が押す。</p>
 *
 * <ol>
 *   <li>先攻をとるか後攻をとるか({@link #chooseOrder})</li>
 *   <li>マリガンでどの札を戻すか({@link #mulligan})</li>
 * </ol>
 *
 * <p>アプリが引き受けるのは「乱数」と「シャッフル・移動・枚数を数えること」だけであり、
 * どちらも {@link ManualGameService} に既にある処理である。</p>
 *
 * <h2>★やらないと明示しておくこと(1-3)</h2>
 * <ul>
 *   <li>ターンを進めない。フェイズを進めない。先攻に最初のドローをさせない</li>
 *   <li>「マリガンは1回まで」以外のルールを強制しない</li>
 *   <li>山札が足りなくてもゲームを止めない(<b>デッキ切れ敗北は判定しない</b>。
 *       引ける枚数だけ引き、ログに残す)</li>
 *   <li>マリガンで戻す枚数に上限を設けない(ルール上「任意の枚数」である)</li>
 * </ul>
 * この境界が守られている限り、開始シーケンスは<b>「準備の代行」であって
 * 「ゲームのプレイ」ではない</b>。
 *
 * <h2>★履歴には積まない / 完了時にクリアする(2-5・P12)</h2>
 * 開始処理は「シャッフル → ドロー → 戻す → シャッフル → ドロー」という複数の状態変更を
 * 含み、途中まで戻せると「シャッフル後の並びだけ戻る」といった意味のない中間状態が
 * 作れてしまう。加えて対戦部屋の Undo は深さ1であり、そもそも全体を戻せない。
 * <b>戻したいならリセットする。</b>
 * したがって各操作は {@link ManualOperationService#apply} を<b>通さない</b>
 * (通すと履歴に積まれ、しかも自分自身の {@code denyDuringStart} に弾かれる)。
 *
 * <h2>★ロックは自分で取らない</h2>
 * 呼び出し側({@code ManualWsController})が {@code synchronized (room.getLock())} の中で
 * 呼ぶ前提である。{@link ManualOperationService} と同じ規約である。
 */
@Service
public class ManualStartService {

    private static final Logger log = LoggerFactory.getLogger(ManualStartService.class);

    /**
     * 先後判定のダイスの面数(総合ルール 2-5 / 設計書 3-2。マスター指示により20面)。
     *
     * ★{@code ManualGameService.rollDie(int sides)} の<b>呼び出し側</b>に置く。
     * 21c の {@code DICE_SIDES = 6} を書き換えるのではなく、こちらが 20 を渡す。
     * 定数の名前はそのままで意味だけが変わるのが最も気づきにくい壊し方である。
     */
    public static final int DICE_SIDES = 20;

    /** ダイスの振り直しの上限。★同値は振り直す(3-2)が、無限ループにはしない */
    private static final int MAX_DICE_REROLL = 1000;

    private final ManualGameService gameService;

    private final ManualCardRepository cards;

    /**
     * ピュア・エレメントの台帳カードID(★設計書 5-2)。設定できていなければ null。
     *
     * <h3>★カードIDを Java にリテラルで書かない(ハンドオフ3章の原則)</h3>
     * ピュア・エレメントは台帳上の特定の1枚なので、素直に書くと原則を破る。
     * 名前(「ピュア・エレメント」)での検索を採らなかったのは、
     * <b>名前をリテラルで書くのはIDを書くのと本質的に同じ</b>だからである。
     * 設定ファイルに出せば、台帳のIDが変わってもコードは変わらない。
     */
    private final String pureElementId;

    public ManualStartService(ManualGameService gameService, ManualCardRepository cards,
            @Value("${qte.manual.pure-element-id:}") String pureElementId) {
        this.gameService = gameService;
        this.cards = cards;
        // ★起動時に台帳へ存在するか検証する(5-2)。ただし<b>起動は失敗させない</b>。
        //   一人回しでは無くても困らない機能であり、設定漏れでアプリ全体が上がらなくなる
        //   ほうが害が大きい。配布だけをスキップし、そのことをログに残す。
        String configured = pureElementId == null || pureElementId.isBlank()
                ? null : pureElementId.trim();
        if (configured == null) {
            log.warn("qte.manual.pure-element-id が未設定である。"
                    + "後攻へのピュア・エレメントの配布はスキップされる(Batch 23 5-2)");
            this.pureElementId = null;
        } else if (cards.findOptionalById(configured).isEmpty()) {
            log.warn("qte.manual.pure-element-id = {} は手動モードの台帳に存在しない。"
                    + "後攻へのピュア・エレメントの配布はスキップされる(Batch 23 5-2)", configured);
            this.pureElementId = null;
        } else {
            this.pureElementId = configured;
        }
    }

    /** ピュア・エレメントを配れる状態か。テストとビューの説明文が見る。 */
    public boolean isPureElementAvailable() {
        return pureElementId != null;
    }

    // ================= 2-2. 遷移 =================

    /**
     * 開始シーケンスを始める(IDLE → {@link ManualStartPhase#ORDER_METHOD})。
     *
     * <h3>★開始できる条件(2-3)</h3>
     * <ul>
     *   <li><b>対戦部屋</b> — 両席が {@code deckLoaded} であること。片方だけでは始めない
     *       (マスター指示「お互いがデッキを読み込んだ後に」)</li>
     *   <li><b>全公開部屋</b> — 読み込まれているデッキが1つ以上あること。
     *       ★<b>1つだけでも開始方法を選ばせる</b>(マスター指示 2026-08-06)。
     *       設計書 6-2 は「1デッキなら先攻として扱い、モーダルを出さずに4枚引く」と
     *       していたが、それでは<b>後攻の練習ができない</b>。総合ルール 2-5 の後攻は
     *       5枚引いて【ピュア・エレメント】を受け取るという別の初期条件であり、
     *       一人回しで確かめたいのはまさにそこである。
     *       ★このとき「自分」が指すのは<b>デッキを読み込んでいる席</b>である
     *       ({@link #subjectSeat})</li>
     * </ul>
     */
    public ManualLogEvent begin(ManualRoom room, ManualActor actor) {
        ManualPermissions.require(ManualPermissions.denyStartControl(actor, room));
        if (room.getStartPhase() != ManualStartPhase.IDLE) {
            throw new IllegalStateException("すでにゲームを開始しています(やり直すにはリセットしてください)");
        }
        List<ManualSeatId> loaded = loadedSeats(room);
        if (loaded.isEmpty()) {
            throw new IllegalStateException("デッキを読み込んでください");
        }
        if (actor.isRestricted() && loaded.size() < ManualSeatId.values().length) {
            throw new IllegalStateException("両方の席がデッキを読み込むまで開始できません");
        }
        room.setStartPhase(ManualStartPhase.ORDER_METHOD);
        return startLog(actor, "ゲーム開始の準備に入った(開始方法を選択中)");
    }

    /**
     * 開始方法(3択)を選ぶ(★設計書 3-1)。
     *
     * <table>
     *   <caption>選択肢の意味は部屋の種類で変わる</caption>
     *   <tr><th>選択</th><th>対戦部屋</th><th>全公開部屋(ソロ)</th></tr>
     *   <tr><td>{@link ManualStartMethod#DICE}</td>
     *       <td>20面ダイス。勝った側に<b>選択権</b>を与える</td>
     *       <td>ランダム。勝った側が<b>そのまま先攻</b></td></tr>
     *   <tr><td>{@link ManualStartMethod#FIRST}</td><td colspan="2">自分の席が先攻</td></tr>
     *   <tr><td>{@link ManualStartMethod#SECOND}</td><td colspan="2">自分の席が後攻</td></tr>
     * </table>
     *
     * ★<b>ソロだけ DICE の意味が違う</b>(3-1)。対戦では「選択権をランダムに配ってから
     * 人間が選ぶ」ことに意味があるが、ソロは両席とも同じ人が操作するため、
     * 選択モーダルをもう1枚出しても<b>同じ人が続けて2回押すだけ</b>になる。
     */
    public ManualLogEvent chooseMethod(ManualRoom room, ManualActor actor,
            ManualStartMethod method) {
        ManualPermissions.require(ManualPermissions.denyStartControl(actor, room));
        if (room.getStartPhase() != ManualStartPhase.ORDER_METHOD) {
            throw new IllegalStateException("開始方法を選ぶ場面ではありません");
        }
        if (method == null) {
            throw new IllegalArgumentException("開始方法が指定されていません");
        }
        ManualSeatId subject = subjectSeat(room, actor);
        if (method != ManualStartMethod.DICE) {
            ManualSeatId first = method == ManualStartMethod.FIRST ? subject : other(subject);
            return deal(room, actor, first,
                    "席%s が%sを選んだ".formatted(subject,
                            method == ManualStartMethod.FIRST ? "先攻" : "後攻"),
                    null);
        }

        int a;
        int b;
        int rolls = 0;
        do {
            a = gameService.rollDie(DICE_SIDES);
            b = gameService.rollDie(DICE_SIDES);
            rolls++;
            // ★同値は振り直す(3-2)。引き分けを表示して押し直させると、
            //   押し直しの回数だけログが伸びる。決まるまで振るのが1回の操作として自然である
        } while (a == b && rolls < MAX_DICE_REROLL);
        ManualSeatId winner = a > b ? ManualSeatId.A : ManualSeatId.B;
        String dice = "先攻後攻の決定: %d面ダイス 席A %d / 席B %d".formatted(DICE_SIDES, a, b);

        if (!actor.isRestricted()) {
            // ★ソロは勝った側がそのまま先攻(3-1。マスター指示)
            // ★★Batch 38: 出目を配りの儀式へ引き継ぐ。ログが1行なら儀式も1件である
            return deal(room, actor, winner, "%s → 席%s が先攻".formatted(dice, winner),
                    ManualLogStartRite.dice(a, b, winner, "席%s が先攻".formatted(winner)));
        }
        room.setOrderChooserSeat(winner);
        room.setStartPhase(ManualStartPhase.ORDER_CHOICE);
        // ★★Batch 38: 対戦部屋ではここで配らない。儀式は「ダイスだけ」である
        return startLog(actor,
                ManualLogStartRite.dice(a, b, winner, "席%s が選択権".formatted(winner)),
                "%s → 席%s が選択権を得た".formatted(dice, winner));
    }

    /**
     * ダイスの勝者が先攻 / 後攻を選ぶ(★設計書 3-3)。
     * 押せるのは勝った席のプレイヤーだけである({@link ManualPermissions#denyOrderChoice})。
     */
    public ManualLogEvent chooseOrder(ManualRoom room, ManualActor actor, boolean takeFirst) {
        ManualPermissions.require(ManualPermissions.denyOrderChoice(actor, room));
        if (room.getStartPhase() != ManualStartPhase.ORDER_CHOICE) {
            throw new IllegalStateException("先攻・後攻を選ぶ場面ではありません");
        }
        ManualSeatId chooser = room.getOrderChooserSeat();
        ManualSeatId first = takeFirst ? chooser : other(chooser);
        return deal(room, actor, first,
                "席%s が%sを選んだ(席%s が%s)".formatted(chooser, takeFirst ? "先攻" : "後攻",
                        other(chooser), takeFirst ? "後攻" : "先攻"),
                null);
    }

    // ================= 4章. 初期ドローとマリガン =================

    /**
     * シャッフル → 初期ドロー(先攻4 / 後攻5)を1回の操作として行う(★設計書 4-1)。
     *
     * ★<b>新しい初期化処理を書かない</b>(6-3)。通るのは
     * {@link ManualGameService#dealForStart} → {@code applyImport} の1本だけであり、
     * これはデッキ読込・リセットと同じ経路である。
     *
     * ★デッキを読み込んでいない席は配らず、マリガンも待たない(6-2)。
     *
     * @param diceSeed ★Batch 38: 直前に振ったダイス。振っていなければ null。
     *                 ソロのランダムはダイスと配りが同じ1回の操作なので、
     *                 儀式も1件にまとめて運ぶ({@link ManualLogStartRite#deal})
     */
    private ManualLogEvent deal(ManualRoom room, ManualActor actor, ManualSeatId firstSeat,
            String decisionText, ManualLogStartRite diceSeed) {
        room.setFirstSeat(firstSeat);
        room.setOrderChooserSeat(null);
        // ★20b と同じ理由で中央も片付ける。共有ゾーンは席の外にあり clearAll() が届かない
        room.getGameState().clearSharedZones();

        StringBuilder drawn = new StringBuilder();
        // ★★Batch 38: 本文を組み立てるのと<b>同じ1周</b>で員数を拾う(裁定42 と同じ形)。
        //   別に回すと「ログに書いた枚数」と「演出した枚数」が別の場所から来ることになり、
        //   そのズレは静かに起きる
        List<ManualStartDeal> dealt = new ArrayList<>();
        room.getMulliganDone().clear();
        room.getMulliganPending().clear();
        for (ManualSeatId seatId : ManualSeatId.values()) {
            int handSize = seatId == firstSeat
                    ? ManualGameService.FIRST_PLAYER_HAND_SIZE
                    : ManualGameService.SECOND_PLAYER_HAND_SIZE;
            int actual = gameService.dealForStart(room, seatId, handSize);
            if (actual < 0) {
                continue; // デッキ未読込の席(6-2)
            }
            room.getMulliganPending().add(seatId);
            dealt.add(new ManualStartDeal(seatId, 0, actual));
            if (drawn.length() > 0) {
                drawn.append(" / ");
            }
            // ★山札が足りなければ引ける枚数だけ引いてある。事実をそのまま書く(1-3)
            drawn.append("席%s %d枚".formatted(seatId, actual));
            if (actual < handSize) {
                drawn.append("(山札が尽きたため %d枚 に満たない)".formatted(handSize));
            }
        }
        room.setStartPhase(ManualStartPhase.MULLIGAN);
        return startLog(actor, ManualLogStartRite.deal(diceSeed, dealt),
                "%s。シャッフルして初期ドロー: %s".formatted(decisionText, drawn));
    }

    /**
     * マリガン(★設計書 4-2・4-4)。
     * <b>サーバが「戻す → シャッフル → 同数ドロー」を1操作として行う。</b>
     *
     * <h3>★クライアントの {@code move} の並びで実装してはならない(4-4)</h3>
     * <ol>
     *   <li><b>順序が保証されない。</b>戻す→シャッフル→引く の間に相手の操作が挟まりうる</li>
     *   <li><b>ログが「マリガン」ではなく「移動 n件 + シャッフル + ドロー」になる</b></li>
     *   <li><b>設計判断27。</b>クライアントが引く枚数を決められると、
     *       戻した枚数と引く枚数を食い違わせられる</li>
     * </ol>
     *
     * <h3>★1回だけ。0枚確定も1回として消費する(P9)</h3>
     * 総合ルール 2-5 の「ゲーム開始前に任意の1回のみ」である。
     *
     * <h3>★戻したカード名はログに出さない(5-2 のマスク規則)</h3>
     * 手札も山札もどちらも非公開ゾーンである。枚数だけを残す。
     *
     * @param cardIds 山札へ戻す手札。空でよい(= マリガンしない)
     */
    public ManualLogEvent mulligan(ManualRoom room, ManualActor actor, ManualSeatId requestSeat,
            List<String> cardIds) {
        ManualSeatId seatId = requestSeat == null ? subjectSeat(room, actor) : requestSeat;
        ManualPermissions.require(ManualPermissions.denySeatAction(actor, seatId));
        if (room.getStartPhase() != ManualStartPhase.MULLIGAN) {
            throw new IllegalStateException("マリガンの場面ではありません");
        }
        if (!room.getMulliganPending().contains(seatId)) {
            throw new IllegalStateException("席%s はマリガンの対象ではありません".formatted(seatId));
        }
        if (room.getMulliganDone().contains(seatId)) {
            throw new IllegalStateException("席%s のマリガンは終わっています(1回だけです)".formatted(seatId));
        }

        ManualSeat seat = room.getGameState().seat(seatId);
        List<ManualCardInstance> hand = seat.zone(ManualZone.HAND);
        List<ManualCardInstance> back = new ArrayList<>();
        // ★同じカードを2回指す要求は弾く(ManualOperationService と同じ「要求が成り立たない」検査)
        List<String> requested = cardIds == null ? List.of() : cardIds;
        Set<String> unique = new LinkedHashSet<>(requested);
        if (unique.size() != requested.size()) {
            throw new IllegalArgumentException("同じカードが2回指定されています");
        }
        for (String cardId : unique) {
            ManualCardInstance card = hand.stream()
                    .filter(c -> c.getInstanceId().equals(cardId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "席%s の手札にないカードです".formatted(seatId)));
            back.add(card);
        }
        hand.removeAll(back);
        // ★戻す先は山札のどこでもよい(4-4)。直後にシャッフルするため末尾で足りる
        for (ManualCardInstance card : back) {
            card.setFaceDown(false);
            seat.zone(ManualZone.DECK).add(card);
        }
        gameService.shuffleDeck(seat);
        int drew = gameService.drawCards(seat, back.size());

        room.getMulliganDone().add(seatId);
        String text = back.isEmpty()
                ? "席%s はマリガンをしなかった".formatted(seatId)
                : "席%s がマリガンで %d枚 戻し、%d枚 引き直した".formatted(seatId, back.size(), drew);
        if (!back.isEmpty() && drew < back.size()) {
            text += "(山札が尽きたため %d枚 は引けなかった)".formatted(back.size() - drew);
        }

        if (room.getMulliganDone().containsAll(room.getMulliganPending())) {
            text = text + " / " + finish(room);
        }
        // ★★Batch 38: 0枚のマリガンでも儀式は作る。「何も起きなかった」ことは
        //   員数(back = 0)が語る。作らないと、画面側が「儀式が無い」と
        //   「儀式が空だった」を区別できなくなる
        return startLog(actor, ManualLogStartRite.mulligan(seatId, back.size(), drew), text);
    }

    /**
     * 開始を確定する(ピュア・エレメントの配布 → {@link ManualStartPhase#PLAYING})。
     *
     * @return ログ本文に足す説明
     */
    private String finish(ManualRoom room) {
        StringBuilder text = new StringBuilder();
        ManualSeatId second = room.secondSeat();
        String pure = dealPureElement(room, second);
        if (pure != null) {
            text.append(pure).append(" / ");
        }
        room.setStartPhase(ManualStartPhase.PLAYING);
        // ★開始シーケンスは Undo できない(2-5・P12)。完了した時点で履歴をクリアする
        room.getHistory().clear();
        text.append("ゲームを開始した(先攻 席%s / 後攻 席%s)"
                .formatted(room.getFirstSeat(), second));
        // ★一人回しで1デッキだけのとき、相手側の席は空のまま先攻(または後攻)になる。
        //   ログだけを見た人が「配り忘れ」と読まないように、そのことを明記する
        for (ManualSeatId seatId : ManualSeatId.values()) {
            if (!room.getMulliganPending().contains(seatId)) {
                text.append(" ※席%s はデッキ未読込".formatted(seatId));
            }
        }
        return text.toString();
    }

    /**
     * 後攻へピュア・エレメントを渡す(★設計書5章 / 総合ルール 2-5 の4)。
     *
     * <h3>★★<b>表向きで渡す</b>(マスター裁定 2026-08-06)</h3>
     * 総合ルール 2-5 の4は「裏向きで渡す」と書いているが、<b>この文言は語弊がある</b>。
     * 手札は持ち主しか見ないゾーンであり(対戦部屋では相手にカードオブジェクトが
     * そもそも届かない。21 設計書 3-3)、裏向きにしても<b>情報上の意味が1ビットも無い</b>。
     * 実物のカードゲームで「裏向きで渡す」のは相手に見せないためであって、
     * 受け取った本人が中身を見られない状態にするためではない。
     * したがって<b>他の手札と同じ扱いにする</b>。
     *
     * ★裏向きにしていた当初の実装では、手札行で
     * 「灰色の箱に(裏向き)と書かれただけのタイル」になり、
     * <b>持ち主にも何のカードか分からない</b>という実害だけが残っていた。
     *
     * ★この判断により {@code HAND} の表向き正規化
     * ({@code ManualOperationService.FACE_UP_ON_ARRIVAL})との衝突も消えた。
     * 正規化を避ける特別な経路が要らなくなり、ゾーンへ素直に置くだけで済む。
     *
     * @return ログに足す説明。配布しなかったときは null
     */
    private String dealPureElement(ManualRoom room, ManualSeatId second) {
        if (second == null || !room.getMulliganPending().contains(second)) {
            return null; // 後攻席がデッキを読み込んでいない(6-2)
        }
        if (pureElementId == null) {
            // ★設定が無くても開始は完了させる(5-2)。省略したことだけを残す
            return "ピュア・エレメントの設定が無いため配布を省略した";
        }
        Optional<ManualCardMaster> master = cards.findOptionalById(pureElementId);
        if (master.isEmpty()) {
            return "ピュア・エレメントが台帳に無いため配布を省略した";
        }
        // ★表向きのまま置く。ManualCardInstance.of は faceDown = false で作る
        ManualCardInstance instance = ManualCardInstance.of(master.get());
        room.getGameState().seat(second).zone(ManualZone.HAND).add(instance);
        return "席%s(後攻)にピュア・エレメントを渡した".formatted(second);
    }

    // ================= 補助 =================

    /** デッキを読み込んでいる席。★{@code lastImport} が配り直しの前提である。 */
    private List<ManualSeatId> loadedSeats(ManualRoom room) {
        List<ManualSeatId> loaded = new ArrayList<>();
        for (ManualSeatId seatId : ManualSeatId.values()) {
            if (room.getGameState().seat(seatId).getLastImport() != null) {
                loaded.add(seatId);
            }
        }
        return loaded;
    }

    /**
     * 「自分が先攻をとる」の<b>「自分」が指す席</b>(★設計書 3-1 の②③)。
     *
     * <h3>★これは権限の判定ではない</h3>
     * 押せるかどうかは {@link ManualPermissions} が決める。ここが答えるのは
     * 「押した結果、どちらの席が先攻になるのか」だけである。
     * ★ビュー({@code ManualStartView.subjectSeat})も同じメソッドを呼ぶ。
     * ボタンの文言と実際の結果が同じ関数を通るため、表示と挙動がズレない(設計判断34)。
     *
     * <h3>★全公開部屋で「デッキが1つだけ」のときは、その席が主語である</h3>
     * (マスター指示 2026-08-06)。全公開部屋は1人が両席を操作するため、
     * そもそも「自分」の指す先が弱い。デッキが片方にしか無いなら、
     * <b>カードがある席以外を主語にしても意味を成さない</b>。
     * 席Bだけ読み込んでいるのに、作成者席Aを主語にして「自分が先攻」を選ぶと、
     * 空席Aが先攻になり席Bが5枚引く — 選んだ内容と結果が逆さまになる。
     *
     * <h3>それ以外</h3>
     * 対戦部屋では常に押した人の席である。全公開部屋で両席が読み込み済みなら、
     * 押した人の席(席に着いていなければ作成者席、それも無ければ席A)を主語にする。
     */
    public ManualSeatId subjectSeat(ManualRoom room, ManualActor actor) {
        if (!actor.isRestricted()) {
            List<ManualSeatId> loaded = loadedSeats(room);
            if (loaded.size() == 1) {
                return loaded.get(0);
            }
        }
        if (actor.seat() != null) {
            return actor.seat();
        }
        return room.getCreatorSeat() == null ? ManualSeatId.A : room.getCreatorSeat();
    }

    private ManualSeatId other(ManualSeatId seatId) {
        return seatId == ManualSeatId.A ? ManualSeatId.B : ManualSeatId.A;
    }

    /** ★{@link ManualLogKind#START} は {@code plain} である(21a「plain の分類を必ず決める」)。 */
    private ManualLogEvent startLog(ManualActor actor, String text) {
        return ManualLogEvent.plain(ManualLogKind.START, actor.seat(), text);
    }

    /**
     * ★★Batch 38: 儀式を伴う開始ログ。{@link ManualLogEvent#startRite} を呼ぶ<b>唯一の場所</b>である。
     *
     * ★構造の有無で入口を2つに割ってあるのは、START 行には構造を持たないものが
     * 正当に存在するからである(準備の開始・選択権の告知)。
     * 「構造を持つなら必ずここを通る」だけを守り、
     * <b>守れない約束(全 START 行が構造を持つ)は型に書かない</b>。
     */
    private ManualLogEvent startLog(ManualActor actor, ManualLogStartRite rite, String text) {
        return ManualLogEvent.startRite(actor.seat(), rite, text);
    }
}
