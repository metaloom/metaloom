package io.metaloom.loom.rest.model.project;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface ProjectExamples extends ExampleValues {

	default Example projectUpdateRequestExample() {
		return new ExampleImpl(projectUpdateRequest(), "The project update request", HttpResponseStatus.OK);
	}

	default Example projectCreateRequestExample() {
		return new ExampleImpl(projectCreateRequest(), "The project create request", HttpResponseStatus.CREATED);
	}

	default Example projectResponseExample() {
		return new ExampleImpl(projectResponse(), "The project response", HttpResponseStatus.OK);
	}

	default Example projectListResponseExample() {
		return new ExampleImpl(projectListResponse(), "The project list response", HttpResponseStatus.OK);
	}

	default ProjectUpdateRequest projectUpdateRequest() {
		ProjectUpdateRequest request = new ProjectUpdateRequest();
		request.setName("The new name");
		request.setMeta(meta());
		return request;
	}

	default ProjectCreateRequest projectCreateRequest() {
		ProjectCreateRequest request = new ProjectCreateRequest();
		request.setMeta(meta());
		request.setName("The Project Name");
		return request;
	}

	default ProjectResponse projectResponse() {
		ProjectResponse response = new ProjectResponse();
		response.setUuid(uuidB());
		response.setMeta(meta());
		response.setName("The Project Name");
		setCreatorEditor(response);
		return response;
	}

	default ProjectListResponse projectListResponse() {
		ProjectListResponse model = new ProjectListResponse();
		model.setMetainfo(pagingInfo());
		model.add(projectResponse());
		model.add(projectResponse());
		return model;
	}

}
