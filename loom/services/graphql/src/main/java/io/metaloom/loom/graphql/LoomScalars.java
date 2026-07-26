package io.metaloom.loom.graphql;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import graphql.language.IntValue;
import graphql.language.StringValue;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The custom scalars used by the Loom GraphQL schema.
 *
 * <p>Every scalar declared in {@code loom.graphqls} has to be registered in the {@link graphql.schema.idl.RuntimeWiring}, otherwise schema generation
 * fails - see {@link LoomGraphQLProvider}.</p>
 */
public final class LoomScalars {

	private LoomScalars() {
	}

	/**
	 * Long values (file sizes, byte counts) that do not fit into a GraphQL Int (32-bit).
	 */
	public static final GraphQLScalarType LONG = GraphQLScalarType.newScalar()
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

	/**
	 * {@link Instant} timestamps, rendered as ISO-8601 in UTC. The DB stores every audit timestamp as an {@code Instant}, so no zone information is ever
	 * lost by pinning the wire format to UTC.
	 */
	public static final GraphQLScalarType DATE_TIME = GraphQLScalarType.newScalar()
		.name("DateTime")
		.description("An instant in time, serialized as an ISO-8601 string in UTC")
		.coercing(new Coercing<Instant, String>() {
			@Override
			public String serialize(Object input) throws CoercingSerializeException {
				if (input instanceof Instant) {
					return input.toString();
				}
				if (input instanceof CharSequence) {
					return input.toString();
				}
				throw new CoercingSerializeException("Expected an Instant but got: " + input.getClass().getName());
			}

			@Override
			public Instant parseValue(Object input) throws CoercingParseValueException {
				if (input instanceof Instant) {
					return (Instant) input;
				}
				if (input instanceof CharSequence) {
					return parse(input.toString(), CoercingParseValueException::new);
				}
				throw new CoercingParseValueException("Expected an ISO-8601 String but got: " + input.getClass().getName());
			}

			@Override
			public Instant parseLiteral(Object input) throws CoercingParseLiteralException {
				if (input instanceof StringValue) {
					return parse(((StringValue) input).getValue(), CoercingParseLiteralException::new);
				}
				throw new CoercingParseLiteralException("Expected a StringValue but got: " + input.getClass().getName());
			}

			private Instant parse(String value, java.util.function.Function<String, RuntimeException> onError) {
				try {
					return Instant.parse(value);
				} catch (DateTimeParseException e) {
					throw onError.apply("Not a valid ISO-8601 instant: " + value);
				}
			}
		})
		.build();

	/**
	 * Free form JSON, used for the {@code meta} property carried by most elements and for the pipeline {@code definition}.
	 *
	 * <p>Vert.x {@link JsonObject} / {@link JsonArray} are unwrapped into plain maps and lists so the value survives the
	 * {@code ExecutionResult -> JSON} conversion done by the transport layer.</p>
	 */
	public static final GraphQLScalarType JSON = GraphQLScalarType.newScalar()
		.name("Json")
		.description("An arbitrary JSON object")
		.coercing(new Coercing<Object, Object>() {
			@Override
			public Object serialize(Object input) throws CoercingSerializeException {
				if (input instanceof JsonObject) {
					return ((JsonObject) input).getMap();
				}
				if (input instanceof JsonArray) {
					return ((JsonArray) input).getList();
				}
				if (input instanceof Map || input instanceof List) {
					return input;
				}
				throw new CoercingSerializeException("Expected a JSON object but got: " + input.getClass().getName());
			}

			@Override
			public Object parseValue(Object input) throws CoercingParseValueException {
				if (input instanceof Map) {
					return new JsonObject((Map<String, Object>) input);
				}
				throw new CoercingParseValueException("Expected a JSON object but got: " + input.getClass().getName());
			}

			@Override
			public Object parseLiteral(Object input) throws CoercingParseLiteralException {
				throw new CoercingParseLiteralException("Json literals are not supported - pass the value through a query variable instead");
			}
		})
		.build();

}
