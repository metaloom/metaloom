package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.test.data.TestValues.ASSET_UUID;
import static io.metaloom.loom.test.data.TestValues.USER_UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.metaloom.loom.rest.model.role.RoleCreateRequest;
import io.metaloom.loom.rest.model.role.RolePermission;
import io.metaloom.loom.rest.model.role.RoleResponse;
import io.metaloom.loom.rest.model.role.RoleUpdateRequest;

/**
 * The test that proves the ACL matrix is no longer a no-op.
 *
 * <p>
 * Persisting rows that nothing reads would leave the original bug in place with extra steps, so this test never touches
 * {@code PermissionDao.grantRolePermission} or the {@code role_permission} table directly. The grant is made <b>only</b> through the public REST
 * surface an administrator uses - {@code POST /roles} with a permission list - and the effect is observed through a completely different endpoint:
 * whether {@code joedoe} may read an asset.
 * </p>
 *
 * <p>
 * The fixture user {@code joedoe} carries a single direct grant ({@code READ_USER}) and belongs to no group, so any asset access it gains can only
 * have come through the role created here.
 * </p>
 */
public class RolePermissionEnforcementTest extends AbstractEndpointTest {

	/**
	 * Link a role to {@code joedoe} through a fresh group. Roles reach users through group membership only - there is no {@code user_role} table - so
	 * this is the wiring an administrator would do in the admin area after creating the role.
	 */
	private void linkRoleToJoeDoe(UUID roleUuid, String groupName) {
		User joedoe = daos().userDao().load(USER_UUID);
		Group group = daos().groupDao().create(joedoe, groupName);
		daos().groupDao().store(group);
		daos().groupDao().addRoleToGroup(group, daos().roleDao().load(roleUuid));
		daos().groupDao().addUserToGroup(group, joedoe);
	}

	private LoomHttpClient loginJoeDoe() throws LoomClientException {
		LoomHttpClient client = loom.httpClient();
		AuthLoginResponse login = client.login("joedoe", "finger").sync().body();
		client.setToken(login.getToken());
		return client;
	}

	@Test
	public void testRoleGrantedPermissionIsEnforced() throws Exception {
		UUID roleUuid;

		// 1. An administrator creates an empty role - over REST, the way the admin area does it. No
		//    DAO grant is involved anywhere in this test.
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			RoleCreateRequest request = new RoleCreateRequest();
			request.setName("asset-reader-role");
			RoleResponse role = client.createRole(request).sync().body();
			roleUuid = role.getUuid();
			assertTrue(role.getPermissions().isEmpty(), "The role starts out granting nothing");
		}

		// 2. The role is attached to joedoe through a group. It still grants nothing, so joedoe is
		//    refused - and every step below runs against this same, already-authorized session.
		linkRoleToJoeDoe(roleUuid, "asset-reader-group");

		try (LoomHttpClient client = loginJoeDoe()) {
			expect(403, "Forbidden", client.loadAsset(ASSET_UUID));
		}

		// 3. The administrator ticks READ_ASSET in the matrix. This is the whole feature: a grant made
		//    over REST has to change what a user may do.
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			RoleUpdateRequest grant = new RoleUpdateRequest();
			grant.setPermissions(List.of(RolePermission.READ_ASSET));
			RoleResponse updated = client.updateRole(roleUuid, grant).sync().body();
			assertEquals(List.of(RolePermission.READ_ASSET), updated.getPermissions());
		}

		try (LoomHttpClient client = loginJoeDoe()) {
			AssetResponse asset = client.loadAsset(ASSET_UUID).sync().body();
			assertNotNull(asset, "The role's READ_ASSET grant must let joedoe read the asset");
			assertEquals(ASSET_UUID, asset.getUuid());
		}

		// 4. Unticking it takes the access away again. This half also covers the effective-permission
		//    cache: it has no expiry, so without an explicit invalidation the revoke would persist to
		//    the database and change nothing for a session that already resolved its authorizations.
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			RoleUpdateRequest revoke = new RoleUpdateRequest();
			revoke.setPermissions(List.of());
			client.updateRole(roleUuid, revoke).sync().body();
		}

		try (LoomHttpClient client = loginJoeDoe()) {
			expect(403, "Forbidden", client.loadAsset(ASSET_UUID));
		}
	}

	/**
	 * Replacing the permission set swaps the authority rather than adding to it: the role gains what the new list holds and loses what it omits, and
	 * both halves are visible at the endpoints they gate.
	 */
	@Test
	public void testReplacingPermissionsSwapsEnforcedAuthority() throws Exception {
		UUID roleUuid;

		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			RoleCreateRequest request = new RoleCreateRequest();
			request.setName("swap-role");
			request.setPermissions(List.of(RolePermission.READ_ASSET));
			roleUuid = client.createRole(request).sync().body().getUuid();
		}

		linkRoleToJoeDoe(roleUuid, "swap-group");

		try (LoomHttpClient client = loginJoeDoe()) {
			assertNotNull(client.loadAsset(ASSET_UUID).sync().body(), "READ_ASSET is in effect");
			// READ_TAG is not granted yet.
			expect(403, "Forbidden", client.listTags());
		}

		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			RoleUpdateRequest swap = new RoleUpdateRequest();
			swap.setPermissions(List.of(RolePermission.READ_TAG));
			client.updateRole(roleUuid, swap).sync().body();
		}

		try (LoomHttpClient client = loginJoeDoe()) {
			assertNotNull(client.listTags().sync().body(), "READ_TAG is now in effect");
			expect(403, "Forbidden", client.loadAsset(ASSET_UUID));
		}
	}
}
