ALTER TABLE broker_order
    ADD COLUMN next_reconcile_at timestamptz NOT NULL DEFAULT '-infinity';

CREATE INDEX broker_order_reconciliation_due_idx
    ON broker_order (next_reconcile_at, updated_at, account_id, client_order_id)
    WHERE status IN ('UNKNOWN', 'SUBMITTING');
