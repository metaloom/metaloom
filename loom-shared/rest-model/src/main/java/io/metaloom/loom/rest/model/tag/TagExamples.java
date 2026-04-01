package io.metaloom.loom.rest.model.tag;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface TagExamples extends ExampleValues {

	default Example tagResponseExample() {
		return new ExampleImpl(tagResponse(), "The tag response", HttpResponseStatus.OK);
	}

	default Example tagUpdateRequestExample() {
		return new ExampleImpl(tagUpdateRequest(), "The tag update request", HttpResponseStatus.OK);
	}

	default Example tagCreateRequestExample() {
		return new ExampleImpl(tagCreateRequest(), "The tag create request", HttpResponseStatus.CREATED);
	}

	default Example tagListResponseExample() {
		return new ExampleImpl(tagListResponse(), "The tag list response", HttpResponseStatus.OK);
	}

	default TagResponse tagResponse() {
		TagResponse model = new TagResponse();
		model.setUuid(uuidB());
		model.setName("red");
		model.setCollection("colors");
		model.setMeta(meta());
		model.setColor("1681de");
		model.setArea(12, 42, 120, 240, 480, 200);
		setCreatorEditor(model);
		return model;
	}

	default TagCreateRequest tagCreateRequest() {
		TagCreateRequest model = new TagCreateRequest();
		model.setName("red");
		model.setCollection("colors");
		model.setMeta(meta());
		return model;
	}

	default TagUpdateRequest tagUpdateRequest() {
		TagUpdateRequest model = new TagUpdateRequest();
		model.setName("red");
		model.setCollection("colors");
		model.setMeta(meta());
		return model;
	}

	default TagListResponse tagListResponse() {
		TagListResponse model = new TagListResponse();
		model.setMetainfo(pagingInfo());
		model.add(tagResponse());
		model.add(tagResponse());
		return model;
	}

	default TagReference tagReference() {
		return tagReferenceA();
	}

}
