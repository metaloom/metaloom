package io.metaloom.loom.rest.model.example.impl;

import io.metaloom.loom.rest.model.RestModel;
import io.metaloom.loom.rest.model.example.Example;
import io.netty.handler.codec.http.HttpResponseStatus;

public class ExampleImpl implements Example {

	private RestModel body;
	private String description;
	private HttpResponseStatus code;

	public ExampleImpl(RestModel body, String description, HttpResponseStatus code) {
		this.body = body;
		this.description = description;
		this.code = code;
	}

	@Override
	public RestModel body() {
		return body;
	}

	@Override
	public String description() {
		return description;
	}

	@Override
	public HttpResponseStatus code() {
		return code;
	}
}
