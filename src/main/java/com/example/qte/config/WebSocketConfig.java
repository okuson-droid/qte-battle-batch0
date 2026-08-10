package com.example.qte.config;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP over WebSocket の設定。
 *
 * - /ws            : WebSocketの接続口(ハンドシェイク用URL)。接続は最初の1回だけで、
 *                    以降は同じ接続の上をメッセージが双方向に流れ続ける
 * - /app/...       : クライアント → サーバ。@MessageMappingのメソッドに届く
 *                    (MVCでいうリクエストURLに相当)
 * - /topic/...     : サーバ → クライアント。購読(subscribe)している全クライアントに配信される
 *                    (MVCに相当物がない、WebSocket固有の「サーバ発信」の口)
 *
 * <h2>★Batch 28: 接続の安定化</h2>
 * 20b〜27 までこのクラスは「口を開ける」だけで、<b>時間に関する設定を1つも持っていなかった</b>。
 * その結果、次の2つが既定のまま放置されていた。
 * <ol>
 *   <li>ハートビート — Spring のシンプルブローカーは<b>既定で無効</b>である
 *       (TaskScheduler を渡して初めて有効になる)。つまり操作していない間、
 *       接続は完全な無通信になっていた</li>
 *   <li>送信バッファと送信時間の上限 — 既定値は存在するが、
 *       「何を意図してその値なのか」がコードのどこにも書かれていなかった</li>
 * </ol>
 * このアプリは<b>部屋をメモリ上にしか持たない</b>(設計判断1)。接続が切れて猶予5分を
 * 超えると席が空き、無人になれば部屋ごと消える。接続の寿命はこのアプリでは
 * 「ゲームの寿命」そのものであり、既定任せにしてよい設定ではない。
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer, DisposableBean {

    /** ハートビートの間隔(ミリ秒)。{@code [クライアント→サーバ, サーバ→クライアント]} */
    private static final long[] HEARTBEAT = { 10_000, 10_000 };

    /**
     * ハートビート送出用のスケジューラ。
     *
     * <h3>★あえて {@code @Bean} にしていない</h3>
     * このアプリは {@code @EnableScheduling} を使っており
     * ({@link com.example.qte.manual.web.ManualCleanupScheduler} の切断猶予・無人部屋の掃除)、
     * {@code @Scheduled} の実行先は「コンテナ内で一意な {@code TaskScheduler} Bean」で決まる。
     * ここで {@code TaskScheduler} 型のBeanを増やすと解決が曖昧になり、
     * <b>掃除のスケジューラとハートビートのスケジューラが同じ1本のスレッドを共有する</b>
     * といった、意図していない結び付きが起きうる。
     *
     * 用途がハートビート専用である以上、Beanとして公開する理由が無い。
     * 代わりに {@link DisposableBean} でシャットダウン時の停止を引き受ける
     * ({@code ThreadPoolTaskScheduler} のスレッドは非デーモンであり、
     * 止め忘れるとJVMが終了しない)。
     */
    private final ThreadPoolTaskScheduler heartbeatScheduler = createHeartbeatScheduler();

    private static ThreadPoolTaskScheduler createHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void destroy() {
        heartbeatScheduler.shutdown();
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws");
    }

    /**
     * ★ハートビートを10秒間隔で双方向に流す。
     *
     * <h3>なぜ要るのか</h3>
     * <ul>
     *   <li><b>アイドル切断の回避</b> — 経路上のプロキシやNATは、無通信の接続を
     *       黙って捨てることがある。捨てられたことはどちらの端も気づけず、
     *       次に操作したときに初めて「送ったのに何も起きない」として現れる</li>
     *   <li><b>死んだ接続の検出</b> — 相手が落ちた(ブラウザごと終了した等)場合、
     *       TCPは何も教えてくれない。ハートビートが途切れることで初めて切断と分かり、
     *       猶予5分のカウント({@code ManualViewBuilder} / {@code ManualCleanupScheduler})が
     *       正しく始まる</li>
     *   <li><b>ホスティングのスピンダウン対策</b> — 無料枠は一定時間の無通信で
     *       インスタンスを止める。部屋はメモリ上にしかないので、止まると対戦が消える</li>
     * </ul>
     *
     * ★10秒は「切断に気づくのが十分早く、かつ通信量が無視できる」値である。
     * ハートビートのフレームは実質数バイトであり、10秒間隔なら1接続あたり毎分12フレーム、
     * 盤面の配信1回(数十KB)に比べて誤差でしかない。
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic")
                .setHeartbeatValue(HEARTBEAT)
                .setTaskScheduler(heartbeatScheduler);
        registry.setApplicationDestinationPrefixes("/app");
    }

    /**
     * ★送信まわりの上限を明示する。値は Spring の既定と同じか、意図的に広げたものである。
     *
     * <h3>なぜ既定のままにしないのか</h3>
     * 盤面の配信は1回あたり数十KBあり、対戦が進んでログが伸びると100KBを超えうる。
     * 受信側の描画が詰まると送信バッファに未送信のメッセージが積み上がり、
     * <b>上限を超えた時点で Spring はそのセッションを閉じる</b>。
     * つまり「重い」が「切れる」に化ける経路がここにある。
     * 既定値のままだと、その挙動が起きたときにコードのどこにも手がかりが残らない。
     *
     * <ul>
     *   <li>{@code messageSizeLimit} — <b>受信</b>1メッセージの上限。クライアントが送るのは
     *       操作リクエスト(数百バイト)だけなので既定64KBで十分すぎる。明示だけしておく</li>
     *   <li>{@code sendBufferSizeLimit} — 1セッションぶんの未送信バッファ。
     *       既定512KBから<b>1MBへ広げる</b>。盤面100KB級の配信が数回詰まっただけで
     *       切断されるのを避けるためである。★これは対症療法であり、
     *       根治は配信サイズを小さくすること(ログの全行配信をやめる)である</li>
     *   <li>{@code sendTimeLimit} — 1メッセージの送信にかけてよい時間。既定10秒のまま。
     *       これを超えるのは本当に相手が居ないときであり、伸ばす意味が無い</li>
     * </ul>
     */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(64 * 1024);
        registration.setSendBufferSizeLimit(1024 * 1024);
        registration.setSendTimeLimit(10 * 1000);
    }
}
