package io.metaloom.loom.graphql;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import io.metaloom.loom.api.search.SearchProvider;
import io.metaloom.loom.db.dagger.DaoCollection;

/**
 * Builds and provides the {@link GraphQL} execution engine for the Loom GraphQL API.
 *
 * <p>The schema is defined in SDL ({@code /loom.graphqls}) and the data fetchers are contributed per domain by the {@link AbstractDomainWiring}
 * implementations below. Adding a field therefore means touching two places: the SDL and the wiring of the owning domain.</p>
 */
@Singleton
public class LoomGraphQLProvider {

	private final GraphQL graphQL;

	@Inject
	public LoomGraphQLProvider(DaoCollection daos, SearchProvider search) {
		this.graphQL = buildGraphQL(daos, search);
	}

	private GraphQL buildGraphQL(DaoCollection daos, SearchProvider search) {
		TypeDefinitionRegistry typeRegistry = loadSchema();
		RuntimeWiring wiring = buildWiring(daos, search);
		GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(typeRegistry, wiring);
		return GraphQL.newGraphQL(schema).build();
	}

	private TypeDefinitionRegistry loadSchema() {
		SchemaParser schemaParser = new SchemaParser();
		try (InputStream is = getClass().getResourceAsStream("/loom.graphqls")) {
			Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
			return schemaParser.parse(reader);
		} catch (Exception e) {
			throw new RuntimeException("Failed to load GraphQL schema", e);
		}
	}

	private RuntimeWiring buildWiring(DaoCollection daos, SearchProvider search) {
		RuntimeWiring.Builder builder = RuntimeWiring.newRuntimeWiring()
			.scalar(LoomScalars.LONG)
			.scalar(LoomScalars.DATE_TIME)
			.scalar(LoomScalars.JSON);

		// Repeated type("Query") registrations are merged by the builder, so every domain can extend the query root.
		for (AbstractDomainWiring domain : domains(daos, search)) {
			domain.wire(builder);
		}
		return builder.build();
	}

	private List<AbstractDomainWiring> domains(DaoCollection daos, SearchProvider search) {
		return List.of(
			new AssetWiring(daos),
			new AclWiring(daos),
			new PipelineWiring(daos),
			new SkillWiring(daos),
			new MemoryWiring(daos),
			new SearchWiring(search));
	}

	/**
	 * Return the configured {@link GraphQL} engine.
	 */
	public GraphQL graphQL() {
		return graphQL;
	}

}
