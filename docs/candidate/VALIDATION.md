<!--
SPDX-FileCopyrightText: 2026 TRICORE contributors
SPDX-License-Identifier: Apache-2.0
-->

# Validation summary

This page consolidates the verified milestone evidence. It is a summary, not new proof: each row says **who produced
the evidence and whether it was executed again in M6.3**.

**Provenance labels**

| Label | Meaning |
| --- | --- |
| `EXEC-M6.3` | Executed during M6.3 (documentation milestone) on the machine described in [QUICKSTART.md](QUICKSTART.md) |
| `CI` | Hosted GitHub Actions run at the stated commit (link given) |
| `REPORT` | Recorded by the earlier milestone's agent or author in a committed document. **Not re-executed in M6.3**, so it is not new test evidence |
| `LOCAL-REPORT` | Recorded in a report that exists only on the owner's machine (git-ignored `.local/`), not in any repository |

Suites **overlap** (for example the M4 Playwright run includes every M3 spec, and the M6.2 run includes both). The
numbers below are per suite and per commit. **Do not add them together.**

## 1. Baselines

| Repository | Commit | Role |
| --- | --- | --- |
| Platform | `f401b6e79930a1386e71e92a91ae6a909cfa1e59` | Frozen runtime (M2.5) |
| Platform | `179d61d31069ce1a1bf246f35722d3a66cad0a93` | M6.1-FIX: deployment files and documentation only |
| ESM | `a4db6fe2127a936c85ee20aa2d3cf557fa107c3f` (M3, M4) → `8705d53ae9ce0361027a50135317f6a10be33e19` (M6.1-FIX) → `7b191c509d671af22089756dc18c4f3e94e8d3eb` (M6.2) | |
| Procurement | `e8595f5a18f5968a21cd48b52df574a5bf9c8622` (M5 clean-clone commit) → `f86777755fbcfde249881af0ee3064cca1de35a1` (M6.1-FIX) | Local only |

## 2. Milestone evidence

### M3 IT Helpdesk (ESM `d13d999`/`4d6c108`) — `REPORT` ([M3-evidence.md](https://github.com/lexuanbao258-collab/opentrix-enterprise-demo/blob/7b191c509d671af22089756dc18c4f3e94e8d3eb/docs/evidence/M3-evidence.md))

