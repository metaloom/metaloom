package io.metaloom.loom.client.common;

import io.metaloom.loom.client.common.method.ClientMethods;

public interface LoomClient extends ClientMethods, CommonSettings, AutoCloseable {

	/**
	 * Set the token to be used for authentication.
	 *
	 * @param token
	 */
	void setToken(String token);

	/**
	 * Close the client and release all resources.
	 */
	void close();
}
