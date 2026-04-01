package io.metaloom.loom.api.filter;

import io.metaloom.filter.parser.impl.LHSFilterParserImpl;

public class LoomLHSFilterParser extends LHSFilterParserImpl {

	public LoomLHSFilterParser() {
		super();
		register(LoomFilterKey.FILE_SIZE);
		register(LoomFilterKey.USERNAME);
	}

}
