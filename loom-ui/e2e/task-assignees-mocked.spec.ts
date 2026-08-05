import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for assigning a task to users and groups — no running Loom backend.
 *
 * The interesting behaviour is the reconcile in `TasksView.syncAssignees`: the REST
 * surface is additive (`POST /tasks/:uuid/assignees`) plus explicit per-target deletes,
 * so the view has to diff the picker selection against what the task already carries.
 * These tests pin that diff — added targets are POSTed, removed ones are DELETEd on
 * their kind-specific sub-path, and an edit that leaves the selection alone writes
 * nothing at all.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const TASK_UUID = "33333333-3333-3333-3333-333333333333";
const USER_UUID = "55555555-5555-5555-5555-555555555555";
const GROUP_UUID = "66666666-6666-6666-6666-666666666666";

interface Assignee {
  userUuid?: string;
  groupUuid?: string;
  name?: string;
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function task(assignees: Assignee[]) {
  return {
    uuid: TASK_UUID,
    title: "Mock task",
    priority: "MEDIUM",
    assignees,
    status: { creator: { uuid: ME_UUID }, created: new Date().toISOString() },
  };
}

async function installMocks(page: Page, seed: Assignee[]) {
  let assignees: Assignee[] = [...seed];
  const calls: string[] = [];

  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route =>
    json(route, { uuid: ME_UUID, username: "admin", enabled: true })
  );

  // The picker's option source.
  await page.route(/\/api\/v1\/users(\?|$)/, route =>
    json(route, { data: [{ uuid: USER_UUID, username: "joedoe", enabled: true }] })
  );
  await page.route(/\/api\/v1\/groups(\?|$)/, route =>
    json(route, { data: [{ uuid: GROUP_UUID, name: "editors" }] })
  );

  // Assignee sub-resource. Registered BEFORE the bare /tasks routes below so the more
  // specific pattern wins — Playwright matches most-recently-registered first.
  await page.route(/\/api\/v1\/tasks\/[^/]+\/assignees\/users\/([^/]+)$/, route => {
    const uuid = route.request().url().split("/").pop()!;
    calls.push(`DELETE user ${uuid}`);
    assignees = assignees.filter(a => a.userUuid !== uuid);
    return route.fulfill({ status: 204, body: "" });
  });
  await page.route(/\/api\/v1\/tasks\/[^/]+\/assignees\/groups\/([^/]+)$/, route => {
    const uuid = route.request().url().split("/").pop()!;
    calls.push(`DELETE group ${uuid}`);
    assignees = assignees.filter(a => a.groupUuid !== uuid);
    return route.fulfill({ status: 204, body: "" });
  });
  await page.route(/\/api\/v1\/tasks\/[^/]+\/assignees$/, route => {
    if (route.request().method() === "POST") {
      const body = JSON.parse(route.request().postData() || "{}");
      calls.push(`POST users=[${(body.userUuids ?? []).join(",")}] groups=[${(body.groupUuids ?? []).join(",")}]`);
      for (const u of body.userUuids ?? []) assignees.push({ userUuid: u, name: "joedoe" });
      for (const g of body.groupUuids ?? []) assignees.push({ groupUuid: g, name: "editors" });
      return json(route, { data: assignees }, 201);
    }
    return json(route, { data: assignees });
  });

  await page.route(/\/api\/v1\/tasks(\?|$)/, route =>
    json(route, { data: [task(assignees)], _metainfo: { totalCount: 1 } })
  );
  await page.route(/\/api\/v1\/tasks\/[^/]+$/, route => {
    if (route.request().method() === "POST") {
      calls.push("POST task update");
    }
    return json(route, task(assignees));
  });

  return calls;
}

async function login(page: Page, seed: Assignee[]) {
  const calls = await installMocks(page, seed);
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });

  await page.getByRole("button", { name: "Tasks" }).first().click();
  await expect(page.getByRole("heading", { name: "Tasks" })).toBeVisible({ timeout: 10_000 });
  return calls;
}

