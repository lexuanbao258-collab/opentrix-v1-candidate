-- SPDX-FileCopyrightText: 2026 TRICORE contributors
-- SPDX-License-Identifier: Apache-2.0

CREATE TABLE opentrix_platform.application_versions (
    app_key         TEXT NOT NULL REFERENCES opentrix_platform.applications(app_key) ON DELETE RESTRICT,
    app_version     TEXT NOT NULL CHECK (length(app_version) BETWEEN 5 AND 64),
    manifest        JSONB NOT NULL CHECK (jsonb_typeof(manifest) = 'object'),
    manifest_sha256 TEXT NOT NULL CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$'),
    registered_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (app_key, app_version)
);

INSERT INTO opentrix_platform.application_versions
    (app_key, app_version, manifest, manifest_sha256, registered_at)
SELECT app_key, app_version, manifest, manifest_sha256, registered_at
FROM opentrix_platform.applications;

-- Keep reserved IDs even after deletion. The runtime can claim once and read, but cannot release a claim.
CREATE TABLE opentrix_platform.create_requests (
    record_id      UUID PRIMARY KEY,
    tenant_id      TEXT NOT NULL,
    app_key        TEXT NOT NULL,
    entity_key     TEXT NOT NULL,
    actor_id       TEXT NOT NULL,
    actor_type     TEXT NOT NULL CHECK (actor_type IN ('USER', 'MACHINE')),
    payload_sha256 BYTEA CHECK (octet_length(payload_sha256) = 32)
);

-- Historical IDs are reserved without a fingerprint: the original unredacted request is unavailable.
INSERT INTO opentrix_platform.create_requests (record_id, tenant_id, app_key, entity_key, actor_id, actor_type)
SELECT DISTINCT ON (record_id) record_id, tenant_id, app_key, entity_key, actor_id, actor_type
FROM opentrix_platform.audit_events
ORDER BY record_id, event_id;

INSERT INTO opentrix_platform.create_requests (record_id, tenant_id, app_key, entity_key, actor_id, actor_type)
SELECT record_id, tenant_id, app_key, entity_key, created_by, 'USER'
FROM opentrix_platform.records ON CONFLICT (record_id) DO NOTHING;

REVOKE ALL ON opentrix_platform.application_versions, opentrix_platform.create_requests FROM PUBLIC;
GRANT SELECT ON opentrix_platform.application_versions TO "${runtimeRole}";
GRANT SELECT, INSERT ON opentrix_platform.create_requests TO "${runtimeRole}";
