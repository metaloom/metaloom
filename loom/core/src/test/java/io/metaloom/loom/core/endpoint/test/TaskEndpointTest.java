package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.rest.model.assertj.Assertions.assertThat;

import java.time.Instant;

import io.metaloom.loom.api.task.TaskStatus;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractCRUDEndpointTest;
import io.metaloom.loom.rest.model.task.TaskCreateRequest;
import io.metaloom.loom.rest.model.task.TaskListResponse;
import io.metaloom.loom.rest.model.task.TaskResponse;
import io.metaloom.loom.rest.model.task.TaskUpdateRequest;

public class TaskEndpointTest extends AbstractCRUDEndpointTest {

	@Override
	protected void testRead(LoomHttpClient client) throws LoomClientException {
		TaskResponse task = client.loadTask(TASK_UUID).sync().body();
		assertThat(task).isValid();
	}

	@Override
	protected void testCreate(LoomHttpClient client) throws LoomClientException {
		TaskCreateRequest request = new TaskCreateRequest();
		request.setTitle("dummy title");
		request.setTaskStatus(TaskStatus.REVIEW);
		request.setDueDate(Instant.parse("2026-08-01T12:00:00Z"));
		TaskResponse task = client.createTask(request).sync().body();
		assertThat(task).isValid();
		org.assertj.core.api.Assertions.assertThat(task.getTaskStatus()).as("taskStatus").isEqualTo(TaskStatus.REVIEW);
		org.assertj.core.api.Assertions.assertThat(task.getDueDate()).as("dueDate").isEqualTo(Instant.parse("2026-08-01T12:00:00Z"));

		TaskResponse task2 = client.loadTask(task.getUuid()).sync().body();
		assertThat(task).matches(task2);
	}

	@Override
	protected void testDelete(LoomHttpClient client) throws LoomClientException {
		client.deleteTask(TASK_UUID).sync().body();
		expect(404, "Not Found", client.loadTask(TASK_UUID));
	}

	@Override
	protected void testUpdate(LoomHttpClient client) throws LoomClientException {
		TaskUpdateRequest update = new TaskUpdateRequest();
		update.setTitle("updated-title");
		update.setDescription("updated-description");
		update.setTaskStatus(TaskStatus.ACCEPTED);
		update.setDueDate(Instant.parse("2026-09-01T12:00:00Z"));
		TaskResponse response = client.updateTask(TASK_UUID, update).sync().body();
		assertThat(response).isValid();
		org.assertj.core.api.Assertions.assertThat(response.getTaskStatus()).as("taskStatus").isEqualTo(TaskStatus.ACCEPTED);
		org.assertj.core.api.Assertions.assertThat(response.getDueDate()).as("dueDate").isEqualTo(Instant.parse("2026-09-01T12:00:00Z"));
	}

	@Override
	protected void testReadPage(LoomHttpClient client) throws LoomClientException {
		for (int i = 0; i < 100; i++) {
			TaskCreateRequest request = new TaskCreateRequest();
			request.setTitle("dummy title");
			client.createTask(request).sync().body();
		}
		TaskListResponse list = client.listTasks().sync().body();
		assertThat(list).isValid().hasSize(25).hasPerPage(25);
	}

	@Override
	protected LoomClientRequest<?> createRequest(LoomHttpClient client) {
		TaskCreateRequest request = new TaskCreateRequest();
		request.setTitle("perm-check");
		return client.createTask(request);
	}

	@Override
	protected LoomClientRequest<?> loadRequest(LoomHttpClient client) {
		return client.loadTask(TASK_UUID);
	}

	@Override
	protected LoomClientRequest<?> listRequest(LoomHttpClient client) {
		return client.listTasks();
	}

	@Override
	protected LoomClientRequest<?> deleteRequest(LoomHttpClient client) {
		return client.deleteTask(TASK_UUID);
	}

}