test.describe("Task assignees – mocked e2e", () => {
  test("the table renders an avatar per assignee", async ({ page }) => {
    await login(page, [{ userUuid: USER_UUID, name: "joedoe" }, { groupUuid: GROUP_UUID, name: "editors" }]);

    await expect(page.getByTestId("tasks-row-assignees")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId("tasks-drawer-assignee-chip")).toHaveCount(0);

    // The drawer shows them as labelled chips, with groups prefixed so a group named
    // "alice" cannot read as a person.
    await page.getByText("Mock task").first().click();
    const chips = page.getByTestId("tasks-drawer-assignee-chip");
    await expect(chips).toHaveCount(2);
    await expect(chips.nth(0)).toHaveText("joedoe");
    await expect(chips.nth(1)).toHaveText("@editors");
  });

  test("creating a task assigns the picked targets in one POST", async ({ page }) => {
    const calls = await login(page, []);

    await page.getByTestId("tasks-create-button").click();
    await page.getByTestId("tasks-title-input").fill("Assigned on create");

    const picker = page.getByTestId("tasks-assignees-input");
    await picker.click();
    await page.getByRole("option", { name: "joedoe" }).click();
    await picker.click();
    await page.getByRole("option", { name: "@editors" }).click();

    await page.getByTestId("tasks-create-submit-button").click();

    await expect(page.getByTestId("tasks-assignees-input")).toBeHidden({ timeout: 10_000 });
    // Both targets travel in a single request — the picker batches them rather than
    // issuing one call per chip.
    expect(calls).toContain(`POST users=[${USER_UUID}] groups=[${GROUP_UUID}]`);
    expect(calls.filter(c => c.startsWith("POST users="))).toHaveLength(1);
  });

  test("editing diffs the selection: adds POST, removals DELETE, no-ops write nothing", async ({ page }) => {
    const calls = await login(page, [{ userUuid: USER_UUID, name: "joedoe" }]);

    await page.getByText("Mock task").first().click();
    await page.getByTestId("tasks-edit-button").click();

    // Drop the seeded user and add the group instead. Backspace on the empty input
    // removes the trailing chip. Do NOT press Escape to dismiss the option popup —
    // the edit form is a MUI Drawer and Escape closes the whole thing.
    const picker = page.getByTestId("tasks-edit-assignees-input");
    await picker.click();
    await picker.press("Backspace");
    // Scope to the chip, not the text — "joedoe" also appears as an option in the open
    // popup, so a bare getByText would match either.
    await expect(page.locator(".MuiAutocomplete-tag")).toHaveCount(0);

    await page.getByRole("option", { name: "@editors" }).click();

    await page.getByTestId("tasks-save-button").click();
    await expect(page.getByTestId("tasks-edit-assignees-input")).toBeHidden({ timeout: 10_000 });

    expect(calls).toContain(`POST users=[] groups=[${GROUP_UUID}]`);
    expect(calls).toContain(`DELETE user ${USER_UUID}`);
  });

  test("an edit that leaves the assignees alone issues no assignment calls", async ({ page }) => {
    const calls = await login(page, [{ userUuid: USER_UUID, name: "joedoe" }]);

    await page.getByText("Mock task").first().click();
    await page.getByTestId("tasks-edit-button").click();
    await page.getByTestId("tasks-edit-title-input").fill("Renamed only");
    await page.getByTestId("tasks-save-button").click();
    await expect(page.getByTestId("tasks-edit-assignees-input")).toBeHidden({ timeout: 10_000 });

    // Renaming a task must not rewrite its assignment rows — that would churn the
    // assigned timestamps and, once notifications land, re-notify everybody.
    expect(calls).toContain("POST task update");
    expect(calls.filter(c => c.startsWith("POST users=") || c.startsWith("DELETE "))).toHaveLength(0);
  });
});
