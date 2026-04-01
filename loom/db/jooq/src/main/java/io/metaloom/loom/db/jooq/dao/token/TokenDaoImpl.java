package io.metaloom.loom.db.jooq.dao.token;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.tables.JooqToken;
import io.metaloom.loom.db.model.token.Token;
import io.metaloom.loom.db.model.token.TokenDao;

@Singleton
public class TokenDaoImpl extends AbstractJooqDao<Token> implements TokenDao {

	@Inject
	public TokenDaoImpl(DSLContext ctx) {
		super(ctx);
	}
	
	@Override
	public String getTypeName() {
		return "Tokens";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return JooqToken.TOKEN;
	}

	@Override
	protected Class<? extends Token> getPojoClass() {
		return TokenImpl.class;
	}

	@Override
	public Token createToken(UUID userUuid, String name, String tokenValue) {
		Token token = new TokenImpl();
		token.setName(name);
		token.setToken(tokenValue);
		setCreatorEditor(token, userUuid);
		return token;
	}
}
