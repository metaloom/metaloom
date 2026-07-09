package io.metaloom.loom.auth;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.RoutingContext;

public interface LoomAuthenticationHandler extends Handler<RoutingContext> {

	/**
	 * Validate a bearer token asynchronously. Used by transports that cannot
	 * carry an {@code Authorization} header, such as browser WebSocket
	 * handshakes that pass the token via a query parameter.
	 *
	 * @param token the JWT bearer token
	 * @return future that succeeds with the authenticated {@link User} or
	 *         fails when the token is invalid or expired
	 */
	Future<User> authenticateToken(String token);

}
