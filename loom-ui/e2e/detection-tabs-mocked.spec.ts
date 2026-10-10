import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the Detection screen's three tabs — no running Loom backend.
 *
 * `help-hints-mocked.spec.ts` checks the hint's link target per tab. This spec checks the other
 * half of the switch: that each tab mounts its own panel (and only its own), that the hint and the
 * panel move together, and that going back to Faces restores both rather than leaving the last
 * tab's hint behind.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

async function openDetection(page: Page) {
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await page.getByRole("button", { name: "Detection", exact: true }).first().click();
}

/** Which of the three panels is mounted, read off the search box each one owns. */
async function expectPanel(page: Page, panel: "faces" | "objects" | "llm") {
  await expect(page.getByTestId("facedetection-search")).toHaveCount(panel === "faces" ? 1 : 0);
  await expect(page.getByTestId("objectdetection-search")).toHaveCount(panel === "objects" ? 1 : 0);
  await expect(page.getByTestId("llmdetection-search")).toHaveCount(panel === "llm" ? 1 : 0);
}

async function expectOnlyHint(page: Page, topic: "detection.faces" | "detection.results") {
  const other = topic === "detection.faces" ? "detection.results" : "detection.faces";
  await expect(page.getByTestId(`help-hint-${topic}`)).toHaveCount(1);
  await expect(page.getByTestId(`help-hint-${other}`)).toHaveCount(0);
}

test.describe("Detection tabs – mocked e2e", () => {
  test("each tab mounts its own panel and carries its own help topic, and Faces comes back intact", async ({ page }) => {
    await openDetection(page);

    const faces = page.getByRole("tab", { name: "Faces", exact: true });
    const objects = page.getByRole("tab", { name: "Objects", exact: true });
    const llm = page.getByRole("tab", { name: "LLM", exact: true });

    await expect(faces).toHaveAttribute("aria-selected", "true", { timeout: 10_000 });
    await expectPanel(page, "faces");
    await expectOnlyHint(page, "detection.faces");

    await objects.click();
    await expect(objects).toHaveAttribute("aria-selected", "true");
    await expectPanel(page, "objects");
    await expectOnlyHint(page, "detection.results");
    // The objects panel finished its own load: the empty state, not the face screen's.
    await expect(page.getByTestId("objectdetection-empty")).toBeVisible();

    await llm.click();
    await expect(llm).toHaveAttribute("aria-selected", "true");
    await expectPanel(page, "llm");
    await expectOnlyHint(page, "detection.results");

    await faces.click();
    await expect(faces).toHaveAttribute("aria-selected", "true");
    await expectPanel(page, "faces");
    await expectOnlyHint(page, "detection.faces");
    await expect(page.getByTestId("facedetection-switcher")).toBeVisible();
  });
});
