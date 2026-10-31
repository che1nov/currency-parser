package ru.study.rates.config;

import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import ru.study.rates.adapter.SourceException;
import ru.study.rates.service.CollectionBusyException;
import ru.study.rates.service.RateCollectionService;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CollectionScheduleTest {
    @Test
    void keepsSchedulingAfterFailureAndBusyCollection() throws Exception {
        var service = mock(RateCollectionService.class);
        when(service.today()).thenReturn(LocalDate.of(2024, 3, 5));
        var calls = new AtomicInteger();
        var finished = new CountDownLatch(1);
        var thread = new AtomicReference<Thread>();
        doAnswer(invocation -> {
            thread.set(Thread.currentThread());
            int count = calls.incrementAndGet();
            if (count == 1) throw new SourceException("Unavailable");
            if (count == 2) throw new CollectionBusyException();
            finished.countDown();
            return java.util.List.of();
        }).when(service).collect(any(), isNull());
        try (var schedule = new CollectionSchedule(service, 0, 10)) {
            schedule.start();
            assertThat(finished.await(3, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(calls.get()).isGreaterThanOrEqualTo(3);
        assertThat(thread.get().getName()).isEqualTo("rate-scheduler");
        assertThat(thread.get().isAlive()).isFalse();
    }

    @Test
    void interruptsActiveCollectionAndWaitsForSchedulerToStop() throws Exception {
        var service = mock(RateCollectionService.class);
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var thread = new AtomicReference<Thread>();
        doAnswer(invocation -> {
            thread.set(Thread.currentThread());
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException ex) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return java.util.List.of();
        }).when(service).collect(any(), isNull());
        try (var schedule = new CollectionSchedule(service, 0, 10)) {
            schedule.start();
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(interrupted.getCount()).isZero();
        assertThat(thread.get().isAlive()).isFalse();
    }

    @Test
    void rejectsInvalidIntervalsBeforeCreatingThreads() {
        var service = mock(RateCollectionService.class);
        assertThatThrownBy(() -> new CollectionSchedule(service, -1, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CollectionSchedule(service, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
