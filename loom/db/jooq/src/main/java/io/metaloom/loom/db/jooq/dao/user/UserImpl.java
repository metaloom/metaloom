package io.metaloom.loom.db.jooq.dao.user;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.user.User;

public class UserImpl extends AbstractEditableElement<User> implements User {

	private String username;
	private String email;
	private String firstname;
	private String lastname;
	private String passwordHash;
	private boolean enabled;
	private boolean deleted;
	private boolean sso;

	public UserImpl() {
	}

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
	public String getPasswordHash() {
		return passwordHash;
	}

	@Override
	public User setPasswordHash(String hash) {
		this.passwordHash = hash;
		return this;
	}

}
