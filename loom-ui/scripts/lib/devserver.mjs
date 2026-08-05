// Starting (or finding) the Vite dev server the capture scripts drive.
//
// Extracted from capture-debug-screenshots.mjs so the per-node capture scripts do not each grow
// their own copy of two subtleties that were both learned the hard way.

import { spawn } from "child_process";
import path from "path";

const sleep = ms => new Promise(r => setTimeout(r, ms));

/**
 * Answering, not merely bound.
 *
 * A TCP probe is the obvious check and the wrong one: Vite binds to the `localhost` *name*, which
 * on a dual-stack host may be `::1` alone, so a connect to 127.0.0.1 reports a server that is
 * plainly running as absent. Ask over HTTP and let the resolver do what the browser will do.
 */
export async function serverAnswering(port) {
  try {
    await fetch(`http://localhost:${port}/ui/`, { signal: AbortSignal.timeout(1500) });
    return true;
  } catch {
    return false;
  }
}

/**
 * Return a handle to a running dev server, starting one if nothing is listening.
 *
 * @returns the spawned process, or null when an existing server was reused (do not kill that one).
 */
export async function ensureDevServer(root, port) {
  if (await serverAnswering(port)) {
    console.log(`using the Vite server already listening on ${port}`);
    return null;
  }
  console.log(`starting Vite on ${port}…`);
  // `npx` hangs in this environment — call the installed binary directly.
  const bin = path.resolve(root, "node_modules/.bin/vite");
  const proc = spawn(bin, ["--port", String(port), "--strictPort"], { cwd: root, stdio: "ignore" });
  for (let i = 0; i < 100; i++) {
    await sleep(300);
    if (await serverAnswering(port)) return proc;
  }
  proc.kill();
  throw new Error(`Vite did not come up on ${port}`);
}
