package io.metaloom.loom.agent.memory.rest;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.DELETE;
import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.POST;
import static io.vertx.core.http.HttpMethod.PUT;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;

/**
 * Browse and edit the agent memory bank from the Loom UI.
 *
 * <ul>
 * <li>{@code GET /memory/scopes} — the caller's scopes with usage and quota</li>
 * <li>{@code GET /memory?scope=&ref=&prefix=} — list notes</li>
 * <li>{@code GET /memory/entry?scope=&ref=&id=} — read one note</li>
 * <li>{@code POST|PUT /memory/entry?scope=&ref=&id=} — create / upsert a note</li>
 * <li>{@code DELETE /memory/entry?scope=&ref=&id=} — delete a note</li>
 * </ul>
 *
 * <p>The note id is a nested path and is passed as the {@code id} query parameter rather than in the route, following the {@code /sessions/:uuid/files?path=}
 * precedent — arbitrary depth without server-side wildcard routing.</p>
 */
public class MemoryEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(MemoryEndpoint.class);

	private final MemoryEndpointService service;

	@Inject
	public MemoryEndpoint(MemoryEndpointService service, EndpointDependencies deps) {
		super(deps);
		this.service = service;
	}

	@Override
	public String name() {
		return "memory";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/memory";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		// Secure our own subtree independently of endpoint registration order.
		secure(basePath());
		secure(basePath() + "/scopes");
		secure(basePath() + "/entry");

		addRoute(basePath() + "/scopes", GET,
			"List the memory scopes available to the current user, with usage and quota",
			service::listScopes);

		addRoute(basePath(), GET,
			"List the notes of a memory scope",
			service::list);

		addRoute(basePath() + "/entry", GET,
			"Read one memory note",
			service::loadEntry);

		addRoute(basePath() + "/entry", POST,
			"Create a memory note",
			service::createEntry);

		addRoute(basePath() + "/entry", PUT,
			"Create or update a memory note",
			service::updateEntry);

		addRoute(basePath() + "/entry", DELETE,
			"Delete a memory note",
			service::deleteEntry);
	}

}
