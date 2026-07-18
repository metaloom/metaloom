package io.metaloom.loom.server.grpc;

import com.google.protobuf.Descriptors.ServiceDescriptor;

import io.vertx.grpc.common.GrpcMessageDecoder;
import io.vertx.grpc.common.GrpcMessageEncoder;
import io.vertx.grpc.common.ServiceMethod;
import io.vertx.grpc.common.ServiceName;
import io.vertx.grpc.server.GrpcServer;

/**
 * A group of gRPC call handlers backed by one protobuf service definition. Implementations mirror the REST endpoint
 * pattern: one class per protobuf service.
 */
public interface LoomGrpcService {

	/**
	 * Protobuf descriptor of the implemented service. Used to advertise the service via server reflection.
	 *
	 * @return
	 */
	ServiceDescriptor descriptor();

	/**
	 * Register the call handlers of this service on the given server.
	 *
	 * @param server
	 */
	void register(GrpcServer server);

	/**
	 * Fully qualified name of the implemented service, e.g. {@code asset.AssetLoader}.
	 *
	 * @return
	 */
	default ServiceName name() {
		return ServiceName.create(descriptor().getFullName());
	}

	/**
	 * Build a server side {@link ServiceMethod} for a method of this service.
	 *
	 * @param methodName
	 *            protobuf method name, e.g. {@code Store}
	 * @param requestPrototype
	 *            default instance of the request message
	 * @return
	 */
	default <Req extends com.google.protobuf.Message, Resp extends com.google.protobuf.Message> ServiceMethod<Req, Resp> method(
		String methodName, Req requestPrototype) {
		return ServiceMethod.server(name(), methodName, GrpcMessageEncoder.encoder(), GrpcMessageDecoder.decoder(requestPrototype));
	}

}
