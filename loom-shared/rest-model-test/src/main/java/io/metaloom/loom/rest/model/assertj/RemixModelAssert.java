package io.metaloom.loom.rest.model.assertj;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import io.metaloom.loom.rest.model.remix.RemixResponse;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.validation.impl.LoomModelValidatorImpl;

public class RemixModelAssert extends AbstractModelAssert<RemixModelAssert, RemixResponse> {

	private LoomModelValidator validator = new LoomModelValidatorImpl();

	public RemixModelAssert(RemixResponse actual) {
		super(actual, RemixModelAssert.class);
	}

	public RemixModelAssert isValid() {
		validator.validate(actual);
		return this;
	}

	public RemixModelAssert hasName(String name) {
		assertEquals(name, actual.getName(), "The remix name did not match");
		return this;
	}

	public RemixModelAssert hasDescription(String description) {
		assertEquals(description, actual.getDescription(), "The remix description did not match");
		return this;
	}

	public RemixModelAssert hasSource(UUID assetUuid) {
		assertEquals(assetUuid, actual.getSourceAssetUuid(), "The source asset did not match");
		return this;
	}

	public RemixModelAssert hasNoSource() {
		assertNull(actual.getSourceAssetUuid(), "The remix should not name a source asset");
		return this;
	}

	public RemixModelAssert hasMemberCount(long count) {
		assertEquals(count, actual.getMemberCount(), "The member count did not match");
		return this;
	}

}
