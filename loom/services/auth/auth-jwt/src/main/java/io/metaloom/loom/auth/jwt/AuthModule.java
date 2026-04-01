package io.metaloom.loom.auth.jwt;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dagger.Module;
import dagger.Provides;
import io.metaloom.loom.api.options.AuthenticationOptions;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.api.options.LoomOptionsLookup;
import io.metaloom.loom.auth.KeyStoreHelper;
import io.vertx.core.Vertx;
import io.vertx.ext.auth.KeyStoreOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;

@Module
public class AuthModule {

	private static final Logger log = LoggerFactory.getLogger(AuthModule.class);

	@Provides
	public JWTAuth jwtAuthProvider(Vertx vertx, LoomOptionsLookup optionsLookup) {
		LoomOptions options = optionsLookup.options();
		File basePath = optionsLookup.baseConfigFolder();
		Path keystorePath = basePath.toPath().resolve(AuthenticationOptions.DEFAULT_KEYSTORE_FILENAME);

		AuthenticationOptions loomAuthOptions = options.getAuth();

		if (!Files.exists(keystorePath)) {
			log.info("Creating keystore file {}", keystorePath);
			String pass = options.getAuth().getKeystorePassword();
			try {
				KeyStoreHelper.gen(keystorePath.toAbsolutePath().toString(), pass);
			} catch (Exception e) {
				throw new RuntimeException("Failure while creating keystore " + keystorePath, e);
			}
		} else {
			log.info("Using keystore location {}", keystorePath);
		}

		KeyStoreOptions keyStoreOptions = new KeyStoreOptions().setPath(keystorePath.toAbsolutePath().toString())
			.setPassword(loomAuthOptions.getKeystorePassword())
			.setType("jceks");

		JWTAuthOptions config = new JWTAuthOptions().setKeyStore(keyStoreOptions);
		return JWTAuth.create(vertx, config);
	}
}
