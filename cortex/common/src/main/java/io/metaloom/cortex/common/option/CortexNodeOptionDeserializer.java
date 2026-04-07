package io.metaloom.cortex.common.option;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.metaloom.cortex.api.option.node.CortexNodeOptions;

@Singleton
public class CortexNodeOptionDeserializer extends JsonDeserializer<CortexNodeOptions> {

	private final static Logger log = LoggerFactory.getLogger(CortexNodeOptionDeserializer.class);

	private Map<String, Class<? extends CortexNodeOptions>> infoMap;

	@Inject
	public CortexNodeOptionDeserializer(Set<CortexNodeOptionDeserializerInfo> infos) {
		this.infoMap = toMap(infos);
	}

	@Override
	public CortexNodeOptions deserialize(JsonParser jsonParser, DeserializationContext ctxt) throws IOException, JacksonException {
		ObjectCodec oc = jsonParser.getCodec();
		ObjectMapper mapper = (ObjectMapper) jsonParser.getCodec();
		JsonNode node = oc.readTree(jsonParser);
		String key = jsonParser.getCurrentName();
		Class<? extends CortexNodeOptions> mappingClazz = infoMap.get(key);
		if (mappingClazz == null) {
			log.warn("Did not find module options class for mapping {}. Ignoring found option.", key);
			return null;
		} else {
			log.info("Mapping node options for " + key + " to " + mappingClazz.getSimpleName());
			return mapper.convertValue(node, mappingClazz);
		}

	}

	private Map<String, Class<? extends CortexNodeOptions>> toMap(Set<CortexNodeOptionDeserializerInfo> infos) {
		Map<String, Class<? extends CortexNodeOptions>> map = new HashMap<>();
		for (CortexNodeOptionDeserializerInfo info : infos) {
			Class<? extends CortexNodeOptions> prev = map.put(info.getOptionPrefix(), info.getOptionClazz());
			if (prev != null) {
				log.error("Conflicting configuration mapping detected for prefix {} with {} and {}. Ignoring found options.", info.getOptionPrefix(),
					info.getOptionClazz().getSimpleName(), prev.getSimpleName());
				throw new RuntimeException("Invalid configuration mapping");
			}
		}
		return map;
	}
}
