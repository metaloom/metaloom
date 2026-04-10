package io.metaloom.loom.rest.model.assertj;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.metaloom.loom.rest.model.pool.AssetPoolResponse;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.validation.impl.LoomModelValidatorImpl;

public class AssetPoolModelAssert extends AbstractModelAssert<AssetPoolModelAssert, AssetPoolResponse> {

	private LoomModelValidator validator = new LoomModelValidatorImpl();

	public AssetPoolModelAssert(AssetPoolResponse actual) {
		super(actual, AssetPoolModelAssert.class);
	}

	public AssetPoolModelAssert isValid() {
		validator.validate(actual);
		return this;
	}

	public AssetPoolModelAssert hasName(String name) {
		assertEquals(name, actual.getName(), "The pool name did not match");
		return this;
	}

	public AssetPoolModelAssert matches(AssetPoolResponse response) {
		assertEquals(response.getUuid(), actual.getUuid(), "The UUID did not match.");
		assertEquals(response.getName(), actual.getName(), "The name did not match.");
		return this;
	}

}
