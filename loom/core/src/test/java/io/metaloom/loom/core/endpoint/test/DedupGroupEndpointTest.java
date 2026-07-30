package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.role.Role;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.metaloom.loom.rest.model.dedup.DedupGroupCreateRequest;
import io.metaloom.loom.rest.model.dedup.DedupGroupListResponse;
import io.metaloom.loom.rest.model.dedup.DedupGroupMemberModel;
import io.metaloom.loom.rest.model.dedup.DedupGroupResponse;
import io.metaloom.loom.rest.model.dedup.DedupGroupUpdateRequest;
import io.metaloom.utils.hash.SHA512;

/**
 * Endpoint behaviour of {@code /api/v1/dedup-groups} and {@code /api/v1/assets/:uuid/dedup-groups}.
 *
 * <p>
 * This is the human-in-the-loop half of the deduplication workflow: the discovery node posts candidate groups, a reviewer confirms or rejects them,
 * and only then may the apply node move a file. Not a CRUD resource (a group is created with its whole member set and only ever transitions status), so
 * this extends {@link AbstractEndpointTest}.
 * </p>
 */
public class DedupGroupEndpointTest extends AbstractEndpointTest {

	private static final String ALGO = "metaloom-multisector-v1";

	private static final UUID USER_UUID = io.metaloom.loom.test.data.TestValues.USER_UUID;

	private static final UUID ADMIN_UUID = io.metaloom.loom.test.data.TestValues.ADMIN_UUID;

	// --- fixtures ---------------------------------------------------------------------------------

	private Asset seedAsset(String filename, long size) {
		DaoCollection daos = daos();
		Asset asset = daos.assetDao().createAsset(adminUuid(), SHA512.fromString(randomSha512()),
			"video/mp4", filename, "/media/" + filename, size);
		daos.assetDao().store(asset);
		return asset;
	}

	private String randomSha512() {
		return UUID.randomUUID().toString().replace("-", "").repeat(4);
	}

	private DedupGroupCreateRequest request(Asset keep, Asset dup) {
		return new DedupGroupCreateRequest()
			.setAlgorithm(ALGO)
			.setKeepAssetUuid(keep.getUuid().toString())
			.setScore(0.87f)
			.setMembers(List.of(
				new DedupGroupMemberModel().setAssetUuid(keep.getUuid().toString())
					.setRole(DedupGroupMemberModel.ROLE_KEEP).setScore(1.0f).setSize(4096L).setZeroChunkCount(0L),
				new DedupGroupMemberModel().setAssetUuid(dup.getUuid().toString())
					.setRole(DedupGroupMemberModel.ROLE_DUP).setScore(0.87f).setSize(2048L).setZeroChunkCount(0L)));
	}

	/**
	 * Log the {@code joedoe} fixture user in holding exactly the given permissions, granted role -&gt; group -&gt; user.
	 *
	 * <p>
	 * Never a direct user grant: {@code user_permission} is keyed by {@code user_uuid} alone, so a multi-permission fixture built that way would
	 * silently keep only the last permission.
	 * </p>
	 */
	private LoomHttpClient loginWith(String roleName, Permission... permissions) throws LoomClientException {
		DaoCollection daos = daos();
		User joedoe = daos.userDao().load(USER_UUID);
		Role role = daos.roleDao().createRole(ADMIN_UUID, roleName);
		daos.roleDao().store(role);
		for (Permission permission : permissions) {
			daos.permissionDao().grantRolePermission(role.getUuid(), permission, "test");
		}
		Group group = daos.groupDao().create(joedoe, roleName + "-group");
		daos.groupDao().store(group);
		daos.groupDao().addRoleToGroup(group, role);
		daos.groupDao().addUserToGroup(group, joedoe);

		LoomHttpClient client = httpClient();
		AuthLoginResponse login = client.login("joedoe", "finger").sync().body();
		client.setToken(login.getToken());
		return client;
	}

	// --- happy path -------------------------------------------------------------------------------

