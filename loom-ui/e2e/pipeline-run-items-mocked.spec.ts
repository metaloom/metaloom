import { test, expect, Page } from "@playwright/test";

/**
 * Mocked tests for the pipeline run-detail drill-down.
 *
 * Clicking a run row in the Run History panel opens a right-side drawer listing the
 * run's items, each with a state chip (PENDING/RUNNING/SUCCESS/FAILED/SKIPPED) and,
 * for failed items, an error message. A run with no items shows an empty state. No
 * Loom backend is required — REST is intercepted via `page.route` and the events
 * socket is swallowed. Modelled on `pipeline-run-cancel-mocked.spec.ts`.
 */

const PIPELINE_UUID = "11111111-1111-1111-1111-111111111111";
const RUN_UUID = "run-0000-0000-0000-000000000001";

const DEFINITION = {
  nodes: [
    { id: "src", type: "filesystem-source", label: "Source", position: { x: 0, y: 0 }, data: {} },
    { id: "sha512", type: "sha512", label: "SHA-512", position: { x: 260, y: 0 }, data: {} },
  ],
  edges: [{ id: "e1", source: "src", target: "sha512" }],
};

function pipeline() {
  return {
    uuid: PIPELINE_UUID,
    versionUuid: "aaaaaaaa-0000-0000-0000-000000000001",
    versionNumber: 1,
    name: "Quick Hash",
    description: "Mocked pipeline",
    definition: DEFINITION,
    enabled: true,
    priority: 0,
    dryRun: false,
    status: { creator: { uuid: "u1", name: "admin" }, created: "2026-07-01T10:00:00Z" },
  };
}

function run() {
  return {
    uuid: RUN_UUID,
    pipelineUuid: PIPELINE_UUID,
    started: "2026-07-20T10:00:00Z",
    status: "success",
    mediaCount: 3,
    successCount: 1,
    failureCount: 1,
    dryRun: false,
  };
}

function item(over: Partial<Record<string, unknown>>) {
  return {
    uuid: "item-" + (over.itemSeq ?? 0),
    runUuid: RUN_UUID,
    itemSeq: 0,
    mediaPath: "/media/file.mp4",
    state: "PENDING",
    ...over,
  };
}

async function mockBackend(page: Page) {
  const itemsState = { items: [] as unknown[] };

  await page.route("**/api/v1/**", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) })
  );
  await page.route("**/api/v1/login", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ token: "fake-jwt" }) })
  );
  await page.route("**/api/v1/pipeline/node-descriptors", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ nodeDescriptors: [], contentTypes: [] }) })
  );
  await page.route("**/api/v1/pipeline/content-types", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([]) })
  );
  await page.route("**/api/v1/pipelines", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [pipeline()], _metainfo: { totalCount: 1 } }) })
  );
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/versions`, route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [pipeline()], _metainfo: { totalCount: 1 } }) })
  );
  // Run items endpoint (more specific — registered after the /runs route below wins for
  // its path). Serves the current mutable items state.
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs/${RUN_UUID}/items`, route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: itemsState.items }) })
  );
  // One run in the history so a clickable row is present at load time.
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs`, route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [run()] }) })
  );

  return { itemsState };
}

async function loginAndOpenEditor(page: Page) {
  // Swallow the pipeline-events websocket so it does not hit a real backend.
  await page.routeWebSocket(/\/pipelines\/events\/ws/, () => { /* accept and ignore */ });

  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await page.getByRole("button", { name: "Pipelines" }).first().click();
  await expect(page.getByTestId("pipeline-canvas")).toBeVisible({ timeout: 10_000 });
  await expect(page.getByTestId("pipeline-node-sha512")).toBeVisible({ timeout: 10_000 });
}

test.describe("Pipeline run items – mocked", () => {

  test("opening a run shows its items with state chips and error text", async ({ page }) => {
    const { itemsState } = await mockBackend(page);
    itemsState.items = [
      item({ itemSeq: 0, state: "SUCCESS", mediaPath: "/media/ok.mp4" }),
      item({ itemSeq: 1, state: "FAILED", mediaPath: "/media/bad.mp4", errorMessage: "decode failed: broken header" }),
      item({ itemSeq: 2, state: "PENDING", mediaPath: "/media/wait.mp4" }),
    ];
    await loginAndOpenEditor(page);

    // The run row is present in the history panel.
    const row = page.getByTestId(`pipeline-run-row-${RUN_UUID}`);
    await expect(row).toBeVisible({ timeout: 10_000 });

    // Clicking it opens the run-detail drawer with the three items.
    await row.click();
    await expect(page.getByTestId("pipeline-run-detail-drawer")).toBeVisible({ timeout: 5_000 });

    const items = page.getByTestId("pipeline-run-item");
    await expect(items).toHaveCount(3);
    await expect(page.locator('[data-testid="pipeline-run-item"][data-state="SUCCESS"]')).toHaveCount(1);
    await expect(page.locator('[data-testid="pipeline-run-item"][data-state="FAILED"]')).toHaveCount(1);
    await expect(page.locator('[data-testid="pipeline-run-item"][data-state="PENDING"]')).toHaveCount(1);

    // The failed item surfaces its error message.
    await expect(page.getByTestId("pipeline-run-item-error")).toHaveText(/decode failed: broken header/);
  });

  test("a run with no items shows an empty state", async ({ page }) => {
    const { itemsState } = await mockBackend(page);
    itemsState.items = [];
    await loginAndOpenEditor(page);

    const row = page.getByTestId(`pipeline-run-row-${RUN_UUID}`);
    await expect(row).toBeVisible({ timeout: 10_000 });
    await row.click();

    await expect(page.getByTestId("pipeline-run-detail-drawer")).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId("pipeline-run-item")).toHaveCount(0);
    await expect(page.getByTestId("pipeline-run-items-empty")).toBeVisible();
  });
});
