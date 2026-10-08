package io.github.vivekdavara.dispatch.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Plain WebSocket (no STOMP or SockJS): courier apps are native clients, the protocol is four message types, and
 * a raw socket keeps it easy to read. Browsers from other origins are refused by Spring's default same-origin
 * check; clients that send no Origin header (native apps) are allowed.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final CourierSocketHandler handler;

    public WebSocketConfig(CourierSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/couriers/*");
    }
}
