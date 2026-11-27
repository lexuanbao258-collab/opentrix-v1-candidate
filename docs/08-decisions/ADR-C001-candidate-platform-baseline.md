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

Keep the existing Workflow domain (`core/workflow`), including its immutable definitions, instances and optimistic `runtimeVersion`. **Flowable is deferred.** The legacy `WorkflowController` stays unregistered. Platform records with a workflow store the state on the record row and use its version as the workflow version. The service restores a domain instance, checks the supplied version under a row lock, and writes the new state and audit event in one PostgreSQL transaction.

**Separation of duties.** A manifest transition can declare `notByCreator: true`. The creator is denied even when their roles grant the transition. Record `allowedActions` and execution apply the same role, scope and creator rule. A denied transition leaves the state, version and audit unchanged. HR Leave `1.1.0` applies this rule to `approve` and `reject`.

**Transaction outcomes.** Platform writes own their commit boundary and reject calls made inside an external transaction. The record, its create reservation when applicable, and audit changes commit together. A failure in the transaction body triggers rollback. Failure to start a transaction returns `503 STORE_UNAVAILABLE`. A failure after the write body returns is conservatively reported as `503 COMMIT_OUTCOME_UNKNOWN`, including a failed commit whose outcome could have been classified more narrowly with database-specific evidence. The server never retries an unknown write.

## 4. Decision — data engine: hybrid relational + JSONB

Schema `opentrix_platform` (own Flyway history table `platform_schema_history`, location `classpath:db/platform`). It does not touch the pinned Workflow history.

| Table | Purpose | Runtime privilege |
| --- | --- | --- |
| `applications` | active manifest (JSONB) + SHA-256, PK `app_key` | SELECT |
| `application_versions` | immutable manifest history by `(app_key, app_version)` | SELECT |
| `entity_definitions` | record schema, search fields, permissions; PK `(app_key, entity_key)` | SELECT |
| `records` | typed columns (`record_id`, `tenant_id`, `app_key`, `entity_key`, `version`, `created_by/at`, `updated_by/at`) + `data JSONB` (object, ≤ 64 KiB); FK to `entity_definitions` | SELECT, INSERT, UPDATE, DELETE |
| `audit_events` | append-only `CREATE/UPDATE/DELETE/TRANSITION` with actor, version, and operation details | SELECT, INSERT |
| `create_requests` | reserved UUID, tenant/app/entity/caller scope and original payload fingerprint when available | SELECT, INSERT |
| `platform_schema_history` | Flyway schema migration history | SELECT |

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

**Registration** is a controlled deployment step. Stop the Platform runtime before invoking `PlatformMigration` (Gradle `platformMigrate` or the Compose `platform-migrate` service) with the schema owner's credentials. After the schema migration, the registrar validates and activates all supplied manifests in one transaction. Each active version change writes its history and updates entity definitions in that transaction. An identical active version and hash is a no-op. Reusing a version with a different hash, using an older version, or changing only SemVer build metadata is rejected. Restart the runtime after successful registration to load the active versions. The API supports discovery, and the process caches manifests for its lifetime.

**Compatibility.** A higher SemVer may add entities, optional fields and roles. Existing entities, roles, field definitions and required fields must be retained. Existing operation and transition grants may narrow. A new grant or broader scope on an existing entity is rejected. An existing workflow keeps its state topology, can tighten editability, and can add `notByCreator` restrictions. A previously workflow-free entity must be empty before it can gain a workflow. The active workflow always supplies an initial state for new records. V3 backfills manifest history from the active registry and reserves historical record IDs while preserving existing records and audit rows. V1 and V2 stay pinned.

**Runtime guard.** Startup requires a dedicated PostgreSQL login with no role memberships. This covers `SET ROLE` access through membership even with `NOINHERIT`. The guard rejects administrative role attributes, Platform ownership or DDL privileges, table or column grant options, unexpected column privileges and deviations from the table matrix. It checks the schema version, Platform sequence grants, non-internal Platform triggers and database DDL event triggers. Privileges and triggers introduced after startup are checked at the next startup. The legacy Workflow schema keeps its existing per-transaction checks.

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
| POST | `.../records` | body `{data, id?}` → 201 + Location. Retain client UUID and original data for reconciliation. |
| GET | `.../records/{id}` | |
| PUT | `.../records/{id}` | body `{expectedVersion, data}`; stale → 409 `VERSION_CONFLICT` |
| DELETE | `.../records/{id}?expectedVersion=` | 204; stale → 409 |
| GET | `.../records/{id}/workflow` | State, version and allowed actions for the caller |
| POST | `.../records/{id}/transitions/{transition}` | Body `{expectedVersion}`; 200 with the updated record |
| GET | `.../records/{id}/history` | Initial state and successful transitions; requires record read access |
| GET | `.../entities/{entity}/audit?recordId=&limit=&offset=` | Requires the entity-wide `audit` grant; sensitive fields are redacted |
| GET | `/v3/api-docs` | OpenAPI 3. **Off by default** (`OPENTRIX_OPENAPI_ENABLED`); when on it still requires a valid bearer token. Never routed by the ingress. The reviewed contract is committed as `docs/api/openapi-v1.json` and a test fails if it drifts from the running application. |

