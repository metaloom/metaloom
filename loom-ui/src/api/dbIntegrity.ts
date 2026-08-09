import { API_BASE_URL } from "./config";

/**
 * Client for `GET /db-integrity` — whether the database still holds the invariants the server
 * assumes.
 *
 * The report covers what the schema itself cannot enforce: rows pointing at things that are gone
 * through columns carrying no foreign key, audit timestamps that contradict each other, required
 * names left blank, text columns holding values outside their vocabulary, and constraints that rows
 * were written around.
 *
 * It is computed per request. There is no stored report and no job to poll — refetching is how you
 * re-run it.
 */

/** What kind of defect a check looks for. */
export type DbIntegrityCategory =
  | "DANGLING"
  | "TIMESTAMP"
  | "MANDATORY_FIELD"
  | "VOCABULARY"
  | "CARDINALITY";

/** How badly a finding matters. Only ERROR is treated as a failure by the server-side test helper. */
export type DbIntegritySeverity = "INFO" | "WARN" | "ERROR";

/** Catalogue entry: what a check is, independent of whether it has been run. */
export interface DbIntegrityCheck {
  /** Stable identifier. The only field to branch on; the description may be reworded. */
  code: string;
  category: DbIntegrityCategory;
  severity: DbIntegritySeverity;
  /** The table read. Names a theme rather than one table where a check sweeps several. */
  table: string;
  /** The column read, or null when the check is about whole rows. */
  column?: string | null;
  description: string;
}

/** What one check found. `count` is the whole truth; `samples` is capped by the server. */
export interface DbIntegrityCheckResult {
  check: DbIntegrityCheck;
  count: number;
  samples: string[];
  durationMs: number;
  /** Set only when the check itself threw. The rest of the sweep still ran. */
  error?: string | null;
}

export interface DbIntegrityReport {
  /** Server time the sweep started (ISO 8601 instant). */
  timestamp: string;
  durationMs: number;
  checksRun: number;
  findingCount: number;
  clean: boolean;
  results: DbIntegrityCheckResult[];
}

export interface DbIntegrityCheckList {
  checks: DbIntegrityCheck[];
}

/** Filters accepted by the report route. An unknown code or category is a 400, not an empty report. */
export interface DbIntegrityFilters {
  check?: string;
  category?: DbIntegrityCategory;
  severity?: DbIntegritySeverity;
  /** Offending rows to name per finding. The server caps this. */
  limit?: number;
}

function authHeaders(token: string): Record<string, string> {
  return {
    "Content-Type": "application/json",
    Authorization: `Bearer ${token}`,
  };
}

async function handleResponse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`API error ${res.status}: ${text}`);
  }
  return res.json() as Promise<T>;
}

/** Build the query string for the report route. Exported so it can be unit-tested. */
export function integrityQuery(filters?: DbIntegrityFilters): string {
  const params = new URLSearchParams();
  if (filters?.check) params.set("check", filters.check);
  if (filters?.category) params.set("category", filters.category);
  if (filters?.severity) params.set("severity", filters.severity);
  if (filters?.limit !== undefined) params.set("limit", String(filters.limit));
  const query = params.toString();
  return query ? `?${query}` : "";
}

/** Run the checks and return what they found. */
export async function loadDbIntegrityReport(
  token: string,
  filters?: DbIntegrityFilters,
): Promise<DbIntegrityReport> {
  const res = await fetch(`${API_BASE_URL}/db-integrity${integrityQuery(filters)}`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<DbIntegrityReport>(res);
}

/** List the registered checks without running any of them. */
export async function loadDbIntegrityChecks(token: string): Promise<DbIntegrityCheckList> {
  const res = await fetch(`${API_BASE_URL}/db-integrity/checks`, {
    method: "GET",
    headers: authHeaders(token),
  });
  return handleResponse<DbIntegrityCheckList>(res);
}

// ── Reading a report ──────────────────────────────────────────────────────
//
// The panel needs the same few questions answered every render, and each is easy to get subtly
// wrong at the call site — most of all "did anything fail", where a check that could not run must
// count as a failure rather than as a pass.

/** Results that found something, or that could not run, at or above `min`. */
export function failuresAtLeast(
  report: DbIntegrityReport,
  min: DbIntegritySeverity,
): DbIntegrityCheckResult[] {
  const order: DbIntegritySeverity[] = ["INFO", "WARN", "ERROR"];
  const floor = order.indexOf(min);
  return report.results.filter(
    r => (r.count > 0 || !!r.error) && order.indexOf(r.check.severity) >= floor,
  );
}

/** How many results failed at each severity. Used for the summary row. */
export function severityCounts(report: DbIntegrityReport): Record<DbIntegritySeverity, number> {
  const counts: Record<DbIntegritySeverity, number> = { INFO: 0, WARN: 0, ERROR: 0 };
  for (const result of report.results) {
    if (result.count > 0 || result.error) counts[result.check.severity] += 1;
  }
  return counts;
}

/**
 * Results grouped by category, in the order the server sent them.
 *
 * A category with nothing in it is omitted rather than rendered empty — the report already says how
 * many checks ran, so an empty group adds a heading and no information.
 */
export function groupByCategory(
  results: DbIntegrityCheckResult[],
): Map<DbIntegrityCategory, DbIntegrityCheckResult[]> {
  const grouped = new Map<DbIntegrityCategory, DbIntegrityCheckResult[]>();
  for (const result of results) {
    const bucket = grouped.get(result.check.category);
    if (bucket) bucket.push(result);
    else grouped.set(result.check.category, [result]);
  }
  return grouped;
}
