package ru.study.rates.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import ru.study.rates.service.CollectionBusyException;
import ru.study.rates.service.RateCollectionService;

@Configuration
@ConditionalOnProperty(name = "collector.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class CollectionSchedule implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CollectionSchedule.class);
    private final RateCollectionService collector;
    private final ScheduledExecutorService scheduler;
    private final long initialDelayMs;
    private final long intervalMs;

    public CollectionSchedule(RateCollectionService collector,
                              @Value("${collector.initial-delay-ms:1000}") long initialDelayMs,
                              @Value("${collector.interval-ms:3600000}") long intervalMs) {
        if (initialDelayMs < 0 || intervalMs <= 0) {
            throw new IllegalArgumentException("Начальная задержка должна быть >= 0, интервал > 0");
        }
        this.collector = collector;
        this.initialDelayMs = initialDelayMs;
        this.intervalMs = intervalMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> new Thread(task, "rate-scheduler"));
    }

    @PostConstruct
    public void start() {
        scheduler.scheduleWithFixedDelay(this::collect, initialDelayMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    public void collect() {
        try {
            collector.collect(collector.today(), null);
        } catch (CollectionBusyException ex) {
            log.info("Scheduled collection skipped: another collection is running");
        } catch (RuntimeException ex) {
            // Исключение из периодической задачи отменило бы последующие запуски.
            log.warn("Scheduled collection failed; next run remains scheduled", ex);
        }
    }

    @PreDestroy
    @Override
    public void close() {
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Планировщик не остановился за 20 секунд");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Остановка планировщика прервана", ex);
        }
    }
}
