package io.metaloom.cortex.api.node.artifact.impl;

import java.util.Optional;

import io.metaloom.cortex.api.node.artifact.Artifact;
import io.metaloom.cortex.api.node.artifact.ArtifactCache;
import io.metaloom.cortex.api.node.artifact.ArtifactException;
import io.metaloom.cortex.api.node.artifact.ArtifactFactory;
import io.metaloom.cortex.api.node.artifact.ArtifactKey;

/**
 * Computes through, retains nothing.
 *
 * <p>
 * See {@link ArtifactCache#noop()} for why this is the default rather than a throwing stub: a node written against the artifact API has to keep
 * working when nobody opened a scope for it.
 * </p>
 */
public final class NoOpArtifactCache implements ArtifactCache {

	public static final NoOpArtifactCache INSTANCE = new NoOpArtifactCache();

	private static final Publication NO_PUBLICATION = new Publication() {

		@Override
		public void commit() {
		}

		@Override
		public void close() {
		}
	};

	private NoOpArtifactCache() {
	}

	@Override
	public <T> T get(ArtifactKey<T> key, ArtifactFactory<T> factory) {
		try {
			Artifact<T> artifact = factory.create();
			if (artifact == null) {
				throw new ArtifactException("Factory for artifact " + key + " returned null");
			}
			return artifact.value();
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new ArtifactException("Could not produce artifact " + key, e);
		}
	}

	@Override
	public <T> Optional<T> peek(ArtifactKey<T> key) {
		return Optional.empty();
	}

	@Override
	public void invalidate(ArtifactKey<?> key) {
	}

	@Override
	public long retainedBytes() {
		return 0;
	}

	@Override
	public long maxBytes() {
		return Long.MAX_VALUE;
	}

	@Override
	public Publication beginPublication() {
		return NO_PUBLICATION;
	}

	@Override
	public void close() {
	}
}
