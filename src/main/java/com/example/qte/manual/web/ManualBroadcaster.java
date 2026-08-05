package com.example.qte.manual.web;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.example.qte.manual.ManualDragCue;
import com.example.qte.manual.ManualOccupant;
import com.example.qte.manual.ManualRoom;
import com.example.qte.manual.ManualViewpoint;
import com.example.qte.manual.view.ManualGameView;
import com.example.qte.manual.view.ManualViewBuilder;

import lombok.RequiredArgsConstructor;

/**
 * 手動モードの盤面配信。
 *
 * <h2>★配信は在室者ごとの個別宛先である(設計書 2-4・レビューD反映)</h2>
 * 宛先は {@code /topic/manual/{roomId}/view/{occupantId}} で、occupantId は
 * 入室時にサーバが発行するランダムUUIDである。
 *
 * 席共通・観戦共通のトピックにしなかった理由はフェイズ2にある。
 * SimpleBroker は購読を認可しないため、そうした宛先を使うと
 * <b>部屋IDを知っているだけで(入室手続きを踏まずに)全情報を購読できてしまう</b>。
 * 個別宛先なら、occupantId を持たない者に届く宛先がそもそも存在しない。
 * ★Batch 21a でこの判断が実際に効き始めた。同じ部屋の在室者に対して
 * <b>中身の違うビューを送る</b>には、宛先が分かれていることが前提になる。
 * 観戦を許可しない部屋で観戦者に何も届かないのも、宛先が存在しないからである(2-1)。
 *
 * 既存の {@code /app/room/...} とは前置詞が異なるため {@code @MessageMapping} は衝突しない。
 *
 * <h2>ペイロードが Map ではなく record である理由</h2>
 * Spring Framework 7 の {@code convertAndSend} には「ペイロード + ヘッダーMap」を受ける
 * オーバーロードがあり、Map を第2引数に渡すと呼び出しが曖昧になってコンパイルエラーになる。
 * 加えて、送信プロトコルの形が型として明文化される利点もある。
 */
@Component
@RequiredArgsConstructor
public class ManualBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;

    private final ManualViewBuilder viewBuilder;

    /**
     * クライアントへ送るメッセージの型。
     * type=VIEW のとき view が、type=ERROR のとき message が、type=CUE のとき cue が入る。
     *
     * ★CUE を VIEW と同じ宛先に流すのは、クライアントの購読を1本に保つためである。
     * 宛先を分けると、購読の張り忘れが「矢印だけ来ない」という分かりにくい形で出る。
     */
    public record ManualWsMessage(String type, ManualGameView view, String message,
            ManualDragCue.View cue) {

        static ManualWsMessage ofView(ManualGameView view) {
            return new ManualWsMessage("VIEW", view, null, null);
        }

        static ManualWsMessage ofError(String message) {
            return new ManualWsMessage("ERROR", null, message, null);
        }

        static ManualWsMessage ofCue(ManualDragCue.View cue) {
            return new ManualWsMessage("CUE", null, null, cue);
        }
    }

    /** 部屋の全在室者に、それぞれの視点のビューを配信する。 */
    public void broadcast(ManualRoom room) {
        for (ManualOccupant occupant : room.getOccupants()) {
            sendTo(room, occupant);
        }
    }

    /** 在室者1人にビューを送る。入室直後の初回配信・視点切替後の再送で使う(21 5-5)。 */
    public void sendTo(ManualRoom room, ManualOccupant occupant) {
        ManualGameView view = viewBuilder.build(room, occupant);
        messagingTemplate.convertAndSend(destinationOf(room.getRoomId(), occupant.getOccupantId()),
                ManualWsMessage.ofView(view));
    }

    /** 特定の在室者へのエラー通知。盤面は変わっていないので本人にだけ返す。 */
    public void sendError(String roomId, String occupantId, String message) {
        if (occupantId == null) {
            return;
        }
        messagingTemplate.convertAndSend(destinationOf(roomId, occupantId),
                ManualWsMessage.ofError(message));
    }

    /**
     * ドラッグ軌跡の矢印を、掴んでいる本人<b>以外</b>へ配る(21 7章)。
     *
     * ★閲覧者ごとに視点フィルタを掛け直す。起点のカード識別子は
     * 「そのカードが見える人」にだけ載せる(7-3)。フィルタをここで行うのは、
     * 宛先が在室者ごとに分かれているという配信の形をそのまま使えるためである。
     *
     * ★自分のドラッグ中は表示しない(7-4)。自分はブラウザのゴーストを見ているので、
     * 送り返すと二重に見える。
     *
     * @param cueFactory 閲覧者の視点から、その人へ送る内容を作る関数
     */
    public void sendDragCue(ManualRoom room, ManualOccupant actor,
            java.util.function.Function<ManualViewpoint, ManualDragCue.View> cueFactory) {
        for (ManualOccupant occupant : room.getOccupants()) {
            if (occupant == actor || !occupant.isConnected()) {
                continue;
            }
            ManualDragCue.View cue = cueFactory.apply(ManualViewpoint.of(room, occupant));
            messagingTemplate.convertAndSend(
                    destinationOf(room.getRoomId(), occupant.getOccupantId()),
                    ManualWsMessage.ofCue(cue));
        }
    }

    private String destinationOf(String roomId, String occupantId) {
        return "/topic/manual/%s/view/%s".formatted(roomId, occupantId);
    }
}
