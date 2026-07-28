package io.metaloom.cortex.common.media;

import java.nio.file.Paths;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.loom.pipeline.model.MediaRef;

/**
 * Turns a media <em>reference</em> back into a handle.
 *
 * <p>The counterpart of {@link io.metaloom.cortex.api.media.ProcessableMedia#reference()}: a
 * source node emits references, they travel to another worker inside a {@code MediaRef}, and that
 * worker resolves them here. Splitting resolution out of {@code LoomMediaLoader} is what lets a
 * reference be something other than a local path - {@code LoomMediaLoader.load} takes a
 * {@link java.nio.file.Path}, and a URI cannot be expressed as one
 * ({@code Paths.get("s3://b/k")} collapses to {@code s3:/b/k}).</p>
 *
 * <p>This default implementation handles the only case that existed before remote media: a local
 * filesystem path. The S3-aware implementation lives in {@code cortex-s3-common} and delegates
 * here for anything that is not an {@code s3://} URI, so a worker with no S3 configuration
 * behaves exactly as it always did.</p>
 */
@Singleton
public class MediaReferenceResolver {

	private final LoomMediaLoader mediaLoader;

	@Inject
	public MediaReferenceResolver(LoomMediaLoader mediaLoader) {
		this.mediaLoader = mediaLoader;
	}

	/**
	 * @param reference the media reference, e.g. an absolute path
	 * @return the media handle
	 */
	public LoomMedia resolve(String reference) {
		return mediaLoader.load(Paths.get(reference));
	}

	/**
	 * Resolve a reference the engine has extra information about.
	 *
	 * <p>The size and sha512 on a {@link MediaRef} are already known to the engine, and a resolver
	 * for remote media can use them - to reject an oversized object before opening a connection,
	 * for instance. This default ignores them because a local path needs no such help.</p>
	 *
	 * @param ref the media reference carrying whatever the engine already knows
	 * @return the media handle
	 */
	public LoomMedia resolve(MediaRef ref) {
		return resolve(ref.getPath());
	}

	/**
	 * @return the loader this resolver builds filesystem media through
	 */
	protected LoomMediaLoader mediaLoader() {
		return mediaLoader;
	}
}
