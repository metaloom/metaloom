package io.metaloom.loom.rest.model.transcript;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public interface TranscriptExamples extends ExampleValues {

	default Example transcriptCreateRequestExample() {
		return new ExampleImpl(transcriptCreateRequest(), "The transcript create request", HttpResponseStatus.CREATED);
	}

	default Example transcriptUpdateRequestExample() {
		return new ExampleImpl(transcriptUpdateRequest(), "The transcript update request", HttpResponseStatus.OK);
	}

	default Example transcriptResponseExample() {
		return new ExampleImpl(transcriptResponse(), "The transcript response", HttpResponseStatus.OK);
	}

	default Example transcriptListResponseExample() {
		return new ExampleImpl(transcriptListResponse(), "The transcript list response", HttpResponseStatus.OK);
	}

	default TranscriptResponse transcriptResponse() {
		TranscriptResponse model = new TranscriptResponse();
		model.setUuid(uuidC());
		model.setSource("whisper");
		model.setProducerVersion("ggml-base");
		model.setStreamIndex(0);
		model.setLang("en");
		model.setModel("ggml-base");
		model.setTranscriptText("Hello world. This is a test transcription.");
		model.setDuration(5000L);
		model.setTranscriptJson(new JsonObject()
			.put("segments", new JsonArray()
				.add(new JsonObject().put("text", "Hello world.").put("from", 0).put("to", 2500))
				.add(new JsonObject().put("text", "This is a test transcription.").put("from", 2500).put("to", 5000))));
		model.setAssetUuid(uuidA().toString());
		setCreatorEditor(model);
		return model;
	}

	default TranscriptCreateRequest transcriptCreateRequest() {
		TranscriptCreateRequest model = new TranscriptCreateRequest();
		model.setSource("whisper");
		model.setProducerVersion("ggml-base");
		model.setStreamIndex(0);
		model.setLang("en");
		model.setModel("ggml-base");
		model.setTranscriptText("Hello world. This is a test transcription.");
		model.setDuration(5000L);
		model.setTranscriptJson(new JsonObject()
			.put("segments", new JsonArray()
				.add(new JsonObject().put("text", "Hello world.").put("from", 0).put("to", 2500))
				.add(new JsonObject().put("text", "This is a test transcription.").put("from", 2500).put("to", 5000))));
		return model;
	}

	default TranscriptUpdateRequest transcriptUpdateRequest() {
		TranscriptUpdateRequest model = new TranscriptUpdateRequest();
		model.setLang("de");
		model.setTranscriptText("Hallo Welt. Dies ist eine Testtranskription.");
		model.setDuration(5200L);
		model.setTranscriptJson(new JsonObject()
			.put("segments", new JsonArray()
				.add(new JsonObject().put("text", "Hallo Welt.").put("from", 0).put("to", 2600))
				.add(new JsonObject().put("text", "Dies ist eine Testtranskription.").put("from", 2600).put("to", 5200))));
		return model;
	}

	default TranscriptListResponse transcriptListResponse() {
		TranscriptListResponse model = new TranscriptListResponse();
		model.setMetainfo(pagingInfo());
		model.add(transcriptResponse());
		model.add(transcriptResponse());
		return model;
	}

}
