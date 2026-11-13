package io.github.quant.server.backtest;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/** Tracks queued and executing task reservations per trusted tenant identity. */
public final class TenantTaskQuota {
    private final int maxPerTenant;
    private final Map<String, Integer> active = new HashMap<>();

    public TenantTaskQuota(int maxPerTenant) {
        if (maxPerTenant < 1) throw new IllegalArgumentException("tenant task quota must be positive");
        this.maxPerTenant = maxPerTenant;
    }

    public synchronized Ticket reserve(String tenantId) {
        requireTenant(tenantId);
        int count = active.getOrDefault(tenantId, 0);
        if (count >= maxPerTenant) throw new RejectedExecutionException("tenant task quota exceeded");
        active.put(tenantId, count + 1);
        return new Ticket(tenantId);
    }

    public synchronized int inUse(String tenantId) {
        requireTenant(tenantId);
        return active.getOrDefault(tenantId, 0);
    }

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenant ID is required");
    }

    public final class Ticket implements AutoCloseable {
        private final String tenantId;
        private boolean released;

        private Ticket(String tenantId) {
            this.tenantId = tenantId;
        }

        @Override
        public void close() {
            synchronized (TenantTaskQuota.this) {
                if (released) return;
                active.computeIfPresent(tenantId, (ignored, count) -> count == 1 ? null : count - 1);
                released = true;
            }
        }
    }
}
