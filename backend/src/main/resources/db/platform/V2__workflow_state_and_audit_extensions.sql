-- SPDX-FileCopyrightText: 2026 TRICORE contributors
-- SPDX-License-Identifier: Apache-2.0
--
-- Additive: workflow state lives on the record itself (single source of truth, same row, same version
-- counter), and audit gains workflow and actor-kind facts. Existing rows stay valid.
-- The runtime login's table privileges are unchanged (they already cover the new columns).

ALTER TABLE opentrix_platform.records
    ADD COLUMN workflow_state TEXT
        CHECK (workflow_state IS NULL OR workflow_state ~ '^[A-Z][A-Z0-9_]{0,31}$');

ALTER TABLE opentrix_platform.audit_events
    ADD COLUMN transition_id TEXT CHECK (transition_id IS NULL OR transition_id ~ '^[a-z][a-zA-Z0-9_]{0,62}$'),
    ADD COLUMN from_state    TEXT CHECK (from_state IS NULL OR from_state ~ '^[A-Z][A-Z0-9_]{0,31}$'),
    ADD COLUMN to_state      TEXT CHECK (to_state IS NULL OR to_state ~ '^[A-Z][A-Z0-9_]{0,31}$'),
    ADD COLUMN actor_type    TEXT NOT NULL DEFAULT 'USER' CHECK (actor_type IN ('USER', 'MACHINE'));

-- V1 created these as an unnamed column check and an unnamed table check.
ALTER TABLE opentrix_platform.audit_events DROP CONSTRAINT audit_events_action_check;
ALTER TABLE opentrix_platform.audit_events DROP CONSTRAINT audit_events_check;

ALTER TABLE opentrix_platform.audit_events
    ADD CONSTRAINT audit_events_action_valid
        CHECK (action IN ('CREATE', 'UPDATE', 'DELETE', 'TRANSITION')),
    ADD CONSTRAINT audit_events_shape_valid CHECK (
           (action = 'CREATE'     AND before_data IS NULL AND after_data IS NOT NULL
                                  AND transition_id IS NULL AND from_state IS NULL)
        OR (action = 'UPDATE'     AND before_data IS NOT NULL AND after_data IS NOT NULL
                                  AND transition_id IS NULL AND from_state IS NULL AND to_state IS NULL)
        OR (action = 'DELETE'     AND before_data IS NOT NULL AND after_data IS NULL
                                  AND transition_id IS NULL AND from_state IS NULL AND to_state IS NULL)
        OR (action = 'TRANSITION' AND before_data IS NULL AND after_data IS NULL
                                  AND transition_id IS NOT NULL AND from_state IS NOT NULL AND to_state IS NOT NULL));
