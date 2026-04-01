package io.metaloom.loom.rest;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.rest.model.message.GenericMessageResponse;
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
		} else {
			log.error("Request failed server error in path {}", rc.normalizedPath(), rc.failure());
			GenericMessageResponse errorResponse = new GenericMessageResponse();
			errorResponse.setMessage("Internal Server Error");
			rc.response().setStatusCode(500).end(Json.encodeToBuffer(errorResponse));
		}
	}

}
