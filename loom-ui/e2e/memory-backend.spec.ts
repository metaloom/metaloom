import { test, expect } from "@playwright/test";

/**
 * End-to-end tests for the agent memory bank against the real Loom backend.
 *
 * Prerequisites — set env vars before running:
 *   VITE_API_BASE_URL  – points to the running Loom backend (e.g. /api/v1)
 *   VITE_PROXY_TARGET  – proxy target for the Vite dev server (e.g. http://localhost:8092)
 *
 * Assumes:
 *  1. A Loom server is running with demo data populated (DemoDatabaseInitializer)
 *  2. The server was started with `LOOM_AGENT_MEMORY_ENABLED=true` — without it neither
 *     `MemoryEndpoint` nor `MemoryDenyRuleEndpoint` is registered and every route below 404s
 *     (LOOM_UI.md §7.8)
 *  3. Default admin credentials: admin / finger
 *
 * Exercises:
 *   GET/POST/PUT/DELETE /api/v1/memory/entry?scope=&ref=&id=   ·   GET /api/v1/memory[/scopes]
 *   GET/POST/DELETE     /api/v1/memory-deny-rules[/:uuid]
 *
 * The second test is the only one in the suite that proves the denylist is load-bearing: the
 * mocked specs can show the admin screen sending the right rule, but only a real server can show
 * that the rule then stops a write.
 */

