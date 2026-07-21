package io.metaloom.loom.graphql;

import io.metaloom.loom.db.model.perm.Permission;

/**
 * Callback used by GraphQL data fetchers to enforce field-level authorization.
 *
 * <p>The checker is supplied by the transport layer (e.g. the REST GraphQL endpoint) via the
 * {@link graphql.GraphQLContext} of the {@link graphql.ExecutionInput}. This keeps the
 * {@code loom-service-graphql} module free of any dependency on the authentication/authorization
 * layer while still allowing fetchers to reject access to individual fields.</p>
 *
 * <p>The instance is resolved <em>after</em> the current user's authorizations have been loaded, so
 * {@link #hasPermission(Permission)} can be evaluated synchronously.</p>
 */
@FunctionalInterface
public interface GraphQLPermissionChecker {

	/**
	 * Key under which the checker is stored in the {@link graphql.GraphQLContext}.
	 */
	String CONTEXT_KEY = "loom.permissionChecker";

	/**
	 * Return whether the current user has been granted the given permission.
	 *
	 * @param permission
	 *            the permission to test
	 * @return {@code true} when the user holds the permission
	 */
	boolean hasPermission(Permission permission);

}
