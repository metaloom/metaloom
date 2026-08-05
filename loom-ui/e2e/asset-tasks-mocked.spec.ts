import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the asset-detail Tasks tab — no running Loom backend required.
 * All `/api/v1/**` calls are intercepted via `page.route`, with a small in-memory
 * task store so list → create → assign round-trips against the asset-scoped routes:
 *   GET /api/v1/assets/:uuid/tasks
 *   POST/DELETE /api/v1/assets/:uuid/tasks/:taskUuid
 *   POST /api/v1/tasks
 *
 * The tab renders REAL TaskResponse fields only: title, description, uppercase
 * priority, taskStatus (PENDING | REJECTED | ACCEPTED | REVIEW) and dueDate.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const ASSET_UUID = "22222222-2222-2222-2222-222222222222";

interface StoredTask {
  uuid: string;
  title: string;
  description?: string;
  priority?: string;
  taskStatus?: string;
  dueDate?: string;
  assignees?: { userUuid?: string; groupUuid?: string; name?: string }[];
  status: { creator: { uuid: string }; created: string };
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function asset() {
  return {
    uuid: ASSET_UUID,
    file: { filename: "mock-asset.jpg", mimeType: "image/jpeg", size: 1024 },
    status: { creator: { uuid: ME_UUID } },
  };
}

function seededTask(): StoredTask {
  return {
    uuid: "44444444-4444-4444-4444-444444444444",
    title: "Review footage",
    description: "Check the intro sequence",
    priority: "HIGH",
    taskStatus: "PENDING",
    dueDate: "2026-08-01T12:00:00Z",
    assignees: [
      { userUuid: "55555555-5555-5555-5555-555555555555", name: "joedoe" },
      { groupUuid: "66666666-6666-6666-6666-666666666666", name: "editors" },
    ],
    status: { creator: { uuid: ME_UUID }, created: "2026-07-01T09:00:00Z" },
  };
}

async function installMocks(page: Page, seed: StoredTask[]) {
  const tasks: StoredTask[] = [...seed];
  const assigned = new Set(seed.map(t => t.uuid));
  let seq = 0;
  const calls: string[] = [];

  // Catch-all first (lowest priority) — empty collections for the many
  // list endpoints AssetDetail fans out to (reactions, transcripts, …).
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));

  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route =>
    json(route, { uuid: ME_UUID, username: "admin", enabled: true })
  );

  // Asset list + detail
  await page.route(/\/api\/v1\/assets(\?|$)/, route =>
    json(route, { data: [asset()], _metainfo: { totalCount: 1 } })
  );
  await page.route(/\/api\/v1\/assets\/[^/]+$/, route => json(route, asset()));

  // Task create (global route)
  await page.route(/\/api\/v1\/tasks$/, route => {
    if (route.request().method() === "POST") {
      const body = JSON.parse(route.request().postData() || "{}");
      const created: StoredTask = {
        uuid: `task-${++seq}`,
        title: body.title ?? "",
        description: body.description,
        priority: body.priority,
        taskStatus: body.taskStatus ?? "PENDING",
        dueDate: body.dueDate,
        status: { creator: { uuid: ME_UUID }, created: new Date().toISOString() },
      };
      tasks.push(created);
      calls.push(`POST /tasks (${created.uuid})`);
      return json(route, created, 201);
    }
    return json(route, { data: [] });
  });

  // Asset-scoped task list
  await page.route(/\/api\/v1\/assets\/[^/]+\/tasks$/, route =>
    json(route, { data: tasks.filter(t => assigned.has(t.uuid)), _metainfo: { totalCount: assigned.size } })
  );

  // Assign / unassign
  await page.route(/\/api\/v1\/assets\/[^/]+\/tasks\/[^/]+$/, route => {
    const taskUuid = decodeURIComponent(route.request().url().split("/tasks/")[1].split("?")[0]);
    if (route.request().method() === "DELETE") {
      assigned.delete(taskUuid);
      return route.fulfill({ status: 204, body: "" });
    }
    // POST assign
    assigned.add(taskUuid);
    calls.push(`POST /assets/tasks (${taskUuid})`);
    const found = tasks.find(t => t.uuid === taskUuid);
    return json(route, found ?? {}, 201);
  });

  return calls;
}

async function loginAndOpenTasksTab(page: Page, seed: StoredTask[]) {
  const calls = await installMocks(page, seed);
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });

  await page.getByRole("button", { name: "Assets" }).first().click();
  const assetLink = page.getByText("mock-asset.jpg").first();
  await expect(assetLink).toBeVisible({ timeout: 10_000 });
  await assetLink.click();
  await expect(page).toHaveURL(/\/assets\/[0-9a-f-]+/, { timeout: 5_000 });
  await page.getByRole("tab", { name: /tasks/i }).click();
  return calls;
}

test.describe("Asset tasks – mocked e2e", () => {
  test("Tasks tab lists tasks from the asset-scoped task API", async ({ page }) => {
    await loginAndOpenTasksTab(page, [seededTask()]);

    const item = page.getByTestId("asset-task-item");
    await expect(item).toBeVisible({ timeout: 10_000 });
    await expect(item.getByText("Review footage")).toBeVisible();
    await expect(item.getByTestId("asset-task-status-chip")).toHaveText("Pending");
    await expect(item.getByTestId("asset-task-priority-chip")).toHaveText("High");
    await expect(item.getByTestId("asset-task-due-date")).toBeVisible();

    // Assignees are backed by REST since V2.69 and render as an avatar group.
    await expect(item.getByTestId("asset-task-assignees")).toBeVisible();
  });

  test("empty state renders when the asset has no assigned tasks", async ({ page }) => {
    await loginAndOpenTasksTab(page, []);

    await expect(page.getByText("No tasks linked")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("asset-task-item")).toHaveCount(0);
  });

  test("Create Task chains task creation with asset assignment", async ({ page }) => {
    const calls = await loginAndOpenTasksTab(page, []);

    await page.getByTestId("asset-actions-menu-button").click();
    await page.getByTestId("asset-task-create-menu-item").click();

    await page.getByTestId("asset-task-create-title-input").fill("New asset task");
    await page.getByTestId("asset-task-create-description-input").fill("Created from the asset detail view");
    await page.getByTestId("asset-task-create-priority-select").selectOption("CRITICAL");
    await page.getByTestId("asset-task-create-submit-button").click();

    const item = page.getByTestId("asset-task-item");
    await expect(item).toBeVisible({ timeout: 10_000 });
    await expect(item.getByText("New asset task")).toBeVisible();

    // The UI must first create the task and then assign it to the asset.
    expect(calls).toEqual(["POST /tasks (task-1)", "POST /assets/tasks (task-1)"]);
  });

  test("task drawer shows taskStatus, priority, due date and assignees but no tags", async ({ page }) => {
    await loginAndOpenTasksTab(page, [seededTask()]);

    await page.getByTestId("asset-task-item").click();

    const drawer = page.getByTestId("asset-task-drawer");
    await expect(drawer).toBeVisible();
    await expect(drawer.getByTestId("asset-task-drawer-status-chip")).toHaveText("Pending");
    await expect(drawer.getByTestId("asset-task-drawer-priority-chip")).toHaveText("High");
    await expect(drawer.getByTestId("asset-task-drawer-due-date")).toBeVisible();
    await expect(drawer.getByText("Due Date")).toBeVisible();
    await expect(drawer.getByText("Created", { exact: true })).toBeVisible();

    // Tags remain mock-only; assignees are not.
    await expect(drawer.getByText("Tags", { exact: true })).toHaveCount(0);
  });
});
