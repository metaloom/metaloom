package io.metaloom.loom.core.endpoint;

import org.junit.jupiter.api.Test;

public interface CRUDEndpointTestcases extends EndpointTest {

	@Test
	void testRead() throws Exception;

	@Test
	void testCreate() throws Exception;

	@Test
	void testDelete() throws Exception;

	@Test
	void testUpdate() throws Exception;

	@Test
	void testReadPaged() throws Exception;
}
