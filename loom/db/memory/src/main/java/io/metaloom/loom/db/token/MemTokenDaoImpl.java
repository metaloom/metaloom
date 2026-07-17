package io.metaloom.loom.db.token;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.db.mem.AbstractMemDao;
import io.metaloom.loom.db.model.token.Token;
import io.metaloom.loom.db.model.token.TokenDao;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/**
 * In-memory implementation of TokenDao for testing.
 */
@Singleton
public class MemTokenDaoImpl extends AbstractMemDao<Token> implements TokenDao {

	private final ConcurrentMap<String, Token> tokenIndex = new ConcurrentHashMap<>();

	@Inject
	public MemTokenDaoImpl() {
		super();
	}

	@Override
	public String getTypeName() {
		return "Tokens";
	}

	@Override
	public Token createToken(UUID userUuid, String name, String tokenValue) {
		Token token = new MemTokenImpl();
		token.setName(name);
		token.setToken(tokenValue);
		token.setUserUuid(userUuid.toString());
		store(token);
		return token;
	}

	@Override
	public void store(Token element) {
		super.store(element);
		tokenIndex.put(element.getToken(), element);
	}

	@Override
	public void delete(UUID id) {
		Token token = storage.remove(id);
		if (token != null) {
			tokenIndex.remove(token.getToken());
		}
	}

	@Override
	public Future<Optional<Token>> findByToken(String tokenValue) {
		return Future.succeededFuture(Optional.ofNullable(tokenIndex.get(tokenValue)));
	}

	/**
	 * In-memory token implementation.
	 */
	public static class MemTokenImpl extends io.metaloom.loom.db.mem.AbstractMemCUDElement<Token> implements Token {

		private String name;
		private String token;
		private String userUuid;
		private String description;
		private JsonObject meta = new JsonObject();

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

		@Override
		public JsonObject getMeta() {
			return meta;
		}

		@Override
		public Token setMeta(JsonObject meta) {
			this.meta = meta;
			return this;
		}
	}
}