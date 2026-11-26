// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0

import assert from "node:assert/strict";
import http from "node:http";
import { after, before, test } from "node:test";
import { ApiError, OutcomeUnknownError, createClient, run } from "./opentrix.mjs";

const ID = "00000000-0000-4000-8000-000000000001";
const SECRET = "s3cret-value";
let server;
let base;
let seen;
let mode = "ok";

before(async () => {
  server = http.createServer((req, res) => {
    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => {
      const body = Buffer.concat(chunks).toString();
      seen.push({ method: req.method, url: req.url, auth: req.headers.authorization, body });
      if (req.url === "/token") {
        res.setHeader("Content-Type", "application/json");
        return res.end(JSON.stringify({ access_token: "tok-1", expires_in: 300 }));
      }
      if (mode === "drop") return req.socket.destroy();
      if (mode === "conflict") {
        res.statusCode = 409;
        return res.end(JSON.stringify({ code: "VERSION_CONFLICT" }));
      }
      if (req.method === "DELETE") {
        res.statusCode = 204;
        return res.end();
      }
      res.setHeader("Content-Type", "application/json");
      res.end(JSON.stringify({ ok: true }));
    });
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());

function client(overrides = {}) {
  seen = [];
  mode = "ok";
  return createClient({ apiUrl: base, tokenUrl: `${base}/token`, clientId: "ext", clientSecret: SECRET, ...overrides });
}

test("fetches one token with Basic auth and sends it as bearer; secret never goes in a URL or body of API calls", async () => {
  const c = client();
  await c.apps();
  await c.list("hr-leave", "leaveRequest", { q: "a&b", limit: 5, offset: 10 });
  assert.equal(seen.filter((r) => r.url === "/token").length, 1);
  assert.equal(seen[0].auth, `Basic ${Buffer.from(`ext:${SECRET}`).toString("base64")}`);
  assert.equal(seen[1].auth, "Bearer tok-1");
  assert.equal(seen[2].url, "/api/v1/apps/hr-leave/entities/leaveRequest/records?limit=5&offset=10&q=a%26b");
  for (const request of seen.slice(1)) assert.ok(!request.url.includes(SECRET) && !request.body.includes(SECRET));
});

test("builds workflow, history, audit and write requests", async () => {
  const c = client();
  await c.transition("hr-leave", "leaveRequest", ID, "submit", 0);
  await c.update("hr-leave", "leaveRequest", ID, 1, { reason: "x" });
  await c.remove("hr-leave", "leaveRequest", ID, 2);
  await c.audit("hr-leave", "leaveRequest", { recordId: ID });
  const calls = seen.filter((r) => r.url !== "/token").map((r) => `${r.method} ${r.url}`);
  assert.deepEqual(calls, [
    `POST /api/v1/apps/hr-leave/entities/leaveRequest/records/${ID}/transitions/submit`,
    `PUT /api/v1/apps/hr-leave/entities/leaveRequest/records/${ID}`,
    `DELETE /api/v1/apps/hr-leave/entities/leaveRequest/records/${ID}?expectedVersion=2`,
    `GET /api/v1/apps/hr-leave/entities/leaveRequest/audit?limit=20&offset=0&recordId=${ID}`,
  ]);
  assert.deepEqual(JSON.parse(seen.find((r) => r.method === "POST" && r.url !== "/token").body), { expectedVersion: 0 });
});

test("rejects unsafe segments and non-loopback http before any network call", async () => {
  const c = client();
  await assert.rejects(c.entity("../admin", "x"), /invalid app/);
  await assert.rejects(c.get("hr-leave", "leaveRequest", "1;drop"), /invalid record id/);
  assert.equal(seen.length, 0);
  assert.throws(() => createClient({ apiUrl: "http://api.example.test", tokenUrl: `${base}/token`, clientId: "a", clientSecret: "b" }),
    /https/);
  assert.throws(() => createClient({ apiUrl: base, tokenUrl: "https://user:pw@idp.example.test/t", clientId: "a", clientSecret: "b" }),
    /credentials/);
});

test("HTTP errors surface the server error body without retry", async () => {
  const c = client();
  mode = "conflict";
  await assert.rejects(c.update("hr-leave", "leaveRequest", ID, 0, {}), (e) => e instanceof ApiError && e.status === 409 && e.body.code === "VERSION_CONFLICT");
  assert.equal(seen.filter((r) => r.method === "PUT").length, 1);
});

test("a write whose connection drops after sending is COMMIT_OUTCOME_UNKNOWN and is not retried", async () => {
  const c = client();
  mode = "drop";
  await assert.rejects(c.create("hr-leave", "leaveRequest", { reason: "x" }), OutcomeUnknownError);
  assert.equal(seen.filter((r) => r.method === "POST" && r.url !== "/token").length, 1);
});

test("a dropped read is a plain failure, not an unknown outcome", async () => {
  const c = client();
  mode = "drop";
  await assert.rejects(c.apps(), (e) => !(e instanceof OutcomeUnknownError) && /unreachable/.test(e.message));
});

test("CLI prints JSON and never prints the secret", async () => {
  const c = client();
  const lines = [];
  await run(["get", "hr-leave", "leaveRequest", ID], c, (line) => lines.push(line));
  assert.deepEqual(JSON.parse(lines[0]), { ok: true });
  assert.ok(!lines.join("").includes(SECRET));
  await assert.rejects(run(["create", "hr-leave", "leaveRequest"], c, () => {}), /--data is required/);
  await assert.rejects(run(["bogus"], c, () => {}), /usage/);
});
