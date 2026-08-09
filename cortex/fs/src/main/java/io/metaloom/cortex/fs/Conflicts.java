package io.metaloom.cortex.fs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Choosing where a file may actually land when its preferred destination is taken.
 *
 * <p>
 * The numbering is the same shape as {@code io.metaloom.utils.fs.FileUtils.autoRotate}, which both dedup nodes already relied on, but expressed on
 * {@link Path}, returning an empty optional for {@link ConflictPolicy#SKIP} rather than encoding "nothing to do" as an exception, and naming the
 * ceiling when it is reached.
 * </p>
 */
public final class Conflicts {

	/**
	 * How many numbered alternatives to try before giving up.
	 *
	 * <p>
	 * A thousand files with the same name in one folder is not a naming collision any more, it is a loop or a misconfiguration, and continuing to
	 * probe would hide it.
	 * </p>
	 */
	public static final int MAX_ATTEMPTS = 1024;

	private Conflicts() {
	}

	/**
	 * Resolve {@code desired} against whatever is already on disk.
	 *
	 * @param desired
	 *            the preferred destination
	 * @param policy
	 *            what to do when it is occupied
	 * @return the path to write, or empty when the policy says to skip
	 * @throws ConflictException
	 *             when the policy is {@link ConflictPolicy#FAIL}, or when SUFFIX ran out of attempts
	 */
	public static Optional<Path> resolve(Path desired, ConflictPolicy policy) {
		if (!Files.exists(desired)) {
			return Optional.of(desired);
		}
		switch (policy) {
		case SKIP:
			return Optional.empty();
		case FAIL:
			throw new ConflictException("The destination " + desired + " already exists");
		case SUFFIX:
		default:
			return Optional.of(rotate(desired));
		}
	}

	private static Path rotate(Path desired) {
		String name = desired.getFileName().toString();
		int dot = name.lastIndexOf('.');
		String stem = dot > 0 ? name.substring(0, dot) : name;
		String extension = dot > 0 ? name.substring(dot) : "";

		for (int i = 1; i < MAX_ATTEMPTS; i++) {
			Path candidate = desired.resolveSibling(stem + "_" + i + extension);
			if (!Files.exists(candidate)) {
				return candidate;
			}
		}
		throw new ConflictException(
			"Gave up finding a free name for " + desired + " after " + MAX_ATTEMPTS + " attempts; the target folder already holds that many");
	}

	/**
	 * A destination could not be chosen. Distinct from an IO failure: nothing was attempted.
	 */
	public static class ConflictException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		public ConflictException(String message) {
			super(message);
		}
	}
}
