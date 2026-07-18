package io.metaloom.loom.core.endpoint;

import org.junit.jupiter.api.Test;

/**
 * Testcases for endpoints which support partial update (PATCH) and full replace (PUT) in addition to the POST based update.
 */
public interface ReplaceEndpointTestcases extends EndpointTest {

	@Test
	void testPatch() throws Exception;

	@Test
	void testReplace() throws Exception;

	@Test
	void testReplaceRejectsPartialBody() throws Exception;

}
