package io.metaloom.loom.rest;

import java.sql.SQLException;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.rest.model.message.GenericMessageResponse;
import io.metaloom.loom.storage.BinaryStorageException;
import io.metaloom.loom.rest.validation.ValidationException;
import io.vertx.core.Handler;
import io.vertx.core.json.Json;
import io.vertx.ext.web.RoutingContext;

@Singleton
public class ServerFailureHandler implements Handler<RoutingContext> {

	private static final Logger log = LoggerFactory.getLogger(ServerFailureHandler.class);

	@Inject
	public ServerFailureHandler() {
	}

	@Override
	public void handle(RoutingContext rc) {
		if (rc.response().headWritten()) {
			log.error("Request failed in path {} but response head was already sent. Cannot send error response.", rc.normalizedPath(), rc.failure());
			// Attempt to close the response if not yet ended
			if (!rc.response().ended()) {
				rc.response().end();
			}
			return;
		}
		if (rc.failure() instanceof ValidationException ve) {
			log.error("Request failed with validation error in path {}", rc.normalizedPath(), rc.failure());
			GenericMessageResponse errorResponse = new GenericMessageResponse();
			errorResponse.setMessage(ve.getMessage());
			rc.response().setStatusCode(400).end(Json.encodeToBuffer(errorResponse));
		} else if (rc.failure() instanceof LoomRestException lre) {
			log.error("Request failed with REST error in path {}", rc.normalizedPath(), rc.failure());
			GenericMessageResponse errorResponse = new GenericMessageResponse();
			errorResponse.setMessage(lre.getMessage());
			rc.response().setStatusCode(lre.httpCode()).end(Json.encodeToBuffer(errorResponse));
		} else if (isUniqueViolation(rc.failure())) {
			// Creating something that already exists is the caller's situation to resolve, not a server
			// fault. Several tables carry a natural key - blacklist is unique per (asset, creator),
			// reaction per (asset, creator, type) - and without this branch every such duplicate came
			// back as a 500 that told the client nothing about what to do next.
			log.info("Request in path {} rejected as a duplicate", rc.normalizedPath(), rc.failure());
			GenericMessageResponse errorResponse = new GenericMessageResponse();
			errorResponse.setMessage("The resource already exists.");
			rc.response().setStatusCode(409).end(Json.encodeToBuffer(errorResponse));
		} else if (rc.failure() instanceof BinaryStorageException bse) {
			// A storage backend refused or could not be reached - an unreachable bucket, absent credentials,
			// a full disk. Still a 500, because the deployment is at fault rather than the request, but the
			// message travels: BinaryStorageException is documented to name the backend and the locator
			// precisely because "upload failed" without either is unactionable when several pools exist, and
			// collapsing it into "Internal Server Error" threw away the only part that identified the pool.
			log.error("Request failed with storage error in path {}", rc.normalizedPath(), rc.failure());
			GenericMessageResponse errorResponse = new GenericMessageResponse();
			errorResponse.setMessage(bse.getMessage());
			rc.response().setStatusCode(500).end(Json.encodeToBuffer(errorResponse));
		} else {
			log.error("Request failed server error in path {}", rc.normalizedPath(), rc.failure());
			GenericMessageResponse errorResponse = new GenericMessageResponse();
			errorResponse.setMessage("Internal Server Error");
			rc.response().setStatusCode(500).end(Json.encodeToBuffer(errorResponse));
		}
	}

	/** SQLState {@code 23505}, unique_violation. Standard JDBC, so no driver or jOOQ type is needed here. */
	private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

	/**
	 * Whether the failure is - at any depth - a unique constraint violation. The DAO layer hands these up wrapped in a jOOQ
	 * {@code DataAccessException}, so the cause chain has to be walked rather than the top type inspected.
	 */
	private static boolean isUniqueViolation(Throwable failure) {
		Throwable seen = failure;
		for (int depth = 0; seen != null && depth < 20; depth++) {
			if (seen instanceof SQLException sql && SQLSTATE_UNIQUE_VIOLATION.equals(sql.getSQLState())) {
				return true;
			}
			seen = seen.getCause() == seen ? null : seen.getCause();
		}
		return false;
	}

}
