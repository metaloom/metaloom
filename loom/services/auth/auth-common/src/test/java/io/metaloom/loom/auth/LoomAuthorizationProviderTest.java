package io.metaloom.loom.auth;

import static org.mockito.Mockito.mock;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.model.perm.PermissionDao;
import io.metaloom.loom.db.model.perm.Permission;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authorization.Authorization;
import io.vertx.ext.auth.authorization.PermissionBasedAuthorization;
import io.vertx.ext.auth.impl.UserImpl;

public class LoomAuthorizationProviderTest {

	public static Vertx vertx = Vertx.vertx();

	@Test
	public void testBasics() {

		PermissionDao permDao = mock(PermissionDao.class);
		PermissionCache cache = new PermissionCache();
		LoomAuthorizationProvider authProvider = new LoomAuthorizationProvider(permDao, cache);

		User user = new UserImpl(new JsonObject(), new JsonObject());

		// Authorize
		// PermissionBasedAuthorization perm1 = PermissionBasedAuthorization.create(Permissions.CREATE_ASSET.name());
		// user.authorizations().add("test", perm1);
		user.authorizations().forEach("test", auth -> {
			System.out.println("Auth " + auth);
		});
		authProvider.getAuthorizations(user)
			.onSuccess(done -> {
				// cache is populated, perform query
				if (PermissionBasedAuthorization.create(Permission.CREATE_ASSET.name()).match(user)) {
					System.out.println("User has the authority");
				} else {
					System.out.println("User does not have the authority");
				}
			}).onFailure(error -> {
				error.printStackTrace();
			});
	}
}
