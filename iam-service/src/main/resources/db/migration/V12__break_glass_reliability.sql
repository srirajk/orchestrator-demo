-- C6 durable issuance/outbox ledger. PENDING is written before promotion; ACTIVATED is written after
-- the idempotent C5 promotion receipt. audit_recorded=false on an ACTIVATED row is a durable outbox item
-- that a retry/reconciler can safely finish.
CREATE TABLE IF NOT EXISTS break_glass_issuances (
    grant_id          TEXT PRIMARY KEY,
    tenant_id         TEXT NOT NULL,
    scope             TEXT NOT NULL,
    resource_kind     TEXT NOT NULL,
    action            TEXT NOT NULL,
    role_name         TEXT NOT NULL,
    issued_at         TIMESTAMPTZ NOT NULL,
    expires_at        TIMESTAMPTZ NOT NULL,
    requested_by      TEXT NOT NULL,
    justification     TEXT,
    approved_by       TEXT NOT NULL,
    correlation_id    TEXT NOT NULL,
    state             TEXT NOT NULL,
    from_bundle_id    TEXT,
    active_bundle_id  TEXT,
    audit_recorded    BOOLEAN NOT NULL DEFAULT FALSE,
    last_error        TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_break_glass_issuance_reconcile
    ON break_glass_issuances (state, audit_recorded, updated_at);

-- Both issuance and use writes are idempotent on their durable decision/correlation identity.
CREATE UNIQUE INDEX IF NOT EXISTS uq_break_glass_audit_event
    ON audit_log (tenant_id, action, resource_type, resource_id, correlation_id)
    WHERE action IN ('break_glass.granted', 'break_glass.used') AND correlation_id IS NOT NULL;

-- One Cerbos call can carry many resource/action decisions, so C6 uses a composite event identity rather
-- than collapsing a batch into cerbos_decision_log's callId primary key.
CREATE TABLE IF NOT EXISTS break_glass_decision_events (
    event_id              TEXT PRIMARY KEY,
    cerbos_call_id        TEXT NOT NULL,
    tenant_id             TEXT NOT NULL,
    active_policy_version TEXT NOT NULL,
    decision              TEXT NOT NULL,
    principal_id          TEXT NOT NULL,
    principal_roles       TEXT NOT NULL,
    resource_kind         TEXT NOT NULL,
    resource_id           TEXT NOT NULL,
    action                TEXT NOT NULL,
    occurred_at           TIMESTAMPTZ NOT NULL,
    processed             BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_break_glass_decision_pending
    ON break_glass_decision_events (decision, processed, occurred_at);

-- Immutable lineage: every promoted bundle maps to every break-glass grant whose rules it contains.
CREATE TABLE IF NOT EXISTS break_glass_bundle_grants (
    id         TEXT PRIMARY KEY,
    bundle_id  TEXT NOT NULL,
    grant_id   TEXT NOT NULL,
    UNIQUE (bundle_id, grant_id)
);
CREATE INDEX IF NOT EXISTS idx_break_glass_bundle_grants_bundle
    ON break_glass_bundle_grants (bundle_id);
