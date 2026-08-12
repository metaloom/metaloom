import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for annotation authoring + reactions on the Asset Detail view — no
 * running Loom backend required. All `/api/v1/**` calls are intercepted via
 * `page.route`, with small in-memory stores so create/edit/delete and reaction
 * round-trips work against:
 *   POST /api/v1/annotations                       (create)
 *   POST /api/v1/annotations/:uuid                 (update)
 *   DELETE /api/v1/annotations/:uuid               (delete)
 *   GET/POST /api/v1/annotations/:uuid/reactions   and
 *   DELETE /api/v1/annotations/:uuid/reactions/:reactionUuid
 * Annotations are delivered embedded on the asset response (resp.annotations),
 * matching how AssetDetail loads them.
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const OTHER_UUID = "99999999-9999-9999-9999-999999999999";
const ASSET_UUID = "22222222-2222-2222-2222-222222222222";

/** A 1x1 JPEG, so the media pane lays out a real image for the region drag rather than a broken one. */
const TINY_JPEG = Buffer.from(
  "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==",
  "base64",
);

/** What the browser actually sent, so a *missing* write can be asserted rather than inferred. */
interface Recorder {
  /** Bodies of every POST /annotations. */
  creates: Record<string, unknown>[];
  /** Bodies of every POST /annotations/:uuid (the update route). */
  updates: { uuid: string; body: Record<string, unknown> }[];
}

function recorder(): Recorder {
  return { creates: [], updates: [] };
}

interface StoredAnnotation {
  uuid: string;
  type?: string;
  title?: string;
  description?: string;
  assetUuid?: string;
  area?: Record<string, number>;
  status: { creator: { uuid: string }; created: string };
}

