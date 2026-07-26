package io.metaloom.loom.graphql;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import graphql.GraphqlErrorException;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.idl.RuntimeWiring;
import io.metaloom.loom.db.model.perm.Permission;

/**
 * Base class for the per domain wiring contributions of the Loom GraphQL schema.
 *
 * <p>The schema is deliberately split by domain - one subclass per bounded area (assets, ACL, pipelines, skills, memory). Each subclass registers the
 * data fetchers for its own {@code Query} fields plus the field resolvers of the types it owns. {@link LoomGraphQLProvider} merges them into a single
 * {@link RuntimeWiring}; {@code RuntimeWiring.Builder} merges repeated {@code type("Query")} registrations, so every subclass can contribute to the query
 * root.</p>
 */
public abstract class AbstractDomainWiring {

	private static final Logger log = LoggerFactory.getLogger(AbstractDomainWiring.class);

	/**
	 * Contribute the data fetchers of this domain to the shared runtime wiring.
	 *
	 * @param builder
	 *            the wiring being assembled
	 */
	public abstract void wire(RuntimeWiring.Builder builder);

	/**
	 * Enforce that the current request is authorized for the given permission before the field is resolved.
	 *
	 * <p>The {@link GraphQLPermissionChecker} is looked up from the execution {@link graphql.GraphQLContext}. It is supplied by the transport layer once
	 * the user's authorizations have been resolved (see the REST GraphQL endpoint). When no checker is present the request is treated as
	 * unauthenticated.</p>
	 *
	 * @param env
	 *            the data fetching environment
	 * @param permission
	 *            the permission required to resolve the field
	 * @throws GraphqlErrorException
	 *             when the request is unauthenticated or lacks the permission. The error carries a {@code code} extension of {@code UNAUTHENTICATED} or
	 *             {@code FORBIDDEN} respectively.
	 */
	protected static void requirePermission(DataFetchingEnvironment env, Permission permission) {
		GraphQLPermissionChecker checker = env.getGraphQlContext().get(GraphQLPermissionChecker.CONTEXT_KEY);
		if (checker == null) {
			throw GraphqlErrorException.newErrorException()
				.message("Not authenticated")
				.extensions(Map.of("code", "UNAUTHENTICATED"))
				.build();
		}
		if (!checker.hasPermission(permission)) {
			if (log.isDebugEnabled()) {
				log.debug("Request is lacking permission {}", permission);
			}
			throw GraphqlErrorException.newErrorException()
				.message("Missing permission " + permission.name())
				.extensions(Map.of("code", "FORBIDDEN", "permission", permission.name()))
				.build();
		}
	}

	/**
	 * Read a UUID argument. Returns null when the argument was not provided.
	 *
	 * @throws GraphqlErrorException
	 *             when the value is not a valid UUID. Without this the raw {@link IllegalArgumentException} would surface as an opaque internal error.
	 */
	protected static UUID uuidArg(DataFetchingEnvironment env, String name) {
		Object raw = env.getArgument(name);
		if (raw == null) {
			return null;
		}
		if (raw instanceof UUID) {
			return (UUID) raw;
		}
		try {
			return UUID.fromString(String.valueOf(raw));
		} catch (IllegalArgumentException e) {
			throw GraphqlErrorException.newErrorException()
				.message("Argument '" + name + "' is not a valid UUID: " + raw)
				.extensions(Map.of("code", "BAD_USER_INPUT", "argument", name))
				.build();
		}
	}

	/**
	 * Null safe list wrapper - the schema declares list fields as non-null, so a DAO returning null would fail the whole query.
	 */
	protected static <T> List<T> orEmpty(List<T> list) {
		return list == null ? Collections.emptyList() : list;
	}

}
