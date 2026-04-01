package io.metaloom.loom.client.grpc;

import java.util.function.Supplier;

import com.google.common.util.concurrent.ListenableFuture;

import io.grpc.ManagedChannel;
import io.metaloom.loom.client.common.CommonSettings;
import io.metaloom.loom.client.grpc.method.LoomClientRequest;

public interface ClientSettings extends CommonSettings {

	String token();

	/**
	 * Return the prepared gRPC channel.
	 * 
	 * @return
	 */
	ManagedChannel channel();

	default <T> LoomClientRequest<T> request(Supplier<T> blockingSupplier,
		Supplier<ListenableFuture<T>> asyncSupplier) {
		return new LoomClientRequest<>(this, blockingSupplier, asyncSupplier);
	}

}
