package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.GET;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.model.ModelExamples;
import io.metaloom.loom.rest.service.impl.DbIntegrityEndpointService;

/**
 * The database integrity report.
 *
 * <p>
 * Answers "does the database still hold the invariants the code assumes" - dangling rows the schema
 * has no foreign key to prevent, audit timestamps that contradict each other, required names left
 * blank, varchar columns holding values their Java enum does not have, and CHECK constraints that
 * rows were written around.
 * </p>
 *
 * <p>
 * Singular path, deliberately. {@code CODING.md} reserves the singular for singleton and RPC-style
 * resources, and this is one report about one database rather than a collection of integrity
 * objects. {@code /checks} underneath it <em>is</em> a collection and is plural.
 * </p>
 *
 * <p>
 * No POST. The report reads and computes; it writes nothing and fixes nothing. A
 * {@code POST /run} would imply a job with a lifecycle to poll, and there is none - the caller
 * simply asks again.
 * </p>
 */
@Singleton
public class DbIntegrityEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(DbIntegrityEndpoint.class);

	private final DbIntegrityEndpointService service;

	private final ModelExamples examples;

	@Inject
	public DbIntegrityEndpoint(DbIntegrityEndpointService service, ModelExamples examples,
		EndpointDependencies deps) {
		super(deps);
		this.service = service;
		this.examples = examples;
	}

	@Override
	public String name() {
		return "db-integrity";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/db-integrity";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		// Two routes under one base, so secure the subtree rather than the exact path.
		secure(basePath() + "*");

		addRoute(basePath() + "/checks", GET,
			"List the registered database integrity checks without running any of them",
			null,
			examples.dbIntegrityCheckListExample(),
			lrc -> service.loadChecks(lrc));

		addRoute(basePath(), GET,
			"Run the database integrity checks and report the findings."
				+ " Filter with ?check=, ?category=, ?severity= and ?limit=",
			null,
			examples.dbIntegrityReportExample(),
			lrc -> service.loadReport(lrc));
	}
}
