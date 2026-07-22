package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.rest.model.assertj.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.task.TaskPriority;
import io.metaloom.loom.api.task.TaskStatus;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.rest.model.task.TaskCreateRequest;
import io.metaloom.loom.rest.model.task.TaskListResponse;
import io.metaloom.loom.rest.model.task.TaskResponse;

public class AssetTaskEndpointTest extends AbstractEndpointTest {

	private static final Instant DUE_DATE = Instant.parse("2026-08-01T12:00:00Z");

	@Test
	public void testAssignTaskToAsset() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			TaskCreateRequest request = new TaskCreateRequest();
			request.setTitle("Review footage");
			request.setDescription("Check the intro sequence");
			request.setPriority(TaskPriority.HIGH);
			request.setTaskStatus(TaskStatus.REVIEW);
			request.setDueDate(DUE_DATE);
			TaskResponse task = client.createTask(request).sync().body();
			assertThat(task).isValid();
			org.assertj.core.api.Assertions.assertThat(task.getPriority()).as("priority").isEqualTo(TaskPriority.HIGH);
			org.assertj.core.api.Assertions.assertThat(task.getTaskStatus()).as("taskStatus").isEqualTo(TaskStatus.REVIEW);
			org.assertj.core.api.Assertions.assertThat(task.getDueDate()).as("dueDate").isEqualTo(DUE_DATE);

			TaskResponse assigned = client.assignTaskToAsset(ASSET_UUID, task.getUuid()).sync().body();
			assertThat(assigned).isValid();

			TaskListResponse list = client.listAssetTasks(ASSET_UUID).sync().body();
			TaskResponse listed = list.getData().stream()
				.filter(t -> t.getUuid().equals(task.getUuid()))
				.findFirst()
				.orElse(null);
			org.assertj.core.api.Assertions.assertThat(listed).as("The assigned task must be listed on the asset").isNotNull();
			org.assertj.core.api.Assertions.assertThat(listed.getTaskStatus()).as("listed taskStatus").isEqualTo(TaskStatus.REVIEW);
			org.assertj.core.api.Assertions.assertThat(listed.getDueDate()).as("listed dueDate").isEqualTo(DUE_DATE);

			client.unassignTaskFromAsset(ASSET_UUID, task.getUuid()).sync().body();

			// An empty list response omits the data property
			TaskListResponse list2 = client.listAssetTasks(ASSET_UUID).sync().body();
			org.assertj.core.api.Assertions.assertThat(list2.getData() == null
				|| list2.getData().stream().noneMatch(t -> t.getUuid().equals(task.getUuid())))
				.as("The task must no longer be listed after unassigning").isTrue();
		}
	}

	@Test
	public void testAssignErrors() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			TaskCreateRequest request = new TaskCreateRequest();
			request.setTitle("Error case task");
			TaskResponse task = client.createTask(request).sync().body();
			assertThat(task).isValid();

			expect(404, "Not Found", client.assignTaskToAsset(UUID.randomUUID(), task.getUuid()));
			expect(404, "Not Found", client.assignTaskToAsset(ASSET_UUID, UUID.randomUUID()));
			expect(404, "Not Found", client.unassignTaskFromAsset(ASSET_UUID, UUID.randomUUID()));
			expect(404, "Not Found", client.listAssetTasks(UUID.randomUUID()));
		}
	}

}
