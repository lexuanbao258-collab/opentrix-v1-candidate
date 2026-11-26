#!/usr/bin/env node
// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * LIVE acceptance script: two machine identities (client_credentials) drive HR Leave CRUD and workflow through
 * the public ingress, outside the Platform and the Portal BFF. It prints one line per step and exits non-zero on
 * the first failed expectation. Secrets come from the environment and are never printed.
 *
 *   OPENTRIX_API_URL=https://localhost:8443
 *   OPENTRIX_TOKEN_URL=http://localhost:8180/realms/tricore/protocol/openid-connect/token
 *   OPENTRIX_CA_FILE=<local Caddy CA PEM>   EXTERNAL_EMPLOYEE_SECRET=...   EXTERNAL_APPROVER_SECRET=...
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { request } from "node:https";
import { ApiError, createClient } from "./opentrix.mjs";

const env = process.env;
const config = { apiUrl: env.OPENTRIX_API_URL, tokenUrl: env.OPENTRIX_TOKEN_URL, caFile: env.OPENTRIX_CA_FILE };
const employee = createClient({ ...config, clientId: "hr-employee-bot", clientSecret: env.EXTERNAL_EMPLOYEE_SECRET });
const approver = createClient({ ...config, clientId: "hr-approver-bot", clientSecret: env.EXTERNAL_APPROVER_SECRET });
const APP = "hr-leave";
const ENTITY = "leaveRequest";
const SECRET_REASON = `family matter ${crypto.randomUUID()}`;

let step = 0;
function ok(message) {
  console.log(`PASS ${String(++step).padStart(2, "0")} ${message}`);
}

function ingressStatus(method, path) {
  return new Promise((resolve, reject) => {
    const req = request(new URL(path, config.apiUrl), {
      method, ca: config.caFile ? readFileSync(config.caFile) : undefined, timeout: 15_000,
    }, (response) => {
      response.resume();
      resolve(response.statusCode);
    });
    req.on("error", reject);
    req.on("timeout", () => req.destroy(new Error("ingress timeout")));
    req.end();
  });
}

async function code(promise) {
  try {
    await promise;
    return null;
  } catch (error) {
    if (error instanceof ApiError) return `${error.status} ${error.body?.code ?? ""}`.trim();
    throw error;
  }
}

const data = { kind: "ANNUAL", startDate: "2026-12-24", days: 3, reason: SECRET_REASON };

assert.equal(await ingressStatus("GET", "/api/v1/apps"), 401);
assert.equal(await ingressStatus("POST", "/api/v1/apps"), 404);
assert.equal(await ingressStatus("GET", "/api/v1/apps/unknown/admin"), 404);
assert.equal(await ingressStatus("GET", "/api/v1/health"), 404);
assert.equal(await ingressStatus("GET", "/v3/api-docs"), 404);
ok("TLS verified; public route requires bearer; unlisted routes and methods are closed");

// machine identity sees only the application it holds roles in
const apps = await employee.apps();
assert.deepEqual(apps.map((a) => a.id), ["hr-leave"]);
assert.deepEqual(apps[0].callerRoles, ["employee"]);
ok("employee bot discovers only hr-leave, with role employee");

const entity = await employee.entity(APP, ENTITY);
assert.equal(entity.workflow.initialState, "DRAFT");
assert.equal(entity.allowedOperations.create, "own");
ok("entity schema and workflow are readable");

// CRUD
const created = await employee.create(APP, ENTITY, data);
assert.equal(created.state, "DRAFT");
assert.equal(created.version, 0);
ok(`create -> ${created.id} in DRAFT, version 0`);

assert.equal((await employee.get(APP, ENTITY, created.id)).data.days, 3);
const page = await employee.list(APP, ENTITY, { q: SECRET_REASON, limit: 5 });
assert.deepEqual(page.items.map((record) => record.id), [created.id]);
ok("read and search by reason");

