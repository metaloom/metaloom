package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.READ_DB_INTEGRITY;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.integrity.DbIntegrityCategory;
import io.metaloom.loom.db.integrity.DbIntegrityCheckInfo;
import io.metaloom.loom.db.integrity.DbIntegrityCheckResult;
import io.metaloom.loom.db.integrity.DbIntegrityFinding;
import io.metaloom.loom.db.integrity.DbIntegrityReport;
import io.metaloom.loom.db.integrity.DbIntegrityScope;
import io.metaloom.loom.db.integrity.DbIntegrityService;
import io.metaloom.loom.db.integrity.DbIntegritySeverity;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.dbintegrity.DbIntegrityCheckListResponse;
import io.metaloom.loom.rest.model.dbintegrity.DbIntegrityCheckModel;
import io.metaloom.loom.rest.model.dbintegrity.DbIntegrityCheckResultModel;
import io.metaloom.loom.rest.model.dbintegrity.DbIntegrityReportResponse;
import io.metaloom.loom.rest.service.AbstractEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.vertx.core.Vertx;

/**
 * Serves {@code GET /api/v1/db-integrity} and {@code /db-integrity/checks}.
 *
 * <p>
 * The report is computed per request. There is no stored result and no job to poll: the checks are
 * aggregate queries, the whole sweep is milliseconds on any database an operator is looking at
 * interactively, and a cached report would answer a question about a database that no longer exists.
 * </p>
 *
 * <p>
 * jOOQ is blocking, so the sweep runs on a worker via {@link Vertx#executeBlocking} rather than on
 * the event loop - the same shape {@code RESTInfoEndpoint} uses for its one query.
 * </p>
 *
 * <p>
 * Not a CRUD resource, so this extends {@link AbstractEndpointService}: there is no element, no
 * uuid, and nothing to write.
 * </p>
 */
@Singleton
public class DbIntegrityEndpointService extends AbstractEndpointService {

	private static final Logger log = LoggerFactory.getLogger(DbIntegrityEndpointService.class);

	private final DbIntegrityService integrity;
	private final Vertx vertx;

	@Inject
	public DbIntegrityEndpointService(DbIntegrityService integrity, Vertx vertx, LoomModelBuilder modelBuilder,
		LoomModelValidator validator) {
		super(modelBuilder, validator);
		this.integrity = integrity;
		this.vertx = vertx;
	}

	/**
	 * {@code GET /api/v1/db-integrity} - run the checks and report what they found.
	 *
	 * <p>
	 * Filters: {@code ?check=} one or more codes, {@code ?category=} one or more categories,
	 * {@code ?severity=} a minimum, {@code ?limit=} how many offending rows to name per finding. An
	 * unknown code or category is a 400 rather than an empty result - a caller who has mistyped a
	 * filter should not be told the database is clean.
	 * </p>
	 */
	public void loadReport(LoomRoutingContext lrc) {
		checkPerm(lrc, READ_DB_INTEGRITY, () -> {
			DbIntegrityScope scope = scopeFrom(lrc);
			vertx.<DbIntegrityReport>executeBlocking(() -> integrity.check(scope))
				.onComplete(ar -> {
					if (ar.succeeded()) {
						lrc.send(toResponse(ar.result()));
					} else {
						// A single check that throws is caught inside the service and reported as a
						// failed result, so reaching here means the sweep itself could not run at all.
						log.error("Error while running the database integrity checks", ar.cause());
						lrc.error("Failed to run the database integrity checks");
					}
				});
		});
	}

	/**
	 * {@code GET /api/v1/db-integrity/checks} - the catalogue, with nothing run.
	 *
	 * <p>
	 * No {@code executeBlocking}: the catalogue is a static list and touches no database.
	 * </p>
	 */
	public void loadChecks(LoomRoutingContext lrc) {
		checkPerm(lrc, READ_DB_INTEGRITY, () -> {
			DbIntegrityCheckListResponse response = new DbIntegrityCheckListResponse();
			integrity.catalog().forEach(info -> response.add(toModel(info)));
			lrc.send(response);
		});
	}

