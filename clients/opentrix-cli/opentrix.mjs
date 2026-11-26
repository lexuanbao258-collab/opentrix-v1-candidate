#!/usr/bin/env node
// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * Minimal external client for the OpenTrix Platform API v1. It runs outside the Platform and outside the
 * Portal BFF: machine identity (OAuth2 client_credentials) -> bearer token -> public API route.
 * No dependencies (Node >= 22). No retries: a write whose connection fails after it was sent reports
 * COMMIT_OUTCOME_UNKNOWN and must be reconciled by reading, never blindly repeated.
 *
 * Environment: OPENTRIX_API_URL, OPENTRIX_TOKEN_URL, OPENTRIX_CLIENT_ID, OPENTRIX_CLIENT_SECRET,
 * optional OPENTRIX_CA_FILE (PEM, for a private CA such as the local Caddy CA).
 * The secret is read from the environment only (never from argv) and is never printed.
 */
import http from "node:http";
import https from "node:https";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const SEGMENT = /^[A-Za-z][A-Za-z0-9_-]{0,62}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MAX_RESPONSE = 2_000_000;

export class ApiError extends Error {
  constructor(status, body) {
    super(`HTTP ${status}${body?.code ? ` ${body.code}` : ""}`);
    this.status = status;
    this.body = body;
  }
}

/** The request may have reached the server and committed; the caller must reconcile by reading. */
export class OutcomeUnknownError extends Error {
  constructor() {
    super("COMMIT_OUTCOME_UNKNOWN: connection failed after the request was sent; do not retry blindly");
    this.code = "COMMIT_OUTCOME_UNKNOWN";
  }
}

function checkedUrl(value, name) {
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new Error(`${name} is not a valid URL`);
  }
  const loopback = ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname);
  if (!(url.protocol === "https:" || (url.protocol === "http:" && loopback)) || url.username || url.password || url.hash) {
    throw new Error(`${name} must be https (http only for loopback) without credentials`);
  }
  return url;
}

function segment(value, label) {
  if (!SEGMENT.test(value)) throw new Error(`invalid ${label}`);
  return value;
}

function recordId(value) {
  if (!UUID.test(value)) throw new Error("invalid record id");
  return value;
}

export function createClient({ apiUrl, tokenUrl, clientId, clientSecret, caFile, timeoutMs = 15_000 }) {
  const api = checkedUrl(apiUrl, "OPENTRIX_API_URL");
  const token = checkedUrl(tokenUrl, "OPENTRIX_TOKEN_URL");
  if (!clientId || !clientSecret) throw new Error("client id and secret are required");
  const ca = caFile ? readFileSync(caFile) : undefined;
  let cached = { value: null, expiresAt: 0 };

  function send(url, { method, headers, body }) {
    const transport = url.protocol === "https:" ? https : http;
    return new Promise((resolve, reject) => {
      let sent = false;
      const req = transport.request(url, { method, headers, ca, timeout: timeoutMs }, (res) => {
        const chunks = [];
        let size = 0;
        res.on("data", (chunk) => {
          size += chunk.length;
          if (size > MAX_RESPONSE) {
            req.destroy(new Error("response too large"));
            return;
          }
          chunks.push(chunk);
        });
        res.on("end", () => resolve({ status: res.statusCode ?? 0, text: Buffer.concat(chunks).toString("utf8") }));
        res.on("error", (error) => reject(Object.assign(error, { sent: true })));
      });
      // 'finish': the whole request was handed to the OS. A refused connection never gets here.
      req.on("finish", () => {
        sent = true;
      });
      req.on("timeout", () => req.destroy(new Error("timeout")));
      req.on("error", (error) => reject(Object.assign(error, { sent })));
      req.end(body);
    });
  }

  async function accessToken() {
    if (cached.value && Date.now() < cached.expiresAt - 10_000) return cached.value;
    const basic = Buffer.from(`${encodeURIComponent(clientId)}:${encodeURIComponent(clientSecret)}`).toString("base64");
    const response = await send(token, {
      method: "POST",
      headers: { Authorization: `Basic ${basic}`, "Content-Type": "application/x-www-form-urlencoded", Accept: "application/json" },
      body: "grant_type=client_credentials",
    }).catch(() => {
      throw new Error("token endpoint unreachable");
    });
    let parsed;
    try {
      parsed = JSON.parse(response.text);
    } catch {
      /* non-JSON token response */
    }
    if (response.status !== 200 || typeof parsed?.access_token !== "string") {
      throw new Error(`token request failed (HTTP ${response.status})`);
    }
    cached = { value: parsed.access_token, expiresAt: Date.now() + (Number(parsed.expires_in) || 60) * 1000 };
    return cached.value;
  }

  async function call(method, path, body) {
    if (!path.startsWith("/api/v1/apps")) throw new Error("route outside the Platform API");
    const url = new URL(path, api);
    if (url.origin !== api.origin) throw new Error("route outside the configured API origin");
    const payload = body === undefined ? undefined : JSON.stringify(body);
    const headers = {
      Authorization: `Bearer ${await accessToken()}`,
      Accept: "application/json",
      ...(payload === undefined ? {} : { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(payload) }),
    };
    let response;
    try {
      response = await send(url, { method, headers, body: payload });
    } catch (error) {
      if (method !== "GET" && error.sent) throw new OutcomeUnknownError();
      throw new Error(`API unreachable: ${error.message}`);
    }
    let parsed;
    try {
      parsed = response.text ? JSON.parse(response.text) : undefined;
    } catch {
      /* non-JSON body */
    }
    if (response.status >= 200 && response.status < 300) return parsed;
    if (response.status === 503 && method !== "GET") throw new OutcomeUnknownError();
    throw new ApiError(response.status, parsed);
  }

  const base = (app, entity) => `/api/v1/apps/${segment(app, "app")}/entities/${segment(entity, "entity")}`;
  const methods = {
    apps: () => call("GET", "/api/v1/apps"),
    entity: (app, entity) => call("GET", base(app, entity)),
    list: (app, entity, { q, limit = 20, offset = 0 } = {}) => {
      const params = new URLSearchParams({ limit: String(limit), offset: String(offset) });
      if (q) params.set("q", q);
      return call("GET", `${base(app, entity)}/records?${params}`);
    },
    create: (app, entity, data) => call("POST", `${base(app, entity)}/records`, { data }),
    get: (app, entity, id) => call("GET", `${base(app, entity)}/records/${recordId(id)}`),
    update: (app, entity, id, expectedVersion, data) =>
      call("PUT", `${base(app, entity)}/records/${recordId(id)}`, { expectedVersion, data }),
    remove: (app, entity, id, expectedVersion) =>
      call("DELETE", `${base(app, entity)}/records/${recordId(id)}?expectedVersion=${Number(expectedVersion)}`),
    workflow: (app, entity, id) => call("GET", `${base(app, entity)}/records/${recordId(id)}/workflow`),
    transition: (app, entity, id, transitionId, expectedVersion) =>
      call("POST", `${base(app, entity)}/records/${recordId(id)}/transitions/${segment(transitionId, "transition")}`, { expectedVersion }),
    history: (app, entity, id) => call("GET", `${base(app, entity)}/records/${recordId(id)}/history`),
    audit: (app, entity, { recordId: id, limit = 20, offset = 0 } = {}) => {
      const params = new URLSearchParams({ limit: String(limit), offset: String(offset) });
      if (id) params.set("recordId", recordId(id));
      return call("GET", `${base(app, entity)}/audit?${params}`);
    },
  };
  // Validation errors reject the promise instead of throwing synchronously.
  return Object.fromEntries(Object.entries(methods).map(([name, fn]) => [name, async (...args) => fn(...args)]));
}

