import { test, expect, Page } from "@playwright/test";
import { fileURLToPath } from "url";
import path from "path";
import fs from "fs";

/**
 * Mocked tests for per-node result inspection.
 *
 * Selecting a run item loads its node executions from
 * `GET /pipelines/:uuid/runs/:runUuid/items/:itemUuid/tasks` and paints what each node emitted
 * onto the canvas — but only in debug mode, so the editor is untouched while designing a graph.
 *
 * No Loom backend is required; every REST call is intercepted.
 */

const PIPELINE_UUID = "11111111-1111-1111-1111-111111111111";
const PIPELINE_NAME = "Quick Hash";
const RUN_UUID = "run-0000-0000-0000-000000000001";
const ITEM_UUID = "item-0000-0000-0000-000000000001";

/**
 * The payloads a real node produced.
 *
 * `scripts/fixtures/` is written by `DocsFixtureGenerator` (integration-test) and is what
 * `scripts/capture-debug-screenshots.mjs` photographs. Reading it here rather than hand-writing a
 * 33-element detection port is the whole point: the spec and the documentation screenshots then
 * cannot describe two different renderings of the same payload, and a node whose output shape
 * changes breaks this spec instead of silently ageing the pictures.
 */
const FIXTURES = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../scripts/fixtures");
const OBJECTDETECT = JSON.parse(
  fs.readFileSync(path.join(FIXTURES, "nodes/objectdetect/fixture.json"), "utf8"),
) as {
  outputs: Record<string, { contentType: string; cardinality: string; elements: unknown[] }>;
  previews: Record<string, { file: string; mimeType: string; width: number; height: number; markdown?: string }>;
};

/** Ports the objectdetect node wrote, in the order the strip will list them. */
const DETECT_PORTS = Object.keys(OBJECTDETECT.outputs);
/** How many detections the fixture carries — 33, one preview crop each. */
const DETECTION_COUNT = OBJECTDETECT.outputs.detections.elements.length;
/** Ports shown before `NodeResultStrip` collapses the rest into "+n more". */
const MAX_STRIP_ROWS = 3;

const DEFINITION = {
  nodes: [
    { id: "src", type: "filesystem-source", label: "Source", position: { x: 0, y: 0 }, data: {} },
    { id: "sha512", type: "sha512", label: "SHA-512", position: { x: 260, y: 0 }, data: {} },
    { id: "det", type: "objectdetect", label: "Objects", position: { x: 520, y: 0 }, data: {} },
  ],
  edges: [
    { id: "e1", source: "src", target: "sha512" },
    { id: "e2", source: "src", target: "det" },
  ],
};

/** The URL the tasks route advertises for one preview key, exactly as `PipelineModelBuilder` builds it. */
function previewUrl(taskUuid: string, key: string): string {
  return `/api/v1/pipelines/${PIPELINE_UUID}/runs/${RUN_UUID}/items/${ITEM_UUID}`
    + `/tasks/${taskUuid}/previews/${encodeURIComponent(key)}`;
}

/**
 * The fixture's preview metadata, with the `file` reference swapped for the URL the server would
 * have published. Keys are `portId` and `portId#seq` — the per-element crops live in the same map
 * as the port-level frame, which is what `NodeResultDetail` walks to build the tile grid.
 */
function detectPreviews(taskUuid: string): Record<string, unknown> {
  const previews: Record<string, unknown> = {};
  for (const [key, { file: _file, ...meta }] of Object.entries(OBJECTDETECT.previews)) {
    previews[key] = { ...meta, url: previewUrl(taskUuid, key) };
  }
  return previews;
}

function pipeline() {
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
  };
}

const RUN = {
  uuid: RUN_UUID,
  pipelineUuid: PIPELINE_UUID,
  started: "2026-07-20T10:00:00Z",
  finished: "2026-07-20T10:01:00Z",
  status: "success",
  mediaCount: 1,
  successCount: 1,
  failureCount: 0,
  dryRun: false,
};

const ITEM = {
  uuid: ITEM_UUID,
  runUuid: RUN_UUID,
  itemSeq: 0,
  mediaPath: "/media/library/holiday-clip.mp4",
  state: "SUCCESS",
};

