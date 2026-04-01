package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.rest.model.assertj.Assertions.assertThat;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractCRUDEndpointTest;
import io.metaloom.loom.rest.model.task.TaskCreateRequest;
import io.metaloom.loom.rest.model.task.TaskListResponse;
import io.metaloom.loom.rest.model.task.TaskResponse;
import io.metaloom.loom.rest.model.task.TaskUpdateRequest;

public class TaskEndpointTest extends AbstractCRUDEndpointTest {

	@Override
	protected void testRead(LoomHttpClient client) throws LoomClientException {
		TaskResponse task = client.loadTask(TASK_UUID).sync();
		assertThat(task).isValid();
	}

	@Override
	protected void testCreate(LoomHttpClient client) throws LoomClientException {
		TaskCreateRequest request = new TaskCreateRequest();
		request.setTitle("dummy title");
		TaskResponse task = client.createTask(request).sync();
		assertThat(task).isValid();

		TaskResponse task2 = client.loadTask(task.getUuid()).sync();
		assertThat(task).matches(task2);
	}

	@Override
	protected void testDelete(LoomHttpClient client) throws LoomClientException {
		client.deleteTask(TASK_UUID).sync();
		expect(404, "Not Found", client.loadTask(TASK_UUID));
	}

	@Override
	protected void testUpdate(LoomHttpClient client) throws LoomClientException {
		TaskUpdateRequest update = new TaskUpdateRequest();
		update.setTitle("updated-title");
		update.setDescription("updated-description");
		TaskResponse response = client.updateTask(TASK_UUID, update).sync();
		assertThat(response).isValid();
	}

	@Override
	protected void testReadPage(LoomHttpClient client) throws LoomClientException {
		for (int i = 0; i < 100; i++) {
			TaskCreateRequest request = new TaskCreateRequest();
			request.setTitle("dummy title");
			client.createTask(request).sync();
		}
		TaskListResponse list = client.listTasks().sync();
		assertThat(list).isValid().hasSize(25).hasPerPage(25);
	}

}
