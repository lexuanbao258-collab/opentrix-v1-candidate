<!--
SPDX-FileCopyrightText: 2026 TRICORE contributors
SPDX-License-Identifier: Apache-2.0
-->

# ADR-C001 — Candidate Platform baseline: product boundary, workflow, hybrid data, public contract

**Status:** PROPOSED (candidate repository `lexuanbao258-collab/opentrix-v1-candidate` only; not an upstream decision)

**Decision date:** 2026-11-26 · **Baseline:** `main@e7cd26a` · **Branch:** `feat/m0-m1-platform-records`

**Scope:** applies to this candidate. Upstream ADRs stay unchanged and keep their history; this ADR records which of them the candidate no longer follows (section 6).

## 1. Context

Phase 1 audit (2026-11-26) found that the repository is a hardened IT Helpdesk demo, not a reusable foundation. Helpdesk rules are hard-coded in `infrastructure/workflow/HelpdeskService.java`. There is no Application Registry, no generic data API, no audit API, no OpenAPI. The Workflow Flyway history is pinned by checksum to V1–V4 (`WorkflowMigrations`, `workflow-event-trigger-manifest.json`). The project owner approved seven product decisions: developer-first, API-first, self-hosted Enterprise Application Foundation; ESM is an independent proof application.

## 2. Decision — product boundary

1. **Platform** (this backend) owns identity verification, authorization, the Application Registry, generic records, workflow and audit. It exposes only versioned REST (`/api/v1/...`) described by OpenAPI.
2. **ESM applications** (IT Helpdesk, HR Leave, ...) are *data*: one manifest per app in `apps/<id>/app.json`, plus UI that calls the Platform API. Applications never access the Platform database directly.
3. Platform code (`com.tricore.dxos.platform..`) must not depend on Helpdesk, legacy workflow adapters or HTTP gateways, and existing modules must not depend on the Platform. `PlatformDependencyRulesTest` (ArchUnit) enforces this.
4. The legacy Helpdesk (`/api/v1/mvp/requests`, its disposable-loopback guard and its schema) stays as is. It is not exposed through the Platform and its guards are not relaxed.

## 3. Decision — workflow engine

Keep the existing Workflow domain (`core/workflow`: immutable definitions, instances and optimistic `runtimeVersion`). **Flowable is deferred.** The legacy `WorkflowController` stays unregistered. Platform records with a workflow store the state on the record row and use its version as the workflow version. The service restores a domain instance for the transition, applies the manifest's role and ownership rules, and writes the new state and audit event in one PostgreSQL transaction. The service checks the supplied version under a row lock. A commit error returns `503 COMMIT_OUTCOME_UNKNOWN` for reconciliation.

## 4. Decision — data engine: hybrid relational + JSONB

Schema `opentrix_platform` (own Flyway history table `platform_schema_history`, location `classpath:db/platform`). It does not touch the pinned Workflow history.

| Table | Purpose | Runtime privilege |
| --- | --- | --- |
| `applications` | registered manifest (JSONB) + SHA-256, PK `app_key` | SELECT |
| `entity_definitions` | record schema, search fields, permissions; PK `(app_key, entity_key)` | SELECT |
| `records` | typed columns (`record_id`, `tenant_id`, `app_key`, `entity_key`, `version`, `created_by/at`, `updated_by/at`) + `data JSONB` (object, ≤ 64 KiB); FK to `entity_definitions` | SELECT, INSERT, UPDATE, DELETE |
| `audit_events` | append-only `CREATE/UPDATE/DELETE` with actor, version, before/after JSONB | SELECT, INSERT |

- Record data is validated server-side against the entity's JSON Schema (networknt `json-schema-validator` 2.0.8, Draft 2020-12, format assertions on, remote fetching off).
- Search: `data ->> field ILIKE pattern` with the field name and pattern as bind parameters; only manifest-declared string fields are searchable.
- No per-application DDL is generated; the runtime never needs DDL.

