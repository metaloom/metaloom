package io.metaloom.cortex;

import io.metaloom.cortex.api.option.CortexOptions;

public interface Cortex {

	void checkNodes();

	/**
	 * Start the Cortex instance. This will block the calling thread until {@link #shutdown()} is called.
	 *
	 * @return Fluent API
	 * @throws Exception
	 */
	Cortex run() throws Exception;

	/**
	 * Start the Cortex instance.
	 *
	 * @param block
	 *            Whether to block the calling thread
	 * @return Fluent API
	 * @throws Exception
	 */
	Cortex run(boolean block) throws Exception;

	/**
	 * Shutdown the running instance.
	 */
	void shutdown();

	/**
	 * Shutdown the instance and terminate the JVM.
	 *
	 * @param code
	 *            Exit code to return
	 */
	void shutdownAndTerminate(int code);

	/**
	 * Block the calling thread until shutdown.
	 *
	 * @throws InterruptedException
	 */
	void dontExit() throws InterruptedException;

	/**
	 * Return the actual monitoring port.
	 *
	 * @return
	 */
	Integer actualMonitoringPort();

	/**
	 * Create a new Cortex instance from the given options.
	 *
	 * @param options
	 * @return
	 */
	static Cortex create(CortexOptions options) {
		return CortexFactory.create(options);
	}

}
