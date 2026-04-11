package io.metaloom.loom.client.http.impl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.metaloom.loom.client.common.LoomClientResponse;
import io.metaloom.loom.rest.model.RestResponseModel;

public class LoomClientResponseImpl<T extends RestResponseModel<T>> implements LoomClientResponse<T> {

	private final T body;
	private final int statusCode;
	private final String statusMessage;
	private final Map<String, List<String>> headers;

	public LoomClientResponseImpl(T body, int statusCode, String statusMessage, Map<String, List<String>> headers) {
		this.body = body;
		this.statusCode = statusCode;
		this.statusMessage = statusMessage;
		this.headers = headers != null ? Collections.unmodifiableMap(new LinkedHashMap<>(headers)) : Collections.emptyMap();
	}

	@Override
	public T body() {
		return body;
	}

	@Override
	public int statusCode() {
		return statusCode;
	}

	@Override
	public String statusMessage() {
		return statusMessage;
	}

	@Override
	public Map<String, List<String>> headers() {
		return headers;
	}

	@Override
	public String header(String name) {
		List<String> values = headers(name);
		return values.isEmpty() ? null : values.get(0);
	}

	@Override
	public List<String> headers(String name) {
		// OkHttp headers are case-insensitive; do a case-insensitive lookup
		for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(name)) {
				return entry.getValue();
			}
		}
		return Collections.emptyList();
	}

}
