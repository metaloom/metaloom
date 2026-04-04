package io.metaloom.loom.rest.model.assertj;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.metaloom.loom.rest.model.group.GroupResponse;
import io.metaloom.loom.rest.model.junit.LoomAssertions;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.validation.impl.LoomModelValidatorImpl;

public class GroupModelAssert extends AbstractModelAssert<GroupModelAssert, GroupResponse> {

	private LoomModelValidator validator = new LoomModelValidatorImpl();

	public GroupModelAssert(GroupResponse actual) {
		super(actual, GroupModelAssert.class);
	}

	public GroupModelAssert isValid() {
		validator.validate(actual);
		return this;
	}

	public GroupModelAssert matches(GroupResponse group) {
		assertEquals(group.getUuid(), actual.getUuid(), "UUID did not match.");
		assertEquals(group.getName(), actual.getName(), "Name did not match.");
		LoomAssertions.assertEqualsJson(group.getMeta(), actual.getMeta(), "Metadata did not match");
		return this;
	}


}
