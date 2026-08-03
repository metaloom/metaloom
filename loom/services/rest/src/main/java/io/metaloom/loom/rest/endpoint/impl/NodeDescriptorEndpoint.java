package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.GET;

import java.util.ArrayList;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.model.nodes.NodeAvailability;
import io.metaloom.loom.rest.model.nodes.NodeDescriptorsResponse;
import io.metaloom.loom.rest.service.impl.NodeAvailabilityService;
import io.vertx.core.Future;
import io.vertx.core.json.Json;

/**
 * Serves pipeline node contracts, the content-type vocabulary, and the fleet state beside them.
 *
 * <p>
 * The editor builds its palette, its edit forms and its connector validation from the main call, and
 * it makes that call <strong>before any token is in hand</strong> — so contracts and the plain
 * availability flags are served unauthenticated.
 *
 * <p>
 * {@code providedBy} names workers, which is fleet topology, so it is served only from the secured
 * {@code /availability} route. That route split is not cosmetic: an unsecured route never resolves a
 * caller, so a permission check inside the main response would deny everyone — including an
 * administrator who holds the permission. The editor is built to read sensibly without worker names
 * and to enrich once it has a token.
 * </p>
 */
@Singleton
public class NodeDescriptorEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(NodeDescriptorEndpoint.class);

	private final NodeDescriptorRegistry registry;

	private final NodeAvailabilityService availabilityService;

	@Inject
	public NodeDescriptorEndpoint(NodeDescriptorRegistry registry, NodeAvailabilityService availabilityService,
		EndpointDependencies deps) {
		super(deps);
		this.registry = registry;
		this.availabilityService = availabilityService;
	}

	@Override
	public String name() {
		return "node-descriptors";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/pipeline/node-descriptors";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		// The presence route is the authenticated one - see mayNameWorkers below for why the split
		// falls here rather than inside the main response.
		secure(basePath() + "/availability");

		// Contracts + vocabulary + fleet state, in one call - the editor's single source, and loaded
		// before anyone has logged in, so it is deliberately unauthenticated and never names a worker.
		addRoute(basePath(), GET, "Load all pipeline node descriptors", lrc -> {
			NodeDescriptorsResponse response = new NodeDescriptorsResponse()
				.setNodeDescriptors(new ArrayList<>(registry.getAll()))
				.setContentTypes(registry.contentTypes())
				.setAvailability(availabilityService.availability(false));
			lrc.sendText(Json.encode(response), "application/json", 200);
		});

		/*
		 * Presence changes far more often than contracts do - every worker connect, disconnect,
		 * restart and scale event - and the full response is ~115 KB for 34 nodes. Re-downloading all
		 * of it in every open browser tab to learn that one boolean flipped is the obvious thing to
		 * build and the wrong one, so presence has its own route.
		 *
		 * It is also where `providedBy` lives, because this route is authenticated and the one above
		 * cannot be.
		 */
		addRoute(basePath() + "/availability", GET, "Load node availability without the contracts",
			lrc -> mayNameWorkers(lrc)
				.onSuccess(includeProviders -> {
					Map<String, NodeAvailability> availability = availabilityService.availability(includeProviders);
					lrc.sendText(Json.encode(availability), "application/json", 200);
				}));

		// Load a single node descriptor by node id.
		addRoute(basePath() + "/:nodeId", GET, "Load a pipeline node descriptor by node id", lrc -> {
			String nodeId = lrc.pathParam("nodeId");
			var descriptor = registry.get(nodeId);
			if (descriptor == null) {
				lrc.sendText("{\"message\":\"Node descriptor not found: " + nodeId + "\"}", "application/json", 404);
				return;
			}
			lrc.sendText(Json.encode(descriptor), "application/json", 200);
		});

		// The vocabulary on its own, including types synthesized from announced ports.
		addRoute(API_V1_PATH + "/pipeline/content-types", GET, "Load all pipeline content types", lrc -> {
			lrc.sendText(Json.encode(registry.contentTypes()), "application/json", 200);
		});
	}

	/**
	 * Whether this caller may be told which workers offer a node.
	 *
	 * <p>
	 * 🔴 Only meaningful on a <strong>secured</strong> route. An unsecured route never runs the auth
	 * handler, so there is no resolvable caller and this would answer {@code false} for everyone,
	 * including an administrator holding the permission — a gate that is always shut is not a gate.
	 * That is exactly why {@code providedBy} lives on {@code /availability} and not in the main
	 * descriptor response: the main one has to stay loadable before login, so it can never name a
	 * worker at all.
	 * </p>
	 *
	 * <p>
	 * Never fails the request either way: a caller without the permission simply gets availability
	 * without the worker names, and the editor is built to say something useful without them.
	 * </p>
	 */
	private Future<Boolean> mayNameWorkers(LoomRoutingContext lrc) {
		try {
			return lrc.permissions()
				.map(permitted -> permitted.test(Permission.READ_CORTEX_INSTANCE))
				.otherwise(false);
		} catch (RuntimeException e) {
			return Future.succeededFuture(false);
		}
	}
}
