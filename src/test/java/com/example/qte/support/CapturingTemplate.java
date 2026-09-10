package com.example.qte.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.example.qte.web.GameBroadcaster.WsMessage;

/**
 * 配信を「送らずに控える」器(★Batch 84a)。
 *
 * <p>★<b>本物の {@code GameBroadcaster} をそのまま使うための器である。</b>
 * 段が載るかどうかは<b>配信の層が決めている</b>ので、
 * 配信の層を差し替えた器({@link PeekingBroadcaster})では測れない ——
 * 測りたいものが器の中に消えてしまう(70〜82 の「番人を置く場所を選ぶ前に、
 * そこまで届くかを確かめる」の形)。
 *
 * <p>★★<b>宛先ごとに最後の1通を控える。</b>段は閲覧者ごとに中身が違うので、
 * <b>誰に何が届いたか</b>を突き合わせられなければ、席ごとの番人が書けない。
 */
public final class CapturingTemplate extends SimpMessagingTemplate {

    private final Map<String, WsMessage> last = new LinkedHashMap<>();
    private final List<String> destinations = new ArrayList<>();

    public CapturingTemplate() {
        super(noopChannel());
    }

    private static MessageChannel noopChannel() {
        return (message, timeout) -> true;
    }

    @Override
    public void convertAndSend(String destination, Object payload) {
        destinations.add(destination);
        if (payload instanceof WsMessage message) {
            last.put(destination, message);
        }
    }

    /** その閲覧者へ最後に届いた1通。★届いていなければ null */
    public WsMessage lastTo(String roomId, String viewerId) {
        return last.get("/topic/room/%s/player/%s".formatted(roomId, viewerId));
    }

    /** 送った通数(★宛先の数を数える番人が使う) */
    public int sentCount() {
        return destinations.size();
    }

    public void clear() {
        last.clear();
        destinations.clear();
    }
}
