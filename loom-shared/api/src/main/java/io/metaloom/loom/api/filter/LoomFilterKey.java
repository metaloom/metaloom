package io.metaloom.loom.api.filter;

import io.metaloom.filter.key.impl.SizeFilterKey;
import io.metaloom.filter.key.impl.StringFilterKey;

public final class LoomFilterKey {

	public static final StringFilterKey UUID = new StringFilterKey("uuid");

	public static final StringFilterKey NAME = new StringFilterKey("name");

	public static final StringFilterKey COLLECTION = new StringFilterKey("collection");

	public static final StringFilterKey USERNAME = new StringFilterKey("username");

	public final static SizeFilterKey FILE_SIZE = new SizeFilterKey("size");

}
