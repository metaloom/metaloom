package io.metaloom.loom.core;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.core.impl.LoomFactoryImpl;

/**
 * {@link LoomImpl#run(boolean)} used to swallow every exception thrown while booting the Dagger component: it logged the failure and returned
 * normally, leaving {@code shutdown = false} as if the instance had started successfully. {@code LoomServerRunner} and {@code LoomDemoRunner} both
 * wrap {@code loom.run()} in a try/catch that calls {@code shutdownAndTerminate} on failure - with the exception swallowed inside {@code run()}
 * that catch never triggered, so a boot failure left the process sitting on the shutdown latch forever: no server listening, no non-zero exit
 * code, indistinguishable from a slow-starting healthy instance to any orchestrator watching the process.
 *
 * <p>
 * {@code run(false)} with a null options lookup is a boot failure that needs no database or Vert.x instance to provoke: Dagger's generated builder
 * runs {@code Preconditions.checkNotNull} on the bound configuration before anything else, so the failure happens synchronously and before any
 * component wiring, which is exactly the "exception part-way through boot" case the fix is about.
 * </p>
 */
public class LoomImplTest {

	@Test
	@DisplayName("run(false) propagates a boot failure instead of swallowing it")
	public void shouldPropagateBootFailure() throws Exception {
		LoomImpl loom = (LoomImpl) new LoomFactoryImpl().create(null);
		assertThrows(NullPointerException.class, () -> loom.run(false),
			"a failure while building the Dagger component must reach the caller, not just the log");
	}

	@Test
	@DisplayName("shutdown() after a failed run() is a no-op, not an NPE on the never-built component")
	public void shouldTolerateShutdownAfterFailedRun() throws Exception {
		LoomImpl loom = (LoomImpl) new LoomFactoryImpl().create(null);
		assertThrows(NullPointerException.class, () -> loom.run(false));
		// Must not throw: a failed run() has to leave the instance flagged as shut down again, since
		// loomInternal was never assigned and shutdown() would otherwise dereference it.
		loom.shutdown();
	}
}
