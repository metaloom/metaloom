package io.metaloom.loom.rest.model.assertj;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.metaloom.loom.rest.model.tag.TagResponse;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.validation.impl.LoomModelValidatorImpl;

public class TagModelAssert extends AbstractModelAssert<TagModelAssert, TagResponse> {

	private LoomModelValidator validator = new LoomModelValidatorImpl();

	public TagModelAssert(TagResponse actual) {
		super(actual, TagModelAssert.class);
	}

	public TagModelAssert isValid() {
		validator.validate(actual);
		return this;
	}

	public TagModelAssert matches(TagResponse task) {
		assertEquals(actual.getName(), task.getName(), "Name did not match up");
		assertEquals(actual.getCollection(), task.getCollection(), "Collection did not match up");
		assertEquals(actual.getUuid(), task.getUuid(), "UUID did not match up");
		return this;
	}

}
