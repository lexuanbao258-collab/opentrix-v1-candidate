// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0
package com.tricore.dxos.platform.manifest;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** A validated application manifest. Instances are only created by {@link ManifestValidator}. */
public record AppManifest(String id, String version, String sha256, JsonNode document,
                          Set<String> roles, Map<String, Entity> entities) {

    public enum Operation { READ, CREATE, UPDATE, DELETE }

    /** ALL covers every record of the entity; OWN covers records created by the caller. */
    public enum Scope { OWN, ALL }

    public record Entity(String key, JsonNode recordSchema, List<String> searchFields,
                         Map<Operation, Map<String, Scope>> permissions) {

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
