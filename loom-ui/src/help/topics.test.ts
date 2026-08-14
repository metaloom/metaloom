import { describe, expect, it } from "vitest";
import { HELP_TOPICS, HelpTopic, helpUrl } from "./topics";
import en from "../i18n/locales/en.json";
import de from "../i18n/locales/de.json";
/**
 * The website's half of the contract. Imported rather than read off disk so `tsc` checks the shape
 * too, and so this needs no `@types/node` for one `readFileSync`.
 */
import siteHelp from "../../../website/data/en/help.json";

/**
 * The gate on the whole coachmark scheme.
 *
 * A help icon links at a topic id and trusts the website to know what that means. Nothing at
 * runtime can tell us it does not — the reader finds out, on a page we never see. So the two halves
 * are checked against each other here, at build time, in the one place that can see both: the
 * website and the UI are the same repository.
 *
 * The site's own build closes the other half of the loop. `layouts/help/list.html` renders every
 * entry of that map as a real `<a href>`, so `check-links.mjs` verifies each destination page *and
 * its anchor* actually exists. Between the two, a shortcut cannot be broken from either end without
 * something going red.
 */
const site = siteHelp.topics;
const ids = Object.keys(HELP_TOPICS) as HelpTopic[];

describe("help topic registry", () => {
  it("every topic the UI can link to exists in the website's map", () => {
    const known = new Set(site.map((t) => t.id));
    const missing = ids.filter((id) => !known.has(id));
    expect(
      missing,
      "add these to website/data/en/help.json, or the help icon lands on a page that cannot " +
        "resolve the id and falls back to a search",
    ).toEqual([]);
  });

  it("the website's map has no entry the UI never asks for", () => {
    // Not fatal on the site — an orphan is a link nobody follows — but it is always either a typo
    // or a hint that was removed from a screen and left behind here.
    const asked = new Set<string>(ids);
    const orphans = site.map((t) => t.id).filter((id) => !asked.has(id));
    expect(orphans, "remove these from website/data/en/help.json, or wire them to a screen").toEqual([]);
  });

  it("every destination is a documentation anchor, not a bare page", () => {
    // A shortcut that drops the reader at the top of a 600-line page has not answered anything.
    // *Which* anchors exist is the site build's business (check-links.mjs); that there is one at
    // all is this side's.
    for (const topic of site) {
      expect(topic.url, `${topic.id} must point at a section`).toMatch(/^\/docs\/.+#.+/);
    }
  });

  it("gives every topic a natural-language fallback query", () => {
    for (const id of ids) {
      const { query } = HELP_TOPICS[id];
      // The fallback runs against embedded prose. A one-word query retrieves almost nothing from
      // it, and the id itself retrieves nothing at all.
      expect(query.split(/\s+/).length, `${id} query is too short to retrieve anything`).toBeGreaterThanOrEqual(5);
      expect(query, `${id} query must not be the id`).not.toBe(id);
      // The site's literal pass reads `/[-_./]/ || /\d/` as "this is a name the product uses", and
      // scores an exact code-block match at 9 on the strength of it — which is a redirect. A
      // fallback phrased in words must not be mistaken for one.
      expect(
        /[-_./]|\d/.test(query),
        `${id} query looks like an identifier, which changes how the site scores it`,
      ).toBe(false);
    }
  });
});

describe("help topic labels", () => {
  // i18next's key separator is "." (the default — see src/i18n/i18n.ts), so a topic id like
  // "detection.faces" reaches a *nested* label at help.topic.detection.faces. A flat
  // "detection.faces" key in the locale file is unreachable and renders as the raw key.
  const labelOf = (bundle: unknown, id: string): unknown =>
    id.split(".").reduce<any>((node, part) => (node == null ? undefined : node[part]), (bundle as any).help?.topic);

  for (const [locale, bundle] of [["en", en], ["de", de]] as const) {
    it(`names every topic in ${locale}.json`, () => {
      for (const id of ids) {
        expect(typeof labelOf(bundle, id), `help.topic.${id} is missing from ${locale}.json`).toBe("string");
      }
    });
  }
});

describe("helpUrl", () => {
  it("carries both the id and the fallback query", () => {
    const url = new URL(helpUrl("pipeline.editing"));
    expect(url.pathname).toBe("/help/");
    expect(url.searchParams.get("t")).toBe("pipeline.editing");
    expect(url.searchParams.get("q")).toBe(HELP_TOPICS["pipeline.editing"].query);
  });

  it("percent-encodes the query rather than pasting it in", () => {
    // The query is prose with spaces in it; an unencoded one truncates at the first space and the
    // site's fallback silently searches for a fragment.
    expect(helpUrl("workflow.dedup")).not.toContain(" ");
    expect(helpUrl("workflow.dedup")).toContain("q=review+duplicate+photos");
  });

  it("points at the documentation site by default", () => {
    expect(helpUrl("chat")).toMatch(/^https:\/\/metaloom\.io\/help\/\?/);
  });

  it("produces a distinct URL for every topic", () => {
    const urls = ids.map(helpUrl);
    expect(new Set(urls).size).toBe(urls.length);
  });
});
