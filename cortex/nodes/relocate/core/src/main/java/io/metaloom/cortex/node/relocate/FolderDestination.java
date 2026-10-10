package io.metaloom.cortex.node.relocate;

import java.nio.file.Path;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.fs.PathContainment;
import io.metaloom.loom.client.common.LoomClient;

/**
 * A folder on the worker's own filesystem.
 *
 * <p>
 * The simplest target and the only one that needs nothing from Loom, which is what makes trash and quarantine work on a worker that cannot reach the
 * server. The destination is the configured folder verbatim; how the path below it is built is {@link Layout}'s business.
 * </p>
 */
@Singleton
public class FolderDestination implements MoveDestination {

	@Inject
	public FolderDestination() {
	}

	@Override
	public MoveTarget target() {
		return MoveTarget.FOLDER;
	}

	@Override
	public List<String> validate(MoveNodeOptions options) {
		if (options.getTargetFolder() == null || options.getTargetFolder().toString().isBlank()) {
			return List.of("the FOLDER target needs a 'targetFolder'");
		}
		return List.of();
	}

	@Override
	public MovePlan plan(LoomClient client, LoomMedia media, MoveNodeOptions options) throws Exception {
		Path root = resolveRoot(options);

		// Idempotency. Path.startsWith rather than String.startsWith: /data/dups-old is not inside
		// /data/dups, and answering otherwise would report an untouched file as already relocated.
		if (PathContainment.isInside(media.file(), root)) {
			return MovePlan.filesystem(media.file().toPath(), null, null).asAlreadyThere();
		}

		Path target = Layouts.resolve(root, media, options.getLayout(), options.getSourceRoot());
		MovePlan plan = MovePlan.filesystem(target, null, null);

		// Under the content-addressed layout the destination path is the hash, so an occupied destination
		// means the archive already holds these bytes - not that two different files want one name. Only
		// there: for FLAT, MIRROR and DATE a collision really is a name collision, and onConflict decides.
		if (options.getLayout() == Layout.CONTENT && Layouts.holdsSameContent(target, media)) {
			return plan.asAlreadyThere();
		}
		return plan;
	}

	/**
	 * A relative target folder resolves against the source root when one is configured, and against the working directory otherwise.
	 *
	 * <p>
	 * That is what makes {@code targetFolder: trash} mean "a trash folder in this corpus" rather than "a trash folder wherever the worker happens to
	 * have been started".
	 * </p>
	 */
	private Path resolveRoot(MoveNodeOptions options) {
		Path folder = options.getTargetFolder();
		if (folder.isAbsolute()) {
			return folder.normalize();
		}
		Path sourceRoot = options.getSourceRoot();
		return sourceRoot == null ? folder.toAbsolutePath().normalize() : sourceRoot.resolve(folder).toAbsolutePath().normalize();
	}
}
