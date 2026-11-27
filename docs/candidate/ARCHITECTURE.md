<!--
SPDX-FileCopyrightText: 2026 TRICORE contributors
SPDX-License-Identifier: Apache-2.0
-->

# Architecture of the candidate Platform

Diagrams are Mermaid (rendered by GitHub; the source is plain text and reviewable). They show **only components that
exist** in this repository or in the sibling application repositories. Colour key used throughout:

- **green** = new candidate Platform architecture (package `com.tricore.dxos.platform`, schema `opentrix_platform`, API `/api/v1/apps`)
- **blue** = independent applications built on the public contract (ESM, Procurement)
- **grey, dashed** = legacy upstream components that still exist in the repository but are **not** part of the Platform API
- **white** = third-party infrastructure

The legacy modular monolith, its Helpdesk and the Portal are described by the upstream documents
([../ARCHITECTURE.md](../ARCHITECTURE.md)). The candidate decisions are in
[ADR-C001](../08-decisions/ADR-C001-candidate-platform-baseline.md).

## 1. System context

```mermaid
flowchart LR
  user([Person with a browser]):::actor
  svc([Machine client<br/>script, CLI, service]):::actor
  kc[(Keycloak<br/>OIDC provider<br/>realm tricore)]:::infra
  esm[ESM application<br/>IT Helpdesk + HR Leave<br/>React SPA + Node BFF]:::app
  proc[Procurement CLI<br/>Python stdlib]:::app
  plat[OpenTrix Platform<br/>Spring Boot backend<br/>REST /api/v1/apps]:::new
  pg[(PostgreSQL 17<br/>schema opentrix_platform)]:::infra
  legacy[Legacy Helpdesk Portal<br/>+ Portal BFF + optional APISIX]:::legacy

  user -->|HTTPS| esm
  esm -->|bearer token of the signed-in person| plat
  svc --> proc
  proc -->|bearer token, client_credentials| plat
  esm -. sign-in redirect .-> kc
  proc -->|token request| kc
  plat -->|JWKS and discovery| kc
  plat -->|JDBC runtime login| pg
  legacy -. uses its own schema, not exposed through the Platform .-> pg

  classDef new fill:#dff5e1,stroke:#2e7d32,color:#000
  classDef app fill:#e3f0ff,stroke:#1565c0,color:#000
  classDef infra fill:#fff,stroke:#555,color:#000
  classDef legacy fill:#eee,stroke:#888,stroke-dasharray:4 3,color:#444
  classDef actor fill:#fff8e1,stroke:#b28900,color:#000
```

Applications reach the Platform **only** through HTTPS and the OpenAPI v1 contract. Neither ESM nor Procurement has a
database connection to the Platform. The browser never holds an access token (ESM BFF keeps it server side).

## 2. Containers (demo deployment)

```mermaid
flowchart TB
  subgraph host[Host: loopback only]
    subgraph ns[One Docker network namespace shared via network_mode service:postgres]
      ingress[Caddy ingress<br/>HTTPS, internal CA<br/>method and path allowlist]:::infra
      backend[Platform backend<br/>Spring Boot 3, Java 21<br/>:8080 loopback]:::new
      kc[Keycloak 26.8.0<br/>start-dev<br/>:8180 + mgmt :9001]:::infra
      pg[(PostgreSQL 17.11<br/>:5432 loopback)]:::infra
      dbinit[[db-init<br/>one-shot: roles, schemas]]:::infra
      kcinit[[kc-init<br/>one-shot: secrets, users]]:::infra
      mig[[platform-migrate<br/>one-shot: Flyway V1-V3<br/>+ manifest registration]]:::new
      pbff[Legacy Portal BFF :8081<br/>+ legacy Portal static files]:::legacy
    end
  end
  ingress --> backend
  ingress -. legacy routes, not used by ESM or Procurement .-> pbff
  backend --> pg
  backend --> kc
  dbinit --> pg
  mig -->|schema owner login| pg
  kcinit --> kc
  classDef new fill:#dff5e1,stroke:#2e7d32,color:#000
  classDef infra fill:#fff,stroke:#555,color:#000
  classDef legacy fill:#eee,stroke:#888,stroke-dasharray:4 3,color:#444
```

- Published to the host: ingress HTTPS (and, for ESM, Keycloak `8180` for the browser redirect). Backend and PostgreSQL
  listen on loopback inside the shared namespace only.
- The ESM and Procurement stacks start `postgres`, `keycloak`, `backend`, `ingress` and the three one-shot jobs. They do
  **not** start the legacy Portal BFF. The legacy components appear in the base Compose file only.
- Start order is enforced by Compose: `db-init` and `platform-migrate` complete, then the backend starts once Keycloak is
  `service_healthy`.

## 3. Platform module boundaries

