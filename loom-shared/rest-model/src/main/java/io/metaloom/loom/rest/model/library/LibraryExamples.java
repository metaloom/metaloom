package io.metaloom.loom.rest.model.library;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface LibraryExamples extends ExampleValues {

	default Example libraryUpdateRequestExample() {
		return new ExampleImpl(libraryUpdateRequest(), "The library update request", HttpResponseStatus.OK);
	}

	default Example libraryCreateRequestExample() {
		return new ExampleImpl(libraryCreateRequest(), "The library create request", HttpResponseStatus.CREATED);
	}

	default Example libraryResponseExample() {
		return new ExampleImpl(libraryResponse(), "The library response", HttpResponseStatus.OK);
	}

	default Example libraryListResponseExample() {
		return new ExampleImpl(libraryListResponse(), "The library list response", HttpResponseStatus.OK);
	}

	default LibraryResponse libraryResponse() {
		LibraryResponse model = new LibraryResponse();
		model.setUuid(uuidC());
		model.setName("MyProject");
		model.setMeta(meta());
		setCreatorEditor(model);
		return model;
	}

	default LibraryUpdateRequest libraryUpdateRequest() {
		LibraryUpdateRequest model = new LibraryUpdateRequest();
		model.setName("NewProject");
		model.setMeta(meta());
		return model;
	}

	default LibraryCreateRequest libraryCreateRequest() {
		LibraryCreateRequest model = new LibraryCreateRequest();
		model.setName("MyProject");
		model.setMeta(meta());
		return model;
	}

	default LibraryListResponse libraryListResponse() {
		LibraryListResponse model = new LibraryListResponse();
		model.setMetainfo(pagingInfo());
		model.add(libraryResponse());
		model.add(libraryResponse());
		return model;
	}

}
