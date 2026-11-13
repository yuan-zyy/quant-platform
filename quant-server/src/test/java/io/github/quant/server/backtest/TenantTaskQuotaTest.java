package io.github.quant.server.backtest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class TenantTaskQuotaTest {
    @Test
    void capsEachTenantIndependentlyAndCannotReleaseTwice() {
        var quota = new TenantTaskQuota(1);
        try (var first = quota.reserve("tenant-a"); var other = quota.reserve("tenant-b")) {
            assertThrows(RejectedExecutionException.class, () -> quota.reserve("tenant-a"));
            assertThrows(RejectedExecutionException.class, () -> quota.reserve("tenant-b"));
            first.close();
            try (var replacement = quota.reserve("tenant-a")) {
                first.close();
                assertThrows(RejectedExecutionException.class, () -> quota.reserve("tenant-a"));
            }
            try (var replacement = quota.reserve("tenant-a")) {
                assertThrows(RejectedExecutionException.class, () -> quota.reserve("tenant-b"));
            }
        }
    }

    @Test
    void releasesOnExceptionAndRejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> new TenantTaskQuota(0));
        var quota = new TenantTaskQuota(1);
        assertThrows(IllegalArgumentException.class, () -> quota.reserve(" "));
        assertThrows(IllegalStateException.class, () -> {
            try (var ignored = quota.reserve("tenant-a")) {
                throw new IllegalStateException("fixture work failed");
            }
        });
        try (var recovered = quota.reserve("tenant-a")) {
            assertThrows(RejectedExecutionException.class, () -> quota.reserve("tenant-a"));
        }
    }

    @Test
    void simultaneousReservationsCannotExceedCapacity() throws Exception {
        var quota = new TenantTaskQuota(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var attempts = IntStream.range(0, 16).mapToObj(index -> executor.submit(() -> {
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture did not start");
                try {
                    return quota.reserve("tenant-a");
                } catch (RejectedExecutionException exhausted) {
                    return null;
                }
            })).toList();
            var acquired = new java.util.ArrayList<TenantTaskQuota.Ticket>();
            start.countDown();
            try {
                for (var attempt : attempts) {
                    var ticket = attempt.get(5, TimeUnit.SECONDS);
                    if (ticket != null) acquired.add(ticket);
                }
                assertEquals(2, acquired.size());
            } finally {
                acquired.forEach(TenantTaskQuota.Ticket::close);
            }
            List<TenantTaskQuota.Ticket> reacquired = List.of(quota.reserve("tenant-a"), quota.reserve("tenant-a"));
            reacquired.forEach(TenantTaskQuota.Ticket::close);
        }
    }
}
