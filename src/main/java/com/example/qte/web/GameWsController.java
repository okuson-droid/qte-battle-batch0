package com.example.qte.web;

import org.springframework.messaging.Message;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.qte.effect.TargetChoice;
import com.example.qte.game.GameService;
import com.example.qte.room.GameRoom;
import com.example.qte.room.GameRoomManager;
import com.example.qte.room.SeatId;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * WebSocketメッセージの入口。MVCの@Controllerに相当する層で、役割も同じ:
 * 「受け取る・検証を業務層に任せる・結果を返す」に徹し、ルールの中身は持たない。
 *
 * すべてのハンドラは共通の型で処理する:
 *   1) 部屋を特定する
 *   2) synchronized(room.getLock()) で「1部屋1操作」に直列化する
 *   3) GameServiceで状態を変更する(ルール違反は例外で拒否される)
 *   4) 成功: 両者へビューを配信 / 失敗: 操作者にだけエラーを返す
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class GameWsController {

    private final GameRoomManager roomManager;
    private final GameService gameService;
    private final GameBroadcaster broadcaster;

    /**
     * 入室したクライアントの購読準備完了通知。両者揃ったら試合を生成する。
     *
     * <p>★<b>Batch 66: 観戦者もこれを送る。</b>観戦者に「準備完了」は無いが、
     * 送らせないと<b>最初のビューが届くのが誰かの次の操作まで待ちになる</b>
     * (配信は誰かが動いたときにしか起きない)。席に着いていない人は
     * 準備の旗を立てず、{@code execute} の末尾の配信だけを受け取る。
     * ★席にも観戦者にも見つからない id は、理由を返して弾く。
     */
    @MessageMapping("/room/{roomId}/ready")
    public void ready(@DestinationVariable String roomId, ActionRequest request) {
        execute(roomId, request, room -> {
            var slot = room.findSlot(request.playerId()).orElse(null);
            if (slot == null) {
                room.findSpectator(request.playerId()).orElseThrow(
                        () -> new IllegalStateException("この部屋に入室していません"));
                return;
            }
            slot.setReady(true);
            gameService.startIfBothReady(room);
        });
    }

    /** ダイス勝者による先攻/後攻の選択 */
    @MessageMapping("/room/{roomId}/choose-order")
    public void chooseOrder(@DestinationVariable String roomId, ChooseOrderRequest request) {
        execute(roomId, request.playerId(),
                room -> gameService.chooseOrder(room, request.playerId(), request.goFirst()));
    }

    /** マナチャージ */
    @MessageMapping("/room/{roomId}/charge-mana")
    public void chargeMana(@DestinationVariable String roomId, HandActionRequest request) {
        execute(roomId, request.playerId(),
                room -> gameService.chargeMana(room, request.playerId(), request.handIndex()));
    }

    /** 手札のカードをプレイ(ミニオン召喚・スペル使用)。対象指定があればtargetsに載せて送られる */
    @MessageMapping("/room/{roomId}/play-card")
    public void playCard(@DestinationVariable String roomId, PlayCardRequest request) {
        execute(roomId, request.playerId(), room -> gameService.playCard(
                room, request.playerId(), request.handIndex(), request.targets(), request.enhanced(),
                request.materialIds(), request.manaIndexes()));
    }

    /**
     * 手札のミニオンを【賢魂：n】として使う(★Batch 54。裁定152)。
     *
     * ★<b>{@code play-card} と別の宛先にしている。</b> どちらの姿で使うかは
     * カードの種別ではなく<b>プレイヤーの宣言</b>であり、宛先そのものが宣言になる。
     * 素材も強化コストも伴わないので {@code materialIds} / {@code enhanced} は読まない。
     */
    @MessageMapping("/room/{roomId}/play-soul")
    public void playSoul(@DestinationVariable String roomId, PlayCardRequest request) {
        execute(roomId, request.playerId(), room -> gameService.playSoulCard(
                room, request.playerId(), request.handIndex(), request.targets(),
                request.manaIndexes()));
    }

    /** 禁忌カードの使用(メインフェイズのみ・マナで直接コストを支払う) */
    @MessageMapping("/room/{roomId}/play-taboo")
    public void playTaboo(@DestinationVariable String roomId, TabooRequest request) {
        execute(roomId, request.playerId(), room -> gameService.playTabooCard(
                room, request.playerId(), request.tabooIndex(),
                request.manaIndexes(), request.targets(), request.materialIds()));
    }

    /**
     * 禁忌カードを【賢魂：n】として使う(★Batch 54。マスター裁定 A6)。
     * 退けるマナは n 枚である —— 賢魂として使うならコストは n だからである。
     */
    @MessageMapping("/room/{roomId}/play-taboo-soul")
    public void playTabooSoul(@DestinationVariable String roomId, TabooRequest request) {
        execute(roomId, request.playerId(), room -> gameService.playTabooSoulCard(
                room, request.playerId(), request.tabooIndex(),
                request.manaIndexes(), request.targets()));
    }

    /** 【特殊召喚】(条件・代替コストによる代替召喚) */
    @MessageMapping("/room/{roomId}/special-summon")
    public void specialSummon(@DestinationVariable String roomId, PlayCardRequest request) {
        execute(roomId, request.playerId(), room -> gameService.specialSummon(
                room, request.playerId(), request.handIndex(), request.targets(),
                request.materialIds(), request.manaIndexes()));
    }

    /**
     * <b>墓地からの</b>【特殊召喚】(★Batch 53。《サモナーポップ・エンラ》)。
     * 手札からの特殊召喚と違うのは出どころだけで、素材も対象も同じ形で送る。
     */
    @MessageMapping("/room/{roomId}/special-summon-from-grave")
    public void specialSummonFromGrave(@DestinationVariable String roomId, GraveSummonRequest request) {
        execute(roomId, request.playerId(), room -> gameService.specialSummonFromGrave(
                room, request.playerId(), request.trashIndex(), request.targets(),
                request.materialIds()));
    }

    /** 攻撃(targetInstanceIdがnullならリーダー攻撃) */
    @MessageMapping("/room/{roomId}/attack")
    public void attack(@DestinationVariable String roomId, AttackRequest request) {
        execute(roomId, request.playerId(), room -> gameService.attack(
                room, request.playerId(), request.attackerInstanceId(), request.targetInstanceId()));
    }

    /** マリガン(手札の引き直し)。handIndexesが空なら引き直しなしで確定 */
    @MessageMapping("/room/{roomId}/mulligan")
    public void mulligan(@DestinationVariable String roomId, MulliganRequest request) {
        execute(roomId, request.playerId(), room -> gameService.mulligan(
                room, request.playerId(), request.handIndexes()));
    }

    /** リーダーの攻撃(ウェポン装備時のみ)。targetInstanceIdがnullならリーダー攻撃 */
    @MessageMapping("/room/{roomId}/leader-attack")
    public void leaderAttack(@DestinationVariable String roomId, AttackRequest request) {
        execute(roomId, request.playerId(), room -> gameService.leaderAttack(
                room, request.playerId(), request.targetInstanceId()));
    }

    /** リーダーの起動能力(メインフェイズ・1ターン1回) */
    @MessageMapping("/room/{roomId}/leader-ability")
    public void leaderAbility(@DestinationVariable String roomId, LeaderAbilityRequest request) {
        execute(roomId, request.playerId(), room -> gameService.useLeaderAbility(
                room, request.playerId(), request.targets()));
    }

    /** ミニオンの起動能力(メインフェイズ・自身をタップ。a6) */
    @MessageMapping("/room/{roomId}/minion-ability")
    public void minionAbility(@DestinationVariable String roomId, MinionAbilityRequest request) {
        execute(roomId, request.playerId(), room -> gameService.useMinionAbility(
                room, request.playerId(), request.instanceId(), request.targets()));
    }

    /**
     * 墓地からのミニオン召喚(リーダー【黄泉の召喚主】のみ・サブフェイズ)。
     *
     * ★<b>Batch 60(裁定278(c)): 対象を選ぶ【召喚時】も通るようになった。</b>
     * 受け取る型を {@code TrashActionRequest} から {@link GraveSummonRequest} へ変えている ——
     * 墓地からの【特殊召喚】と<b>まったく同じ形</b>(墓地の位置 + 対象)であり、
     * 型を2つに分ける理由が無い。{@code materialIds} はこちらでは読まない
     * (墓地からの「召喚」で出せるのはミニオンだけで、進化は【特殊召喚】の側を通る)。
     */
    @MessageMapping("/room/{roomId}/summon-from-grave")
    public void summonFromGrave(@DestinationVariable String roomId, GraveSummonRequest request) {
        execute(roomId, request.playerId(), room -> gameService.summonFromGrave(
                room, request.playerId(), request.trashIndex(), request.targets()));
    }

    /** フェイズを1つ進める */
    @MessageMapping("/room/{roomId}/next-phase")
    public void nextPhase(@DestinationVariable String roomId, ActionRequest request) {
        execute(roomId, request.playerId(),
                room -> gameService.nextPhase(room, request.playerId()));
    }

    /**
     * 割り込み選択の解決(a9)。効果の途中でプレイヤーに問い合わせた選択の答えを受け取る。
     * 降臨の伝道師をはじめ、風文明の各カードがこの1本の経路を共有する。
     */
    @MessageMapping("/room/{roomId}/resolve-choice")
    public void resolveChoice(@DestinationVariable String roomId, ResolveChoiceRequest request) {
        execute(roomId, request.playerId(), room -> gameService.resolveChoice(
                room, request.playerId(), request.chosenIndexes()));
    }

    /** ターン終了(残りフェイズを飛ばして相手にターンを渡す) */
    @MessageMapping("/room/{roomId}/end-turn")
    public void endTurn(@DestinationVariable String roomId, ActionRequest request) {
        execute(roomId, request.playerId(),
                room -> gameService.endTurn(room, request.playerId()));
    }

    // ---------------------------------------------------------------
    // ★★★試合の出入り(Batch 72)
    // ---------------------------------------------------------------
    //
    // ★<b>WebSocket に置いた。</b>66 の役割分担は「ページを開くまでと、
    //   盤面に持ち込むファイルは HTTP / 開いたあとの操作は WebSocket」である。
    //   席を立つ・座る・退室・投了・再戦は<b>どれも開いたあとの操作</b>であり、
    //   しかも全員の画面を変える —— execute の末尾の配信がそのまま要る。
    //   ★手動モードの /manual/{roomId}/seat・/leave と同じ場所である。

    /**
     * 席に着く / 席を立つ(★Batch 72)。★手動モードの {@code seat} と同じ形にしてある ——
     * {@code seat} が null なら席を立ち、非 null ならその席に着く。
     *
     * <p>★<b>手動モードと違うのは「観戦できない部屋で席を立ったとき」だけである。</b>
     * あちらは退室に読み替えるが、こちらは<b>断る</b>(マスター確認)。
     * 通常モードには {@link #leave} が別に在り、押した人が自分で選ぶ ——
     * 1つのボタンが部屋の設定しだいで別のことをするのは、
     * 「押すつもりが無かった」を作る形である(設計判断47 の筋)。
     */
    @MessageMapping("/room/{roomId}/seat")
    public void seat(@DestinationVariable String roomId, SeatRequest request) {
        execute(roomId, request.playerId(), room -> {
            if (request.seat() == null) {
                gameService.standUp(room, request.playerId());
            } else {
                gameService.takeSeat(room, request.playerId(), request.seat());
            }
        });
    }

    /**
     * 退室(★Batch 72)。席に着いていた人も観戦者も同じ口を通る。
     *
     * <h2>★★★手動モードの形は写せなかった</h2>
     * 手動モードは {@code send('leave'); forgetOccupant(); location.href = '/';} と
     * <b>送って即座に遷移する</b>。それが成り立つのは
     * <b>あちらの退室が失敗しないから</b>である。
     *
     * <p>通常モードの退室は<b>失敗しうる</b>(対戦中の着席者は断られる)。
     * 同じ形で書くと、断られたときの理由({@code sendError})が
     * <b>ロビーへ遷移したあとの端末</b>へ届き、誰も読まない ——
     * しかも localStorage は既に消えているので、<b>席に着いたまま戻れなくなる</b>。
     * ★71 の教訓「同じ穴を塞ぐことと、同じ形で塞ぐことは別である」の2例目である。
     *
     * <p>→ <b>受理されたことを本人へ1通返す</b>({@code WsMessage} の type=LEFT)。
     * クライアントはそれを見てから localStorage を消して遷移する。
     * ★退室した本人はもう {@code broadcast} の宛先ではないので、別に送るしかない。
     * ★★この形は切断にも強い —— 送れていなければ返事も来ないので、
     * <b>ローカルでは何も起きない</b>(設計判断49 がそのまま効く)。
     */
    @MessageMapping("/room/{roomId}/leave")
    public void leave(@DestinationVariable String roomId, ActionRequest request) {
        execute(roomId, request.playerId(),
                room -> gameService.leave(room, request.playerId()),
                () -> broadcaster.sendLeft(roomId, request.playerId()));
    }

    /**
     * 投了(★Batch 72)。★<b>いつでも押せる</b>(相手のターン中・割り込み待ち・マリガン中)。
     * 詰まったときの逃げ道は、詰まりの原因になっている規則に左右されてはいけない。
     */
    @MessageMapping("/room/{roomId}/concede")
    public void concede(@DestinationVariable String roomId, ActionRequest request) {
        execute(roomId, request.playerId(),
                room -> gameService.concede(room, request.playerId()));
    }

    /**
     * 再戦の申し込み・承諾・辞退(★Batch 72)。
     *
     * <p>★<b>宛先は1つである。</b>3つとも「再戦」という1つの話題の手であり、
     * 宛先を割ると {@code sfxForAction} と機械検証の照合先が3つに増える。
     * ★{@code play-soul} を {@code play-card} と別宛先にしたのは
     * <b>どちらの姿で使うかという宣言</b>だったからであり(裁定152)、性質が違う。
     */
    @MessageMapping("/room/{roomId}/rematch")
    public void rematch(@DestinationVariable String roomId, RematchRequest request) {
        execute(roomId, request.playerId(),
                room -> gameService.rematch(room, request.playerId(), request.action()));
    }

    // ---- 共通処理 ----

    private void execute(String roomId, ActionRequest request, RoomAction action) {
        execute(roomId, request.playerId(), action);
    }

    private void execute(String roomId, String playerId, RoomAction action) {
        execute(roomId, playerId, action, null);
    }

    /**
     * @param onSuccess 配信のあとに1度だけ走る後始末。★Batch 72 の {@link #leave} だけが使う ——
     *                  <b>退室した本人は配信の宛先から消えている</b>ので、
     *                  「受理された」を伝える経路がここにしか無い。
     *                  ★null 可。増やすときは「配信では届かない相手が居るか」を根拠にすること。
     */
    private void execute(String roomId, String playerId, RoomAction action, Runnable onSuccess) {
        GameRoom room = roomManager.findRoom(roomId)
                .orElse(null);
        if (room == null) {
            broadcaster.sendError(roomId, playerId, "部屋が見つかりません: " + roomId);
            return;
        }
        try {
            synchronized (room.getLock()) {
                action.apply(room);
            }
            broadcaster.broadcast(room);
            if (onSuccess != null) {
                onSuccess.run();
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            // ルール違反: 状態は変更されていないので、操作者にだけ理由を返す
            broadcaster.sendError(roomId, playerId, e.getMessage());
        }
    }

    /**
     * ★★★Batch 72b: <b>読めなかったメッセージを黙って捨てない</b>(設計判断51)。
     *
     * <p>賢魂の不具合は、コードの筋がどこも間違っていないのに直らなかった ——
     * クライアントは正しく送り、サーバのハンドラは正しく書かれていたが、
     * <b>その二つの間</b>でメッセージが捨てられていたからである。
     * 変換に失敗したメッセージはハンドラに入らないので {@code execute} を通らず、
     * {@code sendError} の経路にも乗らない。画面には何も起きない。
     *
     * <p>→ 変換の失敗をここで受け、<b>操作した人に返す</b>。
     * ★宛先(roomId)はヘッダから取れる。playerId は<b>読めなかった本文</b>の中にしかないので、
     * そこから1つの項目だけを拾う —— 本文全体をもう一度解釈することはしない
     * (解釈できないと分かっている物である)。拾えなければサーバのログだけが残る。
     *
     * <p>★<b>これは番人であって、直し方ではない。</b>原因そのものは
     * 「クライアントが送らない項目を原始型で受けていたこと」であり、
     * それは {@link PlayCardRequest#enhanced()} 側で直してある。
     */
    @MessageExceptionHandler(MessageConversionException.class)
    public void onUnreadableMessage(MessageConversionException e, Message<byte[]> message) {
        String destination = SimpMessageHeaderAccessor.getDestination(message.getHeaders());
        log.error("受け取れないメッセージ: destination={} {}", destination, e.getMessage());
        String roomId = roomIdOf(destination);
        String playerId = playerIdOf(message.getPayload());
        if (roomId == null || playerId == null) {
            return;
        }
        broadcaster.sendError(roomId, playerId,
                "操作を受け取れませんでした(送信の形が不正です)。画面を再読み込みしてください");
    }

    /** {@code /app/room/{roomId}/{action}} の roomId。形が違えば null */
    private static String roomIdOf(String destination) {
        if (destination == null) {
            return null;
        }
        Matcher m = ROOM_IN_DESTINATION.matcher(destination);
        return m.find() ? m.group(1) : null;
    }

    /** 読めなかった本文から playerId だけを拾う。見つからなければ null */
    private static String playerIdOf(byte[] payload) {
        Matcher m = PLAYER_ID_IN_BODY.matcher(new String(payload, StandardCharsets.UTF_8));
        return m.find() ? m.group(1) : null;
    }

    private static final Pattern ROOM_IN_DESTINATION = Pattern.compile("/room/([^/]+)/");
    private static final Pattern PLAYER_ID_IN_BODY =
            Pattern.compile("\"playerId\"\\s*:\\s*\"([^\"]+)\"");

    @FunctionalInterface
    private interface RoomAction {
        void apply(GameRoom room);
    }

    /**
     * ★★★Batch 73: <b>送られてこなければ断る関門</b>(72b の宿題)。
     *
     * <p>72b で {@code enhanced} を箱型にしたのは、
     * <b>原始型の項目が本文に無いと変換ごと失敗する</b>(そしてそれが誰にも返らない)からである。
     * 残りの項目も同じ地雷を踏みうるので、73 ですべて箱型にした ——
     * <b>ただし、箱型にすることと畳むことは別である。</b>
     *
     * <h2>★畳んでよいのは、畳んだ先が「何もしない」に落ちるときだけである</h2>
     * <ul>
     *   <li>{@code enhanced} …… 無ければ false = <b>通常の使用</b>。
     *       {@code play-soul} は送らないのが正しいので、畳むのが正しい</li>
     *   <li>{@code handIndex} …… 無ければ 0 = <b>手札の1枚目をプレイする</b>。
     *       送っていない人の意図と何の関係も無い操作が通ってしまう</li>
     *   <li>{@code goFirst} …… 無ければ false = <b>後攻を選ぶ</b>。
     *       ダイスに勝った人が選び直せない、取り返しのつかない選択である</li>
     * </ul>
     * → <b>既定値が「何もしない」にならない項目は、畳まずに断る。</b>
     *
     * <h2>★なぜ {@code IllegalArgumentException} なのか</h2>
     * 素の自動開封({@code Integer} → {@code int})に任せると
     * {@link NullPointerException} になり、{@link #execute} はそれを捕まえない ——
     * <b>また無言に戻る</b>。ここで投げる2種類の例外だけが、
     * 「操作した人へ理由を返す」経路に乗る。
     *
     * <p>★アクセサから呼ぶので、評価されるのは {@code execute} のラムダの中である
     * (= try の中である)。呼び出し側で先に開封しないこと。
     */
    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "操作に必要な項目が送られていません: " + field);
        }
        return value;
    }

    // ---- クライアントから受け取るメッセージの型 ----

    public record ActionRequest(String playerId) {
    }

    /**
     * ダイスの勝者による先攻 / 後攻の選択。
     *
     * @param goFirst ★Batch 73: <b>箱型だが畳まない</b>({@link #required})。
     *                無いときに false と読むと<b>後攻を選んだこと</b>になり、
     *                しかもこの選択はやり直せない。
     */
    public record ChooseOrderRequest(String playerId, Boolean goFirst) {

        public Boolean goFirst() {
            return required(goFirst, "goFirst");
        }
    }

    /**
     * 手札の位置だけを送る操作(マナチャージ)。
     *
     * @param handIndex ★Batch 73: <b>箱型だが畳まない</b>({@link #required})。
     *                  無いときに 0 と読むと<b>手札の1枚目をマナに置く</b>ことになる。
     */
    public record HandActionRequest(String playerId, Integer handIndex) {

        public Integer handIndex() {
            return required(handIndex, "handIndex");
        }
    }

    // ★Batch 60: TrashActionRequest(playerId + trashIndex だけ)は削除した。
    // 最後の使い手だった summon-from-grave が、裁定278(c) で対象を伴うようになり
    // GraveSummonRequest へ移ったためである。「墓地の位置を送る操作」の型は1つでよい。

    /**
     * @param materialIds ★Batch 52。進化召喚の素材にする自分の場のミニオンの instanceId。
     *                    進化ミニオン以外では空(または未送信)である
     */
    /**
     * @param manaIndexes ★Batch 70(裁定319): 払うマナの位置。
     *                    <b>クリックからのプレイだけが送ってくる</b> ——
     *                    ドラッグ&ドロップは空で送り、サーバが
     *                    {@code ManaPayment.normalOrder} の順に自動で払う(裁定315・316)。
     * @param enhanced    ★Batch 72b(不具合修正): <b>箱型である。</b>
     *                    {@code play-soul} はこの宛先を共用しながら
     *                    <b>強化使用を持たない</b>ので、送ってこない
     *                    ({@link #playSoul} の説明も「読まない」と書いている)。
     *                    原始型 {@code boolean} のままだと、
     *                    <b>送られてこないこと自体が変換の失敗</b>になり、
     *                    ハンドラに入る前にメッセージごと捨てられる ——
     *                    しかも捨てたことは誰にも返らない。
     *                    ★この形は {@code materialIds} / {@code manaIndexes} と同じである。
     */
    public record PlayCardRequest(String playerId, Integer handIndex,
            List<TargetChoice> targets, Boolean enhanced, List<String> materialIds,
            List<Integer> manaIndexes) {

        /**
         * ★Batch 73: <b>箱型だが畳まない</b>({@link #required})。
         * 無いときに 0 と読むと<b>手札の1枚目をプレイする</b>ことになる ——
         * {@code enhanced} と同じ record に居ながら、扱いは逆である。
         */
        public Integer handIndex() {
            return required(handIndex, "handIndex");
        }

        public Boolean enhanced() {
            return enhanced != null && enhanced;
        }

        public List<String> materialIds() {
            return materialIds == null ? List.of() : materialIds;
        }

        public List<Integer> manaIndexes() {
            return manaIndexes == null ? List.of() : manaIndexes;
        }
    }

    /**
     * 墓地を出どころにする召喚(★Batch 53)。手札の位置ではなく墓地の位置を送る。
     *
     * ★Batch 60: <b>墓地からの【特殊召喚】と、墓地からの召喚(《黄泉の召喚主》)が共用する。</b>
     * 後者は {@code materialIds} を読まない —— 出せるのはミニオンだけだからである。
     */
    public record GraveSummonRequest(String playerId, Integer trashIndex,
            List<TargetChoice> targets, List<String> materialIds) {

        /**
         * ★Batch 73: <b>箱型だが畳まない</b>({@link #required})。
         * 無いときに 0 と読むと<b>墓地のいちばん古い1枚</b>を出すことになる。
         */
        public Integer trashIndex() {
            return required(trashIndex, "trashIndex");
        }

        public List<String> materialIds() {
            return materialIds == null ? List.of() : materialIds;
        }
    }

    public record LeaderAbilityRequest(String playerId, List<TargetChoice> targets) {
    }

    public record MulliganRequest(String playerId, List<Integer> handIndexes) {
    }

    public record TabooRequest(String playerId, Integer tabooIndex,
            List<Integer> manaIndexes, List<TargetChoice> targets, List<String> materialIds) {

        /**
         * ★Batch 73: <b>箱型だが畳まない</b>({@link #required})。
         * 無いときに 0 と読むと<b>禁忌デッキの1枚目</b>を使うことになる ——
         * しかも禁忌は使い終わると消滅ゾーンへ行き、二度と戻らない。
         */
        public Integer tabooIndex() {
            return required(tabooIndex, "tabooIndex");
        }

        public List<String> materialIds() {
            return materialIds == null ? List.of() : materialIds;
        }
    }

    public record AttackRequest(String playerId, String attackerInstanceId, String targetInstanceId) {
    }

    /** 割り込み選択の答え。選んだ候補の位置(PendingChoice.candidates内の0起点)の一覧 */
    public record ResolveChoiceRequest(String playerId, List<Integer> chosenIndexes) {
    }

    public record MinionAbilityRequest(String playerId, String instanceId, List<TargetChoice> targets) {
    }

    /**
     * 席に着く / 立つ(★Batch 72)。{@code seat} が null なら席を立つ。
     * ★手動モードの {@code SeatRequest} と同じ形である(型は共有していない。{@link SeatId} を参照)。
     */
    public record SeatRequest(String playerId, SeatId seat) {
    }

    /** 再戦(★Batch 72)。★1つの話題の3手を1つの宛先で受ける */
    public record RematchRequest(String playerId, GameService.RematchAction action) {
    }
}
