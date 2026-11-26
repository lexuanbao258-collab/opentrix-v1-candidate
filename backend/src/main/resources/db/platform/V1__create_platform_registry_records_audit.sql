-- SPDX-FileCopyrightText: 2026 TRICORE contributors
-- SPDX-License-Identifier: Apache-2.0
--
-- Platform schema, separate from the Workflow schema and its pinned Flyway history.
-- Run only by the migration owner (PlatformMigration). The runtime role gets DML on records,
-- insert-only audit, read-only registry, and no DDL privilege.

CREATE TABLE opentrix_platform.applications (
    app_key         TEXT PRIMARY KEY CHECK (app_key ~ '^[a-z][a-z0-9-]{0,62}$'),
    app_version     TEXT NOT NULL CHECK (length(app_version) BETWEEN 5 AND 64),
    manifest        JSONB NOT NULL CHECK (jsonb_typeof(manifest) = 'object'),
    manifest_sha256 TEXT NOT NULL CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$'),
    registered_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE opentrix_platform.entity_definitions (
    app_key       TEXT NOT NULL REFERENCES opentrix_platform.applications(app_key) ON DELETE RESTRICT,
    entity_key    TEXT NOT NULL CHECK (entity_key ~ '^[a-z][a-zA-Z0-9]{0,62}$'),
    record_schema JSONB NOT NULL CHECK (jsonb_typeof(record_schema) = 'object'),
    search_fields TEXT[] NOT NULL DEFAULT '{}',
    permissions   JSONB NOT NULL CHECK (jsonb_typeof(permissions) = 'object'),
    PRIMARY KEY (app_key, entity_key)
);

CREATE TABLE opentrix_platform.records (
    record_id  UUID PRIMARY KEY,
    tenant_id  TEXT NOT NULL CHECK (tenant_id ~ '^[a-z][a-z0-9-]{0,62}$'),
    app_key    TEXT NOT NULL,
    entity_key TEXT NOT NULL,
    data       JSONB NOT NULL CHECK (jsonb_typeof(data) = 'object' AND octet_length(data::text) <= 65536),
    version    BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by TEXT NOT NULL CHECK (length(created_by) BETWEEN 1 AND 1024),
    created_at TIMESTAMPTZ NOT NULL,
    updated_by TEXT NOT NULL CHECK (length(updated_by) BETWEEN 1 AND 1024),
    updated_at TIMESTAMPTZ NOT NULL CHECK (updated_at >= created_at),
    FOREIGN KEY (app_key, entity_key)
        REFERENCES opentrix_platform.entity_definitions(app_key, entity_key) ON DELETE RESTRICT
);

CREATE INDEX records_scope_idx
    ON opentrix_platform.records (tenant_id, app_key, entity_key, created_at DESC, record_id);
CREATE INDEX records_owner_idx
    ON opentrix_platform.records (tenant_id, app_key, entity_key, created_by);

-- No foreign key to records: the audit trail outlives deleted records.
CREATE TABLE opentrix_platform.audit_events (
    event_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    tenant_id      TEXT NOT NULL,
    app_key        TEXT NOT NULL,
    entity_key     TEXT NOT NULL,
    record_id      UUID NOT NULL,
    action         TEXT NOT NULL CHECK (action IN ('CREATE', 'UPDATE', 'DELETE')),
    actor_id       TEXT NOT NULL CHECK (length(actor_id) BETWEEN 1 AND 1024),
    record_version BIGINT NOT NULL CHECK (record_version >= 0),
    before_data    JSONB,
    after_data     JSONB,
    CHECK ((action = 'CREATE' AND before_data IS NULL AND after_data IS NOT NULL)
        OR (action = 'UPDATE' AND before_data IS NOT NULL AND after_data IS NOT NULL)
        OR (action = 'DELETE' AND before_data IS NOT NULL AND after_data IS NULL))
);

CREATE INDEX audit_events_record_idx
    ON opentrix_platform.audit_events (tenant_id, app_key, entity_key, record_id, event_id);

REVOKE ALL ON ALL TABLES IN SCHEMA opentrix_platform FROM PUBLIC;
GRANT USAGE ON SCHEMA opentrix_platform TO "${runtimeRole}";
GRANT SELECT ON opentrix_platform.applications, opentrix_platform.entity_definitions,
    opentrix_platform.platform_schema_history TO "${runtimeRole}";
GRANT SELECT, INSERT, UPDATE, DELETE ON opentrix_platform.records TO "${runtimeRole}";
GRANT SELECT, INSERT ON opentrix_platform.audit_events TO "${runtimeRole}";
