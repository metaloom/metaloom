package io.metaloom.loom.rest.model.example;

import static io.metaloom.loom.rest.model.example.ModelTestHelper.assertModel;

import org.junit.jupiter.api.Test;

public class TaskModelTest extends AbstractModelTest {

	@Test
	@Override
	public void testResponse() {
		assertModel(taskResponse(), "TaskResponse");
	}

	@Test
	@Override
	public void testCreateRequest() {
		assertModel(taskCreateRequest(), "TaskCreateRequest");
	}

	@Test
	@Override
	public void testUpdateRequest() {
		assertModel(taskUpdateRequest(), "TaskUpdateRequest");
	}

	@Test
	@Override
	public void testReference() {
		//assertModel(taskReference(), "TaskReference");
	}

	@Test
	@Override
	public void testListResponse() {
		assertModel(taskListResponse(), "TaskListResponse");
	}



}
