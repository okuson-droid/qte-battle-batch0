package com.example.qte.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket の設定。
 *
 * - /ws            : WebSocketの接続口(ハンドシェイク用URL)。接続は最初の1回だけで、
 *                    以降は同じ接続の上をメッセージが双方向に流れ続ける
 * - /app/...       : クライアント → サーバ。@MessageMappingのメソッドに届く
 *                    (MVCでいうリクエストURLに相当)
 * - /topic/...     : サーバ → クライアント。購読(subscribe)している全クライアントに配信される
 *                    (MVCに相当物がない、WebSocket固有の「サーバ発信」の口)
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }
}
