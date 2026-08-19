package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.DELETE;
import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.POST;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.model.ModelExamples;
import io.metaloom.loom.rest.service.impl.FailureReportEndpointService;

/**
 * Problem reports submitted from the UI, at {@code /api/v1/failure-reports}.
 *
 * <p>
 * The create route takes no permission - only authentication. See {@link FailureReportEndpointService} for why, and V2.106 for the schema-side note.
 * </p>
 */
public class FailureReportEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(FailureReportEndpoint.class);

	private final FailureReportEndpointService service;
	private final ModelExamples examples;

	@Inject
	public FailureReportEndpoint(FailureReportEndpointService service, EndpointDependencies deps, ModelExamples examples) {
		super(deps);
		this.service = service;
		this.examples = examples;
	}

	@Override
	public String name() {
		return "failure-report";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/failure-reports";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath() + "*");

		addRoute(basePath(), POST,
			"Submit a problem report. Requires authentication and no permission, so that a user can always report a failure",
			examples.failureReportCreateRequestExample(),
			examples.failureReportResponseExample(),
			lrc -> {
				service.create(lrc);
			});

		addRoute(basePath() + "/:uuid", POST,
			"Move a problem report through triage",
			examples.failureReportUpdateRequestExample(),
			examples.failureReportResponseExample(),
			lrc -> {
				service.update(lrc, lrc.pathParamUUID("uuid"));
			});

		addRoute(basePath() + "/:uuid", DELETE,
			"Delete a problem report and its screenshot",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				service.delete(lrc, lrc.pathParamUUID("uuid"));
			});

		addListRoute(basePath(), GET,
			"Load a paged list of problem reports",
			examples.failureReportListResponseExample(),
			lrc -> {
				service.list(lrc);
			});

		// Registered before /:uuid so the literal segment is not swallowed by the wildcard - see RESTAPI.md section 9.
		addDownloadRoute(basePath() + "/:uuid/screenshot",
			"Download the screenshot attached to a problem report",
			lrc -> {
				service.loadScreenshot(lrc, lrc.pathParamUUID("uuid"));
			});

		addRoute(basePath() + "/:uuid", GET,
			"Load a problem report",
			null,
			examples.failureReportResponseExample(),
			lrc -> {
				service.load(lrc, lrc.pathParamUUID("uuid"));
			});
	}
}