## 5. Decision — manifests, roles and the public integration contract

**Manifest format `candidate-v1`** (`backend/src/main/resources/platform/app-manifest.schema.json`). The frame (`id`, `version` SemVer, `license` SPDX pattern, localized `name`/`description`, `roles`) is adapted from upstream commit `1ec99ad` (ADR-019, author Phan Hai Yen, Apache-2.0). The candidate deliberately differs:

| Upstream ADR-019 | Candidate | Reason |
| --- | --- | --- |
| `entities.<name>` = any JSON Schema 2020-12 (`$ref` to the meta-schema) | closed object of scalar fields; no `$ref`, `$id`, composition | no remote resolution, bounded validation cost, predictable storage |
| no permission model | `permissions.{read,create,update,delete}` = role → `all` / `own` | server-side resource authorization from data |
| `workflow` → BPMN file | optional state machine on an entity | Initial state, states, transitions and role scopes are declared in JSON. States with no outgoing transitions are terminal. |
| validator 3.0.9 (Jackson 3, test scope) | 2.0.8 (Jackson 2, runtime) | Spring Boot 3.5 runs on Jackson 2 |

Duplicate JSON keys are rejected (strict parser). Nothing in a manifest is executed or concatenated into SQL.

**Registration** is a controlled deployment step: `PlatformMigration` (Gradle `platformMigrate`, compose service `platform-migrate`) runs with the schema owner's credentials. It refuses databases with event triggers, migrates, then registers `apps/*/app.json` in one transaction. Identical manifests are a no-op; a changed manifest for an already registered id is rejected (immutable). There is no public manifest CRUD and no hot reload.

**Runtime guard** (`PlatformRuntimeGuard`, startup only, fail-closed): refuses to start when the runtime login is superuser/createrole/createdb/bypassrls, holds CREATE on the database or the Platform schema, or owns the schema; when the schema is not at the expected migration; when any DDL event trigger exists; when a Platform table has a non-internal trigger; or when the login's privileges on Platform tables differ from the exact expected matrix (no extra, no missing privilege). **This is not equivalent to the legacy per-transaction catalog guard**, which stays active for the legacy Workflow schema only. Limit: the Platform check runs once at startup, so an event trigger, table trigger or grant added later by a privileged role is not detected until the next start. Migrations refuse databases with event triggers, so the Platform is not designed for databases that require them. A periodic or per-transaction re-check is an open item.

**Manifest regular expressions** (`pattern`) are restricted to a finite subset (`SafePatterns`): literals, character classes, `.`, `\d \w \s`, and bounded quantifiers. Up to three variable-width atoms may provide at most 256 width combinations per starting position. The validator checks a patterned field's declared `maxLength` (at most 1,000 characters) before running its regex.

**Runtime privileges on the legacy schema:** the Platform migration grants only on `opentrix_platform`. `PlatformDatabaseSecurityTest.platformMigrationDoesNotWidenRuntimePrivilegesOnLegacySchemas` snapshots ACLs of every other schema before and after migration and asserts no change.

**Roles:** Keycloak client roles on the resource client (`tricore-api`) named `<appId>:<role>`, read from `resource_access.tricore-api.roles`. A role only counts inside its own app and only if the manifest declares it. Identity is the canonical `oidc:<b64(iss)>.<b64(sub)>` from the existing `CurrentPrincipal`.

**Authorization order:** verified caller → app exists *and* caller has a role in it, else **404** → entity exists, else 404 → operation granted, else **403** → record visible (`own` = created by caller), else 404 → schema validation (400) → write + audit in one transaction. Visibility filters are applied in SQL before `LIMIT/OFFSET`, so totals and pages never include hidden records.

