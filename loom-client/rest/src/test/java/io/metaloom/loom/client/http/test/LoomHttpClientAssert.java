package io.metaloom.loom.client.http.test;

import org.assertj.core.api.AbstractAssert;

import io.metaloom.loom.client.http.LoomHttpClient;

public class LoomHttpClientAssert extends AbstractAssert<LoomHttpClientAssert, LoomHttpClient> {

	protected LoomHttpClientAssert(LoomHttpClient actual) {
		super(actual, LoomHttpClientAssert.class);
	}

	public static LoomHttpClientAssert assertThat(LoomHttpClient actual) {
		return new LoomHttpClientAssert(actual);
	}

}
