package com.example.qte.web;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import com.example.qte.room.GameRoom;
import com.example.qte.room.GameRoomManager;

import lombok.RequiredArgsConstructor;

/**
 * ★★★Batch 75: 通常モードの WebSocket 切断を検知する(裁定342)。
 *
 * <p>手動モードの {@code ManualDisconnectListener} が 19a から持っていたものを、
 * 通常モードに要るぶんだけ作った。あちらの Javadoc には
 * 「プロジェクト全体でこの種のリスナーはこれが最初である(<b>通常モードにも無い</b>)」と
 * 書いてあり、75 までそれは本当だった ——
 * <b>通常モードでは、タブを閉じた人が席に着いたまま永久に残っていた。</b>
 *
 * <h2>★★★手動モードと決定的に違うところ</h2>
 * 手動モードのリスナーは {@code connected} を倒し、<b>席を空ける判断(5分の猶予)を
 * スケジューラに委ねる</b>。通常モードは<b>席を空けない</b>(裁定342) ——
 * ここがやるのは「繋がっている人の一覧から外す」ことだけである。
 * <ul>
 *   <li>対戦中に席が空いても、誰も座れない({@code GameRoom.takeSeat} が断る)</li>
 *   <li>席と {@code GameState} の2人は1対1であり、空けると盤面の持ち主が消える</li>
 *   <li>WAITING 中に席を空ける操作は、72 が<b>退室</b>として別に作った</li>
 * </ul>
 * ★★<b>同じ穴を塞ぐことと、同じ形で塞ぐことは別である</b>(71 の教訓)。
 *
 * <h2>配信しない理由</h2>
 * ★{@code RoomView} は「相手が繋がっているか」を<b>1つも持っていない</b>ので、
 * ここで配信しても<b>ビューは1バイトも変わらない</b>。
 * 手動モードが配信するのは、あちらの在室者一覧に切断中の印が出るからである。
 * ★<b>通常モードで「相手が切断中」が見えないことは 75 でも直していない</b> ——
 * それはビューを1つ増やす工事であり、積み残しとして書き残した。
 *
 * <h2>2つのリスナーが同じイベントを受けることについて</h2>
 * ★{@code ManualDisconnectListener} と本クラスは<b>どちらも全部屋を走査する</b>。
 * 台帳が別なので({@code ManualRoomManager} / {@code GameRoomManager})、
 * 片方で見つかればもう片方は空振りする。部屋数は多くても数十であり、
 * 走査コストは無視できる —— <b>台帳を1つにまとめないのは 21a からの設計判断である</b>
 * (「通常モードの部屋を探す処理が手動モードの部屋を見つけてしまう」経路を作らない)。
 */
@Component
@RequiredArgsConstructor
public class GameDisconnectListener {

    private final GameRoomManager roomManager;

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        for (GameRoom room : roomManager.allRooms()) {
            String occupantId;
            synchronized (room.getLock()) {
                occupantId = room.markDisconnected(sessionId);
            }
            if (occupantId != null) {
                // ★sessionId は接続1本につき1つなので、見つかった時点で他の部屋を探す必要は無い
                return;
            }
        }
    }
}
