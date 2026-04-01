package io.metaloom.loom.db.model.token;

import io.metaloom.loom.db.CUDElement;

public interface Token extends CUDElement<Token> {

	String getName();

	Token setName(String name);

	String getToken();

	Token setToken(String token);

	String getDescription();

	Token setDescription(String description);

	String getUserUuid();

	Token setUserUuid(String userUuid);

}
