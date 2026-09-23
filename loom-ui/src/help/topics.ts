/**
 * The documentation shortcuts the in-product help icons point at.
 *
 * A help icon never holds a documentation URL. It holds a **topic id**, and the site decides what
 * that means today — because a Loom installation outlives the site it links to. Wiring this build
 * to `/docs/ui/#pipeline-editing` means every already-deployed instance breaks silently the day
 * that heading is reworded, on the reader's machine, where nothing here would ever find out.
 *
 * So a hint links at `https://metaloom.io/help/?t=<id>&q=<query>`, and the site resolves it:
 *
 *   - `t` against its curated map (`website/data/en/help.json`), which is instant and exact, and
 *     which its build checks — a retired anchor fails the site build rather than 404-ing a reader;
 *   - `q` against the documentation search index, if `t` is an id the site has never heard of.
 *     That is what makes a UI newer than the site — or older than a renamed topic — degrade into a
 *     ranked list of real pages instead of a dead link.
 *
 * The two halves do not repeat each other. The site owns id → destination; this file owns
 * id → label and the words to fall back on. `topics.test.ts` asserts every id below appears in the
 * site's map, so a topic cannot be added here and forgotten there.
 */

/** Every shortcut the UI can ask for. Ids are permanent: a shipped build keeps sending one. */
export const HELP_TOPICS = {
  chat: {
    /** Sent as `q` when the site does not recognise the id. Phrased as a reader would ask it —
     *  the index is embedded prose, and an identifier list retrieves nothing from it. */
    query: "chat with the ai agent about my media sessions and skills",
  },
  memory: {
    query: "notes the agent remembers across conversations agent memory",
  },
  search: {
    query: "search syntax filters find pictures by meaning",
  },
  uploads: {
    query: "upload files choose a library and pool watch upload progress",
  },
  "detection.faces": {
    query: "review detected faces confirm a group of faces name a person",
  },
  "detection.results": {
    query: "review what a model found confirm or reject a detection",
  },
  "workflow.rating": {
    query: "rate and tag pictures quickly with the keyboard",
  },
  "workflow.dedup": {
    query: "review duplicate photos choose which copy to keep",
  },
  "pipeline.editing": {
    query: "build a pipeline connect nodes typed ports run and debug it",
  },
  "admin.acl": {
    query: "roles and permissions decide who is allowed to do what",
  },
  assets: {
    query: "browse the media catalogue open a file and see what is known about it",
  },
  library: {
    query: "organise media into libraries and see what each one holds",
  },
  collections: {
    query: "gather assets into a named collection and share the set",
  },
  tags: {
    query: "tag collections rename a tag move it between collections rate it",
  },
  tasks: {
    query: "work assigned to people with a due date and a state",
  },
  persons: {
    query: "people in your media and the pictures that belong to them",
  },
  skills: {
    query: "instructions you give the agent for a job it should know",
  },
  chatSessions: {
    query: "conversations with the agent kept and returned to later",
  },
  monitoring: {
    query: "how the instance is doing ingest rates run outcomes storage growth",
  },
  cortex: {
    query: "worker machines that run the processing and what they may run",
  },
  "admin.spaces": {
    query: "separate one tenant of media from another with a space",
  },
  "admin.pools": {
    query: "where the bytes live filesystem or object storage and how full",
  },
  "admin.denylist": {
    query: "words and notes the agent must never keep or repeat",
  },
  "admin.indices": {
    query: "the search index rebuilding it and watching it catch up",
  },
  "admin.integrity": {
    query: "checks that look for rows pointing at things that are gone",
  },
  "admin.failureReports": {
    query: "the inbox of problems people reported from inside the product",
  },
} as const;

export type HelpTopic = keyof typeof HELP_TOPICS;

/**
 * Where `/help/` lives.
 *
 * Overridable because an air-gapped installation may mirror the documentation somewhere its
 * operators can actually reach, and a help icon that opens a host nobody can route to is worse
 * than no help icon. Build-time, like every other `VITE_*` (LOOM_UI.md §9.1).
 */
export const HELP_BASE_URL =
  import.meta.env.VITE_HELP_BASE_URL?.replace(/\/+$/, "") ?? "https://metaloom.io/help";

/**
 * The URL a help icon opens.
 *
 * Both parameters travel every time, and the redundancy is the point: `t` is what makes the normal
 * case exact and instant, `q` is what makes an id this site has not got still land the reader on
 * something real.
 */
export function helpUrl(topic: HelpTopic): string {
  const params = new URLSearchParams({ t: topic, q: HELP_TOPICS[topic].query });
  return `${HELP_BASE_URL}/?${params.toString()}`;
}
