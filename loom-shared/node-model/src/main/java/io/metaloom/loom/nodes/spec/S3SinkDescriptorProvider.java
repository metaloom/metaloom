package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.*;
import static io.metaloom.loom.nodes.spec.NodeCategory.*;
import static io.metaloom.loom.nodes.spec.NodeMode.*;
import static io.metaloom.loom.nodes.spec.ParameterType.*;
import static io.metaloom.loom.nodes.spec.PortSpec.many;
import static io.metaloom.loom.nodes.spec.PortSpec.one;

import java.util.List;

/**
 * Provides the node descriptor for the S3 sink output node.
 */
public class S3SinkDescriptorProvider implements NodeDescriptorProvider {

	private static final List<String> STANDARD_EVENTS = List.of(
		"NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED", "NODE_STATS");

	/** Must match {@code S3SinkNodeOptions.DEFAULT_KEY_TEMPLATE}. */
	private static final String DEFAULT_KEY_TEMPLATE =
		"cortex/{sourceNode}/{sourceKey}/{sha512:4}/{sha512}{ext}";

	@Override
	public List<NodeDescriptor> getDescriptors() {
		return List.of(
			new NodeDescriptor()
				.setNodeId("s3-sink")
				.setName("S3 Sink")
				.setDescription("Uploads files produced by upstream nodes - thumbnails, depth maps, generated "
					+ "images, speech audio - into an S3 bucket, and registers each one in Loom as an asset "
					+ "so it becomes retrievable rather than living on a single worker's disk.")
				.setIcon("cloud_upload")
				.setCategory(OUTPUT)
				.setInputPorts(List.of(
					many("artifacts", ARTIFACT_ANY)
						.describedAs("Artifacts", "Every produced file to upload - thumbnails, depth maps, generated images, speech audio")))
				.setOutputPorts(List.of(
					one("result", STRUCT_JSON)
						.describedAs("Upload Report", "Per artifact: the object key, its size and whether it was uploaded or skipped as unchanged"),
					one("count", SCALAR_INTEGER)
						.describedAs("Uploaded Count", "How many objects ended up in the bucket"),
					one("flag", SCALAR_STRING)
						.describedAs("Flag", "Processing marker recording how this node finished for the item")))
				.setParameters(List.of(
					commonEnabled(),
					new NodeParameter().setKey("bucket").setType(STRING).setLabel("Bucket")
						.setDescription("Destination bucket. Endpoint, region and credentials are configured "
							+ "on the worker, not here, so they are never stored in the pipeline"),
					new NodeParameter().setKey("keyTemplate").setType(STRING)
						.setDefaultValue(DEFAULT_KEY_TEMPLATE).setLabel("Key template")
						.setDescription("Object key. Placeholders: {sha512}, {sha512:N}, {sourceSha512}, "
							+ "{nodeId}, {sourceNode}, {sourceKey}, {ext}, {filename}, {basename}, "
							+ "{assetUuid}, {index}, {indexSuffix}. Using {sha512} requires an upstream "
							+ "hash node"),
					new NodeParameter().setKey("includeSource").setType(BOOLEAN).setDefaultValue(false)
						.setLabel("Include source media")
						.setDescription("Also upload the media item itself, which turns this node into an "
							+ "archiver. Media that already lives in a bucket is not re-uploaded"),
					new NodeParameter().setKey("createAssets").setType(BOOLEAN).setDefaultValue(true)
						.setLabel("Create assets")
						.setDescription("Register each uploaded file in Loom as its own asset. Turn off to "
							+ "use this node as a pure uploader"),
					new NodeParameter().setKey("overwrite").setType(ENUM)
						.setValues(List.of("NEVER", "IF_DIFFERENT", "ALWAYS"))
						.setDefaultValue("IF_DIFFERENT").setLabel("Overwrite")
						.setDescription("IF_DIFFERENT skips an object already present at the same key with "
							+ "the same size, which is what makes a re-run cheap"),
					new NodeParameter().setKey("deleteAfterUpload").setType(BOOLEAN).setDefaultValue(false)
						.setLabel("Delete after upload")
						.setDescription("Remove the local file once the object is confirmed in the bucket. "
							+ "Off by default: nodes such as Scene Layout read the depth map from the same "
							+ "worker's local cache, and deleting it breaks that chain with a misleading "
							+ "'depth map not found' skip"),
					new NodeParameter().setKey("maxArtifacts").setType(INTEGER).setDefaultValue(64).setMin(1)
						.setLabel("Max artifacts")
						.setDescription("Cap per media item. Exceeding it fails the node rather than "
							+ "silently uploading only some"),
					new NodeParameter().setKey("maxArtifactBytes").setType(INTEGER).setDefaultValue(0).setMin(0)
						.setLabel("Max artifact bytes")
						.setDescription("Per-file size cap in bytes; 0 is unlimited"),
					new NodeParameter().setKey("failOnPartial").setType(BOOLEAN).setDefaultValue(true)
						.setLabel("Fail on partial")
						.setDescription("Report the node failed when some artifacts could not be uploaded")))
				.setDefaultConcurrency(1)
				.setDefaultMode(PARALLEL)
				.setDefaultBlocking(false)
				.setEvents(STANDARD_EVENTS)
		);
	}

	private static NodeParameter commonEnabled() {
		return new NodeParameter().setKey("enabled").setType(BOOLEAN).setDefaultValue(true)
			.setLabel("Enabled").setDescription("Whether this node is active in the pipeline");
	}
}
