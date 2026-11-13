CREATE TABLE broker_order (
    account_id text NOT NULL,
    client_order_id text NOT NULL,
    exchange text NOT NULL,
    symbol text NOT NULL,
    side text NOT NULL CHECK (side IN ('BUY','SELL')),
    quantity bigint NOT NULL CHECK (quantity > 0),
    limit_price numeric NOT NULL CHECK (limit_price > 0),
    status text NOT NULL DEFAULT 'SUBMITTING'
        CHECK (status IN ('SUBMITTING','ACKNOWLEDGED','REJECTED','UNKNOWN')),
    submit_deadline timestamptz NOT NULL,
    broker_order_id text,
    code text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (account_id, client_order_id),
    CHECK ((status = 'ACKNOWLEDGED') = (broker_order_id IS NOT NULL)),
    CHECK ((status = 'REJECTED') = (code IS NOT NULL))
);
