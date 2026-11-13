package io.github.quant.server.backtest;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static io.github.quant.server.backtest.BacktestTaskScheduler.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class BacktestTaskSchedulerTest {
    @Test
    void scopesTaskAccessAndReclaimsCanceledQueueCapacity() throws Exception {
        try (var scheduler = new BacktestTaskScheduler(1, 1, 4)) {
            var started = new CountDownLatch(1);
            var interrupted = new CountDownLatch(1);
            var blocker = scheduler.submit("tenant-a", () -> {
                started.countDown();
                try {
                    new CountDownLatch(1).await();
                    throw new AssertionError("blocking job completed");
                } catch (InterruptedException canceled) {
                    interrupted.countDown();
                    throw canceled;
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(RUNNING, scheduler.get("tenant-a", blocker.id()).orElseThrow().status());
            var queued = scheduler.submit("tenant-a", () -> fail("canceled queued job ran"));
            assertTrue(scheduler.get("tenant-b", queued.id()).isEmpty());
            assertTrue(scheduler.cancel("tenant-b", queued.id()).isEmpty());
            assertThrows(RejectedExecutionException.class, () -> scheduler.submit("tenant-b", () -> null));
            assertEquals(CANCELED, scheduler.cancel("tenant-a", queued.id()).orElseThrow().status());
            var replacement = scheduler.submit("tenant-b", () -> { throw new IllegalStateException("job details"); });
            assertEquals(CANCELED, scheduler.cancel("tenant-a", blocker.id()).orElseThrow().status());
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            awaitStatus(scheduler, "tenant-b", replacement.id(), FAILED);
            assertEquals(CANCELED, scheduler.get("tenant-a", blocker.id()).orElseThrow().status());
        }
    }

    @Test
    void evictsOnlyTerminalHistoryAndRejectsSubmissionAfterClose() throws Exception {
        try (var scheduler = new BacktestTaskScheduler(1, 2, 1)) {
            var started = new CountDownLatch(1);
            var running = scheduler.submit("tenant-a", () -> {
                started.countDown();
                new CountDownLatch(1).await();
                return null;
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertThrows(RejectedExecutionException.class, () -> scheduler.submit("tenant-b", () -> null));
            scheduler.cancel("tenant-a", running.id());
            var next = scheduler.submit("tenant-b", () -> { throw new IllegalStateException("failure"); });
            assertTrue(scheduler.get("tenant-a", running.id()).isEmpty());
            awaitStatus(scheduler, "tenant-b", next.id(), FAILED);
            scheduler.close();
            assertThrows(RejectedExecutionException.class, () -> scheduler.submit("tenant-b", () -> null));
        }
    }

    private static void awaitStatus(BacktestTaskScheduler scheduler, String tenant, UUID id,
                                    BacktestTaskScheduler.Status expected) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (scheduler.get(tenant, id).orElseThrow().status() != expected) {
                Thread.sleep(5);
            }
        });
    }
}
