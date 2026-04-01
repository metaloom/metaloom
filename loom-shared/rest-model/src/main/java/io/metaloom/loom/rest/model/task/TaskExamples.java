package io.metaloom.loom.rest.model.task;

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
		model.setMeta(meta());
		return model;
	}

	default TaskCreateRequest taskCreateRequest() {
		TaskCreateRequest model = new TaskCreateRequest();
		model.setTitle("The title");
		model.setMeta(meta());
		return model;
	}

	default TaskUpdateRequest taskUpdateRequest() {
		TaskUpdateRequest model = new TaskUpdateRequest();
		model.setTitle("The title");
		model.setMeta(meta());
		return model;
	}

	default TaskListResponse taskListResponse() {
		TaskListResponse model = new TaskListResponse();
		model.add(taskResponse());
		model.setMetainfo(pagingInfo());
		return model;
	}
}
