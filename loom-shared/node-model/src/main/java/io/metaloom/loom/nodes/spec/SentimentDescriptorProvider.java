package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypes.*;
import static io.metaloom.loom.nodes.spec.NodeCategory.*;
import static io.metaloom.loom.nodes.spec.NodeMode.*;
import static io.metaloom.loom.nodes.spec.ParameterType.*;

import java.util.List;

/**
 * Provides the node descriptor for the sentiment analysis node.
 */
public class SentimentDescriptorProvider implements NodeDescriptorProvider {

	private static final List<String> STANDARD_EVENTS = List.of(
		"NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED", "NODE_STATS");

	@Override
	public List<NodeDescriptor> getDescriptors() {
		return List.of(
			new NodeDescriptor()
				.setKind("sentiment")
				.setName("Sentiment Analysis")
				.setDescription("Score the polarity (positive/neutral/negative) of text produced by an upstream node. German and English.")
				.setIcon("mood")
				.setCategory(ANALYSIS)
				.setInputs(List.of(new NodeInput("text", DATA_TEXT, true)))
				.setOutputs(List.of(
					new NodeOutput("sentiment_label", DATA_STRING),
					new NodeOutput("sentiment_score", DATA_NUMBER),
					new NodeOutput("sentiment_result", DATA_TEXT)))
				.setParameters(List.of(
					commonEnabled(), commonProcessIncomplete(), commonRetryFailed(),
					new NodeParameter().setKey("sentimentHost").setType(STRING).setDefaultValue("localhost")
						.setLabel("Sidecar Host").setDescription("Host of the /v1/sentiment sidecar"),
					new NodeParameter().setKey("sentimentPort").setType(INTEGER).setDefaultValue(9110)
						.setLabel("Sidecar Port").setDescription("Port of the /v1/sentiment sidecar").setMin(1),
					new NodeParameter().setKey("language").setType(STRING).setDefaultValue("auto")
						.setLabel("Language").setDescription("'de', 'en', or 'auto' to let the sidecar detect it"),
					new NodeParameter().setKey("modelDe").setType(STRING)
						.setLabel("German Model").setDescription("Override the sidecar's German checkpoint"),
					new NodeParameter().setKey("modelEn").setType(STRING)
						.setLabel("English Model").setDescription("Override the sidecar's English checkpoint"),
					new NodeParameter().setKey("maxChars").setType(INTEGER).setDefaultValue(200000)
						.setLabel("Max Characters").setDescription("Upper bound on the text sent to the sidecar").setMin(1)))
				.setDefaultConcurrency(1)
				.setDefaultMode(PARALLEL)
				.setEvents(STANDARD_EVENTS)
		);
	}

	private static NodeParameter commonEnabled() {
		return new NodeParameter().setKey("enabled").setType(BOOLEAN).setDefaultValue(true)
			.setLabel("Enabled").setDescription("Whether this node is active in the pipeline");
	}

	private static NodeParameter commonProcessIncomplete() {
		return new NodeParameter().setKey("processIncomplete").setType(BOOLEAN).setDefaultValue(false)
			.setLabel("Process Incomplete").setDescription("Process media files that are still being written");
	}

	private static NodeParameter commonRetryFailed() {
		return new NodeParameter().setKey("retryFailed").setType(BOOLEAN).setDefaultValue(false)
			.setLabel("Retry Failed").setDescription("Retry processing media that previously failed");
	}
}
