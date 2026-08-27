package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.game.GameService;
import com.example.qte.room.GameRoom;
import com.example.qte.room.GameRoomManager;
import com.example.qte.room.GameRoomOptions;
import com.example.qte.room.SeatId;
import com.example.qte.support.PeekingBroadcaster;
import com.example.qte.web.GameWsController;

/**
 * ★★★Batch 73: <b>送られてこない項目を、実際の入口で断れているか</b>(72b の宿題)。
 *
 * <h2>{@code WsRequestPayloadTest} と何が違うのか</h2>
 * あちらは<b>変換の層</b>を見る —— 本文が開けるか、開けたあとアクセサが断るか。
 * こちらは<b>実際の入口</b>を通す —— {@code @MessageMapping} のメソッドを呼び、
 * <b>断った理由が送り主へ返るところまで</b>を見る。
 *
 * <p>★<b>この2つは別のことを守っている。</b>
 * アクセサが正しく投げていても、それが {@code execute} の {@code try} の外で
 * 評価されていれば理由は返らない —— <b>捕まえる人が居ない例外は、無言と同じである</b>。
 * ★71・72 の教訓「番人は実際の入口から起こす」がそのまま効く。
 *
 * <h2>なぜ盤面を作らないのか</h2>
 * 関門はハンドラの<b>いちばん手前</b>に立っている ——
 * {@code gameService.…(room, playerId, request.handIndex())} の引数評価で飛ぶので、
 * 業務層には1歩も入らない。<b>部屋さえあれば測れる</b>。
 * ★逆に言えば、盤面を作らないと測れなくなったときは、
 * 関門が奥へ動いている(= 手前の別の判定に食われている)ということである。
 */
@SpringBootTest
class Batch73PayloadGuardTest {

    @Autowired
    GameRoomManager roomManager;

    @Autowired
    GameService gameService;

    /**
     * 「送られてこなかった項目」。★<b>直接 {@code null} と書かずに変数にしている。</b>
     *
     * <p>受け口を<b>原始型に戻す</b>壊し検証を成立させるためである ——
     * {@code new ChooseOrderRequest(id, null)} と書くと、
     * 受け口が {@code boolean} になった瞬間に<b>この試験のコンパイルが通らなくなり</b>、
     * 報告書が1つも生まれない(裁定304 の EMPTY)。
     * 箱型の変数を渡す形なら、原始型に戻しても<b>自動開封で実行時に倒れる</b>ので、
     * 壊し検証が「番人が落ちた」を観測できる。
     *
     * <p>★★<b>試験の書き方が、壊せるかどうかを決める。</b>
     */
    private static final Integer 送られてこない整数 = null;

    private static final Boolean 送られてこない真偽 = null;

    /** 入口を叩くための台。★{@code broadcaster} が「送り主へ返ったもの」を覗く窓である */
    private record Rig(GameWsController 入口, String roomId, String playerId,
            PeekingBroadcaster 返ったもの) {
    }

    private Rig 席に着いた部屋() {
        PeekingBroadcaster broadcaster = new PeekingBroadcaster();
        GameRoom room = roomManager.createRoom(new GameRoomOptions("試験部屋", true, false));
        String id = room.join("あるふぁ", SeatId.A).getPlayerId();
        return new Rig(new GameWsController(roomManager, gameService, broadcaster),
                room.getRoomId(), id, broadcaster);
    }

    // ===================================================================
    // 送られてこなければ断る —— 入口ごとに1件
    // ===================================================================

    @Test
    @DisplayName("★先攻/後攻が送られてこないと、送り主に理由が返る")
    void 先攻後攻が無いと断る() {
        Rig t = 席に着いた部屋();

        t.入口().chooseOrder(t.roomId(),
                new GameWsController.ChooseOrderRequest(t.playerId(), 送られてこない真偽));

        assertThat(t.返ったもの().message())
                .as("★返らなければ、押した人の画面には何も起きない(72b の形)")
                .contains("goFirst");
        assertThat(t.返ったもの().playerId()).isEqualTo(t.playerId());
    }

    @Test
    @DisplayName("★手札の位置が送られてこないと、送り主に理由が返る(マナチャージ)")
    void マナチャージの手札の位置が無いと断る() {
        Rig t = 席に着いた部屋();

        t.入口().chargeMana(t.roomId(),
                new GameWsController.HandActionRequest(t.playerId(), 送られてこない整数));

        assertThat(t.返ったもの().message()).contains("handIndex");
        assertThat(t.返ったもの().playerId()).isEqualTo(t.playerId());
    }

    @Test
    @DisplayName("★手札の位置が送られてこないと、送り主に理由が返る(プレイ)")
    void プレイの手札の位置が無いと断る() {
        Rig t = 席に着いた部屋();

        t.入口().playCard(t.roomId(), new GameWsController.PlayCardRequest(
                t.playerId(), 送られてこない整数, List.of(), null, null, null));

        assertThat(t.返ったもの().message()).contains("handIndex");
    }

    @Test
    @DisplayName("★禁忌の位置が送られてこないと、送り主に理由が返る")
    void 禁忌の位置が無いと断る() {
        Rig t = 席に着いた部屋();

        t.入口().playTaboo(t.roomId(), new GameWsController.TabooRequest(
                t.playerId(), 送られてこない整数, List.of(), List.of(), null));

        assertThat(t.返ったもの().message()).contains("tabooIndex");
    }

    @Test
    @DisplayName("★墓地の位置が送られてこないと、送り主に理由が返る")
    void 墓地の位置が無いと断る() {
        Rig t = 席に着いた部屋();

        t.入口().summonFromGrave(t.roomId(),
                new GameWsController.GraveSummonRequest(t.playerId(), 送られてこない整数, List.of(), null));

        assertThat(t.返ったもの().message()).contains("trashIndex");
    }

    // ===================================================================
    // ★「常に断る」実装を排除する(裁定181)
    // ===================================================================

    /**
     * ★★<b>断る側だけを測ると、何でも断る実装が緑になる。</b>
     * 値が送られてきたときは関門を通り抜け、<b>業務層の言葉で断られる</b>ことを見る。
     * ★文言そのものは業務層の持ち物なので、<b>「送られていません」でないこと</b>で測る ——
     * 文言を書き写すと、業務層が言い回しを変えただけでここが赤くなる(67 の教訓・写し)。
     */
    @Test
    @DisplayName("値が送られてくれば関門は通り抜ける(断り文句が変わる)")
    void 送られてくれば関門は通る() {
        Rig t = 席に着いた部屋();

        t.入口().chargeMana(t.roomId(),
                new GameWsController.HandActionRequest(t.playerId(), 0));

        assertThat(t.返ったもの().message())
                .as("★盤面が無いので断られはするが、それは関門の言葉ではない")
                .isNotNull()
                .doesNotContain("送られていません");
    }
}
