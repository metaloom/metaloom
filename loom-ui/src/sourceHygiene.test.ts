import { describe, it, expect } from "vitest";

/**
 * Source-level guard rails.
 *
 * `src/Login/Login.tsx` was an unmodified MUI template whose only submit handler was
 * `console.log({ email, password })`. It was never mounted — `index.html` loads `main.tsx` and the
 * legacy `src/index.js` entry point that routed to it was orphaned — but it sat in the repository
 * one accidental import away from being live, complete with an `eslint-disable no-console` above
 * the credential dump. It has been deleted; this test is what stops the pattern from coming back
 * in any form, in any file.
 *
 * The sources are read through Vite's raw glob rather than `fs`: this package has no `@types/node`,
 * so an `fs` import compiles under vitest and then fails `tsc --noEmit` (see `nodeColors.test.ts`).
 * The glob deliberately excludes test files — this one names the forbidden words itself.
 */
const SOURCES = import.meta.glob<string>(
  [
    "./**/*.{ts,tsx,js,jsx}",
    "!./**/*.{test,spec}.{ts,tsx}",
    "!./**/*.d.ts",
  ],
  { query: "?raw", import: "default", eager: true }
);

/** Words that must never appear inside a console call's arguments. */
const CREDENTIAL_WORDS = /password|passwd|\bsecret|credential|api[_-]?key|authorization/i;

/**
 * Return the argument text of every `console.<method>(...)` call in `source`.
 *
 * Paren matching rather than a regex: the offending call spanned four lines and an object literal,
 * which a single-line `console\.log\(.*\)` pattern misses entirely.
 */
function consoleCallArguments(source: string): string[] {
  const calls: string[] = [];
  const opener = /console\s*\.\s*[A-Za-z]+\s*\(/g;
  let match: RegExpExecArray | null;
  while ((match = opener.exec(source)) !== null) {
    const start = match.index + match[0].length;
    let depth = 1;
    let i = start;
    for (; i < source.length && depth > 0; i++) {
      const c = source[i];
      if (c === "(") depth++;
      else if (c === ")") depth--;
    }
    calls.push(source.slice(start, i - 1));
  }
  return calls;
}

describe("source hygiene", () => {
  it("reads the source tree", () => {
    // A broken glob would make every assertion below pass vacuously.
    expect(Object.keys(SOURCES).length).toBeGreaterThan(100);
  });

  it("never logs credentials to the console", () => {
    const offenders: string[] = [];
    for (const [path, source] of Object.entries(SOURCES)) {
      for (const args of consoleCallArguments(source)) {
        if (CREDENTIAL_WORDS.test(args)) {
          offenders.push(`${path}: console call over ${args.replace(/\s+/g, " ").trim()}`);
        }
      }
    }
    expect(offenders).toEqual([]);
  });

  it("has no legacy Login screen or legacy entry point", () => {
    const paths = Object.keys(SOURCES);
    expect(paths.filter((p) => /^\.\/Login\//.test(p) || p === "./index.js")).toEqual([]);
  });
});
