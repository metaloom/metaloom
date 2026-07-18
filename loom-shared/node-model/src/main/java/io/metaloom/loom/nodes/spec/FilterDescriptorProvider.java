package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypes.*;
import static io.metaloom.loom.nodes.spec.NodeCategory.*;
import static io.metaloom.loom.nodes.spec.NodeMode.*;
import static io.metaloom.loom.nodes.spec.ParameterType.*;

import java.util.List;

/**
 * Provides node descriptors for all pipeline filter nodes.
 */
public class FilterDescriptorProvider implements NodeDescriptorProvider {

	private static final List<String> STANDARD_EVENTS = List.of(
		"NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED", "NODE_STATS");

	@Override
	public List<NodeDescriptor> getDescriptors() {
		return List.of(
			new NodeDescriptor()
				.setKind("filter-mimetype")
				.setName("MIME Type Filter")
				.setDescription("Filter media by MIME type (e.g. only process images or videos).")
				.setIcon("filter_alt")
				.setCategory(FILTER)
				.setInputs(List.of(new NodeInput("media", MEDIA_ANY, true)))
				.setOutputs(List.of(new NodeOutput("filter_passed", CONTROL_FILTER_RESULT)))
				.setParameters(List.of(
					commonEnabled(),
					new NodeParameter().setKey("allowedTypes").setType(STRING)
						.setLabel("Allowed MIME Types")
						.setDescription("Comma-separated list of allowed MIME types (e.g. 'image/*,video/*')")))
				.setDefaultConcurrency(4)
				.setDefaultMode(PARALLEL)
				.setEvents(STANDARD_EVENTS),

			new NodeDescriptor()
				.setKind("filter-date")
				.setName("Date Filter")
				.setDescription("Filter media by file creation or modification date.")
				.setIcon("date_range")
				.setCategory(FILTER)
				.setInputs(List.of(new NodeInput("media", MEDIA_ANY, true)))
				.setOutputs(List.of(new NodeOutput("filter_passed", CONTROL_FILTER_RESULT)))
				.setParameters(List.of(
					commonEnabled(),
					new NodeParameter().setKey("after").setType(STRING)
						.setLabel("After Date").setDescription("Only pass files modified after this date (ISO-8601)"),
					new NodeParameter().setKey("before").setType(STRING)
						.setLabel("Before Date").setDescription("Only pass files modified before this date (ISO-8601)")))
				.setDefaultConcurrency(4)
				.setDefaultMode(PARALLEL)
				.setEvents(STANDARD_EVENTS),

			new NodeDescriptor()
				.setKind("filter-size")
				.setName("File Size Filter")
				.setDescription("Filter media by file size.")
				.setIcon("straighten")
				.setCategory(FILTER)
				.setInputs(List.of(new NodeInput("media", MEDIA_ANY, true)))
				.setOutputs(List.of(new NodeOutput("filter_passed", CONTROL_FILTER_RESULT)))
				.setParameters(List.of(
					commonEnabled(),
					new NodeParameter().setKey("minSize").setType(INTEGER)
						.setLabel("Min Size (bytes)").setDescription("Minimum file size in bytes").setMin(0),
					new NodeParameter().setKey("maxSize").setType(INTEGER)
						.setLabel("Max Size (bytes)").setDescription("Maximum file size in bytes").setMin(0)))
				.setDefaultConcurrency(4)
				.setDefaultMode(PARALLEL)
				.setEvents(STANDARD_EVENTS),

			new NodeDescriptor()
				.setKind("filter-duplicate")
				.setName("Duplicate Filter")
				.setDescription("Filter out media that has already been processed.")
				.setIcon("block")
				.setCategory(FILTER)
				.setInputs(List.of(new NodeInput("media", MEDIA_ANY, true)))
				.setOutputs(List.of(new NodeOutput("filter_passed", CONTROL_FILTER_RESULT)))
				.setParameters(List.of(commonEnabled()))
				.setDefaultConcurrency(4)
				.setDefaultMode(PARALLEL)
				.setEvents(STANDARD_EVENTS),

			new NodeDescriptor()
				.setKind("filter-blacklist")
				.setName("Blacklist Filter")
				.setDescription("Filter out media whose upstream text output matches a blacklisted term.")
				.setIcon("playlist_remove")
				.setCategory(FILTER)
				.setInputs(List.of(new NodeInput("text", DATA_TEXT, true)))
				.setOutputs(List.of(new NodeOutput("filter_passed", CONTROL_FILTER_RESULT)))
				.setParameters(List.of(
					commonEnabled(),
					new NodeParameter().setKey("terms").setType(STRING)
						.setLabel("Blacklisted Terms")
						.setDescription("Comma-separated list of blacklisted terms")))
				.setDefaultConcurrency(4)
				.setDefaultMode(PARALLEL)
				.setEvents(STANDARD_EVENTS),

			new NodeDescriptor()
				.setKind("filter-quality")
				.setName("Quality Filter")
				.setDescription("Filter media based on quality metrics (resolution, blurriness, bitrate).")
				.setIcon("tune")
				.setCategory(FILTER)
				.setInputs(List.of(new NodeInput("quality", DATA_QUALITY, true)))
				.setOutputs(List.of(new NodeOutput("filter_passed", CONTROL_FILTER_RESULT)))
				.setParameters(List.of(
					commonEnabled(),
					new NodeParameter().setKey("minWidth").setType(INTEGER)
						.setLabel("Min Width (px)").setMin(0),
					new NodeParameter().setKey("minHeight").setType(INTEGER)
						.setLabel("Min Height (px)").setMin(0),
					new NodeParameter().setKey("maxBlurriness").setType(NUMBER)
						.setLabel("Max Blurriness").setMin(0.0)))
				.setDefaultConcurrency(4)
				.setDefaultMode(PARALLEL)
				.setEvents(STANDARD_EVENTS),

			new NodeDescriptor()
				.setKind("filter-threshold")
				.setName("Threshold Filter")
				.setDescription("Filter media based on a numeric upstream output exceeding a threshold.")
				.setIcon("linear_scale")
				.setCategory(FILTER)
				.setInputs(List.of(new NodeInput("value", DATA_NUMBER, true)))
				.setOutputs(List.of(new NodeOutput("filter_passed", CONTROL_FILTER_RESULT)))
				.setParameters(List.of(
					commonEnabled(),
					new NodeParameter().setKey("threshold").setType(NUMBER)
						.setLabel("Threshold").setDescription("Minimum value to pass the filter")))
				.setDefaultConcurrency(4)
				.setDefaultMode(PARALLEL)
				.setEvents(STANDARD_EVENTS),

			new NodeDescriptor()
				.setKind("filter-asset-attribute")
				.setName("Asset Attribute Filter")
				.setDescription("Filter media based on asset attributes stored in Loom.")
				.setIcon("label")
				.setCategory(FILTER)
				.setInputs(List.of(new NodeInput("media", MEDIA_ANY, true)))
				.setOutputs(List.of(new NodeOutput("filter_passed", CONTROL_FILTER_RESULT)))
				.setParameters(List.of(
					commonEnabled(),
					new NodeParameter().setKey("attributeKey").setType(STRING)
						.setLabel("Attribute Key").setDescription("The asset attribute to check"),
					new NodeParameter().setKey("attributeValue").setType(STRING)
						.setLabel("Expected Value").setDescription("The value to match against")))
				.setDefaultConcurrency(4)
				.setDefaultMode(PARALLEL)
				.setEvents(STANDARD_EVENTS)
		);
	}

	private static NodeParameter commonEnabled() {
		return new NodeParameter().setKey("enabled").setType(BOOLEAN).setDefaultValue(true)
			.setLabel("Enabled").setDescription("Whether this node is active in the pipeline");
	}
}
