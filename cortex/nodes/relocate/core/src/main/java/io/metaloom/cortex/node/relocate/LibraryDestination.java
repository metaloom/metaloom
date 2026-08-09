package io.metaloom.cortex.node.relocate;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.library.LibraryResponse;

/**
 * A library, which is a pool plus a membership fact.
 *
 * <p>
 * A library has <b>no filesystem root of its own</b> - the schema gives it a name and a {@code pool_uuid}, nothing else. So "move into library X"
 * means "write the bytes into X's pool, and re-point the binary's {@code library_uuid} at X". Both halves matter: the first puts the bytes where
 * uploads into that library go, the second is what makes the asset show up as belonging to it.
 * </p>
 */
@Singleton
public class LibraryDestination implements MoveDestination {

	private final PoolDestination pools;

	@Inject
	public LibraryDestination(PoolDestination pools) {
		this.pools = pools;
	}

	@Override
	public MoveTarget target() {
		return MoveTarget.LIBRARY;
	}

	@Override
	public List<String> validate(MoveNodeOptions options) {
		if (PoolDestination.isBlank(options.getLibraryUuid())) {
			return List.of("the LIBRARY target needs a 'libraryUuid'");
		}
		try {
			UUID.fromString(options.getLibraryUuid().trim());
		} catch (IllegalArgumentException e) {
			return List.of("libraryUuid is not a valid uuid: " + options.getLibraryUuid());
		}
		return List.of();
	}

	@Override
	public MovePlan plan(LoomClient client, LoomMedia media, MoveNodeOptions options) throws Exception {
		PoolDestination.requireClient(client, "LIBRARY");

		UUID libraryUuid = UUID.fromString(options.getLibraryUuid().trim());
		LibraryResponse library = client.loadLibrary(libraryUuid).sync().body();
		if (library == null) {
			throw new IllegalStateException("No library found for uuid " + libraryUuid);
		}

		UUID poolUuid = library.getPoolUuid();
		if (poolUuid == null) {
			// NULL means "the server's local upload directory", which is a path on the Loom host that the
			// worker has no way to name. Guessing would scatter bytes; say what is missing instead.
			throw new IllegalStateException("Library " + libraryUuid + " (" + library.getName()
				+ ") has no storage pool, so its bytes live in the server's local upload directory, which this worker cannot address."
				+ " Give the library a pool, or move to a pool directly.");
		}

		return pools.planForPool(client, poolUuid, media, options, libraryUuid);
	}
}
