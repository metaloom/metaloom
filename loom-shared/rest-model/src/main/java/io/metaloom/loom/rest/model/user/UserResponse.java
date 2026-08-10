package io.metaloom.loom.rest.model.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class UserResponse extends AbstractCreatorEditorRestResponse<UserResponse> implements UserModel<UserResponse> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("The username of the user.")
	private String username;

	@JsonProperty(required = false)
	@JsonPropertyDescription("The firstname of the user.")
	private String firstname;

	@JsonPropertyDescription("The lastname of the user.")
	@JsonProperty(required = false)
	private String lastname;

	@JsonProperty(required = false)
	@JsonPropertyDescription("The email address of the user.")
	private String email;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Flag that indicates whether the user is enabled")
	private boolean enabled;

	@JsonProperty(required = false)
	@JsonPropertyDescription("URL of the user's avatar picture, or null when they have none. Read-only: upload one via POST /users/:uuid/avatar or POST /me/avatar.")
	private String avatarUrl;

	public UserResponse() {
	}

	public String getAvatarUrl() {
		return avatarUrl;
	}

	public UserResponse setAvatarUrl(String avatarUrl) {
		this.avatarUrl = avatarUrl;
		return this;
	}

	@Override
	public String getUsername() {
		return username;
	}

	@Override
	public UserResponse setUsername(String username) {
		this.username = username;
		return this;
	}

	@Override
	public String getFirstname() {
		return firstname;
	}

	@Override
	public UserResponse setFirstname(String firstname) {
		this.firstname = firstname;
		return this;
	}

	@Override
	public String getLastname() {
		return lastname;
	}

	@Override
	public UserResponse setLastname(String lastname) {
		this.lastname = lastname;
		return this;
	}

	@Override
	public String getEmail() {
		return email;
	}

	@Override
	public UserResponse setEmail(String email) {
		this.email = email;
		return this;
	}

	@Override
	public UserResponse self() {
		return this;
	}

}
