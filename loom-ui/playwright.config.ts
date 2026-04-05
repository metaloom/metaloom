import { defineConfig } from "@playwright/test";

const vitePort = Number(process.env.VITE_PORT ?? 3000);

export default defineConfig({
  testDir: "./e2e",
  timeout: 30_000,
  retries: 0,
  use: {
    baseURL: `http://localhost:${vitePort}`,
    headless: true,
  },
  webServer: {
    command: `npx vite --port ${vitePort}`,
    url: `http://localhost:${vitePort}`,
    reuseExistingServer: !process.env.CI,
    timeout: 30_000,
    // VITE_* env vars are inherited from the parent process,
    // so VITE_API_BASE_URL set on the playwright invocation
    // automatically propagates to the Vite dev server.
  },
  projects: [
    {
      name: "chromium",
      use: { browserName: "chromium" },
    },
  ],
});
