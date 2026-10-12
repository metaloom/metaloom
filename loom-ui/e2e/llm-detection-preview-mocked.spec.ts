import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the Detection screen's LLM tab, which is a preview with sample data.
 *
 * It used to show fixed prompts and results ("gpt-4o", asset ids a1-a7) as if they were the
 * server's, with Edit/Run buttons that had no handler and a create dialog whose prompts were never
 * saved. What is pinned here: the screen says it is a preview, and nothing on it pretends to act.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

async function openLlmTab(page: Page) {
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await page.getByRole("button", { name: "Detection", exact: true }).first().click();
  await page.getByRole("tab", { name: "LLM", exact: true }).click();
}

test.describe("LLM detection preview – mocked e2e", () => {
  test("the tab says it shows sample data and every action is disabled", async ({ page }) => {
    await openLlmTab(page);

    const notice = page.getByTestId("llmdetection-preview-notice");
    await expect(notice).toBeVisible({ timeout: 10_000 });
    await expect(notice).toContainText("Preview — sample data, not connected to a backend");

    await expect(page.getByTestId("llmdetection-new-prompt")).toBeDisabled();
    const prompts = page.getByTestId("llmdetection-prompt");
    await expect(prompts.first()).toBeVisible();
    const count = await prompts.count();
    expect(count).toBeGreaterThan(0);
    await expect(page.getByTestId("llmdetection-edit")).toHaveCount(count);
    await expect(page.getByTestId("llmdetection-run")).toHaveCount(count);
    for (let i = 0; i < count; i++) {
      await expect(page.getByTestId("llmdetection-edit").nth(i)).toBeDisabled();
      await expect(page.getByTestId("llmdetection-run").nth(i)).toBeDisabled();
    }

    // The tooltip explains why, on the live wrapper around the disabled button.
    await page.getByTestId("llmdetection-new-prompt").locator("..").hover();
    await expect(page.getByRole("tooltip")).toContainText("Not available in the preview");

    // No create dialog can be opened.
    await expect(page.getByRole("dialog")).toHaveCount(0);
  });

  test("the results are translated rather than raw keys or raw status values", async ({ page }) => {
    await openLlmTab(page);
    await page.getByText("Results", { exact: true }).click();
    const results = page.getByTestId("llmdetection-result");
    await expect(results.first()).toBeVisible({ timeout: 10_000 });
    await expect(results.first()).toContainText("87 tokens");
    await expect(page.getByText("Success", { exact: true }).first()).toBeVisible();
    await expect(page.getByText(/llmDetection\./)).toHaveCount(0);
  });

  test("the effort chip is translated, not printed as an i18n key", async ({ page }) => {
    await openLlmTab(page);
    await expect(page.getByTestId("llmdetection-prompt").first()).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText("Medium effort", { exact: true })).toBeVisible();
    await expect(page.getByText(/llmDetection\./)).toHaveCount(0);
  });
});
