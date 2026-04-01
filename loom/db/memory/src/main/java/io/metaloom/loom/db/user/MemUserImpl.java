package io.metaloom.loom.db.user;

import io.metaloom.loom.db.mem.AbstractMemCUDElement;
import io.metaloom.loom.db.model.user.User;
import io.vertx.core.json.JsonObject;

public class MemUserImpl extends AbstractMemCUDElement<User> implements User {

	private String lastname;
	private String firstname;
	private String email;
	private String username;
	private String passwordHash;
	private JsonObject meta;
	private boolean sso;
	private boolean enabled;
	private boolean deleted;

	@Override
	public String getUsername() {
		return username;
	}

	@Override
	public User setUsername(String username) {
		this.username = username;
		return this;
	}

	@Override
	public String getEmail() {
		return email;
	}

	@Override
	public User setEmail(String email) {
		this.email = email;
		return this;
	}

	@Override
	public String getFirstname() {
		return firstname;
	}

	@Override
	public User setFirstname(String firstname) {
		this.firstname = firstname;
		return this;
	}

	@Override
	public String getLastname() {
		return lastname;
	}

	@Override
	public User setLastname(String lastname) {
		this.lastname = lastname;
		return this;
	}

	@Override
	public String getPasswordHash() {
		return passwordHash;
	}

	@Override
	public User setPasswordHash(String hash) {
		this.passwordHash = hash;
		return this;
	}

	@Override
	public JsonObject getMeta() {
		return meta;
	}

	@Override
	public User setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

	@Override
	public boolean isSSO() {
		return sso;
	}

	@Override
	public User setSSO(boolean flag) {
		this.sso = flag;
		return this;
	}

	@Override
	public boolean isEnabled() {
		return enabled;
	}

	@Override
	public User setEnabled(boolean flag) {
		this.enabled = flag;
		return this;
	}

	@Override
	public boolean isDeleted() {
		return deleted;
	}

	@Override
	public User setDeleted(boolean flag) {
		this.deleted = flag;
		return this;
	}
}
