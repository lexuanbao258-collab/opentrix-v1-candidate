<!--
SPDX-FileCopyrightText: 2026 TRICORE contributors
SPDX-License-Identifier: Apache-2.0
-->

# Platform API v1 guide

The machine-readable contract is [`../api/openapi-v1.json`](../api/openapi-v1.json). It is the **source of truth** for
paths, parameters and schemas; a Platform test fails if it drifts from the running application. This guide explains
behaviour the schema cannot express: authentication, authorization order, concurrency, idempotency and what to do when
a write's outcome is unknown. Examples below are real responses captured from a stack at `f401b6e` during M6.3 (ids
and actor ids shortened; data is synthetic).

Nothing here is invented: if an operation, filter or header is not in the table below, the Platform does not support it.

## 1. Base URL, content and authentication

- Base path: `/api/v1`. HTTPS ingress in the demo (for example `https://localhost:18444`). The demo ingress forwards
  only the method/path pairs of this contract to the backend; it answers `404` for `/v3/*` and for other `/api/*` paths.
- Request and response bodies are JSON. A body-carrying request needs `Content-Type: application/json`, otherwise
  `415 UNSUPPORTED_MEDIA_TYPE`.
- Every operation needs `Authorization: Bearer <access token>`. The token is a JWT from the configured OIDC provider.
  The Platform checks signature, issuer, audience (`tricore-api`), expiry and subject, and reads the caller's roles from
  `resource_access.tricore-api.roles` (role names `<appId>:<role>`). See the
  [developer guide](DEVELOPER-GUIDE.md#7-identity-and-the-keycloak-realm) for how to obtain a token with
  `client_credentials`.
- Errors use one shape: `{"status":404,"code":"NOT_FOUND","message":"Resource not found","errors":{}}`. `errors` maps a
  JSON location to a message for validation failures, for example `{"/quantity":"must have a minimum value of 1"}`.
- `GET /v3/api-docs` exists only when the backend sets `OPENTRIX_OPENAPI_ENABLED=true`, still requires a bearer token and
  is never routed by the ingress. Use the committed file instead.

## 2. Operations

All paths are below `/api/v1/apps`. `{app}` is the application id, `{entity}` the entity key, `{id}` a record UUID.

| # | Method and path | Needs | Success | Errors (besides `401`, `503`) |
| --- | --- | --- | --- | --- |
| 1 | `GET /` | a valid token | `200` applications where the caller holds a role | |
| 2 | `GET /{app}` | a role in the app | `200` | `404` when the caller holds no role in it |
| 3 | `GET /{app}/entities/{entity}` | a role in the app | `200` schema, search fields, `allowedOperations`, workflow summary | `404` |
| 4 | `GET /{app}/entities/{entity}/records?q=&limit=&offset=` | `read` | `200` page | `400` `403` `404` |
| 5 | `POST /{app}/entities/{entity}/records` | `create` | `201` + `Location` | `400` `403` `404` `409` `415` |
| 6 | `GET /{app}/entities/{entity}/records/{id}` | `read` (and `own` rule) | `200` | `400` `403` `404` |
| 7 | `PUT /{app}/entities/{entity}/records/{id}` | `update` | `200` | `400` `403` `404` `409` `415` |
| 8 | `DELETE /{app}/entities/{entity}/records/{id}?expectedVersion=` | `delete` | `204` | `400` `403` `404` `409` |
| 9 | `GET /{app}/entities/{entity}/records/{id}/workflow` | `read` | `200` state, version, `allowedActions` | `400` `403` `404` |
| 10 | `POST /{app}/entities/{entity}/records/{id}/transitions/{transition}` | the transition's role grant | `200` updated record | `400` `403` `404` `409` `415` |
| 11 | `GET /{app}/entities/{entity}/records/{id}/history` | `read` | `200` array | `400` `403` `404` |
| 12 | `GET /{app}/entities/{entity}/audit?recordId=&limit=&offset=` | entity-wide `audit` grant | `200` page | `400` `403` `404` |

Query parameters: `limit` 1 to 100 (default 20); `offset` 0 to **10000** for records and 0 to **100000** for audit;
`q` string; `recordId` UUID; `expectedVersion` integer 0 or more (required on DELETE). There is no other filter, no
sort parameter, no cursor, no bulk operation and no `PATCH`. Records are returned newest first (`created_at DESC`).

### What each status means

| Status | Code | Meaning | Client action |
| --- | --- | --- | --- |
| 400 | `VALIDATION_FAILED` | Body or parameter invalid; `errors` is keyed by JSON location | Fix and resend. Nothing was written. |
| 401 | `AUTHENTICATION_REQUIRED` | Missing, expired or invalid token | Get a new token. Nothing was written. |
| 403 | `FORBIDDEN` | The caller sees the resource but lacks the operation, the transition grant, or is the creator of a `notByCreator` transition | Do not retry. Nothing was written. |
| 404 | `NOT_FOUND` | Missing **or hidden** (no role in the app, unknown entity, `own` record of someone else) | Treat as "not visible to you". It does not prove absence. |
| 409 | `VERSION_CONFLICT` | `expectedVersion` is stale | Re-read, decide again, then send a new request. |
| 409 | `STATE_LOCKED` | Update or delete in a state declared `editable: false` | Use a transition instead. |
| 409 | `TRANSITION_NOT_ALLOWED` | The transition does not start at the record's current state | Re-read the workflow. |
| 409 | `CREATE_ID_CONFLICT` | The UUID is reserved for a different original payload | Use a new UUID, or resend the original data. |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Not `application/json` | Fix the header. |
| 503 | `STORE_UNAVAILABLE` | The Platform could not **start** the database transaction | Documented as not applied. If in doubt, re-read; then resend deliberately, not in an automatic loop. |
| 503 | `COMMIT_OUTCOME_UNKNOWN` | The write ran but the commit result is unknown | **Do not retry.** Reconcile (section 6). |
| 500 / 502 / 504 | none guaranteed | Platform bug, or the ingress lost the backend (observed `502` while the backend was stopped for registration) | For writes: outcome unknown, reconcile (section 6). For reads: retry later. |

Authorization order (ADR-C001): verified caller, then app exists **and** the caller has a role in it (else `404`), then
entity exists (`404`), then operation granted (`403`), then record visible (`own` means created by the caller, else
`404`), then schema validation (`400`), then the write and its audit row in one transaction. Hidden records are also
excluded in SQL before paging, so `total` and pages never reveal them.

## 3. Discovery

```text
GET /api/v1/apps                                  -> applications in which you hold a role
GET /api/v1/apps/opentrix-procurement             -> id, version, name, callerRoles, entities
GET /api/v1/apps/opentrix-procurement/entities/purchaseRequest
```

```json
{
  "app": "opentrix-procurement", "key": "purchaseRequest",
  "schema": { "type": "object", "required": ["itemName","quantity","estimatedUnitPriceMinor","justification"],
              "properties": { "quantity": { "type": "integer", "minimum": 1, "maximum": 10000 } },
              "additionalProperties": false },
  "searchFields": ["itemName"],
  "allowedOperations": { "create": "own", "read": "own", "update": "own", "delete": "own" },
  "workflow": { "initialState": "DRAFT",
    "transitions": [ { "id": "approve", "from": "PENDING", "to": "APPROVED", "allowedForCaller": false, "notByCreator": true } ] }
}
```

`allowedOperations` shows what **this caller** may do (`own`, `all`, and `audit: "all"` when granted). Build UI controls
from it, not from the manifest. `allowedForCaller` on a transition reflects the role grant only; use the per-record
`allowedActions` (operation 9) to know what is executable now.

## 4. Records: create, read, update, delete, search

**Create** (operation 5). `id` is optional; supply your own UUID to make the request reconcilable (section 5).

```http
POST /api/v1/apps/opentrix-procurement/entities/purchaseRequest/records
Content-Type: application/json

{"id":"59edd7d2-efe9-4ecf-a591-0f7573669ebe",
 "data":{"itemName":"Synthetic keyboard","quantity":2,"estimatedUnitPriceMinor":2500,"justification":"demo"}}
```

```http
HTTP/1.1 201 Created
Location: /api/v1/apps/opentrix-procurement/entities/purchaseRequest/records/59edd7d2-efe9-4ecf-a591-0f7573669ebe

{"id":"59edd7d2-…","app":"opentrix-procurement","entity":"purchaseRequest",
 "data":{"itemName":"Synthetic keyboard","quantity":2,"justification":"demo","estimatedUnitPriceMinor":2500},
 "version":0,"state":"DRAFT","createdBy":"oidc:aHR0cD….YzBkNDU0…","createdAt":"2026-11-27T11:07:16.679896Z",
 "updatedBy":"oidc:aHR0cD….YzBkNDU0…","updatedAt":"2026-11-27T11:07:16.679896Z"}
```

For entities without a workflow `state` carries no workflow state (not exercised in M6.3). A record starts in the workflow's `initialState` and cannot be
given a state through `data` (the closed schema rejects extra fields with `400`). `createdBy` is the canonical actor id
`oidc:base64url(issuer).base64url(subject)`; for a person it identifies the Keycloak user, for a machine it identifies
the **service account**.

**Read** (6): `GET …/records/{id}` returns the record above. **Search and page** (4):

```http
GET …/records?q=keyboard&limit=20&offset=0
{"items":[ {record}, … ],"total":1,"limit":20,"offset":0}
```

`q` is a case-insensitive substring match (`ILIKE '%q%'`, `%` and `_` escaped) over the entity's declared `search`
fields, any field matching. It is implemented without an index, which suits small catalogues and not large tables. It
is the **only** filter: there is no filter by state, owner, date or any other field. Clients that need one must page
and filter themselves; the ESM application does this for at most 500 records and discloses the cap in its UI.

**Update** (7) replaces the whole `data` object and needs the version you last read:

```http
PUT …/records/{id}
{"expectedVersion":0,"data":{ …all fields… }}
```

Success returns the record with `version` incremented. A stale `expectedVersion` returns `409 VERSION_CONFLICT` and
changes nothing. **Delete** (8): `DELETE …/records/{id}?expectedVersion=0` returns `204`.

## 5. Workflow and history

```http
GET …/records/{id}/workflow
{"state":"PENDING","version":1,"terminal":false,"editable":false,
 "allowedActions":[{"id":"approve","to":"APPROVED"},{"id":"reject","to":"REJECTED"}]}

POST …/records/{id}/transitions/approve
{"expectedVersion":1}
```

The transition returns `200` with the updated record (`state` `APPROVED`, `version` 2). `allowedActions` already applies
the role grant, the `own`/`all` scope, the current state and `notByCreator`. A creator holding the approver role gets
an **empty** `allowedActions` and `403` if they call the transition anyway; state, version and audit stay unchanged.

```http
GET …/records/{id}/history
[{"action":"CREATE","transitionId":null,"fromState":null,"toState":"DRAFT","actorType":"MACHINE","recordVersion":0,"occurredAt":"…"},
 {"action":"TRANSITION","transitionId":"submit","fromState":"DRAFT","toState":"PENDING","actorType":"MACHINE","recordVersion":1,"occurredAt":"…"}]
```

History shows the creation state and successful transitions, needs only record read access, and carries `actorId` and
`actorType` (`USER` or `MACHINE`). Updates and deletes are in the audit trail, not in history.

## 6. Audit

```http
GET …/entities/purchaseRequest/audit?recordId={id}&limit=2&offset=0
{"items":[{"eventId":1,"action":"CREATE","recordVersion":0,"actorType":"MACHINE","toState":"DRAFT","before":null,
           "after":{"itemName":"Synthetic keyboard","quantity":2,"justification":"[REDACTED]","estimatedUnitPriceMinor":2500}}],
 "total":3,"limit":2,"offset":0}
```

Audit is entity-wide and needs an `audit` grant (`"all"` only; there is no per-owner audit). Without it the response is
`403`. Fields marked `sensitive` in the manifest appear as `[REDACTED]` in `before` and `after`. The ordinary record
read still returns sensitive fields to callers who can read the record. Audit rows are append-only (the runtime
database login has no UPDATE, DELETE or TRUNCATE on them). `actorType` is `MACHINE` only when the token carries a
`client_id` claim equal to `azp` (Keycloak `service_account` scope); otherwise it is `USER`.

## 7. Concurrency, idempotency and unknown outcomes

**`expectedVersion`.** Every record has an integer `version` that increases by one on each update or transition. PUT,
DELETE and transitions must send the version they read. The Platform compares it under a row lock; a mismatch is
`409 VERSION_CONFLICT` and nothing changes. Two concurrent writers: exactly one wins.

**Client-generated UUID (create only).** Supply `id` (a UUID you generate and keep). The Platform records a reservation
that binds the UUID to tenant, app, entity, caller (and caller kind) and stores a SHA-256 fingerprint of the original
`data` (no payload values). Then:

| Replay of the same `id` | Result |
| --- | --- |
| Same caller, same original `data`, record exists and is readable | `201` with the **current** record and the same `Location`. No second CREATE audit row. |
| Same caller, **different** `data` | `409 CREATE_ID_CONFLICT` |
| Record was deleted, or the replay is out of the caller's scope | `404` |
| Another caller | `404` |

Reservations are kept after delete and have no expiry. Historical records (created before reservations existed) have a
reservation without a fingerprint. If you omit `id`, the server picks one; if that response is lost you cannot
reconcile reliably.

**Reconciling an ambiguous write.** A write is ambiguous when you sent it and did not get a definite answer: a lost or
reset connection, a timeout, `503 COMMIT_OUTCOME_UNKNOWN`, or `500`/`502`/`504`. The server never retries a write itself,
and **neither should you, blindly.** Procedure:

1. Stop. Do not resend yet. Keep the UUID, the original data and the `expectedVersion` you used.
2. Read with the same credentials: record (`GET …/{id}`), workflow state and history; use `audit` if you hold the grant.
3. Decide by operation:

| Operation | Evidence of applied | Evidence of not applied | Then |
| --- | --- | --- | --- |
| Create with your UUID | `GET {id}` returns your data | `GET {id}` is `404` (could also be hidden) | Replay the create with the same UUID **and** the original data. It is safe: it either returns the existing record or creates it once. |
| Update | `version` is above your `expectedVersion` and `data` equals what you sent | `version` still equals `expectedVersion` | If not applied, resend with the current version after re-reading. If another writer advanced it, show the conflict to the user. |
| Transition | history has your `transitionId` at version `expectedVersion + 1` by your actor | state and version unchanged | Same as update. |
| Delete | `GET` returns `404` | `GET` still returns the record at your version | A `404` may also be hiding; with an audit grant check for a `DELETE` event. Otherwise report "unconfirmed". |

A single read is **not always conclusive** (another writer may have acted in between, and `404` conflates missing with
hidden). When it is not, say so to the user instead of guessing. The ESM UI locks the form, offers "Check status" and
only after proof of absence offers "Retry with the same request id".

`503 STORE_UNAVAILABLE` is the one documented definite "not applied" failure (the transaction could not start). Only
that body code is treated as definite; a bare `503`, `502` or `504` from the ingress is not.

## 8. What is not supported

No endpoint other than the twelve above; no filter except `q`; no sort; no cursor paging (offset only, capped); no
`PATCH`; no batch; no cross-entity or cross-application query; no aggregates or counts besides `total`; no user
directory (actor ids are opaque); no workflow comments; no file upload; no webhooks or events.
