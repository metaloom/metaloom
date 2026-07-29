package io.metaloom.cortex.node.sink.s3;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

/**
 * Options for the {@code s3-sink} node.
 *
 * <p>Every field here describes <em>the work</em> rather than the worker, so they are set per
 * pipeline node instance through {@link io.metaloom.cortex.common.node.PipelineConfigurable}; the
 * values below act as fleet-wide defaults a definition layers over. Connection settings -
 * endpoint, region, credentials - are deliberately <b>not</b> here: they live on
 * {@code CortexOptions.getS3()} / {@code CORTEX_S3_*}, because a pipeline definition is stored in
 * Postgres and rendered verbatim in the editor, and the editor's parameter model has no secret
 * type.</p>
 *
 * <pre>
 * nodes:
 *   s3-sink:
 *     enabled: true
 *     bucket: media
 *     keyTemplate: "cortex/{sourceNode}/{sourceKey}/{sha512:4}/{sha512}{ext}"
 *     overwrite: IF_DIFFERENT
 * </pre>
 */
public class S3SinkNodeOptions extends AbstractNodeOptions<S3SinkNodeOptions> {

	public static final String KEY = "s3-sink";

	/**
	 * Content-addressed on the artifact's own hash, so the same bytes always land at the same key.
	 * That is what makes the overwrite check meaningful and a re-run free, and the {@code :4} shard
	 * mirrors the local {@code *_bin} cache layout.
	 */
	public static final String DEFAULT_KEY_TEMPLATE =
		"cortex/{sourceNode}/{sourceKey}/{sha512:4}/{sha512}{ext}";

	public static final int DEFAULT_MAX_ARTIFACTS = 64;

	/** Deliberately permissive: S3 itself is stricter, but a gateway may not be. */
	private static final Pattern BUCKET_PATTERN = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{1,62}");

	private String bucket;

	private String keyTemplate = DEFAULT_KEY_TEMPLATE;

	private boolean includeSource;

	private boolean createAssets = true;

	private OverwritePolicy overwrite = OverwritePolicy.IF_DIFFERENT;

	private boolean deleteAfterUpload;

	private int maxArtifacts = DEFAULT_MAX_ARTIFACTS;

	private long maxArtifactBytes;

	private boolean failOnPartial = true;

	@Override
	protected S3SinkNodeOptions self() {
		return this;
	}

	/**
	 * Destination bucket. Required, but enforced in {@code configure(...)} rather than here - see
	 * {@link #validate()}.
	 */
	public String getBucket() {
		return bucket;
	}

	public S3SinkNodeOptions setBucket(String bucket) {
		this.bucket = bucket;
		return this;
	}

	public String getKeyTemplate() {
		return keyTemplate;
	}

	public S3SinkNodeOptions setKeyTemplate(String keyTemplate) {
		this.keyTemplate = keyTemplate == null || keyTemplate.isBlank() ? DEFAULT_KEY_TEMPLATE : keyTemplate;
		return this;
	}

	/**
	 * Also upload the media item itself, which is what makes {@code filesystem-source → s3-sink}
	 * an archiver. Media that is already an {@code s3://} reference is skipped rather than
	 * round-tripped.
	 */
	public boolean isIncludeSource() {
		return includeSource;
	}

	public S3SinkNodeOptions setIncludeSource(boolean includeSource) {
		this.includeSource = includeSource;
		return this;
	}

	/**
	 * Create a Loom asset for each uploaded artifact. On by default - an artifact that is not an
	 * asset is not retrievable, which is the point of the node. Turn off to use the sink as a
	 * pure uploader.
	 */
	public boolean isCreateAssets() {
		return createAssets;
	}

	public S3SinkNodeOptions setCreateAssets(boolean createAssets) {
		this.createAssets = createAssets;
		return this;
	}

	public OverwritePolicy getOverwrite() {
		return overwrite;
	}

	public S3SinkNodeOptions setOverwrite(OverwritePolicy overwrite) {
		this.overwrite = overwrite == null ? OverwritePolicy.IF_DIFFERENT : overwrite;
		return this;
	}

	/**
	 * Remove the local file once the object is confirmed in the bucket.
	 *
	 * <p>🔴 <b>Off by default, and it must stay that way.</b> {@code SceneLayoutNode} reads
	 * {@code depthmap_path} from the same worker's local cache, so a sink that deleted by default
	 * would break that chain - and it would surface as {@code scene-layout} skipping with "depth
	 * map not found", which looks like a depthmap bug and is not.</p>
	 */
	public boolean isDeleteAfterUpload() {
		return deleteAfterUpload;
	}

	public S3SinkNodeOptions setDeleteAfterUpload(boolean deleteAfterUpload) {
		this.deleteAfterUpload = deleteAfterUpload;
		return this;
	}

	/**
	 * Ceiling on artifacts per media item. Exceeding it fails the node rather than truncating: a
	 * script emitting thousands of images is a bug, and a truncating sink would hide it.
	 */
	public int getMaxArtifacts() {
		return maxArtifacts;
	}

	public S3SinkNodeOptions setMaxArtifacts(int maxArtifacts) {
		this.maxArtifacts = maxArtifacts;
		return this;
	}

	/**
	 * @return per-file size cap in bytes, or 0 for unbounded
	 */
	public long getMaxArtifactBytes() {
		return maxArtifactBytes;
	}

	public S3SinkNodeOptions setMaxArtifactBytes(long maxArtifactBytes) {
		this.maxArtifactBytes = maxArtifactBytes;
		return this;
	}

	/**
	 * Report the node FAILED when some artifacts could not be uploaded. On by default: a sink that
	 * silently loses two of five artifacts is worse than a red node.
	 */
	public boolean isFailOnPartial() {
		return failOnPartial;
	}

	public S3SinkNodeOptions setFailOnPartial(boolean failOnPartial) {
		this.failOnPartial = failOnPartial;
		return this;
	}

	/**
	 * Shape validation only.
	 *
	 * <p>⚠️ Deliberately does <b>not</b> require {@code bucket}. {@code RegistryNodeRegistrar.adapt}
	 * validates the <em>worker's</em> options for every node it builds, and the bucket belongs on
	 * the node definition - so requiring it here would make every {@code s3-sink} in every pipeline
	 * fail to build with a misleading message. The effective bucket is required in
	 * {@code configure(...)} instead, which is the same split {@code ScriptNode} uses for its
	 * script.</p>
	 */
	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>();
		errors.addAll(validateCommon());

		if (bucket != null && !bucket.isBlank() && !BUCKET_PATTERN.matcher(bucket).matches()) {
			errors.add("bucket '" + bucket + "' is not a valid S3 bucket name");
		}

		if (keyTemplate == null || keyTemplate.isBlank()) {
			errors.add("keyTemplate must not be empty");
		} else {
			try {
				S3KeyTemplate.parse(keyTemplate);
			} catch (IllegalArgumentException e) {
				errors.add("keyTemplate: " + e.getMessage());
			}
		}

		if (maxArtifacts <= 0) {
			errors.add("maxArtifacts must be positive, got " + maxArtifacts);
		}
		if (maxArtifactBytes < 0) {
			errors.add("maxArtifactBytes must be non-negative, got " + maxArtifactBytes);
		}

		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
