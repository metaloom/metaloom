import { test, expect, Page, Route } from "@playwright/test";

/**
 * Mocked e2e for speaker review and for the person suggestions on both kinds of cluster — no
 * running Loom backend required.
 *
 * Speaker clusters share the cluster table and routes with face clusters. Before the Speakers tab
 * existed the face screen listed every cluster, so each voice turned up as a face card with no
 * crops. This spec pins the split (each tab asks for its own `type`), the one-click acceptance of a
 * suggestion on either kind of card, and the speaker card itself: what it says, what it plays, and
 * the verdicts it can record.
 *
 * Routes exercised:
 *   GET      /api/v1/clusters?type=face|speaker&status=
 *   GET      /api/v1/clusters/:uuid/members
 *   POST     /api/v1/clusters/:uuid/confirm
 *   POST     /api/v1/clusters/:uuid/reject
 *   DELETE   /api/v1/clusters/:uuid/person
 *   GET      /api/v1/persons
 *   GET      /api/v1/assets/:uuid
 *   GET      /api/v1/assets/:uuid/audio-clip?from=&to=
 *   POST     /api/v1/persons/:uuid/voice-samples/from-cluster
 */

const ME_UUID = "11111111-1111-1111-1111-111111111111";
const ASSET_UUID = "22222222-2222-2222-2222-222222222222";
const FACE_CLUSTER = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const SPEAKER_A = "5a5a5a5a-0000-0000-0000-00000000000a";
const SPEAKER_B = "5b5b5b5b-0000-0000-0000-00000000000b";
const PERSON_ANNA = "44444444-4444-4444-4444-444444444444";
const PERSON_BOB = "45454545-4545-4545-4545-454545454545";

/** A 1x1 JPEG, so a face crop resolves to something the browser will decode. */
const TINY_JPEG = Buffer.from(
  "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==",
  "base64",
);

/** A valid, silent 16 kHz mono WAV of 100 samples - what the audio-clip route answers with. */
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

interface StoredCluster {
  uuid: string;
  name: string;
  type: "face" | "speaker";
  reviewStatus: string;
  assetUuid: string;
  memberCount: number;
  personUuid?: string;
  personName?: string;
  suggestedPersonUuid?: string;
  suggestedPersonName?: string;
  suggestedScore?: number;
  meta?: Record<string, unknown>;
  /** Speech windows, for a speaker. */
  windows?: { timeFrom: number; timeTo: number }[];
}

interface Recorder {
  clusterQueries: string[];
  confirms: { clusterUuid: string; body: Record<string, unknown> }[];
  rejects: string[];
  detaches: string[];
  clips: { from: string | null; to: string | null; auth: string | undefined }[];
  imports: { personUuid: string; body: Record<string, unknown> }[];
}

