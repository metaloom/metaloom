package io.metaloom.loom.db.namespace;

import org.junit.ClassRule;
import org.junit.Rule;

import io.metaloom.loom.test.container.LoomTestContext;
import io.metaloom.loom.test.dagger.LoomJooqTestComponent;

public class JooqNamespaceDaoTest extends AbstractNamespaceDaoTest {

	@Rule
	@ClassRule
	public static LoomTestContext context = new LoomTestContext();

	public LoomNamespaceDao getDao() {
		LoomJooqTestComponent loomTest = context.component();
		return loomTest.daos().getNamespaceDao();
	}

}
