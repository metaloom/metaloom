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

async function installMocks(page: Page, seed: StoredAnnotation[]) {
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

async function loginAndOpenAnnotations(page: Page, seed: StoredAnnotation[]) {
  await installMocks(page, seed);
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
});
