package com.example.qte.manual.web;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.example.qte.manual.ManualOccupant;
import com.example.qte.manual.ManualRoom;
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
 * フェイズ1では在室者が1人なので実質1本しか流れないが、
 * <b>宛先形式は配管であり、後から変えると 17b〜18c を掘り返すことになる</b>ため、
 * ここでこの形にしておく。
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
     * type=VIEW のとき view が入り、type=ERROR のとき message が入る。
     */
    public record ManualWsMessage(String type, ManualGameView view, String message) {

        static ManualWsMessage ofView(ManualGameView view) {
            return new ManualWsMessage("VIEW", view, null);
        }

        static ManualWsMessage ofError(String message) {
            return new ManualWsMessage("ERROR", null, message);
        }
    }

    /** 部屋の全在室者に、それぞれの視点のビューを配信する。 */
    public void broadcast(ManualRoom room) {
        for (ManualOccupant occupant : room.getOccupants()) {
            sendTo(room, occupant);
        }
    }

    /** 在室者1人にビューを送る。入室直後の初回配信で使う。 */
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

    private String destinationOf(String roomId, String occupantId) {
        return "/topic/manual/%s/view/%s".formatted(roomId, occupantId);
    }
}
