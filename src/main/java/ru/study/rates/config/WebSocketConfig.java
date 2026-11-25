package ru.study.rates.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.websocket.Extension;
import jakarta.websocket.server.ServerEndpointConfig;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.server.standard.ServerEndpointRegistration;
import org.springframework.web.socket.server.standard.StandardWebSocketUpgradeStrategy;
import ru.study.rates.web.JobWebSocketHandler;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final JobWebSocketHandler handler;
    public WebSocketConfig(JobWebSocketHandler handler) { this.handler = handler; }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        var upgrade = new StandardWebSocketUpgradeStrategy() {
            @Override
            protected void upgradeHttpToWebSocket(HttpServletRequest request, HttpServletResponse response,
                    ServerEndpointConfig config, Map<String, String> pathParameters) throws Exception {
                var original = (ServerEndpointRegistration) config;
                var uncompressed = new ServerEndpointRegistration(config.getPath(), original.getEndpoint()) {
                    @Override
                    public List<Extension> getNegotiatedExtensions(List<Extension> installed, List<Extension> requested) {
                        // Tomcat добавляет встроенные расширения даже при пустом config.getExtensions().
                        return List.of();
                    }
                };
                uncompressed.setSubprotocols(config.getSubprotocols());
                uncompressed.setEncoders(config.getEncoders());
                uncompressed.setDecoders(config.getDecoders());
                uncompressed.setUserProperties(config.getUserProperties());
                super.upgradeHttpToWebSocket(request, response, uncompressed, pathParameters);
            }
        };
        registry.addHandler(handler, "/ws/jobs/*").setHandshakeHandler(new DefaultHandshakeHandler(upgrade));
    }
}