/** One settled execution per node, with the payloads the two nodes really emit. */
const TASKS = [
  {
    uuid: "task-1", itemUuid: ITEM_UUID, runUuid: RUN_UUID,
    nodeId: "src", nodeKind: "filesystem-source", elementSeq: 0,
    state: "DONE", attempt: 1, maxAttempts: 3, durationMs: 5,
    outputs: {
      media: {
        contentType: "media/video", cardinality: "ONE",
        elements: [{ origin: { itemId: ITEM_UUID, seq: 0, total: 1 }, value: "/media/library/holiday-clip.mp4" }],
      },
      thumb: {
        contentType: "artifact/image", cardinality: "ONE",
        elements: [{ origin: { itemId: ITEM_UUID, seq: 0, total: 1 }, value: "/var/cortex/thumb_bin/ab/cd/x.jpg" }],
      },
      huge: {
        contentType: "artifact/image", cardinality: "ONE",
        elements: [{ origin: { itemId: ITEM_UUID, seq: 0, total: 1 }, value: "/var/cortex/thumb_bin/ab/cd/huge.jpg" }],
      },
    },
    // A produced image is only ever reachable through a preview: the port carries a path on
    // the worker that made it. `huge` shows the other outcome — capped, so not produced.
    previews: {
      thumb: {
        mimeType: "image/jpeg", width: 512, height: 288,
        url: `/api/v1/pipelines/${PIPELINE_UUID}/runs/${RUN_UUID}/items/${ITEM_UUID}/tasks/task-1/previews/thumb`,
      },
      huge: { skippedReason: "Preview exceeds 98304 bytes" },
    },
  },
  {
    uuid: "task-2", itemUuid: ITEM_UUID, runUuid: RUN_UUID,
    nodeId: "sha512", nodeKind: "sha512", elementSeq: 0,
    // Retried once and still failed: the attempt counter and the error are the two things
    // that explain a partially-failed run, and neither is reachable anywhere else.
    state: "FAILED", attempt: 2, maxAttempts: 3, durationMs: 91,
    errorMessage: "Read timed out after 30s",
    outputs: {
      sha512: {
        contentType: "hash/sha512", cardinality: "ONE",
        elements: [{ origin: { itemId: ITEM_UUID, seq: 0, total: 1 }, value: "0f8ef1c9ab34de56789012" }],
      },
      faces: {
        contentType: "detection/face", cardinality: "MANY",
        elements: [
          { origin: { itemId: ITEM_UUID, seq: 0, total: 2 }, value: { index: 0, confidence: 0.93 } },
          { origin: { itemId: ITEM_UUID, seq: 1, total: 2 }, value: { index: 1, confidence: 0.71 } },
        ],
      },
    },
    // A node that describes its own output: this outranks every content-type default, because
    // the node knows these rows are one-per-face and the type system does not.
    previews: {
      faces: { markdown: "| # | confidence |\n|---|---|\n| 0 | 0.93 |\n| 1 | 0.71 |" },
    },
  },
  {
    // Verbatim from the objectdetect fixture: four ports, one of them a MANY detection port with
    // 33 encoded-JSON elements and a preview crop per element.
    uuid: "task-3", itemUuid: ITEM_UUID, runUuid: RUN_UUID,
    nodeId: "det", nodeKind: "objectdetect", elementSeq: 0,
    state: "DONE", attempt: 1, maxAttempts: 3, durationMs: 2732,
    outputs: OBJECTDETECT.outputs,
    previews: detectPreviews("task-3"),
  },
];

