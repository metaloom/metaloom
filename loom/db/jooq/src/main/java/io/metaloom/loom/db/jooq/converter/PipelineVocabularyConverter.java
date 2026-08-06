package io.metaloom.loom.db.jooq.converter;

import java.util.function.BiFunction;

import org.jooq.Converter;

/**
 * Maps a {@code VARCHAR} status/state column onto its Java enum.
 *
 * <p>
 * The columns stay {@code VARCHAR} on purpose — a Postgres enum needs a migration for every new
 * value — so this is the only place a bad string can be caught. A value outside the vocabulary is
 * rejected here, naming the column and the value, rather than being handed on as a status the UI
 * cannot switch on.
 * </p>
 *
 * <p>
 * jOOQ instantiates a forced-type converter reflectively through its no-argument constructor, which
 * is why each column gets its own trivial subclass rather than one parameterised instance.
 * </p>
 *
 * @param <E> the vocabulary
 */
public abstract class PipelineVocabularyConverter<E extends Enum<E>> implements Converter<String, E> {

	private static final long serialVersionUID = 1L;

	private final Class<E> type;
	private final String column;
	private final BiFunction<String, String, E> parser;

	/**
	 * @param type   the vocabulary
	 * @param column the qualified column name, used in the failure message
	 * @param parser the vocabulary's own {@code parse(column, value)}
	 */
	protected PipelineVocabularyConverter(Class<E> type, String column, BiFunction<String, String, E> parser) {
		this.type = type;
		this.column = column;
		this.parser = parser;
	}

	@Override
	public E from(String databaseObject) {
		return parser.apply(column, databaseObject);
	}

	@Override
	public String to(E userObject) {
		return userObject == null ? null : userObject.name();
	}

	@Override
	public Class<String> fromType() {
		return String.class;
	}

	@Override
	public Class<E> toType() {
		return type;
	}
}
