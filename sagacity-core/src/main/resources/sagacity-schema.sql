-- Sagacity M1 Schema
-- Postgres 14+

CREATE TABLE IF NOT EXISTS side_effect_journal (
    saga_id     TEXT        NOT NULL,
    seq         BIGINT      NOT NULL,
    tool_name   TEXT        NOT NULL,
    phase       TEXT        NOT NULL,
    input       TEXT        NOT NULL DEFAULT '',
    payload     TEXT        NOT NULL DEFAULT '',
    timestamp   TIMESTAMP   NOT NULL,
    hash        CHAR(64)    NOT NULL,
    PRIMARY KEY (saga_id, seq)
);

CREATE INDEX IF NOT EXISTS idx_journal_saga_id ON side_effect_journal (saga_id);
