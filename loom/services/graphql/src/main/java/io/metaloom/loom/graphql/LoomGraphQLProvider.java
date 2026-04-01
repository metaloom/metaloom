package io.metaloom.loom.graphql;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import graphql.GraphQL;
import graphql.language.IntValue;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.TypeRuntimeWiring;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetAudioComp;
import io.metaloom.loom.db.model.asset.AssetComponentDao;
import io.metaloom.loom.db.model.asset.AssetDao;
import io.metaloom.loom.db.model.asset.AssetImageComp;
import io.metaloom.loom.db.model.asset.AssetLocation;
import io.metaloom.loom.db.model.asset.AssetLocationDao;
import io.metaloom.loom.db.model.asset.AssetVideoComp;

/**
 * Builds and provides the {@link GraphQL} execution engine for the Loom GraphQL API.
 */
@Singleton
public class LoomGraphQLProvider {

	private static final Logger log = LoggerFactory.getLogger(LoomGraphQLProvider.class);

	/**
	 * Custom scalar for Long values (file sizes etc.) that don't fit in a GraphQL Int (32-bit).
	 */
	private static final GraphQLScalarType LONG_SCALAR = GraphQLScalarType.newScalar()
		.name("Long")
		.description("Long scalar for large numeric values")
		.coercing(new Coercing<Long, Long>() {
			@Override
			public Long serialize(Object input) throws CoercingSerializeException {
				if (input instanceof Number) {
					return ((Number) input).longValue();
				}
				throw new CoercingSerializeException("Expected a Number but got: " + input.getClass().getName());
			}

			@Override
			public Long parseValue(Object input) throws CoercingParseValueException {
				if (input instanceof Number) {
					return ((Number) input).longValue();
				}
				throw new CoercingParseValueException("Expected a Number but got: " + input.getClass().getName());
			}

			@Override
			public Long parseLiteral(Object input) throws CoercingParseLiteralException {
				if (input instanceof IntValue) {
					return ((IntValue) input).getValue().longValue();
				}
				throw new CoercingParseLiteralException("Expected an IntValue but got: " + input.getClass().getName());
			}
		})
		.build();

	private final GraphQL graphQL;

	@Inject
	public LoomGraphQLProvider(DaoCollection daos) {
		this.graphQL = buildGraphQL(daos);
	}

	private GraphQL buildGraphQL(DaoCollection daos) {
		TypeDefinitionRegistry typeRegistry = loadSchema();
		RuntimeWiring wiring = buildWiring(daos);
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

	private RuntimeWiring buildWiring(DaoCollection daos) {
		AssetDao assetDao = daos.assetDao();
		AssetLocationDao locationDao = daos.assetLocationDao();
		AssetComponentDao componentDao = daos.assetComponentDao();

		// Query root
		DataFetcher<Asset> assetFetcher = env -> {
			String uuidStr = env.getArgument("uuid");
			return assetDao.load(UUID.fromString(uuidStr));
		};

		DataFetcher<List<? extends Asset>> assetsFetcher = env -> {
			return assetDao.findAll().collect(Collectors.toList());
		};

		// Asset field resolvers
		DataFetcher<List<? extends AssetLocation>> locationsFetcher = env -> {
			Asset asset = env.getSource();
			return locationDao.findAll()
				.filter(loc -> asset.getUuid().equals(loc.getAssetUuid()))
				.collect(Collectors.toList());
		};

		DataFetcher<List<AssetImageComp>> imageCompsFetcher = env -> {
			Asset asset = env.getSource();
			return componentDao.loadImageComps(asset.getUuid());
		};

		DataFetcher<List<AssetVideoComp>> videoCompsFetcher = env -> {
			Asset asset = env.getSource();
			return componentDao.loadVideoComps(asset.getUuid());
		};

		DataFetcher<List<AssetAudioComp>> audioCompsFetcher = env -> {
			Asset asset = env.getSource();
			return componentDao.loadAudioComps(asset.getUuid());
		};

		// SHA / hash fetchers
		DataFetcher<String> sha512Fetcher = env -> {
			Asset asset = env.getSource();
			return asset.getSHA512() != null ? asset.getSHA512().toString() : null;
		};
		DataFetcher<String> sha256Fetcher = env -> {
			Asset asset = env.getSource();
			return asset.getSHA256() != null ? asset.getSHA256().toString() : null;
		};
		DataFetcher<String> md5Fetcher = env -> {
			Asset asset = env.getSource();
			return asset.getMD5() != null ? asset.getMD5().toString() : null;
		};

		// ImageComponent field resolvers
		DataFetcher<String> dominantColorFetcher = env -> {
			AssetImageComp comp = env.getSource();
			return comp.getImageDominantColor();
		};
		DataFetcher<Integer> widthFetcher = env -> {
			AssetImageComp comp = env.getSource();
			return comp.getMediaWidth();
		};
		DataFetcher<Integer> heightFetcher = env -> {
			AssetImageComp comp = env.getSource();
			return comp.getMediaHeight();
		};

		return RuntimeWiring.newRuntimeWiring()
			.scalar(LONG_SCALAR)
			.type(TypeRuntimeWiring.newTypeWiring("Query")
				.dataFetcher("asset", assetFetcher)
				.dataFetcher("assets", assetsFetcher))
			.type(TypeRuntimeWiring.newTypeWiring("Asset")
				.dataFetcher("locations", locationsFetcher)
				.dataFetcher("imageComponents", imageCompsFetcher)
				.dataFetcher("videoComponents", videoCompsFetcher)
				.dataFetcher("audioComponents", audioCompsFetcher)
				.dataFetcher("sha512", sha512Fetcher)
				.dataFetcher("sha256", sha256Fetcher)
				.dataFetcher("md5", md5Fetcher))
			.type(TypeRuntimeWiring.newTypeWiring("ImageComponent")
				.dataFetcher("dominantColor", dominantColorFetcher)
				.dataFetcher("width", widthFetcher)
				.dataFetcher("height", heightFetcher))
			.build();
	}

	/**
	 * Return the configured {@link GraphQL} engine.
	 */
	public GraphQL graphQL() {
		return graphQL;
	}
}
