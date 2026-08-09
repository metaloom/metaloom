package io.metaloom.cortex.node.relocate;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.api.node.spec.ParamDoc;
import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

/**
 * Per-instance configuration for the {@code move} node.
 *
 * <p>
 * Everything here describes <b>the work</b> rather than the worker - two move nodes in one graph legitimately send different items to different
 * places - so all of it arrives through {@code PipelineConfigurable.configure(...)} on the node definition. Worker YAML under the {@code move} key
 * still supplies fleet-wide defaults.
 * </p>
 *
 * <p>
 * ⚠️ Per-target requirements (a bucket for {@code S3_BUCKET}, a uuid for {@code LIBRARY}) are enforced in {@code configure}, not here.
 * {@code RegistryNodeRegistrar} validates the worker's options for every node it builds, so a rule expressed in {@link #validate()} would reject a
 * worker that merely has the kind available.
 * </p>
 */
public class MoveNodeOptions extends AbstractNodeOptions<MoveNodeOptions> {

	public static final String KEY = "move";

	private static final String DEFAULT_TARGET_FOLDER = "trash";

	@ParamDoc(label = "Target", description = "Where the bytes go: a folder on this worker, a storage pool, a library's pool, or an S3 bucket", order = 10)
	private MoveTarget target = MoveTarget.FOLDER;

	@ParamDoc(label = "Target Folder", description = "Destination root for the FOLDER target. A relative path resolves against the source root", order = 20)
	private Path targetFolder = Paths.get(DEFAULT_TARGET_FOLDER);

	@ParamDoc(label = "Source Root", description = "The scan root paths are mirrored below. Only used by the MIRROR layout", order = 30)
	private Path sourceRoot;

	@ParamDoc(label = "Layout", description = "How the path below the target root is built. Forced to CONTENT for pool, library and bucket targets", order = 40)
	private Layout layout = Layout.MIRROR;

	@ParamDoc(label = "Pool Uuid", description = "The storage pool to move into, for the POOL target", order = 50)
	private String poolUuid;

	@ParamDoc(label = "Library Uuid", description = "The library to move into, for the LIBRARY target. Its pool decides where the bytes land", order = 60)
	private String libraryUuid;

	@ParamDoc(label = "Bucket", description = "The S3 bucket to move into, for the S3_BUCKET target. Credentials come from the worker's CORTEX_S3_* configuration", order = 70)
	private String bucket;

	@ParamDoc(label = "On Conflict", description = "What to do when the destination is occupied. There is deliberately no overwrite", order = 80)
	private String onConflict = "SUFFIX";

	@ParamDoc(label = "Cross Device", description = "What to do when the destination is on another filesystem. Copying is unbounded, so the default declines", order = 90)
	private String crossDevice = "SKIP";

	@ParamDoc(label = "Source Policy", description = "Whether to remove the original once the destination is verified. KEEP makes this a copy", order = 100)
	private String sourcePolicy = "KEEP";

	@ParamDoc(label = "Verify", description = "How the destination is proved to hold the same bytes before the original may be removed", order = 110)
	private String verify = "SHA512";

	@ParamDoc(label = "Update Location", description = "Record the new location in Loom after a successful move", order = 120)
	private boolean updateLocation = true;

	@ParamDoc(label = "Dry Run", description = "Report the move that would happen and touch nothing", order = 130)
	private boolean dryRun = false;

	public MoveTarget getTarget() {
		return target;
	}

	public MoveNodeOptions setTarget(MoveTarget target) {
		this.target = target;
		return this;
	}

	public Path getTargetFolder() {
		return targetFolder;
	}

	public MoveNodeOptions setTargetFolder(Path targetFolder) {
		this.targetFolder = targetFolder;
		return this;
	}

	public Path getSourceRoot() {
		return sourceRoot;
	}

	public MoveNodeOptions setSourceRoot(Path sourceRoot) {
		this.sourceRoot = sourceRoot;
		return this;
	}

	public Layout getLayout() {
		return layout;
	}

	public MoveNodeOptions setLayout(Layout layout) {
		this.layout = layout;
		return this;
	}

	public String getPoolUuid() {
		return poolUuid;
	}

