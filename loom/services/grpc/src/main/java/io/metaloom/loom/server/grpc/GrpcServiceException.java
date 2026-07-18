package io.metaloom.loom.server.grpc;

import io.vertx.grpc.common.GrpcStatus;

/**
 * Exception which carries an explicit {@link GrpcStatus}. Service implementations throw or fail their future with this
 * exception whenever they want to control the status code that is returned to the client. Any other exception is mapped
 * by {@link GrpcErrors#statusOf(Throwable)}.
 */
public class GrpcServiceException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final GrpcStatus status;

	public GrpcServiceException(GrpcStatus status, String message) {
		super(message);
		this.status = status;
	}

	public GrpcServiceException(GrpcStatus status, String message, Throwable cause) {
		super(message, cause);
		this.status = status;
	}

	public GrpcStatus status() {
		return status;
	}

}