	@Test
	public void testCreateAndLoadGroup() throws LoomClientException {
		Asset keep = seedAsset("keep_a.mp4", 4096L);
		Asset dup = seedAsset("dup_a.mp4", 2048L);
		LoomHttpClient client = httpClient();
		loginAdmin(client);

		DedupGroupResponse created = client.createDedupGroup(request(keep, dup)).sync().body();
		assertNotNull(created.getUuid());
		assertEquals("PENDING", created.getStatus(), "A discovered group must start out awaiting review");
		assertEquals(ALGO, created.getAlgorithm());
		assertEquals(keep.getUuid().toString(), created.getKeepAssetUuid());
		assertEquals(2, created.getMembers().size());

		DedupGroupResponse loaded = client.loadDedupGroup(UUID.fromString(created.getUuid())).sync().body();
		assertEquals(created.getUuid(), loaded.getUuid());
		assertTrue(loaded.getMembers().stream()
			.anyMatch(m -> DedupGroupMemberModel.ROLE_DUP.equals(m.getRole()) && dup.getUuid().toString().equals(m.getAssetUuid())));
		assertTrue(loaded.getMembers().stream()
			.anyMatch(m -> DedupGroupMemberModel.ROLE_KEEP.equals(m.getRole()) && keep.getUuid().toString().equals(m.getAssetUuid())));
	}

	/**
	 * Re-running discovery over unchanged content must update the pending group rather than pile up review records.
	 */
	@Test
	public void testCreateIsIdempotentForPendingGroups() throws LoomClientException {
		Asset keep = seedAsset("keep_b.mp4", 4096L);
		Asset dup = seedAsset("dup_b.mp4", 2048L);
		LoomHttpClient client = httpClient();
		loginAdmin(client);

		client.createDedupGroup(request(keep, dup)).sync().body();
		client.createDedupGroup(request(keep, dup)).sync().body();

		DedupGroupListResponse pending = client.listDedupGroups("PENDING").sync().body();
		long forKeep = pending.getData().stream()
			.filter(g -> keep.getUuid().toString().equals(g.getKeepAssetUuid()))
			.count();
		assertEquals(1, forKeep, "Re-discovery must upsert the pending group, not create a second one");
	}

	@Test
	public void testConfirmAndReject() throws LoomClientException {
		Asset keep = seedAsset("keep_c.mp4", 4096L);
		Asset dup = seedAsset("dup_c.mp4", 2048L);
		LoomHttpClient client = httpClient();
		loginAdmin(client);

		DedupGroupResponse created = client.createDedupGroup(request(keep, dup)).sync().body();
		UUID uuid = UUID.fromString(created.getUuid());

		DedupGroupResponse confirmed = client.updateDedupGroup(uuid,
			new DedupGroupUpdateRequest().setStatus("CONFIRMED").setKeepAssetUuid(keep.getUuid().toString())).sync().body();
		assertEquals("CONFIRMED", confirmed.getStatus());

		DedupGroupResponse rejected = client.updateDedupGroup(uuid, new DedupGroupUpdateRequest().setStatus("REJECTED")).sync().body();
		assertEquals("REJECTED", rejected.getStatus());

		// The confirmed list must no longer contain it, so the apply node cannot act on a reverted decision.
		DedupGroupListResponse confirmedList = client.listDedupGroups("CONFIRMED").sync().body();
		assertTrue(confirmedList.getData() == null
			|| confirmedList.getData().stream().noneMatch(g -> created.getUuid().equals(g.getUuid())));
	}

	@Test
	public void testListByAssetIsTheApplyNodeEntryPoint() throws LoomClientException {
		Asset keep = seedAsset("keep_d.mp4", 4096L);
		Asset dup = seedAsset("dup_d.mp4", 2048L);
		LoomHttpClient client = httpClient();
		loginAdmin(client);

		DedupGroupResponse created = client.createDedupGroup(request(keep, dup)).sync().body();

		// Both roles must find the group: the apply node looks itself up by the duplicate's uuid.
		DedupGroupListResponse forDup = client.listAssetDedupGroups(dup.getUuid()).sync().body();
		assertTrue(forDup.getData().stream().anyMatch(g -> created.getUuid().equals(g.getUuid())));

		DedupGroupListResponse forKeep = client.listAssetDedupGroups(keep.getUuid()).sync().body();
		assertTrue(forKeep.getData().stream().anyMatch(g -> created.getUuid().equals(g.getUuid())));
	}

	@Test
	public void testDeleteGroup() throws LoomClientException {
		Asset keep = seedAsset("keep_e.mp4", 4096L);
		Asset dup = seedAsset("dup_e.mp4", 2048L);
		LoomHttpClient client = httpClient();
		loginAdmin(client);

		DedupGroupResponse created = client.createDedupGroup(request(keep, dup)).sync().body();
		UUID uuid = UUID.fromString(created.getUuid());
		client.deleteDedupGroup(uuid).sync();

		expect(404, "Not Found", client.loadDedupGroup(uuid));
	}

