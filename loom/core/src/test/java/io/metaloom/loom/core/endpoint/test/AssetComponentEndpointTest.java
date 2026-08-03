package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.role.Role;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.rest.model.asset.AssetComponentCreateRequest;
import io.metaloom.loom.rest.model.asset.AssetComponentListResponse;
import io.metaloom.loom.rest.model.asset.AssetComponentResponse;
import io.metaloom.loom.rest.model.asset.AssetComponentType;
import io.metaloom.loom.rest.model.asset.AssetComponentUpdateRequest;
import io.metaloom.loom.rest.model.asset.info.GeoLocationInfo;
import io.metaloom.loom.rest.model.asset.info.ImageInfo;
import io.metaloom.loom.rest.model.asset.info.JsonComponentInfo;
import io.vertx.core.json.JsonObject;

public class AssetComponentEndpointTest extends AbstractEndpointTest {

	@Test
	public void testCreate() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetComponentResponse response = createJsonComponent(client, ASSET_UUID, "test-schema", new JsonObject().put("key", "value"));
			assertNotNull(response.getUuid());
			assertEquals(AssetComponentType.JSON, response.getType());
			assertEquals(ASSET_UUID, response.getAssetUuid());
			assertNotNull(response.getJson());
			assertEquals("test-schema", response.getJson().getSchemaType());
			assertEquals("value", response.getJson().getData().getString("key"));
		}
	}

	@Test
	public void testRead() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetComponentResponse created = createJsonComponent(client, ASSET_UUID, "read-schema", new JsonObject().put("foo", "bar"));
			UUID compUuid = created.getUuid();

			AssetComponentResponse loaded = client.loadAssetComponent(ASSET_UUID, compUuid).sync().body();
			assertNotNull(loaded);
			assertEquals(compUuid, loaded.getUuid());
			assertEquals(AssetComponentType.JSON, loaded.getType());
			assertEquals("read-schema", loaded.getJson().getSchemaType());
			assertEquals("bar", loaded.getJson().getData().getString("foo"));
		}
	}

	@Test
	public void testUpdate() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetComponentResponse created = createJsonComponent(client, ASSET_UUID, "old-schema", new JsonObject().put("a", 1));
			UUID compUuid = created.getUuid();

			AssetComponentUpdateRequest updateRequest = new AssetComponentUpdateRequest();
			updateRequest.setType(AssetComponentType.JSON);
			updateRequest.setJson(new JsonComponentInfo()
				.setSchemaType("new-schema")
				.setData(new JsonObject().put("a", 2)));
			AssetComponentResponse updated = client.updateAssetComponent(ASSET_UUID, compUuid, updateRequest).sync().body();
			assertNotNull(updated);
			assertEquals("new-schema", updated.getJson().getSchemaType());
			assertEquals(2, updated.getJson().getData().getInteger("a"));
		}
	}

	@Test
	public void testDelete() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetComponentResponse created = createJsonComponent(client, ASSET_UUID, "delete-schema", new JsonObject().put("x", true));
			UUID compUuid = created.getUuid();

			client.deleteAssetComponent(ASSET_UUID, compUuid).sync().body();
			expect(404, "Not Found", client.loadAssetComponent(ASSET_UUID, compUuid));
		}
	}

	@Test
	public void testList() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			// Create a few components
			createJsonComponent(client, ASSET_UUID, "list-schema-1", new JsonObject().put("i", 1));
			createJsonComponent(client, ASSET_UUID, "list-schema-2", new JsonObject().put("i", 2));

			AssetComponentListResponse list = client.listAssetComponents(ASSET_UUID).sync().body();
			assertNotNull(list);
			assertNotNull(list.getData());
			// At least the 2 we just created
			assertEquals(2, list.getData().size());
		}
	}

	/**
	 * The discriminators and shared provenance a Cortex node needs in order to write a typed component at all.
	 */
	@Test
	public void testGeoComponentRoundTripsItsDiscriminatorsAndProvenance() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			AssetComponentResponse created = client.createAssetComponent(ASSET_UUID, geoRequest(35.360833, 138.7275)
				.setNodeId("public-branch")
				.setProducerVersion("metadata/1")
				.setMeta(new JsonObject().put("altitudeM", 2305))).sync().body();

			assertEquals(AssetComponentType.GEO, created.getType());
			assertEquals("exif", created.getMethod());
			assertEquals(Long.valueOf(0L), created.getTimeFrom());
			assertEquals("public-branch", created.getNodeId());
			assertEquals("metadata/1", created.getProducerVersion());
			assertNotNull(created.getGeo());
			assertEquals(12f, created.getGeo().getAccuracyM(), 0.001f);

			AssetComponentResponse loaded = client.loadAssetComponent(ASSET_UUID, created.getUuid()).sync().body();
			assertEquals("exif", loaded.getMethod());
			assertEquals("metadata/1", loaded.getProducerVersion());
		}
	}

	/**
	 * The endpoint upserts. Without this a second pipeline run over the same asset would violate
	 * {@code asset_geo_comp_unique_key} and fail the node.
	 */
	@Test
	public void testRepeatedCreateUpsertsRatherThanFailing() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			AssetComponentResponse first = client.createAssetComponent(ASSET_UUID, geoRequest(35.360833, 138.7275))
				.sync().body();
			AssetComponentResponse second = client.createAssetComponent(ASSET_UUID, geoRequest(35.4, 138.7))
				.sync().body();

			assertEquals(first.getUuid(), second.getUuid(), "the same identity must resolve to the same row");

			AssetComponentListResponse list = client.listAssetComponents(ASSET_UUID).sync().body();
			assertEquals(1, list.getData().size(), "a re-run must replace its row, not append one");
			assertEquals(35.4, list.getData().get(0).getGeo().getLat(), 0.0001);
		}
	}

	/**
	 * A different {@code method} is a different reading, not a duplicate: the source is part of the identity.
	 */
	@Test
	public void testADifferentMethodIsADifferentComponent() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			client.createAssetComponent(ASSET_UUID, geoRequest(35.360833, 138.7275)).sync().body();
			client.createAssetComponent(ASSET_UUID, geoRequest(35.4, 138.7).setMethod("sidecar")).sync().body();

			assertEquals(2, client.listAssetComponents(ASSET_UUID).sync().body().getData().size());
		}
	}

	/**
	 * The JSON component's variant is part of its identity too, so two schema variants coexist while a re-run of one replaces itself.
	 */
	@Test
	public void testJsonComponentVariantDiscriminatesAndUpserts() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			createJsonComponent(client, ASSET_UUID, "metadata", new JsonObject().put("v", 1));
			createJsonComponent(client, ASSET_UUID, "metadata", new JsonObject().put("v", 1).put("dc", "second"));

			AssetComponentListResponse list = client.listAssetComponents(ASSET_UUID).sync().body();
			assertEquals(1, list.getData().size());
			assertEquals("second", list.getData().get(0).getJson().getData().getString("dc"));
		}
	}

	@Test
	public void testTypedMediaFieldsRoundTrip() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			AssetComponentCreateRequest request = new AssetComponentCreateRequest();
			request.setType(AssetComponentType.IMAGE);
			request.setSource("metadata");
			request.setStreamIndex(0);
			request.setImage(new ImageInfo().setWidth(6000).setHeight(4000).setOrientation(6).setBitDepth(14)
				.setEncoding("jpeg"));

			AssetComponentResponse created = client.createAssetComponent(ASSET_UUID, request).sync().body();

			assertEquals(Integer.valueOf(0), created.getStreamIndex());
			assertEquals(Integer.valueOf(6), created.getImage().getOrientation());
			assertEquals(Integer.valueOf(14), created.getImage().getBitDepth());
			assertEquals("jpeg", created.getImage().getEncoding());
		}
	}

	/**
	 * Fine-grained permission handling, not only the admin path. The fixture user's permissions are granted through a group and a role, because
	 * {@code user_permission} allows only one direct grant per user.
	 */
	@Test
	public void testPermissionsAreEnforced() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			// No permissions at all: every route is denied.
			LoomHttpClient nobody = loginPermissionlessClient();
			expect(403, "Forbidden", nobody.listAssetComponents(ASSET_UUID));
			expect(403, "Forbidden", nobody.createAssetComponent(ASSET_UUID, geoRequest(1, 1)));

			loginAdmin(client);
			AssetComponentResponse created = client.createAssetComponent(ASSET_UUID, geoRequest(1, 1)).sync().body();

			// READ_ASSET alone reaches the read routes and is refused by the write ones.
			LoomHttpClient reader = clientWith("comp-reader", Permission.READ_ASSET);
			assertNotNull(reader.listAssetComponents(ASSET_UUID).sync().body());
			assertNotNull(reader.loadAssetComponent(ASSET_UUID, created.getUuid()).sync().body());
			expect(403, "Forbidden", reader.createAssetComponent(ASSET_UUID, geoRequest(2, 2)));
			expect(403, "Forbidden", reader.deleteAssetComponent(ASSET_UUID, created.getUuid()));

			// UPDATE_ASSET is the write permission; it does not imply the read one.
			LoomHttpClient writer = clientWith("comp-writer", Permission.UPDATE_ASSET);
			assertNotNull(writer.createAssetComponent(ASSET_UUID, geoRequest(3, 3)).sync().body());
			expect(403, "Forbidden", writer.listAssetComponents(ASSET_UUID));
		}
	}

	/**
	 * A fresh user holding exactly the given permissions, granted through a group and a role.
	 */
	private LoomHttpClient clientWith(String username, Permission... permissions) throws LoomClientException {
		DaoCollection daos = daos();
		User user = daos.userDao().createUser(adminUuid(), username);
		user.enable();
		user.setPasswordHash(loom.internal().authService().encodePassword("secret"));
		daos.userDao().store(user);

		Role role = daos.roleDao().createRole(adminUuid(), username + "-role");
		daos.roleDao().store(role);
		for (Permission permission : permissions) {
			daos.permissionDao().grantRolePermission(role.getUuid(), permission);
		}
		Group group = daos.groupDao().create(user, username + "-group");
		daos.groupDao().store(group);
		daos.groupDao().addRoleToGroup(group, role);
		daos.groupDao().addUserToGroup(group, user);

		LoomHttpClient client = loom.httpClient();
		client.setToken(client.login(username, "secret").sync().body().getToken());
		return client;
	}

	private AssetComponentCreateRequest geoRequest(double lat, double lon) {
		AssetComponentCreateRequest request = new AssetComponentCreateRequest();
		request.setType(AssetComponentType.GEO);
		request.setSource("metadata");
		// The source the coordinate came from, which is what the component's identity keys on.
		request.setMethod("exif");
		request.setTimeFrom(0L);
		request.setGeo(new GeoLocationInfo().setLat(lat).setLon(lon).setAccuracyM(12f));
		return request;
	}

	private AssetComponentResponse createJsonComponent(LoomHttpClient client, UUID assetUuid, String schemaType, JsonObject data)
		throws LoomClientException {
		AssetComponentCreateRequest request = new AssetComponentCreateRequest();
		request.setType(AssetComponentType.JSON);
		request.setSource("unit-test");
		request.setJson(new JsonComponentInfo()
			.setSchemaType(schemaType)
			.setData(data));
		return client.createAssetComponent(assetUuid, request).sync().body();
	}
}
