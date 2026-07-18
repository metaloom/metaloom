package io.metaloom.loom.server.grpc.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;

import io.metaloom.loom.proto.reflection.ErrorResponse;
import io.metaloom.loom.proto.reflection.FileDescriptorResponse;
import io.metaloom.loom.proto.reflection.ListServiceResponse;
import io.metaloom.loom.proto.reflection.ServerReflectionProto;
import io.metaloom.loom.proto.reflection.ServerReflectionRequest;
import io.metaloom.loom.proto.reflection.ServerReflectionResponse;
import io.metaloom.loom.proto.reflection.ServiceResponse;
import io.metaloom.loom.server.grpc.LoomGrpcService;
import io.vertx.grpc.common.GrpcMessageDecoder;
import io.vertx.grpc.common.GrpcMessageEncoder;
import io.vertx.grpc.common.GrpcStatus;
import io.vertx.grpc.common.ServiceMethod;
import io.vertx.grpc.common.ServiceName;
import io.vertx.grpc.server.GrpcServer;
import io.vertx.grpc.server.GrpcServerRequest;
import io.vertx.grpc.server.GrpcServerResponse;

/**
 * Implements the standard {@code grpc.reflection.v1.ServerReflection} service so that tooling such as {@code grpcurl}
 * can discover the exposed services without a local copy of the proto files.
 *
 * <p>
 * The same handler is additionally registered under the legacy {@code grpc.reflection.v1alpha} name. Both revisions of
 * the protocol are wire compatible - only the proto package differs - so older clients are served by the same
 * implementation.
 *
 * <p>
 * Reflection is unauthenticated: it only exposes the service schema, which is public information, and requiring a token
 * would defeat its purpose as a discovery mechanism.
 */
@Singleton
public class GrpcReflectionService implements LoomGrpcService {

	private static final String V1ALPHA_SERVICE = "grpc.reflection.v1alpha.ServerReflection";

	private static final String METHOD = "ServerReflectionInfo";

	/** Services advertised via reflection, keyed by fully qualified name. */
	private final Map<String, ServiceDescriptor> services = new LinkedHashMap<>();

	@Inject
	public GrpcReflectionService() {
	}

	@Override
	public ServiceDescriptor descriptor() {
		return ServerReflectionProto.getDescriptor().findServiceByName("ServerReflection");
	}

	/**
	 * Declare the services which reflection should advertise. Called by the {@code GrpcService} once all services are
	 * known, including this one.
	 *
	 * @param loomServices
	 */
	public void setServices(Collection<LoomGrpcService> loomServices) {
		services.clear();
		for (LoomGrpcService service : loomServices) {
			ServiceDescriptor descriptor = service.descriptor();
			services.put(descriptor.getFullName(), descriptor);
		}
	}

	@Override
	public void register(GrpcServer server) {
		server.callHandler(reflectionMethod(name()), this::handle);
		// Serve pre-v1 clients (e.g. older grpcurl) from the same implementation
		server.callHandler(reflectionMethod(ServiceName.create(V1ALPHA_SERVICE)), this::handle);
	}

	private ServiceMethod<ServerReflectionRequest, ServerReflectionResponse> reflectionMethod(ServiceName serviceName) {
		return ServiceMethod.server(serviceName, METHOD, GrpcMessageEncoder.encoder(),
			GrpcMessageDecoder.decoder(ServerReflectionRequest.getDefaultInstance()));
	}

	/**
	 * The reflection call is bidirectional streaming: answer every request message in order and close the response once
	 * the client half-closes.
	 */
	private void handle(GrpcServerRequest<ServerReflectionRequest, ServerReflectionResponse> request) {
		GrpcServerResponse<ServerReflectionRequest, ServerReflectionResponse> response = request.response();
		request.handler(message -> response.write(answer(message)));
		request.endHandler(v -> response.end());
	}