```mermaid
flowchart LR
  subgraph P[com.tricore.dxos.platform]
    api[api<br/>PlatformController<br/>PlatformExceptionHandler]:::new
    rec[records<br/>RecordService<br/>RecordRepository<br/>PlatformTransactions]:::new
    man[manifest<br/>AppManifest<br/>ManifestValidator<br/>SafePatterns]:::new
    reg[registry<br/>PlatformRegistry<br/>ManifestRegistrar<br/>PlatformMigration<br/>PlatformRuntimeGuard]:::new
    sec[security<br/>PlatformCaller]:::new
  end
  core[core.workflow<br/>domain: definition, instance,<br/>transition, optimistic version]:::shared
  ident[application.identity<br/>CurrentPrincipal<br/>implemented by infrastructure.identity.security]:::shared
  err[common.error ApiError]:::shared
  legacyH[gateway.helpdesk,<br/>infrastructure.workflow<br/>HelpdeskService,<br/>legacy workflow adapters]:::legacy

  api --> rec
  api --> sec
  api --> reg
  rec --> man
  rec --> core
  reg --> man
  sec --> ident
  api --> err
  legacyH -. no dependency in either direction<br/>enforced by PlatformDependencyRulesTest .- P

  classDef new fill:#dff5e1,stroke:#2e7d32,color:#000
  classDef shared fill:#fff,stroke:#555,color:#000
  classDef legacy fill:#eee,stroke:#888,stroke-dasharray:4 3,color:#444
```

`core.workflow` is the pre-existing domain model (immutable definitions, instances, optimistic `runtimeVersion`) reused
as a library. The Platform does **not** use the legacy workflow controller (it stays unregistered) nor the legacy
workflow tables. The Platform keeps a record's workflow state on the record row.

## 4. ESM authentication flow

```mermaid
sequenceDiagram
  autonumber
  participant B as Browser (React SPA)
  participant F as ESM BFF (Node)
  participant K as Keycloak
  participant G as Platform ingress (Caddy)
  participant P as Platform backend
  B->>F: GET /bff/login
  F-->>B: 302 to Keycloak (code + PKCE S256 + state + nonce)
  B->>K: authenticate (demo user)
  K-->>B: 302 to /bff/callback?code=…
  B->>F: GET /bff/callback?code=…
  F->>K: exchange code + code_verifier (confidential client esm-bff)
  K-->>F: access token + refresh token
  F-->>B: Set-Cookie: opaque session id (HttpOnly, SameSite=Lax), tokens stay in the BFF session
  B->>F: GET /bff/api/v1/apps/… (cookie, writes add CSRF token and Origin check)
  F->>F: route/method/query allowlist, 64 KiB body limit
  F->>G: same path under /api/v1/apps with Authorization: Bearer (server-side token)
  G->>G: method + path allowlist
  G->>P: forward (cookies stripped)
  P->>P: verify signature, issuer, audience, expiry, read roles, authorize app, entity, operation, record
  P-->>F: JSON
  F-->>B: JSON (no token ever reaches the browser)
```

The BFF is an optimisation and a safety layer, **not** the authority: the Platform authorizes every call from the
verified token. Sessions are in memory (single instance, lost on restart).

## 5. Record and workflow write transaction

```mermaid
sequenceDiagram
  autonumber
  participant C as Client
  participant A as PlatformController
  participant S as RecordService
  participant T as PlatformTransactions
  participant D as PostgreSQL
  C->>A: POST …/transitions/approve {expectedVersion: 1}
  A->>S: verified caller (PlatformCaller), path, body
  S->>S: app+role (404) → entity (404) → operation/grant (403) → record visibility (404) → notByCreator (403)
  S->>T: write(work)
  T->>D: BEGIN (READ COMMITTED)
  T->>D: SELECT record … FOR UPDATE
  T->>S: run
  S->>S: compare version (409), state allows transition (409), restore WorkflowInstance and apply TransitionCommand
  S->>D: UPDATE records SET workflow_state, version = version + 1
  S->>D: INSERT audit_events (TRANSITION, actor, actor_type, from_state, to_state)
  T->>D: COMMIT
  alt body failed before returning
    T-->>A: rollback, original error (400/403/404/409 or 503 STORE_UNAVAILABLE)
  else body returned but COMMIT outcome uncertain
    T-->>A: 503 COMMIT_OUTCOME_UNKNOWN (never retried by the server)
  else committed
    T-->>A: updated record
    A-->>C: 200
  end
```

For **create**, the same transaction also inserts the `create_requests` reservation (UUID, tenant, app, entity, actor and
payload fingerprint). Platform writes refuse to run inside an externally managed transaction.

## 6. Manifest registration and upgrade flow

