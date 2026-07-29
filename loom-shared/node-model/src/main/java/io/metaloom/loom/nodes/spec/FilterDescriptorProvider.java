package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.*;
import static io.metaloom.loom.nodes.spec.NodeCategory.*;
import static io.metaloom.loom.nodes.spec.NodeMode.*;
import static io.metaloom.loom.nodes.spec.ParameterType.*;
import static io.metaloom.loom.nodes.spec.PortSpec.many;
import static io.metaloom.loom.nodes.spec.PortSpec.one;
import static io.metaloom.loom.nodes.spec.PortSpec.optionalOne;

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
				.setInputPorts(List.of(
					one("media", MEDIA_ANY)
						.describedAs("Media", "The media item whose MIME type is tested")))
				.setOutputPorts(List.of(
					one("media", MEDIA_ANY)
						.describedAs("Media", "The item, passed through. Wire downstream work here and set the edge branch to PASS or REJECT"),
					one("passed", CONTROL_FILTER)
						.describedAs("Passed", "The verdict itself, for a node that consumes the decision rather than the item")))
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
				.setInputPorts(List.of(
					one("media", MEDIA_ANY)
						.describedAs("Media", "The media item whose timestamps are tested")))
				.setOutputPorts(List.of(
					one("media", MEDIA_ANY)
						.describedAs("Media", "The item, passed through. Wire downstream work here and set the edge branch to PASS or REJECT"),
					one("passed", CONTROL_FILTER)
						.describedAs("Passed", "The verdict itself, for a node that consumes the decision rather than the item")))
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
				.setInputPorts(List.of(
					one("media", MEDIA_ANY)
						.describedAs("Media", "The media item whose size on disk is tested")))
				.setOutputPorts(List.of(
					one("media", MEDIA_ANY)
						.describedAs("Media", "The item, passed through. Wire downstream work here and set the edge branch to PASS or REJECT"),
					one("passed", CONTROL_FILTER)
						.describedAs("Passed", "The verdict itself, for a node that consumes the decision rather than the item")))
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
				// The identity is a hash, not the media: the node compares what an upstream hash
				// node produced, which is what its implementation always did.
				.setInputPorts(List.of(
					one("hash", HASH_ANY)
						.describedAs("Hash", "The identity checked against what this pipeline has already seen")))
				.setOutputPorts(List.of(
					one("media", MEDIA_ANY)
						.describedAs("Media", "The item, passed through. Wire downstream work here and set the edge branch to PASS or REJECT"),
					one("passed", CONTROL_FILTER)
						.describedAs("Passed", "The verdict itself, for a node that consumes the decision rather than the item")))
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
				.setInputPorts(List.of(
					one("media", MEDIA_ANY)
						.describedAs("Media", "The item being gated. Passed through to the media output"),
					// MANY because checking a transcript and an OCR pass together is the normal
					// case; each wired producer contributes its elements.
					many("text", TEXT_ANY)
						.describedAs("Text", "Every upstream text matched against the blacklisted terms")))
				.setOutputPorts(List.of(
					one("media", MEDIA_ANY)
						.describedAs("Media", "The item, passed through. Wire downstream work here and set the edge branch to PASS or REJECT"),
					one("passed", CONTROL_FILTER)
						.describedAs("Passed", "The verdict itself, for a node that consumes the decision rather than the item")))
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
				.setInputPorts(List.of(
					one("media", MEDIA_ANY)
						.describedAs("Media", "The item being gated. Passed through to the media output"),
					one("quality", STRUCT_QUALITY)
						.describedAs("Quality Metrics", "The metric bag from an upstream quality node")))
				.setOutputPorts(List.of(
					one("media", MEDIA_ANY)
						.describedAs("Media", "The item, passed through. Wire downstream work here and set the edge branch to PASS or REJECT"),
					one("passed", CONTROL_FILTER)
						.describedAs("Passed", "The verdict itself, for a node that consumes the decision rather than the item")))
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
				.setInputPorts(List.of(
					one("media", MEDIA_ANY)
						.describedAs("Media", "The item being gated. Passed through to the media output"),
					one("value", SCALAR_NUMBER)
						.describedAs("Value", "The number compared against the configured threshold")))
				.setOutputPorts(List.of(
					one("media", MEDIA_ANY)
						.describedAs("Media", "The item, passed through. Wire downstream work here and set the edge branch to PASS or REJECT"),
					one("passed", CONTROL_FILTER)
						.describedAs("Passed", "The verdict itself, for a node that consumes the decision rather than the item")))
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
				.setInputPorts(List.of(
					one("media", MEDIA_ANY)
						.describedAs("Media", "The media item whose Loom asset attributes are tested"),
					// Declared because the node has always read resolution, fps and bitrate from a
					// quality node; without this port it could only reach them by node id.
					optionalOne("quality", STRUCT_QUALITY)
						.describedAs("Quality", "Metrics from a quality node; without it only the file-size checks apply")))
				.setOutputPorts(List.of(
					one("media", MEDIA_ANY)
						.describedAs("Media", "The item, passed through. Wire downstream work here and set the edge branch to PASS or REJECT"),
					one("passed", CONTROL_FILTER)
						.describedAs("Passed", "The verdict itself, for a node that consumes the decision rather than the item")))
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
