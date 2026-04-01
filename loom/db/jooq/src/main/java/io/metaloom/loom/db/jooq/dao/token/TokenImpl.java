package io.metaloom.loom.db.jooq.dao.token;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.token.Token;

public class TokenImpl extends AbstractEditableElement<Token> implements Token {

	private String name;

	private String token;

	private String userUuid;

	private String description;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public Token setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public String getToken() {
		return token;
	}

	@Override
	public Token setToken(String token) {
		this.token = token;
		return this;
	}

	@Override
	public String getDescription() {
		return description;
	}

	@Override
	public Token setDescription(String description) {
		this.description = description;
		return this;
	}

	@Override
	public String getUserUuid() {
		return userUuid;
	}

	@Override
	public Token setUserUuid(String userUuid) {
		this.userUuid = userUuid;
		return this;
	}
}
