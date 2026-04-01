package io.metaloom.loom.rest.json.serializer;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import io.metaloom.utils.hash.AbstractStringHash;

public class HashSerializer extends JsonSerializer<AbstractStringHash> {

	@Override
	public void serialize(AbstractStringHash value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
		gen.writeObject(value.toString());
	}

}
