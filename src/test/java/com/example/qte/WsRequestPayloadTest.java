package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.GenericMessage;

import com.example.qte.support.PeekingBroadcaster;
import com.example.qte.web.GameWsController;

/**
 * ★★★Batch 72b: <b>クライアントが実際に送る本文が、サーバの受け口で開けるか</b>を見る。
 *
 * <h2>なぜこの番人が要るのか</h2>
 * 「スペル枠へ落としても賢魂が使えない」不具合は、クライアントもサーバのハンドラも
 * 正しかったのに直らなかった。壊れていたのは<b>その二つの間</b>である ——
 * {@code play-soul} は {@code enhanced} を送らないのに、
 * {@code PlayCardRequest} がそれを原始型 {@code boolean} で受けていた。
 * Spring Boot 4(Jackson 3)は<b>原始型の項目が欠けていると変換ごと失敗させる</b>ので、
 * メッセージはハンドラに入る前に捨てられ、しかも捨てたことは誰にも返らなかった。
 *
 * <h2>既存の番人がどれも届かなかった理由</h2>
 * <ul>
 *   <li>JUnit は {@code GameService} を直接呼ぶ —— 本文の変換を通らない</li>
 *   <li>機械検証(verify)は Java を起動しない —— 宛先が {@code play-soul} であることまでしか見ない</li>
 * </ul>
 * ★どちらも「送る側」と「受ける側」を別々に見ており、<b>境目を見ていなかった</b>。
 * この試験はその境目だけを見る。
 *
 * <h2>本文はどこから来たか</h2>
 * すべて {@code battle.js} の {@code send(...)} が実際に組み立てる形を写した。
 * ★<b>入口ごとに1件ずつ</b>ある —— 賢魂はクリックとドラッグで払い方が違い、
 * 今回の不具合は<b>両方</b>に居た。
 */
class WsRequestPayloadTest {

    /** ★アプリと同じ既定の変換器である(WebSocketConfig は変換器を差し替えていない) */
    private final MessageConverter converter = new JacksonJsonMessageConverter();

    private <T> T convert(String json, Class<T> type) {
        Message<byte[]> message = new GenericMessage<>(json.getBytes(StandardCharsets.UTF_8));
        Object converted = converter.fromMessage(message, type);
        assertThat(converted).as("変換できない本文: " + json).isNotNull();
        return type.cast(converted);
    }

    // ------------------------------------------------------------------
    // 1) 今回の不具合そのもの
    // ------------------------------------------------------------------

    @Test
    @DisplayName("賢魂をスペル枠へ落としたときの本文が開ける(enhanced を送らない)")
    void 賢魂のドラッグの本文が開ける() {
        var request = convert("{\"playerId\":\"p1\",\"handIndex\":0,\"targets\":[],\"manaIndexes\":[]}",
                GameWsController.PlayCardRequest.class);
        assertThat(request.handIndex()).isZero();
        assertThat(request.enhanced()).isFalse();
        assertThat(request.manaIndexes()).isEmpty();
        assertThat(request.materialIds()).isEmpty();
    }

    @Test
    @DisplayName("賢魂をクリックで使うときの本文が開ける(払うマナを選んで送る)")
    void 賢魂のクリックの本文が開ける() {
        var request = convert(
                "{\"playerId\":\"p1\",\"handIndex\":2,\"targets\":[],\"manaIndexes\":[0,1]}",
                GameWsController.PlayCardRequest.class);
        assertThat(request.handIndex()).isEqualTo(2);
        assertThat(request.enhanced()).isFalse();
        assertThat(request.manaIndexes()).containsExactly(0, 1);
    }

    @Test
    @DisplayName("enhanced は送られてくれば読む(送らない=false と区別できる必要は無い)")
    void 強化使用は送られてくれば読む() {
        assertThat(convert("{\"playerId\":\"p1\",\"handIndex\":0,\"targets\":[],\"enhanced\":true}",
                GameWsController.PlayCardRequest.class).enhanced()).isTrue();
        assertThat(convert("{\"playerId\":\"p1\",\"handIndex\":0,\"targets\":[],\"enhanced\":false}",
                GameWsController.PlayCardRequest.class).enhanced()).isFalse();
    }

