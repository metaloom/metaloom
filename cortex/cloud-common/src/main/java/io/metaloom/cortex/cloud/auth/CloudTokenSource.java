package io.metaloom.cortex.cloud.auth;

import java.io.IOException;

/**
 * Supplies the bearer token a cloud API call is made with.
 *
 * <p>Implementations cache: a token is good for around an hour and a worker may run dozens of node
 * tasks a minute, so fetching per request would turn every download into two round trips.</p>
 */
public interface CloudTokenSource {

	/**
	 * @return a currently valid access token
	 * @throws IOException when a token cannot be obtained
	 */
	String accessToken() throws IOException;

	/**
	 * Discard the cached token.
	 *
	 * <p>Called when the API answers {@code 401}, which means the token was revoked or expired
	 * earlier than its stated lifetime - the one case a cache cannot predict.</p>
	 */
	void invalidate();

	/**
	 * @return a stable identity for the credential, used to scope persisted scan indexes so two
	 *         credentials cannot share and corrupt one index
	 */
	String accountId();
}
