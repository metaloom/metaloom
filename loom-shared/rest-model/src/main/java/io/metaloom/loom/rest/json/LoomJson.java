package io.metaloom.loom.rest.json;

import java.io.InputStream;
import java.util.TimeZone;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.metaloom.loom.rest.json.deserializer.HashDeserializer;
import io.metaloom.loom.rest.json.deserializer.JsonArrayDeserializer;
import io.metaloom.loom.rest.json.deserializer.JsonObjectDeserializer;
import io.metaloom.loom.rest.json.serializer.HashSerializer;
import io.metaloom.loom.rest.json.serializer.JsonArraySerializer;
import io.metaloom.loom.rest.json.serializer.JsonObjectSerializer;
import io.metaloom.loom.rest.model.RestModel;
import io.metaloom.loom.rest.model.RestResponseModel;
import io.metaloom.utils.hash.AbstractStringHash;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.internal.buffer.BufferInternal;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Helper which manages JSON handling.
 */
public final class LoomJson {

	private static final String PARSE_ERROR = "Error while parsing model to JSON.";

	public static ObjectMapper mapper;

	static {
		mapper = new ObjectMapper()
			.setSerializationInclusion(Include.NON_NULL);
		SimpleModule module = new SimpleModule();
		// JSON
		module.addSerializer(JsonObject.class, new JsonObjectSerializer());
		module.addSerializer(JsonArray.class, new JsonArraySerializer());
		module.addDeserializer(JsonObject.class, new JsonObjectDeserializer());
		module.addDeserializer(JsonArray.class, new JsonArrayDeserializer());
		// Hashes
		module.addSerializer(AbstractStringHash.class, new HashSerializer());
		module.addDeserializer(AbstractStringHash.class, new HashDeserializer());
		mapper.registerModule(new JavaTimeModule());
		mapper.setTimeZone(TimeZone.getTimeZone("UTC"));
		mapper.registerModule(module);
	}

	private LoomJson() {
	}

	public static JsonNode toJson(String content) throws JsonProcessingException {
		JsonNode json = mapper.readTree(content);
		if (json == null) {
			return null;
		}
		return json;
	}

	public static String encode(RestModel model) {
		try {
			return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(model);
		} catch (JsonProcessingException e) {
			throw new RuntimeException(PARSE_ERROR, e);
		}
	}

	public static String encodeCompact(RestModel model) {
		try {
			return mapper.writeValueAsString(model);
		} catch (JsonProcessingException e) {
			throw new RuntimeException(PARSE_ERROR, e);
		}
	}

	public static <T extends RestModel> T parse(String json, Class<T> modelClass) {
		try {
			return mapper.readValue(json, modelClass);
		} catch (JacksonException e) {
			throw new RuntimeException(PARSE_ERROR, e);
		}
	}

	public static <T extends RestModel> T parse(Buffer buffer, Class<T> modelClass) {
		ByteBuf bb = ((BufferInternal)buffer).getByteBuf();
		try (InputStream ins = new ByteBufInputStream(bb)) {
			return mapper.readValue(ins, modelClass);
		} catch (Exception e) {
			throw new RuntimeException(PARSE_ERROR, e);
		}
	}

	public static Buffer encodeCompactToBuffer(RestResponseModel model) {
		try {
			return Buffer.buffer(mapper.writeValueAsBytes(model));
		} catch (JsonProcessingException e) {
			throw new RuntimeException(PARSE_ERROR, e);
		}
	}

	public static Buffer encodeToBuffer(RestResponseModel model) {
		try {
			return Buffer.buffer(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(model));
		} catch (JsonProcessingException e) {
			throw new RuntimeException(PARSE_ERROR, e);
		}
	}

}
