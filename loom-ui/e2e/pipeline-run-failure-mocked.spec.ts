import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the whole "a pipeline run failed" path: notification → run detail → the node
 * that broke and why.
 *
 * This existed as four disconnected pieces. The notification carries only `pipeline_run_uuid`;
 * `notificationLink` pointed it at `/monitoring?run=…`, and `MonitoringArea` reads no query
 * parameter at all — so clicking a failure notification silently discarded the run and showed
 * fleet-wide statistics. Meanwhile the only run detail view lived inside the pipeline editor,
 * reachable only from the run-history panel of whichever pipeline happened to be selected, and it
 * loaded a node's `error_message` only after the reviewer clicked the right item.
 *
 * So this spec asserts the chain end to end, because every link in it was individually fine:
 *   1. the notification routes to the editor,
 *   2. the editor resolves a bare run uuid to its pipeline (`GET /pipeline-runs/:runUuid`),
 *   3. it selects that pipeline rather than the first in the list,
 *   4. the drawer opens on the FAILED item without a click, and
 *   5. the failing node, its kind and its error text are on screen.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const OTHER_PIPELINE_UUID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const FAILING_PIPELINE_UUID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
const RUN_UUID = "cccccccc-cccc-cccc-cccc-cccccccccccc";
const ITEM_OK_UUID = "dddddddd-dddd-dddd-dddd-dddddddddddd";
const ITEM_FAILED_UUID = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";

/** The real error from metaloom.sky, which is what this whole path exists to surface. */
const NODE_ERROR =
  "java.lang.UnsatisfiedLinkError: /tmp/libjinspireface.so: libopencv_imgproc.so.501: cannot open shared object file";

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

const DESCRIPTORS = ["filesystem-source", "facedetect"].map(kind => ({
  kind,
  name: kind,
  description: "",
  icon: "",
  category: "TRANSFORM",
  inputPorts: kind === "filesystem-source"
    ? []
    : [{ id: "media", label: "Media", contentType: "media/*", cardinality: "ONE", required: true }],
  outputPorts: [{ id: kind === "filesystem-source" ? "media" : "faces", contentType: "media/*", cardinality: "ONE", required: true }],
  inputGroups: [],
  outputGroups: [],
  dynamicPorts: false,
  parameters: [],
  defaultConcurrency: 1,
  defaultMode: "SEQUENTIAL",
  defaultBlocking: false,
  events: [],
}));

function pipelineResponse(uuid: string, name: string) {
  return {
    uuid,
    versionUuid: `${uuid}-v1`,
    versionNumber: 1,
    name,
    description: "Mocked pipeline",
    definition: {
      // No `position` on purpose. A pipeline authored over REST - which is how the one behind a
      // failure notification usually got there - carries none, and React Flow reads `position.x`
      // unconditionally: the editor used to throw inside its layout pass and render an error page
      // instead of the run that failed. It only ever auto-selected `ps[0]`, a seeded pipeline with
      // positions, so nothing hit this until a deep link could choose the pipeline.
      nodes: [
        { id: "source", type: "filesystem-source", label: "Source", data: {} },
        { id: "faces", type: "facedetect", label: "Faces", data: {} },
      ],
      edges: [
        { id: "e1", source: "source", sourcePort: "media", target: "faces", targetPort: "media", branch: "ANY" },
      ],
    },
    enabled: true,
    priority: 0,
    dryRun: false,
    status: { creator: { uuid: ME_UUID, name: "admin" }, created: "2026-09-20T06:00:00Z" },
  };
}

const RUN = {
  uuid: RUN_UUID,
  pipelineUuid: FAILING_PIPELINE_UUID,
  pipelineVersion: 1,
  started: "2026-09-20T06:10:00Z",
  finished: "2026-09-20T06:11:00Z",
  status: "FAILED",
  mediaCount: 2,
  successCount: 1,
  failureCount: 1,
  skippedCount: 0,
  dryRun: false,
};

const RUN_ITEMS = [
  {
    uuid: ITEM_OK_UUID, runUuid: RUN_UUID, itemSeq: 0,
    mediaPath: "/content/episode-01.mkv", state: "SUCCESS",
  },
  {
    uuid: ITEM_FAILED_UUID, runUuid: RUN_UUID, itemSeq: 1,
    mediaPath: "/content/episode-02.mkv", state: "FAILED",
  },
];

const NODE_TASKS = [
  {
    uuid: "t-source", itemUuid: ITEM_FAILED_UUID, runUuid: RUN_UUID,
    nodeId: "source", nodeKind: "filesystem-source", elementSeq: 0, generation: 0,
    state: "COMPLETED", attempt: 0, maxAttempts: 1, durationMs: 3,
  },
  {
    uuid: "t-faces", itemUuid: ITEM_FAILED_UUID, runUuid: RUN_UUID,
    nodeId: "faces", nodeKind: "facedetect", elementSeq: 0, generation: 0,
    state: "FAILED", attempt: 1, maxAttempts: 1, durationMs: 0,
    errorMessage: NODE_ERROR,
  },
];

interface Calls {
  /** Run uuids the resolver route was asked for. Empty means the deep link never happened. */
  resolved: string[];
  /** Item uuids whose node executions were fetched. */
  tasksFor: string[];
}

