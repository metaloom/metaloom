package io.metaloom.loom.core.endpoint;

import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.LoomCoreTestExtension;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.role.Role;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public abstract class AbstractEndpointTest implements EndpointTest {

	@RegisterExtension
	protected LoomCoreTestExtension loom = new LoomCoreTestExtension();

	@Override
	public LoomHttpClient httpClient() {
		return loom.httpClient();
	}

	/**
	 * The DAO collection of the booted server, used to seed data or set up RBAC fixtures.
	 */
	protected DaoCollection daos() {
		return loom.internal().daos();
	}

	/**
	 * The admin user uuid, the creator/editor stamped onto seeded fixtures.
	 */
	protected UUID adminUuid() {
		return daos().userDao().loadAdmin().getUuid();
	}

	public void loginAdmin(LoomHttpClient client) throws LoomClientException {
		AuthLoginResponse loginResponse = client.login("admin", "finger").sync().body();
		client.setToken(loginResponse.getToken());
	}

	/**
	 * Provision a fresh, enabled user that holds <em>no</em> permissions and return a client already logged in as that user.
	 *
	 * <p>Authentication only requires a valid token, not any particular permission, so this user reaches the endpoint but is denied by every
	 * permission check — which is exactly what the RBAC security testcases assert.</p>
	 */
	protected LoomHttpClient loginPermissionlessClient() throws LoomClientException {
		User nobody = daos().userDao().createUser(adminUuid(), "nobody");
		nobody.enable();
		nobody.setPasswordHash(loom.internal().authService().encodePassword("secret"));
		daos().userDao().store(nobody);

		LoomHttpClient client = loom.httpClient();
		AuthLoginResponse login = client.login("nobody", "secret").sync().body();
		client.setToken(login.getToken());
		return client;
	}

	/**
	 * Provision a fresh, enabled user holding exactly the given permissions and return a client already logged in as that user.
	 *
	 * <p>
	 * For routes whose required permission set depends on the request: a caller holding one of the two permissions and not the other must be rejected,
	 * and a permissionless user cannot show that.
	 * </p>
	 *
	 * <p>
	 * The grant goes through a role and a group rather than {@code user_permission}, whose primary key allows only one direct grant per user.
	 * </p>
	 *
	 * @param username
	 *            must be unique within the test; the role and the group are named after it
	 */
	protected LoomHttpClient loginClientWith(String username, Permission... permissions) throws LoomClientException {
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
		AuthLoginResponse login = client.login(username, "secret").sync().body();
		client.setToken(login.getToken());
		return client;
	}

	public String generateJWT() {
		JsonArray claims = new JsonArray();
		claims.add(Permission.CREATE_ASSET.name());
		JsonObject userAttr = new JsonObject().put("claim", claims);
		return loom.internal().authService().generate(userAttr);
	}

	protected void expect(int statusCode, String statusMsg, LoomClientRequest<?> request) {
		try {
			request.sync().body();
			fail("The request should have failed with code " + statusCode + " and msg " + statusMsg + " but it succeeded.");
		} catch (LoomClientException e) {
			assertEquals(statusCode, e.getStatusCode(), "The status code did not match");
			assertEquals(statusMsg, e.getStatusMsg(), "The status message did not match up");
			// TODO assert body
		}

	}
}
