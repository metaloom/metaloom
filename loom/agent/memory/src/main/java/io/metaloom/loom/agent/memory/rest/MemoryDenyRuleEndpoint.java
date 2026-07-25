package io.metaloom.loom.agent.memory.rest;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.DELETE;
import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.POST;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;

/**
 * Admin CRUD for the memory denylist ({@code /api/v1/memory-deny-rules}).
 *
 * <p>Registered as its own endpoint rather than as a sub-route of {@code /memory} because the two have different audiences and different permissions:
 * notes are user data, the denylist is instance policy.</p>
 */
public class MemoryDenyRuleEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(MemoryDenyRuleEndpoint.class);

	private final MemoryDenyRuleEndpointService service;

	@Inject
	public MemoryDenyRuleEndpoint(MemoryDenyRuleEndpointService service, EndpointDependencies deps) {
		super(deps);
		this.service = service;
	}

	@Override
	public String name() {
		return "memory-deny-rule";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/memory-deny-rules";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath() + "*");

		addRoute(basePath(), GET,
			"Load a paged list of memory deny rules",
			service::list);

		addRoute(basePath(), POST,
			"Create a new memory deny rule",
			service::create);

		addRoute(basePath() + "/:uuid", GET,
			"Load a memory deny rule",
			lrc -> service.load(lrc, lrc.pathParamUUID("uuid")));

		addRoute(basePath() + "/:uuid", POST,
			"Update a memory deny rule",
			lrc -> service.update(lrc, lrc.pathParamUUID("uuid")));

		addRoute(basePath() + "/:uuid", DELETE,
			"Delete a memory deny rule",
			lrc -> service.delete(lrc, lrc.pathParamUUID("uuid")));
	}

}
