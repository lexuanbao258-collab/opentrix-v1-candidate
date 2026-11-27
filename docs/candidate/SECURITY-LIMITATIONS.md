<!--
SPDX-FileCopyrightText: 2026 TRICORE contributors
SPDX-License-Identifier: Apache-2.0
-->

# Threat model and operational limitations

> **Status.** OpenTrix is a **competition-ready, self-hosted demonstration candidate. It is not a production-certified
> system.** It has not had an independent security audit, penetration test or load test. Use synthetic data only. This
> page lists what the design protects, what the evidence covers and what it does not.

Evidence for the "verified" statements is in [VALIDATION.md](VALIDATION.md). Reporting a vulnerability: see the root
[SECURITY.md](../../SECURITY.md) (candidate section at the top).

## 1. Assets, actors and trust boundaries

| Asset | Where it lives |
| --- | --- |
| Business records and their versions | `opentrix_platform.records` |
| Audit trail | `opentrix_platform.audit_events` (append-only by privilege) |
| Manifests (the authorization policy) | `applications`, `application_versions`, `entity_definitions` |
| Tokens and client secrets | Keycloak; ESM BFF session memory; `.local/` files of a stack |
| Local CA key | Caddy volume of each stack |

| Actor | Assumed to be |
| --- | --- |
| Authenticated person or machine | Untrusted for authorization: may attempt other apps, other users' records, forged state |
| Application developer / operator | Trusted: owns manifests, runs registration, holds the schema-owner credential |
| Browser | Untrusted; holds no access token |
| Keycloak | Trusted identity authority (demo-mode, see section 3) |
| Anything on the host able to read `.local/` or Docker | Fully trusted; the demo is single-user, single-host |

Trust boundaries: browser to BFF (session + CSRF), BFF or CLI to ingress (TLS + allowlist), ingress to backend (JWT
validation), backend to PostgreSQL (least-privilege login).

## 2. What is protected, and how

| Concern | Control | Verified by |
| --- | --- | --- |
| **App and resource authorization** | Roles come from `resource_access.tricore-api.roles` and count only inside their own app and only if declared in the manifest. Order: app-and-role (404), entity (404), operation (403), record visibility (404), validation (400), write. `own` is enforced in SQL before paging. Hidden records are `404`, not `403`. | M3 to M5 negative suites: cross-app 404 both ways, cross-user isolation, grants matrix |
| **Separation of duties** | `notByCreator` on a transition denies the creator even if their role grants it. `allowedActions` and execution use the same rule. A denial leaves state, version and audit unchanged. | HR: employee-and-manager self-approval `403` (M4); Procurement `m5-dual` `403` (M5, M6.3 smoke) |
| **Runtime database privileges** | The backend logs in as a role with no role memberships and no DDL; table grants per the ADR matrix (records SELECT/INSERT/UPDATE/DELETE, audit SELECT/INSERT, others SELECT). Migration never widens privileges on legacy schemas. | `PlatformDatabaseSecurityTest`; M6.1 runtime privilege matrix |
| **Tamper resistance of audit** | Runtime has no UPDATE, DELETE or TRUNCATE on `audit_events`; CHECK constraints fix row shapes. | Privilege matrix; schema |
| **Atomic writes** | Record, workflow state, reservation and audit commit together or not at all. | Platform tests; ESM and Procurement live suites |
| **Lost updates** | `expectedVersion` under a row lock; concurrent writers: exactly one wins. | M3/M4 E2E concurrent-transition test |
| **Sensitive fields in audit** | `sensitive: true` fields appear as `[REDACTED]` in audit `before`/`after`. Error bodies and container logs did not echo them in the checks run. | M4 `hr-security`; M6.1 log scan |
| **Session and CSRF (ESM)** | Authorization Code + PKCE S256 + state + nonce; confidential client; opaque `HttpOnly; SameSite=Lax` cookie; CSRF token and `Origin` check on every write; per-app route/method/query allowlist; 64 KiB body limit; CSP `default-src 'self'`; idle 30 min and absolute 8 h session limits. | M3/M4 `security` and `hr-security` specs |
| **Transport** | TLS from a local CA; clients verify the CA and the hostname. Ingress allowlists exactly the contract's method/path pairs and strips cookies and forwarded headers. | M5 TLS test; M6.1 ingress review |

## 3. Known limitations and non-guarantees

