package com.example.qte.web;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

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
     * クライアントへ送るメッセージの型。
     * type=VIEW のとき view が入り、type=ERROR のとき message が入る。
     * ★Batch 72: type=LEFT(退室が受理された)が3つ目である。どちらも入らない。
     * ★★Batch 75: type=ROOM_LOST(部屋がもう無い)が4つ目である。これもどちらも入らない。
     */
    public record WsMessage(String type, GameView view, String message) {

        static WsMessage ofView(GameView view) {
            return new WsMessage("VIEW", view, null);
        }

        static WsMessage ofError(String message) {
            return new WsMessage("ERROR", null, message);
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
            return new WsMessage("ROOM_LOST", null, null);
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
            return new WsMessage("LEFT", null, null);
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
    public void broadcast(GameRoom room) {
        for (PlayerSlot slot : room.getSlots()) {
            sendViewTo(room, slot.getPlayerId());
        }
        for (Spectator spectator : room.getSpectators()) {
            sendViewTo(room, spectator.spectatorId());
        }
    }

    private void sendViewTo(GameRoom room, String viewerId) {
        GameView view = viewBuilder.build(room, viewerId);
        messagingTemplate.convertAndSend(destinationOf(room.getRoomId(), viewerId),
                WsMessage.ofView(view));
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
