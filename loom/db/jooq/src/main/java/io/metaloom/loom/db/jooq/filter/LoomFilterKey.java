package io.metaloom.loom.db.jooq.filter;

import io.metaloom.filter.key.impl.SizeFilterKey;
import io.metaloom.filter.key.impl.StringFilterKey;

public final class LoomFilterKey {

	public static final StringFilterKey USER_USERNAME = new StringFilterKey("username");

	public final static SizeFilterKey FILE_SIZE = new SizeFilterKey("size");

}
