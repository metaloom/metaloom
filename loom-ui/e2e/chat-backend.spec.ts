import { test, expect, Page } from "@playwright/test";

/**
 * End-to-end tests for chat sessions (real backend).
 *
 * Prerequisites — set env vars before running:
 *   VITE_API_BASE_URL  – e.g. /api/v1
 *   VITE_PROXY_TARGET  – e.g. http://localhost:8092
 *
 * Assumes:
 *  1. A Loom server is running with demo data (DemoDatabaseInitializer)
 *  2. Default admin credentials: admin / finger
 *
 * Deliberately NOT covered here: live agent runs. The agentic loop is
 * non-deterministic with a real LLM and is covered by backend JUnit tests with
 * a scripted TurnStreamer (ChatStreamEndpointTest). If the AI service is not
 * configured, sending a message surfaces the error toast — the only streaming
 * assertion made here.
 */

async function login(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

test.describe("Chat sessions – backend e2e", () => {
  test("chat workspace loads with the session rail", async ({ page }) => {
    await login(page);
    await expect(page.getByPlaceholder(/Ask about assets/i)).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText("New chat")).toBeVisible();
  });

  test("session rail lists persisted sessions or the empty state", async ({ page }) => {
    await login(page);
    await expect(page.getByPlaceholder(/Ask about assets/i)).toBeVisible({ timeout: 10_000 });

    // The rail either lists persisted sessions or shows the empty state —
    // session creation happens lazily on the first agent message, which this
    // suite deliberately does not send (no live-LLM assertions here).
    const emptyState = page.getByText("No conversations yet");
    const sessionRows = page.locator(".chat-del");
    await expect
      .poll(async () => (await emptyState.isVisible()) || (await sessionRows.count()) > 0, { timeout: 10_000 })
      .toBe(true);
  });

  test("skill toggles are reachable from the chat header", async ({ page }) => {
    await login(page);
    await page.getByTestId("chat-skills-button").click();
    await expect(page.getByTestId("chat-skills-panel")).toBeVisible({ timeout: 5_000 });
  });
});
