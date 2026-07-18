package io.metaloom.loom.server.grpc.impl;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.protobuf.Descriptors.ServiceDescriptor;

import io.metaloom.loom.proto.health.HealthCheckRequest;
import io.metaloom.loom.proto.health.HealthCheckResponse;
import io.metaloom.loom.proto.health.HealthCheckResponse.ServingStatus;
import io.metaloom.loom.proto.health.HealthProto;
import io.metaloom.loom.server.grpc.GrpcHandlers;
import io.metaloom.loom.server.grpc.GrpcServiceException;
import io.metaloom.loom.server.grpc.LoomGrpcService;
import io.vertx.core.Future;
import io.vertx.grpc.common.GrpcStatus;
import io.vertx.grpc.common.ServiceMethod;
import io.vertx.grpc.server.GrpcServer;
import io.vertx.grpc.server.GrpcServerRequest;
import io.vertx.grpc.server.GrpcServerResponse;

/**
 * Implements the standard {@code grpc.health.v1.Health} service.
 *
 * <p>
 * The empty service name refers to the server as a whole. Individual services can be registered via
 * {@link #setStatus(String, ServingStatus)} - a lookup of an unregistered name answers {@code NOT_FOUND} as required by
 * the health checking protocol.
 *
 * <p>
 * Health checks are deliberately unauthenticated so that load balancers and orchestrators can probe the server without
 * holding a token.
 */
@Singleton
public class GrpcHealthService implements LoomGrpcService {

	/** The empty service name is the well known key for the server as a whole. */
	public static final String SERVER = "";

	private final Map<String, ServingStatus> statuses = new ConcurrentHashMap<>();

	/** Open Watch streams per service name. */
	private final Map<String, Set<GrpcServerResponse<HealthCheckRequest, HealthCheckResponse>>> watchers = new ConcurrentHashMap<>();

	@Inject
	public GrpcHealthService() {
		statuses.put(SERVER, ServingStatus.SERVING);
	}

	@Override
	public ServiceDescriptor descriptor() {
		return HealthProto.getDescriptor().findServiceByName("Health");
	}

	@Override
	public void register(GrpcServer server) {
		ServiceMethod<HealthCheckRequest, HealthCheckResponse> check = method("Check", HealthCheckRequest.getDefaultInstance());
		ServiceMethod<HealthCheckRequest, HealthCheckResponse> watch = method("Watch", HealthCheckRequest.getDefaultInstance());

		server.callHandler(check, GrpcHandlers.anonymous(request -> Future.succeededFuture(check(request.getService()))));
		server.callHandler(watch, this::watch);
	}

	/**
	 * Update the serving status of a service and notify all open Watch streams.
	 *
	 * @param service
	 *            fully qualified service name, or {@link #SERVER} for the server as a whole
	 * @param status
	 */
	public void setStatus(String service, ServingStatus status) {
		statuses.put(service, status);
		Set<GrpcServerResponse<HealthCheckRequest, HealthCheckResponse>> responses = watchers.get(service);
		if (responses != null) {
			HealthCheckResponse message = response(status);
			for (GrpcServerResponse<HealthCheckRequest, HealthCheckResponse> response : responses) {
				response.write(message);
			}
		}
	}

	private HealthCheckResponse check(String service) {
		ServingStatus status = statuses.get(service);
		if (status == null) {
			// The protocol mandates NOT_FOUND for services the server does not know about
			throw new GrpcServiceException(GrpcStatus.NOT_FOUND, "Unknown service: " + service);
		}
		return response(status);
	}

	/**
	 * Server streaming Watch: emit the current status immediately, then every subsequent change.
	 */
	private void watch(GrpcServerRequest<HealthCheckRequest, HealthCheckResponse> request) {
		request.last().onSuccess(message -> {
			String service = message.getService();
			GrpcServerResponse<HealthCheckRequest, HealthCheckResponse> response = request.response();

			// An unknown service is reported as SERVICE_UNKNOWN on the stream rather than failing the call
			ServingStatus current = statuses.getOrDefault(service, ServingStatus.SERVICE_UNKNOWN);
			response.write(response(current));

			Set<GrpcServerResponse<HealthCheckRequest, HealthCheckResponse>> responses = watchers
				.computeIfAbsent(service, key -> ConcurrentHashMap.newKeySet());
			responses.add(response);

			// Drop the watcher as soon as the client goes away, otherwise the set grows unbounded
			request.connection().closeHandler(v -> responses.remove(response));
			response.exceptionHandler(err -> responses.remove(response));
		});
	}

	private HealthCheckResponse response(ServingStatus status) {
		return HealthCheckResponse.newBuilder().setStatus(status).build();
	}

}
