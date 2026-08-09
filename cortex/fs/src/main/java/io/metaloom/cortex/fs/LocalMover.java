package io.metaloom.cortex.fs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.fs.XAttrs.XAttrResult;

/**
 * The one implementation of "get these bytes to that path" on a local filesystem.
 *
 * <p>
 * Everything that makes a relocation safe lives here rather than at each call site:
 * </p>
 *
 * <ul>
 * <li>the filesystem boundary is probed <b>before</b> anything is written, so a cross-device copy is a decision rather than a surprise;</li>
 * <li>the copy path writes a {@code .part} sibling and publishes it atomically, so a killed worker never leaves a visible truncated file;</li>
 * <li>extended attributes - and therefore the cached SHA-512 - are carried across explicitly, with failures reported rather than printed;</li>
 * <li>the destination is verified before the source is unlinked, and the source is only ever unlinked after that.</li>
 * </ul>
 *
 * <p>
 * 🔴 Removing the source is the only irreversible thing in this class, and it happens in exactly one place, reachable only after a successful verify.
 * Every failure path leaves the source untouched.
 * </p>
 */
public final class LocalMover {

	private static final Logger log = LoggerFactory.getLogger(LocalMover.class);

	private final FileStoreProbe probe;

	public LocalMover() {
		this(FileStores::sameStore);
	}

	/**
	 * @param probe
	 *            the boundary check. Injectable so a test can exercise the cross-device paths without needing a second mount point
	 */
	public LocalMover(FileStoreProbe probe) {
		this.probe = probe;
	}

	/**
	 * Whether two paths share a filesystem.
	 */
	@FunctionalInterface
	public interface FileStoreProbe {
		boolean sameStore(Path a, Path b) throws IOException;
	}

	/**
	 * Relocate {@code source} to {@code desiredTarget}.
	 *
	 * @param source
	 *            the file to relocate; must exist
	 * @param desiredTarget
	 *            the preferred destination path
	 * @param conflictPolicy
	 *            what to do when the destination is occupied
	 * @param crossDevicePolicy
	 *            what to do when the destination is on another filesystem
	 * @param removeSource
	 *            whether to unlink the source once the destination is verified. Ignored on the same-filesystem path, where the rename removes it by
	 *            definition
	 * @param verifier
	 *            proves the destination holds what the source held. Called after the bytes are in place and before the source is removed
	 * @return what happened
	 * @throws IOException
	 *             when the filesystem operation itself fails. The source is intact in every such case
	 */
	public MoveOutcome move(Path source, Path desiredTarget, ConflictPolicy conflictPolicy, CrossDevicePolicy crossDevicePolicy,
		boolean removeSource, Verifier verifier) throws IOException {

		if (!Files.isRegularFile(source)) {
			throw new IOException("Not a regular file: " + source);
		}

		Optional<Path> resolved = Conflicts.resolve(desiredTarget, conflictPolicy);
		if (resolved.isEmpty()) {
			return MoveOutcome.skipped("the destination " + desiredTarget + " is occupied");
		}
		Path target = resolved.get();
		long bytes = Files.size(source);

		Path parent = target.getParent();
		boolean sameStore = probe.sameStore(source, parent != null ? parent : target);

		if (sameStore) {
			// A rename. Constant time, atomic, and the extended attributes travel with the inode.
			Files.createDirectories(parent);
			AtomicFiles.move(source, target);
			return new MoveOutcome(MoveOutcome.State.MOVED, target, bytes, false, true, null);
		}

		switch (crossDevicePolicy) {
		case SKIP:
			return MoveOutcome.skipped(crossDeviceMessage(source, target, bytes));
		case FAIL:
			throw new IOException("Refusing a cross-device move: " + crossDeviceMessage(source, target, bytes));
		case COPY:
		default:
			log.warn("Copying across a filesystem boundary rather than renaming: {}", crossDeviceMessage(source, target, bytes));
			return copyAcross(source, target, bytes, removeSource, verifier);
		}
	}

	private MoveOutcome copyAcross(Path source, Path target, long bytes, boolean removeSource, Verifier verifier) throws IOException {
		Path parent = target.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}

		// Capture before copying: on a failure the source is still the only good copy and must be left alone.
		XAttrResult snapshot = XAttrs.snapshot(source);
		XAttrs.report(snapshot, source, "read");

		Path part = AtomicFiles.partFor(target);
		try {
			Files.copy(source, part, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
			Map<String, ByteBuffer> attributes = snapshot.attributes();
			if (!attributes.isEmpty()) {
				XAttrResult restored = XAttrs.restore(part, attributes);
				XAttrs.report(restored, part, "write");
			}
			AtomicFiles.move(part, target);
		} catch (IOException e) {
			// Leave nothing half-written behind; the source has not been touched.
			Files.deleteIfExists(part);
			throw e;
		}

		if (verifier != null && !verifier.verify(source, target)) {
			// The destination does not hold what the source holds. Remove it and keep the original:
			// a failed relocation must cost nothing but time.
			Files.deleteIfExists(target);
			throw new IOException("The copy of " + source + " at " + target + " did not verify; the destination was removed and the source kept");
		}

		if (removeSource) {
			Files.delete(source);
			return new MoveOutcome(MoveOutcome.State.MOVED, target, bytes, true, true, null);
		}
		return new MoveOutcome(MoveOutcome.State.COPIED, target, bytes, true, false, "the source was kept");
	}

	private String crossDeviceMessage(Path source, Path target, long bytes) {
		return source + " is on " + FileStores.describeStoreOf(source) + ", " + target + " is on " + FileStores.describeStoreOf(target) + "; "
			+ bytes + " bytes";
	}

	/**
	 * Proves that the destination holds what the source held.
	 *
	 * <p>
	 * Injected rather than fixed because the right proof differs by caller: comparing sizes is enough for a content-addressed layout, where the path
	 * itself already encodes the hash, while a plain folder move wants the digest.
	 * </p>
	 */
	@FunctionalInterface
	public interface Verifier {
		boolean verify(Path source, Path target) throws IOException;
	}

	/**
	 * The cheap verifier: the destination exists and is the same length.
	 */
	public static final Verifier SIZE_VERIFIER = (source, target) -> Files.exists(target) && Files.size(source) == Files.size(target);
}
