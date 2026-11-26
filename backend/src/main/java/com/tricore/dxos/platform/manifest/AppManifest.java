// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0
package com.tricore.dxos.platform.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.tricore.dxos.core.workflow.WorkflowDefinition;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** A validated application manifest. Instances are only created by {@link ManifestValidator}. */
public record AppManifest(String id, String version, String sha256, JsonNode document,
                          Set<String> roles, Map<String, Entity> entities) {

    public enum Operation { READ, CREATE, UPDATE, DELETE, AUDIT }

    /** ALL covers every record of the entity; OWN covers records created by the caller. */
    public enum Scope { OWN, ALL }

    /**
     * Finite workflow: the state machine is the reused core {@link WorkflowDefinition}; the Platform adds only
     * per-state editability and per-transition role grants. Terminal states are the ones without outgoing transitions.
     */
    public record Workflow(WorkflowDefinition definition, Map<String, Boolean> editable,
                           Map<String, Map<String, Scope>> transitionRoles) {

        public boolean isEditable(String state) {
            return editable.getOrDefault(state, true);
        }

        public Optional<Scope> scopeFor(String transitionId, Set<String> callerRoles) {
            Scope widest = null;
            for (var grant : transitionRoles.getOrDefault(transitionId, Map.of()).entrySet()) {
                if (callerRoles.contains(grant.getKey()) && (widest == null || grant.getValue() == Scope.ALL)) {
                    widest = grant.getValue();
                }
            }
            return Optional.ofNullable(widest);
        }
    }

    public record Entity(String key, JsonNode recordSchema, List<String> searchFields, Set<String> sensitiveFields,
                         Map<Operation, Map<String, Scope>> permissions, Workflow workflow) {

        /** Widest scope any of the caller's roles grants for the operation, if any. */
        public Optional<Scope> scopeFor(Operation operation, Set<String> callerRoles) {
            Scope widest = null;
            for (var grant : permissions.getOrDefault(operation, Map.of()).entrySet()) {
                if (callerRoles.contains(grant.getKey()) && (widest == null || grant.getValue() == Scope.ALL)) {
                    widest = grant.getValue();
                }
            }
            return Optional.ofNullable(widest);
        }
    }
}
