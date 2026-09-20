package io.metaloom.loom.auth;

import io.metaloom.loom.db.model.user.User;
import io.vertx.core.json.JsonObject;

public interface AuthenticationService {

	void verify(String token);

	/**
	 * Generate a JWT using the provided credential information.
	 * 
	 * @param json
	 * @return
	 */
	String generate(JsonObject json);

	/**
	 * Generate a JWT with an explicit lifetime, for a credential that is not a browser session.
	 *
	 * <p>
	 * A media token is the case this exists for: it rides in a query string, where it can be logged by a proxy, pasted into a chat or left in a
	 * browser history, so it must expire in minutes rather than share the session's hour. The caller supplies the claims that scope it.
	 * </p>
	 *
	 * @param json
	 *            claims to embed
	 * @param expiresInSeconds
	 *            lifetime of the token
	 * @return the signed token
	 */
	String generate(JsonObject json, int expiresInSeconds);

	User login(String username, String password);

	String encodePassword(String password);

	/**
	 * Check a plaintext password against a stored hash.
	 *
	 * <p>
	 * Exposed so that credentials which are not user accounts - a share link's password - are verified by the same encoder, at the same cost factor,
	 * as a login. A second {@code BCryptPasswordEncoder} constructed elsewhere would be a second place for the work factor to drift, and the cost
	 * factor is the only thing standing between an unauthenticated password endpoint and an offline guessing loop.
	 * </p>
	 *
	 * @param password
	 *            the plaintext candidate
	 * @param hash
	 *            the stored bcrypt hash
	 * @return true when the password matches; false when it does not, or when either argument is null
	 */
	boolean matchesPassword(String password, String hash);

}