    // ------------------------------------------------------------------
    // 2) クライアントが送るすべての本文(入口ごと)
    // ------------------------------------------------------------------

    /** @param 宛先 battle.js の {@code send(...)} の第1引数。@param 本文 実際に組み立てられる JSON */
    private record Payload(String 宛先, String 本文, Class<?> 受け口) {
    }

    private static final String P = "\"playerId\":\"p1\"";

    private static final List<Payload> すべての送信 = List.of(
            new Payload("ready", "{" + P + "}", GameWsController.ActionRequest.class),
            new Payload("next-phase", "{" + P + "}", GameWsController.ActionRequest.class),
            new Payload("end-turn", "{" + P + "}", GameWsController.ActionRequest.class),
            new Payload("concede", "{" + P + "}", GameWsController.ActionRequest.class),
            new Payload("leave", "{" + P + "}", GameWsController.ActionRequest.class),
            new Payload("choose-order", "{" + P + ",\"goFirst\":true}",
                    GameWsController.ChooseOrderRequest.class),
            new Payload("charge-mana", "{" + P + ",\"handIndex\":1}",
                    GameWsController.HandActionRequest.class),
            new Payload("mulligan", "{" + P + ",\"handIndexes\":[0,2]}",
                    GameWsController.MulliganRequest.class),
            new Payload("mulligan(引き直さない)", "{" + P + ",\"handIndexes\":[]}",
                    GameWsController.MulliganRequest.class),
            // ★プレイ: 入口が2つ(クリックは払うマナを選び、ドラッグは空で送る。裁定315・316・319)
            new Payload("play-card(クリック)",
                    "{" + P + ",\"handIndex\":0,\"targets\":[],\"enhanced\":false,\"manaIndexes\":[0,1]}",
                    GameWsController.PlayCardRequest.class),
            new Payload("play-card(ドラッグ)",
                    "{" + P + ",\"handIndex\":0,\"targets\":[],\"enhanced\":false,\"manaIndexes\":[]}",
                    GameWsController.PlayCardRequest.class),
            new Payload("play-card(進化)",
                    "{" + P + ",\"handIndex\":0,\"targets\":[],\"enhanced\":false,\"manaIndexes\":[],"
                            + "\"materialIds\":[\"m-1\"]}",
                    GameWsController.PlayCardRequest.class),
            new Payload("play-card(対象あり)",
                    "{" + P + ",\"handIndex\":0,\"enhanced\":false,\"manaIndexes\":[],"
                            + "\"targets\":[{\"handIndexes\":[],\"minionIds\":[\"m-1\"],"
                            + "\"manaIndexes\":[],\"trashIndexes\":[],\"weaponSides\":[]}]}",
                    GameWsController.PlayCardRequest.class),
            new Payload("play-soul(ドラッグ)", "{" + P + ",\"handIndex\":0,\"targets\":[],\"manaIndexes\":[]}",
                    GameWsController.PlayCardRequest.class),
            new Payload("play-soul(クリック)", "{" + P + ",\"handIndex\":0,\"targets\":[],\"manaIndexes\":[0]}",
                    GameWsController.PlayCardRequest.class),
            new Payload("special-summon",
                    "{" + P + ",\"handIndex\":0,\"targets\":[],\"enhanced\":false,\"manaIndexes\":[]}",
                    GameWsController.PlayCardRequest.class),
            new Payload("play-taboo", "{" + P + ",\"tabooIndex\":0,\"manaIndexes\":[0],\"targets\":[]}",
                    GameWsController.TabooRequest.class),
            new Payload("play-taboo-soul", "{" + P + ",\"tabooIndex\":1,\"manaIndexes\":[],\"targets\":[]}",
                    GameWsController.TabooRequest.class),
            new Payload("summon-from-grave", "{" + P + ",\"targets\":[],\"trashIndex\":3}",
                    GameWsController.GraveSummonRequest.class),
            new Payload("special-summon-from-grave",
                    "{" + P + ",\"targets\":[],\"trashIndex\":3,\"materialIds\":[\"m-1\"]}",
                    GameWsController.GraveSummonRequest.class),
            new Payload("leader-ability", "{" + P + ",\"targets\":[]}",
                    GameWsController.LeaderAbilityRequest.class),
            new Payload("minion-ability", "{" + P + ",\"instanceId\":\"m-1\",\"targets\":[]}",
                    GameWsController.MinionAbilityRequest.class),
            new Payload("attack", "{" + P + ",\"attackerInstanceId\":\"m-1\",\"targetInstanceId\":\"m-2\"}",
                    GameWsController.AttackRequest.class),
            new Payload("attack(リーダーへ)",
                    "{" + P + ",\"attackerInstanceId\":\"m-1\",\"targetInstanceId\":null}",
                    GameWsController.AttackRequest.class),
            new Payload("leader-attack", "{" + P + ",\"targetInstanceId\":null}",
                    GameWsController.AttackRequest.class),
            new Payload("resolve-choice", "{" + P + ",\"chosenIndexes\":[0]}",
                    GameWsController.ResolveChoiceRequest.class),
            // ★Batch 72
            new Payload("seat(座る)", "{" + P + ",\"seat\":\"A\"}", GameWsController.SeatRequest.class),
            new Payload("seat(立つ)", "{" + P + ",\"seat\":null}", GameWsController.SeatRequest.class),
            new Payload("rematch", "{" + P + ",\"action\":\"OFFER\"}",
                    GameWsController.RematchRequest.class));

