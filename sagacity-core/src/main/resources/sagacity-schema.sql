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

-- Pending human approvals for IRREVERSIBLE tools.
-- Unlike the journal this table is mutable: rows are deleted once the approval
-- is consumed or rejected. The durable record of the decision lives in the
-- journal (AWAITING_APPROVAL / APPROVED / REJECTED), not here.
CREATE TABLE IF NOT EXISTS sagacity_approval_request (
    saga_id      TEXT        NOT NULL,
    journal_seq  BIGINT      NOT NULL,
    tool_name    TEXT        NOT NULL,
    input        TEXT        NOT NULL DEFAULT '',
    input_hash   CHAR(64)    NOT NULL,
    created_at   TIMESTAMP   NOT NULL,
    PRIMARY KEY (saga_id, journal_seq)
);

CREATE INDEX IF NOT EXISTS idx_approval_saga_id ON sagacity_approval_request (saga_id);
