package io.metaloom.loom.core.endpoint.graphql;

import org.junit.jupiter.api.Test;

/**
 * The permission-check contract that every per-domain GraphQL test must fulfil.
 *
 * <p>{@link AbstractGraphQLTest} implements this interface but leaves the two methods abstract, so every concrete domain test class (e.g.
 * {@code UserGraphQLTest}, {@code PipelineGraphQLTest}) is forced by the compiler to provide both testcases. This is what "secures the creation" of the
 * domain tests: a new domain test class cannot be added without also asserting that its individual and list retrieval queries are guarded by a permission
 * check.</p>
 *
 * <p>Each implementation must, for <em>every</em> retrieval query of its domain (see the query root in the GraphQL spec):</p>
 * <ul>
 * <li>log in as a user that holds <em>no</em> permissions (see {@link AbstractGraphQLTest#loginPermissionlessClient()}), and</li>
 * <li>assert the query is rejected with a {@code FORBIDDEN} error naming the required read permission
 * (see {@link AbstractGraphQLTest#assertRetrievalForbidden}).</li>
 * </ul>
 */
public interface GraphQLSecurityTestcases {

	/**
	 * Assert that every <em>single-element</em> retrieval query of the domain (by uuid, by name, by version number, …) requires the appropriate read
	 * permission.
	 */
	@Test
	void testIndividualRetrievalRequiresPermission() throws Exception;

	/**
	 * Assert that every <em>list</em> retrieval query of the domain requires the appropriate read permission.
	 */
	@Test
	void testListRetrievalRequiresPermission() throws Exception;

}
