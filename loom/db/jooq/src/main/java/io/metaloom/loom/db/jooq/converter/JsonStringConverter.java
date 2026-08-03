package io.metaloom.loom.db.jooq.converter;

import org.jooq.Converter;
import org.jooq.JSONB;

/**
 * Maps a {@code jsonb} column to a plain {@link String}.
 *
 * <p>
 * Unlike {@link JsonObjectConverter}, this parses nothing. It exists for a column that is JSON only so
 * Postgres will index and query it — {@code node_descriptor.descriptor} holds a serialized
 * {@code NodeDescriptor} that goes straight to and from Jackson, and round-tripping it through a
 * {@code JsonObject} would re-parse and re-encode the document on every read for no gain, and would
 * drag a Vert.x type into {@code loom-db-api} in the process.
 * </p>
 */
public class JsonStringConverter implements Converter<JSONB, String> {

	private static final long serialVersionUID = 1L;

	@Override
	public String from(JSONB databaseObject) {
		return databaseObject == null ? null : databaseObject.data();
	}

	@Override
	public JSONB to(String userObject) {
		return userObject == null ? null : JSONB.jsonb(userObject);
	}

	@Override
	public Class<JSONB> fromType() {
		return JSONB.class;
	}

	@Override
	public Class<String> toType() {
		return String.class;
	}
}
