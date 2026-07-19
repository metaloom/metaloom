package io.metaloom.loom.test.container;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * The Loom server, running from the published demo image.
 *
 * <p>Configured entirely through environment variables, which is the point: the JVM
 * based tests build {@code LoomOptions} by hand and so never touch the
 * {@code @EnvironmentVariable} binding that a real deployment depends on. If that
 * binding breaks, this container fails to find its database and the test says so.</p>
 */
public class LoomContainer extends GenericContainer<LoomContainer> {

	public static final String DEFAULT_IMAGE = "metaloom/loom-demo:latest";

	public static final int REST_PORT = 8092;

	/** Matches {@code LOOM_INITIAL_PASSWORD} below - the admin user the image bootstraps. */
	public static final String ADMIN_USERNAME = "admin";
	public static final String ADMIN_PASSWORD = "finger";

	public LoomContainer(Network network, String databaseAlias, String databaseName, String username, String password) {
		super(System.getProperty("loom.image", DEFAULT_IMAGE));
		withNetwork(network);
		withNetworkAliases("loom");
		withExposedPorts(REST_PORT);

		withEnv("LOOM_INITIAL_PASSWORD", ADMIN_PASSWORD);
		// The database is addressed by its network alias, not localhost - both live on
		// the same user defined network.
		withEnv("LOOM_DB_HOST", databaseAlias);
		withEnv("LOOM_DB_PORT", "5432");
		withEnv("LOOM_DB_NAME", databaseName);
		withEnv("LOOM_DB_USERNAME", username);
		withEnv("LOOM_DB_PASSWORD", password);

		// Loom runs its own Flyway migration at bootstrap, so an empty database is
		// expected here. Waiting on the health endpoint rather than a log line means
		// the wait is tied to the server actually serving requests.
		waitingFor(Wait.forHttp("/api/v1/health")
			.forPort(REST_PORT)
			.forStatusCode(200)
			.withStartupTimeout(Duration.ofMinutes(3)));
	}

	/** Port on the host that maps to the container's REST port. */
	public int restPort() {
		return getMappedPort(REST_PORT);
	}
}
