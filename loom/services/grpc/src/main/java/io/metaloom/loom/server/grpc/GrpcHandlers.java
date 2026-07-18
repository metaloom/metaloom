package io.metaloom.loom.server.grpc;

import java.util.function.BiFunction;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.ext.auth.User;
import io.vertx.grpc.server.GrpcServerRequest;

/**
 * Factory methods for gRPC call handlers which take care of authentication and error mapping so that service
 * implementations only deal with the request and response messages.
 */
public final class GrpcHandlers {

	private GrpcHandlers() {
	}

	/**
	 * Build a handler for a unary (single request, single response) call which requires an authenticated user.
	 *
	 * @param authenticator
	 * @param impl
	 *            receives the authenticated user and the request message
	 * @return
	 */
	public static <Req, Resp> Handler<GrpcServerRequest<Req, Resp>> authenticated(GrpcAuthenticator authenticator,
		BiFunction<User, Req, Future<Resp>> impl) {
		return request -> authenticator.authenticate(request)
			.compose(user -> request.last().compose(message -> invoke(impl, user, message)))
			.onSuccess(response -> request.response().end(response))
			.onFailure(err -> GrpcErrors.fail(request.response(), err));
	}

	/**
	 * Build a handler for a unary call which does not require authentication. Used for the well known health and
	 * reflection services.
	 *
	 * @param impl
	 * @return
	 */
	public static <Req, Resp> Handler<GrpcServerRequest<Req, Resp>> anonymous(java.util.function.Function<Req, Future<Resp>> impl) {
		return request -> request.last()
			.compose(message -> invoke((user, msg) -> impl.apply(msg), null, message))
			.onSuccess(response -> request.response().end(response))
			.onFailure(err -> GrpcErrors.fail(request.response(), err));
	}

	/**
	 * Invoke the implementation, turning a thrown exception into a failed future so that both failure modes are mapped
	 * onto a gRPC status the same way.
	 */
	private static <Req, Resp> Future<Resp> invoke(BiFunction<User, Req, Future<Resp>> impl, User user, Req message) {
		try {
			return impl.apply(user, message);
		} catch (Exception e) {
			return Future.failedFuture(e);
		}
	}

}
