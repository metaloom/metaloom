package io.metaloom.loom.rest.model.example;

import static io.metaloom.loom.rest.model.example.ModelTestHelper.assertModel;

import org.junit.jupiter.api.Test;

public class AuthLoginModelTest implements Examples {

	@Test
	public void testLoginResponse() {
		assertModel(authLoginResponse(), "AuthLoginResponse");
	}

	@Test
	public void restLoginRequest() {
		assertModel(authLoginRequest(), "AuthLoginRequest");
	}

}
