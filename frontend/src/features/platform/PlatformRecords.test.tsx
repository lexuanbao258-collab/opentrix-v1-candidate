// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0

import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { PlatformClient, type EntityInfo } from "../../services/platform/platformClient";
import { PlatformRecords } from "./PlatformRecords";

const ENTITY: EntityInfo = {
  app: "it-helpdesk",
  key: "category",
  name: { vi: "Danh mục dịch vụ" },
  schema: {
    required: ["code", "name", "active"],
    properties: {
      code: { type: "string", title: "Code" },
      name: { type: "string", title: "Name" },
      active: { type: "boolean", title: "Active" },
    },
  },
  searchFields: ["code", "name"],
  allowedOperations: { read: "all", create: "all", update: "all", delete: "all" },
};

function renderWith(fetcher: typeof fetch) {
  const client = new PlatformClient("csrf-1", () => {}, fetcher);
  render(
    <MemoryRouter initialEntries={["/platform/it-helpdesk/category"]}>
      <Routes>
        <Route path="/platform/:app/:entity" element={<PlatformRecords client={client} />} />
      </Routes>
    </MemoryRouter>,
  );
}

function json(status: number, body?: unknown) {
  return new Response(body === undefined ? null : JSON.stringify(body), { status });
}

describe("PlatformRecords", () => {
  it("renders schema-driven fields, creates through the API and shows field errors", async () => {
    const calls: [string, RequestInit][] = [];
    const fetcher = vi.fn(async (url: string, init: RequestInit) => {
      calls.push([url, init]);
      if (url.endsWith("/entities/category")) return json(200, ENTITY);
      if (init.method === "POST" && calls.filter(([, i]) => i.method === "POST").length === 1) {
        return json(400, { status: 400, code: "VALIDATION_FAILED", message: "Record is invalid", errors: { "/code": "pattern" } });
      }
      if (init.method === "POST") return json(201, { id: "x", data: {}, version: 0 });
      return json(200, { items: [{ id: "00000000-0000-4000-8000-000000000001", data: { code: "NET", name: "Network", active: true }, version: 2 }], total: 1, limit: 10, offset: 0 });
    }) as unknown as typeof fetch;
    renderWith(fetcher);

    expect(await screen.findByRole("heading", { name: "Danh mục dịch vụ" })).toBeInTheDocument();
    expect(await screen.findByText("Network")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Thêm mới" }));
    await userEvent.type(screen.getByLabelText("Code *"), "bad code");
    await userEvent.type(screen.getByLabelText("Name *"), "Phần mềm");
    await userEvent.click(screen.getByLabelText("Active"));
    await userEvent.click(screen.getByRole("button", { name: "Lưu" }));
    expect(await screen.findByText("pattern")).toBeInTheDocument();

    const post = calls.find(([, init]) => init.method === "POST")!;
    expect(post[0]).toBe("/bff/api/v1/apps/it-helpdesk/entities/category/records");
    expect(JSON.parse(String(post[1].body))).toEqual({ data: { code: "bad code", name: "Phần mềm", active: true } });

    await userEvent.click(screen.getByRole("button", { name: "Lưu" }));
    expect(await screen.findByText("Đã tạo bản ghi.")).toBeInTheDocument();
  });

  it("sends the record version on update and reloads after a conflict", async () => {
    const methods: string[] = [];
    const fetcher = vi.fn(async (url: string, init: RequestInit) => {
      methods.push(`${init.method} ${url}`);
      if (url.endsWith("/entities/category")) return json(200, ENTITY);
      if (init.method === "PUT") return json(409, { status: 409, code: "VERSION_CONFLICT", message: "changed", errors: {} });
      return json(200, { items: [{ id: "00000000-0000-4000-8000-000000000001", data: { code: "NET", name: "Network", active: true }, version: 2 }], total: 1, limit: 10, offset: 0 });
    }) as unknown as typeof fetch;
    renderWith(fetcher);

    await userEvent.click(await screen.findByRole("button", { name: "Sửa" }));
    expect(screen.getByLabelText("Name *")).toHaveValue("Network");
    await userEvent.click(screen.getByRole("button", { name: "Lưu" }));

    expect(await screen.findByText(/đã bị người khác thay đổi/)).toBeInTheDocument();
    const put = (fetcher as unknown as ReturnType<typeof vi.fn>).mock.calls.find(([, init]) => init.method === "PUT")!;
    expect(JSON.parse(String(put[1].body)).expectedVersion).toBe(2);
    await waitFor(() => expect(methods.filter((m) => m.startsWith("GET") && m.includes("/records?")).length).toBe(2));
  });

  it("hides write actions the server does not grant", async () => {
    const readOnly = { ...ENTITY, allowedOperations: { read: "all" as const } };
    renderWith(vi.fn(async (url: string) =>
      url.endsWith("/entities/category")
        ? json(200, readOnly)
        : json(200, { items: [{ id: "00000000-0000-4000-8000-000000000001", data: { code: "NET", name: "Network", active: true }, version: 0 }], total: 1, limit: 10, offset: 0 }),
    ) as unknown as typeof fetch);
    expect(await screen.findByText("Network")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Thêm mới" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Sửa" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Xóa" })).not.toBeInTheDocument();
  });
});
