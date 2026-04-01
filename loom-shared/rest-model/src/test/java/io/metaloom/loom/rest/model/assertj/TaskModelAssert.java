package io.metaloom.loom.rest.model.assertj;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.assertj.core.api.AbstractAssert;

import io.metaloom.loom.rest.model.task.TaskResponse;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.validation.impl.LoomModelValidatorImpl;

public class TaskModelAssert extends AbstractAssert<TaskModelAssert, TaskResponse> {

	private LoomModelValidator validator = new LoomModelValidatorImpl();

	public TaskModelAssert(TaskResponse actual) {
		super(actual, TaskModelAssert.class);
	}

	public TaskModelAssert isValid() {
		validator.validate(actual);
		return this;
	}

	public TaskModelAssert matches(TaskResponse task) {
		assertEquals(actual.getTitle(), task.getTitle(), "Title did not match up");
		assertEquals(actual.getDescription(), task.getDescription(), "Description did not match up");
		assertEquals(actual.getUuid(), task.getUuid(), "UUID did not match up");
		return this;
	}

}
