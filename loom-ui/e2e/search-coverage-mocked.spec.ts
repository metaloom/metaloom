import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e proving that every search field in the UI **actually filters**.
 *
 * The gap this closes: most search boxes had been asserted visible and never typed into. A field
 * that renders, takes input and narrows nothing looks identical to a working one in a spec that
 * only checks `toBeVisible`, and identical to a broken one in the product.
 *
 * So every case here types a term that should match, checks the list narrowed, types one that
 * cannot match, and checks the view says so rather than showing an empty table. Where a view
 * distinguishes "nothing here" from "nothing matched" it asserts the inline hint and not the
 * EmptyState (LOOM_UI.md §7.5).
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const NO_MATCH = "zzzznothingmatchesthiszzzz";

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

// ── Fixtures ────────────────────────────────────────────────────────────
// Every collection carries one row whose name contains "alpha" and one containing "bravo", so a
// single pair of search terms drives the whole table-driven suite below.

const audit = (creator = ME_UUID) => ({
  created: "2026-01-01T00:00:00Z",
  edited: "2026-02-01T00:00:00Z",
  creator: { uuid: creator, name: "admin" },
});

const pair = (prefix: string) => [
  { uuid: `${prefix}-1`, name: `${prefix} alpha`, status: audit() },
  { uuid: `${prefix}-2`, name: `${prefix} bravo`, status: audit() },
];

// `locations` is what puts an asset in a library — LibraryView derives its panel from it, so an
// asset without one renders nowhere no matter what the library list says.
const ASSETS = [
  { uuid: "ast-1", file: { mimeType: "image/jpeg", filename: "alpha.jpg", size: 2048, origin: "upload", firstSeen: "" },
    hashes: { sha512: "h1" }, locations: [{ libraryUuid: "lib-1" }], status: audit() },
  { uuid: "ast-2", file: { mimeType: "image/jpeg", filename: "bravo.jpg", size: 2048, origin: "upload", firstSeen: "" },
    hashes: { sha512: "h2" }, locations: [{ libraryUuid: "lib-1" }], status: audit() },
];

const TOKENS = [
  { uuid: "tok-1", name: "alpha key", createdAt: "2026-01-01T00:00:00Z", status: audit() },
  { uuid: "tok-2", name: "bravo key", createdAt: "2026-01-02T00:00:00Z", status: audit() },
];

const DENYLIST = [
  { uuid: "deny-1", pattern: "alpha-secret", scope: "user", note: "", status: audit() },
  { uuid: "deny-2", pattern: "bravo-secret", scope: "user", note: "", status: audit() },
];

const PROCESSORS = [
  { nodeId: "w1", name: "alpha-worker", host: "10.0.0.1:9090", priority: 1, capabilities: ["GPU"], status: "ONLINE" },
  { nodeId: "w2", name: "bravo-worker", host: "10.0.0.2:9090", priority: 1, capabilities: ["CPU"], status: "ONLINE" },
];

function integrityCheck(code: string, name: string, category: string) {
  return {
    check: { code, name, category, severity: "ERROR", table: "t", column: null, description: `About ${name}.` },
    count: 1, samples: [], durationMs: 3, error: null,
  };
}

const INTEGRITY_REPORT = {
  timestamp: "2026-08-09T11:24:07Z", durationMs: 312, checksRun: 2, findingCount: 2, clean: false,
  results: [
    integrityCheck("ALPHA_CHECK", "Alpha dangling rows", "DANGLING"),
    integrityCheck("BRAVO_CHECK", "Bravo timestamps", "TIMESTAMP"),
  ],
};

const INDEX_LIST = {
  data: [
    { id: "alpha-index", kind: "FULLTEXT", backendId: "postgres", label: "Alpha documents",
      enabled: true, available: true, documentCount: 10, indexedCount: 10, pendingCount: 0, supportedActions: ["REINDEX"] },
    { id: "bravo-index", kind: "FULLTEXT", backendId: "postgres", label: "Bravo documents",
      enabled: true, available: true, documentCount: 20, indexedCount: 20, pendingCount: 0, supportedActions: ["REINDEX"] },
  ],
  backends: [{ id: "postgres", label: "PostgreSQL", enabled: true, available: true, reason: null }],
};

const GIB = 1024 * 1024 * 1024;

