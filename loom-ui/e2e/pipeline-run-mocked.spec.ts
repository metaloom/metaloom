import { test, expect, Page, WebSocketRoute } from "@playwright/test";

/**
 * Mocked tests for the pipeline run-trigger (`POST /run`) path.
 *
 * Clicking the Run control invokes `runPipeline` → `POST /api/v1/pipelines/:uuid/run`
 * with a `{ dryRun }` body derived from the selected pipeline. On a dispatched
 * response the editor shows the "run dispatched" success toast and refreshes the run
 * history (`GET .../runs`); a non-dispatched response surfaces an info toast (no
 * processor) without a refetch, and a 4xx surfaces an error toast — never a silent
 * no-op. No Loom backend is required — REST is intercepted via `page.route` and the
 * events socket via `page.routeWebSocket`. Modelled on `pipeline-run-cancel-mocked.spec.ts`.
 */

const PIPELINE_UUID = "11111111-1111-1111-1111-111111111111";
const PIPELINE_NAME = "Quick Hash";

const DEFINITION = {
  nodes: [
    { id: "src", type: "filesystem-source", label: "Source", position: { x: 0, y: 0 }, data: {} },
    { id: "sha512", type: "sha512", label: "SHA-512", position: { x: 260, y: 0 }, data: {} },
  ],
  edges: [{ id: "e1", source: "src", target: "sha512" }],
};

function pipeline(over: Partial<Record<string, unknown>> = {}) {
  return {
    uuid: PIPELINE_UUID,
    versionUuid: "aaaaaaaa-0000-0000-0000-000000000001",
    versionNumber: 1,
    name: PIPELINE_NAME,
    description: "Mocked pipeline",
    definition: DEFINITION,
    enabled: true,
    priority: 0,
    dryRun: false,
    status: { creator: { uuid: "u1", name: "admin" }, created: "2026-07-01T10:00:00Z" },
    ...over,
  };
}

function run(status: string, over: Partial<Record<string, unknown>> = {}) {
  return {
    uuid: "run-0000-0000-0000-000000000001",
    pipelineUuid: PIPELINE_UUID,
    started: "2026-07-20T10:00:00Z",
    status,
    mediaCount: 2,
    successCount: status === "success" ? 2 : 0,
    failureCount: 0,
    dryRun: false,
    ...over,
  };
}

interface RunOptions {
  /** dryRun flag on the seeded pipeline (drives both the Run chip label and the request body). */
  dryRun?: boolean;
  /** Response for the `POST /run` call. When `httpStatus` is a 4xx it is returned instead of the body. */
  runResponse?: Record<string, unknown>;
  httpStatus?: number;
}

