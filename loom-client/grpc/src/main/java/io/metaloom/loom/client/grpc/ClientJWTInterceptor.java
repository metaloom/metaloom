package io.metaloom.loom.client.grpc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.net.HttpHeaders;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

public class ClientJWTInterceptor implements ClientInterceptor {

	private static final Logger log = LoggerFactory.getLogger(ClientJWTInterceptor.class);

	static final Metadata.Key<String> CUSTOM_HEADER_KEY = Metadata.Key.of(HttpHeaders.AUTHORIZATION, Metadata.ASCII_STRING_MARSHALLER);

	private ClientSettings client;

	public ClientJWTInterceptor(ClientSettings client) {
		this.client = client;
	}

	@Override
	public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
		return new SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {

			@Override
			public void start(Listener<RespT> responseListener, Metadata headers) {
				/* put custom header */
				headers.put(CUSTOM_HEADER_KEY, "Bearer " + client.token());
				super.start(new SimpleForwardingClientCallListener<RespT>(responseListener) {
					@Override
					public void onHeaders(Metadata headers) {
						/**
						 * if you don't need receive header from server, you can use {@link io.grpc.stub.MetadataUtils#attachHeaders} directly to send header
						 */
						log.info("header received from server:" + headers);
						super.onHeaders(headers);
					}
				}, headers);
			}
		};
	}

}