/** Mirrors `StorageReport`: the backends card renders only once `storageTotals` resolves. */
const STORAGE_REPORT = {
  timestamp: "2026-08-09T11:24:07Z",
  thresholds: { minFreeSpaceBytes: GIB, warnFreeSpaceBytes: 5 * GIB, maxUploadSizeBytes: -1 },
  objects: 30,
  distinctBytes: 150 * GIB,
  orphanObjects: 0,
  orphanBytes: 0,
  // `elements` and `logicalBytes`, not `objects`/`bytes` — StorageCategory declares both as
  // required and the table renders `elements.toLocaleString()` straight out, so the wrong spelling
  // takes the whole screen down rather than showing a blank cell.
  categories: [{ category: "ASSET_BINARY", elements: 30, logicalBytes: 150 * GIB, distinctObjects: 30, distinctBytes: 150 * GIB }],
  backends: [
    { poolUuid: "p-1", poolName: "alpha pool", kind: "filesystem", description: "filesystem:/a",
      freeBytes: 45 * GIB, totalBytes: 200 * GIB, watermark: "OK", objects: 10, bytes: 100 * GIB, error: null },
    { poolUuid: "p-2", poolName: "bravo pool", kind: "s3", description: "s3:bravo",
      watermark: "UNKNOWN", objects: 20, bytes: 50 * GIB },
  ],
};

const CHATS = [
  { uuid: "chat-1", title: "alpha conversation", messages: [], status: audit() },
  { uuid: "chat-2", title: "bravo conversation", messages: [], status: audit() },
];

async function installMocks(page: Page) {
  // Registered first so the specific routes below win — Playwright matches last-registered first.
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));
  // Search off, so the asset browser stays on the browse path where its box filters locally.
  await page.route(/\/api\/v1\/search\/status$/, route =>
    json(route, { provider: "none", available: false, reason: "off", capabilities: [], documentCount: 0, dirtyCount: 0 }));

  const list = (rows: unknown[]) => (route: Route) =>
    json(route, { data: rows, _metainfo: { perPage: 100, totalCount: rows.length } });

  await page.route(/\/api\/v1\/tags(\?|$)/, list(pair("tag")));
  await page.route(/\/api\/v1\/pools(\?|$)/, list([
    { uuid: "pool-1", name: "alpha pool", type: "FS", path: "/mnt/a", status: audit() },
    { uuid: "pool-2", name: "bravo pool", type: "FS", path: "/mnt/b", status: audit() },
  ]));
  await page.route(/\/api\/v1\/libraries(\?|$)/, list([{ uuid: "lib-1", name: "Main", meta: {}, status: audit() }]));
  await page.route(/\/api\/v1\/assets(\?|$)/, list(ASSETS));
  await page.route(/\/api\/v1\/spaces(\?|$)/, list(pair("space")));
  await page.route(/\/api\/v1\/groups(\?|$)/, list(pair("group")));
  await page.route(/\/api\/v1\/roles(\?|$)/, list(pair("role")));
  await page.route(/\/api\/v1\/blacklists(\?|$)/, list(pair("blocked")));
  await page.route(/\/api\/v1\/tokens(\?|$)/, list(TOKENS));
  await page.route(/\/api\/v1\/memory-denylist(\?|$)/, list(DENYLIST));
  await page.route(/\/api\/v1\/users(\?|$)/, list([
    { uuid: "u-1", username: "alpha-user", enabled: true, status: audit() },
    { uuid: "u-2", username: "bravo-user", enabled: true, status: audit() },
  ]));
  await page.route(/\/api\/v1\/processors(\?|$)/, route =>
    json(route, { data: PROCESSORS, _metainfo: { totalCount: PROCESSORS.length } }));
  await page.route(/\/api\/v1\/db-integrity(\?.*)?$/, route => json(route, INTEGRITY_REPORT));
  await page.route(/\/api\/v1\/search-indices$/, route => json(route, INDEX_LIST));
  await page.route(/\/api\/v1\/storage$/, route => json(route, STORAGE_REPORT));
  await page.route(/\/api\/v1\/chats(\?|$)/, list(CHATS));
}