    @Test
    @DisplayName("★クライアントが送るすべての本文が、サーバの受け口で開ける")
    void すべての送信が開ける() {
        for (Payload s : すべての送信) {
            assertThatCode(() -> convert(s.本文(), s.受け口()))
                    .as(s.宛先() + " の本文が開けない: " + s.本文())
                    .doesNotThrowAnyException();
        }
    }

    /**
     * ★<b>この表は増えなければならない。</b>宛先を1つ足したら1行足す ——
     * 足し忘れたことをここで気づけるように、件数を書き留めておく。
     * ★件数そのものに意味は無い。意味があるのは「変えたときに立ち止まること」である。
     */
    @Test
    @DisplayName("送信の表は通常モードのすべての宛先を覆っている")
    void 送信の表の件数() {
        assertThat(すべての送信).hasSize(29);
    }

    // ------------------------------------------------------------------
    // 3) ★Batch 73: 箱型にしたが畳まない項目 —— 欠けたら「断る」
    // ------------------------------------------------------------------

    /**
     * ★★★<b>箱型にすることと、畳むことは別である。</b>
     *
     * <p>72b は {@code enhanced} を箱型にして null を false へ畳んだ。
     * それが正しかったのは、<b>畳んだ先が「通常の使用」= 送らない入口の意図そのもの</b>
     * だったからである。73 が箱型にした5項目は違う ——
     * {@code handIndex} を 0 へ畳めば<b>手札の1枚目をプレイする</b>ことになり、
     * {@code goFirst} を false へ畳めば<b>後攻を選んだ</b>ことになる。
     *
     * <p>→ <b>変換は通す</b>(メッセージを捨てない)。
     * <b>読むときに断る</b>(操作した人へ理由が返る)。
     *
     * <p>★この2段は別のことを守っている。1段目が無いと 72b の不具合に戻り、
     * 2段目が無いと「送っていない値で操作が通る」。
     */
    private static final List<Payload> 必須項目が欠けた本文 = List.of(
            new Payload("choose-order(goFirst が無い)", "{" + P + "}",
                    GameWsController.ChooseOrderRequest.class),
            new Payload("charge-mana(handIndex が無い)", "{" + P + "}",
                    GameWsController.HandActionRequest.class),
            new Payload("play-card(handIndex が無い)", "{" + P + ",\"targets\":[]}",
                    GameWsController.PlayCardRequest.class),
            new Payload("summon-from-grave(trashIndex が無い)", "{" + P + ",\"targets\":[]}",
                    GameWsController.GraveSummonRequest.class),
            new Payload("play-taboo(tabooIndex が無い)", "{" + P + ",\"targets\":[]}",
                    GameWsController.TabooRequest.class));

    @Test
    @DisplayName("★必須項目が欠けていても、メッセージは捨てられない(変換は通る)")
    void 必須項目が欠けても変換は通る() {
        for (Payload s : 必須項目が欠けた本文) {
            assertThatCode(() -> convert(s.本文(), s.受け口()))
                    .as(s.宛先() + " で変換が失敗している(捨てられている): " + s.本文())
                    .doesNotThrowAnyException();
        }
    }

