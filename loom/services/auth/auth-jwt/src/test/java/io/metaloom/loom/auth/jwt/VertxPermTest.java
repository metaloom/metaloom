package io.metaloom.loom.auth.jwt;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.api.options.LoomOptionsLookup;
import io.metaloom.loom.db.model.perm.Permission;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.authorization.Authorization;
import io.vertx.ext.auth.authorization.AuthorizationProvider;
import io.vertx.ext.auth.authorization.PermissionBasedAuthorization;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.authorization.JWTAuthorization;

public class VertxPermTest {

	public static Vertx vertx = Vertx.vertx();

	@Test
	public void testVertxPerm() {

		// Authenticate
		LoomOptions options = new LoomOptions();
		options.getAuth().setKeystorePassword("ABCD");
		LoomOptionsLookup lookup = new LoomOptionsLookup(null, options);
		JWTAuth jwtAuth = new AuthModule().jwtAuthProvider(vertx, lookup);

		AuthorizationProvider authProvider = JWTAuthorization.create("claim");

		JsonArray claims = new JsonArray();
		claims.add(Permission.CREATE_ASSET.name());
		JsonObject userAttr = new JsonObject().put("claim", claims);
		String token = jwtAuth.generateToken(userAttr);
		System.out.println("Token: " + token);
		User user = jwtAuth.authenticate(new TokenCredentials(token)).result();

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
