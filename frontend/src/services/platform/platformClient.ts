// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * Browser client for the Platform API v1 through the Portal BFF (same origin, session cookie,
 * CSRF header on writes). It never talks to the database or to the backend directly, and it only
 * builds paths from validated identifiers.
 */

export type FieldSchema = {
  type: "string" | "integer" | "number" | "boolean";
  title?: string;
  description?: string;
  enum?: unknown[];
  maxLength?: number;
  format?: string;
};

export type EntityInfo = {
  app: string;
  key: string;
  name: Record<string, string>;
  schema: {
    required?: string[];
    properties: Record<string, FieldSchema>;
  };
  searchFields: string[];
  allowedOperations: Partial<Record<"read" | "create" | "update" | "delete", "all" | "own">>;
};

export type AppInfo = {
  id: string;
  version: string;
  name: Record<string, string>;
  callerRoles: string[];
  entities: { key: string; name: Record<string, string> }[];
};

export type PlatformRecord = {
  id: string;
  data: Record<string, unknown>;
  version: number;
  createdAt: string;
  updatedAt: string;
};

export type RecordPage = { items: PlatformRecord[]; total: number; limit: number; offset: number };

export type ApiError = { status: number; code: string; message: string; errors: Record<string, string> };

export type Result<T> = { ok: true; status: number; body: T } | { ok: false; status: number; error?: ApiError };

const APP = /^[a-z][a-z0-9-]{0,62}$/;
const ENTITY = /^[a-z][a-zA-Z0-9]{0,62}$/;
const ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function recordsPath(app: string, entity: string, id?: string) {
  if (!APP.test(app) || !ENTITY.test(entity) || (id !== undefined && !ID.test(id))) {
    throw new Error("Invalid Platform route.");
  }
  return `/api/v1/apps/${app}/entities/${entity}${id === undefined ? "" : `/records/${id}`}`;
}

export class PlatformClient {
  constructor(
    private readonly csrfToken: string,
    private readonly onExpired: () => void,
    private readonly fetcher: typeof fetch = (...args) => fetch(...args),
  ) {
    if (!csrfToken.trim()) throw new Error("BFF CSRF token is required.");
  }

  applications() {
    return this.call<AppInfo[]>("GET", "/api/v1/apps");
  }

  entity(app: string, entity: string) {
    return this.call<EntityInfo>("GET", recordsPath(app, entity));
  }

  search(app: string, entity: string, query: { q?: string; limit: number; offset: number }) {
    const params = new URLSearchParams({ limit: String(query.limit), offset: String(query.offset) });
    if (query.q) params.set("q", query.q);
    return this.call<RecordPage>("GET", `${recordsPath(app, entity)}/records?${params}`);
  }

  create(app: string, entity: string, data: Record<string, unknown>) {
    return this.call<PlatformRecord>("POST", `${recordsPath(app, entity)}/records`, { data });
  }

  update(app: string, entity: string, id: string, expectedVersion: number, data: Record<string, unknown>) {
    return this.call<PlatformRecord>("PUT", recordsPath(app, entity, id), { expectedVersion, data });
  }

  remove(app: string, entity: string, id: string, expectedVersion: number) {
    return this.call<undefined>("DELETE", `${recordsPath(app, entity, id)}?expectedVersion=${expectedVersion}`);
  }

  private async call<T>(method: "GET" | "POST" | "PUT" | "DELETE", path: string, body?: unknown): Promise<Result<T>> {
    const write = method !== "GET";
    const response = await this.fetcher(`/bff${path}`, {
      method,
      credentials: "same-origin",
      mode: "same-origin",
      redirect: "error",
      cache: "no-store",
      headers: {
        Accept: "application/json",
        ...(write ? { "X-CSRF-TOKEN": this.csrfToken } : {}),
        ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    if (response.status === 401) this.onExpired();
    const text = await response.text();
    let parsed: unknown;
    try {
      parsed = text ? JSON.parse(text) : undefined;
    } catch {
      parsed = undefined;
    }
    if (response.ok) return { ok: true, status: response.status, body: parsed as T };
    return { ok: false, status: response.status, error: parsed as ApiError | undefined };
  }
}
