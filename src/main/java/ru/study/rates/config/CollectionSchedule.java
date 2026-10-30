package ru.study.rates.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import ru.study.rates.service.CollectionBusyException;
import ru.study.rates.service.RateCollectionService;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "collector.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class CollectionSchedule {
    private static final Logger log = LoggerFactory.getLogger(CollectionSchedule.class);
    private final RateCollectionService collector;

    public CollectionSchedule(RateCollectionService collector) {
        this.collector = collector;
    }

    @Scheduled(initialDelayString = "${collector.initial-delay-ms:1000}",
            fixedDelayString = "${collector.interval-ms:3600000}")
    public void collect() {
        try {
            collector.collect(collector.today(), null);
        } catch (CollectionBusyException ex) {
            log.info("Scheduled collection skipped: another collection is running");
        } catch (RuntimeException ex) {
            // Сервис записал причину; исключение не должно отменять следующие запуски.
            log.warn("Scheduled collection failed; next run remains scheduled");
        }
    }
}
