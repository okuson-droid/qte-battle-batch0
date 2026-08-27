package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.support.GenericMessage;

import com.example.qte.manual.ManualDragCue;
import com.example.qte.manual.ManualOpRequest;
import com.example.qte.manual.web.ManualWsController;

/**
 * ★★★Batch 73: <b>手動モードにも、送る側と受ける側の「あいだ」の番人を置く</b>(72b の宿題)。
 *
 * <h2>なぜ 72b では作らなかったのか</h2>
 * 72b は不具合の修正であり、触ったのは通常モードの受け口1つだけだった。
 * 手動モードにも同じ形の地雷があることは分かっていたが、
 * <b>そのとき壊れていたわけではない</b>ので宿題として書き残した。
 * ★73 が {@link ManualDragCue.Request} を箱型にしたので、
 * <b>受け口に手を入れた以上、あいだの番人が要る</b>。
 *
 * <h2>この表が測るもの</h2>
 * {@code manual-battle.js} の {@code send(action, payload)} が実際に組み立てる本文を、
 * <b>アプリと同じ変換器</b>へ通す。★{@code send} は
 * {@code {occupantId: OCCUPANT_ID, ...payload}} を組むので、
 * どの本文にも {@code occupantId} が1つだけ載る。
 *
 * <h2>★クライアントが1つも送らない宛先が3つある</h2>
 * {@code turn} / {@code phase} / {@code resync} である。
 * ★<b>これは取りこぼしではない。</b> Batch 20b が
 * 「ターン/フェイズUIを表示ごと削除した(サーバ側の turn/phase 操作は残っている)」と
 * 明記しており、{@code manual-battle.js} の 2006行目にも「休眠コード」と書いてある。
 * ★表に本文を載せられないので、<b>載せないことをここに書き残す</b>(裁定196 の流儀)——
 * 再導入するときは、この表に3行足すところから始めること。
 */
class ManualWsRequestPayloadTest {

    /** ★アプリと同じ既定の変換器である(WebSocketConfig は変換器を差し替えていない) */
    private final MessageConverter converter = new JacksonJsonMessageConverter();

    private <T> T convert(String json, Class<T> type) {
        Message<byte[]> message = new GenericMessage<>(json.getBytes(StandardCharsets.UTF_8));
        Object converted = converter.fromMessage(message, type);
        assertThat(converted).as("変換できない本文: " + json).isNotNull();
        return type.cast(converted);
    }

    /** @param 宛先 manual-battle.js の {@code send(...)} の第1引数 */
    private record Payload(String 宛先, String 本文, Class<?> 受け口) {
    }

    private static final String O = "\"occupantId\":\"o1\"";

