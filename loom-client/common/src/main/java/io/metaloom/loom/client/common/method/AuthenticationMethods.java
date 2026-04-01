package io.metaloom.loom.client.common.method;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;

public interface AuthenticationMethods {

	LoomClientRequest<AuthLoginResponse> login(String username, String password);
}
