package io.metaloom.loom.rest.model.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;

public class AuthLoginRequest implements RestRequestModel {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Username that should be used for the login process.")
	private String username;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Password that should be used for the login process.")
	private String password;

	public AuthLoginRequest() {
	}

	public String getUsername() {
		return username;
	}

	public AuthLoginRequest setUsername(String username) {
		this.username = username;
		return this;
	}

	public String getPassword() {
		return password;
	}

	public AuthLoginRequest setPassword(String password) {
		this.password = password;
		return this;
	}
}
