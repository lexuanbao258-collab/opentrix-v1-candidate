// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0
package com.tricore.dxos.infrastructure.identity.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test-only token issuer. The decoder is the production {@link SecurityConfig#configureJwtDecoder}
 * chain (issuer, audience, typ, subject, expiry validators); only the key source is local.
 */
public final class TestJwt {
    public static final String ISSUER = "https://issuer.example.test";
    public static final String AUDIENCE = "tricore-api";
    private static final RSAKey KEY = generate();

    private TestJwt() {}

    public static JwtDecoder decoder() {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256,
                new ImmutableJWKSet<>(new JWKSet(KEY.toPublicJWK()))));
        var reference = new AtomicReference<com.nimbusds.jwt.proc.ConfigurableJWTProcessor<SecurityContext>>();
        SecurityConfig.jwtProcessorCustomizer(reference).accept(processor);
        return SecurityConfig.configureJwtDecoder(reference.get(), ISSUER, AUDIENCE);
    }

    /** Keycloak-shaped client_credentials token: service-account subject, azp = client id. */
    public static String machineToken(String clientId, String... clientRoles) {
        return sign(base("service-account-" + clientId, clientRoles).claim("azp", clientId)
                .claim("client_id", clientId).build());
    }

    /** Signed access token with Keycloak-shaped client roles on the resource client. */
    public static String token(String subject, String... clientRoles) {
        return sign(base(subject, clientRoles).claim("azp", "portal").claim("preferred_username", subject).build());
    }

    private static JWTClaimsSet.Builder base(String subject, String... clientRoles) {
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER).audience(AUDIENCE).subject(subject)
                .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("typ", "Bearer")
                .claim("resource_access", Map.of(AUDIENCE, Map.of("roles", List.of(clientRoles))));
    }

    private static String sign(JWTClaimsSet claims) {
        try {
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT)
                    .keyID(KEY.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(KEY));
            return jwt.serialize();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static RSAKey generate() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-key").generate();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
