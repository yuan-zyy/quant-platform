INSERT INTO backtest_task
    (tenant_id, task_id, idempotency_key, scenario_id, scenario_version, status,
     started_at, finished_at, lease_owner, lease_epoch, lease_expires_at, attempt_count, result_json)
VALUES
    ('tenant-a', '00000000-0000-0000-0000-000000000001', 'recovery-queued', 'demo-buy-hold', 'v1',
     'QUEUED', NULL, NULL, NULL, 0, NULL, 0, NULL),
    ('tenant-a', '00000000-0000-0000-0000-000000000002', 'recovery-completed', 'demo-buy-hold', 'v1',
     'SUCCEEDED', '2026-01-05T07:00:00Z', '2026-01-05T07:01:00Z', NULL, 1, NULL, 1,
     '{"schemaVersion":1,"metrics":{"endingEquity":"10010.00","totalReturn":"0.001","maxDrawdown":"0","fillCount":1}}'),
    ('tenant-a', '00000000-0000-0000-0000-000000000003', 'recovery-expired', 'demo-buy-hold', 'v1',
     'RUNNING', '2026-01-05T07:00:00Z', NULL, 'old-worker', 1, '-infinity', 1, NULL);

INSERT INTO broker_order
    (account_id, client_order_id, exchange, symbol, side, quantity, limit_price, status,
     submit_deadline, broker_order_id, next_reconcile_at)
VALUES
    ('account-a', 'unknown-1', 'SSE', '600000', 'BUY', 100, 10.25, 'UNKNOWN', '-infinity', NULL, '-infinity'),
    ('account-a', 'submitting-1', 'SSE', '600000', 'BUY', 100, 10.25, 'SUBMITTING', '-infinity', NULL, '-infinity'),
    ('account-a', 'acknowledged-1', 'SSE', '600000', 'SELL', 100, 10.25, 'ACKNOWLEDGED',
     '-infinity', 'simulated-recovery-ack', '-infinity'),
    ('account-a', 'deferred-1', 'SSE', '600000', 'BUY', 100, 10.25, 'UNKNOWN', '-infinity', NULL, 'infinity');
