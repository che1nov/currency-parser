package ru.study.rates.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CollectionJobServiceTest {
    private final RateCollectionService collector = mock(RateCollectionService.class);
    private final CollectionJobService jobs = new CollectionJobService(collector, Clock.systemUTC());
    private final LocalDate date = LocalDate.of(2024, 3, 5);

    @AfterEach void close() { jobs.close(); }

    @Test
    void deliversCompletionToEarlyAndLateSubscribers() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(collector.collect(date, null)).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("Timeout");
            return List.of();
        });
        var first = jobs.start(date, 0);
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var early = new CompletableFuture<CollectionJobService.Snapshot>();
            jobs.onCompletion(first.id(), early::complete);
            release.countDown();
            var completed = early.get(3, TimeUnit.SECONDS);
            var late = new CompletableFuture<CollectionJobService.Snapshot>();
            jobs.onCompletion(first.id(), late::complete);
            assertThat(late.get(1, TimeUnit.SECONDS)).isEqualTo(completed);
            assertThat(jobs.find(first.id())).isEqualTo(completed);
            assertThat(completed.state()).isEqualTo("SUCCEEDED");
            assertThat(completed.completedAt()).isNotNull();
        } finally { release.countDown(); }
    }

    @Test
    void disconnectedSubscriberIsRemovedAndFailureBecomesTerminal() throws Exception {
        when(collector.collect(date, null)).thenThrow(new IllegalStateException("private failure"));
        var initial = jobs.start(date, 100);
        var calls = new AtomicInteger();
        jobs.onCompletion(initial.id(), result -> calls.incrementAndGet()).run();
        var completion = new CompletableFuture<CollectionJobService.Snapshot>();
        jobs.onCompletion(initial.id(), completion::complete);
        var failed = completion.get(3, TimeUnit.SECONDS);
        assertThat(failed.state()).isEqualTo("FAILED");
        assertThat(failed.error()).doesNotContain("private failure");
        assertThat(failed.rates()).isEmpty();
        assertThat(calls).hasValue(0);
    }

    @Test
    void boundsQueueAndKeepsAcceptedJobsAvailable() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(collector.collect(date, null)).thenAnswer(call -> {
            entered.countDown(); release.await(3, TimeUnit.SECONDS); return List.of();
        });
        var first = jobs.start(date, 0);
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 8; i++) jobs.start(date, 0);
            assertThatThrownBy(() -> jobs.start(date, 0)).isInstanceOf(RejectedExecutionException.class);
            assertThat(jobs.find(first.id()).state()).isEqualTo("RUNNING");
        } finally { release.countDown(); }
    }

    @Test
    void rejectsInvalidDelayAndUnknownJob() {
        assertThatThrownBy(() -> jobs.start(date, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jobs.start(date, 5001)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jobs.find(java.util.UUID.randomUUID())).isInstanceOf(NoSuchElementException.class);
        verify(collector, never()).collect(any(), any());
    }
}