function recorder(): Recorder {
  return { clusterQueries: [], confirms: [], rejects: [], detaches: [], clips: [], imports: [] };
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

const PERSONS = [
  { uuid: PERSON_ANNA, alias: "anna", firstname: "Anna", lastname: "Meyer" },
  { uuid: PERSON_BOB, alias: "bob", firstname: "Bob", lastname: "Stone" },
];

/**
 * Speaker A: three turns, the longest 40 s, so the preview has to be capped at 30 s.
 * Its windows overlap the way the node emits them: 3 s every 1.5 s.
 */
function speakerA(overrides: Partial<StoredCluster> = {}): StoredCluster {
  return {
    uuid: SPEAKER_A,
    name: "Speaker 1",
    type: "speaker",
    reviewStatus: "PENDING",
    assetUuid: ASSET_UUID,
    memberCount: 5,
    suggestedPersonUuid: PERSON_ANNA,
    suggestedPersonName: "Anna Meyer",
    suggestedScore: 0.873,
    meta: {
      totalMs: 62_000,
      segments: [
        { startMs: 0, endMs: 12_000 },
        { startMs: 20_000, endMs: 60_000 },
        { startMs: 70_000, endMs: 80_000 },
      ],
    },
    windows: [
      { timeFrom: 0, timeTo: 3000 },
      { timeFrom: 1500, timeTo: 4500 },
      { timeFrom: 3000, timeTo: 6000 },
      { timeFrom: 20_000, timeTo: 23_000 },
      { timeFrom: 21_500, timeTo: 24_500 },
    ],
    ...overrides,
  };
}

function speakerB(overrides: Partial<StoredCluster> = {}): StoredCluster {
  return {
    uuid: SPEAKER_B,
    name: "Speaker 2",
    type: "speaker",
    reviewStatus: "PENDING",
    assetUuid: ASSET_UUID,
    memberCount: 1,
    meta: { totalMs: 3000, segments: [{ startMs: 90_000, endMs: 93_000 }] },
    windows: [{ timeFrom: 90_000, timeTo: 93_000 }],
    ...overrides,
  };
}

function faceCluster(overrides: Partial<StoredCluster> = {}): StoredCluster {
  return {
    uuid: FACE_CLUSTER,
    name: "",
    type: "face",
    reviewStatus: "PENDING",
    assetUuid: ASSET_UUID,
    memberCount: 2,
    suggestedPersonUuid: PERSON_ANNA,
    suggestedPersonName: "Anna Meyer",
    suggestedScore: 0.91,
    ...overrides,
  };
}

/** Routes are matched most-recently-registered first, so the catch-all goes in first. */
async function installMocks(page: Page, rec: Recorder, clusters: StoredCluster[]) {
  const nameOf = (uuid?: string) => {
    const p = PERSONS.find(x => x.uuid === uuid);
    return p ? `${p.firstname} ${p.lastname}` : undefined;
  };
  let seq = 0;

  await page.route(/\/api\/v1\//, route => json(route, { data: [] }));
  await page.route(/\/api\/v1\/login$/, route => json(route, { token: "fake-jwt" }));
  await page.route(/\/api\/v1\/me$/, route => json(route, { uuid: ME_UUID, username: "admin", enabled: true }));

  await page.route(/\/api\/v1\/persons(\?|$)/, route => json(route, { data: PERSONS, _metainfo: { totalCount: PERSONS.length } }));

  // The list honours `type` and `status` the way the server does, so a screen that forgets to ask
  // for its own type gets the other one's clusters and the test sees it.
  await page.route(/\/api\/v1\/clusters(\?|$)/, route => {
    const url = new URL(route.request().url());
    rec.clusterQueries.push(url.search);
    const type = url.searchParams.get("type");
    const status = url.searchParams.get("status");
    const data = clusters
      .filter(c => !type || c.type === type)
      .filter(c => !status || c.reviewStatus === status)
      .map(({ windows: _windows, ...c }) => c);
    return json(route, { data, _metainfo: { totalCount: data.length } });
  });

  await page.route(/\/api\/v1\/clusters\/[^/]+\/members$/, route => {
    const uuid = route.request().url().split("/clusters/")[1].split("/members")[0];
    const stored = clusters.find(c => c.uuid === uuid);
    const members = stored?.type === "speaker"
      ? (stored.windows ?? []).map((w, i) => ({ embeddingUuid: `${uuid}-w${i}`, assetUuid: ASSET_UUID, ...w }))
      : Array.from({ length: stored?.memberCount ?? 0 }, (_, i) => ({ embeddingUuid: `${uuid}-e${i}`, detectionUuid: `${uuid}-d${i}`, assetUuid: ASSET_UUID }));
    return json(route, { total: members.length, members });
  });

  await page.route(/\/api\/v1\/clusters\/[^/]+\/confirm$/, route => {
    const uuid = route.request().url().split("/clusters/")[1].split("/confirm")[0];
    const body = route.request().postDataJSON() as Record<string, unknown>;
    rec.confirms.push({ clusterUuid: uuid, body });
    const stored = clusters.find(c => c.uuid === uuid)!;
    stored.reviewStatus = "CONFIRMED";
    if (body.personUuid) {
      stored.personUuid = body.personUuid as string;
      stored.personName = nameOf(stored.personUuid);
    } else {
      stored.personUuid = `person-new-${++seq}`;
      stored.personName = body.alias as string;
    }
    const { windows: _w, ...out } = stored;
    return json(route, out);
  });

  await page.route(/\/api\/v1\/clusters\/[^/]+\/reject$/, route => {
    const uuid = route.request().url().split("/clusters/")[1].split("/reject")[0];
    rec.rejects.push(uuid);
    const stored = clusters.find(c => c.uuid === uuid)!;
    stored.reviewStatus = "REJECTED";
    const { windows: _w, ...out } = stored;
    return json(route, out);
  });

  await page.route(/\/api\/v1\/clusters\/[^/]+\/person$/, route => {
    const uuid = route.request().url().split("/clusters/")[1].split("/person")[0];
    rec.detaches.push(uuid);
    const stored = clusters.find(c => c.uuid === uuid)!;
    stored.reviewStatus = "PENDING";
    delete stored.personUuid;
    delete stored.personName;
    const { windows: _w, ...out } = stored;
    return json(route, out);
  });

  await page.route(/\/api\/v1\/assets\/[^/?]+$/, route =>
    json(route, { uuid: ASSET_UUID, file: { filename: "town-hall.mp4", mimeType: "video/mp4", size: 1000 } }));

  await page.route(/\/api\/v1\/assets\/[^/]+\/audio-clip/, route => {
    const url = new URL(route.request().url());
    rec.clips.push({ from: url.searchParams.get("from"), to: url.searchParams.get("to"), auth: route.request().headers()["authorization"] });
    return route.fulfill({ status: 200, contentType: "audio/wav", body: tinyWav() });
  });

  await page.route(/\/api\/v1\/assets\/[^/]+\/detections\/[^/]+\/crop/, route =>
    route.fulfill({ status: 200, contentType: "image/jpeg", body: TINY_JPEG }));

  await page.route(/\/api\/v1\/persons\/[^/]+\/voice-samples\/from-cluster$/, route => {
    const personUuid = route.request().url().split("/persons/")[1].split("/voice-samples")[0];
    rec.imports.push({ personUuid, body: route.request().postDataJSON() as Record<string, unknown> });
    return json(route, { uuid: "sample-1", filename: "speaker.wav", mimeType: "audio/wav", size: 10, url: "/x", hero: false, prints: [] }, 201);
  });
}

async function login(page: Page) {
  if (await page.getByPlaceholder("Username").count() === 0) return;
  await page.getByPlaceholder("Username").fill("admin");
  await page.getByPlaceholder("Password").fill("finger");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByPlaceholder("Username")).toBeHidden({ timeout: 10_000 });
}

async function openDetection(page: Page) {
  await page.goto("/");
  await login(page);
  await page.getByRole("button", { name: "Detection", exact: true }).first().click();
  await expect(page.getByTestId("facedetection-switcher")).toBeVisible({ timeout: 10_000 });
}

async function openSpeakers(page: Page) {
  await openDetection(page);
  await page.getByRole("tab", { name: "Speakers", exact: true }).click();
  await expect(page.getByTestId("speaker-review")).toBeVisible();
}

function speakerCard(page: Page, uuid: string) {
  return page.locator(`[data-testid=speaker-card][data-cluster-id="${uuid}"]`);
}

test.describe("Faces and speakers – each tab asks for its own clusters", () => {
  test("the face screen lists face clusters only, and the speakers tab speaker clusters only", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, [faceCluster(), speakerA()]);
    await openDetection(page);

    // One face card. Before the type filter, the speaker turned up here as a second, crop-less card.
    await expect(page.getByTestId("cluster-card")).toHaveCount(1, { timeout: 10_000 });
    expect(rec.clusterQueries.some(q => q.includes("type=face"))).toBe(true);
    expect(rec.clusterQueries.every(q => q.includes("type="))).toBe(true);

    await page.getByRole("tab", { name: "Speakers", exact: true }).click();
    await expect(page.getByTestId("speaker-card")).toHaveCount(1);
    expect(rec.clusterQueries.some(q => q.includes("type=speaker"))).toBe(true);
    await expect(page.getByTestId("facedetection-switcher")).toHaveCount(0);
  });
});

