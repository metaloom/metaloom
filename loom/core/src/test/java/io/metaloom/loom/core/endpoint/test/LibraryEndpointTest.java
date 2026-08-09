package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.db.model.perm.Permission.READ_LIBRARY;
import static io.metaloom.loom.rest.model.assertj.Assertions.assertThat;
import static io.metaloom.loom.test.data.TestValues.ASSET_UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractCRUDEndpointTest;
import io.metaloom.loom.rest.model.asset.AssetListResponse;
import io.metaloom.loom.rest.model.library.LibraryCreateRequest;
import io.metaloom.loom.rest.model.library.LibraryListResponse;
import io.metaloom.loom.rest.model.library.LibraryResponse;
import io.metaloom.loom.rest.model.library.LibraryUpdateRequest;

public class LibraryEndpointTest extends AbstractCRUDEndpointTest {

	@Override
	protected void testRead(LoomHttpClient client) throws LoomClientException {
		LibraryResponse library = client.loadLibrary(LIBRARY_UUID).sync().body();
		assertThat(library).isValid();
	}

	@Override
	protected void testCreate(LoomHttpClient client) throws LoomClientException {
		LibraryCreateRequest request = new LibraryCreateRequest();
		request.setName("dummy name");
		LibraryResponse library = client.createLibrary(request).sync().body();
		assertThat(library).isValid();

		LibraryResponse library2 = client.loadLibrary(library.getUuid()).sync().body();
		assertThat(library).matches(library2);
	}

	@Override
	protected void testDelete(LoomHttpClient client) throws LoomClientException {
		client.deleteLibrary(LIBRARY_UUID).sync().body();
		expect(404, "Not Found", client.loadLibrary(LIBRARY_UUID));
	}

	@Override
	protected void testUpdate(LoomHttpClient client) throws LoomClientException {
		LibraryUpdateRequest update = new LibraryUpdateRequest();
		update.setName("updated-name");
		LibraryResponse response = client.updateLibrary(LIBRARY_UUID, update).sync().body();
		assertThat(response).isValid();
	}

	@Override
	protected void testReadPage(LoomHttpClient client) throws LoomClientException {
		for (int i = 0; i < 100; i++) {
			LibraryCreateRequest request = new LibraryCreateRequest();
			request.setName("dummy name");
			client.createLibrary(request).sync().body();
		}
		LibraryListResponse list = client.listLibraries().sync().body();
		assertThat(list).isValid().hasSize(25).hasPerPage(25);
	}

	// --- Membership (library_asset) ---

	/**
	 * Library membership is the organizational link, and it is deliberately independent of {@code asset_location.library_uuid}: an asset can be a
	 * member of a library it holds no binary in. Re-adding is a 200 rather than a duplicate-key error.
	 */
	@Test
	public void testAddAssetIsIdempotent() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			LibraryResponse library = createLibrary(client, "membership-target");

			assertEquals(201, client.addLibraryAsset(library.getUuid(), ASSET_UUID).sync().statusCode(), "Unexpected status code");
			assertEquals(200, client.addLibraryAsset(library.getUuid(), ASSET_UUID).sync().statusCode(), "Unexpected status code");

			AssetListResponse assets = client.listLibraryAssets(library.getUuid()).sync().body();
			assertThat(assets).isValid().hasSize(1);
		}
	}

	@Test
	public void testRemoveAsset() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			LibraryResponse library = createLibrary(client, "removal-target");
			client.addLibraryAsset(library.getUuid(), ASSET_UUID).sync().body();

			assertEquals(204, client.removeLibraryAsset(library.getUuid(), ASSET_UUID).sync().statusCode(), "Unexpected status code");

			// An empty list response carries a null data list, not an empty one, so hasSize(0) would NPE.
			AssetListResponse assets = client.listLibraryAssets(library.getUuid()).sync().body();
			assertTrue(assets.getData() == null || assets.getData().isEmpty(), "The library should have no members");
		}
	}

	@Test
	public void testRemoveAssetThatIsNotAMemberIs404() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			LibraryResponse library = createLibrary(client, "non-member");
			expect(404, "Not Found", client.removeLibraryAsset(library.getUuid(), ASSET_UUID));
		}
	}

	@Test
	public void testListLibrariesOfAsset() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			LibraryResponse library = createLibrary(client, "reverse-lookup");
			client.addLibraryAsset(library.getUuid(), ASSET_UUID).sync().body();

			LibraryListResponse libraries = client.listAssetLibraries(ASSET_UUID).sync().body();
			assertThat(libraries).isValid().hasSize(1);
		}
	}

	@Test
	public void testAddAssetToUnknownLibraryIs404() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			expect(404, "Not Found", client.addLibraryAsset(UUID.randomUUID(), ASSET_UUID));
		}
	}

	@Test
	public void testAddUnknownAssetIs404() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			expect(404, "Not Found", client.addLibraryAsset(LIBRARY_UUID, UUID.randomUUID()));
		}
	}

	/**
	 * Membership is a mutation of the library, so {@code READ_LIBRARY} alone must not be enough to write one.
	 */
	@Test
	public void testReadLibraryDoesNotGrantMembershipWrites() throws Exception {
		try (LoomHttpClient client = loginClientWith("library-reader", READ_LIBRARY)) {
			expect(403, "Forbidden", client.addLibraryAsset(LIBRARY_UUID, ASSET_UUID));
			expect(403, "Forbidden", client.removeLibraryAsset(LIBRARY_UUID, ASSET_UUID));
		}
	}

	@Test
	public void testMembershipReadsRequirePermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			expect(403, "Forbidden", client.listLibraryAssets(LIBRARY_UUID));
			expect(403, "Forbidden", client.listAssetLibraries(ASSET_UUID));
		}
	}

	private LibraryResponse createLibrary(LoomHttpClient client, String name) throws LoomClientException {
		LibraryCreateRequest request = new LibraryCreateRequest();
		request.setName(name);
		return client.createLibrary(request).sync().body();
	}

	@Override
	protected LoomClientRequest<?> createRequest(LoomHttpClient client) {
		LibraryCreateRequest request = new LibraryCreateRequest();
		request.setName("perm-check");
		return client.createLibrary(request);
	}

	@Override
	protected LoomClientRequest<?> loadRequest(LoomHttpClient client) {
		return client.loadLibrary(LIBRARY_UUID);
	}

	@Override
	protected LoomClientRequest<?> listRequest(LoomHttpClient client) {
		return client.listLibraries();
	}

	@Override
	protected LoomClientRequest<?> deleteRequest(LoomHttpClient client) {
		return client.deleteLibrary(LIBRARY_UUID);
	}

}