	private ServerReflectionResponse answer(ServerReflectionRequest request) {
		ServerReflectionResponse.Builder builder = ServerReflectionResponse.newBuilder()
			.setValidHost(request.getHost())
			.setOriginalRequest(request);

		switch (request.getMessageRequestCase()) {
		case LIST_SERVICES:
			return builder.setListServicesResponse(listServices()).build();
		case FILE_BY_FILENAME:
			return byFilename(builder, request.getFileByFilename());
		case FILE_CONTAINING_SYMBOL:
			return bySymbol(builder, request.getFileContainingSymbol());
		default:
			// Extension lookups are only meaningful for proto2 extensions, which loom does not use
			return builder.setErrorResponse(error(GrpcStatus.UNIMPLEMENTED,
				"Unsupported reflection request: " + request.getMessageRequestCase())).build();
		}
	}

	private ListServiceResponse listServices() {
		ListServiceResponse.Builder builder = ListServiceResponse.newBuilder();
		for (String name : services.keySet()) {
			builder.addService(ServiceResponse.newBuilder().setName(name));
		}
		return builder.build();
	}

	private ServerReflectionResponse byFilename(ServerReflectionResponse.Builder builder, String filename) {
		for (ServiceDescriptor service : services.values()) {
			FileDescriptor file = service.getFile();
			if (file.getName().equals(filename)) {
				return builder.setFileDescriptorResponse(descriptorsOf(file)).build();
			}
		}
		return builder.setErrorResponse(error(GrpcStatus.NOT_FOUND, "File not found: " + filename)).build();
	}

	/**
	 * Resolve the file which declares the given fully qualified symbol. Both services and messages are searched, since
	 * clients look up message types to decode responses.
	 */
	private ServerReflectionResponse bySymbol(ServerReflectionResponse.Builder builder, String symbol) {
		for (ServiceDescriptor service : services.values()) {
			FileDescriptor file = service.getFile();
			if (declares(file, symbol)) {
				return builder.setFileDescriptorResponse(descriptorsOf(file)).build();
			}
		}
		return builder.setErrorResponse(error(GrpcStatus.NOT_FOUND, "Symbol not found: " + symbol)).build();
	}

	private boolean declares(FileDescriptor file, String symbol) {
		for (ServiceDescriptor service : file.getServices()) {
			if (service.getFullName().equals(symbol)) {
				return true;
			}
			if (service.findMethodByName(methodOf(symbol, service.getFullName())) != null) {
				return true;
			}
		}
		for (Descriptor message : file.getMessageTypes()) {
			if (message.getFullName().equals(symbol)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Turn {@code pkg.Service.Method} into {@code Method} when the symbol belongs to the given service.
	 */
	private String methodOf(String symbol, String serviceFullName) {
		String prefix = serviceFullName + ".";
		return symbol.startsWith(prefix) ? symbol.substring(prefix.length()) : "";
	}

	/**
	 * Collect the descriptor of the file plus all files it depends on - clients need the full transitive closure to
	 * build a descriptor pool.
	 */
	private FileDescriptorResponse descriptorsOf(FileDescriptor file) {
		List<FileDescriptor> collected = new ArrayList<>();
		collect(file, collected);

		FileDescriptorResponse.Builder builder = FileDescriptorResponse.newBuilder();
		for (FileDescriptor descriptor : collected) {
			builder.addFileDescriptorProto(descriptor.toProto().toByteString());
		}
		return builder.build();
	}

	private void collect(FileDescriptor file, List<FileDescriptor> collected) {
		if (collected.contains(file)) {
			return;
		}
		collected.add(file);
		for (FileDescriptor dependency : file.getDependencies()) {
			collect(dependency, collected);
		}
	}

	private ErrorResponse error(GrpcStatus status, String message) {
		return ErrorResponse.newBuilder()
			.setErrorCode(status.code)
			.setErrorMessage(message)
			.build();
	}

}
