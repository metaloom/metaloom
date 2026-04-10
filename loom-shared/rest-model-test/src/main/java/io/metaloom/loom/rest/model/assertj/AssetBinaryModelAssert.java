package io.metaloom.loom.rest.model.assertj;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.metaloom.loom.rest.model.asset.binary.AssetBinaryResponse;
import io.metaloom.loom.rest.model.junit.LoomAssertions;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.validation.impl.LoomModelValidatorImpl;

public class AssetBinaryModelAssert extends AbstractModelAssert<AssetBinaryModelAssert, AssetBinaryResponse> {

	private LoomModelValidator validator = new LoomModelValidatorImpl();

	public AssetBinaryModelAssert(AssetBinaryResponse actual) {
		super(actual, AssetBinaryModelAssert.class);
	}

	public AssetBinaryModelAssert isValid() {
		validator.validate(actual);
		return this;
	}

	public AssetBinaryModelAssert hasPath(String path) {
		assertEquals(path, actual.getFilesystem().getPath(), "The filesystem.path value did not match");
		return this;
	}

	public AssetBinaryModelAssert matches(AssetBinaryResponse response) {
		assertEquals(response.getUuid(), actual.getUuid(), "The UUID did not match.");
		LoomAssertions.assertEqualsJson(response.getMeta(), actual.getMeta(), "The meta JSON did not match up");
		return this;
	}

}
