package io.metaloom.loom.db.jooq.integrity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.db.integrity.DbIntegrityCheckInfo;
import io.metaloom.loom.db.integrity.DbIntegrityCheckResult;
import io.metaloom.loom.db.integrity.DbIntegrityFinding;
import io.metaloom.loom.db.integrity.DbIntegrityReport;
import io.metaloom.loom.db.integrity.DbIntegrityScope;
import io.metaloom.loom.db.integrity.DbIntegrityService;

/**
 * Runs the checks in {@link DbIntegrityChecks} against Postgres.
 *
 * <p>
 * Blocking, like every other jOOQ caller in the tree - the REST layer wraps it in
 * {@code vertx.executeBlocking}, and tests call it straight.
 * </p>
 */
@Singleton
public class JooqDbIntegrityService implements DbIntegrityService {

	private static final Logger log = LoggerFactory.getLogger(JooqDbIntegrityService.class);

	private final DSLContext ctx;
	private final List<DbIntegrityCheck> checks;

	@Inject
	public JooqDbIntegrityService(DSLContext ctx) {
		this(ctx, DbIntegrityChecks.all());
	}

	/** Visible for tests that want to run one check in isolation. */
	public JooqDbIntegrityService(DSLContext ctx, List<DbIntegrityCheck> checks) {
		this.ctx = ctx;
		this.checks = List.copyOf(checks);
	}

	@Override
	public DbIntegrityReport check(DbIntegrityScope scope) {
		Instant startedAt = Instant.now();
		long sweepStart = System.nanoTime();

		List<DbIntegrityCheckResult> results = new ArrayList<>();
		for (DbIntegrityCheck check : checks) {
			DbIntegrityCheckInfo info = check.info();
			if (!scope.accepts(info)) {
				continue;
			}
			results.add(run(check, info, scope.sampleLimit()));
		}

		long durationMs = millisSince(sweepStart);
		DbIntegrityReport report = new DbIntegrityReport(startedAt, durationMs, results);
		if (log.isDebugEnabled()) {
			log.debug("Integrity sweep: {} checks, {} findings, {}ms", results.size(), report.findingCount(),
				durationMs);
		}
		return report;
	}

	@Override
	public List<DbIntegrityCheckInfo> catalog() {
		return checks.stream().map(DbIntegrityCheck::info).toList();
	}

	/**
	 * A check that throws is recorded and the sweep continues. A column dropped by a migration breaks
	 * exactly one check; letting it abort the run would hide the other twenty-two at precisely the
	 * moment the database is most likely to be in an interesting state.
	 */
	private DbIntegrityCheckResult run(DbIntegrityCheck check, DbIntegrityCheckInfo info, int sampleLimit) {
		long start = System.nanoTime();
		try {
			long count = check.count(ctx);
			if (count == 0) {
				return DbIntegrityCheckResult.clean(info, millisSince(start));
			}
			List<DbIntegrityFinding> samples = sampleLimit == 0
				? List.of()
				: check.sample(ctx, sampleLimit);
			return new DbIntegrityCheckResult(info, count, samples, millisSince(start), null);
		} catch (Exception e) {
			log.warn("Integrity check {} failed to run", info.code(), e);
			return DbIntegrityCheckResult.failed(info, e, millisSince(start));
		}
	}

	private static long millisSince(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000L;
	}
}
