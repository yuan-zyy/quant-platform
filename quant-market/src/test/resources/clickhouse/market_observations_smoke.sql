INSERT INTO market_observations_v1
    (dataset_version, import_generation, exchange, symbol, session_date, observation_kind,
     event_time, available_time, open_price, opening_quantity, high_price, low_price, close_price, session_volume)
VALUES
    ('daily-v1', 1, 'SSE', '600000', '2026-01-05', 'open',
     '2026-01-05 01:30:00', '2026-01-05 01:30:00', 10, 100, NULL, NULL, NULL, NULL),
    ('daily-v1', 1, 'SSE', '600000', '2026-01-05', 'close',
     '2026-01-05 07:00:00', '2026-01-05 07:00:00.000000001', 10, NULL, 12, 9, 11, 1000),
    ('daily-v1', 2, 'SSE', '600000', '2026-01-05', 'close',
     '2026-01-05 07:00:00', '2026-01-05 07:00:00.000000002', 10, NULL, 12, 9, 12, 1100);

OPTIMIZE TABLE market_observations_v1 FINAL;

SELECT throwIf(count() != 3 OR countIf(observation_kind = 'open' AND opening_quantity = 100
    AND isNull(close_price)) != 1, 'Publications or opening payload lost')
FROM market_observations_v1;

SELECT throwIf(count() != 1 OR tupleElement(argMax(tuple(close_price, session_volume), available_time), 1) != 11,
    'As-of query included a later revision')
FROM market_observations_v1
WHERE dataset_version = 'daily-v1' AND observation_kind = 'close'
  AND available_time <= toDateTime64('2026-01-05 07:00:00.000000001', 9, 'UTC');

SELECT throwIf(count() != 1 OR tupleElement(argMax(tuple(close_price, session_volume), available_time), 1) != 11,
    'Pinned generation included a later import')
FROM market_observations_v1
WHERE dataset_version = 'daily-v1' AND observation_kind = 'close' AND import_generation <= 1
  AND available_time <= toDateTime64('2026-01-05 07:00:01', 9, 'UTC');

SELECT throwIf(count() != 2
    OR tupleElement(argMax(tuple(close_price, session_volume), available_time), 1) != 12
    OR tupleElement(argMax(tuple(close_price, session_volume), available_time), 2) != 1100
    OR tupleElement(argMin(tuple(close_price, session_volume), available_time), 1) != 11,
    'Latest snapshot and first replay publication disagree with expected payloads')
FROM market_observations_v1
WHERE dataset_version = 'daily-v1' AND observation_kind = 'close'
  AND available_time <= toDateTime64('2026-01-05 07:00:01', 9, 'UTC');