test.describe("Agent memory – full backend e2e", () => {

  test("create → read → overwrite → delete a note, and POST refuses a taken id", async ({ page }) => {
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

      const scopesRes = await fetch(`/api/v1/memory/scopes`, { headers });
      if (scopesRes.status === 404) {
        return { error: "memory endpoints are not registered — start the server with LOOM_AGENT_MEMORY_ENABLED=true" };
      }
      const scopes = ((await scopesRes.json())?.scopes ?? []) as Array<{ scope: string; writable: boolean }>;
      if (!scopes.some(s => s.scope === "user")) return { error: "no user memory scope", scopes };

      // The id is a nested path and travels as a query parameter, never in the route.
      const id = `e2e/pw-note-${Date.now()}.md`;
      const entryUrl = (memoryId: string) => `/api/v1/memory/entry?scope=user&id=${encodeURIComponent(memoryId)}`;

      const createRes = await fetch(entryUrl(id), {
        method: "POST",
        headers,
        body: JSON.stringify({ body: "Durable fact one.", title: "PW note" }),
      });
      const created = await createRes.json();
      if (!createRes.ok) return { error: "create failed", created };

      // Same id again — a create must not become an overwrite.
      const conflictRes = await fetch(entryUrl(id), {
        method: "POST",
        headers,
        body: JSON.stringify({ body: "Impostor.", title: "PW note" }),
      });

      // The upsert is the edit path, and it bumps the version rather than adding a row.
      const updateRes = await fetch(entryUrl(id), {
        method: "PUT",
        headers,
        body: JSON.stringify({ body: "Durable fact two.", title: "PW note" }),
      });
      const updated = await updateRes.json();

      const loadRes = await fetch(entryUrl(id), { headers });
      const loaded = await loadRes.json();

      const listRes = await fetch(`/api/v1/memory?scope=user`, { headers });
      const listed = ((await listRes.json())?.entries ?? []) as Array<{ id: string }>;

      const deleteRes = await fetch(entryUrl(id), { method: "DELETE", headers });
      const afterRes = await fetch(entryUrl(id), { headers });

      return {
        createdId: created.id,
        createdVersion: created.version,
        conflictStatus: conflictRes.status,
        updatedVersion: updated.version,
        loadedBody: loaded.body,
        listedOnce: listed.filter(e => e.id === id).length,
        deleteStatus: deleteRes.status,
        afterDeleteStatus: afterRes.status,
      };
    });

    expect(result).not.toHaveProperty("error");
    const r = result as Record<string, unknown>;
    expect(r.createdId).toBeTruthy();
    // A second POST on the same id is a conflict — the note it would have replaced is untouched,
    // which the read below confirms carried the *update's* body, not the impostor's.
    expect(r.conflictStatus).toBe(409);
    expect(Number(r.updatedVersion)).toBeGreaterThan(Number(r.createdVersion));
    expect(r.loadedBody).toContain("Durable fact two.");
    expect(r.listedOnce).toBe(1);
    expect([200, 204]).toContain(r.deleteStatus);
    expect(r.afterDeleteStatus).toBe(404);
  });

  test("a deny rule blocks a matching memory write with its own message", async ({ page }) => {
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

      const stamp = Date.now();
      // A phrase nothing else in the demo data contains, so the rule can only fire on our own write.
      const secret = `pwforbidden${stamp}`;
      const message = "That phrase must never be stored. Record where it lives instead.";

      const ruleRes = await fetch(`/api/v1/memory-deny-rules`, {
        method: "POST",
        headers,
        body: JSON.stringify({ name: `pw-deny-${stamp}`, pattern: `(?i)\\b${secret}\\b`, message }),
      });
      if (ruleRes.status === 404) {
        return { error: "memory-deny-rules is not registered — start the server with LOOM_AGENT_MEMORY_ENABLED=true" };
      }
      const rule = await ruleRes.json();
      if (!rule.uuid) return { error: "rule create failed", rule };

      const entryUrl = (memoryId: string) => `/api/v1/memory/entry?scope=user&id=${encodeURIComponent(memoryId)}`;
      const cleanId = `e2e/pw-clean-${stamp}.md`;
      const deniedId = `e2e/pw-denied-${stamp}.md`;

      const write = async (memoryId: string, body: string, title: string) => {
        const res = await fetch(entryUrl(memoryId), {
          method: "PUT",
          headers,
          body: JSON.stringify({ body, title }),
        });
        return { status: res.status, payload: await res.json().catch(() => null) };
      };

      // The rule is specific: an unrelated note still goes through.
      const clean = await write(cleanId, "Nothing sensitive here.", "PW clean");
      // …and the matching one does not.
      const denied = await write(deniedId, `Remember this: ${secret}`, "PW denied");
      // The title is stored and rendered too, so it is checked just as the body is.
      const deniedByTitle = await write(deniedId, "Innocuous body.", `PW ${secret}`);
      // A refused write must leave nothing behind.
      const deniedLoad = await fetch(entryUrl(deniedId), { headers });

      // Disabling the rule is the admin's off switch — it has to actually take effect.
      await fetch(`/api/v1/memory-deny-rules/${rule.uuid}`, {
        method: "POST",
        headers,
        body: JSON.stringify({ enabled: false }),
      });
      const afterDisable = await write(deniedId, `Remember this: ${secret}`, "PW denied");

      // Cleanup: both notes and the rule.
      await fetch(entryUrl(cleanId), { method: "DELETE", headers });
      await fetch(entryUrl(deniedId), { method: "DELETE", headers });
      const ruleDeleteRes = await fetch(`/api/v1/memory-deny-rules/${rule.uuid}`, { method: "DELETE", headers });

      return {
        expectedMessage: message,
        secret,
        cleanStatus: clean.status,
        deniedStatus: denied.status,
        deniedMessage: denied.payload?.message,
        deniedByTitleStatus: deniedByTitle.status,
        deniedLoadStatus: deniedLoad.status,
        afterDisableStatus: afterDisable.status,
        ruleDeleteStatus: ruleDeleteRes.status,
      };
    });

    expect(result).not.toHaveProperty("error");
    const r = result as Record<string, unknown>;
    expect(r.cleanStatus).toBe(200);
    expect(r.deniedStatus).toBe(400);
    // The rule's own message reaches the caller — and it does not echo what it matched, which is
    // the whole point of letting the admin write it (MemoryDenylist).
    expect(r.deniedMessage).toBe(r.expectedMessage);
    expect(String(r.deniedMessage)).not.toContain(String(r.secret));
    expect(r.deniedByTitleStatus).toBe(400);
    expect(r.deniedLoadStatus).toBe(404);
    expect(r.afterDisableStatus).toBe(200);
    expect([200, 204]).toContain(r.ruleDeleteStatus);
  });
});
