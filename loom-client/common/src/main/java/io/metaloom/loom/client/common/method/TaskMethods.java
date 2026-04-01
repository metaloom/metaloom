package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.task.TaskCreateRequest;
import io.metaloom.loom.rest.model.task.TaskListResponse;
import io.metaloom.loom.rest.model.task.TaskResponse;
import io.metaloom.loom.rest.model.task.TaskUpdateRequest;

public interface TaskMethods {

	LoomClientRequest<TaskResponse> loadTask(UUID taskUuid);

	LoomClientRequest<TaskResponse> createTask(TaskCreateRequest request);

	LoomClientRequest<TaskResponse> updateTask(UUID taskUuid, TaskUpdateRequest request);

	LoomClientRequest<TaskListResponse> listTasks();

	LoomClientRequest<NoResponse> deleteTask(UUID taskUuid);

}
