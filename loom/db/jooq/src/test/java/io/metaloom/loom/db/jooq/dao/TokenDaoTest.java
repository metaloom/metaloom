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
		return getDao().createToken(user, "name_" + i, "TOKEN_VALUE");
	}

	@Override
	public void assertCreate(Token createdElement) {
		assertEquals("name_0", createdElement.getName());
		assertEquals("TOKEN_VALUE", createdElement.getToken());
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
