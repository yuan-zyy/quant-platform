CREATE TABLE backtest_task (
    tenant_id text NOT NULL,
    task_id uuid NOT NULL,
    idempotency_key text,
    scenario_id text NOT NULL,
    scenario_version text NOT NULL,
    status text NOT NULL DEFAULT 'QUEUED',
    submitted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    started_at timestamptz,
    finished_at timestamptz,
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    lease_owner text,
    lease_epoch bigint NOT NULL DEFAULT 0,
    lease_expires_at timestamptz,
    attempt_count integer NOT NULL DEFAULT 0,
    result_json jsonb,
    failure_code text,
    PRIMARY KEY (tenant_id, task_id),
    UNIQUE (tenant_id, idempotency_key),
    CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELED')),
    CHECK (attempt_count >= 0 AND lease_epoch >= 0),
    CHECK ((status = 'RUNNING') = (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)),
    CHECK ((lease_owner IS NULL) = (lease_expires_at IS NULL)),
    CHECK ((status IN ('SUCCEEDED','FAILED','CANCELED')) = (finished_at IS NOT NULL)),
    CHECK ((status = 'SUCCEEDED') = (result_json IS NOT NULL)),
    CHECK (result_json IS NULL OR jsonb_typeof(result_json) = 'object'),
    CHECK ((status = 'FAILED') = (failure_code IS NOT NULL))
);

CREATE INDEX backtest_task_claim_idx
    ON backtest_task (next_attempt_at, submitted_at, task_id) WHERE status = 'QUEUED';
CREATE INDEX backtest_task_expiry_idx
    ON backtest_task (lease_expires_at, task_id) WHERE status = 'RUNNING';
