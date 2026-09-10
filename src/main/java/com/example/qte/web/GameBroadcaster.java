package com.example.qte.web;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import com.example.qte.game.view.GameView;
import com.example.qte.game.view.GameViewBuilder;
import com.example.qte.room.GameRoom;
import com.example.qte.room.PlayerSlot;
import com.example.qte.room.Spectator;

import lombok.RequiredArgsConstructor;

/**
 * 盤面ビューの配信担当。
 * 同じGameStateから「プレイヤーごとに違うビュー」を組み立てて、
 * それぞれ専用の宛先(/topic/room/{roomId}/player/{playerId})に送り分ける。
 * playerIdは推測不能なUUIDなので、この宛先が実質的な「本人だけの受信箱」になる。
 *
 * ペイロードはMapではなくWsMessage型で送る。Spring Framework 7の
 * convertAndSendには「ペイロード＋ヘッダーMap」を受けるオーバーロードがあり、
 * Mapを第2引数に渡すと呼び出しが曖昧になってコンパイルエラーになるため
 * (加えて、送信プロトコルの形が型として明文化される利点もある)。
 */
@Component
@RequiredArgsConstructor
public class GameBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;
    private final GameViewBuilder viewBuilder;

    /**
     * 解決の途中の1段(★Batch 84a・裁定362)。
     *
     * <p>★<b>段の正はログ行である。</b>{@code logLine} はその段が語る出来事そのものであり、
     * 右列のログに積まれた行と<b>1対1で対応する</b> ——
     * 演出で何が起きたか分からなくても、<b>同じ瞬間のログ行が必ず答えを持っている</b>。
     *
     * <p>★{@code seq} は1から始まる通し番号である。★★<b>飛び番は作らない</b>
     * ({@link StepRecorder#stepsFor} は、1段でも欠けたらまるごと空を返す)。
     *
     * <p>★★★{@code view} は<b>その閲覧者の視点</b>である ——
     * 視点フィルタは {@code GameViewBuilder} の1本だけを通る(設計判断9)。
     */
    public record GameStep(int seq, String logLine, GameView view) {
    }

    /**
     * クライアントへ送るメッセージの型。
     * type=VIEW のとき view が入り、type=ERROR のとき message が入る。
     * ★Batch 72: type=LEFT(退室が受理された)が3つ目である。どちらも入らない。
     * ★★Batch 75: type=ROOM_LOST(部屋がもう無い)が4つ目である。これもどちらも入らない。
     */
    public record WsMessage(String type, GameView view, List<GameStep> steps, int foldedSteps,
            String message) {

        static WsMessage ofView(GameView view, List<GameStep> steps, int foldedSteps) {
            return new WsMessage("VIEW", view, steps, foldedSteps, null);
        }

        static WsMessage ofError(String message) {
            return new WsMessage("ERROR", null, List.of(), 0, message);
        }

        /**
         * 部屋がサーバ上にもう無い(★Batch 75・裁定344)。
         *
         * <p>★★★<b>ERROR で代用しない。</b>72 が {@link #ofLeft} について書いたのと同じ理由である ——
         * ERROR は「その操作が拒否された理由」であって、画面はそれを出して<b>その場に留まる</b>。
         * 部屋消失は<b>留まれない</b>(次のどの操作も同じ結末になる)。
         *
         * <p>★★<b>手動モードは本文の文字列で判定している</b>
         * ({@code msg.message === 'この部屋に入室していません'})。
         * あれは<b>サーバの文言を1文字直しただけで黙って効かなくなる</b> ——
         * 実際に効かなくなっても、画面は「エラーが出た」ように見えるので誰も気づかない
         * (74 の教訓「効きすぎている実装は別の規則の陰に隠れる」の裏側の形である)。
         * ★通常モードは型で運ぶ。手動モードを揃えていないことは設計解説に書き残した。
         */
        static WsMessage ofRoomLost() {
            return new WsMessage("ROOM_LOST", null, List.of(), 0, null);
        }

        /**
         * 退室が受理された(★Batch 72)。★view も message も持たない。
         *
         * <p>★<b>ERROR で代用しない。</b>ERROR は「拒否された理由」であり、
         * 画面はそれを {@code showMessage} に出して<b>その場に留まる</b>。
         * 退室はその逆(受理されたのでページを離れる)であって、
         * 同じ型に2つの意味を載せると<b>どちらの向きか分からない分岐</b>が増える。
         */
        static WsMessage ofLeft() {
            return new WsMessage("LEFT", null, List.of(), 0, null);
        }
    }

    /**
     * 部屋の全員に、それぞれの視点のビューを配信する。
     *
     * <p>★<b>Batch 66: 観戦者も宛先になった。</b>観戦者ぶんの絞り込みは
     * {@code GameViewBuilder} が1本で行う —— この層が「観戦者にはこれを送らない」を
     * 判断しはじめると、フィルタが配信層とビルダー層に割れる(設計判断9)。
     * <b>ここの仕事は「誰に送るか」だけであり、「何を見せるか」ではない。</b>
     */
    public void broadcast(GameRoom room, StepRecorder recorder) {
        for (String viewerId : viewerIdsOf(room)) {
            sendViewTo(room, viewerId, recorder);
        }
    }

    /**
     * 配信の宛先になる人の一覧(★Batch 84a で1箇所に出した)。
     *
     * <p>★<b>段を控えるときと配るときで、同じ一覧を使う。</b>2箇所で数えると、
     * <b>控えた相手と配る相手がずれても誰も気づかない</b>(裁定130・口は1本)。
     */
    private List<String> viewerIdsOf(GameRoom room) {
        List<String> ids = new ArrayList<>();
        for (PlayerSlot slot : room.getSlots()) {
            ids.add(slot.getPlayerId());
        }
        for (Spectator spectator : room.getSpectators()) {
            ids.add(spectator.spectatorId());
        }
        return ids;
    }

    /**
     * その部屋のいまの宛先ぶんを控える記録係を作る(★Batch 84a)。
     *
     * <p>★<b>ビルダーを持っているのはこの層である。</b>記録係を
     * {@code GameWsController} が自分で組み立てると、
     * <b>「何を見せるか」の知識が入口の層へ漏れる</b>(設計判断9)。
     */
    public StepRecorder newRecorder(GameRoom room) {
        return StepRecorder.forViewers(viewBuilder, room, viewerIdsOf(room));
    }

    private void sendViewTo(GameRoom room, String viewerId, StepRecorder recorder) {
        GameView view = viewBuilder.build(room, viewerId);
        messagingTemplate.convertAndSend(destinationOf(room.getRoomId(), viewerId),
                WsMessage.ofView(view, recorder.stepsFor(viewerId), recorder.folded()));
    }

    /** 特定プレイヤーへのエラー通知(ルール違反の操作を拒否したとき) */
    public void sendError(String roomId, String playerId, String message) {
        messagingTemplate.convertAndSend(destinationOf(roomId, playerId),
                WsMessage.ofError(message));
    }

    /**
     * 退室が受理されたことを、退室した本人へ1通だけ返す(★Batch 72)。
     *
     * <p>★<b>{@link #broadcast} では届かない。</b>退室した時点で、その人は
     * 席にも観戦者にも居ない —— 配信の宛先の一覧から消えている。
     * ★だからといって「退室する前に送る」形にはしない。
     * それは<b>まだ受理されていない</b>ものを受理したと言うことである
     * (退室は失敗しうる。{@code GameRoom.leave} を参照)。
     */
    public void sendLeft(String roomId, String occupantId) {
        messagingTemplate.convertAndSend(destinationOf(roomId, occupantId), WsMessage.ofLeft());
    }

    /**
     * 部屋がもう無いことを、操作しようとした人へ返す(★Batch 75・裁定344)。
     *
     * <p>★<b>部屋が無いので {@link #broadcast} は使えない</b> ——
     * 宛先の一覧を持っているのは部屋だからである。{@link #sendLeft} と同じ形で、
     * <b>操作を送ってきた1人にだけ</b>返す。
     *
     * <p>★★<b>他の在室者には届かない。</b>部屋が消えたときに全員へ知らせる経路は無い ——
     * 台帳から消えた時点で、誰が居たかを知っているものが1つも無いためである。
     * ★それでよい: 部屋が消えるのは<b>全員が切断してから</b>なので(裁定342)、
     * 知らせる相手はどのみち居ない。戻ってきた人が {@code ready} を撃った瞬間にこれを受け取る。
     */
    public void sendRoomLost(String roomId, String occupantId) {
        messagingTemplate.convertAndSend(destinationOf(roomId, occupantId), WsMessage.ofRoomLost());
    }

    private String destinationOf(String roomId, String playerId) {
        return "/topic/room/%s/player/%s".formatted(roomId, playerId);
    }
}
