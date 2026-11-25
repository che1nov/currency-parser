package ru.study.rates.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import ru.study.rates.service.CollectionJobService;

@Component
public class JobWebSocketHandler extends TextWebSocketHandler {
    private final CollectionJobService jobs;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, Runnable> subscriptions = new ConcurrentHashMap<>();

    public JobWebSocketHandler(CollectionJobService jobs, ObjectMapper mapper) {
        this.jobs = jobs;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        session.setTextMessageSizeLimit(1024);
        try {
            String path = session.getUri().getPath();
            UUID id = UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
            Runnable unsubscribe = jobs.onCompletion(id, snapshot -> {
                // Подписчик получает ровно одно сообщение, конкурентной отправки нет.
                try {
                    if (session.isOpen()) {
                        session.sendMessage(new TextMessage(mapper.writeValueAsString(
                                CollectionJobController.JobResponse.from(snapshot))));
                        session.close(CloseStatus.NORMAL);
                    }
                } catch (IOException ex) {
                    try { session.close(CloseStatus.SERVER_ERROR); } catch (IOException ignored) { }
                }
            });
            subscriptions.put(session.getId(), unsubscribe);
            // Закрытие могло произойти внутри немедленной доставки готового результата.
            if (!session.isOpen()) remove(session);
        } catch (IllegalArgumentException | java.util.NoSuchElementException ex) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Unknown or expired job"));
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Subscriber limit"));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        session.close(CloseStatus.POLICY_VIOLATION.withReason("Server notifications only"));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { remove(session); }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        remove(session);
        if (session.isOpen()) session.close(CloseStatus.SERVER_ERROR);
    }

    private void remove(WebSocketSession session) {
        Runnable unsubscribe = subscriptions.remove(session.getId());
        if (unsubscribe != null) unsubscribe.run();
    }
}