```mermaid
flowchart TD
  start([Operator has a new or changed app.json]) --> stop[Stop the Platform backend<br/>so no old ACL cache serves requests]
  stop --> run[Run PlatformMigration with the schema owner login<br/>compose: platform-migrate / gradle: platformMigrate]
  run --> fl[Flyway migrate V1 to V3<br/>history table platform_schema_history]
  fl --> val{Every manifest in the directory<br/>valid? strict JSON, schema, semantics}
  val -- no --> fail1[Exit non-zero; backend stays stopped;<br/>nothing registered]
  val -- yes --> tx[One database transaction]
  tx --> cmp{New version vs active version}
  cmp -- identical version and hash --> noop[No-op]
  cmp -- same version, other hash<br/>or older, or build-metadata only --> fail2[Rejected]
  cmp -- higher SemVer --> compat{Compatible?<br/>no removals, no broader grants,<br/>no changed fields or topology}
  compat -- no --> fail3[Rejected, active version unchanged]
  compat -- yes --> write[INSERT application_versions<br/>UPDATE applications and entity_definitions]
  write --> commit[Commit]
  noop --> commit
  commit --> kc{Keycloak healthy?}
  kc -- no --> hold[Backend stays stopped]
  kc -- yes --> start2[Start the backend only<br/>RuntimeGuard checks DB privileges at startup;<br/>manifests are cached for the process lifetime]
  classDef bad fill:#fdecea,stroke:#c62828,color:#000
  class fail1,fail2,fail3,hold bad
```

## 7. Database model (migrations V1, V2, V3)

Schema `opentrix_platform`. The runtime login has only the privileges shown in the last column; DDL belongs to the
schema owner role used by `platform-migrate`.

```mermaid
erDiagram
  applications ||--o{ entity_definitions : "app_key"
  applications ||--o{ application_versions : "app_key"
  entity_definitions ||--o{ records : "(app_key, entity_key)"
  records ||..o{ audit_events : "record_id (no FK)"
  records ||..o| create_requests : "record_id (no FK)"

  applications {
    text app_key PK
    text app_version
    jsonb manifest
    text manifest_sha256
    timestamptz registered_at
  }
  application_versions {
    text app_key PK
    text app_version PK
    jsonb manifest
    text manifest_sha256
    timestamptz registered_at
  }
  entity_definitions {
    text app_key PK
    text entity_key PK
    jsonb record_schema
    text_array search_fields
    jsonb permissions
  }
  records {
    uuid record_id PK
    text tenant_id
    text app_key FK
    text entity_key FK
    jsonb data
    bigint version
    text workflow_state "V2, nullable"
    text created_by
    timestamptz created_at
    text updated_by
    timestamptz updated_at
  }
  audit_events {
    bigint event_id PK
    timestamptz occurred_at
    text tenant_id
    text app_key
    text entity_key
    uuid record_id
    text action "CREATE UPDATE DELETE TRANSITION"
    text actor_id
    text actor_type "V2: USER or MACHINE"
    bigint record_version
    jsonb before_data
    jsonb after_data
    text transition_id "V2"
    text from_state "V2"
    text to_state "V2"
  }
  create_requests {
    uuid record_id PK
    text tenant_id
    text app_key
    text entity_key
    text actor_id
    text actor_type
    bytea payload_sha256 "V3, nullable"
  }
```

| Table | Created in | Runtime privilege |
| --- | --- | --- |
| `applications`, `entity_definitions`, `platform_schema_history` | V1 | SELECT |
| `application_versions` | V3 (backfilled from `applications`) | SELECT |
| `records` | V1 (`workflow_state` added in V2) | SELECT, INSERT, UPDATE, DELETE |
| `audit_events` | V1 (extended in V2) | SELECT, INSERT |
| `create_requests` | V3 (backfilled from audit and records, without fingerprints) | SELECT, INSERT |

Constraints worth knowing: `records.data` must be a JSON object of at most 65,536 bytes; `audit_events` has CHECK
constraints that tie each `action` to the shape of its before/after/transition columns; the foreign key
`records(app_key, entity_key) → entity_definitions` blocks removal of an entity that still has records.
The legacy Helpdesk and Workflow schema (`helpdesk_demo`, Flyway history pinned to V1 to V4) is separate and untouched.

## 8. Deployment topology

```mermaid
flowchart LR
  subgraph laptop[Developer machine, Docker Desktop, Linux containers]
    direction TB
    subgraph esmstack[Compose project opentrix-esm - ESM]
      e1[postgres + keycloak + backend + ingress]:::new
    end
    subgraph procstack[Compose project opentrix-procurement-... - Procurement]
      p1[postgres + keycloak + backend + ingress]:::new
    end
    bff[ESM BFF + built SPA<br/>Node process on the host, :5174]:::app
    cli[procurement.py<br/>Python on the host]:::app
  end
  browser([Browser]) --> bff
  bff -->|HTTPS :8443, trusts local CA| e1
  browser -. OIDC redirect :8180 .-> e1
  cli -->|HTTPS :18444, trusts local CA| p1
  classDef new fill:#dff5e1,stroke:#2e7d32,color:#000
  classDef app fill:#e3f0ff,stroke:#1565c0,color:#000
```

- Each stack is its own Compose project with its own PostgreSQL volume, Keycloak container and CA. Two stacks run side
  by side only when `ESM_*` or `M5_*` ports and project names differ (verified in M6.1-FIX).
- Nothing listens on a non-loopback interface. There is no cloud, Kubernetes, load balancer or replicated database in
  this candidate and none is drawn here.
- ESM and Procurement run on the host, outside the containers; only the Platform stack is containerised.
