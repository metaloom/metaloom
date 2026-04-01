package io.metaloom.loom.rest.validation;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.rest.model.user.UserReference;
import io.metaloom.loom.rest.model.user.UserResponse;
import io.metaloom.loom.rest.validation.impl.LoomModelValidatorImpl;

public class LoomModelValidatorTest {

	LoomModelValidator validator = new LoomModelValidatorImpl();

	@Test
	public void testValidation() {
		UserResponse response = new UserResponse();
		response.setUuid(UUID.randomUUID());
		response.setUsername("abc");
		response.getStatus().setCreator(new UserReference().setName("abc").setUuid(UUID.randomUUID()));
		response.getStatus().setEditor(new UserReference().setName("abc").setUuid(UUID.randomUUID()));
		response.getStatus().setEdited(Instant.now());
		response.getStatus().setCreated(Instant.now());
		validator.validate(response);
	}
}
