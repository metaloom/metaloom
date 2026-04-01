package io.metaloom.loom.rest.model.example;

import io.metaloom.loom.rest.model.RestModel;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface Example {

	RestModel body();

	String description();

	HttpResponseStatus code();

}
