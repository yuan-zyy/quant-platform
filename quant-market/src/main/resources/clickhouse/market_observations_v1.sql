CREATE TABLE market_observations_v1
(
    dataset_version String,
    import_generation Int64,
    exchange LowCardinality(String),
    symbol String,
    session_date Date32,
    observation_kind Enum8('open' = 1, 'close' = 2),
    event_time DateTime64(9, 'UTC'),
    available_time DateTime64(9, 'UTC'),
    open_price Decimal(38, 12),
    opening_quantity Nullable(Int64),
    high_price Nullable(Decimal(38, 12)),
    low_price Nullable(Decimal(38, 12)),
    close_price Nullable(Decimal(38, 12)),
    session_volume Nullable(Int64),
    CONSTRAINT available_after_event CHECK available_time >= event_time
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(session_date)
ORDER BY (dataset_version, exchange, symbol, session_date, observation_kind, available_time);
