package io.metaloom.loom.rest.model.assertj;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.metaloom.loom.rest.model.asset.location.AssetLocationResponse;
import io.metaloom.loom.rest.model.junit.LoomAssertions;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.validation.impl.LoomModelValidatorImpl;

public class AssetLocationModelAssert extends AbstractModelAssert<AssetLocationModelAssert, AssetLocationResponse> {

	private LoomModelValidator validator = new LoomModelValidatorImpl();

	public AssetLocationModelAssert(AssetLocationResponse actual) {
		super(actual, AssetLocationModelAssert.class);
	}

	public AssetLocationModelAssert isValid() {
		validator.validate(actual);
		return this;
	}

	public AssetLocationModelAssert hasPath(String path) {
		assertEquals(path, actual.getFilesystem().getPath(), "The filesystem.path value did not match");
		return this;
	}

	public AssetLocationModelAssert matches(AssetLocationResponse response) {
		assertEquals(response.getUuid(), actual.getUuid(), "The UUID did not match.");
		LoomAssertions.assertEqualsJson(response.getMeta(), actual.getMeta(), "The meta JSON did not match up");
		return this;
	}

}
