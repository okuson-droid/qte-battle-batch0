package com.example.qte.manual.view;

import java.util.List;
import java.util.Map;

import com.example.qte.manual.ManualOccupantRole;
import com.example.qte.manual.ManualPhase;
import com.example.qte.manual.ManualRoomType;
import com.example.qte.manual.ManualSeatId;
import com.example.qte.manual.ManualSpectatorView;
import com.example.qte.manual.ManualZone;

/**
 * 在室者1人に送る盤面ビュー。
 *
 * ★宛先が在室者ごとに分かれているため、この1件が「その人の見えるものすべて」である
 * (設計書 2-4)。★Batch 21a からは<b>実際に人によって中身が違う</b>。
 * 同じ盤面から、席A・席B・全見え観戦・公開のみ観戦の4通りが作られる。
 *
 * <h2>★視点の情報をビューに載せる理由(21 設計書 3-1)</h2>
 * プレイヤーの視点切替UIは作らず、<b>常に自席を下に描く</b>。上下の入れ替えは
 * クライアント描画の責務であり(21b)、そのためにクライアントは
 * 「自分がどちらの席か」を知る必要がある。{@code viewerSeat} がそれである。
 * ★サーバのビューは席A/Bのまま送る。ビューの中で上下を入れ替えてはならない。
 * 入れ替えると、クライアントが送り返す操作の席まで視点混じりになる(21 10章)。
 *
 * @param occupantId    受け取る本人のID。クライアントが購読先を組み立てるのに使う
 * @param backImageId   裏面画像のID。裏向きのカードはこれ1つで描ける
 * @param roomType      部屋の種類。クライアントは操作の出し分けにこれを見る(21 1-1)
 * @param roomName      部屋名(21 1-2)
 * @param viewerSeat    ★閲覧者の席。null なら観戦者。自席=下の描画に使う(3-1)
 * @param spectatorView 観戦者の視点(3-2)。プレイヤーでは null
 * @param canUndo       履歴の状態。★対戦部屋では権限(6-3)も含めた結果である。
 *                      ボタンの活性と実際の可否が同じ関数を通るため、表示と検証がズレない
 * @param log           ★Batch 29: 配るのは<b>末尾60行だけ</b>である
 *                      ({@code ManualViewBuilder.LOG_TAIL})。全文はダウンロードで取れる
 * @param logTotal      ログの総行数。{@code log.size()} より大きければ古い行が省略されている。
 *                      画面が「以前のぶんはログ書出から」と案内するために使う
 * @param declarations  ★Batch 35: 配った {@code log} の中にある勝敗宣言だけを抜き出したもの。
 *                      勝敗の帯とログの決着行はこれだけを読む(設計書 2-3)。
 *                      ★{@code log} と同じ範囲から作るので、ここにある {@code seq} は
 *                      <b>必ず配った行のどれかを指す</b>
 * @param shared        プレイヤー間で共有するゾーン(PLAY / REVEAL)。席に属さない(20b 3-2)
 * @param start         ★Batch 23: 開始シーケンスの状態と「自分が今押せること」。
 *                      クライアントがフェーズから押せる人を組み立て直すと判定が2箇所に分かれる
 */
public record ManualGameView(
        String roomId,
        String occupantId,
        String backImageId,
        ManualRoomType roomType,
        String roomName,
        boolean spectatorAllowed,
        ManualSeatId viewerSeat,
        ManualOccupantRole viewerRole,
        ManualSpectatorView spectatorView,
        int turnNumber,
        ManualPhase phase,
        ManualSeatView seatA,
        ManualSeatView seatB,
        Map<ManualZone, List<ManualCardView>> shared,
        ManualStartView start,
        List<ManualOccupantView> occupants,
        List<ManualLogView> log,
        int logTotal,
        List<ManualDeclarationView> declarations,
        boolean canUndo,
        boolean canRedo) {
}
