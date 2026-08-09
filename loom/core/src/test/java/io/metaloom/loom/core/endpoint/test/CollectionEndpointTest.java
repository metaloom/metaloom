package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.db.model.perm.Permission.READ_COLLECTION;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_COLLECTION;
import static io.metaloom.loom.rest.model.assertj.Assertions.assertThat;
import static io.metaloom.loom.test.data.TestValues.ASSET_UUID;
import static io.metaloom.loom.test.data.TestValues.COLLECTION_UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractCRUDEndpointTest;
import io.metaloom.loom.rest.model.asset.AssetListResponse;
import io.metaloom.loom.rest.model.collection.CollectionAssetBulkRequest;
import io.metaloom.loom.rest.model.collection.CollectionAssetBulkResponse;
import io.metaloom.loom.rest.model.collection.CollectionCreateRequest;
import io.metaloom.loom.rest.model.collection.CollectionListResponse;
import io.metaloom.loom.rest.model.collection.CollectionResponse;
import io.metaloom.loom.rest.model.collection.CollectionUpdateRequest;

public class CollectionEndpointTest extends AbstractCRUDEndpointTest {

	@Override
	protected void testRead(LoomHttpClient client) throws LoomClientException {
		CollectionResponse collection = client.loadCollection(COLLECTION_UUID).sync().body();
		assertThat(collection).isValid();
	}

	@Override
	protected void testCreate(LoomHttpClient client) throws LoomClientException {
		CollectionCreateRequest request = new CollectionCreateRequest();
		request.setName("dummy name");
		CollectionResponse collection = client.createCollection(request).sync().body();
		assertThat(collection).isValid();

		CollectionResponse loaded = client.loadCollection(collection.getUuid()).sync().body();
		assertThat(collection).matches(loaded);
	}

	@Override
	protected void testDelete(LoomHttpClient client) throws LoomClientException {
		client.deleteCollection(COLLECTION_UUID).sync().body();
		expect(404, "Not Found", client.loadCollection(COLLECTION_UUID));
	}

	@Override
	protected void testUpdate(LoomHttpClient client) throws LoomClientException {
		CollectionUpdateRequest update = new CollectionUpdateRequest();
		update.setName("updated-name");
		CollectionResponse response = client.updateCollection(COLLECTION_UUID, update).sync().body();
		assertThat(response).isValid();
	}

	@Override
	protected void testReadPage(LoomHttpClient client) throws LoomClientException {
		for (int i = 0; i < 100; i++) {
			CollectionCreateRequest request = new CollectionCreateRequest();
			// collection.name is UNIQUE (V2.7), unlike library.name - the same loop with a constant name
			// fails on the second iteration.
			request.setName("dummy name " + i);
			client.createCollection(request).sync().body();
		}
		CollectionListResponse list = client.listCollections().sync().body();
		assertThat(list).isValid().hasSize(25).hasPerPage(25);
	}

	// --- Membership ---

	/**
	 * A fresh membership answers 201; repeating it answers 200 and writes nothing.
	 *
	 * <p>
	 * The 200 is the contract that lets a pipeline re-run over an already-curated corpus. Before {@code linkAsset} gained
	 * {@code onConflictDoNothing}, the second call violated the {@code (collection_uuid, asset_uuid)} primary key and answered 500.
	 * </p>
	 */
	@Test
	public void testAddAssetIsIdempotent() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			CollectionResponse collection = createCollection(client, "membership-target");

			assertEquals(201, client.addCollectionAsset(collection.getUuid(), ASSET_UUID).sync().statusCode(), "Unexpected status code");
			assertEquals(200, client.addCollectionAsset(collection.getUuid(), ASSET_UUID).sync().statusCode(), "Unexpected status code");

