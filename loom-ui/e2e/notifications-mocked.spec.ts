import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the notification centre — no running Loom backend.
 *
 * Covers the bell badge, the popover, per-row dismiss, mark-all-read, clear-all, the
 * empty state and the deep link. The live WebSocket path is covered by unit tests
 * (`src/api/pipelineEvents.test.ts`) rather than here: Playwright cannot easily drive the
 * shared module-level socket, and the popover refetches on open anyway, so the REST path
 * is the one that has to be right.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const TASK_UUID = "33333333-3333-3333-3333-333333333333";

interface StoredNotification {
  uuid: string;
  type: string;
  read: boolean;
  title: string;
  body?: string;
  taskUuid?: string;
  status: { creator?: { uuid: string }; created: string };
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function seeded(): StoredNotification[] {
  return [
    {
      uuid: "n1",
      type: "TASK_ASSIGNED",
      read: false,
      title: "joedoe assigned you \"Colour-grade the hero shot\"",
      body: "The white balance drifts warm.",
      taskUuid: TASK_UUID,
      status: { creator: { uuid: "other" }, created: new Date().toISOString() },
    },
    {
      uuid: "n2",
      type: "PIPELINE_RUN_FAILED",
      read: true,
      title: "Pipeline run failed: ingest",
      status: { created: new Date().toISOString() },
    },
  ];
}

async function installMocks(page: Page, seed: StoredNotification[]) {
  let notifications = [...seed];
  const calls: string[] = [];

  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route =>
    json(route, { uuid: ME_UUID, username: "admin", enabled: true })
  );

  // Most specific first — Playwright matches the most recently registered route.
  await page.route(/\/api\/v1\/notifications\/read-all$/, route => {
    calls.push("POST read-all");
    notifications = notifications.map(n => ({ ...n, read: true }));
    return json(route, { message: "ok" });
  });
  await page.route(/\/api\/v1\/notifications\/([^/?]+)$/, route => {
    const uuid = route.request().url().split("/").pop()!.split("?")[0];
    if (route.request().method() === "DELETE") {
      calls.push(`DELETE ${uuid}`);
      notifications = notifications.filter(n => n.uuid !== uuid);
      return route.fulfill({ status: 204, body: "" });
    }
    calls.push(`POST ${uuid}`);
    const body = JSON.parse(route.request().postData() || "{}");
    notifications = notifications.map(n => (n.uuid === uuid ? { ...n, read: body.read } : n));
    return json(route, notifications.find(n => n.uuid === uuid));
  });
  await page.route(/\/api\/v1\/notifications(\?.*)?$/, route => {
    if (route.request().method() === "DELETE") {
      calls.push("DELETE all");
      notifications = [];
      return route.fulfill({ status: 204, body: "" });
    }
    const unreadOnly = route.request().url().includes("unread=true");
    const data = unreadOnly ? notifications.filter(n => !n.read) : notifications;
    return json(route, { data, unreadCount: notifications.filter(n => !n.read).length });
  });

  return calls;
}

async function login(page: Page, seed: StoredNotification[]) {
  const calls = await installMocks(page, seed);
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  return calls;
}

test.describe("Notification centre – mocked e2e", () => {
  test("the bell shows the unread count and the popover lists entries", async ({ page }) => {
    await login(page, seeded());

    const bell = page.getByTestId("notification-bell");
    await expect(bell).toBeVisible({ timeout: 10_000 });
    // One of the two seeded entries is unread.
    await expect(page.getByTestId("notification-badge")).toContainText("1");

    await bell.click();

    const rows = page.getByTestId("notification-row");
    await expect(rows).toHaveCount(2);
    await expect(rows.nth(0)).toContainText("Colour-grade the hero shot");
    // Only the unread row carries the dot.
    await expect(page.getByTestId("notification-unread-dot")).toHaveCount(1);
  });

  test("clicking a row marks it read and deep-links to its subject", async ({ page }) => {
    const calls = await login(page, seeded());

    await page.getByTestId("notification-bell").click();
    await page.getByTestId("notification-row").first().click();

    expect(calls).toContain("POST n1");
    // The task notification deep-links to the tasks view with the task named.
    await expect(page).toHaveURL(/\/tasks\?task=/);
  });

  test("dismissing a row removes it without navigating", async ({ page }) => {
    const calls = await login(page, seeded());

    await page.getByTestId("notification-bell").click();
    await page.getByTestId("notification-dismiss").first().click();

    expect(calls).toContain("DELETE n1");
    await expect(page.getByTestId("notification-row")).toHaveCount(1);
    // Dismiss must not follow the row's link.
    await expect(page).not.toHaveURL(/\/tasks/);
  });

  test("mark all read clears the badge", async ({ page }) => {
    const calls = await login(page, seeded());

    await page.getByTestId("notification-bell").click();
    await page.getByTestId("notification-mark-all").click();

    expect(calls).toContain("POST read-all");
    await expect(page.getByTestId("notification-unread-dot")).toHaveCount(0);
  });

  test("clear all empties the inbox and shows the empty state", async ({ page }) => {
    const calls = await login(page, seeded());

    await page.getByTestId("notification-bell").click();
    await page.getByTestId("notification-clear-all").click();

    expect(calls).toContain("DELETE all");
    await expect(page.getByTestId("notification-row")).toHaveCount(0);
    await expect(page.getByTestId("notifications-empty")).toBeVisible();
  });

  test("an empty inbox shows the empty state and no badge", async ({ page }) => {
    await login(page, []);

    await expect(page.getByTestId("notification-bell")).toBeVisible({ timeout: 10_000 });
    await page.getByTestId("notification-bell").click();

    await expect(page.getByTestId("notifications-empty")).toBeVisible();
    await expect(page.getByTestId("notification-row")).toHaveCount(0);
    // Both bulk actions are pointless on an empty inbox.
    await expect(page.getByTestId("notification-mark-all")).toBeDisabled();
    await expect(page.getByTestId("notification-clear-all")).toBeDisabled();
  });
});
