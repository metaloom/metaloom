package io.metaloom.loom.client.grpc.method;

import java.util.function.Supplier;

import com.google.common.util.concurrent.ListenableFuture;

import io.metaloom.loom.client.grpc.ClientSettings;
import io.metaloom.loom.client.util.LoomClientUtil;
import io.reactivex.rxjava3.core.Maybe;

public class LoomClientRequest<T> {

	private final Supplier<T> blocking;
	private final Supplier<ListenableFuture<T>> async;
	private final ClientSettings settings;

	public LoomClientRequest(ClientSettings settings, Supplier<T> blockingSupplier, Supplier<ListenableFuture<T>> asyncSupplier) {
		this.settings = settings;
		this.blocking = blockingSupplier;
		this.async = asyncSupplier;
	}

	/**
	 * Execute the request synchronously / blocking.
	 * 
	 * @return
	 */
	public T sync() {
		return blocking.get();
	}

	/**
	 * Execute the request asynchronously.
	 * 
	 * @return
	 */
	public ListenableFuture<T> async() {
		return async.get();
	}

	/**
	 * Execute the the request asynchronously via RxJava.
	 * 
	 * @return
	 */
	public Maybe<T> rx() {
		return LoomClientUtil.toMaybe(async(), settings);
	}
}