**Public contract (v1):**

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/api/v1/apps` | apps where the caller holds a role |
| GET | `/api/v1/apps/{appId}` | 404 without a role |
| GET | `/api/v1/apps/{appId}/entities/{entity}` | record schema + caller's allowed operations |
| GET | `/api/v1/apps/{appId}/entities/{entity}/records?q=&limit=&offset=` | `limit` 1–100, `offset` 0–10000 |
| POST | `.../records` | body `{data}` → 201 |
| GET | `.../records/{id}` | |
| PUT | `.../records/{id}` | body `{expectedVersion, data}`; stale → 409 `VERSION_CONFLICT` |
| DELETE | `.../records/{id}?expectedVersion=` | 204; stale → 409 |
| GET | `.../records/{id}/workflow` | State, version and allowed actions for the caller |
| POST | `.../records/{id}/transitions/{transition}` | Body `{expectedVersion}`; 200 with the updated record |
| GET | `.../records/{id}/history` | Initial state and successful transitions; requires record read access |
| GET | `.../entities/{entity}/audit?recordId=&limit=&offset=` | Requires the entity-wide `audit` grant; sensitive fields are redacted |
| GET | `/v3/api-docs` | OpenAPI 3. **Off by default** (`OPENTRIX_OPENAPI_ENABLED`); when on it still requires a valid bearer token. Never routed by the ingress. The reviewed contract is committed as `docs/api/openapi-v1.json` and a test fails if it drifts from the running application. |

Platform application errors use `ApiError {status, code, message, errors}`. Validation errors are keyed by JSON location (`/code`). The browser uses explicit Portal BFF routes for its Platform record UI. External clients call the HTTPS ingress directly through the method/path allowlist with a bearer token for `tricore-api`. Audit distinguishes Keycloak client-credentials tokens by the issued `client_id` matching `azp`. Authorization uses app-scoped client roles and record visibility.

**Tenant:** one deployment organisation (`opentrix.platform.tenant-id`, default `default`). Every query is scoped by `tenant_id`; this is a boundary, not multi-tenancy.

## 6. Upstream ADRs and the candidate

| ADR | Candidate position |
| --- | --- |
| ADR-009 Flowable | Not followed (Flowable deferred). Modular-monolith part still followed. |
| ADR-017 Helpdesk-only scope | Not followed for the Platform: HR Leave and third-party clients are in scope. Legacy Helpdesk code unchanged. |
| ADR-018 APISIX | Optional overlay, not required by the Platform. |
| ADR-016 S3 storage | Deferred. |
| ADR-019 (upstream branch, unmerged) | Frame reused with attribution; entity/workflow parts replaced (section 5). |

## 7. Limits

- Registered manifests cannot evolve yet (immutable); schema evolution with existing records needs a later decision.
- Search is substring `ILIKE` without a trigram index; fine for catalog sizes, not for large tables.
- `offset` paging; no cursor.
- Audit rows are append-only by privilege. Runtime has SELECT/INSERT on audit and lacks UPDATE, DELETE and TRUNCATE.
- Manifest regex patterns run in the JVM; manifests are owner-controlled, not user input.
- Not production-ready; no multi-tenancy.

## 9. Attribution

Upstream copyright and the SPDX headers of existing files are unchanged. The manifest frame is adapted from upstream commit `1ec99ad` (Phan Hai Yen, Apache-2.0). New candidate files follow the repository convention (`2026 TRICORE contributors`, Apache-2.0); the candidate-specific contribution (Platform module, migrations, BFF routes, UI probe, tests, this ADR) was authored in the candidate repository by `lexuanbao258-collab` with AI assistance (Claude) and is recorded in commit trailers and `NOTICE`.

## 8. Rollback

Every change is additive. **Functional rollback:** set `OPENTRIX_PLATFORM_ENABLED=false` (Platform beans, routes and OpenAPI disappear; the legacy Helpdesk is unaffected) and remove the `platform-migrate` service. Stored Platform data is kept. No existing migration, table or guard was modified.

**Destructive step, never automatic:** removing the Platform data (`DROP SCHEMA opentrix_platform CASCADE`) deletes all records and audit events. It requires a separate, explicit approval and a verified backup; no script or test in this repository performs it against a persistent database.
