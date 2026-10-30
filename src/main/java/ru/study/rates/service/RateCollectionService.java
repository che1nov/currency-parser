package ru.study.rates.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ru.study.rates.adapter.CbrClient;
import ru.study.rates.adapter.SourceException;
import ru.study.rates.model.Currency;
import ru.study.rates.model.CurrencyRate;
import ru.study.rates.model.RateSnapshot;
import ru.study.rates.repository.RateRepository;

@Service
public class RateCollectionService {
    private static final Logger log = LoggerFactory.getLogger(RateCollectionService.class);
    private final CbrClient client;
    private final RateRepository repository;
    private final ExecutorService executor;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final ReentrantLock collectionLock = new ReentrantLock();
    private final AtomicReference<CollectionStatus> status = new AtomicReference<>(
            new CollectionStatus("IDLE", null, null, null, null, 0, null));

    public RateCollectionService(CbrClient client, RateRepository repository, ExecutorService collectorExecutor,
                                 TransactionTemplate transaction, Clock clock) {
        this.client = client;
        this.repository = repository;
        this.executor = collectorExecutor;
        this.transaction = transaction;
        this.clock = clock;
    }

    public List<CurrencyRate> collect(LocalDate date, String sourceUrl) {
        validateDate(date);
        client.validateSource(sourceUrl);
        if (!collectionLock.tryLock()) {
            throw new CollectionBusyException();
        }
        var startedAt = clock.instant();
        var lastSuccess = status.get().lastSuccessAt();
        status.set(new CollectionStatus("RUNNING", date, startedAt, null, lastSuccess, 0, null));
        var futures = new ArrayList<Future<RateSnapshot>>(2);
        try {
            log.info("Collecting rates date={}", date);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            futures.add(executor.submit(() -> client.fetch(date)));
            futures.add(executor.submit(() -> client.fetch(date.minusDays(1))));
            var current = await(futures.get(0), deadline);
            var previous = await(futures.get(1), deadline);
            var rates = calculate(date, current, previous);
            // Сетевые запросы завершены: транзакция охватывает только запись всех пяти курсов.
            transaction.executeWithoutResult(tx -> repository.saveAll(rates));
            var completedAt = clock.instant();
            status.set(new CollectionStatus("SUCCEEDED", date, startedAt, completedAt, completedAt,
                    rates.size(), null));
            log.info("Rates saved date={} count={}", date, rates.size());
            return rates;
        } catch (RuntimeException ex) {
            String message = ex instanceof SourceException ? ex.getMessage() : "Не удалось сохранить курсы";
            status.set(new CollectionStatus("FAILED", date, startedAt, clock.instant(), lastSuccess, 0, message));
            log.error("Collection failed date={}", date, ex);
            throw ex;
        } finally {
            futures.forEach(future -> { if (!future.isDone()) future.cancel(true); });
            collectionLock.unlock();
        }
    }

    public CollectionStatus status() {
        return status.get();
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public void validateDate(LocalDate date) {
        if (date == null || date.isBefore(LocalDate.of(2000, 1, 2)) || date.isAfter(today())) {
            throw new IllegalArgumentException("date должна быть между 2000-01-02 и сегодняшней датой по Москве");
        }
    }

    private RateSnapshot await(Future<RateSnapshot> future, long deadline) {
        try {
            return future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SourceException("Сбор курсов прерван", ex);
        } catch (TimeoutException ex) {
            throw new SourceException("Превышено время ожидания курсов ЦБ РФ", ex);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof SourceException sourceException) {
                throw sourceException;
            }
            throw new SourceException("Не удалось получить курсы ЦБ РФ", ex.getCause());
        }
    }

    private List<CurrencyRate> calculate(LocalDate date, RateSnapshot current, RateSnapshot previous) {
        var rates = new ArrayList<CurrencyRate>();
        for (var currency : Currency.values()) {
            var quote = current.quotes().get(currency);
            var oldQuote = previous.quotes().get(currency);
            if (quote == null || oldQuote == null) {
                throw new SourceException("Недостаточно данных для вычисления изменения курса");
            }
            BigDecimal change = quote.rublesPerUnit().subtract(oldQuote.rublesPerUnit());
            BigDecimal percent = change.multiply(BigDecimal.valueOf(100))
                    .divide(oldQuote.rublesPerUnit(), 6, RoundingMode.HALF_UP);
            rates.add(new CurrencyRate(date, currency, quote.name(), current.effectiveDate(),
                    previous.effectiveDate(), quote.rublesPerUnit(), oldQuote.rublesPerUnit(),
                    change, percent, clock.instant()));
        }
        return List.copyOf(rates);
    }

    public record CollectionStatus(String state, LocalDate date, Instant startedAt, Instant completedAt,
                                   Instant lastSuccessAt, int savedCount, String error) { }
}
