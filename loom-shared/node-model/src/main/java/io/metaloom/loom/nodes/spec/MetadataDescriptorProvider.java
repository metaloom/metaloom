package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.MEDIA_ANY;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.STRUCT_JSON;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.TEXT_PLAIN;
import static io.metaloom.loom.nodes.spec.NodeCategory.ANALYSIS;
import static io.metaloom.loom.nodes.spec.NodeMode.PARALLEL;
import static io.metaloom.loom.nodes.spec.ParameterType.BOOLEAN;
import static io.metaloom.loom.nodes.spec.ParameterType.INTEGER;
import static io.metaloom.loom.nodes.spec.ParameterType.STRING;
import static io.metaloom.loom.nodes.spec.PortSpec.one;

import java.util.List;

/**
 * Provides the node descriptor for the asset metadata ingest node.
 */
public class MetadataDescriptorProvider implements NodeDescriptorProvider {

	private static final List<String> STANDARD_EVENTS = List.of(
		"NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED", "NODE_STATS");

	@Override
	public List<NodeDescriptor> getDescriptors() {
		return List.of(
			new NodeDescriptor()
				.setKind("metadata")
				.setName("Asset Metadata")
				.setDescription(
					"Read the metadata already inside a file - EXIF, IPTC, XMP, PDF and Office properties, ID3 - "
						+ "and normalise it onto Dublin Core: title, creator, keywords, date, rights and GPS position.")
				.setIcon("info")
				.setCategory(ANALYSIS)
				.setInputPorts(List.of(
					one("media", MEDIA_ANY)
						.describedAs("Media", "The file to read metadata from")))
				.setOutputPorts(List.of(
					one("metadata", STRUCT_JSON)
						.describedAs("Metadata", "The normalised metadata envelope: Dublin Core, rights, capture settings and position"),
					one("text", TEXT_PLAIN)
						.describedAs("Text", "Title, description, keywords and creator, newline-joined - ready for any text consumer"),
					one("geo", STRUCT_JSON)
						.describedAs("Position", "Latitude and longitude, emitted only when the file carried a coordinate")))
				.setParameters(List.of(
					commonEnabled(), commonProcessIncomplete(), commonRetryFailed(),
					new NodeParameter().setKey("includeRaw").setType(BOOLEAN).setDefaultValue(false)
						.setLabel("Include Raw Metadata")
						.setDescription("Store every key the file carried, unmapped. Off by default: maker notes can carry serial numbers and owner names"),
					new NodeParameter().setKey("rawMaxKeys").setType(INTEGER).setDefaultValue(500)
						.setLabel("Raw Key Limit").setDescription("Maximum number of raw entries stored").setMin(1),
					new NodeParameter().setKey("rawMaxValueBytes").setType(INTEGER).setDefaultValue(4096)
						.setLabel("Raw Value Limit").setDescription("Longer raw values are truncated").setMin(1),
					new NodeParameter().setKey("readXmpSidecar").setType(BOOLEAN).setDefaultValue(true)
						.setLabel("Read XMP Sidecar").setDescription("Merge a companion .xmp file when one sits next to the media"),
					new NodeParameter().setKey("writeGeoComponent").setType(BOOLEAN).setDefaultValue(true)
						.setLabel("Store Position").setDescription("Write the position to the asset's geo component"),
					new NodeParameter().setKey("gpsTrackMaxSamples").setType(INTEGER).setDefaultValue(1000)
						.setLabel("Position Sample Limit").setDescription("Upper bound on the position readings kept per asset").setMin(1),
					new NodeParameter().setKey("gpsPolicy").setType(STRING).setDefaultValue("KEEP")
						.setLabel("Position Policy").setDescription("KEEP the exact coordinate, ROUND it, or DROP it entirely"),
					new NodeParameter().setKey("gpsRoundDecimals").setType(INTEGER).setDefaultValue(2)
						.setLabel("Position Rounding").setDescription("Decimal places kept when rounding; 2 is roughly 1.1 km")
						.setMin(0).setMax(6),
					new NodeParameter().setKey("emitText").setType(BOOLEAN).setDefaultValue(true)
						.setLabel("Emit Text").setDescription("Produce the plain-text output for downstream text nodes"),
					new NodeParameter().setKey("licenseDetection").setType(BOOLEAN).setDefaultValue(true)
						.setLabel("Detect Licence").setDescription("Recognise well-known licence URLs and record their identifier"),
					new NodeParameter().setKey("dateFallback").setType(STRING).setDefaultValue("NONE")
						.setLabel("Date Fallback").setDescription("NONE, or FILESYSTEM to fall back to the file's modification time"),
					new NodeParameter().setKey("excludeKeys").setType(STRING)
						.setLabel("Excluded Keys").setDescription("Metadata keys to drop before anything else reads them")))
				.setDefaultConcurrency(4)
				.setDefaultMode(PARALLEL)
				.setEvents(STANDARD_EVENTS));
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
