import { test, expect, Page, Route } from "@playwright/test";

/**
 * The per-chat skills popover: what it says when there is nothing to activate, when the list
 * could not be loaded, and that its manage button leads to the skills screen.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function skill(name: string, enabled: boolean) {
  return {
    uuid: `skill-${name}`,
    name,
    description: `${name} description`,
    content: `# ${name}`,
    enabled,
    published: false,
    status: { creator: { uuid: ME_UUID } },
  };
}

async function installMocks(page: Page, skills: { status?: number; data?: unknown[] }) {
  const skillListUrls: string[] = [];
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));
  await page.route(/\/api\/v1\/skills(\?|$)/, route => {
    skillListUrls.push(route.request().url());
    if (skills.status && skills.status >= 400) return json(route, { message: "boom" }, skills.status);
    return json(route, { data: skills.data ?? [] });
  });
  return { skillListUrls };
}

async function login(page: Page) {
  await page.goto("/");
  if (await page.getByPlaceholder("Username").count() === 0) return;
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

async function openPanel(page: Page) {
  await page.getByTestId("chat-skills-button").click();
  const panel = page.getByTestId("chat-skills-panel");
  await expect(panel).toBeVisible({ timeout: 10_000 });
  return panel;
}

test.describe("Chat skills panel – mocked", () => {
  test("with no skills it says there are none yet", async ({ page }) => {
    await installMocks(page, { data: [] });
    await login(page);
    const panel = await openPanel(page);

    await expect(panel.getByTestId("chat-skills-empty")).toHaveText("No skills yet — create one under Skills");
    await expect(panel.getByTestId("chat-skills-error")).toHaveCount(0);
    await expect(panel.getByTestId(/^skill-toggle-/)).toHaveCount(0);
  });

  test("only enabled skills are offered for activation", async ({ page }) => {
    await installMocks(page, { data: [skill("dormant", false), skill("active-one", true)] });
    await login(page);
    const panel = await openPanel(page);

    await expect(panel.getByTestId("skill-toggle-active-one")).toBeVisible();
    await expect(panel.getByTestId("skill-toggle-dormant")).toHaveCount(0);
  });

  test("a failed load says the skills could not be loaded, not that there are none", async ({ page }) => {
    const { skillListUrls } = await installMocks(page, { status: 500 });
    await login(page);
    const panel = await openPanel(page);

    await expect.poll(() => skillListUrls.length).toBeGreaterThan(0);
    await expect(panel.getByTestId("chat-skills-error")).toHaveText("The skills could not be loaded");
    await expect(panel.getByTestId("chat-skills-empty")).toHaveCount(0);
  });

  test("the manage button closes the popover and opens the skills screen", async ({ page }) => {
    await installMocks(page, { data: [skill("helper", true)] });
    await login(page);
    const panel = await openPanel(page);

    await panel.getByRole("button", { name: "Manage skills", exact: true }).click();

    await expect(page).toHaveURL(/\/skills$/);
    await expect(page.getByTestId("skills-view")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("chat-skills-panel")).toHaveCount(0);
  });
});