| Suite | Result |
| --- | --- |
| BFF unit/integration | 21 passed, 0 failed, 0 skipped |
| Web unit and component | 30 passed, 0 failed, 0 skipped |
| Playwright on an upgraded stack (1.0.0 data → 1.1.0, after restart) | 58 passed, 0 failed, 1 skipped (mobile-menu test, by design) |
| Playwright on a fresh install | 57 passed, 0 failed, 2 skipped |
| Manifest upgrade 1.0.0 → 1.1.0 | accepted; 4 category rows byte-identical before and after |
| Unsafe 1.2.0 variants | 9 of 9 refused; active version stayed 1.1.0 |
| Restart | PostgreSQL snapshot identical (`ticket` 248 rows, `category` 4) |
| Secret scan, `npm audit --omit=dev` | pass; 0 vulnerabilities |
| CI | [success `d13d999`](https://github.com/lexuanbao258-collab/opentrix-enterprise-demo/actions/runs/45947692002) |

### M4 HR Leave (ESM `47085c4`, docs `a4db6fe`) — `REPORT` ([M4-evidence.md](https://github.com/lexuanbao258-collab/opentrix-enterprise-demo/blob/7b191c509d671af22089756dc18c4f3e94e8d3eb/docs/evidence/M4-evidence.md))

| Suite | Result |
| --- | --- |
| BFF | 23 passed |
| Web | 60 passed |
| Playwright full run (includes all M3 specs; `ESM_E2E_UPGRADE=1 ESM_E2E_OUTAGE=1`) | **113 passed, 0 failed, 3 skipped** (restart spec needs a restart; two mobile-only tests on the desktop project) |
| Playwright after stack restart | 12 passed, 0 failed |
| Database after restart | snapshot identical (`leaveRequest` 70, `ticket` 432, `category` 4) |
| Platform runtime-source diff | 0 files, 0 lines vs `f401b6e` |
| CI | [success `a4db6fe`](https://github.com/lexuanbao258-collab/opentrix-enterprise-demo/actions/runs/45950374703) |

### M5 Procurement (`e8595f5`, restart fix `267b4c9`) — `REPORT` (Procurement `M5-REPORT.md`, Vietnamese)

| Suite | Result |
| --- | --- |
| Live regression (2 stacks: main and clean clone) | first run 11 passed, 1 failed (audit recorded `USER` because the token lacked `client_id`); after adding the Keycloak `service_account` scope, **12 passed, 0 failed, 0 skipped** on each stack |
| Persistence after restart | 1 check passed per stack (same PostgreSQL volume, record, history and audit identical) |
| Secret scan | 51 locally generated secret values searched in tracked files; none found |
| CI | none (no remote) |

### M6.1 verification and findings — `LOCAL-REPORT` / prior-agent session, **not re-executed in M6.3**

An independent technical verification before the M6.1-FIX recorded: Platform backend clean build 908 tests passed with 157
skipped (legacy suites that need explicit environment variables); HR Leave E2E 16 checks; IT Helpdesk 1.0.0 → 1.1.0
upgrade with 36 assertions across seed, upgrade and restart; unsafe 1.2.0 upgrade rejected; ESM 83 unit tests (BFF 23 +
web 60) at that commit; Procurement 12 live checks after a restart (the first attempt hit the Keycloak startup race);
database runtime privilege matrix; container logs free of secrets and bearer tokens; ingress allowlist blocking legacy,
`v3` and Keycloak paths. No report file for that session is committed to any repository.

Findings (all four were release-blocking P1 items and were handled in M6.1-FIX):

1. Procurement lacked `LICENSE`/`NOTICE` and a public remote.
2. ESM lacked an AI-assistance disclosure.
3. The Platform candidate was hard to find: no entry point from the upstream default branch README, no pointer to ADR-C001 or the OpenAPI file.
4. No Keycloak readiness gate: the backend reads OIDC discovery at start and failed when Keycloak was slow (reproduced in a Procurement cold build).

### M6.1-FIX closure — `LOCAL-REPORT` plus `CI` (reports `m61-release-hardening-report.md`, `m61-resume-report.md` in the Procurement `.local/`)

| Item | Result |
| --- | --- |
| Platform `179d61d` | [CI success, 5 of 5 jobs](https://github.com/lexuanbao258-collab/opentrix-v1-candidate/actions/runs/45972074736): `r23-compliance`, `portal-bff`, `backend`, `frontend`, `repo-hygiene` |
| ESM `8705d53` | [CI success, 3 of 3 jobs](https://github.com/lexuanbao258-collab/opentrix-enterprise-demo/actions/runs/45972073867) |
| Procurement `f867777` | local commit; no hosted CI |
| Platform diff vs `f401b6e` | 6 files, +75/−16: 0 runtime files; deployment config (`infra/compose.demo.yml`, `infra/demo/init/kc-init.sh`) and documentation only; V1/V2/V3 unchanged |
| Keycloak readiness | 3 of 3 cold boots passed for Platform and for Procurement (readiness gate: Keycloak start to healthy 30.6 to 74.0 s; backend started after healthy; `RestartCount=0`) |
| Live migration (IT 1.0.0 → 1.1.0 via `platform-stack.sh migrate`) | pass: backend stopped before the migrator and started after; fingerprints of seeded categories and audit counts unchanged. Unsafe 1.2.0: rejected, backend stayed stopped, dataset unchanged |
| ESM Playwright after M6.1-FIX | 113 passed, 0 failed, 3 skipped; 12 passed after restart |
| Procurement live regression | 12 passed; persistence after restart passed; Keycloak restart gating confirmed |
| Platform API suites (embedded PostgreSQL, focused groups) | 74 passed, 0 failed (a first run on a full `C:` drive failed 51 tests for lack of disk; re-run with temp on `D:` passed) |
| ESM unit tests at `8705d53` | BFF 25, web 61 passed |
| Co-existence | a new ESM fixture and a new Procurement fixture served requests at the same time (ports 18774/18744/18780 and 18643) |

### M6.2 UI/UX and regression (ESM `7b191c5`) — `REPORT` ([M62-evidence.md](https://github.com/lexuanbao258-collab/opentrix-enterprise-demo/blob/7b191c509d671af22089756dc18c4f3e94e8d3eb/docs/evidence/M62-evidence.md)) and `CI`

| Suite | Result |
| --- | --- |
| BFF unit | 25 passed |
| Web unit | 68 passed |
| Full Playwright regression (IT + HR, security, session, outage, layout, new UI specs) | **119 passed, 0 failed, 4 skipped** (`hr-restart`, `upgrade`, and two mobile-only tests in the desktop project: each needs a different stack state or project) |
| Layout and axe re-run (WCAG 2 A/AA, 2.1 AA serious and critical; 18 pages × desktop and mobile) | 50 passed, 2 skipped |
| Changes outside `web/` UI code | none to backend, BFF, Platform, manifests or API contract; no dependency added |
| CI | [success `7b191c5`](https://github.com/lexuanbao258-collab/opentrix-enterprise-demo/actions/runs/45985853473): `build-test (web)`, `build-test (bff)`, `secrets-scan` |
| Screenshots | synthetic demonstration data seeded through the public API on a disposable stack (see ESM README) |

## 3. Executed again in M6.3 (`EXEC-M6.3`)

| Check | Result |
| --- | --- |
| Baselines of all three repositories | HEAD equals the commits in section 1 before any change; clean trees |
| ESM unit tests at `7b191c5` | BFF **25 passed**, web **68 passed** (93 total across two separate suites, 0 skipped) |
| ESM secret scan | `SECRET_SCAN_PASS`, 127 files, 21 real secret values and 4 patterns |
| Procurement unit tests | 2 passed |
| Isolated Procurement stack from a pinned Platform archive (project `opentrix-procurement-m63-smoke`, port 18463) | `up` succeeded on warm caches after a Docker subnet workaround (section 5 of QUICKSTART) |
| Quick Start CLI flow | create, search, submit, approve, history, audit with `[REDACTED]`, self-approval `403`, as documented |
| HTTP error shapes in the [API guide](API-GUIDE.md) | `400`, `401`, `403`, `404`, `409 VERSION_CONFLICT`, `409 CREATE_ID_CONFLICT`, `415` captured from the live stack; matching-replay `201` confirmed |
| Procurement live regression and restart | see [section 6](#6-m63-onboarding-smoke-test) |

## 4. Runtime source diff

Frozen runtime `f401b6e79930a1386e71e92a91ae6a909cfa1e59`; all commands run in M6.3 on the documentation branches.

| Repository | Command | Result |
| --- | --- | --- |
| Platform | `git diff --stat f401b6e -- backend portal-bff frontend apps clients docs/api` | empty: **0 runtime-source files, 0 lines** (also empty for `backend/src/main/resources/db` and `.../platform`: V1/V2/V3, the manifest schema, the example manifests and `openapi-v1.json` are unchanged) |
| Platform | same plus `infra scripts .github` | 2 files, +26/−12 (`infra/compose.demo.yml`, `infra/demo/init/kc-init.sh`), both from M6.1-FIX at `179d61d`, none from M6.3 |
| Platform | `git diff --name-only 179d61d` | only Markdown files and `NOTICE` (M6.3 changes: README, SECURITY, CONTRIBUTING, NOTICE, `docs/README.md`, a banner in six legacy guides, and seven new files in `docs/candidate/`) |
| ESM | `git diff --name-only 7b191c5` | `NOTICE`, `README.md`, `docs/DEVELOPMENT.md` only; no source, manifest or test file |
| Procurement | `git diff --name-only f867777` | `NOTICE`, `README.md` only; manifest, CLI, deploy and tests untouched |

## 5. Reusability

Measurements of the repository changes, **not** development-time savings. No baseline of building the same
application from scratch exists, so **no time or cost saving is claimed.**

| Measure | Result | Source |
| --- | --- | --- |
| Platform runtime files changed for ESM (IT Helpdesk and HR Leave) | 0 files, 0 lines | M3, M4 evidence (`git diff --stat f401b6e`) |
| Platform runtime files changed for Procurement | 0 files, 0 lines | M5 report |
| HR Leave as a second ESM module | module source about 656 lines in 6 files plus a 54-line manifest copied unmodified; plus about 340 added and 115 removed lines of one-time generalisation of the single-app code (app id, discovery, navigation, audit and history) | M4 evidence section 7 |
| Reused unchanged by HR | BFF session, OIDC, CSRF, upstream timeout and unknown-outcome logic; shared UI components; API transport; shell | M4 evidence section 7 |
| Manifest and deployment work for HR | one new manifest; HR roles added to the BFF client scope; five users; one `ALLOWED_APPS` entry; no Compose change | M4 evidence |
| Procurement application artefacts at M5 | manifest 49 lines; CLI 240 lines, 0 external Python dependencies; deploy script 145 lines; Compose overlay 25 lines; Caddyfile 40 lines; live tests 349 lines | M5 report (counts include blank lines; current files differ slightly after M6.1-FIX) |
| Manual deployment steps for Procurement | one `up` command with two checkouts | M5 report |
| Observed elapsed time for M5 | 30 min 54 s, including survey, runs and waiting | M5 report; describes M5 only, **not** a comparison |

## 6. M6.3 onboarding smoke test

Bounded, documentation-only, on isolated Compose projects (`opentrix-procurement-m63-smoke` on port 18463,
`opentrix-esm-m63-smoke` on 18964/18980/18974), warm image and Gradle caches, nothing pulled (`C:` never below 21 GiB free; limit 10 GB). The
existing stacks and volumes were not touched. No M6.1 suite was repeated in full.

| Step | Result |
| --- | --- |
| Procurement `deploy/stack.py up --platform …` | first attempt failed with `all predefined address pools have been fully subnetted` (Docker ran out of default subnets); second attempt with a temporary `/28` subnet in an external Compose override succeeded. The override is not part of any repository |
| Keycloak gate, migrator, backend | `platform-migrate` exit 0, Keycloak `healthy`, backend started after |
| Quick Start CLI flow | create, search, submit (`PENDING`, v1), approve (`APPROVED`, v2), history (3 events, `MACHINE`), audit (`[REDACTED]`), dual-role self-approval `403` with `allowedActions: []` |
| Error bodies | `401 AUTHENTICATION_REQUIRED`, `400 VALIDATION_FAILED` (`/quantity`), `404 NOT_FOUND`, `403 FORBIDDEN`, `409 VERSION_CONFLICT`, `409 CREATE_ID_CONFLICT`, `415`, `/v3/api-docs` `404`; matching replay returned `201` |
| `python -B -m unittest tests.test_procurement` | 2 passed |
| `python -B -m tests.live -v` | **12 passed**, 0 failed, 0 skipped, 332.356 s |
| `stack.py restart` then `tests.live verify-restart` | passed: record, workflow history and audit preserved |
| Manifest upgrade on that stack | Procurement `1.1.0` (optional field `costCenter` added) registered after stopping the backend; discovery showed `1.1.0`; a record with the new field was created (`201`); the earlier record unchanged. Unsafe `1.2.0` (`read.requester` `own`→`all`) refused: `Incompatible manifest opentrix-procurement/purchaseRequest: grant broadened for role requester`; `1.1.0` restored (`registered=[]`) and the backend restarted |
| Manifests against `app-manifest.schema.json` (Draft 2020-12, `jsonschema` 4.26.0) | all pass: Platform `apps/*`, ESM `manifests/*`, Procurement, and the two upgrade variants; a negative control (field type `array`) fails. The schema does **not** reject the unsafe `1.2.0`; only the registrar does |
| ESM Quick Start: `platform-stack.sh up`, `provision-keycloak.sh`, CA copy, BFF build, BFF start | succeeded; backend, ingress, Keycloak, PostgreSQL up; `/bff/session` → `{"authenticated":false}`; unauthenticated Platform call `401`; `/v3/api-docs` `404` |
| ESM sign-in | Playwright `setup` project: **10 passed** (nine real Keycloak sign-ins for the demo identities plus category seeding) |
| Safe shutdown | `deploy/platform-stack.sh down` and `deploy/stack.py stop` stopped all containers and kept both PostgreSQL and Caddy volumes |
| Disk | about 0.3 GiB net use for the two new stacks on warm caches |
| Docs checks | 8 Mermaid blocks parse (mermaid `parse`); relative links and anchors in all changed Markdown resolve; secret scan of all three working trees ([section 9](#9-secret-scan)) |

What this does **not** show: a cold build, Linux or macOS, a second machine, or the full ESM Playwright regression
(M6.2 ran it: 119 passed).

## 7. Comparison with other projects

No comparison with other products is made. Claims of novelty or superiority are not supported by evidence collected in
this project and are not made.

## 8. Gaps in the evidence

- No cold build (no caches) was timed; warm-cache runs only.
- Linux and macOS were not exercised.
- Procurement has no hosted CI.
- The 908-test Platform backend run and the M6.1 live checks exist only as a prior report and CI; M6.3 did not repeat them.
- The unknown-outcome path is simulated (see [SECURITY-LIMITATIONS.md](SECURITY-LIMITATIONS.md), item 10).
- AI-assisted development is disclosed in [NOTICE](../../NOTICE); no test or review claim here depends on it.

## 9. Secret scan

Run in M6.3 over every tracked and untracked, non-ignored file of the three repositories (before commit): 330 distinct
hex secret values harvested from the stacks' git-ignored `accounts.json`, `compose.env`, `esm.env` and `platform.env`
were searched for. **0 occurrences** in any changed or new document. Regular expressions for private-key blocks,
JWT-shaped strings, AWS-style keys and `password|secret|token = <20+ chars>` found one match, a documented synthetic
test value in the existing `scripts/test-gateway-smoke.ps1` (not changed by M6.3). ESM `scripts/scan-secrets.mjs`:
`SECRET_SCAN_PASS`. A dedicated scanner such as gitleaks was not installed on the machine; this was a purpose-built
check, not a replacement for it.
