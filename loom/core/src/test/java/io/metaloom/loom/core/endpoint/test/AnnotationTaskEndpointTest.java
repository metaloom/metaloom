package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.rest.model.assertj.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.rest.model.annotation.AnnotationResponse;
import io.metaloom.loom.rest.model.task.TaskCreateRequest;
import io.metaloom.loom.rest.model.task.TaskListResponse;
import io.metaloom.loom.rest.model.task.TaskResponse;

public class AnnotationTaskEndpointTest extends AbstractEndpointTest {

	@Test
	public void testAssignTaskToAnnotation() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			TaskCreateRequest request = new TaskCreateRequest();
			request.setTitle("Verify annotation");
			TaskResponse task = client.createTask(request).sync().body();
			assertThat(task).isValid();

			TaskResponse assigned = client.assignTaskToAnnotation(ANNOTATION_UUID, task.getUuid()).sync().body();
			assertThat(assigned).isValid();

			TaskListResponse list = client.listAnnotationTasks(ANNOTATION_UUID).sync().body();
			org.assertj.core.api.Assertions.assertThat(list.getData().stream().anyMatch(t -> t.getUuid().equals(task.getUuid())))
				.as("The assigned task must be listed on the annotation").isTrue();

			// The task must also show up in the embedded task list of the annotation response
			AnnotationResponse annotation = client.loadAnnotation(ANNOTATION_UUID).sync().body();
			org.assertj.core.api.Assertions.assertThat(annotation.getTasks().stream().anyMatch(t -> t.getUuid().equals(task.getUuid())))
				.as("The assigned task must be embedded in the annotation response").isTrue();

			client.unassignTaskFromAnnotation(ANNOTATION_UUID, task.getUuid()).sync().body();

			// An empty list response omits the data property
			TaskListResponse list2 = client.listAnnotationTasks(ANNOTATION_UUID).sync().body();
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

			expect(404, "Not Found", client.assignTaskToAnnotation(UUID.randomUUID(), task.getUuid()));
			expect(404, "Not Found", client.assignTaskToAnnotation(ANNOTATION_UUID, UUID.randomUUID()));
			expect(404, "Not Found", client.unassignTaskFromAnnotation(ANNOTATION_UUID, UUID.randomUUID()));
			expect(404, "Not Found", client.listAnnotationTasks(UUID.randomUUID()));
		}
	}

}