test.describe("Face cluster suggestion – mocked e2e", () => {
  test("Confirm on a suggestion attributes the cluster to the suggested person", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, [faceCluster()]);
    await openDetection(page);

    const card = page.locator(`[data-testid=cluster-card][data-cluster-id="${FACE_CLUSTER}"]`);
    await expect(card.getByTestId("cluster-suggestion-label")).toHaveText("Looks like Anna Meyer (91%)", { timeout: 10_000 });
    // A suggestion is not an attribution: nothing was confirmed by showing it.
    await expect(card).toHaveAttribute("data-assigned", "false");
    expect(rec.confirms).toEqual([]);

    await card.getByTestId("cluster-suggestion-confirm").click();
    await expect.poll(() => rec.confirms).toEqual([{ clusterUuid: FACE_CLUSTER, body: { personUuid: PERSON_ANNA } }]);
    await expect(card).toHaveAttribute("data-assigned", "true");
    await expect(card.getByTestId("cluster-suggestion")).toHaveCount(0);
    await expect(page.locator(`[data-testid=cluster-person-group][data-person-id="${PERSON_ANNA}"]`)).toHaveAttribute("data-person-name", "Anna Meyer");
  });

  test("a decided cluster offers no suggestion", async ({ page }) => {
    await installMocks(page, recorder(), [faceCluster({ reviewStatus: "REJECTED" })]);
    await openDetection(page);
    await expect(page.getByTestId("cluster-card")).toHaveCount(1, { timeout: 10_000 });
    await expect(page.getByTestId("cluster-suggestion")).toHaveCount(0);
  });
});