	private DbIntegrityScope scopeFrom(LoomRoutingContext lrc) {
		DbIntegrityScope scope = DbIntegrityScope.all();

		Set<String> codes = values(lrc, "check");
		if (!codes.isEmpty()) {
			Set<String> known = new LinkedHashSet<>();
			integrity.catalog().forEach(info -> known.add(info.code()));
			for (String code : codes) {
				if (!known.contains(code)) {
					throw new LoomRestException(400, LoomRestErrorCode.BAD_QUERY_PARAMS,
						"Unknown integrity check '" + code + "'. See GET " + "/api/v1/db-integrity/checks"
							+ " for the catalogue.");
				}
			}
			scope = scope.withCodes(codes);
		}

		Set<String> categories = values(lrc, "category");
		if (!categories.isEmpty()) {
			Set<DbIntegrityCategory> parsed = new LinkedHashSet<>();
			for (String category : categories) {
				parsed.add(parseEnum(DbIntegrityCategory.class, category, "category"));
			}
			scope = scope.withCategories(parsed);
		}

		Set<String> severities = values(lrc, "severity");
		if (!severities.isEmpty()) {
			scope = scope.withMinSeverity(
				parseEnum(DbIntegritySeverity.class, severities.iterator().next(), "severity"));
		}

		List<String> limit = lrc.queryParam("limit");
		if (limit != null && !limit.isEmpty()) {
			scope = scope.withSampleLimit(parseLimit(limit.get(0)));
		}
		return scope;
	}

	/** Query parameter values, uppercased and de-duplicated; blanks dropped. */
	private Set<String> values(LoomRoutingContext lrc, String name) {
		List<String> raw = lrc.queryParam(name);
		Set<String> values = new LinkedHashSet<>();
		if (raw != null) {
			for (String value : raw) {
				// Comma-separated is the shape a browser query string naturally takes.
				for (String part : value.split(",")) {
					String trimmed = part.trim().toUpperCase(Locale.ROOT);
					if (!trimmed.isEmpty()) {
						values.add(trimmed);
					}
				}
			}
		}
		return values;
	}

	private <E extends Enum<E>> E parseEnum(Class<E> type, String value, String parameter) {
		try {
			return Enum.valueOf(type, value);
		} catch (IllegalArgumentException e) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_QUERY_PARAMS,
				"Unknown " + parameter + " '" + value + "'. Expected one of "
					+ String.join(", ", java.util.Arrays.stream(type.getEnumConstants()).map(Enum::name).toList())
					+ ".");
		}
	}

	private int parseLimit(String value) {
		try {
			int limit = Integer.parseInt(value.trim());
			if (limit < 0) {
				throw new NumberFormatException(value);
			}
			// DbIntegrityScope clamps to its own ceiling, so an over-large value narrows silently
			// rather than letting a query string ask for a whole table.
			return limit;
		} catch (NumberFormatException e) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_QUERY_PARAMS,
				"The limit parameter must be a non-negative integer, was '" + value + "'.");
		}
	}

	private DbIntegrityReportResponse toResponse(DbIntegrityReport report) {
		DbIntegrityReportResponse response = new DbIntegrityReportResponse()
			.setTimestamp(report.startedAt().toString())
			.setDurationMs(report.durationMs())
			.setChecksRun(report.results().size())
			.setFindingCount(report.findingCount())
			.setClean(report.isClean());
		report.results().forEach(result -> response.add(toModel(result)));
		return response;
	}

	private DbIntegrityCheckResultModel toModel(DbIntegrityCheckResult result) {
		return new DbIntegrityCheckResultModel()
			.setCheck(toModel(result.check()))
			.setCount(result.count())
			.setSamples(result.samples().stream().map(DbIntegrityFinding::toString).toList())
			.setDurationMs(result.durationMs())
			.setError(result.error());
	}

	private DbIntegrityCheckModel toModel(DbIntegrityCheckInfo info) {
		return new DbIntegrityCheckModel()
			.setCode(info.code())
			.setCategory(info.category().name())
			.setSeverity(info.severity().name())
			.setTable(info.table())
			.setColumn(info.column())
			.setDescription(info.description());
	}
}
