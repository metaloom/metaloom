package io.metaloom.loom.db.jooq.converter;

import org.jooq.Converter;
import org.jooq.JSONB;

import io.vertx.core.json.JsonArray;

public class JsonArrayConverter implements Converter<JSONB, JsonArray> {

	private static final long serialVersionUID = 4967877712775298157L;

	@Override
	public JsonArray from(JSONB databaseObject) {
		if (databaseObject == null) {
			return null;
		}
		return new JsonArray(databaseObject.data());
	}

	@Override
	public JSONB to(JsonArray userObject) {
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
	public Class<JsonArray> toType() {
		return JsonArray.class;
	}
}