async function mockBackend(page: Page, opts: RunOptions = {}) {
  const runsState = { runs: [] as unknown[] };
  const runTrigger = { calls: 0, lastBody: null as unknown, lastMethod: "", lastPath: "" };
  const runsFetch = { calls: 0 };

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
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [pipeline({ dryRun: opts.dryRun ?? false })], _metainfo: { totalCount: 1 } }) })
  );
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/versions`, route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [pipeline({ dryRun: opts.dryRun ?? false })], _metainfo: { totalCount: 1 } }) })
  );
  // The run-trigger: record the request (method + body) so the test can assert the
  // dispatched `POST /run` and its `{ dryRun }` body. On a dispatched response we also
  // flip the run list so the editor's post-dispatch loadRuns() refetch observes a new run.
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/run`, route => {
    const req = route.request();
    runTrigger.calls += 1;
    runTrigger.lastMethod = req.method();
    runTrigger.lastPath = new URL(req.url()).pathname;
    runTrigger.lastBody = req.postDataJSON();
    const status = opts.httpStatus ?? 200;
    if (status >= 400) {
      route.fulfill({ status, contentType: "application/json", body: JSON.stringify({ message: "boom" }) });
      return;
    }
    const resp = opts.runResponse ?? { runUuid: "run-1", processorNodeId: "node-1", dispatched: true };
    if (resp.dispatched) runsState.runs = [run("running")];
    route.fulfill({ status, contentType: "application/json", body: JSON.stringify(resp) });
  });
  // Run history — count fetches so the test can assert the post-dispatch refresh.
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs`, route => {
    runsFetch.calls += 1;
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: runsState.runs }) });
  });

  return { runsState, runTrigger, runsFetch };
}

function mockEventsSocket(page: Page): { registered: Promise<void> } {
  // Absorb the pipeline-events socket so it never reaches a real backend; the run-trigger
  // path itself is driven by REST + the loadRuns() refetch, not by live events.
  const registered = page.routeWebSocket(/\/pipelines\/events\/ws/, () => { /* accept and hold */ });
  return { registered: Promise.resolve(registered) };
}

async function loginAndOpenEditor(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
  await page.getByRole("button", { name: "Pipelines" }).first().click();
  await expect(page.getByTestId("pipeline-canvas")).toBeVisible({ timeout: 10_000 });
  await expect(page.getByTestId("pipeline-node-sha512")).toBeVisible({ timeout: 10_000 });
}

test.describe("Pipeline run trigger – mocked", () => {

  test("clicking Run dispatches POST /run, shows the success toast, and refreshes run history", async ({ page }) => {
    const { runTrigger, runsFetch } = await mockBackend(page);
    const { registered } = mockEventsSocket(page);
    await registered;
    await loginAndOpenEditor(page);

    // The editor loads run history once on mount; capture that baseline so we can assert
    // the run-trigger drives a *further* refresh.
    await expect.poll(() => runsFetch.calls, { timeout: 10_000 }).toBeGreaterThan(0);
    const runsBefore = runsFetch.calls;

    await page.getByText("Run", { exact: true }).click();

    // The dispatch fired as a POST with the pipeline's dryRun flag in the body.
    await expect.poll(() => runTrigger.calls, { timeout: 5_000 }).toBe(1);
    expect(runTrigger.lastMethod).toBe("POST");
    expect(runTrigger.lastPath).toContain(`/pipelines/${PIPELINE_UUID}/run`);
    expect(runTrigger.lastBody).toEqual({ dryRun: false });

    // Success toast.
    await expect(page.getByText("Pipeline run dispatched")).toBeVisible({ timeout: 5_000 });

    // Follow-up history refresh, which surfaces the freshly-dispatched run in the banner.
    await expect.poll(() => runsFetch.calls, { timeout: 5_000 }).toBeGreaterThan(runsBefore);
    await expect(page.getByTestId("pipeline-run-banner")).toHaveAttribute("data-status", "running", { timeout: 5_000 });
  });

  test("a dry-run pipeline sends dryRun:true in the request body", async ({ page }) => {
    const { runTrigger } = await mockBackend(page, { dryRun: true });
    const { registered } = mockEventsSocket(page);
    await registered;
    await loginAndOpenEditor(page);

    // In dry-run mode the chip is labelled "Dry Run".
    await page.getByText("Dry Run", { exact: true }).click();

    await expect.poll(() => runTrigger.calls, { timeout: 5_000 }).toBe(1);
    expect(runTrigger.lastBody).toEqual({ dryRun: true });
    await expect(page.getByText("Pipeline run dispatched")).toBeVisible({ timeout: 5_000 });
  });

  test("a not-dispatched response surfaces the no-processor info toast without a history refresh", async ({ page }) => {
    const { runTrigger, runsFetch } = await mockBackend(page, {
      runResponse: { runUuid: "", dispatched: false, message: "No processor available" },
    });
    const { registered } = mockEventsSocket(page);
    await registered;
    await loginAndOpenEditor(page);

    await expect.poll(() => runsFetch.calls, { timeout: 10_000 }).toBeGreaterThan(0);
    const runsBefore = runsFetch.calls;

    await page.getByText("Run", { exact: true }).click();

    await expect.poll(() => runTrigger.calls, { timeout: 5_000 }).toBe(1);
    // The server's message is surfaced to the user rather than swallowed…
    await expect(page.getByText("No processor available")).toBeVisible({ timeout: 5_000 });
    // …and, because nothing was dispatched, there is no run-history refetch and no banner.
    await expect(page.getByTestId("pipeline-run-banner")).toHaveCount(0);
    expect(runsFetch.calls).toBe(runsBefore);
  });

  test("a 4xx from POST /run surfaces an error toast, not a silent no-op", async ({ page }) => {
    const { runTrigger } = await mockBackend(page, { httpStatus: 400 });
    const { registered } = mockEventsSocket(page);
    await registered;
    await loginAndOpenEditor(page);

    await page.getByText("Run", { exact: true }).click();

    await expect.poll(() => runTrigger.calls, { timeout: 5_000 }).toBe(1);
    // handleResponse throws `API error 400: ...`, which the editor reports via the error toast.
    await expect(page.getByText(/API error 400/)).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId("pipeline-run-banner")).toHaveCount(0);
  });
});
