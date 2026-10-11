import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for the voice section of the person detail view — no running Loom backend required.
 *
 * A person's voice samples are what speaker recognition compares new voices with. Like the person's
 * pictures they belong to the person, not to an asset: uploaded, or cut from a speaker cluster
 * confirmed to them. This spec covers the section where that happens - the list and its players,
 * the per-model enrolment chips, the hero voice, upload, delete, import, and the warning that the
 * reference is too short to recognise anybody by.
 *
 * Routes exercised:
 *   GET      /api/v1/persons/:uuid
 *   GET      /api/v1/persons/:uuid/clusters
 *   GET      /api/v1/persons/:uuid/voice-samples
 *   POST     /api/v1/persons/:uuid/voice-samples                (multipart upload)
 *   POST     /api/v1/persons/:uuid/voice-samples/from-cluster
 *   DELETE   /api/v1/persons/:uuid/voice-samples/:sampleUuid
 *   GET      /api/v1/persons/:uuid/voice-samples/:sampleUuid/data
 *   POST     /api/v1/persons/:uuid/hero-voice
 *   GET      /api/v1/assets/:uuid
 *   GET      /api/v1/assets/:uuid/audio-clip?from=&to=
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const ASSET_UUID = "22222222-2222-2222-2222-222222222222";
const SPEAKER_UUID = "5a5a5a5a-0000-0000-0000-00000000000a";
const FACE_CLUSTER_UUID = "33333333-3333-3333-3333-333333333333";
const PERSON_UUID = "44444444-4444-4444-4444-444444444444";

function tinyWav(): Buffer {
  const samples = 100;
  const data = samples * 2;
  const b = Buffer.alloc(44 + data);
  b.write("RIFF", 0); b.writeUInt32LE(36 + data, 4); b.write("WAVE", 8);
  b.write("fmt ", 12); b.writeUInt32LE(16, 16); b.writeUInt16LE(1, 20); b.writeUInt16LE(1, 22);
  b.writeUInt32LE(16000, 24); b.writeUInt32LE(32000, 28); b.writeUInt16LE(2, 32); b.writeUInt16LE(16, 34);
  b.write("data", 36); b.writeUInt32LE(data, 40);
  return b;
}

interface StoredSample {
  uuid: string;
  filename: string;
  mimeType: string;
  size: number;
  url: string;
  hero: boolean;
  durationMs?: number;
  sourceAssetUuid?: string;
  sourceClusterUuid?: string;
  sourceTimeFrom?: number;
  sourceTimeTo?: number;
  prints: { uuid: string; sampleUuid: string; type: string; model: string; dimensions?: number; usable: boolean; error?: string }[];
}

interface Recorder {
  uploads: { contentType: string; body: string }[];
  imports: Record<string, unknown>[];
  heroes: Record<string, unknown>[];
  deletes: string[];
  sampleFetches: { uuid: string; auth: string | undefined }[];
  clips: { from: string | null; to: string | null }[];
  memberReads: string[];
}

