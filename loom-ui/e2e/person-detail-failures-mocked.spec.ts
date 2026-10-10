import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the person detail view's failure paths — no running Loom backend.
 *
 * Two different things can stop `/persons/:id` from showing a person, and the screen used to say
 * the same thing for both: "They may have been deleted." After a 500 that sends somebody looking
 * for a deletion that never happened. Now only a 404 reads as gone; any other failure reads as a
 * failure and offers a retry that really re-runs the load.
 *
 * Also: a rejected upload says so and leaves the gallery as it was.
 *
 * Routes exercised:
 *   GET  /api/v1/persons/:uuid
 *   GET  /api/v1/persons/:uuid/images
 *   GET  /api/v1/persons/:uuid/clusters
 *   POST /api/v1/persons/:uuid/images      (multipart upload)
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const PERSON_UUID = "44444444-4444-4444-4444-444444444444";

interface Recorder {
  personReads: number;
  uploads: number;
}

interface MockOptions {
  /** Status the person read answers with for its first N reads; 200 afterwards. */
  personStatus?: number;
  failPersonReads?: number;
  failUpload?: boolean;
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

async function installMocks(page: Page, rec: Recorder, opts: MockOptions = {}) {
  let failuresLeft = opts.failPersonReads ?? 0;
  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));
  await page.route(/\/api\/v1\/persons\/[^/]+$/, route => {
    rec.personReads++;
    if (failuresLeft > 0) {
      failuresLeft--;
      return json(route, { message: opts.personStatus === 404 ? "Person not found" : "database timeout" }, opts.personStatus ?? 500);
    }
    return json(route, { uuid: PERSON_UUID, alias: "anna", firstname: "Anna", lastname: "Meyer", status: { created: "2026-01-02T03:04:05Z" } });
  });
  await page.route(/\/api\/v1\/persons\/[^/]+\/images$/, route => {
    if (route.request().method() === "POST") {
      rec.uploads++;
      if (opts.failUpload) return json(route, { message: "unsupported image" }, 415);
      return json(route, { uuid: "img-1", filename: "x.png", mimeType: "image/png", size: 1, url: "", avatar: false }, 201);
    }
    return json(route, { data: [], _metainfo: { totalCount: 0 } });
  });
}

async function openPerson(page: Page) {
  // Deep link first: auth is in-memory, a goto after login would log straight back out.
  await page.goto(`/ui/persons/${PERSON_UUID}`);
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

test.describe("Person detail failures – mocked e2e", () => {
  test("a 404 says the person is gone, and is not reported as a failure", async ({ page }) => {
    const rec: Recorder = { personReads: 0, uploads: 0 };
    await installMocks(page, rec, { personStatus: 404, failPersonReads: 100 });
    await openPerson(page);

    const missing = page.getByTestId("person-detail-missing");
    await expect(missing).toBeVisible({ timeout: 10_000 });
    await expect(missing).toContainText("They may have been deleted.");
    await expect(page.getByTestId("person-detail-load-failure")).toHaveCount(0);
    await expect(page.locator("[role=alert]")).toHaveCount(0);
  });

  test("a server failure says the load failed rather than that the person is gone, and retry loads them", async ({ page }) => {
    const rec: Recorder = { personReads: 0, uploads: 0 };
    await installMocks(page, rec, { personStatus: 500, failPersonReads: 1 });
    await openPerson(page);

    const failure = page.getByTestId("person-detail-load-failure");
    await expect(failure).toBeVisible({ timeout: 10_000 });
    await expect(failure).toContainText("Loading this person failed");
    await expect(failure).not.toContainText("may have been deleted");
    await expect(page.getByTestId("person-detail-missing")).toHaveCount(0);
    await expect(page.locator("[role=alert]").filter({ hasText: "This person could not be loaded" })).toBeVisible();
    expect(rec.personReads).toBe(1);

    await page.getByTestId("person-detail-load-failure-action").click();
    await expect.poll(() => rec.personReads, { timeout: 10_000 }).toBe(2);
    await expect(page.getByTestId("person-detail")).toBeVisible();
    await expect(page.getByTestId("person-detail-name")).toHaveText("Anna Meyer");
    await expect(failure).toHaveCount(0);
  });

  test("a rejected upload says so and leaves the gallery empty", async ({ page }) => {
    const rec: Recorder = { personReads: 0, uploads: 0 };
    await installMocks(page, rec, { failUpload: true });
    await openPerson(page);
    await expect(page.getByTestId("person-images-empty")).toBeVisible({ timeout: 10_000 });

    await page.getByTestId("person-image-input").setInputFiles({
      name: "portrait.png", mimeType: "image/png", buffer: Buffer.from("portrait-bytes"),
    });

    await expect.poll(() => rec.uploads, { timeout: 10_000 }).toBe(1);
    await expect(page.locator("[role=alert]").filter({ hasText: "The picture could not be uploaded" })).toBeVisible();
    await expect(page.locator("[role=alert]").filter({ hasText: "Picture added" })).toHaveCount(0);
    await expect(page.getByTestId("person-image-card")).toHaveCount(0);
    await expect(page.getByTestId("person-images-empty")).toBeVisible();
    // Not left disabled: the next attempt must be possible.
    await expect(page.getByTestId("person-image-upload")).not.toHaveClass(/Mui-disabled/);
  });
});
