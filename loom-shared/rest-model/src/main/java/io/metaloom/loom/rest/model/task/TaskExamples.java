package io.metaloom.loom.rest.model.task;

import java.time.Instant;
import java.util.List;

import io.metaloom.loom.api.task.TaskPriority;
import io.metaloom.loom.api.task.TaskStatus;
import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface TaskExamples extends ExampleValues {

	default Example taskResponseExample() {
		return new ExampleImpl(taskResponse(), "The task response", HttpResponseStatus.OK);
	}

	default Example taskUpdateRequestExample() {
		return new ExampleImpl(taskUpdateRequest(), "The task update request", HttpResponseStatus.OK);
	}

	default Example taskCreateRequestExample() {
		return new ExampleImpl(taskCreateRequest(), "The task create request", HttpResponseStatus.CREATED);
	}

	default Example taskListResponseExample() {
		return new ExampleImpl(taskListResponse(), "The task list response", HttpResponseStatus.OK);
	}

	default TaskResponse taskResponse() {
		TaskResponse model = new TaskResponse();
		model.setUuid(uuidA());
		model.setTitle("The title");
		model.setPriority(TaskPriority.MEDIUM);
		model.setTaskStatus(TaskStatus.PENDING);
		model.setDueDate(Instant.parse("2026-08-01T12:00:00Z"));
		model.setMeta(meta());
		return model;
	}

	default TaskCreateRequest taskCreateRequest() {
		TaskCreateRequest model = new TaskCreateRequest();
		model.setTitle("The title");
		model.setPriority(TaskPriority.MEDIUM);
		model.setTaskStatus(TaskStatus.PENDING);
		model.setDueDate(Instant.parse("2026-08-01T12:00:00Z"));
		model.setMeta(meta());
		return model;
	}

	default TaskUpdateRequest taskUpdateRequest() {
		TaskUpdateRequest model = new TaskUpdateRequest();
		model.setTitle("The title");
		model.setPriority(TaskPriority.HIGH);
		model.setTaskStatus(TaskStatus.REVIEW);
		model.setDueDate(Instant.parse("2026-08-01T12:00:00Z"));
		model.setMeta(meta());
		return model;
	}

	default TaskListResponse taskListResponse() {
		TaskListResponse model = new TaskListResponse();
		model.add(taskResponse());
		model.setMetainfo(pagingInfo());
		return model;
	}

	default Example taskAssigneeListResponseExample() {
		return new ExampleImpl(taskAssigneeListResponse(), "The task assignee list response", HttpResponseStatus.OK);
	}

	default Example taskAssignRequestExample() {
		return new ExampleImpl(taskAssignRequest(), "The task assign request", HttpResponseStatus.CREATED);
	}

	default TaskAssigneeResponse taskAssigneeResponse() {
		TaskAssigneeResponse model = new TaskAssigneeResponse();
		model.setUserUuid(uuidB());
		model.setName("joedoe");
		model.setAssigned(Instant.parse("2026-08-01T12:00:00Z"));
		model.setAssignerUuid(uuidA());
		return model;
	}

	default TaskAssigneeListResponse taskAssigneeListResponse() {
		TaskAssigneeListResponse model = new TaskAssigneeListResponse();
		model.add(taskAssigneeResponse());
		return model;
	}

	default TaskAssignRequest taskAssignRequest() {
		TaskAssignRequest model = new TaskAssignRequest();
		model.setUserUuids(List.of(uuidB()));
		model.setGroupUuids(List.of(uuidC()));
		return model;
	}
}
