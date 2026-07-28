package io.metaloom.loom.test.container;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * A MinIO server, standing in for S3-compatible object storage.
 *
 * <p>Hand-rolled rather than using {@code org.testcontainers:minio}: this project pins
 * Testcontainers at 1.17.6, which predates {@code MinIOContainer}. Bumping it would reach
 * {@code e2e-test}, {@code cortex/core}, {@code cortex/cli}, {@code loom-client} and the jOOQ
 * codegen plugin's own Postgres container - unrelated blast radius for a node change. This class
 * matches the style of {@link LoomContainer} and {@link CortexContainer}, which are hand-rolled
 * for their own reasons.</p>
 */
public class MinioContainer extends GenericContainer<MinioContainer> {

	public static final String DEFAULT_IMAGE = "minio/minio:RELEASE.2025-04-22T22-12-26Z";

	public static final int API_PORT = 9000;
	public static final int CONSOLE_PORT = 9001;

	public static final String ALIAS = "minio";
	public static final String ACCESS_KEY = "minioadmin";
	public static final String SECRET_KEY = "minioadmin";
	public static final String REGION = "us-east-1";

	public MinioContainer() {
		this(null);
	}

	public MinioContainer(Network network) {
		super(System.getProperty("minio.image", DEFAULT_IMAGE));
		if (network != null) {
			withNetwork(network);
			withNetworkAliases(ALIAS);
		}
		withEnv("MINIO_ROOT_USER", ACCESS_KEY);
		withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY);
		withCommand("server", "/data", "--console-address", ":" + CONSOLE_PORT);
		withExposedPorts(API_PORT, CONSOLE_PORT);
		// Readiness, not merely process start: the API answers this only once the object layer
		// is initialised, and a bucket created before that fails.
		waitingFor(Wait.forHttp("/minio/health/live").forPort(API_PORT)
			.withStartupTimeout(Duration.ofMinutes(2)));
	}

	/**
	 * @return the endpoint reachable from the test JVM
	 */
	public String endpoint() {
		return "http://" + getHost() + ":" + getMappedPort(API_PORT);
	}

	/**
	 * @return the endpoint reachable from other containers on the shared network
	 */
	public String internalEndpoint() {
		return "http://" + ALIAS + ":" + API_PORT;
	}
}