Platform application errors use `ApiError {status, code, message, errors}`. Validation errors are keyed by JSON location (`/code`). The browser uses explicit Portal BFF routes for its Platform record UI. External clients call the HTTPS ingress directly through the method/path allowlist with a bearer token for `tricore-api`. Audit distinguishes Keycloak client-credentials tokens by the issued `client_id` matching `azp`. Machine ownership belongs to the service account identified by issuer and subject. Actions by people using the same service account share that ownership. End-user delegation is outside the current identity model.

**Create reconciliation.** The client may supply a UUID. A reservation binds it to the tenant, app, entity and verified caller, including actor kind. The database stores a SHA-256 fingerprint of the original JSON object with normalized key order and number scale. The metadata contains no original payload values or credentials. Retain the same UUID and original data when reading by ID or explicitly replaying after a lost response. The CLI emits the UUID before sending a create and accepts `--id` for an explicit replay. A matching replay checks current create and read permission and returns the current record with `201` and the same `Location`. Changed original data produces `409 CREATE_ID_CONFLICT`. Out-of-scope replays and matching replays of deleted records return `404`. Delete keeps the reservation, and an update leaves the original fingerprint intact. Clients that omit `id` still receive a server-generated UUID. If that response is lost, reliable reconciliation is unavailable. Historical IDs are reserved without fingerprints because the original payload cannot be reconstructed reliably.

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

- Manifest evolution is additive and permits policy tightening. Workflow topology changes and record backfills are deferred.
- Create reservations are retained after delete. The MVP has no reservation expiry or purge protocol.
- ACK loss after a successful commit is tested by injecting a failure after PostgreSQL commits. Transport failures are exercised separately with a dropped test HTTP connection.
- A bare HTTP 500 after a write does not establish whether it committed. Read the authorized record and history first. Only create supports explicit replay with the original UUID and payload. Treat `503 STORE_UNAVAILABLE` as the documented failure to start the transaction.
- Use canonical API paths. The ingress and ESM BFF route filters are separate from Platform authorization, which validates the token and enforces application, operation and record scope.
- Gate Backend startup and operational restarts on Keycloak readiness and realm discovery. Controlled manifest registration runs while Backend is stopped. Start Backend after successful registration, without restarting the completed migrator.
- M2 introduced generic Platform changes. Evidence for a second application with zero Platform changes starts from the frozen M2.5 baseline.
- Search is substring `ILIKE` without a trigram index; fine for catalog sizes, not for large tables.
- `offset` paging; no cursor.
- Audit rows are append-only by privilege. Runtime has SELECT/INSERT on audit and lacks UPDATE, DELETE and TRUNCATE.
- Manifest regex patterns run in the JVM; manifests are owner-controlled, not user input.
- Not production-ready; no multi-tenancy.

## 8. Rollback

**Functional rollback:** set `OPENTRIX_PLATFORM_ENABLED=false` and remove the `platform-migrate` service. This preserves Platform data. Deploy a runtime that expects V3 before re-enabling Platform. An older runtime expects the prior schema version.

**Destructive step, never automatic:** removing the Platform data (`DROP SCHEMA opentrix_platform CASCADE`) deletes all records and audit events. It requires a separate, explicit approval and a verified backup; no script or test in this repository performs it against a persistent database.

## 9. Attribution

Upstream copyright and existing SPDX headers are preserved. The manifest frame is adapted from upstream commit `1ec99ad` (Phan Hai Yen, Apache-2.0). The Platform module, migrations, BFF routes, UI probe, tests and this ADR were authored in the independent candidate by `lexuanbao258-collab` with AI assistance, as recorded in commit trailers and [NOTICE](../../NOTICE). M2.5 is the runtime baseline used by the separate M3/M4 ESM and M5 Procurement experiments. Codex assisted with M5 and the M6.1-FIX deployment and documentation work. Candidate milestones do not confer upstream approval.
