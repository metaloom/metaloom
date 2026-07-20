import { defineConfig } from "vitest/config";

// Unit-test runner for the api/*.ts client functions. The e2e suite lives
// under ./e2e and is driven by Playwright (excluded here).
export default defineConfig({
  test: {
    environment: "node",
    include: ["src/**/*.{test,spec}.{ts,tsx}"],
    exclude: ["e2e/**", "node_modules/**"],
  },
});