export function clientFromEnv(env = process.env) {
  return createClient({
    apiUrl: env.OPENTRIX_API_URL,
    tokenUrl: env.OPENTRIX_TOKEN_URL,
    clientId: env.OPENTRIX_CLIENT_ID,
    clientSecret: env.OPENTRIX_CLIENT_SECRET,
    caFile: env.OPENTRIX_CA_FILE,
  });
}

const USAGE = `usage: opentrix <command> [args]
  apps | entity <app> <entity> | list <app> <entity> [--q text] [--limit n] [--offset n]
  create <app> <entity> --data '{json}' | get <app> <entity> <id>
  update <app> <entity> <id> <expectedVersion> --data '{json}' | delete <app> <entity> <id> <expectedVersion>
  workflow <app> <entity> <id> | transition <app> <entity> <id> <transition> <expectedVersion>
  history <app> <entity> <id> | audit <app> <entity> [--record id]`;

function flag(args, name) {
  const index = args.indexOf(name);
  if (index < 0) return undefined;
  const [value] = args.splice(index, 2).slice(1);
  return value;
}

export async function run(argv, client = clientFromEnv(), out = console.log) {
  const args = [...argv];
  const data = flag(args, "--data");
  const q = flag(args, "--q");
  const limit = flag(args, "--limit");
  const offset = flag(args, "--offset");
  const record = flag(args, "--record");
  const [command, app, entity, id, extra, more] = args;
  const json = () => {
    if (data === undefined) throw new Error("--data is required");
    return JSON.parse(data);
  };
  const paging = { limit: limit === undefined ? 20 : Number(limit), offset: offset === undefined ? 0 : Number(offset) };
  const result = await ({
    apps: () => client.apps(),
    entity: () => client.entity(app, entity),
    list: () => client.list(app, entity, { q, ...paging }),
    create: () => client.create(app, entity, json()),
    get: () => client.get(app, entity, id),
    update: () => client.update(app, entity, id, Number(extra), json()),
    delete: () => client.remove(app, entity, id, Number(extra)),
    workflow: () => client.workflow(app, entity, id),
    transition: () => client.transition(app, entity, id, extra, Number(more)),
    history: () => client.history(app, entity, id),
    audit: () => client.audit(app, entity, { recordId: record, ...paging }),
  }[command] ?? (() => { throw new Error(USAGE); }))();
  out(JSON.stringify(result ?? { ok: true }, null, 2));
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  run(process.argv.slice(2)).catch((error) => {
    if (error instanceof ApiError) {
      console.error(JSON.stringify({ status: error.status, error: error.body ?? null }));
      process.exit(1);
    }
    console.error(error.message);
    process.exit(error instanceof OutcomeUnknownError ? 3 : 2);
  });
}
