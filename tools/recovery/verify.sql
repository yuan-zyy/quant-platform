DO $$
BEGIN
    IF (SELECT count(*) FROM backtest_task) <> 3 OR NOT EXISTS (
        SELECT FROM backtest_task WHERE tenant_id = 'tenant-a'
            AND task_id = '00000000-0000-0000-0000-000000000001'
            AND idempotency_key = 'recovery-queued' AND scenario_id = 'demo-buy-hold'
            AND scenario_version = 'v1' AND status = 'QUEUED' AND attempt_count = 0
    ) OR NOT EXISTS (
        SELECT FROM backtest_task WHERE tenant_id = 'tenant-a'
            AND task_id = '00000000-0000-0000-0000-000000000002'
            AND status = 'SUCCEEDED' AND result_json->'metrics'->>'endingEquity' = '10010.00'
            AND (result_json->>'schemaVersion')::integer = 1
            AND finished_at = '2026-01-05T07:01:00Z'
    ) OR NOT EXISTS (
        SELECT FROM backtest_task WHERE tenant_id = 'tenant-a'
            AND task_id = '00000000-0000-0000-0000-000000000003'
            AND status = 'RUNNING' AND lease_owner = 'old-worker'
            AND lease_epoch = 1 AND attempt_count = 1 AND lease_expires_at <= clock_timestamp()
    ) THEN
        RAISE EXCEPTION 'restored task or lease state differs';
    END IF;

    IF (SELECT count(*) FROM broker_order) <> 4 OR NOT EXISTS (
        SELECT FROM broker_order WHERE account_id = 'account-a' AND client_order_id = 'unknown-1'
            AND status = 'UNKNOWN' AND next_reconcile_at <= clock_timestamp() AND limit_price = 10.25
    ) OR NOT EXISTS (
        SELECT FROM broker_order WHERE account_id = 'account-a' AND client_order_id = 'submitting-1'
            AND status = 'SUBMITTING' AND submit_deadline <= clock_timestamp()
    ) OR NOT EXISTS (
        SELECT FROM broker_order WHERE account_id = 'account-a' AND client_order_id = 'acknowledged-1'
            AND status = 'ACKNOWLEDGED' AND broker_order_id = 'simulated-recovery-ack'
    ) OR NOT EXISTS (
        SELECT FROM broker_order WHERE account_id = 'account-a' AND client_order_id = 'deferred-1'
            AND status = 'UNKNOWN' AND next_reconcile_at = 'infinity'
    ) THEN
        RAISE EXCEPTION 'restored order or reconciliation state differs';
    END IF;

    BEGIN
        INSERT INTO backtest_task (tenant_id, task_id, idempotency_key, scenario_id, scenario_version)
        VALUES ('tenant-a', '00000000-0000-0000-0000-000000000004', 'recovery-queued', 'demo-buy-hold', 'v1');
        RAISE EXCEPTION 'restored tenant idempotency constraint did not reject a duplicate';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;
END;
$$;
