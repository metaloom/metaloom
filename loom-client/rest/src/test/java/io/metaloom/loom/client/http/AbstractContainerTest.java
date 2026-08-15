package io.metaloom.loom.client.http;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.metaloom.loom.test.container.LoomContainer;
import io.metaloom.loom.test.data.TestValues;

/**
 * Base class for client tests that need a running Loom server.
 *
 * <p>{@link LoomContainer} starts {@code metaloom/loom:v1.0.0}, an image this repository does not build - see
 * {@code loom/containers/build-containers.sh}, which produces {@code metaloom/loom-server} and {@code metaloom/loom-demo}. Subclasses are therefore
 * disabled. The same ground - the real {@code LoomHttpClient} against a real server over real HTTP - is covered by {@code integration-test/}, which
 * brings up the demo image together with the PostgreSQL instance it needs.</p>
 */
@Testcontainers
public abstract class AbstractContainerTest implements TestValues {

	@Container
	public LoomContainer loom = new LoomContainer();

	protected void sleep(int i) {
		try {
			Thread.sleep(i);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

}
