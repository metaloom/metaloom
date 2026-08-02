package io.metaloom.cortex.cloud;

import java.io.IOException;
import java.io.UncheckedIOException;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.common.media.LoomMediaLoader;
import io.metaloom.cortex.common.media.MediaReferenceResolver;
import io.metaloom.cortex.common.media.SchemeMediaReferenceResolver;
import io.metaloom.loom.pipeline.model.MediaRef;

/**
 * A {@link MediaReferenceResolver} that also understands {@code gdrive://} and
 * {@code onedrive://} references.
 *
 * <p>This is what removes the shared-storage prerequisite for cloud media, exactly as
 * {@code S3MediaReferenceResolver} does for object storage: the source node emits references
 * without downloading anything, and whichever worker later receives the node task resolves them
 * here and materializes into its own cache.</p>
 *
 * <p>One resolver serves every provider, dispatching on the reference's scheme through the
 * {@link CloudSupportRegistry}. A reference for a provider this worker has no credentials for
 * falls through to the superclass rather than throwing - the same shape as any other unrecognised
 * reference, and the failure then surfaces as "file not found" at the node rather than as a
 * transport error in the runner.</p>
 */
public class CloudMediaReferenceResolver extends MediaReferenceResolver
	implements SchemeMediaReferenceResolver.SchemeResolver {

	private final CloudSupportRegistry registry;

	public CloudMediaReferenceResolver(LoomMediaLoader mediaLoader, CloudSupportRegistry registry) {
		super(mediaLoader);
		if (registry == null) {
			throw new IllegalArgumentException("A cloud support registry must be provided");
		}
		this.registry = registry;
	}

	@Override
	public boolean handles(String reference) {
		CloudProviderId provider = CloudUri.providerOf(reference);
		return provider != null && registry.isActive(provider);
	}

	@Override
	public LoomMedia resolve(String reference) {
		return resolve(reference, -1);
	}

	/**
	 * Uses the size the engine already knows to reject an over-size file before any network call.
	 * The change token that keys the cache still has to come from a metadata read, because a
	 * {@code MediaRef} does not carry one and serving a cached copy without checking would risk
	 * handing a node stale bytes.
	 */
	@Override
	public LoomMedia resolve(MediaRef mediaRef) {
		return resolve(mediaRef.getPath(), mediaRef.getSize());
	}

	private LoomMedia resolve(String reference, long knownSize) {
		CloudProviderId provider = CloudUri.providerOf(reference);
		if (provider == null || !registry.isActive(provider)) {
			return super.resolve(reference);
		}
		CloudUri uri = CloudUri.parse(reference);
		CloudMediaMaterializer materializer = registry.get(provider).materializer();

		long maxObjectSize = materializer.maxObjectSize();
		if (maxObjectSize > 0 && knownSize > maxObjectSize) {
			throw new IllegalStateException("File " + reference + " is " + knownSize
				+ " bytes which exceeds the configured maxObjectSize of " + maxObjectSize);
		}

		CloudFileRef ref;
		try {
			ref = materializer.store().get(uri.driveId(), uri.fileId());
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to resolve " + reference, e);
		}
		if (ref == null) {
			// The file vanished between enumeration and execution. Hand back a handle anyway so the
			// node reports a normal "file not found" rather than the worker failing the task with
			// an unrelated transport error.
			ref = CloudFileRef.absent(uri);
		}
		return new CloudLoomMedia(ref, materializer);
	}
}