const edited = await employee.update(APP, ENTITY, created.id, 0, { ...data, days: 4 });
assert.equal(edited.version, 1);
assert.equal(await code(employee.update(APP, ENTITY, created.id, 0, { ...data, days: 5 })), "409 VERSION_CONFLICT");
assert.equal(await code(employee.create(APP, ENTITY, { ...data, days: 99 })), "400 VALIDATION_FAILED");
ok("update with optimistic version; stale version -> 409; invalid record -> 400");

// workflow
const wf = await employee.workflow(APP, ENTITY, created.id);
assert.deepEqual(wf.allowedActions.map((a) => a.id), ["discard", "submit"]);
assert.equal(await code(employee.transition(APP, ENTITY, created.id, "approve", 1)), "403 FORBIDDEN");
assert.equal(await code(employee.transition(APP, ENTITY, created.id, "submit", 0)), "409 VERSION_CONFLICT");
const pending = await employee.transition(APP, ENTITY, created.id, "submit", 1);
assert.equal(pending.state, "PENDING");
assert.equal(pending.version, 2);
ok("employee may submit, not approve; stale transition -> 409; DRAFT -> PENDING");

assert.equal(await code(employee.update(APP, ENTITY, created.id, 2, data)), "409 STATE_LOCKED");
assert.equal(await code(employee.remove(APP, ENTITY, created.id, 2)), "409 STATE_LOCKED");
ok("generic PUT/DELETE are locked while PENDING (state cannot be bypassed)");

assert.deepEqual((await approver.workflow(APP, ENTITY, created.id)).allowedActions.map((a) => a.id), ["approve", "reject"]);
assert.equal(await code(approver.transition(APP, ENTITY, created.id, "submit", 2)), "403 FORBIDDEN");
const approved = await approver.transition(APP, ENTITY, created.id, "approve", 2);
assert.equal(approved.state, "APPROVED");
assert.equal(await code(approver.transition(APP, ENTITY, created.id, "reject", 3)), "409 TRANSITION_NOT_ALLOWED");
ok("approver bot approves; terminal state accepts no further transition");

const history = await employee.history(APP, ENTITY, created.id);
assert.deepEqual(history.map((h) => h.toState), ["DRAFT", "PENDING", "APPROVED"]);
assert.deepEqual(history.map((h) => h.actorType), ["MACHINE", "MACHINE", "MACHINE"]);
ok("workflow history DRAFT -> PENDING -> APPROVED, all actors labelled MACHINE");

// audit: only the auditor grant, values of sensitive fields redacted
const audit = await approver.audit(APP, ENTITY, { recordId: created.id });
assert.deepEqual(audit.items.map((e) => e.action), ["CREATE", "UPDATE", "TRANSITION", "TRANSITION"]);
assert.ok(!JSON.stringify(audit).includes(SECRET_REASON));
assert.equal(audit.items[0].after.reason, "[REDACTED]");
ok("audit readable by the auditor grant; reason redacted in before/after");
assert.equal(await code(employee.audit(APP, ENTITY, { recordId: created.id })), "403 FORBIDDEN");
ok("employee bot cannot read audit");

// isolation
assert.equal(await code(employee.list("it-helpdesk", "category")), "404 NOT_FOUND");
assert.equal(await code(approver.get(APP, ENTITY, created.id)), null);
const other = await employee.create(APP, ENTITY, data);
assert.equal(await code(approver.update(APP, ENTITY, other.id, 0, data)), "403 FORBIDDEN");
ok("no role in it-helpdesk -> 404; manager reads all but cannot edit");

// delete
await employee.remove(APP, ENTITY, other.id, 0);
assert.equal(await code(employee.get(APP, ENTITY, other.id)), "404 NOT_FOUND");
ok("delete a DRAFT record; it is gone");

// wrong credentials fail at the identity provider, not at the API
const forged = createClient({ ...config, clientId: "hr-employee-bot", clientSecret: "wrong-secret" });
await assert.rejects(forged.apps(), /token request failed/);
ok("wrong client secret is rejected by the identity provider");

console.log(`E2E OK: ${step} checks`);
