package io.metaloom.loom.core;


import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.loom.test.LoomProviderExtension;

public class ExampleJunit5Test {

	@RegisterExtension
	public static LoomProviderExtension ext = LoomProviderExtension.create();

	@Test
	public void testDB() throws Exception {
		System.out.println(ext.db());
	}

	@Test
	public void testDB2() {
		System.out.println(ext.db());
	}
}
