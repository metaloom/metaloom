package io.metaloom.cortex.node.relocate;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.library.LibraryListResponse;
import io.metaloom.loom.rest.model.library.LibraryResponse;

/**
 * Membership in a library.
 *
 * <p>
 * ⚠️ This writes {@code library_asset} and nothing else. It does not create a binary, and it does not move one: an asset can belong to a library it
 * holds no bytes in. Relocating the bytes into a library's pool is {@code move --target LIBRARY}.
 * </p>
 */
@Singleton
public class LibraryAssignment implements AssignDestination {

	@Inject
	public LibraryAssignment() {
	}

	@Override
	public AssignTarget target() {
		return AssignTarget.LIBRARY;
	}

	@Override
	public List<String> validate(AssignNodeOptions options) {
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
	public UUID resolve(LoomClient client, AssignNodeOptions options) throws Exception {
		UUID uuid = UUID.fromString(options.getLibraryUuid().trim());
		LibraryResponse library = client.loadLibrary(uuid).sync().body();
		return library == null ? null : library.getUuid();
	}

	@Override
	public boolean isMember(LoomClient client, UUID targetUuid, UUID assetUuid) throws Exception {
		LibraryListResponse page = client.listAssetLibraries(assetUuid).sync().body();
		if (page == null || page.getData() == null) {
			return false;
		}
		return page.getData().stream().anyMatch(l -> targetUuid.equals(l.getUuid()));
	}

	@Override
	public void link(LoomClient client, UUID targetUuid, UUID assetUuid) throws Exception {
		client.addLibraryAsset(targetUuid, assetUuid).sync();
	}

	@Override
	public String table() {
		return "library";
	}

	@Override
	public String describe(AssignNodeOptions options) {
		return "library " + options.getLibraryUuid();
	}
}
