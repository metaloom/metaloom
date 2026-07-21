import { test, expect } from "@playwright/test";

/**
 * End-to-end tests for asset annotations against the real Loom backend.
 *
 * Prerequisites — set env vars before running:
 *   VITE_API_BASE_URL  – points to the running Loom backend (e.g. /api/v1)
 *   VITE_PROXY_TARGET  – proxy target for the Vite dev server (e.g. http://localhost:8092)
 *
 * Assumes:
 *  1. A Loom server is running with demo data populated (DemoDatabaseInitializer)
 *  2. Default admin credentials: admin / finger
 *
 * Exercises the annotation routes (annotations carry a temporal `from`/`to` and a
 * spatial `startX`/`startY`/`width`/`height` region via the nested `area` object):
 *   POST /api/v1/annotations          and   GET/POST/DELETE /api/v1/annotations/:uuid
 *
 * The whole create → edit → delete lifecycle is driven through `fetch` so the spec
 * exercises the backend directly and always removes what it created.
 */

test.describe("Annotations – full backend e2e", () => {
  test("create → edit (time + area) → delete an annotation on an asset via API", async ({ page }) => {
    // Load the app so relative /api/v1 fetches hit the Vite proxy → backend.
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

      // Grab a real demo asset to attach the annotation to.
      const assetsRes = await fetch(`/api/v1/assets`, { headers });
      const assetUuid = (await assetsRes.json())?.data?.[0]?.uuid as string | undefined;
      if (!assetUuid) return { error: "no assets found" };

      // Create — type + title + assetUuid are mandatory; attach an initial temporal+spatial area.
      const createRes = await fetch(`/api/v1/annotations`, {
        method: "POST",
        headers,
        body: JSON.stringify({
          type: "FEEDBACK",
          title: "pw-annotation",
          description: "created by playwright",
          assetUuid,
          area: { from: 1000, to: 2000, startX: 10, startY: 20, width: 100, height: 200 },
        }),
      });
      const created = await createRes.json();
      if (!created.uuid) return { error: "create failed", created };

      // Edit — move the time window and the area box, and rename the title.
      const updateRes = await fetch(`/api/v1/annotations/${created.uuid}`, {
        method: "POST",
        headers,
        body: JSON.stringify({
          title: "pw-annotation-edited",
          area: { from: 5000, to: 6000, startX: 55, startY: 65, width: 150, height: 250 },
        }),
      });
      await updateRes.json();

      // Load to confirm the edit persisted through the backend.
      const loadRes = await fetch(`/api/v1/annotations/${created.uuid}`, { headers });
      const loaded = await loadRes.json();

      // Delete, then confirm it is gone (load now 404s).
      const deleteRes = await fetch(`/api/v1/annotations/${created.uuid}`, { method: "DELETE", headers });
      const afterRes = await fetch(`/api/v1/annotations/${created.uuid}`, { headers });

      return {
        createdUuid: created.uuid,
        createdType: created.type,
        loadedTitle: loaded.title,
        loadedFrom: loaded.area?.from,
        loadedStartX: loaded.area?.startX,
        loadedWidth: loaded.area?.width,
        deleteStatus: deleteRes.status,
        afterDeleteStatus: afterRes.status,
      };
    });

    expect(result).not.toHaveProperty("error");
    const r = result as Record<string, unknown>;
    expect(r.createdUuid).toBeTruthy();
    expect(r.createdType).toBe("FEEDBACK");
    expect(r.loadedTitle).toBe("pw-annotation-edited");
    // The moved time window and area box must have round-tripped through the backend.
    expect(r.loadedFrom).toBe(5000);
    expect(r.loadedStartX).toBe(55);
    expect(r.loadedWidth).toBe(150);
    expect([200, 204]).toContain(r.deleteStatus);
    expect(r.afterDeleteStatus).toBe(404);
  });
});