function recorder(): Recorder {
  return { uploads: [], imports: [], heroes: [], deletes: [], sampleFetches: [], clips: [], memberReads: [] };
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function sampleUrl(uuid: string) {
  return `/api/v1/persons/${PERSON_UUID}/voice-samples/${uuid}/data`;
}

function sample(uuid: string, durationMs: number, extra: Partial<StoredSample> = {}): StoredSample {
  return { uuid, filename: `${uuid}.wav`, mimeType: "audio/wav", size: 1000, url: sampleUrl(uuid), hero: false, durationMs, prints: [], ...extra };
}

interface MockOptions {
  samples?: StoredSample[];
  /** A speaker cluster confirmed to this person, so there is something to import from. */
  withSpeaker?: boolean;
}

/** Routes are matched most-recently-registered first, so the catch-all goes in first. */
async function installMocks(page: Page, rec: Recorder, opts: MockOptions = {}) {
  const samples: StoredSample[] = [...(opts.samples ?? [])];
  let seq = 0;
  const heroUuid = () => samples.find(s => s.hero)?.uuid;

  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));

  const person = () => ({
    uuid: PERSON_UUID,
    alias: "anna",
    firstname: "Anna",
    lastname: "Meyer",
    heroVoiceUrl: heroUuid() ? sampleUrl(heroUuid()!) : null,
    status: { created: "2026-01-02T03:04:05Z" },
  });

  await page.route(/\/api\/v1\/persons\/[^/]+$/, route => json(route, person()));

  // Both kinds of cluster, as the server answers: the face one must not be offered for import, and
  // the speaker one must not be asked for face crops.
  await page.route(/\/api\/v1\/persons\/[^/]+\/clusters$/, route => json(route, {
    data: opts.withSpeaker
      ? [
        { uuid: FACE_CLUSTER_UUID, name: "Anna", type: "face", reviewStatus: "CONFIRMED", personUuid: PERSON_UUID, assetUuid: ASSET_UUID, memberCount: 0 },
        {
          uuid: SPEAKER_UUID, name: "Speaker 1", type: "speaker", reviewStatus: "CONFIRMED", personUuid: PERSON_UUID, assetUuid: ASSET_UUID, memberCount: 4,
          meta: { totalMs: 48_000, segments: [{ startMs: 1000, endMs: 9000 }, { startMs: 30_000, endMs: 70_000 }] },
        },
      ]
      : [],
  }));

  await page.route(/\/api\/v1\/clusters\/[^/]+\/members$/, route => {
    rec.memberReads.push(route.request().url().split("/clusters/")[1].split("/members")[0]);
    return json(route, { members: [], total: 0 });
  });

  await page.route(/\/api\/v1\/assets\/[^/?]+$/, route =>
    json(route, { uuid: ASSET_UUID, file: { filename: "town-hall.mp4", mimeType: "video/mp4", size: 1000 } }));

  await page.route(/\/api\/v1\/assets\/[^/]+\/audio-clip/, route => {
    const url = new URL(route.request().url());
    rec.clips.push({ from: url.searchParams.get("from"), to: url.searchParams.get("to") });
    return route.fulfill({ status: 200, contentType: "audio/wav", body: tinyWav() });
  });

  await page.route(/\/api\/v1\/persons\/[^/]+\/hero-voice$/, route => {
    const body = route.request().postDataJSON() as Record<string, unknown>;
    rec.heroes.push(body);
    samples.forEach(s => (s.hero = s.uuid === body.sampleUuid));
    return json(route, person());
  });

  await page.route(/\/api\/v1\/persons\/[^/]+\/voice-samples\/[^/]+\/data$/, route => {
    const uuid = route.request().url().split("/voice-samples/")[1].split("/data")[0];
    rec.sampleFetches.push({ uuid, auth: route.request().headers()["authorization"] });
    return route.fulfill({ status: 200, contentType: "audio/wav", body: tinyWav() });
  });

  await page.route(/\/api\/v1\/persons\/[^/]+\/voice-samples\/[^/]+$/, route => {
    const uuid = route.request().url().split("/voice-samples/")[1].split("?")[0];
    rec.deletes.push(uuid);
    const idx = samples.findIndex(s => s.uuid === uuid);
    if (idx >= 0) samples.splice(idx, 1);
    return route.fulfill({ status: 204, body: "" });
  });

  await page.route(/\/api\/v1\/persons\/[^/]+\/voice-samples$/, route => {
    if (route.request().method() === "POST") {
      rec.uploads.push({ contentType: route.request().headers()["content-type"] ?? "", body: route.request().postData() ?? "" });
      const created = sample(`uploaded-${++seq}`, 20_000, { filename: "interview.m4a", mimeType: "audio/mp4" });
      samples.push(created);
      return json(route, created, 201);
    }
    return json(route, { data: samples });
  });

  // Registered last so it wins over the by-uuid route above, which would otherwise answer the
  // import as though it were a delete.
  await page.route(/\/api\/v1\/persons\/[^/]+\/voice-samples\/from-cluster$/, route => {
    const body = route.request().postDataJSON() as Record<string, unknown>;
    rec.imports.push(body);
    const created = sample(`imported-${++seq}`, 30_000, {
      filename: "speaker.wav", sourceAssetUuid: ASSET_UUID, sourceClusterUuid: body.clusterUuid as string,
      sourceTimeFrom: 30_000, sourceTimeTo: 60_000,
    });
    samples.push(created);
    return json(route, created, 201);
  });
}

