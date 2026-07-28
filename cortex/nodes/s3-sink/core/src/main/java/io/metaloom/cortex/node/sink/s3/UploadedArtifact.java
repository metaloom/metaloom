package io.metaloom.cortex.node.sink.s3;

import io.vertx.core.json.JsonObject;

/**
 * What became of one artifact, recorded on the source asset so a partial run is diagnosable from
 * Loom rather than from a log grep.
 */
public class UploadedArtifact {

	/** Outcome of a single artifact. */
	public enum State {
		/** Bytes were transferred in this run. */
		UPLOADED,
		/** An object was already at that key with the same size, so the upload was skipped. */
		PRESENT,
		/** The upload or the asset creation failed. */
		FAILED,
		/**
		 * The producing node reported a path but the file is not on this worker. Almost always an
		 * affinity problem - see {@link S3SinkNode}.
		 */
		MISSING
	}

	private final SinkArtifact artifact;
	private State state;
	private String bucket;
	private String key;
	private String etag;
	private long size = -1;
	private String contentType;
	private String assetUuid;
	private String error;
	private boolean deleted;

	public UploadedArtifact(SinkArtifact artifact, State state) {
		this.artifact = artifact;
		this.state = state;
	}

	public SinkArtifact artifact() {
		return artifact;
	}

	public State state() {
		return state;
	}

	public UploadedArtifact state(State state) {
		this.state = state;
		return this;
	}

	public UploadedArtifact location(String bucket, String key) {
		this.bucket = bucket;
		this.key = key;
		return this;
	}

	public UploadedArtifact stored(String etag, long size, String contentType) {
		this.etag = etag;
		this.size = size;
		this.contentType = contentType;
		return this;
	}

	public UploadedArtifact asset(String assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	public UploadedArtifact error(String error) {
		this.error = error;
		return this;
	}

	public UploadedArtifact deleted(boolean deleted) {
		this.deleted = deleted;
		return this;
	}

	public String key() {
		return key;
	}

	public String uri() {
		return bucket == null || key == null ? null : "s3://" + bucket + "/" + key;
	}

	/**
	 * @return true when the bytes are in the bucket, whether this run put them there or found them
	 */
	public boolean isStored() {
		return state == State.UPLOADED || state == State.PRESENT;
	}

	/**
	 * @return the entry recorded in the {@code s3-artifact} component payload
	 */
	public JsonObject toJson() {
		JsonObject json = new JsonObject()
			.put("state", state.name())
			.put("sourceNode", artifact.sourceNode())
			.put("sourceKey", artifact.sourceKey())
			.put("index", artifact.index())
			.put("localFile", artifact.file().toString());
		if (uri() != null) {
			json.put("uri", uri()).put("bucket", bucket).put("key", key);
		}
		if (etag != null) {
			json.put("etag", etag);
		}
		if (size >= 0) {
			json.put("size", size);
		}
		if (contentType != null) {
			json.put("contentType", contentType);
		}
		if (assetUuid != null) {
			json.put("assetUuid", assetUuid);
		}
		if (error != null) {
			json.put("error", error);
		}
		if (deleted) {
			json.put("deleted", true);
		}
		return json;
	}
}
