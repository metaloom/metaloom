package io.metaloom.loom.db.model.token;

import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.user.User;

public interface TokenDao extends CRUDDao<Token> {

	default Token createToken(User user, String name, String tokenValue) {
		return createToken(user.getUuid(), name, tokenValue);
	}

	Token createToken(UUID userUuid, String name, String tokenValue);

}
