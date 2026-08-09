import { describe, expect, it } from "vitest";
import {
  checkStatus, failuresAtLeast, groupByCategory, integrityQuery, notRunCount, passedCount,
  severityCounts,
  type DbIntegrityCategory, type DbIntegrityCheckResult, type DbIntegrityReport,
  type DbIntegritySeverity,
} from "./dbIntegrity";

function result(
  code: string,
  category: DbIntegrityCategory,
  severity: DbIntegritySeverity,
  count: number,
  error?: string,
): DbIntegrityCheckResult {
  return {
    check: { code, name: `Check ${code}`, category, severity, table: "t", column: "c", description: "d" },
    count,
    samples: count > 0 ? ["some-uuid"] : [],
    durationMs: 1,
    error: error ?? null,
  };
}

function report(results: DbIntegrityCheckResult[]): DbIntegrityReport {
  return {
    timestamp: "2026-08-09T11:24:07Z",
    durationMs: 12,
    checksRun: results.length,
    findingCount: results.reduce((sum, r) => sum + r.count, 0),
    clean: results.every(r => r.count === 0 && !r.error),
    results,
  };
}

describe("integrityQuery", () => {
  it("is empty when nothing is filtered", () => {
    expect(integrityQuery()).toBe("");
    expect(integrityQuery({})).toBe("");
  });

  it("encodes each filter it was given", () => {
    expect(integrityQuery({ check: "LOOM_SINGLETON" })).toBe("?check=LOOM_SINGLETON");
    expect(integrityQuery({ category: "CARDINALITY" })).toBe("?category=CARDINALITY");
    expect(integrityQuery({ severity: "ERROR" })).toBe("?severity=ERROR");
  });

  it("keeps a zero limit rather than treating it as absent", () => {
    // limit=0 means "count only, name no rows", which is a real request and not the default.
    expect(integrityQuery({ limit: 0 })).toBe("?limit=0");
  });
});

describe("failuresAtLeast", () => {
  const sample = report([
    result("A", "DANGLING", "ERROR", 2),
    result("B", "TIMESTAMP", "WARN", 1),
    result("C", "VOCABULARY", "ERROR", 0),
    result("D", "MANDATORY_FIELD", "INFO", 5),
  ]);

  it("keeps only results at or above the threshold", () => {
    expect(failuresAtLeast(sample, "ERROR").map(r => r.check.code)).toEqual(["A"]);
    expect(failuresAtLeast(sample, "WARN").map(r => r.check.code)).toEqual(["A", "B"]);
    expect(failuresAtLeast(sample, "INFO").map(r => r.check.code)).toEqual(["A", "B", "D"]);
  });

  it("ignores checks that found nothing", () => {
    expect(failuresAtLeast(sample, "ERROR").every(r => r.count > 0)).toBe(true);
  });

  it("counts a check that could not run as a failure", () => {
    // A check that threw tells us nothing, and "nothing" must not read as "clean" - that is the
    // one way this screen could actively mislead someone.
    const broken = report([result("E", "DANGLING", "ERROR", 0, "column does not exist")]);
    expect(failuresAtLeast(broken, "ERROR").map(r => r.check.code)).toEqual(["E"]);
  });
});

describe("severityCounts", () => {
  it("counts failing checks per severity, not offending rows", () => {
    const counts = severityCounts(report([
      result("A", "DANGLING", "ERROR", 900),
      result("B", "DANGLING", "ERROR", 1),
      result("C", "TIMESTAMP", "WARN", 3),
      result("D", "VOCABULARY", "ERROR", 0),
    ]));
    expect(counts).toEqual({ INFO: 0, WARN: 1, ERROR: 2 });
  });

  it("is all zeroes for a clean report", () => {
    expect(severityCounts(report([result("A", "DANGLING", "ERROR", 0)])))
      .toEqual({ INFO: 0, WARN: 0, ERROR: 0 });
  });
});

describe("groupByCategory", () => {
  it("preserves server order and omits empty categories", () => {
    const grouped = groupByCategory([
      result("A", "DANGLING", "ERROR", 1),
      result("B", "TIMESTAMP", "WARN", 1),
      result("C", "DANGLING", "ERROR", 1),
    ]);
    expect([...grouped.keys()]).toEqual(["DANGLING", "TIMESTAMP"]);
    expect(grouped.get("DANGLING")?.map(r => r.check.code)).toEqual(["A", "C"]);
  });

  it("groups passing checks too, so the catalogue table can list everything", () => {
    const grouped = groupByCategory([
      result("A", "DANGLING", "ERROR", 0),
      result("B", "DANGLING", "WARN", 4),
    ]);
    expect(grouped.get("DANGLING")?.map(r => r.check.code)).toEqual(["A", "B"]);
  });
});

describe("checkStatus", () => {
  it("reports the check's own severity when it found something", () => {
    expect(checkStatus(result("A", "DANGLING", "ERROR", 3))).toBe("ERROR");
    expect(checkStatus(result("B", "TIMESTAMP", "WARN", 1))).toBe("WARN");
  });

  it("reports PASSED when the check ran and found nothing", () => {
    expect(checkStatus(result("C", "VOCABULARY", "ERROR", 0))).toBe("PASSED");
  });

  it("never reports PASSED for a check that could not run", () => {
    // Count zero plus an error means "unknown". Reading that as a pass is the one way the table
    // could tell an operator the database is fine on the strength of a question nobody asked.
    expect(checkStatus(result("D", "DANGLING", "ERROR", 0, "column does not exist"))).toBe("NOT_RUN");
  });
});

describe("passedCount and notRunCount", () => {
  const sample = report([
    result("A", "DANGLING", "ERROR", 0),
    result("B", "TIMESTAMP", "WARN", 2),
    result("C", "VOCABULARY", "ERROR", 0),
    result("D", "CARDINALITY", "ERROR", 0, "relation does not exist"),
  ]);

  it("counts only checks that ran and found nothing as passed", () => {
    expect(passedCount(sample)).toBe(2);
  });

  it("counts a check that threw separately from both passes and findings", () => {
    expect(notRunCount(sample)).toBe(1);
    // The four checks split 2 passed + 1 finding + 1 not run, with nothing counted twice.
    expect(passedCount(sample) + severityCounts(sample).WARN + notRunCount(sample)).toBe(4);
  });
});
