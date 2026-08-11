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
