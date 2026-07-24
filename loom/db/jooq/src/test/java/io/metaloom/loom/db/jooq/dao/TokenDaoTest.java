package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.token.Token;
import io.metaloom.loom.db.model.token.TokenDao;
import io.metaloom.loom.db.model.user.User;

public class TokenDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<TokenDao, Token> {

	@Override
	public TokenDao getDao() {
		return tokenDao();
	}

	@Override
	public Token createElement(User user, int i) {
		// token.token is UNIQUE - every element needs its own value.
		return getDao().createToken(user, "name_" + i, "TOKEN_VALUE_" + i);
	}

	@Override
	public void assertCreate(Token createdElement) {
		assertEquals("name_0", createdElement.getName());
		assertEquals("TOKEN_VALUE_0", createdElement.getToken());
	}

	@Override
	public void assertUpdate(Token updatedElement) {
		assertEquals("new_name", updatedElement.getName());
	}

	@Override
	public void updateElement(Token token) {
		token.setName("new_name");
	}

}
