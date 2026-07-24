package io.metaloom.loom.rest.builder;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.task.TaskStatus;
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
		when(task.getStatus()).thenReturn(TaskStatus.REVIEW);
		when(task.getDueDate()).thenReturn(Instant.parse("2018-10-12T14:15:06Z"));
		// The response carries a creator/editor block; without a creator uuid the builder
		// leaves it empty (machine-written rows).
		mockCreatorEditorRefs(task);
		return task;
	}
}
