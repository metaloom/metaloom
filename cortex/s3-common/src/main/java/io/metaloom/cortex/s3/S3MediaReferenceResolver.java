package io.metaloom.cortex.s3;

import java.io.IOException;
import java.io.UncheckedIOException;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.common.media.LoomMediaLoader;
import io.metaloom.cortex.common.media.MediaReferenceResolver;
import io.metaloom.loom.pipeline.model.MediaRef;

/**
 * A {@link MediaReferenceResolver} that also understands {@code s3://} references.
 *
 * <p>This is the piece that removes the shared-storage prerequisite recorded in
 * {@code MediaRef}'s Javadoc. A source node emits {@code s3://bucket/key} without downloading
 * anything; whichever worker later receives the node task resolves it here and materializes into
 * its own cache. Two workers with no common mount can process the same object.</p>
 *
 * <p>Anything that is not an S3 URI falls through to the superclass unchanged, so a mixed
 * pipeline - filesystem source here, S3 source there - needs no special handling.</p>
 */
public class S3MediaReferenceResolver extends MediaReferenceResolver {

	private final S3MediaMaterializer materializer;

	public S3MediaReferenceResolver(LoomMediaLoader mediaLoader, S3MediaMaterializer materializer) {
		super(mediaLoader);
		if (materializer == null) {
			throw new IllegalArgumentException("A materializer must be provided");
		}
		this.materializer = materializer;
	}

	@Override
	public LoomMedia resolve(String reference) {
		return resolve(reference, -1);
	}

	/**
	 * Uses the size the engine already knows to reject an oversized object before any network call.
	 * Everything else - in particular the ETag that keys the cache - still has to come from a HEAD,
	 * because {@code MediaRef} does not carry one and serving a cached copy without checking it
	 * would risk handing a node stale bytes.
	 */
	@Override
	public LoomMedia resolve(MediaRef mediaRef) {
		return resolve(mediaRef.getPath(), mediaRef.getSize());
	}

	private LoomMedia resolve(String reference, long knownSize) {
		if (!S3Uri.isS3(reference)) {
			return super.resolve(reference);
		}
		S3Uri uri = S3Uri.parse(reference);

		long maxObjectSize = materializer.maxObjectSize();
		if (maxObjectSize > 0 && knownSize > maxObjectSize) {
			throw new IllegalStateException("Object " + reference + " is " + knownSize
				+ " bytes which exceeds the configured maxObjectSize of " + maxObjectSize);
		}

		S3ObjectRef ref;
		try {
			ref = materializer.store().head(uri.bucket(), uri.key());
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to resolve " + reference, e);
		}
		if (ref == null) {
			// The object vanished between enumeration and execution. Hand back a handle anyway so
			// the node reports a normal "file not found" rather than the worker failing the task
			// with an unrelated transport error.
			ref = new S3ObjectRef(uri.bucket(), uri.key(), null, -1, 0);
		}
		return new S3LoomMedia(ref, materializer);
	}
}
