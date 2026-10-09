-- Sagacity Schema v0.5.0
-- Compatible with: PostgreSQL 14+, MySQL 8+, MariaDB 10.6+, H2, SQLite

-- Tamper-evident audit trail for AI agent tool calls.
-- The phase column stores the Phase discriminator (e.g. 'Executed').
-- The phase_data column stores phase-specific JSON (e.g. {"result":"ok"}).
-- Together they replace the old payload column whose meaning varied by phase.
CREATE TABLE IF NOT EXISTS sagacity_journal (
    saga_id     TEXT        NOT NULL,
    seq         BIGINT      NOT NULL,
    tool_name   TEXT        NOT NULL,
    phase       TEXT        NOT NULL,
    phase_data  TEXT        NOT NULL DEFAULT '{}',
    input       TEXT        NOT NULL DEFAULT '',
    recorded_at TIMESTAMP   NOT NULL,
    hash        CHAR(64)    NOT NULL,
    PRIMARY KEY (saga_id, seq)
);

CREATE INDEX IF NOT EXISTS idx_sagacity_journal_saga_id
    ON sagacity_journal (saga_id);

CREATE INDEX IF NOT EXISTS idx_sagacity_journal_phase
    ON sagacity_journal (saga_id, phase);

-- Pending human approval requests for IRREVERSIBLE tools.
-- Rows are deleted once consumed (approved+executed or rejected).
-- The durable decision record lives in sagacity_journal
-- (AwaitingApproval / Approved / Rejected phases), not here.
CREATE TABLE IF NOT EXISTS sagacity_approval_request (
    saga_id      TEXT        NOT NULL,
    journal_seq  BIGINT      NOT NULL,
    tool_name    TEXT        NOT NULL,
    input        TEXT        NOT NULL DEFAULT '',
    input_hash   CHAR(64)    NOT NULL,
    created_at   TIMESTAMP   NOT NULL,
    PRIMARY KEY (saga_id, journal_seq)
);

CREATE INDEX IF NOT EXISTS idx_sagacity_approval_saga_id
    ON sagacity_approval_request (saga_id);
