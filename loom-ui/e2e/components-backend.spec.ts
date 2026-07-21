import { test, expect } from "@playwright/test";

/**
 * End-to-end tests for asset components against the real Loom backend.
 *
 * Prerequisites — set env vars before running:
 *   VITE_API_BASE_URL  – points to the running Loom backend (e.g. /api/v1)
 *   VITE_PROXY_TARGET  – proxy target for the Vite dev server (e.g. http://localhost:8092)
 *
 * Assumes:
 *  1. A Loom server is running with demo data populated (DemoDatabaseInitializer)
 *  2. Default admin credentials: admin / finger
 *
 * Exercises the asset-scoped component routes for every modality:
 *   GET/POST   /api/v1/assets/:assetUuid/components
 *   GET/POST/DELETE /api/v1/assets/:assetUuid/components/:compUuid
 *
 * Component modalities (AssetComponentType): GEO, IMAGE, VIDEO, AUDIO, DOC, TRANSCRIPT, JSON.
 * `source` is editable on every modality; the transcript free-text is edited via
 * `transcript.transcriptText` (covered by the dedicated transcript test below).
 */

test.describe("Asset components – full backend e2e", () => {
  test("create → edit → delete a component for each modality via API", async ({ page }) => {
    await page.goto("/");

    const result = await page.evaluate(async () => {
      const loginRes = await fetch(`/api/v1/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username: "admin", password: "finger" }),
      });
      if (!loginRes.ok) return { error: `login failed ${loginRes.status}` };
      const token = (await loginRes.json()).token as string;
      const headers = { "Content-Type": "application/json", Authorization: `Bearer ${token}` };

      const assetsRes = await fetch(`/api/v1/assets`, { headers });
      const assetUuid = (await assetsRes.json())?.data?.[0]?.uuid as string | undefined;
      if (!assetUuid) return { error: "no assets found" };

      // One create-body per modality, each with its type-specific info block.
      const modalities: Array<{ type: string; body: Record<string, unknown> }> = [
        { type: "GEO", body: { geo: { lon: 13.4, lat: 52.5, alias: "pw-spot" } } },
        { type: "IMAGE", body: { image: { dominantColor: "#FF0000", width: 1920, height: 1080 } } },
        { type: "VIDEO", body: { video: { bitrate: 5000, encoding: "h264", width: 1920, height: 1080, duration: 60 } } },
        { type: "AUDIO", body: { audio: { bpm: 120, bitrate: 320, channels: 2, encoding: "aac", samplingRate: 44100, duration: 180 } } },
        { type: "DOC", body: { document: { plainText: "pw hello", wordCount: 2 } } },
        { type: "JSON", body: { json: { schemaType: "pw-schema", data: { detections: 3 } } } },
      ];

      const base = `/api/v1/assets/${assetUuid}/components`;
      const perModality: Array<Record<string, unknown>> = [];

      for (const m of modalities) {
        // Create.
        const createRes = await fetch(base, {
          method: "POST",
          headers,
          body: JSON.stringify({ type: m.type, source: `pw-${m.type}`, ...m.body }),
        });
        const created = await createRes.json();
        if (!created.uuid) {
          return { error: `create failed for ${m.type}`, created };
        }

        // Edit — `source` is updatable on every modality; the server resolves the
        // component's real type from its uuid, so no `type` is needed in the body.
        const updateRes = await fetch(`${base}/${created.uuid}`, {
          method: "POST",
          headers,
          body: JSON.stringify({ source: `pw-${m.type}-edited` }),
        });
        await updateRes.json();

        // Load to confirm the edit persisted.
        const loadRes = await fetch(`${base}/${created.uuid}`, { headers });
        const loaded = await loadRes.json();

        // Delete, then confirm it is gone.
        const deleteRes = await fetch(`${base}/${created.uuid}`, { method: "DELETE", headers });
        const afterRes = await fetch(`${base}/${created.uuid}`, { headers });

        perModality.push({
          type: m.type,
          createdType: created.type,
          createdUuid: created.uuid,
          loadedSource: loaded.source,
          imageColor: loaded.image?.dominantColor,
          deleteStatus: deleteRes.status,
          afterDeleteStatus: afterRes.status,
        });
      }

      return { perModality };
    });

    expect(result).not.toHaveProperty("error");
    const rows = (result as { perModality: Array<Record<string, unknown>> }).perModality;
    expect(rows).toHaveLength(6);
    for (const row of rows) {
      expect(row.createdUuid, `create ${row.type}`).toBeTruthy();
      expect(row.createdType, `type ${row.type}`).toBe(row.type);
      expect(row.loadedSource, `edited source ${row.type}`).toBe(`pw-${row.type}-edited`);
      expect([200, 204], `delete ${row.type}`).toContain(row.deleteStatus);
      expect(row.afterDeleteStatus, `gone ${row.type}`).toBe(404);
    }
    // The type-specific info block must round-trip too (spot-checked on the IMAGE component).
    expect(rows.find(r => r.type === "IMAGE")?.imageColor).toBe("#FF0000");
  });

  test("transcript component text can be created and edited via API", async ({ page }) => {
    await page.goto("/");

    const result = await page.evaluate(async () => {
      const loginRes = await fetch(`/api/v1/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username: "admin", password: "finger" }),
      });
      if (!loginRes.ok) return { error: `login failed ${loginRes.status}` };
      const token = (await loginRes.json()).token as string;
      const headers = { "Content-Type": "application/json", Authorization: `Bearer ${token}` };

      const assetsRes = await fetch(`/api/v1/assets`, { headers });
      const assetUuid = (await assetsRes.json())?.data?.[0]?.uuid as string | undefined;
      if (!assetUuid) return { error: "no assets found" };

      const base = `/api/v1/assets/${assetUuid}/components`;

      // Create a transcript component with free-text.
      const createRes = await fetch(base, {
        method: "POST",
        headers,
        body: JSON.stringify({
          type: "TRANSCRIPT",
          source: "pw-whisper",
          transcript: { lang: "en", transcriptText: "original transcript text", duration: 10, model: "whisper-1" },
        }),
      });
      const created = await createRes.json();
      if (!created.uuid) return { error: "create failed", created };

      // Edit only the transcript free-text.
      const updateRes = await fetch(`${base}/${created.uuid}`, {
        method: "POST",
        headers,
        body: JSON.stringify({ transcript: { transcriptText: "edited transcript text" } }),
      });
      await updateRes.json();

      // Load to confirm the edited text persisted.
      const loadRes = await fetch(`${base}/${created.uuid}`, { headers });
      const loaded = await loadRes.json();

      // Cleanup.
      const deleteRes = await fetch(`${base}/${created.uuid}`, { method: "DELETE", headers });

      return {
        createdUuid: created.uuid,
        createdText: created.transcript?.transcriptText,
        loadedText: loaded.transcript?.transcriptText,
        loadedLang: loaded.transcript?.lang,
        deleteStatus: deleteRes.status,
      };
    });

    expect(result).not.toHaveProperty("error");
    const r = result as Record<string, unknown>;
    expect(r.createdUuid).toBeTruthy();
    expect(r.createdText).toBe("original transcript text");
    expect(r.loadedText).toBe("edited transcript text");
    // The untouched language field must survive the partial update.
    expect(r.loadedLang).toBe("en");
    expect([200, 204]).toContain(r.deleteStatus);
  });
});