	public MoveNodeOptions setPoolUuid(String poolUuid) {
		this.poolUuid = poolUuid;
		return this;
	}

	public String getLibraryUuid() {
		return libraryUuid;
	}

	public MoveNodeOptions setLibraryUuid(String libraryUuid) {
		this.libraryUuid = libraryUuid;
		return this;
	}

	public String getBucket() {
		return bucket;
	}

	public MoveNodeOptions setBucket(String bucket) {
		this.bucket = bucket;
		return this;
	}

	public String getOnConflict() {
		return onConflict;
	}

	public MoveNodeOptions setOnConflict(String onConflict) {
		this.onConflict = onConflict;
		return this;
	}

	public String getCrossDevice() {
		return crossDevice;
	}

	public MoveNodeOptions setCrossDevice(String crossDevice) {
		this.crossDevice = crossDevice;
		return this;
	}

	public String getSourcePolicy() {
		return sourcePolicy;
	}

	public MoveNodeOptions setSourcePolicy(String sourcePolicy) {
		this.sourcePolicy = sourcePolicy;
		return this;
	}

	public String getVerify() {
		return verify;
	}

	public MoveNodeOptions setVerify(String verify) {
		this.verify = verify;
		return this;
	}

	public boolean isUpdateLocation() {
		return updateLocation;
	}

	public MoveNodeOptions setUpdateLocation(boolean updateLocation) {
		this.updateLocation = updateLocation;
		return this;
	}

	public boolean isDryRun() {
		return dryRun;
	}

	public MoveNodeOptions setDryRun(boolean dryRun) {
		this.dryRun = dryRun;
		return this;
	}

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>(validateCommon());

		if (target == null) {
			errors.add("target must be set");
		}
		if (targetFolder == null) {
			errors.add("targetFolder must not be null");
		} else if (targetFolder.toString().isBlank()) {
			errors.add("targetFolder must not be blank");
		}
		if (layout == null) {
			errors.add("layout must be set");
		}

		addEnumError(errors, "onConflict", () -> io.metaloom.cortex.fs.ConflictPolicy.parse(onConflict));
		addEnumError(errors, "crossDevice", () -> io.metaloom.cortex.fs.CrossDevicePolicy.parse(crossDevice));
		addEnumError(errors, "sourcePolicy", () -> SourcePolicy.parse(sourcePolicy));
		addEnumError(errors, "verify", () -> VerifyPolicy.parse(verify));

		// 🔴 Permission to delete the original comes from a verification, and an S3 object cannot be
		// digested without downloading it back. Asking for both would either delete on an unproven copy or
		// silently pull every object down again; refusing the combination says which one the operator meant.
		if (MoveTarget.S3_BUCKET == target
			&& SourcePolicy.DELETE_AFTER_VERIFY == sourcePolicyOrDefault()
			&& VerifyPolicy.SHA512 == verifyOrDefault()) {
			errors.add("verify SHA512 cannot prove an S3 destination without downloading it back; use SIZE with the S3_BUCKET target");
		}

		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}

	/**
	 * The parsed policy, falling back to the permissive default when the string is unparseable.
	 *
	 * <p>
	 * A bad value is already reported as its own error; combination checks should not report it a second time in different words.
	 * </p>
	 */
	private SourcePolicy sourcePolicyOrDefault() {
		try {
			return SourcePolicy.parse(sourcePolicy);
		} catch (IllegalArgumentException e) {
			return SourcePolicy.KEEP;
		}
	}

	private VerifyPolicy verifyOrDefault() {
		try {
			return VerifyPolicy.parse(verify);
		} catch (IllegalArgumentException e) {
			return VerifyPolicy.SIZE;
		}
	}

	/**
	 * Record a parse failure as a validation error rather than letting it escape as an exception, keeping the message - which names the accepted
	 * values - intact.
	 */
	private void addEnumError(List<String> errors, String field, Runnable parse) {
		try {
			parse.run();
		} catch (IllegalArgumentException e) {
			errors.add(field + ": " + e.getMessage());
		}
	}

	@Override
	protected MoveNodeOptions self() {
		return this;
	}
}
