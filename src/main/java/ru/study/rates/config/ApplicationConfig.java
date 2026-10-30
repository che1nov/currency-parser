package ru.study.rates.config;

import java.io.IOException;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class ApplicationConfig {
    @Bean
    Clock clock() {
        return Clock.system(ZoneId.of("Europe/Moscow"));
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService collectorExecutor() {
        var sequence = new AtomicInteger();
        return new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8),
                task -> new Thread(task, "cbr-worker-" + sequence.incrementAndGet()),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    RestTemplate cbrRestTemplate() throws GeneralSecurityException, IOException {
        var client = HttpClient.newBuilder()
                .sslContext(CbrTls.createContext())
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(10));
        return new RestTemplate(factory);
    }
}