interface StoredReaction {
  uuid: string;
  type: string;
  status: { creator: { uuid: string }; created: string };
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function annotation(overrides: Partial<StoredAnnotation> = {}): StoredAnnotation {
  return {
    uuid: overrides.uuid ?? "aaaa1111-0000-0000-0000-000000000001",
    type: "FEEDBACK",
    title: overrides.title ?? "Seeded annotation",
    description: overrides.description ?? "seeded description",
    assetUuid: ASSET_UUID,
    status: { creator: { uuid: overrides.status?.creator?.uuid ?? ME_UUID }, created: new Date().toISOString() },
    ...overrides,
  };
}

async function installMocks(page: Page, seed: StoredAnnotation[], rec: Recorder) {
  const annotations: StoredAnnotation[] = [...seed];
  const reactions: StoredReaction[] = [];
  let seq = 0;

  function asset() {
    return {
      uuid: ASSET_UUID,
      file: { filename: "mock-asset.jpg", mimeType: "image/jpeg", size: 1024 },
      status: { creator: { uuid: ME_UUID } },
      annotations,
    };
  }

  // Catch-all first (lowest priority) — empty collections for the many list
  // endpoints AssetDetail fans out to (transcripts, comments, reactions, …).
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));

  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route =>
    json(route, { uuid: ME_UUID, username: "admin", enabled: true })
  );

  await page.route(/\/api\/v1\/assets(\?|$)/, route =>
    json(route, { data: [asset()], _metainfo: { totalCount: 1 } })
  );
  await page.route(/\/api\/v1\/assets\/[^/]+$/, route => json(route, asset()));
  await page.route(/\/api\/v1\/assets\/[^/]+\/binary\/data/, route =>
    route.fulfill({ status: 200, contentType: "image/jpeg", body: TINY_JPEG })
  );

  // Annotation reactions: list + create.
  await page.route(/\/api\/v1\/annotations\/[^/]+\/reactions$/, route => {
    if (route.request().method() === "POST") {
      const body = JSON.parse(route.request().postData() || "{}");
      const created: StoredReaction = {
        uuid: `reaction-${++seq}`,
        type: body.type ?? "THUMBSUP",
        status: { creator: { uuid: ME_UUID }, created: new Date().toISOString() },
      };
      reactions.push(created);
      return json(route, created, 201);
    }
    return json(route, { data: reactions });
  });

  // Annotation reaction by uuid: delete.
  await page.route(/\/api\/v1\/annotations\/[^/]+\/reactions\/[^/]+$/, route => {
    const uuid = decodeURIComponent(route.request().url().split("/reactions/")[1].split("?")[0]);
    const idx = reactions.findIndex(r => r.uuid === uuid);
    if (idx >= 0) reactions.splice(idx, 1);
    return route.fulfill({ status: 204, body: "" });
  });

  // Annotation by uuid: update (POST) + delete (DELETE).
  await page.route(/\/api\/v1\/annotations\/[^/]+$/, route => {
    const uuid = decodeURIComponent(route.request().url().split("/annotations/")[1].split("?")[0]);
    if (route.request().method() === "DELETE") {
      const idx = annotations.findIndex(a => a.uuid === uuid);
      if (idx >= 0) annotations.splice(idx, 1);
      return route.fulfill({ status: 204, body: "" });
    }
    // POST update
    const body = JSON.parse(route.request().postData() || "{}");
    rec.updates.push({ uuid, body });
    const existing = annotations.find(a => a.uuid === uuid);
    const updated: StoredAnnotation = {
      ...(existing ?? annotation({ uuid })),
      ...body,
      uuid,
      status: { creator: { uuid: existing?.status.creator.uuid ?? ME_UUID }, created: existing?.status.created ?? new Date().toISOString() },
    };
    if (existing) Object.assign(existing, updated);
    return json(route, updated);
  });

  // Annotations collection: create (POST).
  await page.route(/\/api\/v1\/annotations$/, route => {
    if (route.request().method() === "POST") {
      const body = JSON.parse(route.request().postData() || "{}");
      rec.creates.push(body);
      const created: StoredAnnotation = {
        uuid: `created-${++seq}`,
        type: body.type ?? "FEEDBACK",
        title: body.title,
        description: body.description,
        assetUuid: body.assetUuid ?? ASSET_UUID,
        area: body.area,
        status: { creator: { uuid: ME_UUID }, created: new Date().toISOString() },
      };
      annotations.unshift(created);
      return json(route, created, 201);
    }
    return json(route, { data: annotations });
  });
}

async function loginAndOpenAnnotations(page: Page, seed: StoredAnnotation[], rec: Recorder = recorder()) {
  await installMocks(page, seed, rec);
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
  await page.getByRole("tab", { name: /annotations/i }).click();
  return rec;
}

/** Rubber-band a box over the image, in fractions of the image container. */
async function drawRegion(page: Page, x0: number, y0: number, x1: number, y1: number) {
  const image = page.getByTestId("zoomable-image");
  await expect(image).toBeVisible({ timeout: 10_000 });
  const box = await image.boundingBox();
  if (!box) throw new Error("zoomable-image has no bounding box");
  await page.mouse.move(box.x + box.width * x0, box.y + box.height * y0);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width * x1, box.y + box.height * y1, { steps: 10 });
  await page.mouse.up();
}

/**
 * ZoomableImage reports a region as a fraction of its container and AssetDetail converts it to
 * permille, so the expected numbers are the drag fractions × 1000. The tolerance absorbs the
 * pixel rounding of the mouse path — the point is that the drawn box travelled, not that it
 * survived to the unit.
 */
function expectPermille(actual: unknown, expected: number) {
  expect(typeof actual).toBe("number");
  expect(Math.abs((actual as number) - expected)).toBeLessThan(25);
}

