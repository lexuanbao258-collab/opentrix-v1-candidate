<!--
SPDX-FileCopyrightText: 2026 TRICORE contributors
SPDX-License-Identifier: Apache-2.0
-->

# Developer guide: build an application on the public contract

This guide takes you from nothing to a working application on the OpenTrix Platform without reading any Platform Java
code. Everything is the public contract: the manifest format
([`app-manifest.schema.json`](../../backend/src/main/resources/platform/app-manifest.schema.json)), the HTTP API
([`openapi-v1.json`](../api/openapi-v1.json), explained in the [API guide](API-GUIDE.md)) and the deployment steps
below. The worked example is **Procurement**, an independent Python application
(`opentrix-procurement-demo`, local checkout): its manifest, deployment overlay, Keycloak realm and CLI are exactly what
a new application needs. Statements marked *tested* were executed in M6.3 against a live stack
([VALIDATION.md](VALIDATION.md#6-m63-onboarding-smoke-test)).

## 0. The five steps

1. Write `app.json` (manifest): roles, entities, fields, permissions, optional workflow.
2. Give the Platform the manifest through the **controlled registration step** (stop backend, run migrator, start backend).
3. Create Keycloak client roles `<appId>:<role>` and the identities (people or machine clients) that hold them.
4. Call the API with a bearer token for audience `tricore-api`.
5. Handle failures as the [API guide](API-GUIDE.md#7-concurrency-idempotency-and-unknown-outcomes) prescribes.

## 1. Manifest structure

A manifest is one JSON object (format `candidate-v1`). Unknown properties are rejected, duplicate JSON keys are
rejected, and nothing in a manifest is executed or concatenated into SQL. Procurement's manifest (49 lines):

```json
{
  "id": "opentrix-procurement",
  "version": "1.0.0",
  "license": "Apache-2.0",
  "name": { "en": "Procurement", "vi": "Mua sắm nội bộ" },
  "roles": {
    "requester": { "name": { "en": "Requester" } },
    "approver":  { "name": { "en": "Approver" } },
    "auditor":   { "name": { "en": "Auditor" } }
  },
  "entities": {
    "purchaseRequest": {
      "name": { "en": "Purchase request" },
      "schema": {
        "type": "object",
        "additionalProperties": false,
        "required": ["itemName", "quantity", "estimatedUnitPriceMinor", "justification"],
        "properties": {
          "itemName": { "type": "string", "minLength": 1, "maxLength": 120 },
          "quantity": { "type": "integer", "minimum": 1, "maximum": 10000 },
          "estimatedUnitPriceMinor": { "type": "integer", "minimum": 0, "maximum": 1000000000 },
          "justification": { "type": "string", "minLength": 1, "maxLength": 500, "sensitive": true }
        }
      },
      "search": ["itemName"],
      "permissions": {
        "create": { "requester": "own" },
        "read":   { "requester": "own", "approver": "all", "auditor": "all" },
        "update": { "requester": "own" },
        "delete": { "requester": "own" },
        "audit":  { "auditor": "all" }
      },
      "workflow": {
        "initialState": "DRAFT",
        "states": {
          "DRAFT":    { "name": { "en": "Draft" } },
          "PENDING":  { "name": { "en": "Pending decision" }, "editable": false },
          "APPROVED": { "name": { "en": "Approved" }, "editable": false },
          "REJECTED": { "name": { "en": "Rejected" }, "editable": false }
        },
        "transitions": {
          "submit":  { "from": "DRAFT",   "to": "PENDING",  "roles": { "requester": "own" } },
          "approve": { "from": "PENDING", "to": "APPROVED", "roles": { "approver": "all" }, "notByCreator": true },
          "reject":  { "from": "PENDING", "to": "REJECTED", "roles": { "approver": "all" }, "notByCreator": true }
        }
      }
    }
  }
}
```

| Property | Rule |
| --- | --- |
| `id` | `^[a-z][a-z0-9-]{0,62}$`. Becomes `{appId}` in every URL and the prefix of every Keycloak role (`opentrix-procurement:approver`). Immutable. |
| `version` | Semantic version (`1.0.0`, pre-release and build metadata allowed by SemVer). Each registered version is immutable. |
| `license` | Optional SPDX-like token, `^[A-Za-z0-9][A-Za-z0-9.+-]*$`. |
| `name`, `description` | Localized text: an object keyed by language tag (`en`, `vi`, …). `en` is required; each text 1 to 500 characters; up to 16 languages. |
| `roles` | 1 to 32 roles, key `^[a-z][a-z0-9_-]{0,62}$`, each with a localized `name`. |
| `entities` | 1 to 64 entities, key `^[a-z][a-zA-Z0-9]{0,62}$` (this is `{entity}` in URLs). Each needs `name`, `schema`, `permissions`; optional `search`, `workflow`. |

## 2. Field types and validation constraints

An entity `schema` is a **closed JSON object** (a restricted subset of JSON Schema 2020-12): `type: "object"`,
`additionalProperties: false`, 1 to 64 `properties`, optional `required` (names must exist). There is no `$ref`, `$id`,
composition, nested object, array or remote resource. Property names match `^[a-z][a-zA-Z0-9_]{0,62}$`. A record's
`data` may be at most 64 KiB.

| Field `type` | Constraints you may set |
| --- | --- |
| `string` | `minLength` (0 to 10000), `maxLength` (1 to 10000), `pattern`, `format` (`date`, `date-time`, `email`), `enum` |
| `integer` | `minimum`, `maximum`, `enum` |
| `number` | `minimum`, `maximum`, `enum` |
| `boolean` | none |

Any field may also carry `title`, `description` (documentation only) and `sensitive`. `enum` has 1 to 100 unique values.
Validation runs server-side with format assertions on, and failures return `400 VALIDATION_FAILED` keyed by JSON
location (`{"/quantity":"must have a minimum value of 1"}`).

Rules that surprise people:

- **`pattern`** is limited to a finite safe subset: literals, character classes, `.`, `\d \w \s`, bounded quantifiers
  (at most three variable-width atoms). The field must also declare `maxLength` of at most 1000, which the Platform
  checks before running the regex.
- **`search`**: up to 8 names of `string` fields. `?q=` matches any of them as a case-insensitive substring. An entity
  with no `search` fields cannot be searched.
- **Money** has no currency type. Procurement stores integer minor units (`estimatedUnitPriceMinor`) and the manifest
  declares no currency, total or approval limit.
- **Referential integrity** between entities is not provided. Store a code and validate it in your application.
- The Platform logs `Unknown keyword sensitive` at registration. It is a harmless validator warning; redaction works
  (verified through the API).

## 3. Application-scoped roles and `own`/`all` permissions

A role exists only inside its application. A Keycloak role named `<appId>:<role>` counts only for that app and only if
the manifest declares `<role>`. Holding `opentrix-procurement:approver` gives nothing in `hr-leave`.

`permissions` has one block per operation: `read`, `create`, `update`, `delete` (all required, may be empty `{}`) and
optional `audit`. Each block maps a role to a scope:

| Scope | Meaning |
| --- | --- |
| `all` | Every record of the entity |
| `own` | Only records the caller created (`created_by` equals the caller's actor id) |
| `audit` accepts only `all` | Audit is entity-wide, never per owner |

A caller with no grant for an operation gets `403` (they can see the entity) and a record outside `own` scope gets `404`
(hidden). `create` with `own` simply means the role may create. Discovery tells a caller what it may do:
`allowedOperations` on the entity and `allowedActions` on each record.

## 4. Workflow states and transitions

A workflow is an optional state machine on an entity:

| Property | Rule |
| --- | --- |
| `initialState` | Name of a state; every new record starts there. It must have an outgoing transition. |
| `states` | 2 to 32 states, names `^[A-Z][A-Z0-9_]{0,31}$`. `editable: false` makes generic `PUT` and `DELETE` fail with `409 STATE_LOCKED` in that state (default `true`). A state with **no outgoing transition is terminal**. Every state must be reachable from the initial state. |
| `transitions` | 1 to 64, key `^[a-z][a-zA-Z0-9_]{0,62}$` (the URL segment). `from` and `to` must be different declared states. `roles` is a non-empty map of declared role to `own` or `all`. Optional `notByCreator` and localized `name`. |

The workflow state lives on the record row and is changed **only** by `POST …/transitions/{transition}` with
`{"expectedVersion": n}`. It cannot be forged through `data` (closed schema) or through `PUT`. Each transition and its
audit event commit in one database transaction. Transitions carry no payload: a rejection cannot carry a comment.

**`notByCreator: true`** denies the transition to the record's creator even if their role grants it. This is the
separation-of-duties primitive. In Procurement, a client holding both `requester` and `approver` (`m5-dual`) can create
and submit a request but gets `403 FORBIDDEN` on `approve` and `reject`, an empty `allowedActions`, and an unchanged
state, version and audit trail (tested in M6.3). Another approver must decide. Because identity is the actor id, two
people sharing one machine client are the same creator (see
[SECURITY-LIMITATIONS.md](SECURITY-LIMITATIONS.md), item 2).

## 5. Audit and redaction

Every create, update, delete and transition appends an audit event with actor id, `actorType` (`USER` or `MACHINE`),
record version and `before`/`after` data. Mark personal or confidential fields `"sensitive": true`: their values become
`[REDACTED]` in audit (tested). **This is audit redaction only.** Anyone with `read` on the record still sees the value
(HR Leave managers and auditors read every `reason`). To restrict reading, remove the role's `read` grant.

To let a role read audit, add it to `permissions.audit` with `"all"`. The audit endpoint is per entity and can be
filtered by `recordId`.

## 6. Controlled registration and compatible upgrades

The runtime login cannot change the schema or the registry, and manifests are cached for the life of the process. So
registration is a deployment step, **not** an API call:

1. **Stop the backend** (the old process would otherwise serve stale permissions during and after the change).
2. Run `PlatformMigration` with the **schema owner** credentials: in Compose, the `platform-migrate` service with the
   manifest directory mounted at `/apps` (`PLATFORM_MANIFEST_DIR`). It applies Flyway V1 to V3, validates **every**
   manifest in the directory and activates them in one transaction.
3. If it exits 0 and Keycloak is healthy, **start the backend** only. Do not restart the finished migrator.

```bash
# Procurement stack (an isolated project); manifests are read from .local/<project>/apps/<id>/app.json
docker stop <project>-backend-1
docker compose … run --rm --no-deps platform-migrate      # ends with PLATFORM_MIGRATION_OK registered=[opentrix-procurement]
docker start <project>-backend-1
```
For ESM the same sequence is `deploy/platform-stack.sh migrate`, which also waits for Keycloak and leaves the backend
stopped if registration fails.

What the registrar does with a manifest (tested):

| Situation | Result |
| --- | --- |
| New app, valid | registered and activated |
| Same version and same hash | no-op (`registered=[]`) |
| Same version, different content; older version; only SemVer build metadata changed | rejected |
| Higher version, **compatible** | activated; history row added; existing records and audit untouched |
| Higher version, incompatible | rejected; active version unchanged; non-zero exit; backend must stay stopped until you fix or restore the manifest directory |

**Compatible** means: keep every entity, role, field definition and `required` entry; you may add entities, roles
and optional fields; permissions may **narrow** but never gain a grant or a broader scope on an existing entity; an
existing workflow keeps its states and transitions and may only tighten (`editable: false`, add `notByCreator`);
an entity without a workflow can gain one only while it has no records.

Tested in M6.3 on a stack holding approved records: Procurement `1.1.0` adds an optional field to the existing
entity. After the controlled step, discovery showed `1.1.0`, a record with the new field was created (`201`) and the
earlier record was unchanged.

```diff
- "version": "1.0.0",
+ "version": "1.1.0",
  …
          "justification": { … },
+         "costCenter": { "type": "string", "minLength": 1, "maxLength": 32 }
```

A `1.2.0` that changes `permissions.read.requester` from `own` to `all` was refused with
`Incompatible manifest opentrix-procurement/purchaseRequest: grant broadened for role requester`; the backend stayed
stopped and the dataset was untouched. Restoring the `1.1.0` manifest re-registered as a no-op and the backend started.

Before registering anything you care about, back up the PostgreSQL volume and keep the previous manifest set.

## 7. Identity and the Keycloak realm

The Platform trusts one OIDC issuer (`OIDC_ISSUER_URI`, realm `tricore` in the demo) and one resource client
(`OIDC_RESOURCE_CLIENT_ID=tricore-api`, audience `OIDC_AUDIENCE=tricore-api`).

**What an access token must contain**

| Claim | Requirement |
| --- | --- |
| `iss` | exactly the configured issuer string (the demo uses `http://localhost:8180/realms/tricore`, even though clients reach Keycloak through the HTTPS ingress) |
| `aud` | contains `tricore-api` (an audience mapper on the client) |
| `exp` | present; demo tokens live 300 s |
| `sub` | non-empty string; with `iss` it forms the actor id `oidc:base64url(iss).base64url(sub)` |
| `typ` | `Bearer` |
| `resource_access.tricore-api.roles` | role names `<appId>:<role>`. Realm roles and headers are ignored. |

Observed token of Procurement's `m5-approver` (decoded in M6.3): `aud: tricore-api`, `azp` and `client_id` both
`m5-approver`, `resource_access.tricore-api.roles: ["opentrix-procurement:approver"]`.

**Human user versus machine identity.** Both are the same kind of JWT; they differ in how the token is obtained and in
audit:

| | Human user | Machine identity |
| --- | --- | --- |
| Grant | Authorization Code + PKCE through a confidential BFF (ESM) | OAuth2 `client_credentials` |
| Keycloak object | user with client roles | client with `serviceAccountsEnabled`, plus its `service-account-<client>` user holding the client roles |
| `sub` | the user | the service account |
| `client_id` claim | absent | present and equal to `azp` (**needs the `service_account` client scope**) |
| Audit `actorType` | `USER` | `MACHINE` |
| Ownership (`own`) | the person | the service account: everyone using that secret shares one identity |

**Recipe for a machine client** (what Procurement's `deploy/stack.py` generates):

1. Realm client roles on `tricore-api`: one per `<appId>:<role>`.
2. A confidential client per automation: `serviceAccountsEnabled: true`, `standardFlowEnabled: false`,
   `directAccessGrantsEnabled: false`, `fullScopeAllowed: false`.
3. Default client scopes `basic`, `roles` **and `service_account`**.
4. An audience mapper (`oidc-audience-mapper`) with `included.client.audience: tricore-api` on the access token.
5. Scope mappings on `tricore-api` so only the intended roles appear in the token, and the service-account user holds
   those roles.
6. Without step 3 the token still works but the Platform records `actorType: USER`. This was the one integration defect
   found in M5; it is not visible in the OpenAPI file.

Request a token (secret from a private file or secret store, never from a command line you share):

```bash
curl --cacert caddy-root.crt --ssl-revoke-best-effort \
  -d grant_type=client_credentials -d client_id=m5-requester \
  --data-urlencode client_secret="$CLIENT_SECRET" \
  https://localhost:18444/realms/tricore/protocol/openid-connect/token
# -> {"access_token":"…","expires_in":300,"token_type":"Bearer",…}
```

Cache the token until shortly before `exp` and send it as `Authorization: Bearer …`. Tokens are short-lived, so do not
store them.

**TLS certificate verification.** The demo ingress uses a local CA. Always **verify** it: give your HTTP client the
CA file (`PROCUREMENT_CA_FILE`, `NODE_EXTRA_CA_CERTS`, `--cacert`) and use the hostname `localhost` (strict SNI rejects
IP literals). The Procurement CLI verifies the CA and hostname for **both** the API and the token endpoint, and the
live suite checks that an untrusted CA is refused. Never switch verification off to "make it work".

## 8. Worked example: Procurement, file by file

| File (`opentrix-procurement-demo`) | Purpose |
| --- | --- |
| `manifests/opentrix-procurement/app.json` | The whole application model above |
| `deploy/stack.py` | Provisioning: pins the Platform with `git archive`, generates secrets, writes `realm.json` (roles, six machine clients), copies manifests, starts Compose, exports the CA |
| `deploy/compose.yml` | Overlay on the Platform Compose: one published HTTPS port, Keycloak health gate, manifest mount for `platform-migrate`, own Caddy config |
| `deploy/Caddyfile` | HTTPS ingress: forwards only the contract's method/path pairs and the token endpoint |
| `procurement.py` | Stdlib client: token, TLS, one function per operation, no automatic write retries |
| `tests/live.py`, `tests/test_procurement.py` | Live regression (12) and unit tests (2) |

CLI exit codes you can copy into your own client: `3` = version conflict (re-read, decide again); `4` = write outcome
unknown (run `reconcile ID`, which reads the record and history under the current permissions; the CLI never replays a
write on its own). The CLI prints the create UUID **before** sending, so the id survives a crash.

A new application usually needs only: its manifest, its client roles and identities, and its UI or CLI. In M5 that was a
49-line manifest and a 240-line CLI on an unchanged Platform runtime.

## 9. Checklist and common mistakes

- [ ] Manifest validates (see below) and uses only the supported types and constraints.
- [ ] Roles in `permissions` and `transitions.roles` are all declared in `roles`.
- [ ] Every role has a matching Keycloak client role `<appId>:<role>` and a scope mapping.
- [ ] Machine clients have `service_account` scope and the `tricore-api` audience mapper.
- [ ] Registration followed: stop backend, migrate, start backend.
- [ ] Client sends `expectedVersion` on PUT, DELETE and transitions; generates the create UUID and keeps it.
- [ ] Client never retries a write blindly; it reconciles ([API guide](API-GUIDE.md#7-concurrency-idempotency-and-unknown-outcomes)).
- [ ] Client verifies TLS against the right CA and hostname.

**Validate a manifest** before deploying. The JSON Schema in the repository checks the frame (types, limits, patterns);
semantic checks (declared roles, reachable states, safe regex) run in the registrar. With Python and `jsonschema`:

```bash
python -c "import json,jsonschema,sys; jsonschema.Draft202012Validator(json.load(open('backend/src/main/resources/platform/app-manifest.schema.json'))).validate(json.load(open(sys.argv[1])))" path/to/app.json
```

This was run in M6.3 on the Platform's `apps/*/app.json`, ESM's two manifests and Procurement's manifest
([VALIDATION.md](VALIDATION.md#6-m63-onboarding-smoke-test)). It does **not** replace the semantic checks: the
definitive test is a registration on a disposable stack.

| Mistake | Symptom |
| --- | --- |
| Role used in `permissions` but not in `roles` | registration fails, "undeclared role" |
| Missing `service_account` scope | audit shows `USER` for a machine |
| Starting the backend while registering | stale permissions served; the controlled step prevents it |
| Using `127.0.0.1` in the URL | TLS handshake error (strict SNI) |
| Re-created Keycloak container | new subjects; ownership of old records points to identities that no longer exist |
| Expecting a server-side filter by state or owner | there is none; filter client-side and disclose caps |
| Treating `404` as "does not exist" | it also means "not visible to you" |
