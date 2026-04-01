package io.metaloom.loom.rest.builder;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.model.token.Token;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.token.TokenListResponse;

public class TokenModelBuilderTest extends AbstractModelBuilderTest {

	@Test
	@Override
	void testResponseModel() throws IOException {
		Token token = mockToken("primary");
		assertWithModel(builder().toResponse(token, true), "token.response_with_token");
		assertWithModel(builder().toResponse(token, false), "token.response_no_token");
	}

	@Test
	@Override
	void testListResponseModel() throws IOException {
		Token token1 = mockToken("primary");
		Token token2 = mockToken("secondary");
		Page<Token> page = mockPage(token1, token2);
		TokenListResponse list = builder().toTokenList(page);
		assertWithModel(list, "token.list_response");
	}

	private Token mockToken(String name) {
		Token token = mock(Token.class);
		when(token.getUuid()).thenReturn(TOKEN_UUID);
		when(token.getName()).thenReturn(name);
		return token;
	}

}
