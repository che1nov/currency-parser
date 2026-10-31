package ru.study.rates.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import ru.study.rates.adapter.CollectionJournal;
import ru.study.rates.adapter.FileRateStore;

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
    FileRateStore fileRateStore(@Value("${collector.files-directory:./data}") Path directory, ObjectMapper mapper) {
        return new FileRateStore(directory.resolve("snapshots"), mapper);
    }

    @Bean(destroyMethod = "close")
    CollectionJournal collectionJournal(@Value("${collector.files-directory:./data}") Path directory,
                                         ObjectMapper mapper) throws IOException {
        return new CollectionJournal(directory.resolve("collection-events.jsonl"), mapper,
                LoggerFactory.getLogger(CollectionJournal.class));
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
