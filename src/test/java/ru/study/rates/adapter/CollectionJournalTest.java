package ru.study.rates.adapter;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CollectionJournalTest {
    @TempDir Path directory;
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void drainsAcceptedEventsFromConcurrentProducersAndStopsDaemon() throws Exception {
        var file = directory.resolve("events.jsonl");
        var journal = new CollectionJournal(file, mapper, mock(Logger.class));
        assertThat(journal.health().daemon()).isTrue();
        assertThat(journal.health().running()).isTrue();
        Set<Integer> accepted = ConcurrentHashMap.newKeySet();
        var producers = Executors.newFixedThreadPool(4);
        var started = new CountDownLatch(4);
        var finished = new CountDownLatch(4);
        try {
            for (int producer = 0; producer < 4; producer++) {
                final int id = producer;
                producers.submit(() -> {
                    started.countDown();
                    try {
                        for (int i = 0; i < 100; i++) {
                            int number = id * 100 + i;
                            if (journal.record(Instant.EPOCH, "TEST", LocalDate.of(2024, 3, 5), number)) {
                                accepted.add(number);
                            }
                        }
                    } finally {
                        finished.countDown();
                    }
                });
            }
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(finished.await(3, TimeUnit.SECONDS)).isTrue();
        } finally {
            producers.shutdownNow();
            assertThat(producers.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
            journal.close();
        }
        var lines = Files.readAllLines(file);
        Set<Integer> written = new java.util.HashSet<>();
        for (var line : lines) {
            var json = mapper.readTree(line);
            assertThat(json.get("daemon").asBoolean()).isTrue();
            assertThat(json.get("thread").asText()).isEqualTo("collection-journal");
            assertThat(written.add(json.get("count").asInt())).isTrue();
        }
        assertThat(accepted).isNotEmpty();
        assertThat(written).isEqualTo(accepted);
        assertThat(journal.health().running()).isFalse();
        assertThat(journal.health().pending()).isZero();
        assertThat(journal.record(Instant.EPOCH, "LATE", LocalDate.now(), 0)).isFalse();
        assertThat(journal.health().rejectedEvents()).isEqualTo(401 - accepted.size());
    }

    @Test
    void reportsOverflowWithoutBlockingProducers() throws Exception {
        var blockedMapper = spy(mapper);
        var writing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            writing.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("Writer not released");
            return invocation.callRealMethod();
        }).when(blockedMapper).writeValueAsString(any());
        var file = directory.resolve("overflow.jsonl");
        var journal = new CollectionJournal(file, blockedMapper, mock(Logger.class));
        try {
            assertThat(journal.record(Instant.EPOCH, "FIRST", LocalDate.now(), 0)).isTrue();
            assertThat(writing.await(3, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 256; i++) {
                assertThat(journal.record(Instant.EPOCH, "QUEUED", LocalDate.now(), i)).isTrue();
            }
            assertThat(journal.record(Instant.EPOCH, "OVERFLOW", LocalDate.now(), 0)).isFalse();
            assertThat(journal.health().pending()).isEqualTo(256);
            assertThat(journal.health().rejectedEvents()).isEqualTo(1);
        } finally {
            release.countDown();
            journal.close();
        }
        assertThat(Files.readAllLines(file)).hasSize(257);
    }

    @Test
    void appendsAfterRestartInsteadOfTruncatingHistory() throws Exception {
        var file = directory.resolve("events.jsonl");
        for (int i = 0; i < 2; i++) {
            try (var journal = new CollectionJournal(file, mapper, mock(Logger.class))) {
                assertThat(journal.record(Instant.EPOCH, "SUCCEEDED", LocalDate.now(), 5)).isTrue();
            }
        }
        assertThat(Files.readAllLines(file)).hasSize(2);
    }

    @Test
    void drainsEventsWhenClosingConcurrentlyWithProducer() throws Exception {
        var file = directory.resolve("closing.jsonl");
        var journal = new CollectionJournal(file, mapper, mock(Logger.class));
        var caller = Executors.newSingleThreadExecutor();
        var started = new CountDownLatch(1);
        try {
            var accepted = caller.submit(() -> {
                int count = 0;
                for (int i = 0; i < 1000; i++) {
                    if (journal.record(Instant.EPOCH, "TEST", LocalDate.now(), i)) count++;
                    started.countDown();
                }
                return count;
            });
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            journal.close();
            assertThat(Files.readAllLines(file)).hasSize(accepted.get(3, TimeUnit.SECONDS));
            assertThat(journal.health().running()).isFalse();
        } finally {
            journal.close();
            caller.shutdownNow();
            caller.awaitTermination(3, TimeUnit.SECONDS);
        }
    }
}
