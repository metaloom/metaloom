package io.metaloom.loom.rest.builder;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.model.task.Task;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.task.TaskListResponse;

public class TaskModelBuilderTest extends AbstractModelBuilderTest {

	@Test
	@Override
	void testResponseModel() throws IOException {
		Task task = mockTask("primary");
		assertWithModel(builder().toResponse(task), "task.response");
	}

	@Test
	@Override
	void testListResponseModel() throws IOException {
		Task task1 = mockTask("primary");
		Task task2 = mockTask("secondary");
		Page<Task> page = mockPage(task1, task2);
		TaskListResponse list = builder().toTaskList(page);
		assertWithModel(list, "task.list_response");
	}

	private Task mockTask(String title) {
		Task task = mock(Task.class);
		when(task.getUuid()).thenReturn(TASK_UUID);
		when(task.getTitle()).thenReturn(title);
		return task;
	}
}
