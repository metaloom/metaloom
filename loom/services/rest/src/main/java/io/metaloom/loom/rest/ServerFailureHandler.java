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
		// Read once, up front: every branch below logs it and every branch below returns it, and that pairing is
		// the whole point - it is what lets a user quoting a trace id from a failure report be matched to the
		// stack trace that produced it. See TraceIdHandler.
		String traceId = TraceIdHandler.traceIdOf(rc);

		if (rc.response().headWritten()) {
			log.error("Request failed in path {} [trace {}] but response head was already sent. Cannot send error response.", rc.normalizedPath(),
				traceId, rc.failure());
			// Attempt to close the response if not yet ended
			if (!rc.response().ended()) {
				rc.response().end();
			}
			return;
		}
		if (rc.failure() instanceof ValidationException ve) {
			log.error("Request failed with validation error in path {} [trace {}]", rc.normalizedPath(), traceId, rc.failure());
			fail(rc, 400, ve.getMessage(), traceId);
		} else if (rc.failure() instanceof LoomRestException lre) {
			log.error("Request failed with REST error in path {} [trace {}]", rc.normalizedPath(), traceId, rc.failure());
			fail(rc, lre.httpCode(), lre.getMessage(), traceId);
		} else if (isUniqueViolation(rc.failure())) {
			// Creating something that already exists is the caller's situation to resolve, not a server
			// fault. Several tables carry a natural key - blacklist is unique per (asset, creator),
			// reaction per (asset, creator, type) - and without this branch every such duplicate came
			// back as a 500 that told the client nothing about what to do next.
			log.info("Request in path {} [trace {}] rejected as a duplicate", rc.normalizedPath(), traceId, rc.failure());
			fail(rc, 409, "The resource already exists.", traceId);
		} else if (rc.failure() instanceof BinaryStorageException bse) {
			// A storage backend refused or could not be reached - an unreachable bucket, absent credentials,
			// a full disk. Still a 500, because the deployment is at fault rather than the request, but the
			// message travels: BinaryStorageException is documented to name the backend and the locator
			// precisely because "upload failed" without either is unactionable when several pools exist, and
			// collapsing it into "Internal Server Error" threw away the only part that identified the pool.
			log.error("Request failed with storage error in path {} [trace {}]", rc.normalizedPath(), traceId, rc.failure());
			fail(rc, 500, bse.getMessage(), traceId);
		} else {
			log.error("Request failed server error in path {} [trace {}]", rc.normalizedPath(), traceId, rc.failure());
			// The message stays deliberately opaque - an unclassified failure may carry a SQL fragment or a
			// file path, and neither belongs in a browser. The trace id is what makes that survivable: it is
			// useless to an attacker and it is the exact key an operator needs to find the stack trace above.
			fail(rc, 500, "Internal Server Error", traceId);
		}
	}

	private static void fail(RoutingContext rc, int statusCode, String message, String traceId) {
		GenericMessageResponse errorResponse = new GenericMessageResponse()
			.setMessage(message)
			.setTraceId(traceId);
		rc.response().setStatusCode(statusCode).end(Json.encodeToBuffer(errorResponse));
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
