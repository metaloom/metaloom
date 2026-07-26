import { test, expect, Page } from "@playwright/test";

/**
 * Mocked e2e for the first-run experience:
 *  - the prominent "Hello <username>" greeting shown in a fresh chat session
 *  - the shared feature-page empty states (icon + headline + explanation + CTA)
 *
 * No backend is required — every `/api/v1/**` call is intercepted and answers
 * with an empty list, which is exactly the state the empty views are built for.
 */

const USERNAME = "admin";

async function mockEmptyBackend(page: Page) {
  // Catch-all (lowest priority): everything is empty.
  await page.route("**/api/v1/**", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) })
  );

  await page.route("**/api/v1/login", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ token: "fake-jwt" }) })
  );

  await page.route("**/api/v1/me", route =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ uuid: "11111111-1111-1111-1111-111111111111", username: USERNAME, enabled: true }),
    })
  );
}

async function login(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill(USERNAME);
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

test.describe("First-run experience – mocked", () => {
  test("a new chat session greets the user by name", async ({ page }) => {
    await mockEmptyBackend(page);
    await login(page);

    // The chat is the landing page, so a fresh session is already active.
    const greeting = page.getByTestId("chat-greeting");
    await expect(greeting).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("chat-greeting-title")).toHaveText(`Hello ${USERNAME}`);

    // Sending a message replaces the greeting with the transcript.
    await page.getByPlaceholder(/ask/i).first().fill("hello");
    await page.keyboard.press("Enter");
    await expect(greeting).toBeHidden({ timeout: 10_000 });
  });

  test.describe("feature pages show an empty state with a create action", () => {
    const cases: Array<{ nav: RegExp; testId: string; hasAction: boolean }> = [
      { nav: /^assets$/i, testId: "assets-empty-state", hasAction: true },
      { nav: /^library$/i, testId: "library-empty-state", hasAction: true },
      { nav: /^collections$/i, testId: "collections-empty-state", hasAction: true },
      { nav: /^tags$/i, testId: "tags-empty-state", hasAction: true },
      // The Tasks nav item carries a badge, so its accessible name is not an exact match.
      { nav: /tasks/i, testId: "tasks-empty-state", hasAction: true },
      { nav: /^skills$/i, testId: "skills-empty-state", hasAction: true },
    ];

    for (const { nav, testId, hasAction } of cases) {
      test(`${testId} renders icon, text and CTA`, async ({ page }) => {
        await mockEmptyBackend(page);
        await login(page);

        await page.getByRole("button", { name: nav }).first().click();

        const empty = page.getByTestId(testId);
        await expect(empty).toBeVisible({ timeout: 10_000 });
        // Headline and explanatory sentence are both present.
        await expect(empty.locator("p, h6, .MuiTypography-root")).not.toHaveCount(0);
        if (hasAction) {
          await expect(page.getByTestId(`${testId}-action`)).toBeVisible();
        }
      });
    }
  });

  test("the assets empty state opens the upload dialog", async ({ page }) => {
    await mockEmptyBackend(page);
    await login(page);

    await page.getByRole("button", { name: /^assets$/i }).first().click();
    await page.getByTestId("assets-empty-state-action").click();

    await expect(page.getByRole("dialog")).toBeVisible({ timeout: 5_000 });
  });

  test("the collections empty state opens the create dialog", async ({ page }) => {
    await mockEmptyBackend(page);
    await login(page);

    await page.getByRole("button", { name: /^collections$/i }).first().click();
    await page.getByTestId("collections-empty-state-action").click();

    await expect(page.getByRole("dialog")).toBeVisible({ timeout: 5_000 });
  });
});
