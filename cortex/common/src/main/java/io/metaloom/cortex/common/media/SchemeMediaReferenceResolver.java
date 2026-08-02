package io.metaloom.cortex.common.media;

import java.util.List;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.loom.pipeline.model.MediaRef;

/**
 * A {@link MediaReferenceResolver} that routes a reference to whichever remote-media resolver
 * claims its scheme, and falls back to a local path.
 *
 * <p>Before this existed there was exactly one remote scheme ({@code s3://}) and the choice was an
 * {@code if/else} inside the Dagger provider. A second and third scheme make that untenable: the
 * provider would have to know every remote module, and the ordering of the checks would become
 * load-bearing in a place nobody looks.</p>
 *
 * <p>The fallback is the important part. A worker with no remote configuration at all does not get
 * this class - it gets a plain {@link MediaReferenceResolver}, exactly as before - and a reference
 * whose scheme no branch claims is treated as a path, which is what an unrecognised reference has
 * always meant.</p>
 */
public class SchemeMediaReferenceResolver extends MediaReferenceResolver {

	/**
	 * A resolver that handles one family of references and declines the rest.
	 */
	public interface SchemeResolver {

		/**
		 * @param reference any media reference
		 * @return true when this resolver owns the reference's scheme
		 */
		boolean handles(String reference);

		/**
		 * @param ref the reference, carrying whatever the engine already knows about it
		 * @return the media handle
		 */
		LoomMedia resolve(MediaRef ref);
	}

	private final List<SchemeResolver> branches;

	public SchemeMediaReferenceResolver(LoomMediaLoader mediaLoader, List<SchemeResolver> branches) {
		super(mediaLoader);
		if (branches == null || branches.isEmpty()) {
			throw new IllegalArgumentException(
				"A scheme resolver needs at least one branch; use a plain MediaReferenceResolver otherwise");
		}
		this.branches = List.copyOf(branches);
	}

	/**
	 * Resolve a bare reference.
	 *
	 * <p>The {@code -1} size reproduces what each branch previously saw from its own
	 * {@code resolve(String)}: nothing is known about the size, so a size-based guard cannot fire
	 * and the branch has to ask the provider.</p>
	 */
	@Override
	public LoomMedia resolve(String reference) {
		return resolve(new MediaRef(reference, null, -1));
	}

	@Override
	public LoomMedia resolve(MediaRef ref) {
		String reference = ref.getPath();
		for (SchemeResolver branch : branches) {
			if (branch.handles(reference)) {
				return branch.resolve(ref);
			}
		}
		return resolveLocal(reference);
	}

	/**
	 * @return the branches, in the order they are consulted
	 */
	public List<SchemeResolver> branches() {
		return branches;
	}
}
