package io.metaloom.loom.auth.jwt;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.loom.api.LoomEnv;
import io.metaloom.loom.api.options.AuthenticationOptions;
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

	@TempDir
	public File configFolder;

	@Test
	public void testVertxPerm() {

		// Authenticate
		LoomOptions options = new LoomOptions();
		options.getAuth().setKeystorePassword("ABCD");
		LoomOptionsLookup lookup = new LoomOptionsLookup(configFolder, options);
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

	/**
	 * {@link LoomOptionsLookup#baseConfigFolder()} is null whenever the configuration was read from the classpath instead of from a file, so the
	 * provider has to fall back to the default config folder rather than dereference it.
	 */
	@Test
	public void testKeystoreFolderFallbackForClasspathConfig() throws IOException {
		Path defaultFolder = LoomEnv.LOCAL_CONFIG_PATH.toAbsolutePath().getParent();
		Path keystore = defaultFolder.resolve(AuthenticationOptions.DEFAULT_KEYSTORE_FILENAME);
		boolean folderExisted = Files.exists(defaultFolder);
		try {
			LoomOptions options = new LoomOptions();
			options.getAuth().setKeystorePassword("ABCD");

			JWTAuth jwtAuth = new AuthModule().jwtAuthProvider(vertx, new LoomOptionsLookup(null, options));

			assertNotNull(jwtAuth, "A JWT provider should be created even without a config folder on disk.");
			assertTrue(Files.exists(keystore), "The keystore should have been generated in " + defaultFolder);
		} finally {
			Files.deleteIfExists(keystore);
			if (!folderExisted) {
				Files.deleteIfExists(defaultFolder);
			}
		}
	}
}
