package ru.study.rates.service;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.study.rates.model.CurrencyRate;

/** Короткоживущие задания доставки; сохранённые курсы по-прежнему находятся в H2. */
@Service
public class CollectionJobService {
    private static final Logger log = LoggerFactory.getLogger(CollectionJobService.class);
    private static final int MAX_JOBS = 100;
    private final RateCollectionService collector;
    private final Clock clock;
    private final Map<UUID, Job> jobs = new LinkedHashMap<>();
    // Отдельный исполнитель: ожидающий сбор не занимает его собственные cbr-worker потоки.
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), task -> new Thread(task, "collection-job"),
            new ThreadPoolExecutor.AbortPolicy());

    public CollectionJobService(RateCollectionService collector, Clock clock) {
        this.collector = collector;
        this.clock = clock;
    }

    public synchronized Snapshot start(LocalDate date, int delayMs) {
        collector.validateDate(date);
        if (delayMs < 0 || delayMs > 5000) {
            throw new IllegalArgumentException("delayMs: целое число от 0 до 5000");
        }
        jobs.values().removeIf(job -> job.expired(clock.instant()));
        if (jobs.size() >= MAX_JOBS) {
            var oldest = jobs.entrySet().stream().filter(entry -> entry.getValue().snapshot().terminal())
                    .findFirst().orElseThrow(() -> new RejectedExecutionException("Хранилище заданий заполнено"));
            jobs.remove(oldest.getKey());
        }
        var initial = new Snapshot(UUID.randomUUID(), "QUEUED", date, clock.instant(), null, List.of(), null);
        var job = new Job(initial);
        jobs.put(initial.id(), job);
        try {
            executor.execute(() -> run(job, delayMs));
        } catch (RejectedExecutionException ex) {
            jobs.remove(initial.id());
            throw ex;
        }
        return initial;
    }

    public Snapshot find(UUID id) {
        return findJob(id).snapshot();
    }

    public Runnable onCompletion(UUID id, Consumer<Snapshot> listener) {
        return findJob(id).subscribe(listener);
    }

    private synchronized Job findJob(UUID id) {
        var job = jobs.get(id);
        if (job == null || job.expired(clock.instant())) {
            jobs.remove(id);
            throw new NoSuchElementException("Задание не найдено или срок хранения истёк");
        }
        return job;
    }

    private void run(Job job, int delayMs) {
        var initial = job.snapshot();
        job.running();
        try {
            Thread.sleep(delayMs);
            var rates = collector.collect(initial.date(), null);
            job.complete(new Snapshot(initial.id(), "SUCCEEDED", initial.date(), initial.startedAt(),
                    clock.instant(), rates, null));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            fail(job, initial, "Обработка прервана");
        } catch (RuntimeException ex) {
            log.warn("Collection job failed id={}", initial.id(), ex);
            fail(job, initial, ex instanceof CollectionBusyException
                    ? "Другой сбор уже выполняется; повторите запуск" : "Не удалось собрать курсы; подробности в журнале сервера");
        }
    }

    private void fail(Job job, Snapshot initial, String error) {
        job.complete(new Snapshot(initial.id(), "FAILED", initial.date(), initial.startedAt(),
                clock.instant(), List.of(), error));
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(20, TimeUnit.SECONDS)) log.warn("Job executor did not stop in time");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        List<Job> remaining;
        synchronized (this) { remaining = List.copyOf(jobs.values()); }
        remaining.forEach(job -> { if (!job.snapshot().terminal()) fail(job, job.snapshot(), "Сервер остановлен"); });
    }

    public record Snapshot(UUID id, String state, LocalDate date, Instant startedAt, Instant completedAt,
                           List<CurrencyRate> rates, String error) {
        public Snapshot { rates = List.copyOf(rates); }
        public boolean terminal() { return "SUCCEEDED".equals(state) || "FAILED".equals(state); }
    }

    private static final class Job {
        private Snapshot value;
        private final List<Consumer<Snapshot>> listeners = new ArrayList<>();

        Job(Snapshot value) { this.value = value; }
        synchronized Snapshot snapshot() { return value; }
        synchronized boolean expired(Instant now) {
            return value.terminal() && value.completedAt().plusSeconds(900).isBefore(now);
        }
        synchronized void running() {
            value = new Snapshot(value.id(), "RUNNING", value.date(), value.startedAt(), null, List.of(), null);
        }
        Runnable subscribe(Consumer<Snapshot> listener) {
            Snapshot completed;
            synchronized (this) {
                completed = value.terminal() ? value : null;
                if (completed == null) {
                    if (listeners.size() >= 8) throw new RejectedExecutionException("Слишком много подписчиков задания");
                    listeners.add(listener);
                }
            }
            // Проверка завершения и регистрация атомарны: быстрый результат не потеряется.
            if (completed != null) notifyListener(listener, completed);
            return () -> { synchronized (this) { listeners.remove(listener); } };
        }
        void complete(Snapshot completed) {
            List<Consumer<Snapshot>> targets;
            synchronized (this) {
                if (value.terminal()) return;
                value = completed;
                targets = List.copyOf(listeners);
                listeners.clear();
            }
            targets.forEach(listener -> notifyListener(listener, completed));
        }
        private static void notifyListener(Consumer<Snapshot> listener, Snapshot completed) {
            try { listener.accept(completed); }
            catch (RuntimeException ex) { log.warn("Job subscriber failed id={}", completed.id(), ex); }
        }
    }
}
