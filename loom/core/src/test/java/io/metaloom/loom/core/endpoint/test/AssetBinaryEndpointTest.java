package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.db.model.perm.Permission.UPDATE_ASSET_BINARY;
import static io.metaloom.loom.rest.model.assertj.Assertions.assertThat;
import static io.metaloom.loom.test.data.TestValues.ASSET_POOL_UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractCRUDEndpointTest;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryCreateRequest;
import io.metaloom.loom.rest.model.library.LibraryCreateRequest;
import io.metaloom.loom.rest.model.library.LibraryResponse;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryFilesystemInfo;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryListResponse;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryResponse;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryUpdateRequest;

public class AssetBinaryEndpointTest extends AbstractCRUDEndpointTest {

	@Override
	protected void testRead(LoomHttpClient client) throws LoomClientException {
		AssetBinaryResponse binary = client.loadBinary(ASSET_LOCATION_UUID).sync().body();
		assertThat(binary).isValid();
	}

	@Override
	protected void testCreate(LoomHttpClient client) throws LoomClientException {
		AssetBinaryCreateRequest request = new AssetBinaryCreateRequest();
		request.setFilesystem(new AssetBinaryFilesystemInfo().setPath("/dummy/path"));
		request.setLibraryUuid(LIBRARY_UUID);
		request.setAssetUuid(ASSET_UUID);
		AssetBinaryResponse binary = client.createBinary(request).sync().body();
		assertThat(binary).isValid();

		AssetBinaryResponse binary2 = client.loadBinary(binary.getUuid()).sync().body();
		assertThat(binary).matches(binary2);
	}

	@Override
	protected void testDelete(LoomHttpClient client) throws LoomClientException {
		client.deleteBinary(ASSET_LOCATION_UUID).sync().body();
		expect(404, "Not Found", client.loadBinary(ASSET_LOCATION_UUID));
	}

	@Override
	protected void testUpdate(LoomHttpClient client) throws LoomClientException {
		AssetBinaryUpdateRequest update = new AssetBinaryUpdateRequest();
		update.setFilesystem(new AssetBinaryFilesystemInfo().setPath("updated-path"));
		AssetBinaryResponse response = client.updateBinary(ASSET_LOCATION_UUID, update).sync().body();
		assertThat(response).isValid().hasPath("updated-path");
	}

	@Override
	protected void testReadPage(LoomHttpClient client) throws LoomClientException {
		for (int i = 0; i < 100; i++) {
			AssetBinaryCreateRequest request = new AssetBinaryCreateRequest();
			request.setAssetUuid(ASSET_UUID);
			request.setLibraryUuid(LIBRARY_UUID);
			request.setFilesystem(new AssetBinaryFilesystemInfo().setPath("dummy path " + i));
			client.createBinary(request).sync().body();
		}
		AssetBinaryListResponse list = client.listBinaries().sync().body();
		assertThat(list).isValid().hasSize(25).hasPerPage(25);
	}

	/**
	 * Re-pointing a binary at another library and pool must round-trip.
	 *
	 * <p>
	 * This is the write-then-read check from the node guidelines. Both fields existed on the response and its builder long before the update request
	 * carried them, so they were readable and unwritable: a relocation could not be recorded at all. Asserting the read-back rather than the write is
	 * the whole point - {@code detection.label} was write-only for eight migrations because nothing did this.
	 * </p>
	 */
	@Test
	public void testUpdateRepointsLibraryAndPool() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			LibraryCreateRequest newLibrary = new LibraryCreateRequest();
			newLibrary.setName("relocation-target");
			LibraryResponse library = client.createLibrary(newLibrary).sync().body();

			AssetBinaryUpdateRequest update = new AssetBinaryUpdateRequest()
				.setFilesystem(new AssetBinaryFilesystemInfo().setPath("/relocated/bigbuckbunny-4k.mp4"))
				.setLibraryUuid(library.getUuid())
				.setPoolUuid(ASSET_POOL_UUID);

			AssetBinaryResponse response = client.updateBinary(ASSET_LOCATION_UUID, update).sync().body();
			assertThat(response).isValid().hasPath("/relocated/bigbuckbunny-4k.mp4");
			assertEquals(library.getUuid(), response.getLibraryUuid(), "The binary should have moved to the new library");
			assertEquals(ASSET_POOL_UUID, response.getPoolUuid(), "The binary should record the new pool");

