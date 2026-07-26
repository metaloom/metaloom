package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.db.jooq.Tables.LOOM;
import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.GET;

import java.time.LocalDateTime;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.Record2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.LoomVersion;
import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.HTTPConstants;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.model.info.RESTInfoResponse;
import io.metaloom.loom.rest.openapi.LoomOpenAPI;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;

@Singleton
public class RESTInfoEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(RESTInfoEndpoint.class);

	private final DSLContext dslContext;
	private final Vertx vertx;

	@Inject
	public RESTInfoEndpoint(EndpointDependencies deps, DSLContext dslContext) {
		super(deps);
		this.dslContext = dslContext;
		this.vertx = deps.vertx;
	}

	@Override
	public String name() {
		return "info";
	}

	@Override
	public void register() {
		addRoute(basePath(), GET, "Load REST API info", lrc -> {
			// The loom singleton system row carries the applied DB schema revision and the
			// last-used timestamp. jOOQ access is blocking, so run it off the event loop.
			vertx.<RESTInfoResponse>executeBlocking(() -> {
				Record2<String, LocalDateTime> row = dslContext
					.select(LOOM.DB_REV, LOOM.LAST_USED_TIMESTAMP)
					.from(LOOM)
					.fetchAny();
				String dbRev = row == null ? null : row.value1();
				LocalDateTime lastUsed = row == null ? null : row.value2();
				return buildResponse(LoomVersion.getPlainVersion(), dbRev, lastUsed);
			}).onComplete(ar -> {
				if (ar.succeeded()) {
					lrc.send(ar.result());
				} else {
					log.error("Error while loading Loom instance info", ar.cause());
					lrc.error("Failed to load instance info");
				}
			});
		});

		// YAML is the default representation of the spec, `/openapi.json` serves the same
		// document as JSON - that is what the Swagger UI of the website consumes.
		addRoute(basePath() + "/openapi", GET, "Load the OpenAPI spec of this server (YAML)", lrc -> sendSpec(lrc, false));
		addRoute(basePath() + "/openapi.yaml", GET, "Load the OpenAPI spec of this server (YAML)", lrc -> sendSpec(lrc, false));
		addRoute(basePath() + "/openapi.json", GET, "Load the OpenAPI spec of this server (JSON)", lrc -> sendSpec(lrc, true));
	}

	private void sendSpec(LoomRoutingContext lrc, boolean json) {
		try {
			OpenAPI api = LoomOpenAPI.describe(apiRouter(), baseUrl(lrc));
			if (json) {
				lrc.sendText(Json.pretty(api), HTTPConstants.APPLICATION_JSON, 200);
			} else {
				lrc.sendText(Yaml.pretty(api), HTTPConstants.TEXT_YAML, 200);
			}
		} catch (Exception e) {
			log.error("Error while invoking API spec generator", e);
			throw new LoomRestException(500, LoomRestErrorCode.INTERNAL_ERROR, "Error while generating spec");
		}
	}

	/**
	 * Advertise the address the spec was actually fetched from so that "Try it out" in a spec viewer talks back to this server instead of a
	 * placeholder host.
	 *
	 * @param lrc
	 * @return
	 */
	private static String baseUrl(LoomRoutingContext lrc) {
		HttpServerRequest request = lrc.routingContext().request();
		String host = request.getHeader("X-Forwarded-Host");
		if (host == null) {
			host = request.authority() == null ? null : request.authority().toString();
		}
		if (host == null) {
			return LoomOpenAPI.DEFAULT_BASE_URL;
		}
		String scheme = request.getHeader("X-Forwarded-Proto");
		if (scheme == null) {
			scheme = request.isSSL() ? "https" : "http";
		}
		return scheme + "://" + host;
	}

	@Override
	public String basePath() {
		return API_V1_PATH;
	}

	/**
	 * Map the loom singleton row fields plus the server version into the info response model.
	 *
	 * @param version
	 *            running server version (never null)
	 * @param dbRevision
	 *            applied DB schema revision from the loom row, or null when no row exists
	 * @param lastUsed
	 *            last-used timestamp from the loom row, or null when no row exists
	 * @return populated info response
	 */
	static RESTInfoResponse buildResponse(String version, String dbRevision, LocalDateTime lastUsed) {
		return new RESTInfoResponse()
			.setVersion(version)
			.setDbRevision(dbRevision)
			.setLastUsed(lastUsed == null ? null : lastUsed.toString());
	}

}
