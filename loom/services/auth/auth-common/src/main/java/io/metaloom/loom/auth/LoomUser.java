package io.metaloom.loom.auth;

import java.util.UUID;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;

public class LoomUser implements User {

	private User delegate;

	public LoomUser(User user) {
		this.delegate = user;
	}

	@Override
	public JsonObject attributes() {
		return delegate.attributes();
	}

	@Override
	public JsonObject principal() {
		return delegate.principal();
	}

	@Override
	public User merge(User other) {
		return delegate.merge(other);
	}

	public UUID getUuid() {
		return UUID.fromString(get("uuid"));
	}
}
