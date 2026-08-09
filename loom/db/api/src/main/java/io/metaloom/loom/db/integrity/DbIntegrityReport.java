package io.metaloom.loom.db.integrity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The result of one sweep: every check that ran, in registry order, whether it found anything or
 * not. Clean checks are kept deliberately - "23 checks ran, 0 findings" is a more useful answer than
 * an empty list, and the admin screen renders both halves.
 *
 * @param startedAt
 *            when the sweep began
 * @param durationMs
 *            wall time of the whole sweep
 * @param results
 *            one entry per check that the scope admitted, in registry order
 */
public record DbIntegrityReport(Instant startedAt, long durationMs, List<DbIntegrityCheckResult> results) {

	public DbIntegrityReport {
		results = results == null ? List.of() : List.copyOf(results);
	}

	/** Nothing found and nothing errored. */
	public boolean isClean() {
		return results.stream().allMatch(DbIntegrityCheckResult::isClean);
	}

	/** Whether anything at or above {@code min} was found, counting checks that threw. */
	public boolean has(DbIntegritySeverity min) {
		return !failures(min).isEmpty();
	}

	/**
	 * Checks that found something, or that threw, at or above {@code min}. A check that threw counts
	 * at its own declared severity: we do not know what it would have found, and silently passing is
	 * the wrong default for an integrity sweep.
	 */
	public List<DbIntegrityCheckResult> failures(DbIntegritySeverity min) {
		return results.stream()
			.filter(r -> !r.isClean())
			.filter(r -> r.severity().atLeast(min))
			.toList();
	}

	public Optional<DbIntegrityCheckResult> result(String code) {
		return results.stream().filter(r -> r.code().equals(code)).findFirst();
	}

	/** Total offending rows across every check. */
	public long findingCount() {
		return results.stream().mapToLong(DbIntegrityCheckResult::count).sum();
	}

	public long countFor(String code) {
		return result(code).map(DbIntegrityCheckResult::count).orElse(0L);
	}

	/**
	 * The human-readable rendering used as a JUnit failure message and in the CLI. Lists every failing
	 * check with its code, location, count and samples.
	 *
	 * @param min
	 *            lowest severity to include
	 * @return a multi-line description
	 */
	public String describe(DbIntegritySeverity min) {
		List<DbIntegrityCheckResult> failures = failures(min);
		StringBuilder b = new StringBuilder();
		b.append("Database integrity check failed: ")
			.append(failures.size()).append(" of ").append(results.size())
			.append(" checks reported findings at ").append(min).append(" or above")
			.append(" (sweep took ").append(durationMs).append("ms).\n");

		for (DbIntegrityCheckResult r : failures) {
			DbIntegrityCheckInfo info = r.check();
			// The code first, because that is what someone greps for or puts in an ignore list; the
			// name after it, because that is what makes the line readable at a glance.
			b.append("\n  [").append(info.severity()).append("] ").append(info.code())
				.append(" - ").append(info.name())
				.append(" (").append(info.category()).append(") ").append(info.location());
			if (r.error() != null) {
				b.append(" - CHECK FAILED TO RUN: ").append(r.error()).append('\n');
			} else {
				b.append(" - ").append(r.count()).append(r.count() == 1 ? " row" : " rows").append('\n');
			}
			b.append("          ").append(info.description()).append('\n');
			if (!r.samples().isEmpty()) {
				b.append("          samples:");
				for (DbIntegrityFinding f : r.samples()) {
					b.append("\n            ").append(f);
				}
				if (r.count() > r.samples().size()) {
					b.append("\n            ... and ").append(r.count() - r.samples().size()).append(" more");
				}
				b.append('\n');
			}
		}
		return b.toString();
	}

	@Override
	public String toString() {
		return "DbIntegrityReport[" + results.size() + " checks, " + findingCount() + " findings, "
			+ durationMs + "ms]";
	}
}
