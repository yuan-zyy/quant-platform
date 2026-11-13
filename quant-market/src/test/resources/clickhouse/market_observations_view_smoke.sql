INSERT INTO market_import_batches_v1
    (dataset_version, import_generation, operation_id, payload)
SELECT 'view-smoke-v1', generation, lower(hex(SHA256(payload))), payload
FROM
(
    SELECT 1 AS generation, base64Encode(concat(
        'NjAwMDAw\tU1NF\t2026-01-05\t2026-01-05T01:30:00Z\t2026-01-05T01:30:00Z\tO\t10.000\t100\n',
        'NjAwMDAw\tU1NF\t2026-01-05\t2026-01-05T07:00:00Z\t2026-01-05T07:00:00.000000001Z\tC\t10.000\t12\t9\t11\t1000'
    )) AS payload FROM numbers(2)
    UNION ALL
    SELECT 2, base64Encode(concat(
        'NjAwMDAw\tU1NF\t2026-01-05\t2026-01-05T01:30:00Z\t2026-01-05T01:30:00Z\tO\t10.000\t100\n',
        'NjAwMDAw\tU1NF\t2026-01-05\t2026-01-05T07:00:00Z\t2026-01-05T07:00:00.000000002Z\tC\t10.000\t12\t9\t12\t1100'
    ))
);

SELECT throwIf(
    count() != 4
    OR countIf(exchange = 'SSE' AND symbol = '600000' AND session_date = '2026-01-05') != 4
    OR countIf(observation_kind = 'open' AND open_price = '10.000' AND opening_quantity = 100
        AND isNull(high_price) AND isNull(session_volume)) != 2
    OR uniqExactIf(import_generation, observation_kind = 'open') != 2
    OR countIf(import_generation = 1 AND observation_kind = 'close' AND close_price = '11'
        AND event_time = '2026-01-05T07:00:00Z' AND available_time = '2026-01-05T07:00:00.000000001Z'
        AND isNull(opening_quantity) AND session_volume = 1000) != 1
    OR countIf(import_generation = 2 AND observation_kind = 'close' AND close_price = '12'
        AND available_time = '2026-01-05T07:00:00.000000002Z' AND session_volume = 1100) != 1,
    'Projection lost a publication, included a physical retry, or changed an observation')
FROM market_observations_view_v1
WHERE dataset_version = 'view-smoke-v1';
