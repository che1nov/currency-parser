package ru.study.rates.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;

/** Самостоятельный Runnable: один демон-поток владеет файлом журнала. */
public final class CollectionJournal implements Runnable, AutoCloseable {
    private final BlockingQueue<Event> queue = new ArrayBlockingQueue<>(256);
    private final AtomicLong rejectedEvents = new AtomicLong();
    private final BufferedWriter writer;
    private final ObjectMapper mapper;
    private final Logger log;
    private final Thread worker;
    private volatile boolean accepting = true;
    private volatile IOException failure;

    public CollectionJournal(Path file, ObjectMapper mapper, Logger log) throws IOException {
        this.mapper = mapper;
        this.log = log;
        Files.createDirectories(file.toAbsolutePath().getParent());
        this.writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        this.worker = new Thread(this, "collection-journal");
        worker.setDaemon(true);
        worker.start();
    }

    // Короткая критическая секция: после закрытия новые события уже не попадут в очередь.
    // offer не блокирует HTTP-поток, даже если диск медленный или очередь заполнена.
    public synchronized boolean record(Instant at, String event, LocalDate date, int count) {
        if (!accepting || failure != null || !queue.offer(new Event(at, event, date, count))) {
            rejectedEvents.incrementAndGet();
            log.warn("Journal event rejected event={} date={}", event, date);
            return false;
        }
        return true;
    }

    public Health health() {
        return new Health(worker.isAlive(), worker.isDaemon(), queue.size(), rejectedEvents.get(),
                failure == null ? null : "Ошибка записи журнала; подробности в логах");
    }

    @Override
    public void run() {
        try (writer) {
            while (accepting || !queue.isEmpty()) {
                var event = queue.poll(200, TimeUnit.MILLISECONDS);
                if (event != null) {
                    writer.write(mapper.writeValueAsString(new Line(event.at(), event.event(), event.date(),
                            event.count(), Thread.currentThread().getName(), Thread.currentThread().isDaemon())));
                    writer.newLine();
                    writer.flush();
                }
            }
        } catch (IOException ex) {
            failure = ex;
            log.error("Journal write failed pending={}", queue.size(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Journal interrupted pending={}", queue.size());
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            accepting = false;
        }
        try {
            // Демон сам по себе не удерживает JVM, поэтому явно дожидаемся записи очереди.
            worker.join(5000);
            if (worker.isAlive()) {
                worker.interrupt();
                worker.join(1000);
                throw new IllegalStateException("Журнал не завершил запись за 5 секунд");
            }
        } catch (InterruptedException ex) {
            worker.interrupt();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ожидание остановки журнала прервано", ex);
        }
    }

    private record Event(Instant at, String event, LocalDate date, int count) { }
    private record Line(Instant at, String event, LocalDate date, int count, String thread, boolean daemon) { }
    public record Health(boolean running, boolean daemon, int pending, long rejectedEvents, String error) { }
}
