package io.metaloom.loom.server.grpc;

import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.auth.LoomAuthenticationHandler;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.ext.auth.User;
import io.vertx.grpc.common.GrpcStatus;
import io.vertx.grpc.server.GrpcServerRequest;

/**
 * Authenticates gRPC calls using the bearer token found in the {@code authorization} call metadata.
 *
 * <p>
 * The REST transport also accepts the token via an HttpOnly cookie, which only makes sense for browser clients. gRPC
 * clients always send call metadata, so the {@code authorization} entry is the single supported location here.
 */
@Singleton
public class GrpcAuthenticator {

	private static final Logger log = LoggerFactory.getLogger(GrpcAuthenticator.class);

	/** gRPC metadata keys are always lower case on the wire. */
	private static final String AUTHORIZATION = "authorization";

	private static final Pattern BEARER = Pattern.compile("^Bearer$", Pattern.CASE_INSENSITIVE);

	private final LoomAuthenticationHandler authHandler;

	@Inject
	public GrpcAuthenticator(LoomAuthenticationHandler authHandler) {
		this.authHandler = authHandler;
	}

	/**
	 * Authenticate the given call.
	 *
	 * @param request
	 * @return future which succeeds with the authenticated user or fails with an {@link GrpcStatus#UNAUTHENTICATED}
	 *         {@link GrpcServiceException}
	 */
	public Future<User> authenticate(GrpcServerRequest<?, ?> request) {
		String token = extractToken(request.headers());
		if (token == null) {
			return Future.failedFuture(unauthenticated("Missing or malformed authorization metadata"));
		}
		return authHandler.authenticateToken(token)
			.recover(err -> {
				if (log.isDebugEnabled()) {
					log.debug("gRPC JWT authentication failed for {}", request.fullMethodName(), err);
				}
				return Future.failedFuture(unauthenticated("Invalid or expired token"));
			});
	}

	private String extractToken(MultiMap headers) {
		String authorization = headers.get(AUTHORIZATION);
		if (authorization == null) {
			return null;
		}
		String[] parts = authorization.split(" ");
		if (parts.length == 2 && BEARER.matcher(parts[0]).matches()) {
			return parts[1];
		}
		log.warn("Malformed authorization metadata. Expected format: Bearer [token]");
		return null;
	}

	private GrpcServiceException unauthenticated(String message) {
		return new GrpcServiceException(GrpcStatus.UNAUTHENTICATED, message);
	}

}