			AssetListResponse assets = client.listCollectionAssets(collection.getUuid()).sync().body();
			assertThat(assets).isValid().hasSize(1);
		}
	}

	@Test
	public void testRemoveAsset() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			CollectionResponse collection = createCollection(client, "removal-target");
			client.addCollectionAsset(collection.getUuid(), ASSET_UUID).sync().body();

			assertEquals(204, client.removeCollectionAsset(collection.getUuid(), ASSET_UUID).sync().statusCode(), "Unexpected status code");

			assertNoMembers(client.listCollectionAssets(collection.getUuid()).sync().body());
		}
	}

	/**
	 * An empty list response carries a null {@code data}, not an empty one, so {@code hasSize(0)} would NPE rather than pass.
	 */
	private void assertNoMembers(AssetListResponse assets) {
		assertTrue(assets.getData() == null || assets.getData().isEmpty(), "The collection should have no members");
	}

	/**
	 * Removing an asset that is not a member is a 404, not a silent success. "Remove X" that quietly does nothing hides a wrong uuid.
	 */
	@Test
	public void testRemoveAssetThatIsNotAMemberIs404() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			CollectionResponse collection = createCollection(client, "non-member");
			expect(404, "Not Found", client.removeCollectionAsset(collection.getUuid(), ASSET_UUID));
		}
	}

	/**
	 * A bulk write links every asset it can and reports the rest, rather than failing the whole call. A curation run over a stale list should not lose
	 * the assets that do exist.
	 */
	@Test
	public void testBulkAddReportsPartialFailure() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			CollectionResponse collection = createCollection(client, "bulk-target");
			UUID missing = UUID.randomUUID();
			CollectionAssetBulkRequest request = new CollectionAssetBulkRequest()
				.add(ASSET_UUID)
				.add(missing);

			CollectionAssetBulkResponse response = client.addCollectionAssets(collection.getUuid(), request).sync().body();
			assertEquals(2, response.getTotal(), "total");
			assertEquals(1, response.getAdded(), "added");
			assertEquals(1, response.getFailed(), "failed");

			AssetListResponse assets = client.listCollectionAssets(collection.getUuid()).sync().body();
			assertThat(assets).isValid().hasSize(1);
		}
	}

	/**
	 * An asset already in a collection is neither an addition nor a failure - it is simply absent from both counters.
	 */
	@Test
	public void testBulkAddCountsOnlyNewMembers() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			CollectionResponse collection = createCollection(client, "bulk-idempotent");
			client.addCollectionAsset(collection.getUuid(), ASSET_UUID).sync().body();

			CollectionAssetBulkResponse response = client
				.addCollectionAssets(collection.getUuid(), new CollectionAssetBulkRequest().add(ASSET_UUID)).sync().body();
			assertEquals(1, response.getTotal(), "total");
			assertEquals(0, response.getAdded(), "added");
			assertEquals(0, response.getFailed(), "failed");
		}
	}

	/**
	 * The reverse direction: membership is many-to-many, so an asset genuinely belongs to a list of collections.
	 */
	@Test
	public void testListCollectionsOfAsset() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			CollectionResponse collection = createCollection(client, "reverse-lookup");
			client.addCollectionAsset(collection.getUuid(), ASSET_UUID).sync().body();

			CollectionListResponse collections = client.listAssetCollections(ASSET_UUID).sync().body();
			assertThat(collections).isValid();
			// The fixture already puts ASSET_UUID in COLLECTION_UUID, so this asset is now in at least two.
			assertTrue(collections.getData().size() >= 2, "Expected the asset to be in at least two collections");
		}
	}

	@Test
	public void testAddAssetToUnknownCollectionIs404() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			expect(404, "Not Found", client.addCollectionAsset(UUID.randomUUID(), ASSET_UUID));
		}
	}

	@Test
	public void testAddUnknownAssetIs404() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			expect(404, "Not Found", client.addCollectionAsset(COLLECTION_UUID, UUID.randomUUID()));
		}
	}

	/**
	 * Membership is a mutation of the collection, so it needs {@code UPDATE_COLLECTION}. Holding only {@code READ_COLLECTION} must not be enough -
	 * a permissionless user cannot show that, which is why this grants one permission and not the other.
	 */
	@Test
	public void testReadCollectionDoesNotGrantMembershipWrites() throws Exception {
		try (LoomHttpClient client = loginClientWith("collection-reader", READ_COLLECTION)) {
			expect(403, "Forbidden", client.addCollectionAsset(COLLECTION_UUID, ASSET_UUID));
			expect(403, "Forbidden", client.removeCollectionAsset(COLLECTION_UUID, ASSET_UUID));
			expect(403, "Forbidden", client.addCollectionAssets(COLLECTION_UUID, new CollectionAssetBulkRequest().add(ASSET_UUID)));
		}
	}

	@Test
	public void testMembershipReadsRequirePermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			expect(403, "Forbidden", client.listCollectionAssets(COLLECTION_UUID));
			expect(403, "Forbidden", client.listAssetCollections(ASSET_UUID));
		}
	}

	/**
	 * The counterpart of the negative case above: {@code UPDATE_COLLECTION} is what the write actually needs.
	 *
	 * <p>
	 * The fixture already links {@code ASSET_UUID} into {@code COLLECTION_UUID}, so the successful answer here is 200 rather than 201 - which is
	 * itself the idempotency contract, arrived at from the permission side.
	 * </p>
	 */
	@Test
	public void testUpdateCollectionGrantsMembershipWrites() throws Exception {
		try (LoomHttpClient client = loginClientWith("collection-writer", READ_COLLECTION, UPDATE_COLLECTION)) {
			assertEquals(200, client.addCollectionAsset(COLLECTION_UUID, ASSET_UUID).sync().statusCode(), "Unexpected status code");
		}
	}

	private CollectionResponse createCollection(LoomHttpClient client, String name) throws LoomClientException {
		CollectionCreateRequest request = new CollectionCreateRequest();
		request.setName(name);
		return client.createCollection(request).sync().body();
	}

	@Override
	protected LoomClientRequest<?> createRequest(LoomHttpClient client) {
		CollectionCreateRequest request = new CollectionCreateRequest();
		request.setName("perm-check");
		return client.createCollection(request);
	}

	@Override
	protected LoomClientRequest<?> loadRequest(LoomHttpClient client) {
		return client.loadCollection(COLLECTION_UUID);
	}

	@Override
	protected LoomClientRequest<?> listRequest(LoomHttpClient client) {
		return client.listCollections();
	}

	@Override
	protected LoomClientRequest<?> deleteRequest(LoomHttpClient client) {
		return client.deleteCollection(COLLECTION_UUID);
	}

}
