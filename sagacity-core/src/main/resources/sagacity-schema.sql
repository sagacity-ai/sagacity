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

-- Durable workflow run state (sagacity-workflows v0.4.0+).
-- One row per WorkflowRun. Updated on every state transition so runs survive
-- JVM restarts. The full audit trail is in side_effect_journal; this table
-- only holds the minimal state needed to resume execution and render the UI.
CREATE TABLE IF NOT EXISTS sagacity_workflow_runs (
    run_id                  VARCHAR(36)  NOT NULL,
    workflow_name           VARCHAR(255) NOT NULL,
    status                  VARCHAR(50)  NOT NULL,
    current_stage_order     INT          NOT NULL DEFAULT 0,
    pending_gate_stage      VARCHAR(255),
    completed_stages        TEXT,
    last_stage_output       TEXT,
    last_stage_output_type  VARCHAR(512),
    failure_reason          TEXT,
    started_at              TIMESTAMP    NOT NULL,
    completed_at            TIMESTAMP,
    PRIMARY KEY (run_id)
);

CREATE INDEX IF NOT EXISTS idx_workflow_runs_status ON sagacity_workflow_runs (status);
CREATE INDEX IF NOT EXISTS idx_workflow_runs_name   ON sagacity_workflow_runs (workflow_name);