	// --- validation -------------------------------------------------------------------------------

	@Test
	public void testInvalidStatusIsRejected() throws LoomClientException {
		Asset keep = seedAsset("keep_f.mp4", 4096L);
		Asset dup = seedAsset("dup_f.mp4", 2048L);
		LoomHttpClient client = httpClient();
		loginAdmin(client);

		DedupGroupResponse created = client.createDedupGroup(request(keep, dup)).sync().body();
		expect(400, "Bad Request", client.updateDedupGroup(UUID.fromString(created.getUuid()),
			new DedupGroupUpdateRequest().setStatus("MAYBE")));
	}

	@Test
	public void testGroupWithoutMembersIsRejected() throws LoomClientException {
		Asset keep = seedAsset("keep_g.mp4", 4096L);
		LoomHttpClient client = httpClient();
		loginAdmin(client);

		expect(400, "Bad Request", client.createDedupGroup(new DedupGroupCreateRequest()
			.setAlgorithm(ALGO)
			.setKeepAssetUuid(keep.getUuid().toString())));
	}

	@Test
	public void testUnknownGroupIsNotFound() throws LoomClientException {
		LoomHttpClient client = httpClient();
		loginAdmin(client);
		expect(404, "Not Found", client.loadDedupGroup(UUID.randomUUID()));
	}

	// --- permissions ------------------------------------------------------------------------------

	@Test
	public void testRoutesRequirePermissions() throws LoomClientException {
		Asset keep = seedAsset("keep_h.mp4", 4096L);
		Asset dup = seedAsset("dup_h.mp4", 2048L);

		LoomHttpClient admin = httpClient();
		loginAdmin(admin);
		DedupGroupResponse created = admin.createDedupGroup(request(keep, dup)).sync().body();
		UUID uuid = UUID.fromString(created.getUuid());

		LoomHttpClient nobody = loginPermissionlessClient();
		expect(403, "Forbidden", nobody.listDedupGroups("PENDING"));
		expect(403, "Forbidden", nobody.loadDedupGroup(uuid));
		expect(403, "Forbidden", nobody.createDedupGroup(request(keep, dup)));
		expect(403, "Forbidden", nobody.updateDedupGroup(uuid, new DedupGroupUpdateRequest().setStatus("CONFIRMED")));
		expect(403, "Forbidden", nobody.deleteDedupGroup(uuid));
		expect(403, "Forbidden", nobody.listAssetDedupGroups(dup.getUuid()));
	}

	/**
	 * READ_DEDUP must not imply the right to decide: a reviewer who may only read cannot confirm a group.
	 */
	@Test
	public void testReadPermissionDoesNotGrantUpdate() throws LoomClientException {
		Asset keep = seedAsset("keep_i.mp4", 4096L);
		Asset dup = seedAsset("dup_i.mp4", 2048L);

		LoomHttpClient admin = httpClient();
		loginAdmin(admin);
		DedupGroupResponse created = admin.createDedupGroup(request(keep, dup)).sync().body();
		UUID uuid = UUID.fromString(created.getUuid());

		LoomHttpClient reader = loginWith("dedup-reader", Permission.READ_DEDUP);
		assertNotNull(reader.loadDedupGroup(uuid).sync().body(), "READ_DEDUP must allow reading the queue");
		expect(403, "Forbidden", reader.updateDedupGroup(uuid, new DedupGroupUpdateRequest().setStatus("CONFIRMED")));
		expect(403, "Forbidden", reader.deleteDedupGroup(uuid));
	}

	/**
	 * Deleting an asset must not destroy the review record, but must drop its membership and null the keep pointer.
	 */
	@Test
	public void testAssetDeleteKeepsGroupButDropsMembership() throws LoomClientException {
		Asset keep = seedAsset("keep_j.mp4", 4096L);
		Asset dup = seedAsset("dup_j.mp4", 2048L);
		LoomHttpClient client = httpClient();
		loginAdmin(client);

		DedupGroupResponse created = client.createDedupGroup(request(keep, dup)).sync().body();
		UUID uuid = UUID.fromString(created.getUuid());

		daos().assetDao().delete(keep.getUuid());

		DedupGroupResponse survived = client.loadDedupGroup(uuid).sync().body();
		assertNotNull(survived, "Deleting an asset must not delete the review group");
		assertNull(survived.getKeepAssetUuid(), "The keep pointer must be nulled once the asset is gone");
		assertEquals(1, survived.getMembers().size(), "Only the remaining asset stays a member");
	}
}
