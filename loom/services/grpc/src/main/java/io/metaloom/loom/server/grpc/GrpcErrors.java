package io.metaloom.loom.server.grpc;

import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.grpc.common.GrpcStatus;
import io.vertx.grpc.server.GrpcServerResponse;

/**
 * Maps Java exceptions onto gRPC status codes and terminates responses with them.
 */
public final class GrpcErrors {

	private static final Logger log = LoggerFactory.getLogger(GrpcErrors.class);

	private GrpcErrors() {
	}

	/**
	 * Determine the gRPC status for the given error.
	 *
	 * @param err
	 * @return
	 */
	public static GrpcStatus statusOf(Throwable err) {
		if (err instanceof GrpcServiceException) {
			return ((GrpcServiceException) err).status();
		}
		if (err instanceof IllegalArgumentException) {
			return GrpcStatus.INVALID_ARGUMENT;
		}
		if (err instanceof NoSuchElementException) {
			return GrpcStatus.NOT_FOUND;
		}
		return GrpcStatus.INTERNAL;
	}

	/**
	 * Terminate the response with a status derived from the given error.
	 *
	 * @param response
	 * @param err
	 */
	public static void fail(GrpcServerResponse<?, ?> response, Throwable err) {
		GrpcStatus status = statusOf(err);
		if (status == GrpcStatus.INTERNAL) {
			log.error("Unhandled error while processing gRPC call", err);
		} else if (log.isDebugEnabled()) {
			log.debug("Failing gRPC call with status {}", status, err);
		}
		response.status(status)
			.statusMessage(statusMessage(err))
			.end();
	}

	/**
	 * Build a status message which is safe to place in the grpc-message trailer. Internal errors are not detailed to the
	 * client to avoid leaking implementation details.
	 *
	 * @param err
	 * @return
	 */
	private static String statusMessage(Throwable err) {
		if (statusOf(err) == GrpcStatus.INTERNAL) {
			return "Internal error";
		}
		String message = err.getMessage();
		if (message == null) {
			return err.getClass().getSimpleName();
		}
		// Trailers are header values - strip anything that could break the framing
		return message.replaceAll("[\\r\\n]", " ");
	}

}
