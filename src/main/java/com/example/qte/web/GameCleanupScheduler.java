package com.example.qte.web;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.qte.room.GameRoom;
import com.example.qte.room.GameRoomManager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * ★★★Batch 75: 誰も繋がっていない通常モードの部屋を片付ける(裁定342・343)。
 *
 * <p>{@code GameRoomManager.removeRoom} は 65 以前から在ったが、
 * <b>74 まで呼び出し元が1つも無かった</b> ——
 * 手動モードが 19a で作って 21a まで放置したのとまったく同じ形の積み残しである
 * (66 が「無人部屋の掃除が無い」と書き、72 が退室を作ったことで
 * 「部屋が空になる」経路が日常的になった)。
 *
 * <h2>★★★1段である(裁定342)</h2>
 * 手動モードの {@code ManualCleanupScheduler} は2段である ——
 * <b>先に猶予切れの席を空けてから</b>無人を判定する。通常モードは1段しかない。
 * <pre>
 *   手動: 切断から5分 → 席を空ける → 在室者0が5分 → 部屋を消す
 *   通常: 誰も繋がっていない状態が5分 → 部屋を消す
 * </pre>
 * ★席を空ける段が要らない理由は {@code GameRoom} の「接続と無人」の節に書いた。
 * ★★<b>だから猶予も1つしかない。</b>手動モードの2つ(GRACE_PERIOD / EMPTY_ROOM_TTL)を
 * 足して10分にはしない —— <b>値を写すのではなく、理由から決める</b>(71 の教訓)。
 *
 * <h2>★★★対戦中の部屋も消す(裁定343)</h2>
 * {@code GameState} が在っても、誰も繋がっていなければ消す。
 * <ul>
 *   <li>部屋はメモリ上にしか無い(設計判断1)。どのみちサーバの再起動で消える</li>
 *   <li>残すと、両者が帰った対戦中の部屋がロビーの一覧に<b>永久に並び続ける</b></li>
 *   <li>戻ってきた人には部屋消失の案内が出る(裁定344・345)</li>
 * </ul>
 * ★<b>決着後(FINISHED)も同じ規則である。</b>72 が決着後の退室を作ったので
 * 「盤面が在るのに誰も居ない」は日常的に起きる —— そこに例外を作らない。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GameCleanupScheduler {

    /**
     * 誰も繋がっていない部屋を保持する時間(★Batch 75・裁定342)。
     *
     * <p>★<b>手動モードの {@code EMPTY_ROOM_TTL} と同じ5分だが、写したのではない。</b>
     * 短いと「回線が一瞬切れただけで対戦が消える」、長いとロビーに死に部屋が並ぶ ——
     * その両方を満たす値として、あちらと同じ結論になった。
     * ★★<b>両モードで一致していることを測る番人は置いていない。</b>
     * 一致は<b>要求ではなく偶然</b>であり、見張ると「揃えるべきもの」に化ける
     * (74 の教訓「揃っていることは、正しいことではない」の予防である)。
     */
    public static final Duration DESERTED_ROOM_TTL = Duration.ofMinutes(5);

    private final GameRoomManager roomManager;

    /**
     * 1分ごとに全部屋を確認する。想定規模(部屋数は多くても数十)では
     * この頻度・走査コストのどちらも無視できる。
     *
     * <p>★<b>削除はロックの外で行う</b>(手動モードと同じ判断)。
     * 台帳の Map を触る処理を部屋のロックの中に入れる必要が無く、
     * 入れると2つのロックの順序を気にすることになる。
     */
    @Scheduled(fixedRate = 60_000)
    public void sweep() {
        sweep(Instant.now());
    }

    /**
     * ★★★<b>「今」を受け取る版</b>(★Batch 75)。番人はこちらを叩く。
     *
     * <p>★<b>時計を差し替えるためだけの引数ではない。</b>{@code Instant.now()} を
     * 走査の途中で何度も読むと、部屋ごとに違う「今」で判定することになる ——
     * 1周のあいだ<b>同じ時刻で全部屋を見る</b>ほうが、そもそも正しい。
     *
     * <p>★★<b>設定を足して時計を注入する形は採らなかった。</b>
     * 器を1つ増やす価値が無い(70 の「確定待ちの器を1つにした」と同じ筋)——
     * <b>引数1つで足りる</b>。
     */
    public void sweep(Instant now) {
        List<String> deserted = new ArrayList<>();

        for (GameRoom room : roomManager.allRooms()) {
            synchronized (room.getLock()) {
                if (room.desertedFor(now).compareTo(DESERTED_ROOM_TTL) >= 0) {
                    deserted.add(room.getRoomId());
                }
            }
        }

        for (String roomId : deserted) {
            roomManager.removeRoom(roomId);
            // ★★<b>消したことを誰にも配信しない。</b>宛先の一覧を持っていたのは部屋であり、
            //   そもそも誰も繋がっていないから消している(裁定342)。
            //   戻ってきた人は ready を撃った時点で ROOM_LOST を受け取る(裁定344)。
            log.info("誰も繋がっていない部屋を片付けた: roomId={}", roomId);
        }
    }
}
