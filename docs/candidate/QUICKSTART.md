<!--
SPDX-FileCopyrightText: 2026 TRICORE contributors
SPDX-License-Identifier: Apache-2.0
-->

# Quick Start

Install the candidate Platform locally, run the two example applications and verify them. Everything here uses
synthetic data on `localhost`. Nothing is exposed beyond loopback.

**How to read the status tags.** `TESTED` = executed during M6.3 on the machine described below and passed.
`NOT VERIFIED` = documented by a repository but not executed in M6.3. Do not rely on `NOT VERIFIED` commands without
trying them first.

## 1. Environment

| Item | Tested in M6.3 | Documented minimum |
| --- | --- | --- |
| Operating system | Windows 11 Home 10.0.26200, Git Bash (POSIX shell) | Windows, Linux or macOS with a POSIX shell. **Linux and macOS: NOT VERIFIED.** |
| Docker | Docker Desktop, Engine 29.7.2, Linux containers | Compose **2.24.4 or newer** (the overlays use `!override` and `!reset`). Tested with Compose 5.4.0. |
| Git | 2.45.1 | any recent Git with `git archive` |
| Python | 3.14.2 | 3.10 or newer (standard library only) |
| Node.js | 24.19.0 | 22 or newer (ESM only) |
| Other tools | `tar`, `openssl`, `curl` (ESM scripts use `openssl rand`) | same |

Images used (pulled or built by Compose): PostgreSQL `17.11-alpine`, Keycloak `26.8.0`, Caddy `2.10.2`, Eclipse
Temurin 21 JRE, Gradle 8.14.3 with JDK 21 for the backend build. Java and Gradle run only inside Docker; you do not
install them.

**Disk and memory.** Plan for at least **20 GB free** on the drive that holds Docker's data. Measured:

- The existing Docker data on the test machine (many earlier stacks) was about 17 GB of images and 21 GB of build cache.
  One backend image is about 549 MB.
- Two new isolated stacks (Procurement and ESM) started from **warm** image and build caches used about 0.3 GiB net in M6.3.
- During the M6.1-FIX live runs, free space on `C:` fell from 24.07 GiB to a minimum of 22.34 GiB, a net use of about
  1.7 GiB, with caches already present.
- A **cold** build (no image or Gradle cache) was **not measured** in M6.3. Expect several GB and a build time of
  minutes. The Platform's Gradle test suite with embedded PostgreSQL needs additional temporary space; a run with its
  temp directory on a nearly full drive failed with `No space left` (M6.1-FIX report).
- Docker Desktop with at least 8 GB RAM is recommended by the upstream demo guide.

The M6 milestones stopped all build operations if free space on the system drive fell below **10 GB**. Do the same.

## 2. Pinned commits

| Repository | Commit | Notes |
| --- | --- | --- |
| Platform (`opentrix-v1-candidate`) | runtime `f401b6e79930a1386e71e92a91ae6a909cfa1e59` | Frozen runtime. `git diff f401b6e HEAD -- backend portal-bff frontend apps docs/api` is empty. |
| Platform deployment files | `179d61d31069ce1a1bf246f35722d3a66cad0a93` (M6.1-FIX) | Adds the Keycloak readiness gate to `infra/compose.demo.yml`. ESM uses a checkout at this commit or a descendant. |
| ESM (`opentrix-enterprise-demo`) | `7b191c509d671af22089756dc18c4f3e94e8d3eb` (M6.2) | |
| Procurement (`opentrix-procurement-demo`) | `f86777755fbcfde249881af0ee3064cca1de35a1` | Local checkout only. |