test.describe("Speaker review – mocked e2e", () => {
  test("a card names the asset, says how long and how often the voice spoke, and its status", async ({ page }) => {
    await installMocks(page, recorder(), [speakerA()]);
    await openSpeakers(page);

    const card = speakerCard(page, SPEAKER_A);
    await expect(card.getByTestId("speaker-asset")).toHaveText("town-hall.mp4");
    await expect(card.getByTestId("speaker-stats")).toHaveText("1:02 speaking · 3 turns");
    await expect(card.getByTestId("speaker-status")).toHaveText("Pending");
    await expect(card.getByTestId("speaker-suggestion-label")).toHaveText("Sounds like Anna Meyer (87%)");
  });

  test("Confirm on the suggestion sends the suggested person and shows them on the card", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, [speakerA()]);
    await openSpeakers(page);

    const card = speakerCard(page, SPEAKER_A);
    await card.getByTestId("speaker-suggestion-confirm").click();
    await expect.poll(() => rec.confirms).toEqual([{ clusterUuid: SPEAKER_A, body: { personUuid: PERSON_ANNA } }]);
    await expect(card.getByTestId("speaker-person-chip")).toHaveText("Anna Meyer");
    await expect(card).toHaveAttribute("data-review-status", "CONFIRMED");
    await expect(card.getByTestId("speaker-suggestion")).toHaveCount(0);
  });

  test("play fetches the longest turn, capped at 30 seconds, with the bearer token", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, [speakerA()]);
    await openSpeakers(page);

    const card = speakerCard(page, SPEAKER_A);
    // Nothing is cut until somebody asks: a grid of speakers is not forty WAVs.
    expect(rec.clips).toEqual([]);
    await card.getByTestId("speaker-play-play").click();
    await expect.poll(() => rec.clips).toEqual([{ from: "20000", to: "50000", auth: "Bearer fake-jwt" }]);
    await expect(card.getByTestId("speaker-play-audio")).toHaveAttribute("src", /^blob:/);
  });

  test("expanding merges the member windows into turns, each of them playable", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, [speakerA()]);
    await openSpeakers(page);

    const card = speakerCard(page, SPEAKER_A);
    await card.getByTestId("speaker-expand").click();
    // Five overlapping windows, two turns.
    const turns = card.getByTestId("speaker-turn");
    await expect(turns).toHaveCount(2);
    await expect(turns.nth(0)).toHaveAttribute("data-start-ms", "0");
    await expect(turns.nth(0)).toHaveAttribute("data-end-ms", "6000");
    await expect(turns.nth(1)).toHaveAttribute("data-start-ms", "20000");
    await expect(turns.nth(1)).toHaveAttribute("data-end-ms", "24500");

    await turns.nth(1).getByTestId("speaker-turn-play-play").click();
    await expect.poll(() => rec.clips).toEqual([{ from: "20000", to: "24500", auth: "Bearer fake-jwt" }]);
  });

  test("assign to an existing person, create a new one, reject, and detach", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, [speakerA({ suggestedPersonUuid: undefined, suggestedPersonName: undefined }), speakerB()]);
    await openSpeakers(page);

    // Assign A to Bob through the picker.
    const a = speakerCard(page, SPEAKER_A);
    await a.getByTestId("speaker-assign").click();
    const input = page.getByTestId("speaker-assign-input");
    await input.click();
    await input.fill("bob");
    await page.getByRole("option", { name: "Bob Stone", exact: true }).click();
    await page.getByTestId("speaker-assign-save").click();
    await expect.poll(() => rec.confirms).toEqual([{ clusterUuid: SPEAKER_A, body: { personUuid: PERSON_BOB } }]);
    await expect(a.getByTestId("speaker-person-chip")).toHaveText("Bob Stone");

    // Detach takes Bob back off.
    await a.getByTestId("speaker-person-chip").locator(".MuiChip-deleteIcon").click();
    await expect.poll(() => rec.detaches).toEqual([SPEAKER_A]);
    await expect(a.getByTestId("speaker-person-chip")).toHaveCount(0);
    await expect(a).toHaveAttribute("data-review-status", "PENDING");

    // B goes to a person who does not exist yet.
    const b = speakerCard(page, SPEAKER_B);
    await b.getByTestId("speaker-create").click();
    await page.getByTestId("speaker-create-alias").fill("Narrator");
    await page.getByTestId("speaker-create-save").click();
    await expect.poll(() => rec.confirms.length).toBe(2);
    expect(rec.confirms[1]).toEqual({ clusterUuid: SPEAKER_B, body: { alias: "Narrator" } });
    await expect(b.getByTestId("speaker-person-chip")).toHaveText("Narrator");

    // And A, now unattributed again, is rejected.
    await a.getByTestId("speaker-reject").click();
    await expect.poll(() => rec.rejects).toEqual([SPEAKER_A]);
    await expect(a.getByTestId("speaker-status")).toHaveText("Rejected");
  });

  test("Save as voice sample copies a confirmed speaker into that person's samples", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, [speakerA({ reviewStatus: "CONFIRMED", personUuid: PERSON_ANNA, personName: "Anna Meyer" })]);
    await openSpeakers(page);

    const card = speakerCard(page, SPEAKER_A);
    // A confirmed card has no suggestion and no assign/reject - the verdict is in.
    await expect(card.getByTestId("speaker-suggestion")).toHaveCount(0);
    await expect(card.getByTestId("speaker-assign")).toHaveCount(0);

    await card.getByTestId("speaker-save-sample").click();
    // No range: the server takes the longest turn, at most 30 s of it.
    await expect.poll(() => rec.imports).toEqual([{ personUuid: PERSON_ANNA, body: { clusterUuid: SPEAKER_A } }]);

    // A single turn can be saved with its own range.
    await card.getByTestId("speaker-expand").click();
    await card.getByTestId("speaker-turn").nth(0).getByTestId("speaker-turn-save").click();
    await expect.poll(() => rec.imports.length).toBe(2);
    expect(rec.imports[1]).toEqual({ personUuid: PERSON_ANNA, body: { clusterUuid: SPEAKER_A, timeFrom: 0, timeTo: 6000 } });
  });

  test("the status filter is asked of the server", async ({ page }) => {
    const rec = recorder();
    await installMocks(page, rec, [speakerA(), speakerB({ reviewStatus: "REJECTED" })]);
    await openSpeakers(page);
    await expect(page.getByTestId("speaker-card")).toHaveCount(2);

    await page.getByTestId("speakers-filter-status").click();
    await page.getByTestId("speakers-filter-status-option-REJECTED").click();
    await expect(page.getByTestId("speaker-card")).toHaveCount(1);
    await expect(speakerCard(page, SPEAKER_B)).toBeVisible();
    expect(rec.clusterQueries.some(q => q.includes("type=speaker") && q.includes("status=REJECTED"))).toBe(true);
  });
});
