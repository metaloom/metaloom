package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_TOKEN;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_TOKEN;
import static io.metaloom.loom.db.model.perm.Permission.READ_TOKEN;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_TOKEN;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.token.Token;
import io.metaloom.loom.db.model.token.TokenDao;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.token.TokenCreateRequest;
import io.metaloom.loom.rest.model.token.TokenUpdateRequest;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.utils.StringUtils;

@Singleton
public class TokenEndpointService extends AbstractCRUDEndpointService<TokenDao, Token> {

	@Inject
	public TokenEndpointService(TokenDao tokenDao, DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(tokenDao, daos, modelBuilder, validator);
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID id) {
		delete(lrc, DELETE_TOKEN, id);
	}

	@Override
	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_TOKEN, modelBuilder::toTokenList);
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID id) {
		load(lrc, READ_TOKEN, () -> {
			return dao().load(id);
		}, modelBuilder::toResponse);
	}

	@Override
	public void create(LoomRoutingContext lrc) {
		create(lrc, CREATE_TOKEN, () -> {
			TokenCreateRequest request = lrc.requestBody(TokenCreateRequest.class);
			validator.validate(request);

			String name = request.getName();
			UUID userUuid = lrc.userUuid();
			String tokenValue = StringUtils.randomHumanString(8);
			Token token = dao().createToken(userUuid, name, tokenValue);
			update(request::getMeta, token::setMeta);
			return token;
		}, modelBuilder::toResponseWithToken);
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID id) {
		update(lrc, UPDATE_TOKEN, () -> {
			TokenUpdateRequest request = lrc.requestBody(TokenUpdateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			Token token = dao().load(id);

			update(request::getName, token::setName);
			update(request::getMeta, token::setMeta);
			setEditor(token, userUuid);
			return token;
		}, modelBuilder::toResponse);
	}

}
