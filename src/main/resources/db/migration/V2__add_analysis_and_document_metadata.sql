DO $$
BEGIN
    IF to_regclass('analysis_history') IS NOT NULL THEN
        ALTER TABLE analysis_history ADD COLUMN IF NOT EXISTS status_id INTEGER;
        ALTER TABLE analysis_history ADD COLUMN IF NOT EXISTS failedStep VARCHAR(64);
        ALTER TABLE analysis_history ADD COLUMN IF NOT EXISTS errorMessage TEXT;
        ALTER TABLE analysis_history ADD COLUMN IF NOT EXISTS attempts INTEGER NOT NULL DEFAULT 0;
        ALTER TABLE analysis_history ADD COLUMN IF NOT EXISTS maxAttempts INTEGER NOT NULL DEFAULT 1;
        ALTER TABLE analysis_history ADD COLUMN IF NOT EXISTS startedAt TIMESTAMP;
        ALTER TABLE analysis_history ADD COLUMN IF NOT EXISTS finishedAt TIMESTAMP;
    END IF;
END $$;

DO $$
DECLARE
    document_table REGCLASS;
BEGIN
    document_table := to_regclass('"Document"');
    IF document_table IS NULL THEN
        document_table := to_regclass('document');
    END IF;

    IF document_table IS NOT NULL THEN
        EXECUTE format('ALTER TABLE %s ADD COLUMN IF NOT EXISTS sha256Hash VARCHAR(64)', document_table);
        EXECUTE format('ALTER TABLE %s ADD COLUMN IF NOT EXISTS originalSizeBytes BIGINT NOT NULL DEFAULT 0', document_table);
        EXECUTE format('ALTER TABLE %s ADD COLUMN IF NOT EXISTS compressedSizeBytes BIGINT NOT NULL DEFAULT 0', document_table);
        EXECUTE format('ALTER TABLE %s ADD COLUMN IF NOT EXISTS base64SizeBytes BIGINT NOT NULL DEFAULT 0', document_table);
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('status') IS NOT NULL THEN
        CREATE UNIQUE INDEX IF NOT EXISTS status_libelle_unique_idx ON status (libelle);
    END IF;
END $$;
