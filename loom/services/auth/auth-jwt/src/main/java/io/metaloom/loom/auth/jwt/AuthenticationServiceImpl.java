package io.metaloom.loom.auth.jwt;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.auth.AuthenticationService;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.model.user.UserDao;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.jwt.JWTAuth;

public class AuthenticationServiceImpl implements AuthenticationService {

	private static Logger log = LoggerFactory.getLogger(AuthenticationServiceImpl.class);

	private static final int PASSWORD_HASH_LOGROUND_COUNT = 10;

	private final LoomOptions options;
	private final JWTAuth authProvider;
	private final UserDao userDao;
	private BCryptPasswordEncoder passwordEncoder;

	@Inject
	public AuthenticationServiceImpl(LoomOptions options, UserDao userDao, JWTAuth authProvider) {
		this.options = options;
		this.userDao = userDao;
		this.authProvider = authProvider;
		this.passwordEncoder = new BCryptPasswordEncoder(PASSWORD_HASH_LOGROUND_COUNT);
	}

	@Override
	public void verify(String token) {
		System.out.println("Verify of " + token);
	}

	@Override
	public String generate(JsonObject claims) {
		return authProvider.generateToken(claims, new JWTOptions().setIgnoreExpiration(true));
	}

	@Override
	public String encodePassword(String password) {
		return passwordEncoder.encode(password);
	}

	@Override
	public User login(String username, String password) {
		User user = userDao.loadByUsername(username);
		if (user == null) {
			return null;
		}
		String accountPasswordHash = user.getPasswordHash();
		System.out.println(user.getClass().getName());
		System.out.println("PasswordHash: " + accountPasswordHash + " for " + user.getUsername());
		boolean hashMatches = passwordEncoder.matches(password, accountPasswordHash);
		if (hashMatches) {
			return user;
		} else {
			return null;
		}
	}

}
