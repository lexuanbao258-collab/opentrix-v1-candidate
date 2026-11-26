// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0
package com.tricore.dxos.portal;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Explicit allowlist of Platform API v1 routes for the browser (not a wildcard proxy). Path segments are
 * checked against the manifest identifier grammar and rebuilt; query parameters are re-encoded.
 * Authorization stays in the Platform backend; this layer only holds the session token and CSRF.
 */
@RestController
@RequestMapping("/bff/api/v1/apps")
class PortalPlatformClient {
    private static final Pattern APP = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private static final Pattern ENTITY = Pattern.compile("[a-z][a-zA-Z0-9]{0,62}");
    private final PortalBackendClient backend;

    PortalPlatformClient(PortalBackendClient backend) {
        this.backend = backend;
    }

    @GetMapping
    ResponseEntity<?> applications(OAuth2AuthenticationToken principal, HttpServletRequest request) {
        return backend.forward("GET", "/api/v1/apps", null, principal, request);
    }

    @GetMapping("/{app}/entities/{entity}")
    ResponseEntity<?> entity(@PathVariable String app, @PathVariable String entity,
                             OAuth2AuthenticationToken principal, HttpServletRequest request) {
        if (!valid(app, entity)) return ResponseEntity.notFound().build();
        return backend.forward("GET", base(app, entity), null, principal, request);
    }

    @GetMapping("/{app}/entities/{entity}/records")
    ResponseEntity<?> search(@PathVariable String app, @PathVariable String entity,
                             @RequestParam(required = false) String q,
                             @RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "0") int offset,
                             OAuth2AuthenticationToken principal, HttpServletRequest request) {
        if (!valid(app, entity)) return ResponseEntity.notFound().build();
        if (limit < 1 || limit > 100 || offset < 0 || offset > 10_000 || (q != null && (q.isEmpty() || q.length() > 100))) {
            return ResponseEntity.badRequest().build();
        }
        String query = "?limit=" + limit + "&offset=" + offset
                + (q == null ? "" : "&q=" + URLEncoder.encode(q, StandardCharsets.UTF_8));
        return backend.forward("GET", base(app, entity) + "/records" + query, null, principal, request);
    }

    @PostMapping("/{app}/entities/{entity}/records")
    ResponseEntity<?> create(@PathVariable String app, @PathVariable String entity,
                             OAuth2AuthenticationToken principal, HttpServletRequest request) {
        if (!valid(app, entity)) return ResponseEntity.notFound().build();
        return backend.forwardWrite("POST", base(app, entity) + "/records", request, principal);
    }

    @GetMapping("/{app}/entities/{entity}/records/{id}")
    ResponseEntity<?> get(@PathVariable String app, @PathVariable String entity, @PathVariable UUID id,
                          OAuth2AuthenticationToken principal, HttpServletRequest request) {
        if (!valid(app, entity)) return ResponseEntity.notFound().build();
        return backend.forward("GET", base(app, entity) + "/records/" + id, null, principal, request);
    }

    @PutMapping("/{app}/entities/{entity}/records/{id}")
    ResponseEntity<?> update(@PathVariable String app, @PathVariable String entity, @PathVariable UUID id,
                             OAuth2AuthenticationToken principal, HttpServletRequest request) {
        if (!valid(app, entity)) return ResponseEntity.notFound().build();
        return backend.forwardWrite("PUT", base(app, entity) + "/records/" + id, request, principal);
    }

    @DeleteMapping("/{app}/entities/{entity}/records/{id}")
    ResponseEntity<?> delete(@PathVariable String app, @PathVariable String entity, @PathVariable UUID id,
                             @RequestParam long expectedVersion,
                             OAuth2AuthenticationToken principal, HttpServletRequest request) {
        if (!valid(app, entity)) return ResponseEntity.notFound().build();
        if (expectedVersion < 0) return ResponseEntity.badRequest().build();
        return backend.forward("DELETE", base(app, entity) + "/records/" + id + "?expectedVersion=" + expectedVersion,
                null, principal, request);
    }

    private static boolean valid(String app, String entity) {
        return APP.matcher(app).matches() && ENTITY.matcher(entity).matches();
    }

    private static String base(String app, String entity) {
        return "/api/v1/apps/" + app + "/entities/" + entity;
    }
}