async function login(page: Page) {
  if (await page.getByPlaceholder("Username").count() === 0) return;
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

async function openPerson(page: Page) {
  await page.goto(`/ui/persons/${PERSON_UUID}`);
  await login(page);
  await expect(page.getByTestId("person-detail")).toBeVisible({ timeout: 10_000 });
  await expect(page.getByTestId("person-voice-section")).toBeVisible();
}

function sampleRow(page: Page, uuid: string) {
  return page.locator(`[data-testid=person-voice-sample][data-sample-uuid="${uuid}"]`);
}

test.describe("Person voice samples – mocked e2e", () => {
  test("a person with no recordings says so, and is not warned about being short of speech", async ({ page }) => {
    await installMocks(page, recorder());
    await openPerson(page);

    await expect(page.getByTestId("person-voice-empty")).toBeVisible();
    await expect(page.getByTestId("person-voice-warning")).toHaveCount(0);
    await expect(page.getByTestId("person-hero-voice")).toHaveCount(0);
    await expect(page.getByTestId("person-speaker-import-list")).toHaveCount(0);
  });

  test("under 30 seconds of reference speech carries the warning", async ({ page }) => {
    await installMocks(page, recorder(), { samples: [sample("s1", 8000), sample("s2", 4000)] });
    await openPerson(page);

    await expect(page.getByTestId("person-voice-total")).toHaveAttribute("data-total-ms", "12000");
    await expect(page.getByTestId("person-voice-total")).toHaveText("0:12 of reference speech");
    await expect(page.getByTestId("person-voice-warning"))
      .toHaveText("Recognition works best with at least 30 seconds of speech from more than one occasion.");
  });

  test("enough reference speech carries no warning", async ({ page }) => {
    await installMocks(page, recorder(), { samples: [sample("s1", 20_000), sample("s2", 25_000)] });
    await openPerson(page);
    await expect(page.getByTestId("person-voice-sample")).toHaveCount(2);
    await expect(page.getByTestId("person-voice-warning")).toHaveCount(0);
  });

  test("each sample says where it came from and which models enrolled it", async ({ page }) => {
    await installMocks(page, recorder(), {
      samples: [
        sample("cut", 15_000, {
          filename: "speaker.wav", sourceAssetUuid: ASSET_UUID, sourceTimeFrom: 65_000, sourceTimeTo: 80_000,
          prints: [
            { uuid: "p1", sampleUuid: "cut", type: "speaker", model: "redimnet2", dimensions: 192, usable: true },
            { uuid: "p2", sampleUuid: "cut", type: "speaker", model: "ecapa", usable: false, error: "no speech found" },
          ],
        }),
        sample("upload", 20_000, { filename: "interview.m4a" }),
      ],
    });
    await openPerson(page);

    const cut = sampleRow(page, "cut");
    await expect(cut.getByTestId("person-voice-source")).toHaveText("0:15 · from town-hall.mp4 at 1:05");
    await expect(cut.locator("[data-testid=person-voice-print][data-usable=true]")).toHaveText("redimnet2");
    await expect(cut.locator("[data-testid=person-voice-print][data-usable=false]")).toHaveText("ecapa");

    const upload = sampleRow(page, "upload");
    await expect(upload.getByTestId("person-voice-source")).toHaveText("0:20 · uploaded");
    await expect(upload.getByTestId("person-voice-print-none")).toBeVisible();
  });

  test("a sample plays from a blob fetched with the bearer token, not from a bare URL", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, { samples: [sample("s1", 40_000)] });
    await openPerson(page);

    // Nothing is fetched until play is pressed.
    expect(rec.sampleFetches).toEqual([]);
    await sampleRow(page, "s1").getByTestId("person-voice-play-play").click();
    await expect.poll(() => rec.sampleFetches).toEqual([{ uuid: "s1", auth: "Bearer fake-jwt" }]);
    await expect(sampleRow(page, "s1").getByTestId("person-voice-play-audio")).toHaveAttribute("src", /^blob:/);
  });

  test("uploading a recording posts it as multipart and lists it", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec);
    await openPerson(page);

    await expect(page.getByTestId("person-voice-input")).toHaveAttribute("accept", "audio/*,video/*");
    await page.getByTestId("person-voice-input").setInputFiles({
      name: "interview.m4a",
      mimeType: "audio/mp4",
      buffer: Buffer.from("voice-bytes"),
    });

    await expect.poll(() => rec.uploads.length).toBe(1);
    expect(rec.uploads[0].contentType).toContain("multipart/form-data");
    expect(rec.uploads[0].body).toContain('name="file"; filename="interview.m4a"');
    await expect(page.getByTestId("person-voice-sample")).toHaveCount(1);
    await expect(page.getByTestId("person-voice-filename")).toHaveText("interview.m4a");
  });

  test("set and clear the hero voice; the hero plays beside the avatar", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, { samples: [sample("s1", 20_000), sample("s2", 20_000)] });
    await openPerson(page);

    await expect(page.getByTestId("person-voice-is-hero")).toHaveCount(0);
    await sampleRow(page, "s2").getByTestId("person-voice-make-hero").click();
    await expect.poll(() => rec.heroes).toEqual([{ sampleUuid: "s2" }]);
    await expect(sampleRow(page, "s2")).toHaveAttribute("data-hero", "true");
    await expect(sampleRow(page, "s2").getByTestId("person-voice-is-hero")).toBeVisible();

    const heroPlayer = page.getByTestId("person-hero-voice");
    await heroPlayer.getByTestId("person-hero-voice-play").click();
    await expect.poll(() => rec.sampleFetches).toEqual([{ uuid: "s2", auth: "Bearer fake-jwt" }]);

    await page.getByTestId("person-voice-clear-hero").click();
    await expect.poll(() => rec.heroes).toEqual([{ sampleUuid: "s2" }, { sampleUuid: "" }]);
    await expect(page.getByTestId("person-voice-is-hero")).toHaveCount(0);
    await expect(page.getByTestId("person-hero-voice")).toHaveCount(0);
  });

  test("deleting a sample removes it", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, { samples: [sample("s1", 20_000), sample("s2", 20_000)] });
    await openPerson(page);

    await sampleRow(page, "s1").getByTestId("person-voice-delete").click();
    await expect.poll(() => rec.deletes).toEqual(["s1"]);
    await expect(page.getByTestId("person-voice-sample")).toHaveCount(1);
    await expect(sampleRow(page, "s2")).toBeVisible();
  });

  test("a confirmed speaker can be imported and previewed; a face cluster is not offered", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, { withSpeaker: true });
    await openPerson(page);

    const candidates = page.getByTestId("person-speaker-candidate");
    await expect(candidates).toHaveCount(1);
    await expect(candidates.first()).toHaveAttribute("data-cluster-id", SPEAKER_UUID);
    await expect(candidates.first()).toContainText("town-hall.mp4");
    // A voice has no face crops: its members are not fetched for the crop picker.
    expect(rec.memberReads).not.toContain(SPEAKER_UUID);

    await candidates.first().getByTestId("person-speaker-play-play").click();
    // The longest turn, capped at 30 s.
    await expect.poll(() => rec.clips).toEqual([{ from: "30000", to: "60000" }]);

    await candidates.first().getByTestId("person-speaker-import").click();
    await expect.poll(() => rec.imports).toEqual([{ clusterUuid: SPEAKER_UUID }]);
    await expect(page.getByTestId("person-voice-sample")).toHaveCount(1);
    await expect(page.getByTestId("person-voice-source")).toHaveText("0:30 · from town-hall.mp4 at 0:30");
  });
});