Documentation commits made in M6.3 sit on top of these and change no runtime source (see
[VALIDATION.md](VALIDATION.md#4-runtime-source-diff)).

## 3. Path A: Platform plus Procurement CLI (smallest stack, `TESTED`)

This path starts only PostgreSQL, Keycloak, the Platform backend and an HTTPS ingress. Procurement's `stack.py` extracts
the pinned Platform commit with `git archive`, generates every secret, builds a Keycloak realm with machine clients and
starts an isolated Compose project.

```bash
# Platform checkout (any commit that contains f401b6e, for example the candidate branch)
git clone https://github.com/lexuanbao258-collab/opentrix-v1-candidate.git
# Procurement is a local checkout; no public remote exists. Obtain it from its owner.
cd opentrix-procurement-demo
python deploy/stack.py up --platform /path/to/opentrix-v1-candidate
python deploy/stack.py ps
```

What `up` does, in order (no manual step):

1. **Pin**: `git archive f401b6e…` into `.local/<project>/platform/` and verifies the marker.
2. **Secrets**: writes `.local/<project>/compose.env` (database, Keycloak and client secrets, random) and `accounts.json`
   (six machine client secrets), both mode 0600 and ignored by Git.
3. **Manifests**: copies the Procurement manifest and the two Platform example manifests (`it-helpdesk`, `hr-leave`) to
   `.local/<project>/apps/`.
4. **Keycloak realm**: generates `realm.json` from the Platform realm: one resource client `tricore-api`, the
   Procurement client roles and machine clients `m5-requester`, `m5-approver`, `m5-auditor`, `m5-dual`, `m5-hr`, `m5-it`.
5. **PostgreSQL initialization**: the `db-init` container creates the setup role, the NOLOGIN schema owner, the runtime
   login `helpdesk_runtime_demo` (no role memberships) and the `opentrix_platform_owner` role that owns schema
   `opentrix_platform`.
6. **Controlled registration**: the `platform-migrate` container runs Flyway V1 to V3 as the schema owner and registers
   every manifest under `/apps` in one transaction, then exits.
7. **Start order**: the backend starts only after `platform-migrate` completed **and** Keycloak is healthy (see 5).
8. **HTTPS**: Caddy starts with an internal CA; `up` exports its public root certificate to
   `.local/<project>/caddy-root.crt`.

Defaults: project `opentrix-procurement-m5-verified`, HTTPS port `18444` on `127.0.0.1`. Override with `M5_PROJECT`
(prefix `opentrix-procurement-`) and `M5_PORT` to run beside another stack.

Run the CLI as a machine identity (Git Bash shown; PowerShell form is in the Procurement README):

```bash
P=opentrix-procurement-m5-verified        # your M5_PROJECT
export PROCUREMENT_API_URL=https://localhost:18444   # your M5_PORT
export PROCUREMENT_CA_FILE="$PWD/.local/$P/caddy-root.crt"
use() {  # read the client secret from the private file without printing it
  export PROCUREMENT_CLIENT_ID="m5-$1"
  PROCUREMENT_CLIENT_SECRET=$(python -I -c "import json,sys;print(json.load(open(sys.argv[1]))[sys.argv[2]])" ".local/$P/accounts.json" "$1")
  export PROCUREMENT_CLIENT_SECRET
}
use requester
python procurement.py apps                        # discovery: shows opentrix-procurement and your role
ID=$(python -c "import uuid;print(uuid.uuid4())")
python procurement.py create --id "$ID" --item 'Synthetic keyboard' --quantity 2 --unit-price-minor 2500 --justification 'demo'
python procurement.py submit "$ID" --version 0
use approver
python procurement.py approve "$ID" --version 1
python procurement.py history "$ID"
use auditor
python procurement.py audit --id "$ID"            # justification is [REDACTED]
```

Expected: `create` prints the request UUID first, then the record in state `DRAFT` with `version` 0; `submit` returns
`PENDING` version 1; `approve` returns `APPROVED` version 2; `history` shows CREATE and two TRANSITION events with
`actorType: MACHINE`; `audit` shows `"justification": "[REDACTED]"`. A caller holding both `requester` and `approver`
(`use dual`) gets `HTTP 403 FORBIDDEN` when approving a request it created (`notByCreator`).

Tests (`TESTED`): `python -B -m unittest tests.test_procurement -v` (2 tests), `python -B -m tests.live -v` (12 live
checks, about 5 to 6 minutes), `python deploy/stack.py restart` followed by `python -B -m tests.live verify-restart`.

## 4. Path B: ESM (IT Helpdesk and HR Leave) on the same Platform

ESM needs a Platform checkout at `179d61d` or later because its Compose overlay inherits the Keycloak readiness gate.
Run everything from a POSIX shell (Git Bash on Windows).

```bash
git clone https://github.com/lexuanbao258-collab/opentrix-v1-candidate.git
git clone https://github.com/lexuanbao258-collab/opentrix-enterprise-demo.git
cd opentrix-enterprise-demo
export PLATFORM_DIR="$PWD/../opentrix-v1-candidate"
. deploy/settings.sh
deploy/platform-stack.sh up              # PostgreSQL, Keycloak, backend, HTTPS ingress; registers manifests/
deploy/provision-keycloak.sh             # ESM OIDC client and demo users; random secrets in .local/esm.env
docker cp "$ESM_STACK_PROJECT-ingress-1:/data/caddy/pki/authorities/local/root.crt" "$ESM_LOCAL_DIR/caddy-root.crt"
(cd bff && npm ci && npm run build) && (cd web && npm ci && npm run build)
deploy/run-bff-local.sh                  # foreground; http://localhost:5174
```

Open `http://localhost:5174`. Demo identities and their roles are listed in the ESM
[development guide](https://github.com/lexuanbao258-collab/opentrix-enterprise-demo/blob/7b191c509d671af22089756dc18c4f3e94e8d3eb/docs/DEVELOPMENT.md#run-esm).
Their passwords are random and live only in `.local/esm.env`. Read one without printing the others, for example:
`grep '^ESM_PASSWORD_AGENT=' .local/esm.env`. Never paste them into issues, commits or screenshots.

Default ports: Platform HTTPS `8443`, Keycloak `8180`, BFF `5174`. Use `ESM_STACK_PROJECT`, `ESM_PLATFORM_HTTPS_PORT`,
`ESM_KEYCLOAK_PORT` and `ESM_PORT` to isolate a second stack. Keycloak runs in `start-dev` mode with state inside its
container. If that container is **recreated** (not merely restarted), run `deploy/provision-keycloak.sh` again.

## 5. Keycloak readiness

Keycloak 26.8.0 starts slowly (observed 30 to 74 seconds from container start to healthy). The Platform backend reads
the realm's OIDC discovery document at startup and fails if Keycloak is not ready. The Compose files therefore:

- enable `KC_HEALTH_ENABLED` and a healthcheck that requires HTTP 200 from the management endpoint
  (`:9001/health/ready`) **and** from `/realms/tricore/.well-known/openid-configuration`;
- start the backend only when Keycloak is `service_healthy`.

Check it yourself:

```bash
docker compose ls                                    # find your project
docker ps --filter name=<project>-keycloak --format '{{.Names}} {{.Status}}'   # expect (healthy)
```

Do not start the backend by hand before Keycloak is healthy, and do not replace the gate with a fixed sleep.

## 6. HTTPS and the trusted CA

Caddy issues a certificate from its own internal CA for `localhost`. Clients must trust that CA:

| Client | How |
| --- | --- |
| Procurement CLI | `PROCUREMENT_CA_FILE=.local/<project>/caddy-root.crt` |
| ESM BFF and Playwright | `NODE_EXTRA_CA_CERTS=$ESM_LOCAL_DIR/caddy-root.crt` (set by `. deploy/env.sh`) |
| curl | `curl --cacert <root.crt> …`; on Windows add `--ssl-revoke-best-effort` (the local CA publishes no CRL/OCSP) |

Always use the hostname `localhost`; the ingress enforces strict SNI and an IP-literal URL fails the TLS handshake.
Do not disable certificate verification. A new volume creates a new CA, so export it again after a reset.

## 7. Health and verification commands

| Check | Command | Healthy result |
| --- | --- | --- |
| Containers | `python deploy/stack.py ps` (Procurement) or `docker compose -p $ESM_STACK_PROJECT ps` | postgres and keycloak `healthy`; backend and ingress `running`; `platform-migrate` and `db-init` `exited (0)` |
| Authentication required | `curl --cacert <root.crt> https://localhost:<port>/api/v1/apps` | `401` with `{"code":"AUTHENTICATION_REQUIRED"}` |
| Authenticated discovery | `python procurement.py apps` | `200`, a JSON array containing your applications |
| Contract not exposed | `curl --cacert <root.crt> https://localhost:<port>/v3/api-docs` | `404` (the ingress never routes it) |
| ESM session endpoint | `curl http://localhost:5174/bff/session` | JSON with `authenticated: false` before sign-in |
| Procurement regression | `python -B -m tests.live -v` | 12 checks pass |
| ESM unit tests | `(cd bff && npm test)` and `(cd web && npm test)` | BFF 25 and web 68 passed at `7b191c5` |
| ESM secret scan | `node scripts/scan-secrets.mjs` | `SECRET_SCAN_PASS` |

## 8. Safe shutdown (data is kept)

```bash
python deploy/stack.py stop             # Procurement: stops backend, ingress, keycloak, postgres; keeps volumes
deploy/platform-stack.sh down           # ESM: same, keeps volumes
# stop the BFF with Ctrl+C in its terminal
```

Restart after a stop with `python deploy/stack.py restart` (Procurement) or `deploy/platform-stack.sh up` (ESM). Both
wait for Keycloak before the backend. Restart keeps PostgreSQL data (verified by the persistence checks in
[VALIDATION.md](VALIDATION.md)). Keycloak identities survive a restart but not a container re-creation.

**Never run these unless you intend to delete data:** `deploy/platform-stack.sh reset`, `docker compose … down -v`,
`demo-down … -Reset`, `docker volume rm`, `docker system prune --volumes`. They delete the PostgreSQL volume and the CA.

## 9. Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| `all predefined address pools have been fully subnetted` when creating the network | Docker has run out of default subnets after many stacks. Remove **unused** networks you recognise (`docker network ls`, then `docker network rm <name>`), or enlarge `default-address-pools` in Docker's daemon configuration. Do not touch networks of running stacks. M6.1-FIX and M6.3 hit this and worked around it with a temporary `/28` subnet in a throw-away Compose override. |
| Backend exits at start with an OIDC discovery error | Keycloak was not ready. Use the Compose files unchanged (they gate on health) and see section 5. |
| `TLSV1_ALERT_INTERNAL_ERROR` or certificate errors | Using `127.0.0.1` instead of `localhost`, or the wrong CA for this project. Section 6. |
| Port already in use | Another stack owns it. Pick other ports with `M5_PORT` or the `ESM_*` variables. |
| Platform audit shows `actorType: USER` for a machine client | The client lacks the Keycloak `service_account` scope. See the [developer guide](DEVELOPER-GUIDE.md#7-identity-and-the-keycloak-realm). |
| ESM client or users vanished after `up` | The Keycloak container was re-created. Run `deploy/provision-keycloak.sh` again. |

## 10. Not covered or not verified

- The upstream one-command legacy demo (`infra/demo/demo-up.*`, with or without `--gateway`) starts the **legacy
  Helpdesk Portal** and APISIX. It was **not** exercised in M6.3 and is not needed for the Platform.
- Linux and macOS hosts, cold builds, and building from an extracted source archive without `.git`.
- Running the Platform behind anything other than the supplied Caddy ingress.
