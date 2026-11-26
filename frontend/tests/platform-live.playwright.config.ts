// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0

import { defineConfig } from "@playwright/test";

// LIVE run only: start the compose demo first, then pass DEMO_*_PASSWORD from its env file.
// The demo ingress uses Caddy's local CA, hence ignoreHTTPSErrors for this disposable origin only.
export default defineConfig({
  testDir: ".",
  testMatch: "platform-live.spec.ts",
  workers: 1,
  reporter: "list",
  outputDir: "../playwright-report/platform-live",
  use: {
    baseURL: "https://localhost:8443",
    ignoreHTTPSErrors: true,
    serviceWorkers: "block",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
});
