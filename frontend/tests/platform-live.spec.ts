// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0

import { expect, test, type Page } from "@playwright/test";

// LIVE acceptance against a running compose demo (Caddy -> BFF -> backend -> PostgreSQL, Keycloak login).
// Fails, never skips, when the stack or credentials are missing.
function credential(name: string) {
  const value = process.env[name];
  if (!value) throw new Error(`Missing ${name}; run against a started demo stack.`);
  return value;
}

async function login(page: Page, username: string, password: string) {
  await page.goto("/platform/it-helpdesk/category");
  await page.getByRole("link", { name: /Đăng nhập/ }).click();
  await page.locator("#username").fill(username);
  await page.locator("#password").fill(password);
  await page.locator("#kc-login").click();
  await page.waitForURL(/https:\/\/localhost:8443\//);
  await page.goto("/platform/it-helpdesk/category");
  await expect(page.getByRole("heading", { name: "Danh mục dịch vụ" })).toBeVisible();
}

const CODE = `E2E_${Date.now().toString(36).toUpperCase()}`;

test("admin manages the category catalog end to end through the real Platform API", async ({ page }) => {
  await login(page, "coordinator-demo", credential("DEMO_COORDINATOR_PASSWORD"));

  await page.getByRole("button", { name: "Thêm mới" }).click();
  await page.getByLabel("Code *").fill("bad code");
  await page.getByLabel("Name *").fill("Không hợp lệ");
  await page.getByRole("button", { name: "Lưu" }).click();
  await expect(page.getByRole("status")).toContainText("Record is invalid");
  await expect(page.locator(".platform-form small[role=alert]")).toBeVisible();

  await page.getByLabel("Code *").fill(CODE);
  await page.getByLabel("Name *").fill("Mạng nội bộ E2E");
  await page.getByLabel("Active").check();
  await page.getByRole("button", { name: "Lưu" }).click();
  await expect(page.getByRole("status")).toHaveText("Đã tạo bản ghi.");

  await page.getByLabel(/Tìm theo/).fill(CODE);
  await page.getByRole("button", { name: "Tìm" }).click();
  await expect(page.locator("caption")).toHaveText("1 bản ghi");
  const row = page.getByRole("row", { name: new RegExp(CODE) });
  await expect(row).toContainText("Mạng nội bộ E2E");

  await row.getByRole("button", { name: "Sửa" }).click();
  await page.getByLabel("Name *").fill("Mạng nội bộ E2E (đã sửa)");
  await page.getByRole("button", { name: "Lưu" }).click();
  await expect(page.getByRole("status")).toHaveText("Đã lưu thay đổi.");
  await expect(row).toContainText("(đã sửa)");

  page.once("dialog", (dialog) => dialog.accept());
  await row.getByRole("button", { name: "Xóa" }).click();
  await expect(page.getByRole("status")).toHaveText("Đã xóa bản ghi.");
  await expect(page.locator("caption")).toHaveText("0 bản ghi");
});

test("requester can read the catalog but the server refuses writes", async ({ page }) => {
  await login(page, "employee-demo", credential("DEMO_EMPLOYEE_PASSWORD"));
  await expect(page.getByRole("button", { name: "Thêm mới" })).toHaveCount(0);

  // Bypass the UI: a crafted same-origin write with a valid CSRF token is still rejected by the backend.
  const status = await page.evaluate(async () => {
    const session = await (await fetch("/bff/session")).json();
    const response = await fetch("/bff/api/v1/apps/it-helpdesk/entities/category/records", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": session.csrfToken },
      body: JSON.stringify({ data: { code: "HACK", name: "x", active: true } }),
    });
    return response.status;
  });
  expect(status).toBe(403);

  // Regression: the legacy Helpdesk route still works for the same session.
  const legacy = await page.evaluate(async () => (await fetch("/bff/api/v1/mvp/requests?limit=5")).status);
  expect(legacy).toBe(200);
});
