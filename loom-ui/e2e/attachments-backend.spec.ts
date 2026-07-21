import { test, expect } from "@playwright/test";

/**
 * End-to-end tests for attachments against the real Loom backend.
 *
 * Prerequisites — set env vars before running:
 *   VITE_API_BASE_URL  – points to the running Loom backend (e.g. /api/v1)
 *   VITE_PROXY_TARGET  – proxy target for the Vite dev server (e.g. http://localhost:8092)
 *
 * Assumes:
 *  1. A Loom server is running with demo data populated (DemoDatabaseInitializer)
 *  2. Default admin credentials: admin / finger
 *
 * Exercises the attachment routes:
 *   POST /api/v1/attachments (multipart file upload)   and   GET/POST/DELETE /api/v1/attachments/:uuid
 *
 * Create is a multipart upload: the backend derives filename, size, mimeType and a
 * content-addressed sha512sum from the uploaded bytes. The attachment model exposes
 * no dedicated thumbnail field — any UI thumbnail is rendered from the returned
 * sha512sum / mimeType, so this spec asserts those metadata round-trip after upload.
 */

test.describe("Attachments – full backend e2e", () => {
  test("upload (create) → load metadata → rename → delete an attachment via API", async ({ page }) => {
    await page.goto("/");

    const result = await page.evaluate(async () => {
      const loginRes = await fetch(`/api/v1/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username: "admin", password: "finger" }),
      });
      if (!loginRes.ok) return { error: `login failed ${loginRes.status}` };
      const token = (await loginRes.json()).token as string;
      const jsonHeaders = { "Content-Type": "application/json", Authorization: `Bearer ${token}` };
      const authHeader = { Authorization: `Bearer ${token}` };

      // Create via multipart upload. No Content-Type header — the browser sets the
      // multipart boundary; the server derives all metadata from the uploaded part.
      const bytes = new TextEncoder().encode("pw-attachment-bytes");
      const form = new FormData();
      form.append("file", new Blob([bytes], { type: "image/png" }), "pw-attachment.png");
      const createRes = await fetch(`/api/v1/attachments`, {
        method: "POST",
        headers: authHeader,
        body: form,
      });
      const created = await createRes.json();
      if (!created.uuid) return { error: "create failed", created };

      // Load the persisted metadata — the sha512sum a thumbnail would be keyed on.
      const loadRes = await fetch(`/api/v1/attachments/${created.uuid}`, { headers: jsonHeaders });
      const loaded = await loadRes.json();

      // Rename the attachment.
      const updateRes = await fetch(`/api/v1/attachments/${created.uuid}`, {
        method: "POST",
        headers: jsonHeaders,
        body: JSON.stringify({ filename: "pw-attachment-renamed.png" }),
      });
      await updateRes.json();

      const reloadRes = await fetch(`/api/v1/attachments/${created.uuid}`, { headers: jsonHeaders });
      const reloaded = await reloadRes.json();

      // Delete, then confirm it is gone.
      const deleteRes = await fetch(`/api/v1/attachments/${created.uuid}`, { method: "DELETE", headers: authHeader });
      const afterRes = await fetch(`/api/v1/attachments/${created.uuid}`, { headers: jsonHeaders });

      return {
        createStatus: createRes.status,
        createdUuid: created.uuid,
        loadedFilename: loaded.filename,
        loadedMimeType: loaded.mimeType,
        loadedSha512: loaded.sha512sum,
        renamedFilename: reloaded.filename,
        deleteStatus: deleteRes.status,
        afterDeleteStatus: afterRes.status,
      };
    });

    expect(result).not.toHaveProperty("error");
    const r = result as Record<string, unknown>;
    expect([200, 201]).toContain(r.createStatus);
    expect(r.createdUuid).toBeTruthy();
    expect(r.loadedFilename).toBe("pw-attachment.png");
    expect(r.loadedMimeType).toBe("image/png");
    // A content-addressed sha512sum (128 hex chars) is what a thumbnail/preview is keyed on.
    expect(String(r.loadedSha512)).toMatch(/^[0-9a-f]{128}$/);
    expect(r.renamedFilename).toBe("pw-attachment-renamed.png");
    expect([200, 204]).toContain(r.deleteStatus);
    expect(r.afterDeleteStatus).toBe(404);
  });
});