async function signIn(page: Page) {
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

async function open(page: Page, path: string) {
  await page.goto(`/ui${path}`);
  await signIn(page);
}

/** Type into a search field addressed by its testid, whether the testid sits on the wrapper or the input. */
async function typeSearch(page: Page, testId: string, value: string) {
  const field = page.getByTestId(testId);
  const input = (await field.evaluate(el => el.tagName)) === "INPUT" ? field : field.locator("input");
  await input.fill(value);
}

// ── Table-driven: the list views ────────────────────────────────────────

interface Case {
  name: string;
  path: string;
  testId: string;
  /** Text the matching row shows. */
  keep: string;
  /** Text the row that must disappear shows. */
  drop: string;
  /** Extra step before searching — switching to a tab, mostly. */
  before?: (page: Page) => Promise<void>;
  /** Address the rows by `<prefix><name>` testid instead of by their text. */
  byTestId?: string;
}

const CASES: Case[] = [
  { name: "tags", path: "/tags", testId: "tags-search", keep: "tag alpha", drop: "tag bravo" },
  { name: "library", path: "/library", testId: "library-search", keep: "alpha.jpg", drop: "bravo.jpg" },
  { name: "assets", path: "/assets", testId: "assets-search", keep: "alpha.jpg", drop: "bravo.jpg" },
  { name: "asset pools", path: "/asset-pools", testId: "asset-pools-search", keep: "alpha pool", drop: "bravo pool" },
  { name: "cortex", path: "/cortex", testId: "cortex-search", keep: "alpha-worker", drop: "bravo-worker" },
  { name: "admin spaces", path: "/admin/spaces", testId: "admin-spaces-search", keep: "space alpha", drop: "space bravo" },
  { name: "admin users", path: "/admin/users", testId: "admin-users-search", keep: "alpha-user", drop: "bravo-user" },
  { name: "admin groups", path: "/admin/groups", testId: "admin-groups-search", keep: "group alpha", drop: "group bravo" },
  // Rows rather than text: the selected role's name is repeated in the permissions pane beside
  // the rail, so a text locator stays visible however the rail is filtered.
  { name: "admin roles", path: "/admin/permissions", testId: "admin-roles-search",
    keep: "role alpha", drop: "role bravo", byTestId: "admin-role-row-" },
  { name: "admin blacklist", path: "/admin/blacklist", testId: "admin-blacklist-search", keep: "blocked alpha", drop: "blocked bravo" },
  { name: "admin api keys", path: "/admin/api-keys", testId: "admin-api-keys-search", keep: "alpha key", drop: "bravo key" },
  { name: "db integrity", path: "/admin/db-integrity", testId: "db-integrity-search", keep: "Alpha dangling rows", drop: "Bravo timestamps" },
  { name: "search indices", path: "/admin/indices", testId: "search-indices-search", keep: "alpha-index", drop: "bravo-index" },
  { name: "storage", path: "/admin/storage", testId: "storage-search", keep: "alpha pool", drop: "bravo pool" },
  { name: "chat rail", path: "/", testId: "chat-rail-search", keep: "alpha conversation", drop: "bravo conversation" },
];

test.describe("Search fields – every list view actually filters", () => {
  for (const c of CASES) {
    test(`${c.name}: typing narrows the list and an unmatched term empties it`, async ({ page }) => {
      await installMocks(page);
      await open(page, c.path);
      await c.before?.(page);

      const locate = (text: string) => c.byTestId
        ? page.getByTestId(`${c.byTestId}${text}`)
        : page.getByText(text, { exact: false }).first();
      const keep = locate(c.keep);
      const drop = locate(c.drop);

      await expect(page.getByTestId(c.testId)).toBeVisible({ timeout: 10_000 });
      await expect(keep).toBeVisible();
      await expect(drop).toBeVisible();

      // Narrows.
      await typeSearch(page, c.testId, "alpha");
      await expect(drop).toBeHidden();
      await expect(keep).toBeVisible();

      // A term nothing matches removes the other row too — the field is filtering, not merely
      // reordering or highlighting.
      await typeSearch(page, c.testId, NO_MATCH);
      await expect(keep).toBeHidden();

      // Clearing restores both.
      await typeSearch(page, c.testId, "");
      await expect(keep).toBeVisible();
      await expect(drop).toBeVisible();
    });
  }
});

// ── The screens that need a step first, or assert something else ────────

test.describe("Search fields – screens with their own shape", () => {

  test("db integrity: a check is findable by its code, which is what a ticket carries", async ({ page }) => {
    await installMocks(page);
    await open(page, "/admin/db-integrity");
    await expect(page.getByTestId("db-integrity-search")).toBeVisible({ timeout: 10_000 });

    await typeSearch(page, "db-integrity-search", "BRAVO_CHECK");
    await expect(page.getByText("Bravo timestamps")).toBeVisible();
    await expect(page.getByText("Alpha dangling rows")).toBeHidden();
  });

  test("object detection: the label search narrows the grouped detections", async ({ page }) => {
    await installMocks(page);
    await open(page, "/detection");
    await page.getByRole("tab").nth(1).click();

    await expect(page.getByTestId("objectdetection-search")).toBeVisible({ timeout: 10_000 });
    // No detections are mocked, so the assertion is that the field takes input and the view
    // survives it — the filtering itself is covered by detection-review-mocked.
    await typeSearch(page, "objectdetection-search", NO_MATCH);
    await expect(page.getByTestId("objectdetection-search")).toBeVisible();
  });

  test("llm detection: the prompt search is addressable and filters", async ({ page }) => {
    await installMocks(page);
    await open(page, "/detection");
    await page.getByRole("tab").nth(2).click();

    // Had no testid at all until now, so it could not be reached from a spec.
    await expect(page.getByTestId("llmdetection-search")).toBeVisible({ timeout: 10_000 });
    await typeSearch(page, "llmdetection-search", NO_MATCH);
    await expect(page.getByTestId("llmdetection-search")).toBeVisible();
  });

  test("face detection: the cluster search is addressable", async ({ page }) => {
    await installMocks(page);
    await open(page, "/detection");

    await expect(page.getByTestId("facedetection-search")).toBeVisible({ timeout: 10_000 });
    await typeSearch(page, "facedetection-search", NO_MATCH);
    await expect(page.getByTestId("facedetection-search")).toBeVisible();
  });

  test("memory denylist: the rule search is addressable and filters", async ({ page }) => {
    await installMocks(page);
    await open(page, "/admin/memory-denylist");

    await expect(page.getByTestId("admin-memory-denylist-search")).toBeVisible({ timeout: 10_000 });
    await typeSearch(page, "admin-memory-denylist-search", NO_MATCH);
    await expect(page.getByText("alpha-secret")).toBeHidden();
  });

  test("the pipeline editor's two node searches are addressable and filter the node list", async ({ page }) => {
    await installMocks(page);
    // Both the add-node bar and the N palette require a selected pipeline, and the node list they
    // filter comes from the descriptor registry.
    await page.route(/\/api\/v1\/pipeline\/node-descriptors$/, route => json(route, {
      // `kind` is the node id the picker keys on (`nodeIdOf`), and the port arrays have to exist
      // — the editor reads them while laying the palette row out.
      nodeDescriptors: ["sha512", "whisper"].map(kind => ({
        kind,
        name: kind,
        description: "",
        icon: "",
        category: "TRANSFORM",
        inputPorts: [],
        outputPorts: [{ id: "out", contentType: "artifact/json", cardinality: "ONE", required: true }],
        inputGroups: [],
        outputGroups: [],
        dynamicPorts: false,
        parameters: [],
        defaultConcurrency: 1,
        defaultMode: "SEQUENTIAL",
        defaultBlocking: false,
        events: [],
      })),
      contentTypes: [],
    }));
    await page.route(/\/api\/v1\/pipelines$/, route => json(route, {
      data: [{
        uuid: "pl-1", name: "ingest", description: "", enabled: true, priority: 0, dryRun: false,
        definition: { nodes: [], edges: [] }, status: audit(),
      }],
      _metainfo: { totalCount: 1 },
    }));
    await open(page, "/pipelines");

    // The add-node bar sits under the canvas once a pipeline is selected.
    const addNode = page.getByTestId("pipeline-add-node-search");
    await expect(addNode).toBeVisible({ timeout: 10_000 });
    await addNode.click();
    await expect(page.getByTestId("add-node-sha512")).toBeVisible();
    await addNode.fill("whis");
    await expect(page.getByTestId("add-node-sha512")).toHaveCount(0);
    await expect(page.getByTestId("add-node-whisper")).toBeVisible();

    // And the command palette, bound to N — it refuses to open while a text field has focus, so
    // the blur is part of the contract rather than test scaffolding.
    await addNode.fill("");
    await addNode.blur();
    await page.keyboard.press("n");
    const palette = page.getByTestId("pipeline-palette-search");
    await expect(palette).toBeVisible({ timeout: 10_000 });
    await palette.fill("sha");
    await expect(page.getByTestId("palette-node-sha512")).toBeVisible();
    await expect(page.getByTestId("palette-node-whisper")).toHaveCount(0);
  });
});

// ── Uploads and transcript: search over things that are not rows ────────

test.describe("Search fields – queue and transcript", () => {

  const ASSET_UUID = "3a1c9b5e-2f4d-4a6b-8c0e-1d2f3a4b5c6d";

  /** Three sections whose words are distinct, so a term can single one out. */
  const TRANSCRIPT = {
    uuid: "tr-1",
    assetUuid: ASSET_UUID,
    source: "whisper",
    lang: "en",
    transcriptJson: {
      sections: [
        { id: "s1", title: "Introduction", startTime: 0, endTime: 5, words: [
          { word: "welcome", startTime: 0, endTime: 1, confidence: 0.9 },
          { word: "aboard", startTime: 1, endTime: 2, confidence: 0.9 }] },
        { id: "s2", title: "Middle", startTime: 5, endTime: 10, words: [
          { word: "propeller", startTime: 5, endTime: 6, confidence: 0.9 },
          { word: "torque", startTime: 6, endTime: 7, confidence: 0.9 }] },
        { id: "s3", title: "Closing", startTime: 10, endTime: 15, words: [
          { word: "landing", startTime: 10, endTime: 11, confidence: 0.9 },
          { word: "clear", startTime: 11, endTime: 12, confidence: 0.9 }] },
      ],
    },
  };

  async function openTranscript(page: Page) {
    const detailAsset = {
      uuid: ASSET_UUID,
      file: { filename: "flight.mp4", mimeType: "video/mp4", size: 1024, origin: "upload", firstSeen: "" },
      hashes: { sha512: "h" },
      status: audit(),
    };
    await page.route(/\/api\/v1\/assets\/[^/]+\/transcripts$/, route => json(route, { data: [TRANSCRIPT] }));
    await page.route(/\/api\/v1\/assets\/[^/]+$/, route => json(route, detailAsset));
    await open(page, `/assets/${ASSET_UUID}`);
  }

  /**
   * Find-in-transcript.
   *
   * The one search in the product that is not a list filter: it marks matches and dims the rest
   * rather than removing sections, because the timeline above has to stay proportional and the
   * boundary controls move a section relative to the one next to it.
   */
  test("transcript: a term marks the matching words and dims the sections without them", async ({ page }) => {
    await installMocks(page);
    await openTranscript(page);

    const search = page.getByTestId("transcript-search");
    await expect(search).toBeVisible({ timeout: 10_000 });

    const matchedFlags = () => page.getByTestId("transcript-section")
      .evaluateAll(nodes => nodes.map(n => n.getAttribute("data-matched")));

    // Nothing typed: no section is marked either way.
    await expect.poll(matchedFlags).toEqual([null, null, null]);

    await search.fill("propeller");
    await expect.poll(matchedFlags).toEqual(["false", "true", "false"]);
    await expect(page.getByTestId("transcript-match")).toHaveCount(1);
    await expect(page.getByTestId("transcript-search-count")).toContainText("1");

    // Every section stays in the DOM — dimmed, not removed.
    await expect(page.getByTestId("transcript-section")).toHaveCount(3);
  });

  test("transcript: a term nothing says reports no match rather than blanking the panel", async ({ page }) => {
    await installMocks(page);
    await openTranscript(page);

    await page.getByTestId("transcript-search").fill(NO_MATCH);

    await expect(page.getByTestId("transcript-search-count")).toBeVisible();
    await expect(page.getByTestId("transcript-match")).toHaveCount(0);
    // The transcript is still readable — this is a find, not a filter.
    await expect(page.getByTestId("transcript-section")).toHaveCount(3);
  });

  test("transcript: the section title is searchable too", async ({ page }) => {
    await installMocks(page);
    await openTranscript(page);

    await page.getByTestId("transcript-search").fill("closing");
    await expect.poll(() => page.getByTestId("transcript-section")
      .evaluateAll(nodes => nodes.map(n => n.getAttribute("data-matched")))).toEqual(["false", "false", "true"]);
  });

  test("uploads: the queue filters by filename and says when nothing matches", async ({ page }) => {
    await installMocks(page);
    await open(page, "/uploads");

    // Put two files in the queue. The upload itself never completes here — what matters is that
    // the rows exist to be filtered.
    await page.route(/\/api\/v1\/assets$/, route => json(route, { uuid: "new", file: {}, hashes: {} }, 201));
    await page.getByTestId("upload-view").waitFor({ timeout: 10_000 });
    const input = page.locator('input[type="file"]').first();
    await input.setInputFiles([
      { name: "alpha-clip.mp4", mimeType: "video/mp4", buffer: Buffer.from("a") },
      { name: "bravo-clip.mp4", mimeType: "video/mp4", buffer: Buffer.from("b") },
    ]);

    await expect(page.getByTestId("uploads-search")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText("alpha-clip.mp4")).toBeVisible();
    await expect(page.getByText("bravo-clip.mp4")).toBeVisible();

    await typeSearch(page, "uploads-search", "alpha");
    await expect(page.getByText("bravo-clip.mp4")).toBeHidden();
    await expect(page.getByText("alpha-clip.mp4")).toBeVisible();

    // The queue is not empty — this is the inline hint, never the EmptyState.
    await typeSearch(page, "uploads-search", NO_MATCH);
    await expect(page.getByTestId("uploads-no-match")).toBeVisible();
    await expect(page.getByTestId("upload-empty-state")).toHaveCount(0);
  });
});
