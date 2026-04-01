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

}