| # | Limitation | Consequence | If you needed more |
| --- | --- | --- | --- |
| 1 | **`PlatformRuntimeGuard` runs at startup only.** It checks the privilege matrix, grants, triggers and event triggers once when the backend starts. | A privilege or trigger added to the database afterwards is not noticed until the next startup. | Restrict who can alter the database; add a periodic external check. |
| 2 | **Machine account ownership.** A service account is one identity (issuer + subject). `own` scope and audit attribute everything done with that account to it, no matter which person used the secret. End-user delegation is outside the identity model. | Two operators sharing a client secret share ownership and are indistinguishable in audit. | One client per automation, protect its secret, or add a delegation layer. |
| 3 | **Sensitive HR data scope.** In HR Leave, managers and auditors have `read: all`, so they see every employee's leave `reason`. Only the **audit trail** redacts it. | `sensitive` is an audit-redaction flag, not field-level access control. | Remove `read: all` from roles that must not see it, or split data into another entity. |
| 4 | **Demo-mode Keycloak.** `start-dev`, state inside the container, local CA, generated demo users. Re-creating the container changes the realm state and subjects; ownership of existing data can then point at identities that no longer exist. | Not a hardened identity service. | Production Keycloak or another OIDC provider with persistent configuration. |
| 5 | **ESM sessions are in memory.** One BFF instance; sessions are lost on restart; no shared store. | Restart logs everyone out; cannot scale out. | Shared session store. |
| 6 | **No rate limiting** at the ingress, BFF or backend. | Brute force or request floods are not throttled. | Rate limits and monitoring in front of the BFF and ingress. (The upstream APISIX overlay offers limiting for the legacy demo; it is not part of this Platform path.) |
| 7 | **Client-side filtering cap.** The API has no state or owner filter or aggregate. ESM dashboards and filtered lists scan at most **500** authorized records and say so when truncated. | Numbers can be lower than reality for large data sets. | A server-side filter in the Platform (a feature, out of scope while the freeze holds). |
| 8 | **No referential integrity between entities.** ESM tickets store a category *code*; deleting a category leaves tickets that carry its code. The Platform FK only protects `records` against removal of their `entity_definition`. | Dangling references are possible. | Application-level checks or a Platform feature. |
| 9 | **UUID reservations are retained** after delete, have no expiry and no purge protocol. | A deleted record's UUID can never be reused with different data; the table grows. Reservations for pre-V3 records have no payload fingerprint. | A reservation expiry protocol. |
| 10 | **Unknown-outcome path is simulated.** Tests inject a failure after PostgreSQL commits (an exception plus a deferred trigger) and drop the client connection after a successful write. They do **not** reproduce a real lost commit acknowledgement from the database. Any failure after the write body returns is conservatively reported as `COMMIT_OUTCOME_UNKNOWN`. | The reconciliation procedure is exercised, but not under a real network or database fault. | Fault-injection testing on real infrastructure. |
| 11 | **Layered path filters.** The ingress and the ESM BFF filter paths separately from the Platform's own authorization. | Use canonical API paths. No bypass was found, but normalization behaviour of every layer was not exhaustively proven on a live stack. The Platform validates the token and enforces app, operation and record scope regardless. | |
| 12 | **Single organisation.** `tenant_id` scopes every query but this is a boundary, not multi-tenancy. | One deployment, one organisation. | |
| 13 | **Additive manifest evolution only.** Topology changes of an existing workflow and record back-fills are rejected or deferred. | You cannot reshape a live workflow. | A migration feature. |
| 14 | **Search** is a substring `ILIKE` without an index; paging is by offset (max 10,000). | Slow on large tables. | Index and cursor paging. |
| 15 | **Manifest regular expressions** run in the JVM, restricted to a finite safe subset. Manifests are owner-controlled, not user input. | A hostile manifest author is out of scope. | |
| 16 | **Provenance and legal.** Third-party licences of container images were not assessed for redistribution; the release payload is intended to be source-only. AI assistance is disclosed in `NOTICE`. | Do not redistribute built images without a licence review. | |

## 4. Not done in this candidate

Independent audit or penetration test; load and soak testing; backups and a tested restore procedure; high
availability; production Keycloak; secrets management beyond random local files; monitoring and alerting;
multi-tenancy; data retention and privacy compliance work (GDPR or similar); accessibility review with assistive
technology (automated axe checks only); browsers other than Chromium; physical mobile devices.

## 5. Safe-use rules

1. Synthetic data only, on `localhost` or an isolated network.
2. Never expose the ingress, Keycloak or PostgreSQL beyond loopback.
3. Never commit `.local/`, `.env`, `accounts.json`, `compose.env`, `realm.json`, `esm.env` or a CA key. Run the secret
   scan (`node scripts/scan-secrets.mjs` in ESM; a secret scanner such as gitleaks on the others) before every push.
4. Back up the PostgreSQL volume before any manifest registration that you care about, and keep the previous manifest set.
5. Stop the backend before registering manifests; start it only after registration succeeds.
