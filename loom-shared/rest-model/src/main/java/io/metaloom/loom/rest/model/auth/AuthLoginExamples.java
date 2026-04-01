package io.metaloom.loom.rest.model.auth;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface AuthLoginExamples extends ExampleValues {

	default Example authLoginRequestExample() {
		return new ExampleImpl(authLoginRequest(), "The authentication request.", HttpResponseStatus.OK);
	}

	default Example authLoginResponseExample() {
		return new ExampleImpl(authLoginResponse(), "The authentication request.", HttpResponseStatus.OK);
	}

	default AuthLoginRequest authLoginRequest() {
		AuthLoginRequest model = new AuthLoginRequest();
		model.setUsername("joedoe");
		model.setPassword("passwd");
		return model;
	}

	default AuthLoginResponse authLoginResponse() {
		AuthLoginResponse model = new AuthLoginResponse();
		model.setToken("API_TOKEN");
		return model;
	}

}
