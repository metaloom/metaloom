package io.metaloom.loom.rest.model.jsoncomp;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.json.JsonObject;

public interface JsonCompExamples extends ExampleValues {

	default Example jsonCompCreateRequestExample() {
		return new ExampleImpl(jsonCompCreateRequest(), "The json component create request", HttpResponseStatus.CREATED);
	}

	default Example jsonCompResponseExample() {
		return new ExampleImpl(jsonCompResponse(), "The json component response", HttpResponseStatus.OK);
	}

	default Example jsonCompListResponseExample() {
		return new ExampleImpl(jsonCompListResponse(), "The json component list response", HttpResponseStatus.OK);
	}

	default JsonCompResponse jsonCompResponse() {
		JsonCompResponse model = new JsonCompResponse();
		model.setUuid(uuidC());
		model.setAssetUuid(uuidA().toString());
		model.setNodeKind("hello-world");
		model.setSchemaType("hello-world");
		model.setVariant("");
		model.setProducerVersion("1.0");
		model.setData(new JsonObject().put("file_size", 2048).put("word_count", 137));
		setCreatorEditor(model);
		return model;
	}

	default JsonCompCreateRequest jsonCompCreateRequest() {
		JsonCompCreateRequest model = new JsonCompCreateRequest();
		model.setNodeKind("hello-world");
		model.setSchemaType("hello-world");
		model.setVariant("");
		model.setProducerVersion("1.0");
		model.setData(new JsonObject().put("file_size", 2048).put("word_count", 137));
		return model;
	}

	default JsonCompListResponse jsonCompListResponse() {
		JsonCompListResponse model = new JsonCompListResponse();
		model.setMetainfo(pagingInfo());
		model.add(jsonCompResponse());
		return model;
	}

}
