import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for what the Detection → Objects screen reads off a real detection.
 *
 * `detection-review-mocked.spec.ts` seeds its detections the way the demo database used to: the
 * class inside `meta.label`. The objectdetect node does not write that — it writes the indexed
 * `label` column (and a reviewer's correction goes to `correctedLabel`). Reading only the meta
 * filed every real detection under one group named "objectdetection".
 *
 * The second half: a verdict is durable. Rows already confirmed or rejected — through the review
 * columns, or through the `meta.confirmed` this screen itself writes — must not offer the buttons
 * again after a reload.
 *
 * Routes exercised:
 *   GET /api/v1/assets
 *   GET /api/v1/assets/:uuid/detections
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const ASSET_UUID = "22222222-2222-2222-2222-222222222222";

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function detection(uuid: string, extra: Record<string, unknown>) {
  return {
    uuid, type: "objectdetection", frameNumber: 42, bboxX: 0.1, bboxY: 0.1, bboxWidth: 0.2, bboxHeight: 0.2,
    confidence: 0.95, assetUuid: ASSET_UUID, ...extra,
  };
}

async function installMocks(page: Page) {
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));
  await page.route(/\/api\/v1\/assets(\?|$)/, route => json(route, {
    data: [{ uuid: ASSET_UUID, file: { filename: "street.mp4" } }],
    _metainfo: { totalCount: 1 },
  }));
  await page.route(/\/api\/v1\/assets\/[^/]+\/detections$/, route => json(route, {
    data: [
      // What the objectdetect node writes: the class in `label`, nothing in meta.
      detection("d-dog", { label: "dog" }),
      // A reviewer corrected the class: grouped under the correction.
      detection("d-wolf", { label: "dog", correctedLabel: "wolf", reviewStatus: "CONFIRMED" }),
      detection("d-car", { label: "car", reviewStatus: "REJECTED", confidence: 0.4 }),
      // Confirmed by this very screen in an earlier session.
      detection("d-bus", { label: "bus", meta: { label: "bus", confirmed: true } }),
      // A face on the same asset is not this screen's business.
      { ...detection("d-face", {}), type: "face" },
    ],
  }));
}

async function openObjects(page: Page) {
  await page.goto("/");
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await page.getByRole("button", { name: "Detection", exact: true }).first().click();
  await page.getByRole("tab", { name: /objects/i }).click();
  await expect(page.getByTestId("objectdetection-row").first()).toBeVisible({ timeout: 10_000 });
}

function group(page: Page, label: string) {
  return page.getByTestId("objectdetection-group").filter({ has: page.getByText(label, { exact: true }) });
}

test.describe("Object detection labels – mocked e2e", () => {
  test("detections are grouped by their label column, a correction winning over the model", async ({ page }) => {
    await installMocks(page);
    await openObjects(page);

    await expect(page.getByTestId("objectdetection-group")).toHaveCount(4);
    for (const label of ["dog", "wolf", "car", "bus"]) {
      await expect(group(page, label).getByTestId("objectdetection-row")).toHaveCount(1);
    }
    // The defect: one group named after the detection type.
    await expect(group(page, "objectdetection")).toHaveCount(0);
  });

  test("a verdict already on record is shown, not offered again", async ({ page }) => {
    await installMocks(page);
    await openObjects(page);

    await expect(group(page, "wolf").getByTestId("objectdetection-decision")).toHaveText("confirmed");
    await expect(group(page, "car").getByTestId("objectdetection-decision")).toHaveText("rejected");
    await expect(group(page, "bus").getByTestId("objectdetection-decision")).toHaveText("confirmed");
    await expect(group(page, "bus").getByTestId("objectdetection-confirm")).toHaveCount(0);

    // Only the undecided row still asks.
    await expect(page.getByTestId("objectdetection-confirm")).toHaveCount(1);
    await expect(group(page, "dog").getByTestId("objectdetection-confirm")).toHaveCount(1);
  });

  test("the frame number is labelled as a frame, not as seconds", async ({ page }) => {
    await installMocks(page);
    await openObjects(page);

    const row = group(page, "dog").getByTestId("objectdetection-row");
    await expect(row).toContainText("frame 42");
    await expect(row).not.toContainText("42s");
  });

  test("the label search and the confidence band narrow the groups", async ({ page }) => {
    await installMocks(page);
    await openObjects(page);

    // search-coverage-mocked only proves the field takes input; this proves it filters.
    await page.getByTestId("objectdetection-search").locator("input").fill("wol");
    await expect(page.getByTestId("objectdetection-group")).toHaveCount(1);
    await expect(group(page, "wolf")).toHaveCount(1);

    await page.getByTestId("objectdetection-search").locator("input").fill("");
    await expect(page.getByTestId("objectdetection-group")).toHaveCount(4);

    await page.getByTestId("objectdetection-filter-confidence").click();
    await page.getByTestId("objectdetection-filter-confidence-option-low").click();
    await expect(page.getByTestId("objectdetection-group")).toHaveCount(1);
    await expect(group(page, "car")).toHaveCount(1);
  });
});
