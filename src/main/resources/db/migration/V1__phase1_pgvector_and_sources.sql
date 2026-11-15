-- Phase 1 migration: pgvector extension, passage embeddings (1536d for gemini-embedding-2),
-- source storage/error columns, and product build-step runtime columns.
-- Run this migration directly against Neon/PostgreSQL in production (where ddl-auto=validate/none
-- and RELAY_PGVECTOR_AUTO_MIGRATE=false).

CREATE EXTENSION IF NOT EXISTS vector;

-- 1. relay_product step state columns
ALTER TABLE IF EXISTS relay_product
    ADD COLUMN IF NOT EXISTS step_status VARCHAR(20) NOT NULL DEFAULT 'todo',
    ADD COLUMN IF NOT EXISTS step_progress JSONB,
    ADD COLUMN IF NOT EXISTS step_note TEXT,
    ADD COLUMN IF NOT EXISTS cancelled BOOLEAN NOT NULL DEFAULT false;

-- 2. relay_source storage & error columns
ALTER TABLE IF EXISTS relay_source
    ADD COLUMN IF NOT EXISTS storage_key VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS error_message TEXT;

-- 3. relay_passage product_id FK and vector(1536) embedding column
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'relay_passage'
    ) THEN
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'relay_passage' AND column_name = 'product_id'
        ) THEN
            ALTER TABLE relay_passage ADD COLUMN product_id BIGINT;
            UPDATE relay_passage p
            SET product_id = s.product_id
            FROM relay_source s
            WHERE p.source_id = s.id AND p.product_id IS NULL;
            DELETE FROM relay_passage WHERE product_id IS NULL;
            ALTER TABLE relay_passage ALTER COLUMN product_id SET NOT NULL;
            ALTER TABLE relay_passage
                ADD CONSTRAINT fk_relay_passage_product
                FOREIGN KEY (product_id) REFERENCES relay_product(id) ON DELETE CASCADE;
        END IF;

        IF EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'relay_passage'
              AND column_name = 'embedding'
              AND udt_name <> 'vector'
        ) THEN
            ALTER TABLE relay_passage
                ALTER COLUMN embedding TYPE vector(1536) USING NULL;
        END IF;
    END IF;
END $$;

-- 4. Product, source, and HNSW cosine distance indexes on relay_passage
CREATE INDEX IF NOT EXISTS idx_relay_passage_product_id
    ON relay_passage (product_id);

CREATE INDEX IF NOT EXISTS idx_relay_passage_source_id
    ON relay_passage (source_id);

CREATE INDEX IF NOT EXISTS idx_relay_passage_embedding_hnsw
    ON relay_passage USING hnsw (embedding vector_cosine_ops);

