import { defineConfig } from "vitest/config";

// Unit-test runner for the api/*.ts client functions. The e2e suite lives
// under ./e2e and is driven by Playwright (excluded here).
export default defineConfig({
  server: {
    fs: {
      // `nodeColors.test.ts` imports the website's standalone pipeline editor with `?raw` to assert
      // that its copy of the category palette still matches this one. That file lives outside
      // loom-ui, and Vite refuses to read outside its root unless the parent is allowed here.
      allow: [".."],
    },
  },
  test: {
    environment: "node",
    include: ["src/**/*.{test,spec}.{ts,tsx}"],
    exclude: ["e2e/**", "node_modules/**"],
  },
});
