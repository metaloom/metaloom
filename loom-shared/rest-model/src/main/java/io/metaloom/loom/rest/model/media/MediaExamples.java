package io.metaloom.loom.rest.model.media;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface MediaExamples extends ExampleValues {

	default Example mediaTokenResponseExample() {
		return new ExampleImpl(mediaTokenResponse(), "The media token response", HttpResponseStatus.OK);
	}

	default MediaTokenResponse mediaTokenResponse() {
		// Deliberately not a real-looking JWT: an example credential that reads like one invites
		// somebody to try it.
		return new MediaTokenResponse().setToken("<signed media token>").setExpiresIn(600);
	}
}
