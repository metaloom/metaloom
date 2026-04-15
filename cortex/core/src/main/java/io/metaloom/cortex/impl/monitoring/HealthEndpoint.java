package io.metaloom.cortex.impl.monitoring;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cortex.impl.loom.LoomControlChannel;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;

@Singleton
public class HealthEndpoint {

	private final LoomControlChannel loomControlChannel;

	@Inject
	public HealthEndpoint(LoomControlChannel loomControlChannel) {
		this.loomControlChannel = loomControlChannel;
	}

	public void register(Router router) {
		registerHealthRoute(router, "/api/health");
		registerReadyRoute(router, "/api/ready");

		// Keep compatibility with existing probes while migrating to /api/* endpoints.
		registerHealthRoute(router, "/health");
		registerReadyRoute(router, "/ready");
	}

	private void registerHealthRoute(Router router, String path) {
		router.get(path).handler(ctx -> {
			JsonObject body = new JsonObject()
				.put("status", "up")
				.put("loom", loomControlChannel.healthStatus());
			ctx.response()
				.putHeader("Content-Type", "application/json")
				.end(body.encode());
		});
	}

	private void registerReadyRoute(Router router, String path) {
		router.get(path).handler(ctx -> {
			boolean ready = loomControlChannel.isReady();
			JsonObject body = new JsonObject()
				.put("status", ready ? "ready" : "not_ready")
				.put("loom", loomControlChannel.healthStatus());
			ctx.response()
				.setStatusCode(ready ? 200 : 503)
				.putHeader("Content-Type", "application/json")
				.end(body.encode());
		});
	}

}
