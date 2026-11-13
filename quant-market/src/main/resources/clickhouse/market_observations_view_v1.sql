-- The batch journal is the source of truth. Preserve its timestamps and decimal text without conversion.
CREATE VIEW IF NOT EXISTS market_observations_view_v1 AS
SELECT
    dataset_version,
    import_generation,
    operation_id,
    base64Decode(fields[2]) AS exchange,
    base64Decode(fields[1]) AS symbol,
    fields[3] AS session_date,
    if(fields[6] = 'O', 'open', 'close') AS observation_kind,
    fields[4] AS event_time,
    fields[5] AS available_time,
    fields[7] AS open_price,
    if(fields[6] = 'O', toInt64OrNull(fields[8]), NULL) AS opening_quantity,
    if(fields[6] = 'C', fields[8], NULL) AS high_price,
    if(fields[6] = 'C', fields[9], NULL) AS low_price,
    if(fields[6] = 'C', fields[10], NULL) AS close_price,
    if(fields[6] = 'C', toInt64OrNull(fields[11]), NULL) AS session_volume
FROM
(
    SELECT
        dataset_version,
        import_generation,
        operation_id,
        splitByChar('\t', observation) AS fields
    FROM
    (
        SELECT DISTINCT dataset_version, import_generation, operation_id, payload
        FROM market_import_batches_v1
    )
    ARRAY JOIN splitByChar('\n', base64Decode(payload)) AS observation
)
WHERE throwIf(
    NOT ((fields[6] = 'O' AND length(fields) = 8
        AND match(fields[8], '^(0|[1-9][0-9]{0,18})$')
        AND ifNull(toString(toInt64OrNull(fields[8])), '') = fields[8])
        OR (fields[6] = 'C' AND length(fields) = 11
        AND match(fields[11], '^(0|[1-9][0-9]{0,18})$')
        AND ifNull(toString(toInt64OrNull(fields[11])), '') = fields[11])),
    'Invalid observation row in the market batch journal') = 0;
