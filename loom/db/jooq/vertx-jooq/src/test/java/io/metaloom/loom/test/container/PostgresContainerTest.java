package io.metaloom.loom.test.container;

import org.junit.Test;

import io.metaloom.loom.db.namespace.LoomNamespaceDao;

public class PostgresContainerTest extends LoomTestContext {

	@Test
	public void testContainer() {
		LoomNamespaceDao dao = component().daos().getNamespaceDao();
		dao.createNamespace("test123").blockingGet();
	}
}
