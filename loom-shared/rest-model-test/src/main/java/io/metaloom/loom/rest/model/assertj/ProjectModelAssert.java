package io.metaloom.loom.rest.model.assertj;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.metaloom.loom.rest.model.junit.LoomAssertions;
import io.metaloom.loom.rest.model.project.ProjectResponse;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.validation.impl.LoomModelValidatorImpl;

public class ProjectModelAssert extends AbstractModelAssert<ProjectModelAssert, ProjectResponse> {

	private LoomModelValidator validator = new LoomModelValidatorImpl();

	public ProjectModelAssert(ProjectResponse actual) {
		super(actual, ProjectModelAssert.class);
	}

	public ProjectModelAssert isValid() {
		validator.validate(actual);
		return this;
	}

	public ProjectModelAssert matches(ProjectResponse response) {
		assertEquals(response.getUuid(), actual.getUuid(), "The UUID did not match");
		LoomAssertions.assertEqualsJson(response.getMeta(), actual.getMeta(), "The meta JSON did not match");
		return this;
	}

}