async function installMocks(page: Page): Promise<Calls> {
  const calls: Calls = { resolved: [], tasksFor: [] };
  const pipelines = [pipelineResponse(OTHER_PIPELINE_UUID, "Alpha"), pipelineResponse(FAILING_PIPELINE_UUID, "Face Detection")];

  await page.route("**/api/v1/**", route => json(route, { data: [] }));
  await page.route("**/api/v1/login", route => json(route, { token: "fake-jwt" }));
  await page.route("**/api/v1/me", route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));

  await page.route("**/api/v1/pipeline/node-descriptors", route =>
    json(route, { nodeDescriptors: DESCRIPTORS, contentTypes: [] }));
  await page.route("**/api/v1/pipeline/content-types", route => json(route, []));

  await page.route(/\/api\/v1\/notifications(\?|$)/, route =>
    json(route, {
      data: [{
        uuid: "n1",
        type: "PIPELINE_RUN_FAILED",
        read: false,
        title: "Pipeline run failed: Face Detection",
        pipelineRunUuid: RUN_UUID,
        status: { created: new Date().toISOString() },
      }],
      _metainfo: { totalCount: 1 },
    }));
  await page.route(/\/api\/v1\/notifications\/[^/]+$/, route => json(route, {}));

  await page.route("**/api/v1/pipelines", route =>
    json(route, { data: pipelines, _metainfo: { totalCount: pipelines.length } }));

  // The resolver this whole flow hangs on: a run uuid in, its pipeline out.
  await page.route(/\/api\/v1\/pipeline-runs\/([^/?]+)$/, route => {
    const uuid = route.request().url().match(/pipeline-runs\/([^/?]+)/)![1];
    calls.resolved.push(uuid);
    return uuid === RUN_UUID ? json(route, RUN) : json(route, { message: "not found" }, 404);
  });

  await page.route(/\/api\/v1\/pipelines\/[^/]+\/runs$/, route =>
    json(route, { data: [RUN], _metainfo: { totalCount: 1 } }));

  await page.route(/\/api\/v1\/pipelines\/[^/]+\/runs\/[^/]+\/items$/, route =>
    json(route, { data: RUN_ITEMS, _metainfo: { totalCount: RUN_ITEMS.length } }));

  await page.route(/\/api\/v1\/pipelines\/[^/]+\/runs\/[^/]+\/items\/([^/]+)\/tasks$/, route => {
    const itemUuid = route.request().url().match(/items\/([^/]+)\/tasks/)![1];
    calls.tasksFor.push(itemUuid);
    // The client reads `data` (PipelineNodeTaskListResponse), or a bare array.
    return json(route, { data: itemUuid === ITEM_FAILED_UUID ? NODE_TASKS : [] });
  });

  await page.route(/\/api\/v1\/pipelines\/[^/]+\/versions$/, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/pipelines\/[^/?]+$/, route => {
    const uuid = route.request().url().match(/pipelines\/([^/?]+)/)![1];
    const p = pipelines.find(x => x.uuid === uuid);
    return json(route, p ?? {}, p ? 200 : 404);
  });
  await page.route("**/api/v1/pipelines/validate", route => json(route, { valid: true, errors: [], warnings: [] }));

  return calls;
}

async function login(page: Page) {
  await page.goto("/");
  // A session now survives a reload (LOOM_UI.md §7.1), so a test that reloads mid-way reaches
  // here already signed in and there is no form to fill. Idempotent rather than removed from
  // those call sites: "make sure we are signed in" is what every caller meant all along.
  if (await page.getByPlaceholder("Username").count() === 0) return;
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

test.describe("Pipeline run failure – notification to root cause", () => {
  test("clicking a failure notification opens the run and names the node that broke", async ({ page }) => {
    const calls = await installMocks(page);
    await login(page);

    await page.getByTestId("notification-bell").click();
    await page.getByTestId("notification-row").first().click();

    // 1. The editor, not monitoring.
    await expect(page).toHaveURL(/\/pipelines/, { timeout: 10_000 });

    // 2. The bare run uuid was resolved through the new route.
    await expect.poll(() => calls.resolved, { timeout: 10_000 }).toContain(RUN_UUID);

    // 3. The run's own pipeline is selected - "Alpha" is first in the list and must lose.
    const drawer = page.getByTestId("pipeline-run-detail-drawer");
    await expect(drawer).toBeVisible({ timeout: 10_000 });
    await expect(drawer).toContainText(RUN_UUID);
    await expect(drawer).toContainText("FAILED");

    // 4. The FAILED item was inspected without a click; the SUCCESS item was not the landing one.
    await expect.poll(() => calls.tasksFor, { timeout: 10_000 }).toContain(ITEM_FAILED_UUID);

    // 5. The answer to "why did it fail?" is on screen: which node, its kind, and the error.
    const failedTask = page.getByTestId("pipeline-run-node-task").filter({ hasText: "facedetect" });
    await expect(failedTask).toBeVisible({ timeout: 10_000 });
    await expect(failedTask).toHaveAttribute("data-node-id", "faces");
    await expect(failedTask).toHaveAttribute("data-state", "FAILED");
    await expect(page.getByTestId("pipeline-run-node-task-error")).toContainText("libopencv_imgproc.so.501");
  });

  test("the failing node execution is listed before the ones that succeeded", async ({ page }) => {
    await installMocks(page);
    await login(page);

    await page.getByTestId("notification-bell").click();
    await page.getByTestId("notification-row").first().click();

    const tasks = page.getByTestId("pipeline-run-node-task");
    await expect(tasks).toHaveCount(2, { timeout: 10_000 });
    // A reviewer arriving from a failure notification should not have to scan past the healthy
    // nodes to find the one that broke.
    await expect(tasks.nth(0)).toHaveAttribute("data-state", "FAILED");
  });
});
