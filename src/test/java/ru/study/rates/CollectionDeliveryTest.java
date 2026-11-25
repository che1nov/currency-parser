package ru.study.rates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.study.rates.adapter.CbrClient;
import ru.study.rates.model.Currency;
import ru.study.rates.model.RateSnapshot;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:delivery-test;DB_CLOSE_DELAY=-1", "collector.scheduling-enabled=false"})
@DirtiesContext
class CollectionDeliveryTest {
    @TempDir static Path files;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("collector.files-directory", () -> files.toString());
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @MockitoBean CbrClient source;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @BeforeEach void source() {
        when(source.fetch(any())).thenAnswer(call -> {
            var date = (LocalDate) call.getArgument(0);
            var quotes = new EnumMap<Currency, RateSnapshot.Quote>(Currency.class);
            for (var currency : Currency.values()) {
                quotes.put(currency, new RateSnapshot.Quote(currency.name(), new BigDecimal(
                        date.equals(LocalDate.of(2024, 3, 5)) ? "102" : "100")));
            }
            return new RateSnapshot(date, quotes);
        });
    }

    @Test void pollingAndWebSocketDeliverIdenticalResultIncludingLateSubscription() throws Exception {
        var start = post("{\"date\":\"2024-03-05\",\"delayMs\":300}");
        assertThat(start.statusCode()).isEqualTo(202);
        var id = mapper.readTree(start.body()).get("id").asText();
        var pushed = subscribe(id);
        JsonNode polled = awaitResult(id);
        assertThat(polled.get("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(polled.get("rates").size()).isEqualTo(5);
        assertThat(polled.at("/rates/0/change").decimalValue()).isEqualByComparingTo("2");
        assertThat(mapper.readTree(pushed.get(5, TimeUnit.SECONDS))).isEqualTo(polled);
        assertThat(mapper.readTree(subscribe(id).get(5, TimeUnit.SECONDS))).isEqualTo(polled);
        assertThat(get("/api/jobs/" + id).headers().firstValue("Cache-Control")).hasValue("no-store");
    }

    @Test void webSocketDeliversFailureAndAnotherJobCanSucceed() throws Exception {
        doThrow(new IllegalStateException("injected error")).when(source).fetch(any());
        var id = mapper.readTree(post("{\"date\":\"2024-03-05\"}").body()).get("id").asText();
        var failed = mapper.readTree(subscribe(id).get(5, TimeUnit.SECONDS));
        assertThat(failed.get("state").asText()).isEqualTo("FAILED");
        assertThat(failed.get("rates").isEmpty()).isTrue();
        assertThat(failed.get("error").asText()).doesNotContain("injected error");
        reset(source); source();
        var nextId = mapper.readTree(post("{\"date\":\"2024-03-05\"}").body()).get("id").asText();
        assertThat(nextId).isNotEqualTo(id);
        assertThat(awaitResult(nextId).get("state").asText()).isEqualTo("SUCCEEDED");
    }

    @Test void servesPageAndValidatesJobRequests() throws Exception {
        assertThat(get("/").body()).contains("new WebSocket", "setInterval", "500");
        for (var body : new String[]{"null", "{", "{\"delayMs\":-1}", "{\"delayMs\":5001}",
                "{\"date\":\"bad\"}", "{\"date\":\"1999-01-01\"}", "{\"unknown\":1}"}) {
            assertThat(post(body).statusCode()).as(body).isEqualTo(400);
        }
        assertThat(get("/api/jobs/" + java.util.UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(get("/api/jobs/bad-id").statusCode()).isEqualTo(400);
        verify(source, never()).fetch(any());
    }

    @Test void rejectsWebSocketFromAnotherOrigin() throws Exception {
        try {
            http.newWebSocketBuilder().header("Origin", "https://another-site.example")
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws/jobs/" + java.util.UUID.randomUUID()),
                            new WebSocket.Listener() { }).get(5, TimeUnit.SECONDS);
            fail("Foreign origin was accepted");
        } catch (java.util.concurrent.ExecutionException ex) {
            assertThat(ex.getCause()).isInstanceOf(java.net.http.WebSocketHandshakeException.class);
            assertThat(((java.net.http.WebSocketHandshakeException) ex.getCause()).getResponse().statusCode()).isEqualTo(403);
        }
    }

    private JsonNode awaitResult(String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var response = get("/api/jobs/" + id);
            assertThat(response.statusCode()).isEqualTo(200);
            var result = mapper.readTree(response.body());
            if (result.get("completedAt").isTextual()) return result;
            Thread.sleep(25);
        }
        throw new AssertionError("Job did not complete");
    }
    private CompletableFuture<String> subscribe(String id) {
        var result = new CompletableFuture<String>();
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3))
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/jobs/" + id), new WebSocket.Listener() {
                    final StringBuilder text = new StringBuilder();
                    @Override public void onOpen(WebSocket socket) { socket.request(1); }
                    @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
                        text.append(data);
                        if (last) result.complete(text.toString());
                        socket.request(1);
                        return CompletableFuture.completedFuture(null);
                    }
                    @Override public void onError(WebSocket socket, Throwable error) { result.completeExceptionally(error); }
                    @Override public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
                        if (!result.isDone()) result.completeExceptionally(new AssertionError("Closed before result: " + reason));
                        return CompletableFuture.completedFuture(null);
                    }
                }).exceptionally(error -> { result.completeExceptionally(error); return null; });
        return result;
    }
    private HttpResponse<String> post(String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/jobs"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
