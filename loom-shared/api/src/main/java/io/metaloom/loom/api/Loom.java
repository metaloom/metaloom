package io.metaloom.loom.api;

import io.metaloom.loom.api.options.LoomOptionsLookup;
import io.vertx.core.impl.ServiceHelper;

public interface Loom {

	static LoomFactory factory = ServiceHelper.loadFactory(LoomFactory.class);

	/**
	 * Returns the initialized instance.
	 * 
	 * @param optionsLookup
	 * 
	 * @return Fluent API
	 */
	public static Loom create(LoomOptionsLookup optionsLookup) {
		optionsLookup.options().validate();
		return factory.create(optionsLookup);
	}

	/**
	 * Start MetaLoom Loom. This will effectively block until {@link #shutdown()} is called from another thread. This method will initialize the dagger context
	 * and deploy mandatory services.
	 * 
	 * @return Fluent API
	 * @throws Exception
	 */
	Loom run() throws Exception;

	Loom run(boolean block) throws Exception;

	/**
	 * Shutdown the instance and terminate the JVM.
	 * 
	 * @param code
	 *            Exit code to return
	 */
	public void shutdownAndTerminate(int code);

	/**
	 * Shutdown the running instance.
	 */
	void shutdown();

	void dontExit() throws InterruptedException;

	static String getPlainVersion() {
		return LoomVersion.getPlainVersion();
	}

	Integer actualRestPort();

}
