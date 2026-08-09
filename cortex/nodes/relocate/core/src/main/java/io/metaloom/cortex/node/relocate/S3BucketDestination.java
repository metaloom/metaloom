package io.metaloom.cortex.node.relocate;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.s3.S3ObjectRef;
import io.metaloom.cortex.s3.S3Support;
import io.metaloom.loom.client.common.LoomClient;

/**
 * A bucket named directly on the node, which need not correspond to any pool row.
 *
 * <p>
 * The cold-tier case: it is the one target that works when the worker shares no filesystem with anything. Connection settings - endpoint, region,
 * credentials - are deliberately not node options; they live on {@code CortexOptions.getS3()} / {@code CORTEX_S3_*}, because a pipeline definition is
 * stored in Postgres and rendered verbatim in the editor, and the editor's parameter model has no secret type. Only the bucket is per-node.
 * </p>
 */
@Singleton
public class S3BucketDestination implements MoveDestination {

	private final S3Support s3;

	@Inject
	public S3BucketDestination(S3Support s3) {
		this.s3 = s3;
	}

	@Override
	public MoveTarget target() {
		return MoveTarget.S3_BUCKET;
	}

	@Override
	public List<String> validate(MoveNodeOptions options) {
		if (PoolDestination.isBlank(options.getBucket())) {
			return List.of("the S3_BUCKET target needs a 'bucket'");
		}
		return List.of();
	}

	@Override
	public MovePlan plan(LoomClient client, LoomMedia media, MoveNodeOptions options) throws Exception {
		if (!s3.isActive()) {
			// Fail rather than skip, exactly as s3-sink does: skipping would be silent data loss on a run
			// that reported green, and the fix is a worker configuration the operator has to make.
			throw new IllegalStateException(
				"This worker has no S3 configuration; set the CORTEX_S3_* options before using the S3_BUCKET target");
		}

		String bucket = options.getBucket().trim();
		String key = Layouts.contentKey(media);

		// Idempotency, without downloading anything: the key is derived from the content hash, so an object
		// of the same size already sitting at that key is the same object.
		S3ObjectRef existing = s3.store().head(bucket, key);
		MovePlan plan = MovePlan.s3(bucket, key, null, null);
		if (existing != null && existing.size() == media.size()) {
			return plan.asAlreadyThere();
		}
		return plan;
	}
}
