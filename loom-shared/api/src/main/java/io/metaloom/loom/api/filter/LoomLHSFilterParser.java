package io.metaloom.loom.api.filter;

import io.metaloom.filter.parser.impl.LHSFilterParserImpl;

/**
 * Parses the {@code ?filter=} query string into {@link io.metaloom.filter.Filter} objects.
 *
 * <p>
 * <b>Every key a DAO implements has to be registered here.</b> The parser throws
 * {@link io.metaloom.filter.FilterException} for a key it does not know, before any DAO is reached, so an unregistered key makes the DAO branch that
 * handles it dead code over REST. {@code NAME}, {@code COLLECTION} and {@code UUID} were implemented in {@code TagDaoImpl}, {@code LibraryDaoImpl}
 * and {@code AbstractJooqDao} but missing from this list, and were therefore unreachable from a request.
 * </p>
 */
public class LoomLHSFilterParser extends LHSFilterParserImpl {

	public LoomLHSFilterParser() {
		super();
		register(LoomFilterKey.UUID);
		register(LoomFilterKey.NAME);
		register(LoomFilterKey.COLLECTION);
		register(LoomFilterKey.CREATOR);
		register(LoomFilterKey.EDITOR);
		register(LoomFilterKey.FILE_SIZE);
		register(LoomFilterKey.USERNAME);
		register(LoomFilterKey.STATUS);
		register(LoomFilterKey.DRY_RUN);
		register(LoomFilterKey.ENABLED);
		register(LoomFilterKey.PUBLISHED);
		register(LoomFilterKey.TYPE);
		register(LoomFilterKey.PRIORITY);
	}

}
