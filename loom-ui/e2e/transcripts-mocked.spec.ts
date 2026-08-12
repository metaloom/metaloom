import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for transcript editing on the Asset Detail view — no running Loom
 * backend required. All `/api/v1/**` calls are intercepted via `page.route`,
 * with a small in-memory transcript store so edit → delete round-trips against
 * the asset-scoped routes:
 *   GET/POST   /api/v1/assets/:uuid/transcripts
 *   GET/POST/DELETE /api/v1/assets/:uuid/transcripts/:transcriptUuid
 *
 * The transcript panel renders inline in the left media column (not a tab), so
 * it appears as soon as the asset detail loads with a non-empty transcript list.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const ASSET_UUID = "22222222-2222-2222-2222-222222222222";
const TRANSCRIPT_UUID = "33333333-3333-3333-3333-333333333333";

interface StoredTranscript {
  uuid: string;
  assetUuid: string;
  source?: string;
  lang?: string;
  transcriptJson: { sections: Array<{ id: string; title: string; startTime: number; endTime: number; words: Array<{ word: string; startTime: number; endTime: number; confidence: number }> }> };
}

interface Captured {
  creates: Record<string, unknown>[];
  updates: unknown[];
  deletes: string[];
}

function newCaptured(): Captured {
  return { creates: [], updates: [], deletes: [] };
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

function seedTranscript(): StoredTranscript {
  return {
    uuid: TRANSCRIPT_UUID,
    assetUuid: ASSET_UUID,
    source: "whisper",
    lang: "en",
    transcriptJson: {
      sections: [
        {
          id: "s1",
          title: "Introduction",
          startTime: 0,
          endTime: 5,
          words: [
            { word: "hello", startTime: 0, endTime: 1, confidence: 0.9 },
            { word: "world", startTime: 1, endTime: 2, confidence: 0.9 },
          ],
        },
      ],
    },
  };
}

async function installMocks(page: Page, captured: Captured, options: { createStatus?: number; seed?: StoredTranscript[] } = {}) {
  const transcripts: StoredTranscript[] = options.seed ?? [seedTranscript()];

  // Catch-all first (lowest priority) — empty collections for the many list
  // endpoints AssetDetail fans out to (reactions, comments, tasks, …).
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

  // Asset-scoped transcripts: list + create
  await page.route(/\/api\/v1\/assets\/[^/]+\/transcripts$/, route => {
    if (route.request().method() === "POST") {
      const body = JSON.parse(route.request().postData() || "{}");
      captured.creates.push(body);
      if (options.createStatus && options.createStatus >= 400) {
        return json(route, { message: "the transcript could not be created" }, options.createStatus);
      }
      const created: StoredTranscript = {
        uuid: `transcript-${transcripts.length + 1}`,
        assetUuid: ASSET_UUID,
        source: body.source,
        lang: body.lang,
        transcriptJson: body.transcriptJson ?? { sections: [] },
      };
      transcripts.push(created);
      return json(route, created, 201);
    }
    return json(route, { data: transcripts });
  });

  // Transcript by uuid: update (POST) + delete
  await page.route(/\/api\/v1\/assets\/[^/]+\/transcripts\/[^/]+$/, route => {
    const uuid = decodeURIComponent(route.request().url().split("/transcripts/")[1].split("?")[0]);
    if (route.request().method() === "DELETE") {
      captured.deletes.push(uuid);
      const idx = transcripts.findIndex(t => t.uuid === uuid);
      if (idx >= 0) transcripts.splice(idx, 1);
      return route.fulfill({ status: 204, body: "" });
    }
    // POST update
    const body = route.request().postDataJSON();
    captured.updates.push(body);
    const found = transcripts.find(t => t.uuid === uuid);
    if (found && body.transcriptJson) found.transcriptJson = body.transcriptJson;
    return json(route, found ?? {}, 200);
  });
}

async function loginAndOpenAssetDetail(page: Page, captured: Captured, options: { createStatus?: number; seed?: StoredTranscript[] } = {}) {
  await installMocks(page, captured, options);
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
}

test.describe("Transcripts – mocked e2e", () => {
  test("editing a section title and blurring persists via updateTranscript", async ({ page }) => {
    const captured = newCaptured();
    await loginAndOpenAssetDetail(page, captured);

    const titleField = page.getByTestId("transcript-section-title");
    await expect(titleField).toBeVisible({ timeout: 10_000 });

    // Edit the section title, then blur via Enter to commit.
    await titleField.fill("Opening remarks");
    await Promise.all([
      page.waitForRequest(req =>
        /\/api\/v1\/assets\/[^/]+\/transcripts\/[^/]+$/.test(req.url()) && req.method() === "POST"
      ),
      titleField.press("Enter"),
    ]);

    expect(captured.updates).toHaveLength(1);
    const body = captured.updates[0] as { transcriptJson?: { sections?: Array<{ title: string }> } };
    expect(body.transcriptJson?.sections?.[0]?.title).toBe("Opening remarks");
  });

  test("deleting a transcript removes it from the panel", async ({ page }) => {
    const captured = newCaptured();
    await loginAndOpenAssetDetail(page, captured);

    const deleteBtn = page.getByTestId("transcript-delete");
    await expect(deleteBtn).toBeVisible({ timeout: 10_000 });

    await Promise.all([
      page.waitForRequest(req =>
        /\/api\/v1\/assets\/[^/]+\/transcripts\/[^/]+$/.test(req.url()) && req.method() === "DELETE"
      ),
      deleteBtn.click(),
    ]);

    expect(captured.deletes).toEqual([TRANSCRIPT_UUID]);
    await expect(page.getByTestId("transcript-section-title")).toBeHidden({ timeout: 10_000 });
  });

  test("the overflow menu opens the add-transcript dialog", async ({ page }) => {
    const captured = newCaptured();
    await loginAndOpenAssetDetail(page, captured);

    await page.getByTestId("asset-actions-menu-button").click();
    await page.getByTestId("asset-transcript-create-menu-item").click();

    await expect(page.getByRole("heading", { name: "Add Transcript" })).toBeVisible();
    await expect(page.getByTestId("transcript-create-source-input")).toBeVisible();
    await expect(page.getByTestId("transcript-create-lang-input")).toBeVisible();

    // Opening the dialog must not have written anything — the transcript is created on submit.
    expect(captured.creates).toHaveLength(0);
  });

  test("creating a transcript posts source and language and closes the dialog", async ({ page }) => {
    // Seed nothing, so the panel that appears afterwards can only be the new transcript.
    const captured = newCaptured();
    await loginAndOpenAssetDetail(page, captured, { seed: [] });
    await expect(page.getByTestId("asset-actions-menu-button")).toBeVisible({ timeout: 10_000 });

    await page.getByTestId("asset-actions-menu-button").click();
    await page.getByTestId("asset-transcript-create-menu-item").click();

    await page.getByTestId("transcript-create-source-input").fill("manual");
    await page.getByTestId("transcript-create-lang-input").fill("de");
    await Promise.all([
      page.waitForRequest(req =>
        /\/api\/v1\/assets\/[^/]+\/transcripts$/.test(req.url()) && req.method() === "POST"
      ),
      page.getByTestId("transcript-create-submit-button").click(),
    ]);

    expect(captured.creates).toHaveLength(1);
    // Both fields travel, and the transcript starts empty — the dialog captures provenance,
    // not content, and the sections are typed in afterwards.
    expect(captured.creates[0]).toMatchObject({
      source: "manual",
      lang: "de",
      transcriptJson: { sections: [] },
    });

    // The dialog closes and the new transcript joins the panel under its "source · lang" heading.
    await expect(page.getByRole("heading", { name: "Add Transcript" })).toHaveCount(0);
    await expect(page.getByText("manual · de")).toBeVisible({ timeout: 10_000 });
  });

  test("a create that fails leaves the dialog open with what was typed", async ({ page }) => {
    const captured = newCaptured();
    await loginAndOpenAssetDetail(page, captured, { seed: [], createStatus: 500 });
    await expect(page.getByTestId("asset-actions-menu-button")).toBeVisible({ timeout: 10_000 });

    await page.getByTestId("asset-actions-menu-button").click();
    await page.getByTestId("asset-transcript-create-menu-item").click();
    await page.getByTestId("transcript-create-source-input").fill("manual");
    await page.getByTestId("transcript-create-lang-input").fill("de");
    await page.getByTestId("transcript-create-submit-button").click();

    await expect(page.getByText("Failed to add transcript")).toBeVisible({ timeout: 10_000 });

    // Closing on a failed write would read as success and lose the typed fields with it.
    await expect(page.getByRole("heading", { name: "Add Transcript" })).toBeVisible();
    await expect(page.getByTestId("transcript-create-source-input")).toHaveValue("manual");
    expect(captured.creates).toHaveLength(1);
  });
});
