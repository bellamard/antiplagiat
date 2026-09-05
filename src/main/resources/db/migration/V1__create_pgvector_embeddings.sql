CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS embeddings (
    document_id TEXT NOT NULL,
    chunk_index INTEGER NOT NULL,
    chunk_hash TEXT NOT NULL,
    chunk_text TEXT NOT NULL,
    embedding vector(384) NOT NULL,
    model_name TEXT NOT NULL DEFAULT 'all-MiniLM-L6-v2',
    model_version TEXT,
    chunking_version TEXT NOT NULL DEFAULT 'semantic-v1',
    page_start INTEGER,
    page_end INTEGER,
    char_start INTEGER,
    char_end INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (document_id, chunk_index)
);

CREATE INDEX IF NOT EXISTS embeddings_document_id_idx ON embeddings (document_id);
CREATE INDEX IF NOT EXISTS embeddings_chunk_hash_idx ON embeddings (chunk_hash);
CREATE INDEX IF NOT EXISTS embeddings_embedding_hnsw_idx ON embeddings USING hnsw (embedding vector_cosine_ops);
