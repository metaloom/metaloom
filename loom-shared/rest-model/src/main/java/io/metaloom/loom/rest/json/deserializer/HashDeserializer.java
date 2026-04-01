package io.metaloom.loom.rest.json.deserializer;

import java.io.IOException;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import io.metaloom.utils.hash.AbstractStringHash;
import io.metaloom.utils.hash.MD5;
import io.metaloom.utils.hash.SHA256;
import io.metaloom.utils.hash.SHA512;

public class HashDeserializer extends JsonDeserializer<AbstractStringHash<?>> {

	@Override
	public AbstractStringHash<?> deserialize(JsonParser jsonParser, DeserializationContext ctxt) throws IOException, JacksonException {
		ObjectCodec oc = jsonParser.getCodec();
		JsonNode node = oc.readTree(jsonParser);
		String hash = node.toString();
		int len = hash.length();

		// 129 = sha512
		if (129 == len) {
			return SHA512.fromString(hash);
		}
		// 65 = sha256
		if (65 == len) {
			return SHA256.fromString(hash);
		}
		// 33 = md5
		if (33 == len) {
			return MD5.fromString(hash);
		}

		throw new RuntimeException("Hash value " + hash + " with len " + len + " unknown. Unable to deserialize.");
	}

}
