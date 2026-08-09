package io.metaloom.cortex.node.relocate;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Where the bytes belong, decided without touching anything.
 *
 * <p>
 * Planning is deliberately a separate, read-only step. It is what {@code dryRun} reports, what the idempotency check asks, and what makes a
 * cross-device copy a decision rather than a discovery halfway through. A plan creates no directories and writes no bytes.
 * </p>
 *
 * @param kind
 *            filesystem or bucket - the two things the node knows how to write to
 * @param targetPath
 *            the destination path, for {@link Kind#FILESYSTEM}
 * @param bucket
 *            the destination bucket, for {@link Kind#S3}
 * @param key
 *            the destination object key, for {@link Kind#S3}
 * @param alreadyThere
 *            the bytes are already at the destination, so there is nothing to do
 * @param libraryUuid
 *            the library to re-point the binary at, or null to leave it
 * @param poolUuid
 *            the pool to record on the binary, or null to leave it
 */
public record MovePlan(Kind kind, Path targetPath, String bucket, String key, boolean alreadyThere, UUID libraryUuid, UUID poolUuid) {

	public enum Kind {
		FILESYSTEM,
		S3
	}

	public static MovePlan filesystem(Path targetPath, UUID libraryUuid, UUID poolUuid) {
		return new MovePlan(Kind.FILESYSTEM, targetPath, null, null, false, libraryUuid, poolUuid);
	}

	public static MovePlan s3(String bucket, String key, UUID libraryUuid, UUID poolUuid) {
		return new MovePlan(Kind.S3, null, bucket, key, false, libraryUuid, poolUuid);
	}

	public MovePlan asAlreadyThere() {
		return new MovePlan(kind, targetPath, bucket, key, true, libraryUuid, poolUuid);
	}

	/**
	 * The locator to record on the binary and to emit on the {@code path} port.
	 *
	 * <p>
	 * The {@code s3://bucket/key} form is a contract with Loom, not a display convenience: {@code S3Locator} on the server and {@code S3Uri} on the
	 * worker both parse exactly this, and a bare object key would make the asset unresolvable.
	 * </p>
	 */
	public String locator() {
		return kind == Kind.S3 ? "s3://" + bucket + "/" + key : targetPath.toAbsolutePath().toString();
	}
}