    /**
     * ★★<b>例外の型が肝である。</b>{@code GameWsController.execute} が捕まえるのは
     * {@code IllegalStateException} と {@code IllegalArgumentException} だけである ——
     * 素の自動開封に任せると {@code NullPointerException} になり、
     * <b>捕まえる人が居なくなって、また無言に戻る</b>(72b の宿題そのもの)。
     */
    @Test
    @DisplayName("★必須項目が欠けたまま読もうとすると、返せる例外で断られる")
    void 必須項目が欠けたら断る() {
        assertThatThrownBy(
                () -> convert("{" + P + "}", GameWsController.ChooseOrderRequest.class).goFirst())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("goFirst");
        assertThatThrownBy(
                () -> convert("{" + P + "}", GameWsController.HandActionRequest.class).handIndex())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("handIndex");
        assertThatThrownBy(() -> convert("{" + P + ",\"targets\":[]}",
                GameWsController.PlayCardRequest.class).handIndex())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("handIndex");
        assertThatThrownBy(() -> convert("{" + P + ",\"targets\":[]}",
                GameWsController.GraveSummonRequest.class).trashIndex())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trashIndex");
        assertThatThrownBy(() -> convert("{" + P + ",\"targets\":[]}",
                GameWsController.TabooRequest.class).tabooIndex())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tabooIndex");
    }

    /**
     * ★<b>断る側だけを測ると、「常に断る」実装でも緑になる</b>(裁定181)。
     * 送られてくれば、その値がそのまま読めることも見る。
     */
    @Test
    @DisplayName("必須項目は、送られてくれば読める")
    void 必須項目は送られてくれば読める() {
        assertThat(convert("{" + P + ",\"goFirst\":true}",
                GameWsController.ChooseOrderRequest.class).goFirst()).isTrue();
        assertThat(convert("{" + P + ",\"goFirst\":false}",
                GameWsController.ChooseOrderRequest.class).goFirst()).isFalse();
        assertThat(convert("{" + P + ",\"handIndex\":3}",
                GameWsController.HandActionRequest.class).handIndex()).isEqualTo(3);
        assertThat(convert("{" + P + ",\"handIndex\":0,\"targets\":[]}",
                GameWsController.PlayCardRequest.class).handIndex()).isZero();
        assertThat(convert("{" + P + ",\"trashIndex\":2,\"targets\":[]}",
                GameWsController.GraveSummonRequest.class).trashIndex()).isEqualTo(2);
        assertThat(convert("{" + P + ",\"tabooIndex\":1,\"targets\":[]}",
                GameWsController.TabooRequest.class).tabooIndex()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 4) 読めなかったメッセージは黙って捨てられない(設計判断51)
    // ------------------------------------------------------------------

    private static Message<byte[]> 届いたもの(String destination, String body) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setDestination(destination);
        headers.setLeaveMutable(true);
        return new GenericMessage<>(body.getBytes(StandardCharsets.UTF_8),
                headers.getMessageHeaders());
    }

    @Test
    @DisplayName("★変換に失敗したメッセージは、送った本人に理由が返る")
    void 読めない本文は本人に返る() {
        PeekingBroadcaster broadcaster = new PeekingBroadcaster();
        GameWsController controller = new GameWsController(null, null, broadcaster);

        controller.onUnreadableMessage(new MessageConversionException("読めない"),
                届いたもの("/app/room/ABC123/play-soul", "{\"playerId\":\"p1\",\"handIndex\":0}"));

        assertThat(broadcaster.roomId()).isEqualTo("ABC123");
        assertThat(broadcaster.playerId()).isEqualTo("p1");
        assertThat(broadcaster.message()).contains("受け取れませんでした");
    }

    @Test
    @DisplayName("誰が送ったか分からないときは、返さずにサーバのログだけが残る")
    void 送り主が分からなければ返さない() {
        PeekingBroadcaster broadcaster = new PeekingBroadcaster();
        GameWsController controller = new GameWsController(null, null, broadcaster);

        controller.onUnreadableMessage(new MessageConversionException("読めない"),
                届いたもの("/app/room/ABC123/play-soul", "壊れた本文"));

        assertThat(broadcaster.message()).isNull();
    }
}
