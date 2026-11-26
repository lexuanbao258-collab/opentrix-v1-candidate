// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it, vi } from "vitest";
import { PlatformClient, recordsPath } from "./platformClient";

const ID = "00000000-0000-4000-8000-000000000001";

function fakeFetch(status: number, body?: unknown) {
  return vi.fn(async () => new Response(body === undefined ? null : JSON.stringify(body), { status }));
}

describe("PlatformClient", () => {
  it("only builds paths from validated identifiers", () => {
    expect(recordsPath("it-helpdesk", "category", ID)).toBe(
      `/api/v1/apps/it-helpdesk/entities/category/records/${ID}`,
    );
    for (const [app, entity, id] of [
      ["../admin", "category", undefined],
      ["it-helpdesk", "cat/egory", undefined],
      ["it-helpdesk", "category", "1;DROP"],
    ] as const) {
      expect(() => recordsPath(app, entity, id)).toThrow("Invalid Platform route.");
    }
  });

  it("sends writes same-origin through the BFF with the CSRF header and no bearer token", async () => {
    const fetcher = fakeFetch(200, { id: ID, version: 1 });
    const client = new PlatformClient("csrf-1", () => {}, fetcher);
    await client.update("it-helpdesk", "category", ID, 0, { code: "NET" });

    const [url, init] = fetcher.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe(`/bff/api/v1/apps/it-helpdesk/entities/category/records/${ID}`);
    expect(init.method).toBe("PUT");
    expect(init.credentials).toBe("same-origin");
    expect(init.redirect).toBe("error");
    expect(init.headers).toMatchObject({ "X-CSRF-TOKEN": "csrf-1", "Content-Type": "application/json" });
    expect(JSON.stringify(init.headers)).not.toContain("Authorization");
    expect(JSON.parse(String(init.body))).toEqual({ expectedVersion: 0, data: { code: "NET" } });
  });

  it("encodes search parameters and omits CSRF on reads", async () => {
    const fetcher = fakeFetch(200, { items: [], total: 0, limit: 10, offset: 0 });
    await new PlatformClient("csrf-1", () => {}, fetcher).search("it-helpdesk", "category", {
      q: "a&limit=1000",
      limit: 10,
      offset: 20,
    });
    const [url, init] = fetcher.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("/bff/api/v1/apps/it-helpdesk/entities/category/records?limit=10&offset=20&q=a%26limit%3D1000");
    expect(init.headers).not.toHaveProperty("X-CSRF-TOKEN");
  });

  it("reports API errors and session expiry", async () => {
    const expired = vi.fn();
    const conflict = await new PlatformClient("csrf-1", expired, fakeFetch(409, {
      status: 409, code: "VERSION_CONFLICT", message: "changed", errors: {},
    })).remove("it-helpdesk", "category", ID, 3);
    expect(conflict).toMatchObject({ ok: false, status: 409, error: { code: "VERSION_CONFLICT" } });
    expect(expired).not.toHaveBeenCalled();

    await new PlatformClient("csrf-1", expired, fakeFetch(401)).applications();
    expect(expired).toHaveBeenCalledOnce();
  });
});