			// Read it back through a fresh request: the response above could have been built from the in-heap
			// element rather than from what was persisted.
			AssetBinaryResponse reloaded = client.loadBinary(ASSET_LOCATION_UUID).sync().body();
			assertEquals(library.getUuid(), reloaded.getLibraryUuid(), "The library did not survive the round trip");
			assertEquals(ASSET_POOL_UUID, reloaded.getPoolUuid(), "The pool did not survive the round trip");
		}
	}

	/**
	 * Naming only a library adopts that library's pool. "Move this binary into that library" almost always means "and therefore into its storage".
	 */
	@Test
	public void testUpdateWithOnlyALibraryAdoptsItsPool() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			LibraryCreateRequest pooled = new LibraryCreateRequest();
			pooled.setName("pooled-library");
			pooled.setPoolUuid(ASSET_POOL_UUID);
			LibraryResponse library = client.createLibrary(pooled).sync().body();

			AssetBinaryUpdateRequest update = new AssetBinaryUpdateRequest().setLibraryUuid(library.getUuid());
			AssetBinaryResponse response = client.updateBinary(ASSET_LOCATION_UUID, update).sync().body();

			assertEquals(ASSET_POOL_UUID, response.getPoolUuid(), "The binary should have adopted the library's pool");
		}
	}

	/**
	 * An explicit pool is an operator action - it asserts which backend holds the bytes - so it needs READ_ASSET_POOL on top of UPDATE_ASSET_BINARY.
	 * A caller holding one and not the other must be rejected, which a permissionless user cannot demonstrate.
	 */
	@Test
	public void testAnExplicitPoolAdditionallyRequiresReadAssetPool() throws Exception {
		try (LoomHttpClient client = loginClientWith("binary-updater", UPDATE_ASSET_BINARY)) {
			AssetBinaryUpdateRequest withPool = new AssetBinaryUpdateRequest().setPoolUuid(ASSET_POOL_UUID);
			expect(403, "Forbidden", client.updateBinary(ASSET_LOCATION_UUID, withPool));

			// The same caller may still re-point a path, which needs no pool permission.
			AssetBinaryUpdateRequest pathOnly = new AssetBinaryUpdateRequest()
				.setFilesystem(new AssetBinaryFilesystemInfo().setPath("/still-allowed"));
			AssetBinaryResponse response = client.updateBinary(ASSET_LOCATION_UUID, pathOnly).sync().body();
			assertThat(response).isValid().hasPath("/still-allowed");
		}
	}

	@Test
	public void testUpdateWithAnUnknownLibraryIs404() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetBinaryUpdateRequest update = new AssetBinaryUpdateRequest().setLibraryUuid(UUID.randomUUID());
			expect(404, "Not Found", client.updateBinary(ASSET_LOCATION_UUID, update));
		}
	}

	@Test
	public void testUpdateWithAnUnknownPoolIs404() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetBinaryUpdateRequest update = new AssetBinaryUpdateRequest().setPoolUuid(UUID.randomUUID());
			expect(404, "Not Found", client.updateBinary(ASSET_LOCATION_UUID, update));
		}
	}

	/**
	 * Two binaries landing on the same path in the same library is a caller conflict, not an internal failure.
	 * {@code asset_location_unique_library_path} (V2.48) must surface as 409 rather than a raw driver 500.
	 */
	@Test
	public void testAPathCollisionInTheSameLibraryIs409() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			AssetBinaryCreateRequest occupant = new AssetBinaryCreateRequest();
			occupant.setAssetUuid(ASSET_UUID);
			occupant.setLibraryUuid(LIBRARY_UUID);
			occupant.setFilesystem(new AssetBinaryFilesystemInfo().setPath("/contested/path"));
			client.createBinary(occupant).sync().body();

			AssetBinaryUpdateRequest collide = new AssetBinaryUpdateRequest()
				.setFilesystem(new AssetBinaryFilesystemInfo().setPath("/contested/path"));
			expect(409, "Conflict", client.updateBinary(ASSET_LOCATION_UUID, collide));
		}
	}

	@Override
	protected LoomClientRequest<?> createRequest(LoomHttpClient client) {
		AssetBinaryCreateRequest request = new AssetBinaryCreateRequest();
		request.setFilesystem(new AssetBinaryFilesystemInfo().setPath("/dummy/path"));
		request.setLibraryUuid(LIBRARY_UUID);
		request.setAssetUuid(ASSET_UUID);
		return client.createBinary(request);
	}

	@Override
	protected LoomClientRequest<?> loadRequest(LoomHttpClient client) {
		return client.loadBinary(ASSET_LOCATION_UUID);
	}

	@Override
	protected LoomClientRequest<?> listRequest(LoomHttpClient client) {
		return client.listBinaries();
	}

	@Override
	protected LoomClientRequest<?> deleteRequest(LoomHttpClient client) {
		return client.deleteBinary(ASSET_LOCATION_UUID);
	}

}
