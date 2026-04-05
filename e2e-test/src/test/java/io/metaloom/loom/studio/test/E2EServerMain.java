package io.metaloom.loom.studio.test;

import java.io.File;

import io.metaloom.loom.api.Loom;
import io.metaloom.loom.api.options.AuthenticationOptions;
import io.metaloom.loom.api.options.DatabaseOptions;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.api.options.LoomOptionsLookup;
import io.metaloom.loom.api.options.ServerOptions;

/**
 * Standalone launcher that starts the Loom backend and keeps it running
 * until the process is killed.  Useful for manual / interactive E2E testing.
 */
public class E2EServerMain {

	public static void main(String[] args) throws Exception {
		String dbHost = System.getProperty("db.host", "localhost");
		int dbPort = Integer.getInteger("db.port", 5432);
		String dbName = System.getProperty("db.name", "loom_e2e");
		String dbUser = System.getProperty("db.user", "loom");
		String dbPass = System.getProperty("db.password", "loom");
		int restPort = Integer.getInteger("rest.port", 8092);

		LoomOptions options = new LoomOptions();

		DatabaseOptions dbOptions = new DatabaseOptions();
		dbOptions.setHost(dbHost);
		dbOptions.setPort(dbPort);
		dbOptions.setUsername(dbUser);
		dbOptions.setPassword(dbPass);
		dbOptions.setDatabaseName(dbName);
		options.setDatabase(dbOptions);

		ServerOptions serverOptions = options.getServer();
		serverOptions.setBindAddress("localhost");
		serverOptions.setRestPort(restPort);
		serverOptions.setGrpcPort(0);

		AuthenticationOptions authOptions = options.getAuth();
		authOptions.setKeystorePassword("finger");

		File baseFolder = new File("target", "e2e-test-config");
		File keystoreFile = new File(baseFolder, "keystore.jceks");
		if (keystoreFile.exists()) {
			keystoreFile.delete();
		}

		LoomOptionsLookup lookup = new LoomOptionsLookup(baseFolder, options);
		Loom server = Loom.create(lookup);
		server.run(false);
		int actualPort = server.actualRestPort();
		System.out.println("Loom backend started on port " + actualPort + "  –  press Ctrl+C to stop");
		Thread.currentThread().join(); // block forever
	}
}
