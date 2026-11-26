// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0
package com.tricore.dxos.platform.security;

import com.tricore.dxos.application.identity.CurrentPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the verified caller for Platform requests.
 * Identity: the canonical subject from {@link CurrentPrincipal} (issuer + sub; never request fields).
 * Roles: Keycloak client roles of the resource client, named {@code <appId>:<role>}. A role is only
 * meaningful inside its own application; {@code it-helpdesk:admin} grants nothing in another app.
 */
@Component
@ConditionalOnProperty(name = "opentrix.platform.enabled", havingValue = "true")
public class PlatformCaller {
    private final CurrentPrincipal principal;
    private final String resourceClientId;

    public PlatformCaller(CurrentPrincipal principal,
                          @Value("${tricore.security.oidc.resource-client-id}") String resourceClientId) {
        this.principal = principal;
        this.resourceClientId = resourceClientId;
    }

    /** {@code machine}: the token was issued to an OAuth2 client (client_credentials), not to a signed-in person. */
    public record Caller(String subjectId, Set<String> clientRoles, boolean machine) {
        public Set<String> rolesIn(String appId) {
            String prefix = appId + ":";
            Set<String> roles = new HashSet<>();
            for (String role : clientRoles) {
                if (role.startsWith(prefix) && role.length() > prefix.length()) roles.add(role.substring(prefix.length()));
            }
            return Set.copyOf(roles);
        }
    }

    /**
     * Keycloak's service_account scope maps the client-credentials session note to client_id.
     * This only labels audit events; authorization depends on roles alone.
     */
    static boolean isMachine(org.springframework.security.oauth2.jwt.Jwt jwt) {
        String clientId = jwt.getClaimAsString("client_id");
        return clientId != null && !clientId.isBlank() && clientId.equals(jwt.getClaimAsString("azp"));
    }

    public Caller require() {
        String subjectId = principal.requireVerified().subjectId();
        Set<String> roles = Set.of();
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token
                && token.getToken().getClaim("resource_access") instanceof Map<?, ?> clients
                && clients.get(resourceClientId) instanceof Map<?, ?> client
                && client.get("roles") instanceof List<?> values
                && values.stream().allMatch(String.class::isInstance)) {
            roles = Set.copyOf(values.stream().map(String.class::cast).toList());
        }
        boolean machine = SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token
                && isMachine(token.getToken());
        return new Caller(subjectId, roles, machine);
    }
}
