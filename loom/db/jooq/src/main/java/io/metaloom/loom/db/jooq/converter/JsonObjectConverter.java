package io.metaloom.loom.db.jooq.converter;

import org.jooq.Converter;
import org.jooq.JSONB;

import io.vertx.core.json.JsonObject;

public class JsonObjectConverter implements Converter<JSONB, JsonObject> {

	private static final long serialVersionUID = -4766448938052242634L;

	@Override
	public JsonObject from(JSONB databaseObject) {
		if (databaseObject == null) {
			return null;
		}
		return new JsonObject(databaseObject.data());
	}

	@Override
	public JSONB to(JsonObject userObject) {
		if (userObject == null) {
			return null;
		}
		return JSONB.jsonb(userObject.encode());
	}

	@Override
	public Class<JSONB> fromType() {
		return JSONB.class;
	}

	@Override
	public Class<JsonObject> toType() {
		return JsonObject.class;
	}
}
