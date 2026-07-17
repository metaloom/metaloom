package io.metaloom.loom.db.model.token;

import java.util.Optional;
import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.user.User;
import io.vertx.core.Future;

public interface TokenDao extends CRUDDao<Token> {

	default Token createToken(User user, String name, String tokenValue) {
		return createToken(user.getUuid(), name, tokenValue);
	}

	Token createToken(UUID userUuid, String name, String tokenValue);

	/**
	 * Find a token by its token value (API key).
	 * 
	 * @param tokenValue the token value to search for
	 * @return future with the token if found, empty otherwise
	 */
	Future<Optional<Token>> findByToken(String tokenValue);

}