test.describe("Annotation authoring – mocked e2e", () => {
  test("create an annotation from the composer", async ({ page }) => {
    await loginAndOpenAnnotations(page, []);

    await page.getByTestId("annotation-new-title").fill("New marker");
    await page.getByTestId("annotation-new-desc").fill("Looks great here");
    await page.getByTestId("annotation-post").click();

    await expect(page.getByText("New marker", { exact: true })).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText("Looks great here", { exact: true })).toBeVisible();
  });

  test("edit and delete an owned annotation", async ({ page }) => {
    await loginAndOpenAnnotations(page, [annotation({ title: "Original title", description: "Original body" })]);

    await expect(page.getByText("Original title", { exact: true })).toBeVisible({ timeout: 10_000 });

    // Edit round-trip.
    await page.getByTestId("annotation-edit").first().click();
    await page.getByTestId("annotation-title-field").fill("Edited title");
    await page.getByTestId("annotation-edit-field").fill("Edited body");
    await page.getByTestId("annotation-save").click();

    await expect(page.getByText("Edited title", { exact: true })).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText("Original title", { exact: true })).toBeHidden();

    // Delete round-trip.
    await page.getByTestId("annotation-delete").first().click();
    await expect(page.getByText("Edited title", { exact: true })).toBeHidden({ timeout: 10_000 });
  });

  test("react to an annotation, then remove the reaction", async ({ page }) => {
    await loginAndOpenAnnotations(page, [annotation({ title: "Reactable" })]);

    await expect(page.getByText("Reactable", { exact: true })).toBeVisible({ timeout: 10_000 });

    const up = page.getByTestId("annotation-reaction-up").first();
    const upCount = page.getByTestId("annotation-reaction-up-count").first();

    await expect(upCount).toHaveText("0");
    await up.click();
    await expect(upCount).toHaveText("1", { timeout: 10_000 });
    await up.click();
    await expect(upCount).toHaveText("0", { timeout: 10_000 });
  });

  test("edit/delete controls are hidden on annotations owned by others", async ({ page }) => {
    await loginAndOpenAnnotations(page, [
      annotation({ uuid: "other-1", title: "Not mine", status: { creator: { uuid: OTHER_UUID }, created: new Date().toISOString() } }),
    ]);

    await expect(page.getByText("Not mine", { exact: true })).toBeVisible({ timeout: 10_000 });
    // Authorship gating: no edit/delete affordances for another user's annotation.
    await expect(page.getByTestId("annotation-edit")).toHaveCount(0);
    await expect(page.getByTestId("annotation-delete")).toHaveCount(0);
  });

  test("the composer takes text and gates the post button on a title", async ({ page }) => {
    await loginAndOpenAnnotations(page, []);

    const composer = page.getByTestId("annotation-composer");
    await expect(composer).toBeVisible({ timeout: 10_000 });

    // Both fields live inside the composer, and nothing can be posted yet.
    await expect(composer.getByTestId("annotation-new-title")).toHaveValue("");
    await expect(composer.getByTestId("annotation-new-desc")).toHaveValue("");
    await expect(composer.getByTestId("annotation-post")).toBeDisabled();

    // A description on its own is not enough — an annotation is titled or it is nothing.
    await composer.getByTestId("annotation-new-desc").fill("a body without a title");
    await expect(composer.getByTestId("annotation-post")).toBeDisabled();

    // …and whitespace does not count as a title either.
    await composer.getByTestId("annotation-new-title").fill("   ");
    await expect(composer.getByTestId("annotation-post")).toBeDisabled();

    await composer.getByTestId("annotation-new-title").fill("A real title");
    await expect(composer.getByTestId("annotation-post")).toBeEnabled();
    // Typing the title did not disturb what was already in the description.
    await expect(composer.getByTestId("annotation-new-desc")).toHaveValue("a body without a title");
  });

  /**
   * The toggle is the whole difference between a drag that pans the image and a drag that draws a
   * box, so the unarmed half of this case is the load-bearing one: without it, an annotation that
   * silently picked up whatever region was lying around would still pass.
   */
  test("the region toggle arms region mode and the drawn box rides along with the POST", async ({ page }) => {
    const rec = recorder();
    await loginAndOpenAnnotations(page, [], rec);

    const composer = page.getByTestId("annotation-composer");
    await expect(composer).toBeVisible({ timeout: 10_000 });

    // Unarmed: the same drag draws nothing, and the annotation goes out with no area.
    await drawRegion(page, 0.20, 0.30, 0.60, 0.70);
    await expect(composer.getByText(/Region captured/)).toHaveCount(0);
    await composer.getByTestId("annotation-new-title").fill("No region");
    await composer.getByTestId("annotation-post").click();
    await expect.poll(() => rec.creates.length, { timeout: 10_000 }).toBe(1);
    expect(rec.creates[0].area).toBeUndefined();

    // Armed: the hint appears, the drag is captured, and the area rides along.
    await composer.getByTestId("annotation-region-toggle").click();
    await expect(composer.getByText(/Draw a box on the image/)).toBeVisible();

    await drawRegion(page, 0.20, 0.30, 0.60, 0.70);
    await expect(composer.getByText(/Region captured/)).toBeVisible({ timeout: 5_000 });

    await composer.getByTestId("annotation-new-title").fill("Region marker");
    await composer.getByTestId("annotation-post").click();
    await expect.poll(() => rec.creates.length, { timeout: 10_000 }).toBe(2);

    const area = rec.creates[1].area as Record<string, unknown>;
    expect(area).toBeTruthy();
    expectPermille(area.startX, 200);
    expectPermille(area.startY, 300);
    expectPermille(area.width, 400);
    expectPermille(area.height, 400);

    // Twice on screen: the sidebar entry, plus a chip in the region strip over the media pane.
    // That strip lists only annotations that came back carrying an area, so the second mention is
    // the round-trip made visible — the region-less one posted first appears once.
    await expect(page.getByText("Region marker", { exact: true })).toHaveCount(2, { timeout: 10_000 });
    await expect(page.getByText("No region", { exact: true })).toHaveCount(1);

    // Posting disarms region mode and clears the pending box, so the next annotation starts clean.
    await expect(composer.getByText(/Region captured/)).toHaveCount(0);
    await expect(composer.getByText(/Draw a box on the image/)).toHaveCount(0);
  });

  /**
   * `annotation-cancel` sits on the *edit* form, not on the composer — the composer has no cancel
   * button, it is simply always open on the tab. The leak this guards is the draft: an editor that
   * kept the abandoned text would re-open on it, and the next save would write words the author
   * already backed out of.
   */
  test("cancelling an annotation edit writes nothing and re-opens on the stored text", async ({ page }) => {
    const rec = recorder();
    await loginAndOpenAnnotations(
      page,
      [annotation({ title: "Stored title", description: "Stored body" })],
      rec,
    );

    await expect(page.getByText("Stored title", { exact: true })).toBeVisible({ timeout: 10_000 });

    await page.getByTestId("annotation-edit").first().click();
    await page.getByTestId("annotation-title-field").fill("Abandoned title");
    await page.getByTestId("annotation-edit-field").fill("Abandoned body");
    await page.getByTestId("annotation-cancel").click();

    // The form closes, the list still shows what the server holds, and no write went out.
    await expect(page.getByTestId("annotation-title-field")).toHaveCount(0);
    await expect(page.getByText("Stored title", { exact: true })).toBeVisible();
    await expect(page.getByText("Abandoned title", { exact: true })).toHaveCount(0);
    expect(rec.updates).toEqual([]);

    // Re-opening starts from the stored text, not from the draft that was thrown away.
    await page.getByTestId("annotation-edit").first().click();
    await expect(page.getByTestId("annotation-title-field")).toHaveValue("Stored title");
    await expect(page.getByTestId("annotation-edit-field")).toHaveValue("Stored body");
    expect(rec.updates).toEqual([]);
  });
});
