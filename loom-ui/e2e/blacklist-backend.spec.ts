import { test, expect } from "@playwright/test";

/**
 * End-to-end tests for blacklist entries against the real Loom backend.
 *
 * Prerequisites — set env vars before running:
 *   VITE_API_BASE_URL  – points to the running Loom backend (e.g. /api/v1)
 *   VITE_PROXY_TARGET  – proxy target for the Vite dev server (e.g. http://localhost:8092)
 *
 * Assumes:
 *  1. A Loom server is running with demo data populated (DemoDatabaseInitializer)
 *  2. Default admin credentials: admin / finger
 *
 * Exercises the blacklist routes:
 *   POST /api/v1/blacklists           and   GET/POST/DELETE /api/v1/blacklists/:uuid
 *
 * A blacklist entry is a `name` plus an optional `assetUuid` reference — there is no
 * dedicated "blacklist from asset" route, so the association is created by passing the
 * asset's uuid in the create body (covered by the second test below).
 */

test.describe("Blacklist – full backend e2e", () => {
  test("create → edit → delete a blacklist entry via API", async ({ page }) => {
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

      const name = `pw-blacklist-${Date.now()}`;

      // Create — `name` is the only mandatory field.
      const createRes = await fetch(`/api/v1/blacklists`, {
        method: "POST",
        headers,
        body: JSON.stringify({ name }),
      });
      const created = await createRes.json();
      if (!created.uuid) return { error: "create failed", created };

      // Edit — rename the entry.
      const updateRes = await fetch(`/api/v1/blacklists/${created.uuid}`, {
        method: "POST",
        headers,
        body: JSON.stringify({ name: `${name}-edited` }),
      });
      await updateRes.json();

      // Load to confirm the rename stuck.
      const loadRes = await fetch(`/api/v1/blacklists/${created.uuid}`, { headers });
      const loaded = await loadRes.json();

      // Delete, then confirm it is gone.
      const deleteRes = await fetch(`/api/v1/blacklists/${created.uuid}`, { method: "DELETE", headers });
      const afterRes = await fetch(`/api/v1/blacklists/${created.uuid}`, { headers });

      return {
        createdUuid: created.uuid,
        loadedName: loaded.name,
        deleteStatus: deleteRes.status,
        afterDeleteStatus: afterRes.status,
      };
    });

    expect(result).not.toHaveProperty("error");
    const r = result as Record<string, unknown>;
    expect(r.createdUuid).toBeTruthy();
    expect(r.loadedName).toMatch(/-edited$/);
    expect([200, 204]).toContain(r.deleteStatus);
    expect(r.afterDeleteStatus).toBe(404);
  });

  test("blacklist an asset (create an entry referencing an asset) via API", async ({ page }) => {
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

      // Resolve a real demo asset to blacklist. An asset can only be blacklisted once, and
      // the demo already blacklists two of them, so pick one that is still free rather than
      // whichever asset the list happens to return first.
      const assetsRes = await fetch(`/api/v1/assets`, { headers });
      const assets = ((await assetsRes.json())?.data ?? []) as Array<{ uuid: string }>;
      const existingRes = await fetch(`/api/v1/blacklists`, { headers });
      const taken = new Set(
        (((await existingRes.json())?.data ?? []) as Array<{ assetUuid?: string }>)
          .map(b => b.assetUuid)
          .filter(Boolean) as string[],
      );
      const assetUuid = assets.map(a => a.uuid).find(uuid => !taken.has(uuid));
      if (!assetUuid) return { error: "no un-blacklisted assets found" };

      // Create an entry tied to the asset (the "blacklist from asset" flow).
      const createRes = await fetch(`/api/v1/blacklists`, {
        method: "POST",
        headers,
        body: JSON.stringify({ name: `pw-blacklist-asset-${Date.now()}`, assetUuid }),
      });
      const created = await createRes.json();
      if (!created.uuid) return { error: "create failed", created };

      // Load to confirm the asset reference persisted.
      const loadRes = await fetch(`/api/v1/blacklists/${created.uuid}`, { headers });
      const loaded = await loadRes.json();

      // Cleanup.
      const deleteRes = await fetch(`/api/v1/blacklists/${created.uuid}`, { method: "DELETE", headers });

      return {
        assetUuid,
        createdUuid: created.uuid,
        loadedAssetUuid: loaded.assetUuid,
        deleteStatus: deleteRes.status,
      };
    });

    expect(result).not.toHaveProperty("error");
    const r = result as Record<string, unknown>;
    expect(r.createdUuid).toBeTruthy();
    expect(r.loadedAssetUuid).toBe(r.assetUuid);
    expect([200, 204]).toContain(r.deleteStatus);
  });
});
