package io.metaloom.loom.rest.model.noderun;

import java.util.List;
import java.util.Map;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.metaloom.loom.rest.model.message.GenericMessageResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/** OpenAPI examples for the ad-hoc node execution routes. */
public interface NodeRunExamples extends ExampleValues {

	default Example nodeProbeRequestExample() {
		return new ExampleImpl(nodeProbeRequest(), "The node probe request", HttpResponseStatus.OK);
	}

	default Example nodeProbeResponseExample() {
		return new ExampleImpl(nodeProbeResponse(), "The node probe response", HttpResponseStatus.OK);
	}

	default Example nodeRunRequestExample() {
		return new ExampleImpl(nodeRunRequest(), "The node run request", HttpResponseStatus.OK);
	}

	default Example nodeRunResponseExample() {
		return new ExampleImpl(nodeRunResponse(), "The node run handle", HttpResponseStatus.ACCEPTED);
	}

	default Example nodeRunStatusResponseExample() {
		return new ExampleImpl(nodeRunStatusResponse(), "The node run status response", HttpResponseStatus.OK);
	}

	default Example nodeRunListResponseExample() {
		return new ExampleImpl(nodeRunListResponse(), "The node run list response", HttpResponseStatus.OK);
	}

	default Example nodeRunCancelResponseExample() {
		return new ExampleImpl(new GenericMessageResponse().setMessage("Node run cancelled"),
			"The node run cancel response", HttpResponseStatus.OK);
	}

	default NodeProbeRequest nodeProbeRequest() {
		return new NodeProbeRequest()
			.setKind("vlm")
			.setAssetUuid(uuidA())
			.setOptions(Map.of("prompt", "What is in this image?"))
			.setPersist(false);
	}

	default NodeProbeResponse nodeProbeResponse() {
		return new NodeProbeResponse()
			.setState("COMPLETED")
			.setNodeKind("vlm")
			.setAssetUuid(uuidA())
			.setDurationMs(4120L)
			.setOutputs(new JsonObject().put("text", new JsonObject()
				.put("contentType", "text/plain")
				.put("cardinality", "ONE")
				.put("elements", new JsonArray().add(new JsonObject().put("value", "A beach at sunset.").put("seq", 0)))))
			.setText("beach.jpg\n  vlm: COMPLETED (4120ms)\n    text [text/plain]: A beach at sunset.\n");
	}

	default NodeRunRequest nodeRunRequest() {
		return new NodeRunRequest()
			.setDefinition(nodeRunDefinition())
			.setAssetUuids(List.of(uuidA(), uuidB()))
			.setPersist(true);
	}

	default NodeRunResponse nodeRunResponse() {
		return new NodeRunResponse()
			.setUuid(uuidC())
			.setStatus("RUNNING")
			.setAccepted(2)
			.setRejected(0)
			.setRejectedAssetUuids(List.of())
			.setEtaMs(10_000L)
			.setMessage("2 item(s) accepted");
	}

	default NodeRunStatusResponse nodeRunStatusResponse() {
		return new NodeRunStatusResponse()
			.setUuid(uuidC())
			.setStatus("RUNNING")
			.setMediaCount(2)
			.setSuccessCount(1)
			.setFailureCount(0)
			.setSkippedCount(0)
			.setDefinition(nodeRunDefinition())
			.setResults(List.of(new NodeRunItemResult()
				.setAssetUuid(uuidA())
				.setMediaPath("/data/media/beach.jpg")
				.setNodeId("vlm")
				.setNodeKind("vlm")
				.setState("COMPLETED")
				.setDurationMs(4120L)
				.setOutputs(new JsonObject().put("text", new JsonObject()
					.put("contentType", "text/plain")
					.put("cardinality", "ONE")
					.put("elements", new JsonArray().add(new JsonObject().put("value", "A beach at sunset.").put("seq", 0)))))));
	}

	default NodeRunListResponse nodeRunListResponse() {
		NodeRunListResponse model = new NodeRunListResponse();
		model.add(nodeRunStatusResponse());
		model.setMetainfo(pagingInfo());
		return model;
	}

	/** The definition format is the same one a stored pipeline uses; the source node may be omitted. */
	default JsonObject nodeRunDefinition() {
		return new JsonObject()
			.put("version", 1)
			.put("name", "describe images")
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "vlm").put("type", "vlm")
					.put("options", new JsonObject().put("prompt", "What is in this image?"))));
	}
}
