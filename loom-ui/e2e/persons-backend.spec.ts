import { test, expect, Page } from "@playwright/test";

/**
 * Backend e2e for persons: the CRUD round trip, and the pictures a person owns.
 *
 * Needs a running stack:
 *   ./start-postgres.sh && ./start-demo.sh
 *   VITE_API_BASE_URL=/api/v1 VITE_PROXY_TARGET=http://localhost:8092 npm run test:e2e -- persons-backend
 *
 * The picture half is what the mocked specs cannot prove: that the bytes are really stored, really
 * served back, and really survive as the person's avatar. A person's images belong to the person
 * rather than to any asset (V2.90), which is why they are reachable under `/persons/:uuid/images`
 * and not under `/attachments`.
 */

async function login(page: Page) {
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

async function loginAndGoToPersons(page: Page) {
  await page.goto("/");
  await login(page);
  await page.getByRole("button", { name: "Detection", exact: true }).first().click();
  await expect(page.getByTestId("facedetection-switcher")).toBeVisible({ timeout: 10_000 });
  await page.getByTestId("facedetection-section-persons").click();
}

/** The card carrying this alias, located by testid rather than by a `div` filtered on its text. */
function personCard(page: Page, alias: string) {
  return page.getByTestId("person-card").filter({ has: page.getByTestId("person-alias").getByText(alias, { exact: true }) });
}

async function createPerson(page: Page, alias: string) {
  await page.getByTestId("facedetection-add-person").click();
  await page.getByTestId("facedetection-person-alias").locator("input").fill(alias);
  await page.getByTestId("facedetection-person-create").click();
  await expect(personCard(page, alias)).toBeVisible({ timeout: 10_000 });
}

test.describe("Persons - backend e2e", () => {
  test("create, update and delete a person", async ({ page }) => {
    await loginAndGoToPersons(page);

    const alias = `pw-person-${Date.now()}`;
    const updatedAlias = `${alias}-updated`;
    await createPerson(page, alias);

    const card = personCard(page, alias);
    await card.getByTestId("person-edit").click();
    await page.getByTestId("person-edit-alias").locator("input").fill(updatedAlias);
    await page.getByTestId("person-edit-firstname").locator("input").fill("Test");
    await page.getByTestId("person-edit-lastname").locator("input").fill("User");
    await page.getByTestId("person-edit-save").click();

    const updated = personCard(page, updatedAlias);
    await expect(updated).toBeVisible({ timeout: 10_000 });
    await expect(updated.getByTestId("person-name")).toHaveText("Test User");

    await updated.getByTestId("person-delete").click();
    await expect(updated).toBeHidden({ timeout: 10_000 });
  });

  test("a picture uploaded to a person is stored, served back and becomes their avatar", async ({ page }) => {
    await loginAndGoToPersons(page);

    const alias = `pw-hero-${Date.now()}`;
    await createPerson(page, alias);
    await personCard(page, alias).getByTestId("person-name").click();
    await expect(page.getByTestId("person-detail")).toBeVisible({ timeout: 10_000 });

    // A distinct PNG per run: the bytes are content-addressed, so a fixed payload would dedupe onto
    // whatever a previous run stored.
    await expect(page.getByTestId("person-images-empty")).toBeVisible();
    await page.getByTestId("person-image-input").setInputFiles({
      name: "portrait.png",
      mimeType: "image/png",
      buffer: pngBytes(`${alias}`),
    });

    const card = page.getByTestId("person-image-card");
    await expect(card).toHaveCount(1, { timeout: 10_000 });

    // Served back by the server, not from the file that was picked: a broken image would report a
    // zero natural width even though the <img> element is present.
    const img = page.getByTestId("person-image");
    await expect.poll(() => img.evaluate((el: HTMLImageElement) => el.naturalWidth), { timeout: 10_000 })
      .toBeGreaterThan(0);

    await page.getByTestId("person-image-make-avatar").click();
    await expect(page.getByTestId("person-image-is-avatar")).toHaveCount(1, { timeout: 10_000 });
    await expect(page.getByTestId("person-detail-avatar").locator("img")).toHaveCount(1);

    // And it is the person's avatar everywhere, not just on the page that set it.
    await page.getByTestId("person-detail-back").click();
    await page.getByTestId("facedetection-section-persons").click();
    await expect(personCard(page, alias).locator("img")).toHaveCount(1, { timeout: 10_000 });

    await personCard(page, alias).getByTestId("person-delete").click();
    await expect(personCard(page, alias)).toBeHidden({ timeout: 10_000 });
  });
});

/** A minimal 1x1 PNG whose single pixel is derived from the seed, so each run uploads new bytes. */
function pngBytes(seed: string): Buffer {
  const hash = [...seed].reduce((acc, ch) => (acc * 31 + ch.charCodeAt(0)) >>> 0, 7);
  const rgb = Buffer.from([(hash >> 16) & 0xff, (hash >> 8) & 0xff, hash & 0xff]);

  const crcTable = Array.from({ length: 256 }, (_, n) => {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    return c >>> 0;
  });
  const crc32 = (buf: Buffer) => {
    let c = 0xffffffff;
    for (const byte of buf) c = crcTable[(c ^ byte) & 0xff] ^ (c >>> 8);
    return (c ^ 0xffffffff) >>> 0;
  };
  const chunk = (type: string, data: Buffer) => {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(body));
    return Buffer.concat([len, body, crc]);
  };

  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(1, 0);
  ihdr.writeUInt32BE(1, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 2; // colour type: truecolour

  // One scanline: filter byte 0 followed by the pixel, wrapped in a stored (uncompressed) zlib block.
  const raw = Buffer.concat([Buffer.from([0]), rgb]);
  const adler = (() => {
    let a = 1;
    let b = 0;
    for (const byte of raw) {
      a = (a + byte) % 65521;
      b = (b + a) % 65521;
    }
    return ((b << 16) | a) >>> 0;
  })();
  const header = Buffer.from([0x78, 0x01, 0x01, raw.length, 0x00, ~raw.length & 0xff, 0xff]);
  const adlerBuf = Buffer.alloc(4);
  adlerBuf.writeUInt32BE(adler);
  const idat = Buffer.concat([header, raw, adlerBuf]);

  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", ihdr),
    chunk("IDAT", idat),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}
