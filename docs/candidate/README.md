<!--
SPDX-FileCopyrightText: 2026 TRICORE contributors
SPDX-License-Identifier: Apache-2.0
-->

# OpenTrix Platform candidate: documentation

OpenTrix is a developer-first, API-first, self-hosted, open-source **enterprise application foundation**. This
directory documents the candidate Platform in this repository and the two independent applications built on its public
contract. It is a **competition-ready, self-hosted demonstration candidate. It is not a production-certified system**
and has no upstream approval (see [Status](#status)).

## Read in this order

| Goal | Document |
| --- | --- |
| Understand what OpenTrix is, what it is not and what it has shown | [Product overview](#product-overview) (below) |
| Install and verify the whole system | [QUICKSTART.md](QUICKSTART.md) |
| Build a new application from the public contract | [DEVELOPER-GUIDE.md](DEVELOPER-GUIDE.md) |
| Call the HTTP API correctly, including failures | [API-GUIDE.md](API-GUIDE.md) and the contract [`../api/openapi-v1.json`](../api/openapi-v1.json) |
| See how the parts fit together | [ARCHITECTURE.md](ARCHITECTURE.md) |
| Know what is and is not protected | [SECURITY-LIMITATIONS.md](SECURITY-LIMITATIONS.md) |
| See the verified evidence behind every claim | [VALIDATION.md](VALIDATION.md) |
| Read the binding design decisions | [ADR-C001](../08-decisions/ADR-C001-candidate-platform-baseline.md) |

## Product overview

**Problem.** Internal business applications (a helpdesk, leave requests, purchase approvals) repeat the same
plumbing: identity, per-application roles, record storage with validation, an approval workflow, separation of duties
and an audit trail. Each application usually re-implements it.

**Who it is for.** Developers who build internal applications and want to declare the business model once and call a
versioned REST API. End users reach it through an application UI; machines reach it with OAuth2 `client_credentials`.

**What the Platform provides** (all implemented in this repository, see [VALIDATION.md](VALIDATION.md)):

- Verified identity (OIDC JWT: signature, issuer, audience, expiry, subject) and application-scoped roles read from
  `resource_access.tricore-api.roles`.
- A declarative **application manifest** (`candidate-v1`): roles, entities with typed fields and validation limits,
  `own`/`all` permissions, an optional workflow state machine with `notByCreator` separation of duties, `sensitive`
  fields redacted in audit.
- Generic records API (`/api/v1/apps/...`): discovery, CRUD, substring search, pagination, optimistic concurrency
  (`expectedVersion`), client-generated create UUIDs, workflow actions and history, entity audit.
- One PostgreSQL transaction per write covering the record, workflow state and audit event; explicit unknown-outcome
  signalling (`503 COMMIT_OUTCOME_UNKNOWN`).
- Controlled manifest registration and compatible version upgrades with a least-privilege runtime database login.

**Platform versus business applications.** The Platform owns identity verification, authorization, the application
registry, records, workflow and audit. An application is *data* (a manifest) plus a client (UI or CLI) that calls the
public API. The two ESM modules and Procurement never read the Platform database, and Platform code does not depend on
Helpdesk, legacy workflow adapters or HTTP gateways (ArchUnit rule `PlatformDependencyRulesTest`).

**Applications built on it**

| Application | Repository | What it is |
| --- | --- | --- |
| IT Helpdesk and HR Leave (ESM) | [`opentrix-enterprise-demo`](https://github.com/lexuanbao258-collab/opentrix-enterprise-demo) | Two modules of one React + Node BFF application. Both use the same shell, API client, forms, tables, workflow and audit components. |
| Procurement | `opentrix-procurement-demo` (local checkout; **no public remote has been approved**) | An independently implemented Python standard-library CLI with its own manifest, deployment overlay and tests. |

**Demonstrated reusability** (measured facts, not time-savings claims; details in [VALIDATION.md](VALIDATION.md#5-reusability)):
the ESM applications and Procurement ran on the frozen Platform runtime `f401b6e` with **0 changed Platform runtime
files**. Procurement's application artefacts were measured at M5 as a 49-line manifest and a 240-line CLI.

**Current limitations (summary).** Demo-mode Keycloak, in-memory ESM sessions, no rate limiting, no multi-tenancy, no
referential integrity between entities, no server-side state filter or aggregates, additive-only manifest evolution,
substring search without an index. Full list in [SECURITY-LIMITATIONS.md](SECURITY-LIMITATIONS.md).

## Status

| Item | State |
| --- | --- |
| This repository | Independent candidate repository `lexuanbao258-collab/opentrix-v1-candidate`, branched from upstream `main@e7cd26a`. Candidate work carries **no upstream approval**. |
| Platform runtime baseline | `f401b6e79930a1386e71e92a91ae6a909cfa1e59` (frozen). Later commits changed deployment files and documentation only. |
| Release | Nothing is released, tagged or published from the candidate. Release integration is planned in [M6.4](../06-deployment/m64-release-integration-plan.md). |
| Production | Not certified. Evaluate with synthetic data only. |

## Which documents describe which system

The upstream repository history and its documents are preserved. They describe the **legacy IT Helpdesk demo**, which
still exists in this repository and is **not** exposed through the Platform API. The candidate documents are the
source of truth for the Platform.

| Document | Describes | Use for the Platform? |
| --- | --- | --- |
| [`../../README.md`](../../README.md) (candidate section) | Candidate Platform entry point | Yes |
| [`../../README.md`](../../README.md) (sections after "Legacy OpenTrix demo") | Upstream v0.2.0 Helpdesk demo | No |
| [`../API.md`](../API.md), [`../INTEGRATION.md`](../INTEGRATION.md), [`../ARCHITECTURE.md`](../ARCHITECTURE.md), [`../DEPLOYMENT.md`](../DEPLOYMENT.md), [`../DEVELOPMENT.md`](../DEVELOPMENT.md) | Upstream legacy Helpdesk and Workflow | No; each now carries a banner pointing here |
| [`../08-decisions/ADR-C001-candidate-platform-baseline.md`](../08-decisions/ADR-C001-candidate-platform-baseline.md) | Candidate decisions (status PROPOSED, candidate only) | Yes |
| ADR-007, 008, 009, 016, 017, 018 | Upstream decisions. ADR-009, 016, 017, 018 are partly not followed by the candidate (ADR-C001 section 6). They are intentionally **not edited**. | History only |
| [`../09-evidence/`](../09-evidence/), [`../verification/`](../verification/), [`../06-deployment/`](../06-deployment/) (r23 to r26) | Upstream evidence and release records | History only |
| [`../../NOTICE`](../../NOTICE), [`../../THIRD-PARTY-NOTICES.md`](../../THIRD-PARTY-NOTICES.md), [`../OPEN_SOURCE.md`](../OPEN_SOURCE.md) | Licensing and attribution | Yes |

Links inside upstream documents that point to `github.com/tricore-opentrix/opentrix` refer to the upstream project, not
to this candidate. They are left as written.