    private static final List<Payload> すべての送信 = List.of(
            new Payload("ready", "{" + O + "}", ManualWsController.OccupantRequest.class),
            new Payload("leave", "{" + O + "}", ManualWsController.OccupantRequest.class),
            new Payload("reset", "{" + O + "}", ManualWsController.OccupantRequest.class),
            new Payload("undo", "{" + O + "}", ManualWsController.OccupantRequest.class),
            new Payload("redo", "{" + O + "}", ManualWsController.OccupantRequest.class),
            new Payload("start-begin", "{" + O + "}", ManualWsController.OccupantRequest.class),
            new Payload("seat(座る)", "{" + O + ",\"seat\":\"A\"}",
                    ManualWsController.SeatRequest.class),
            new Payload("seat(立つ)", "{" + O + ",\"seat\":null}",
                    ManualWsController.SeatRequest.class),
            new Payload("viewpoint", "{" + O + ",\"spectatorView\":\"PUBLIC_ONLY\"}",
                    ManualWsController.ViewpointRequest.class),
            // ★移動は入口が3つある(カードのボタン / ドロップ / 山札の並べ替え)
            new Payload("move(ボタン)",
                    "{" + O + ",\"cardIds\":[\"c-1\"],\"toSeat\":\"A\",\"toZone\":\"TRASH\","
                            + "\"toIndex\":null,\"faceDown\":null}",
                    ManualOpRequest.Move.class),
            new Payload("move(ドロップ)",
                    "{" + O + ",\"cardIds\":[\"c-1\"],\"toSeat\":null,\"toZone\":\"FIELD\","
                            + "\"toIndex\":2,\"faceDown\":false}",
                    ManualOpRequest.Move.class),
            new Payload("move(山札の並べ替え)",
                    "{" + O + ",\"cardIds\":[\"c-1\"],\"toSeat\":\"B\",\"toZone\":\"DECK\","
                            + "\"toIndex\":0,\"faceDown\":null}",
                    ManualOpRequest.Move.class),
            // ★evolve は toIndex を送らない(素材の位置を使うため)。箱型なので開ける
            new Payload("evolve",
                    "{" + O + ",\"seat\":\"A\",\"evolutionCardId\":\"c-9\","
                            + "\"materialCardIds\":[\"c-1\"]}",
                    ManualOpRequest.Evolve.class),
            new Payload("lp(増減)", "{" + O + ",\"seat\":\"A\",\"delta\":-3}",
                    ManualOpRequest.Lp.class),
            new Payload("lp(直接)", "{" + O + ",\"seat\":\"A\",\"value\":20}",
                    ManualOpRequest.Lp.class),
            new Payload("stat(攻撃力)", "{" + O + ",\"cardId\":\"c-1\",\"attack\":5}",
                    ManualOpRequest.Stat.class),
            new Payload("stat(体力)", "{" + O + ",\"cardId\":\"c-1\",\"hp\":3}",
                    ManualOpRequest.Stat.class),
            new Payload("stat-reset", "{" + O + ",\"cardId\":\"c-1\"}",
                    ManualOpRequest.Target.class),
            new Payload("label-add", "{" + O + ",\"cardId\":\"c-1\",\"label\":\"守護\"}",
                    ManualOpRequest.Label.class),
            new Payload("label-remove", "{" + O + ",\"cardId\":\"c-1\",\"label\":\"守護\"}",
                    ManualOpRequest.Label.class),
            // ★旗は入口が2つ(1枚を反転 / まとめて false にする)
            new Payload("tap(反転)", "{" + O + ",\"cardIds\":[\"c-1\"]}",
                    ManualOpRequest.Flag.class),
            new Payload("tap(まとめて戻す)", "{" + O + ",\"cardIds\":[\"c-1\"],\"value\":false}",
                    ManualOpRequest.Flag.class),
            new Payload("flip", "{" + O + ",\"cardIds\":[\"c-1\"]}",
                    ManualOpRequest.Flag.class),
            new Payload("used(反転)", "{" + O + ",\"cardIds\":[\"c-1\"]}",
                    ManualOpRequest.Flag.class),
            new Payload("used(まとめて戻す)", "{" + O + ",\"cardIds\":[\"c-1\"],\"value\":false}",
                    ManualOpRequest.Flag.class),
            new Payload("draw", "{" + O + ",\"seat\":\"A\",\"count\":1}",
                    ManualOpRequest.Draw.class),
            new Payload("shuffle", "{" + O + ",\"seat\":\"A\"}", ManualOpRequest.Seat.class),
            new Payload("declare",
                    "{" + O + ",\"seat\":\"A\",\"declaration\":\"WIN\",\"note\":null}",
                    ManualOpRequest.Declare.class),
            new Payload("note", "{" + O + ",\"text\":\"めも\"}", ManualOpRequest.Note.class),
            new Payload("start-method", "{" + O + ",\"method\":\"DICE\"}",
                    ManualOpRequest.StartMethod.class),
            new Payload("start-order(先攻)", "{" + O + ",\"takeFirst\":true}",
                    ManualOpRequest.StartOrder.class),
            new Payload("start-order(後攻)", "{" + O + ",\"takeFirst\":false}",
                    ManualOpRequest.StartOrder.class),
            new Payload("mulligan", "{" + O + ",\"seat\":\"A\",\"cardIds\":[\"c-1\"]}",
                    ManualOpRequest.Mulligan.class),
            new Payload("mulligan(引き直さない)", "{" + O + ",\"seat\":\"A\",\"cardIds\":[]}",
                    ManualOpRequest.Mulligan.class),
            // ★★dragcue は 73 が箱型にした(それまで active が原始型だった)
            new Payload("dragcue(始め)",
                    "{" + O + ",\"cardId\":\"c-1\",\"toSeat\":null,\"toZone\":null,\"active\":true}",
                    ManualDragCue.Request.class),
            new Payload("dragcue(ホバー)",
                    "{" + O + ",\"cardId\":\"c-1\",\"toSeat\":\"A\",\"toZone\":\"FIELD\","
                            + "\"active\":true}",
                    ManualDragCue.Request.class),
            new Payload("dragcue(終わり)",
                    "{" + O + ",\"cardId\":null,\"toSeat\":null,\"toZone\":null,\"active\":false}",
                    ManualDragCue.Request.class));

    @Test
    @DisplayName("★手動モードのクライアントが送るすべての本文が、サーバの受け口で開ける")
    void すべての送信が開ける() {
        for (Payload s : すべての送信) {
            assertThatCode(() -> convert(s.本文(), s.受け口()))
                    .as(s.宛先() + " の本文が開けない: " + s.本文())
                    .doesNotThrowAnyException();
        }
    }

    /**
     * ★<b>この表は増えなければならない。</b>宛先や入口を1つ足したら1行足す。
     * ★件数そのものに意味は無い。意味があるのは「変えたときに立ち止まること」である
     * ({@code WsRequestPayloadTest} と同じ流儀)。
     */
    @Test
    @DisplayName("送信の表は手動モードの入口を覆っている")
    void 送信の表の件数() {
        assertThat(すべての送信).hasSize(37);
    }

    /**
     * ★★★Batch 73 が箱型にした項目そのもの。
     *
     * <p>{@code active} は<b>畳む</b> —— 通常モードの {@code handIndex} は断ったが、
     * ここは畳んだ先が「矢印を消す」= <b>何も起きない</b>だからである。
     * ★しかも {@code dragCue} は {@code dispatch} を通らず、
     * <b>エラーを返さない経路</b>である(設計書 7-2)。断っても届かない。
     */
    @Test
    @DisplayName("★矢印の active は、送られてこなければ「消す」に落ちる(捨てられない)")
    void 矢印の旗は畳む() {
        var request = convert("{" + O + ",\"cardId\":\"c-1\"}", ManualDragCue.Request.class);
        assertThat(request.active())
                .as("★null を false に畳む —— 安全側は「矢印を出さない」である")
                .isFalse();
        assertThat(convert("{" + O + ",\"cardId\":\"c-1\",\"active\":true}",
                ManualDragCue.Request.class).active())
                .as("★送られてくれば読む(常に false を返す実装を排除する)")
                .isTrue();
    }
}
