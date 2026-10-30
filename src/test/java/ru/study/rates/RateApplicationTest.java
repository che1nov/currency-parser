package ru.study.rates;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.study.rates.adapter.CbrClient;
import ru.study.rates.adapter.SourceException;
import ru.study.rates.model.Currency;
import ru.study.rates.model.RateSnapshot;
import ru.study.rates.repository.RateRepository;
import ru.study.rates.service.RateCollectionService;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:rates-test;DB_CLOSE_DELAY=-1",
        "collector.scheduling-enabled=false"})
@AutoConfigureMockMvc
class RateApplicationTest {
    private static final LocalDate DATE = LocalDate.of(2024, 3, 5);
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired RateCollectionService collector;
    @MockitoBean CbrClient client;
    @MockitoSpyBean RateRepository repository;

    @TestConfiguration
    static class TestClock {
        @Bean @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2024-03-05T10:00:00Z"), ZoneId.of("Europe/Moscow"));
        }
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM currency_rate");
        doCallRealMethod().when(client).validateSource(any());
        doAnswer(invocation -> {
            LocalDate date = invocation.getArgument(0);
            return snapshot(date, date.equals(DATE) ? "102" : "100");
        }).when(client).fetch(any());
    }

    @Test
    void collectsCalculatesAndQueriesH2() throws Exception {
        collect().andExpect(status().isOk()).andExpect(jsonPath("$.savedCount").value(5))
                .andExpect(jsonPath("$.rates[0].rate").value(102))
                .andExpect(jsonPath("$.rates[0].change").value(2))
                .andExpect(jsonPath("$.rates[0].changePercent").value(2));
        mvc.perform(get("/answer").param("date", DATE.toString()).param("currency", "usd"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].currency").value("USD"))
                .andExpect(jsonPath("$[0].baseCurrency").value("RUB"))
                .andExpect(jsonPath("$[0].previousEffectiveDate").value("2024-03-04"));
        mvc.perform(get("/api/status")).andExpect(jsonPath("$.state").value("SUCCEEDED"));
    }

    @Test
    void repeatCollectionUpdatesWithoutDuplicates() throws Exception {
        collect().andExpect(status().isOk());
        doReturn(snapshot(DATE, "105")).when(client).fetch(DATE);
        collect().andExpect(status().isOk());
        assertThat(repository.findByDate(DATE)).hasSize(5)
                .allSatisfy(rate -> assertThat(rate.rate()).isEqualByComparingTo("105"));
    }

    @Test
    void sortsNumericallyAndBreaksTiesByCurrency() throws Exception {
        var quotes = new EnumMap<Currency, RateSnapshot.Quote>(snapshot(DATE, "90").quotes());
        quotes.put(Currency.JPY, new RateSnapshot.Quote("Иена", new BigDecimal("0.6")));
        quotes.put(Currency.GBP, new RateSnapshot.Quote("Фунт", new BigDecimal("115")));
        doReturn(new RateSnapshot(DATE, quotes)).when(client).fetch(DATE);
        collect().andExpect(status().isOk());
        mvc.perform(get("/answer").param("sort", "rate").param("direction", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].currency").value("GBP"))
                .andExpect(jsonPath("$[1].currency").value("CNY"))
                .andExpect(jsonPath("$[4].currency").value("JPY"));
        mvc.perform(get("/answer").param("sort", "changePercent").param("direction", "asc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].currency").value("JPY"));
    }

    @Test
    void preservesDataAndReportsUpstreamFailure() throws Exception {
        collect().andExpect(status().isOk());
        var saved = repository.findByDate(DATE);
        doThrow(new SourceException("ЦБ недоступен")).when(client).fetch(DATE);
        collect().andExpect(status().isBadGateway()).andExpect(jsonPath("$.detail").value("ЦБ недоступен"));
        assertThat(repository.findByDate(DATE)).containsExactlyInAnyOrderElementsOf(saved);
        mvc.perform(get("/api/status")).andExpect(jsonPath("$.state").value("FAILED"))
                .andExpect(jsonPath("$.lastSuccessAt").isNotEmpty());
        doReturn(snapshot(DATE, "103")).when(client).fetch(DATE);
        collect().andExpect(status().isOk()); // Блокировка освобождена после ошибки.
    }

    @Test
    void rollsBackWholeBatchOnDatabaseFailure() throws Exception {
        collect().andExpect(status().isOk());
        var saved = repository.findByDate(DATE);
        doReturn(snapshot(DATE, "110")).when(client).fetch(DATE);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DataIntegrityViolationException("Injected failure after writing batch");
        }).when(repository).saveAll(anyList());
        collect().andExpect(status().isServiceUnavailable());
        assertThat(repository.findByDate(DATE)).containsExactlyInAnyOrderElementsOf(saved);
    }

    @Test
    void fetchesBothDaysConcurrentlyAndRejectsOverlappingCollection() throws Exception {
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        Set<String> workerNames = ConcurrentHashMap.newKeySet();
        doAnswer(invocation -> {
            workerNames.add(Thread.currentThread().getName());
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Workers not released");
            return snapshot(invocation.getArgument(0), "100");
        }).when(client).fetch(any());
        var caller = Executors.newSingleThreadExecutor();
        try {
            var first = caller.submit(() -> collector.collect(DATE, null));
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            mvc.perform(get("/api/status")).andExpect(jsonPath("$.state").value("RUNNING"));
            collect().andExpect(status().isConflict());
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).hasSize(5);
            assertThat(workerNames).hasSize(2).allMatch(name -> name.startsWith("cbr-worker-"));
            assertThat(repository.findByDate(DATE)).hasSize(5);
        } finally {
            release.countDown();
            caller.shutdownNow();
            caller.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void computesZeroChangeWhenSameRatesApplyOnBothDays() throws Exception {
        var unchanged = snapshot(DATE.minusDays(2), "100");
        doReturn(unchanged).when(client).fetch(any());
        collect().andExpect(status().isOk())
                .andExpect(jsonPath("$.rates[0].change").value(0))
                .andExpect(jsonPath("$.rates[0].effectiveDate").value("2024-03-03"));
    }

    @Test
    void returnsEmptyListForUncollectedDateAndListsCurrencies() throws Exception {
        mvc.perform(get("/answer").param("date", "2024-01-01"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/currencies")).andExpect(status().isOk())
                .andExpect(content().json("[\"USD\",\"EUR\",\"CNY\",\"GBP\",\"JPY\"]"));
    }

    @Test
    void rejectsInvalidInputWithoutFetching() throws Exception {
        for (String body : List.of("{", "null", "{\"date\":\"bad\"}", "{\"date\":\"2024-03-06\"}",
                "{\"date\":\"1999-01-01\"}", "{\"sourceUrl\":\"http://localhost\"}", "{\"typo\":1}")) {
            mvc.perform(post("/api/collect").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/answer").param("currency", "BTC")).andExpect(status().isBadRequest());
        mvc.perform(get("/answer").param("date", "2024-02-30")).andExpect(status().isBadRequest());
        mvc.perform(get("/answer").param("sort", "DROP TABLE")).andExpect(status().isBadRequest());
        mvc.perform(get("/answer").param("direction", "down")).andExpect(status().isBadRequest());
        verify(client, never()).fetch(any());
    }

    @Test
    void interruptsPendingFetchesAndReleasesLock() throws Exception {
        var started = new CountDownLatch(2);
        var interrupted = new CountDownLatch(2);
        doAnswer(invocation -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
                throw new AssertionError("Unreachable");
            } catch (InterruptedException ex) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new SourceException("Interrupted", ex);
            }
        }).when(client).fetch(any());
        var caller = Executors.newSingleThreadExecutor();
        try {
            var task = caller.submit(() -> collector.collect(DATE, null));
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            task.cancel(true);
            caller.shutdown();
            assertThat(caller.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(repository.findByDate(DATE)).isEmpty();
            doReturn(snapshot(DATE.minusDays(1), "100")).when(client).fetch(any());
            collect().andExpect(status().isOk());
        } finally {
            caller.shutdownNow();
        }
    }

    private org.springframework.test.web.servlet.ResultActions collect() throws Exception {
        return mvc.perform(post("/api/collect").contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"2024-03-05\",\"sourceUrl\":\"https://www.cbr.ru/currency_base/daily/\"}"));
    }

    private static RateSnapshot snapshot(LocalDate date, String value) {
        var quotes = new EnumMap<Currency, RateSnapshot.Quote>(Currency.class);
        for (var currency : Currency.values()) {
            quotes.put(currency, new RateSnapshot.Quote(currency.name(), new BigDecimal(value)));
        }
        return new RateSnapshot(date, quotes);
    }
}
