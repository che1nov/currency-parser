package ru.study.rates;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.EnumMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.study.rates.adapter.CbrClient;
import ru.study.rates.adapter.SourceException;
import ru.study.rates.model.Currency;
import ru.study.rates.model.RateSnapshot;
import ru.study.rates.repository.RateRepository;
import ru.study.rates.service.RateCollectionService;
import ru.study.rates.service.RateQueryService;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:cache-test;DB_CLOSE_DELAY=-1",
        "collector.scheduling-enabled=false", "spring.cache.type=caffeine"})
@DirtiesContext
class RateCacheTest {
    private static final LocalDate DATE = LocalDate.of(2024, 3, 5);
    @TempDir static Path files;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("collector.files-directory", () -> files.toString());
    }
    @Autowired RateQueryService query;
    @Autowired RateCollectionService collector;
    @Autowired RateRepository repository;
    @Autowired CacheManager caches;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CbrClient client;

    @BeforeEach void prepare() {
        caches.getCache("rates").clear();
        jdbc.update("DELETE FROM currency_rate");
        doAnswer(call -> snapshot(call.getArgument(0), "100")).when(client).fetch(any());
        collector.collect(DATE, null);
    }

    @Test void repeatedLookupSkipsDatabaseAndKeysSeparateQueryParameters() {
        long before = repository.readCount();
        var usd = query.find(DATE, Currency.USD, "rate", "asc");
        assertThat(query.find(DATE, Currency.USD, "rate", "asc")).isEqualTo(usd);
        assertThat(repository.readCount() - before).isEqualTo(1);
        assertThat(query.find(DATE, Currency.EUR, "rate", "asc")).extracting("currency").containsExactly(Currency.EUR);
        assertThat(query.find(DATE, null, "currency", "asc")).hasSize(5);
        assertThat(query.find(DATE, null, "currency", "desc").get(0).currency()).isEqualTo(Currency.USD);
        assertThat(query.find(DATE.minusDays(10), Currency.USD, "rate", "asc")).isEmpty();
        assertThat(repository.readCount() - before).isEqualTo(5);
    }

    @Test void successfulUpdateEvictsEveryVariantAndEmptyResult() {
        query.find(DATE, Currency.USD, "rate", "asc");
        query.find(DATE, null, "currency", "desc");
        var anotherDate = DATE.minusDays(10);
        assertThat(query.find(anotherDate, null, "currency", "asc")).isEmpty();
        doAnswer(call -> snapshot(call.getArgument(0), "105")).when(client).fetch(any());
        collector.collect(DATE, null);
        long before = repository.readCount();
        assertThat(query.find(DATE, Currency.USD, "rate", "asc").get(0).rate()).isEqualByComparingTo("105");
        assertThat(query.find(DATE, null, "currency", "desc")).allSatisfy(rate ->
                assertThat(rate.rate()).isEqualByComparingTo("105"));
        assertThat(repository.readCount() - before).isEqualTo(2);
        collector.collect(anotherDate, null);
        assertThat(query.find(anotherDate, null, "currency", "asc")).hasSize(5);
    }

    @Test void failedCollectionPreservesPreviouslyCachedResult() {
        var saved = query.find(DATE, Currency.USD, "rate", "asc");
        long before = repository.readCount();
        doThrow(new SourceException("Injected source failure")).when(client).fetch(any());
        assertThatThrownBy(() -> collector.collect(DATE, null)).isInstanceOf(SourceException.class);
        assertThat(query.find(DATE, Currency.USD, "rate", "asc")).isEqualTo(saved);
        assertThat(repository.readCount()).isEqualTo(before);
    }

    @Test void configuresBoundedTenMinuteCacheAndExpiryReloadsData() throws Exception {
        var nativeCache = ((CaffeineCache) caches.getCache("rates")).getNativeCache();
        var expiry = nativeCache.policy().expireAfterWrite().orElseThrow();
        assertThat(expiry.getExpiresAfter()).isEqualTo(Duration.ofMinutes(10));
        assertThat(nativeCache.policy().eviction().orElseThrow().getMaximum()).isEqualTo(1000);
        try {
            expiry.setExpiresAfter(Duration.ofMillis(50));
            query.find(DATE, null, "currency", "asc");
            long before = repository.readCount();
            Thread.sleep(100);
            query.find(DATE, null, "currency", "asc");
            assertThat(repository.readCount() - before).isEqualTo(1);
        } finally { expiry.setExpiresAfter(Duration.ofMinutes(10)); }
    }

    private RateSnapshot snapshot(LocalDate date, String rate) {
        var quotes = new EnumMap<Currency, RateSnapshot.Quote>(Currency.class);
        for (var currency : Currency.values()) quotes.put(currency,
                new RateSnapshot.Quote(currency.name(), new BigDecimal(rate)));
        return new RateSnapshot(date, quotes);
    }
}
