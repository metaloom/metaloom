package io.metaloom.loom.test.container;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;


/**
 * Testcontainer implementation for a Loom container which can be used in unit tests.
 */
public class LoomContainer extends GenericContainer<LoomContainer> {

	public static final String DEFAULT_VERSION = "v1.0.0";

	public static final int HTTP_PORT = 6333;

	public static final int GRPC_PORT = 6334;

	public LoomContainer() {
		super("metaloom/loom:" + DEFAULT_VERSION);
	}

	public LoomContainer(String version) {
		super("metaloom/loom:" + version);
	}

	@Override
	protected void configure() {
		withLogConsumer(c -> {
			System.out.print(c.getUtf8String());
		});

		withExposedPorts(HTTP_PORT, GRPC_PORT);
		withStartupTimeout(Duration.ofSeconds(15L));
		waitingFor(Wait.forHttp("/").forPort(HTTP_PORT));
	}

	public int grpcPort() {
		return getMappedPort(GRPC_PORT);
	}

	public int httpPort() {
		return getMappedPort(HTTP_PORT);
	}
}
