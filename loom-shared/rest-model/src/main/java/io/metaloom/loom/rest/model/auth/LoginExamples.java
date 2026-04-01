package io.metaloom.loom.rest.model.auth;

import io.metaloom.loom.rest.model.example.ExampleValues;

public interface LoginExamples extends ExampleValues {

	default AuthLoginRequest loginRequest() {
		AuthLoginRequest model = new AuthLoginRequest();
		model.setUsername("joedoe");
		model.setPassword("wae8aoK3cohthaum0Shi");
		return model;
	}
}