async function mockBackend(page: Page) {
  const calls = { tasks: 0, lastPath: "" };

  await page.route("**/api/v1/**", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [] }) })
  );
  await page.route("**/api/v1/login", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ token: "fake-jwt" }) })
  );
  await page.route("**/api/v1/pipeline/node-descriptors", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ nodeDescriptors: [], contentTypes: [] }) })
  );
  await page.route("**/api/v1/pipelines", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [pipeline()], _metainfo: { totalCount: 1 } }) })
  );
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/versions`, route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [pipeline()], _metainfo: { totalCount: 1 } }) })
  );
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs`, route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [RUN] }) })
  );
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs/${RUN_UUID}/items`, route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: [ITEM] }) })
  );
  // Registered after the /items route so the more specific pattern is not shadowed by it.
  await page.route(`**/api/v1/pipelines/${PIPELINE_UUID}/runs/${RUN_UUID}/items/${ITEM_UUID}/tasks`, route => {
    calls.tasks += 1;
    calls.lastPath = new URL(route.request().url()).pathname;
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ data: TASKS }) });
  });

  // Real JPEG bytes for every preview the fixture wrote, port-level and per-element alike. Without
  // them the catch-all above answers an <img> with JSON and every picture in this spec is broken —
  // which would make "the preview binary is gone" indistinguishable from the happy path.
  for (const [key, { file }] of Object.entries(OBJECTDETECT.previews)) {
    const bytes = fs.readFileSync(path.join(FIXTURES, "nodes/objectdetect", file));
    await page.route(`**/previews/${encodeURIComponent(key)}`, route =>
      route.fulfill({ status: 200, contentType: "image/jpeg", body: bytes }));
  }
  const thumbBytes = fs.readFileSync(path.join(FIXTURES, "thumbnail-grid.jpg"));
  await page.route("**/previews/thumb", route =>
    route.fulfill({ status: 200, contentType: "image/jpeg", body: thumbBytes }));

  return { calls };
}

/** Whether the browser actually decoded an image, as opposed to painting a broken-image frame. */
async function loaded(image: import("@playwright/test").Locator): Promise<boolean> {
  return image.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth > 0);
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

/**
 * Close the run drawer and wait for it to actually go.
 *
 * The drawer is modal: while its close transition runs, the backdrop still owns the pointer and
 * every click on the canvas underneath is swallowed.
 */
/**
 * Click a result row on a node card.
 *
 * Dispatched on the element rather than clicked at its coordinates, because React Flow's minimap
 * floats over the bottom-right of the canvas and the second node of this two-node graph lands
 * under it after `fitView`. A positional click — even a forced one — is received by the minimap.
 * The event still bubbles, so React's handler runs exactly as it would for a user whose viewport
 * is not obstructed.
 */
async function clickPort(page: Page, nodeId: string, portId: string) {
  await page.getByTestId(`pipeline-node-${nodeId}`).getByTestId(`node-result-port-${portId}`)
    .dispatchEvent("click");
}

async function closeDrawer(page: Page) {
  await page.getByTestId("pipeline-run-detail-close").click();
  await expect(page.getByTestId("pipeline-run-detail-drawer")).toBeHidden({ timeout: 5_000 });
}

/** Open the run drawer and select the single item, which loads its node executions. */
async function inspectTheItem(page: Page) {
  await page.getByTestId(`pipeline-run-row-${RUN_UUID}`).click();
  await expect(page.getByTestId("pipeline-run-detail-drawer")).toBeVisible({ timeout: 5_000 });
  await page.getByTestId("pipeline-run-item").first().click();
}

test.describe("Pipeline node results – mocked", () => {

  test("selecting a run item loads its node executions and paints them on the canvas", async ({ page }) => {
    const { calls } = await mockBackend(page);
    await loginAndOpenEditor(page);

    await page.getByTestId("pipeline-debug-toggle").click();
    await inspectTheItem(page);

    await expect.poll(() => calls.tasks, { timeout: 5_000 }).toBe(1);
    expect(calls.lastPath).toContain(`/runs/${RUN_UUID}/items/${ITEM_UUID}/tasks`);

    // The hash node shows its port and a truncated digest — the summariser keeps a node card
    // legible rather than printing 128 hex characters onto it.
    const hashPort = page.getByTestId("pipeline-node-sha512").getByTestId("node-result-port-sha512");
    await expect(hashPort).toBeVisible({ timeout: 5_000 });
    await expect(hashPort).toHaveAttribute("data-content-type", "hash/sha512");
    await expect(hashPort).toContainText("0f8ef1c9ab…");

    // The source node's media path is truncated from the left, so the filename survives.
    const mediaPort = page.getByTestId("pipeline-node-src").getByTestId("node-result-port-media");
    await expect(mediaPort).toContainText("holiday-clip.mp4");

    // A produced image renders as a thumbnail fetched from its own URL, not inline.
    const thumb = page.getByTestId("node-result-thumb-thumb");
    await expect(thumb).toBeVisible();
    await expect(thumb).toHaveAttribute("src", /\/tasks\/task-1\/previews\/thumb$/);

    // A capped preview says so instead of looking like a port that emitted nothing.
    await expect(page.getByTestId("node-result-preview-skipped-huge")).toBeVisible();
    await expect(page.getByTestId("node-result-thumb-huge")).toHaveCount(0);

    // A chip names the item the canvas results belong to, and clears them.
    const chip = page.getByTestId("pipeline-inspected-item");
    await expect(chip).toContainText("holiday-clip.mp4");
  });

  test("results are hidden while debug mode is off", async ({ page }) => {
    await mockBackend(page);
    await loginAndOpenEditor(page);

    // Debug is off by default. The item is still inspectable — the request is made and the
    // sidebar can show it — but nothing is painted onto the canvas.
    await inspectTheItem(page);
    await expect(page.getByTestId("pipeline-run-item")).toHaveAttribute("data-selected", "true", { timeout: 5_000 });
    await expect(page.getByTestId("node-result-strip")).toHaveCount(0);

    // The drawer is modal, so its backdrop owns the pointer; close it before reaching the
    // toolbar. Toggling debug then reveals the results already loaded.
    await closeDrawer(page);
    await page.getByTestId("pipeline-debug-toggle").click();
    await expect(page.getByTestId("node-result-strip").first()).toBeVisible({ timeout: 5_000 });
  });

  test("clearing the inspected item removes the results from the canvas", async ({ page }) => {
    await mockBackend(page);
    await loginAndOpenEditor(page);

    await page.getByTestId("pipeline-debug-toggle").click();
    await inspectTheItem(page);
    await expect(page.getByTestId("node-result-strip").first()).toBeVisible({ timeout: 5_000 });

    await closeDrawer(page);
    // Closing the drawer deliberately keeps the results: that is how you get the graph back
    // into view after choosing what to look at.
    await expect(page.getByTestId("node-result-strip").first()).toBeVisible();

    await page.getByTestId("pipeline-inspected-item").getByTestId("CancelIcon").click();
    await expect(page.getByTestId("node-result-strip")).toHaveCount(0);
  });

  test("the Results tab reports the failing node's error and retry count", async ({ page }) => {
    await mockBackend(page);
    await loginAndOpenEditor(page);

    await page.getByTestId("pipeline-debug-toggle").click();
    await inspectTheItem(page);
    await closeDrawer(page);

    await page.getByTestId("pipeline-node-sha512").click();
    await page.getByRole("tab", { name: "Results" }).click();

    const task = page.getByTestId("node-result-task");
    await expect(task).toHaveAttribute("data-state", "FAILED", { timeout: 5_000 });
    await expect(page.getByTestId("node-result-error")).toContainText("Read timed out");
    await expect(page.getByTestId("node-result-attempts")).toContainText("2/3");
    // Outputs survive a FAILED result on purpose — they are the diagnostics that explain
    // the non-completion, so they must still be shown here.
    await expect(page.getByTestId("node-result-detail-sha512")).toContainText("0f8ef1c9ab…");
  });

  test("clicking a port opens the detail view on the node's own description", async ({ page }) => {
    await mockBackend(page);
    await loginAndOpenEditor(page);

    await page.getByTestId("pipeline-debug-toggle").click();
    await inspectTheItem(page);
    await closeDrawer(page);

    await clickPort(page, "sha512", "faces");

    const detail = page.getByTestId("pipeline-result-detail");
    await expect(detail).toBeVisible({ timeout: 5_000 });
    // The node wrote a description, so that is what opens — ahead of the generic table.
    await expect(page.getByTestId("result-view-markdown")).toBeVisible();
    await expect(detail.getByTestId("markdown-content")).toContainText("confidence");
    await expect(detail.getByTestId("markdown-content").locator("table")).toBeVisible();

    // Raw is always offered, and always shows the payload verbatim.
    await page.getByTestId("result-tab-raw").click();
    await expect(page.getByTestId("result-view-raw")).toContainText("detection/face");

    await page.getByTestId("pipeline-result-detail-close").click();
    await expect(detail).toBeHidden();
  });

  test("a produced image opens on its preview, and says it is a reduced copy", async ({ page }) => {
    await mockBackend(page);
    await loginAndOpenEditor(page);

    await page.getByTestId("pipeline-debug-toggle").click();
    await inspectTheItem(page);
    await closeDrawer(page);

    await clickPort(page, "src", "thumb");

    await expect(page.getByTestId("result-view-image")).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId("result-view-image").locator("img"))
      .toHaveAttribute("src", /\/previews\/thumb$/);
    // Honest about what it is: a lossy preview, not the artifact.
    await expect(page.getByTestId("result-view-image")).toContainText("512×288");
  });

  test("a scalar port offers only Value and Raw, with no pointless one-row table", async ({ page }) => {
    await mockBackend(page);
    await loginAndOpenEditor(page);

    await page.getByTestId("pipeline-debug-toggle").click();
    await inspectTheItem(page);
    await closeDrawer(page);

    await clickPort(page, "sha512", "sha512");

    await expect(page.getByTestId("pipeline-result-detail")).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId("result-tab-json")).toBeVisible();
    await expect(page.getByTestId("result-tab-raw")).toBeVisible();
    await expect(page.getByTestId("result-tab-markdown")).toHaveCount(0);
    await expect(page.getByTestId("result-tab-image")).toHaveCount(0);
  });

  test("a MANY detection port draws one preview tile per element, uncollapsed", async ({ page }) => {
    await mockBackend(page);
    await loginAndOpenEditor(page);

    await page.getByTestId("pipeline-debug-toggle").click();
    await inspectTheItem(page);
    await closeDrawer(page);

    await clickPort(page, "det", "detections");
    await expect(page.getByTestId("pipeline-result-detail")).toBeVisible({ timeout: 5_000 });
    // The node described its own output, so Markdown opens first; the crops live behind Image.
    await expect(page.getByTestId("result-view-markdown")).toBeVisible();
    await page.getByTestId("result-tab-image").click();

    const grid = page.getByTestId("result-element-previews");
    await expect(grid).toBeVisible({ timeout: 5_000 });
    // One tile per element and no cap: the grid is the answer to "which 33 things did it find",
    // so a threshold that quietly dropped the tail would be worse than not drawing it at all.
    // If a collapse is ever introduced here, this count is what has to be updated with it.
    await expect(grid.locator("img")).toHaveCount(DETECTION_COUNT);
    await expect(page.getByTestId(`result-element-preview-${DETECTION_COUNT - 1}`)).toBeAttached();

    // The tiles are real bytes off the preview route, not alt text where a picture should be.
    expect(await loaded(page.getByTestId("result-element-preview-0"))).toBe(true);
    expect(await loaded(page.getByTestId(`result-element-preview-${DETECTION_COUNT - 1}`))).toBe(true);

    // The port-level frame carries the boxes; the crops carry the identities. Both, or the Image
    // tab is only half of what it claims to be.
    await expect(page.getByTestId("result-view-image").locator("img").first())
      .toHaveAttribute("src", /\/previews\/detections$/);
    expect(await page.getByTestId("result-view-image")
      .locator("[data-testid^='result-detection-box-']").count()).toBeGreaterThan(0);
  });

  test("the result strip collapses past three ports and counts the remainder", async ({ page }) => {
    await mockBackend(page);
    await loginAndOpenEditor(page);

    await page.getByTestId("pipeline-debug-toggle").click();
    await inspectTheItem(page);
    await closeDrawer(page);

    const card = page.getByTestId("pipeline-node-det");
    await expect(card.getByTestId("node-result-strip")).toBeVisible({ timeout: 5_000 });

    // A card has to stay a card, so the strip shows three ports and says how many it kept back.
    const hidden = DETECT_PORTS.length - MAX_STRIP_ROWS;
    expect(hidden).toBeGreaterThan(0);
    await expect(card.locator("[data-testid^='node-result-port-']")).toHaveCount(MAX_STRIP_ROWS);
    await expect(card.getByTestId("node-result-more")).toHaveText(`+${hidden} more`);

    for (const portId of DETECT_PORTS.slice(0, MAX_STRIP_ROWS)) {
      await expect(card.getByTestId(`node-result-port-${portId}`)).toBeVisible();
    }
    for (const portId of DETECT_PORTS.slice(MAX_STRIP_ROWS)) {
      await expect(card.getByTestId(`node-result-port-${portId}`)).toHaveCount(0);
    }

    // The label is a count, not a control: it does not expand, and the remaining ports are read
    // from the Results tab of the sidebar instead. Clicking it must not select the node either,
    // which is what would happen if the row swallowed the event and the card did not.
    await card.getByTestId("node-result-more").dispatchEvent("click");
    await expect(card.getByTestId(`node-result-port-${DETECT_PORTS[MAX_STRIP_ROWS]}`)).toHaveCount(0);
  });

  test("a preview that cannot be shown says so rather than showing a broken picture", async ({ page }) => {
    await mockBackend(page);
    // Registered last, so it wins over the bytes route: the metadata still promises an image and
    // 512×288, but the binary behind it is gone — an expired run directory, typically.
    await page.route("**/previews/thumb", route =>
      route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify({ message: "Not found" }) }));
    await loginAndOpenEditor(page);

    await page.getByTestId("pipeline-debug-toggle").click();
    await inspectTheItem(page);
    await closeDrawer(page);

    await clickPort(page, "src", "thumb");
    await expect(page.getByTestId("result-view-image")).toBeVisible({ timeout: 5_000 });

    // What survives the 404 is the note: the dimensions the server recorded, and the statement
    // that this was only ever a reduced copy. The <img> itself has no onError fallback today, so
    // it is the note that has to carry the information — assert it does.
    const note = page.getByTestId("result-image-note");
    await expect(note).toBeVisible();
    await expect(note).toContainText("512×288");
    expect(await loaded(page.getByTestId("result-view-image").locator("img"))).toBe(false);
    // Nothing per-element was written for this port, so no empty grid is drawn.
    await expect(page.getByTestId("result-element-previews")).toHaveCount(0);

    await page.getByTestId("pipeline-result-detail-close").click();
    await expect(page.getByTestId("pipeline-result-detail")).toBeHidden();

    // The other way a preview goes missing: the worker declined to make one. That is a different
    // statement from "the port emitted nothing", and the detail view makes it in words.
    await clickPort(page, "src", "huge");
    await expect(page.getByTestId("pipeline-result-detail")).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId("result-tab-image")).toHaveCount(0);
    await expect(page.getByTestId("result-preview-skipped")).toContainText("98304 bytes");
    // Raw is the verbatim payload and nothing else, so the note steps aside there.
    await page.getByTestId("result-tab-raw").click();
    await expect(page.getByTestId("result-preview-skipped")).toHaveCount(0);
  });

  test("a media port shows the path it carried, since the browser cannot open it", async ({ page }) => {
    await mockBackend(page);
    await loginAndOpenEditor(page);

    await page.getByTestId("pipeline-debug-toggle").click();
    await inspectTheItem(page);
    await closeDrawer(page);

    await clickPort(page, "src", "media");

    // A playable value on a `media/*` port opens the player first — and the player is a statement,
    // because the value is a path on the worker that holds the file.
    await expect(page.getByTestId("result-view-media")).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId("result-media-path"))
      .toHaveText("/media/library/holiday-clip.mp4");
    await expect(page.getByTestId("result-view-media").locator("video, audio")).toHaveCount(0);
    // No preview was written for this port, so there is nothing to show as an image.
    await expect(page.getByTestId("result-tab-image")).toHaveCount(0);
  });

  test("the Results tab says results are per item when none is selected", async ({ page }) => {
    await mockBackend(page);
    await loginAndOpenEditor(page);

    await page.getByTestId("pipeline-node-sha512").click();
    await page.getByRole("tab", { name: "Results" }).click();

    await expect(page.getByTestId("node-results-no-item")).toBeVisible({ timeout: 5_000 });
  });
});
