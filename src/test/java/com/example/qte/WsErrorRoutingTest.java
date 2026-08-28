package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.annotation.support.SimpAnnotationMethodMessageHandler;
import org.springframework.messaging.support.AbstractSubscribableChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.GenericMessage;

/**
 * ★★★Batch 72b: <b>開けなかったメッセージが、本当にこの受け皿へ流れてくるか</b>を見る。
 *
 * <h2>なぜ宣言の確認では足りないのか</h2>
 * {@code onUnreadableMessage} を直接呼ぶ試験は「呼べば返す」ことしか言わない。
 * 今回の不具合の本体は<b>そこへ届かないこと</b>だったので、
 * 同じ形の番人を置いても同じ穴が空く ——
 * 「番人を置く場所を選ぶ前に、そこまで届くかを確かめる」(70・71 の教訓)。
 *
 * <p>→ Spring が実際に使う {@link SimpAnnotationMethodMessageHandler} に
 * <b>壊れた本文をそのまま流し込み</b>、ブローカー行きの通路に
 * ERROR が1通出てくることを見る。宣言(アノテーション)ではなく<b>経路</b>を測っている。
 *
 * <p>★部屋は作らない —— 変換の失敗はハンドラに入る前に起きるので、
 * 部屋があるかどうかはこの経路に関係しない。それ自体がこの不具合の性質である。
 */
@SpringBootTest
class WsErrorRoutingTest {

    @Autowired
    private SimpAnnotationMethodMessageHandler handler;

    @Autowired
    @Qualifier("brokerChannel")
    private AbstractSubscribableChannel brokerChannel;

    private List<Message<?>> 捕まえる() {
        List<Message<?>> captured = new ArrayList<>();
        brokerChannel.addInterceptor(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                captured.add(message);
                return message;
            }
        });
        return captured;
    }

    private void 流し込む(String destination, String body) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setDestination(destination);
        headers.setSessionId("test-session");
        headers.setSessionAttributes(new HashMap<>());
        handler.handleMessage(
                new GenericMessage<>(body.getBytes(StandardCharsets.UTF_8), headers.getMessageHeaders()));
    }

    @Test
    @DisplayName("★開けない本文を送ると、送った本人の宛先へ ERROR が1通出る")
    void 開けない本文は本人の宛先へ返る() {
        List<Message<?>> captured = 捕まえる();

        // ★★<b>開けない本文の代表</b>である。賢魂の不具合そのもの(enhanced の欠落)は
        //   受け口を直したのでもう開ける —— 直したあとも<b>この経路が生きている</b>ことを
        //   示すために、種類の違う「開けない本文」を使う(裁定196: 消えた番人は書き残す)。
        流し込む("/app/room/RM0001/play-soul",
                "{\"playerId\":\"P-1\",\"handIndex\":\"いち\",\"targets\":[],\"manaIndexes\":[]}");

        List<String> 宛先 = captured.stream()
                .map(m -> SimpMessageHeaderAccessor.getDestination(m.getHeaders()))
                .toList();
        assertThat(宛先).contains("/topic/room/RM0001/player/P-1");

        String 本文 = captured.stream()
                .filter(m -> "/topic/room/RM0001/player/P-1"
                        .equals(SimpMessageHeaderAccessor.getDestination(m.getHeaders())))
                .map(m -> new String((byte[]) m.getPayload(), StandardCharsets.UTF_8))
                .findFirst().orElse("");
        assertThat(本文).contains("ERROR").contains("受け取れませんでした");
    }

    /**
     * ★★★Batch 75 で返るものが変わった(裁定344)。
     *
     * <p>74 まで、部屋が引けなかったときの {@code execute} は
     * 「部屋が見つかりません: RM0002」という <b>ERROR</b> を返していた。
     * 75 はそれを <b>ROOM_LOST</b> という型に変えている ——
     * ERROR は「その操作が拒否された理由」であって画面はその場に留まるが、
     * 部屋消失は留まれないからである。
     *
     * <p>★★<b>この試験が測っているものは変わっていない</b> ——
     * 「開ける本文は {@code MessageConversionException} の受け皿を通らず、
     * <b>別の理由で</b>断られる」ことである。<b>変えたのは、その別の理由の名前だけ</b>である。
     * ★<b>両方が同じものになっていたら、この試験は何も区別できていない</b>。
     */
    @Test
    @DisplayName("開ける本文は、この受け皿を通らない(部屋が無いという別の理由で断られる)")
    void 開ける本文はここを通らない() {
        List<Message<?>> captured = 捕まえる();

        流し込む("/app/room/RM0002/play-soul",
                "{\"playerId\":\"P-2\",\"handIndex\":0,\"targets\":[],\"manaIndexes\":[]}");

        String 本文 = captured.stream()
                .filter(m -> "/topic/room/RM0002/player/P-2"
                        .equals(SimpMessageHeaderAccessor.getDestination(m.getHeaders())))
                .map(m -> new String((byte[]) m.getPayload(), StandardCharsets.UTF_8))
                .findFirst().orElse("");
        assertThat(本文)
                .as("★Batch 75(裁定344): 部屋が無いときは ROOM_LOST であって ERROR ではない")
                .contains("ROOM_LOST");
        assertThat(本文)
                .as("★変換の受け皿(ERROR)を通っていないこと —— この試験の本題である")
                .doesNotContain("受け取れませんでした")
                .doesNotContain("\"type\":\"ERROR\"");
    }
}
