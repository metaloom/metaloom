package io.metaloom.loom.core.dagger;

import dagger.Binds;
import dagger.Module;
import io.metaloom.loom.auth.AuthenticationService;
import io.metaloom.loom.auth.LoomAuthenticationHandler;
import io.metaloom.loom.auth.jwt.AuthenticationServiceImpl;
import io.metaloom.loom.auth.jwt.LoomJWTAuthHandlerImpl;

@Module
public abstract class AuthBindModule {

	@Binds
	abstract AuthenticationService bindAuthService(AuthenticationServiceImpl e);

	@Binds
	abstract LoomAuthenticationHandler bindAuthHandler(LoomJWTAuthHandlerImpl e);
}
