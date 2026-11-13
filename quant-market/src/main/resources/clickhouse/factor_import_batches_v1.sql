CREATE TABLE IF NOT EXISTS factor_import_batches_v1
(
    dataset_version String,
    import_generation Int64,
    operation_id String,
    payload String,
    CONSTRAINT valid_generation CHECK import_generation > 0
)
ENGINE = MergeTree
ORDER BY (dataset_version, import_generation, operation_id);
