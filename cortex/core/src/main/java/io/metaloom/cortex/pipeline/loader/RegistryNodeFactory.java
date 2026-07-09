package io.metaloom.cortex.pipeline.loader;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.vertx.core.json.JsonObject;

/**
 * Default {@link NodeFactory} implementation that resolves a JSON node
 * definition to a concrete {@link PipelineNode} via a registry of
 * {@code type} → producer functions.
 *
 * <p>Producers are registered up front (typically at startup, from a
 * Dagger module that has access to all concrete cortex nodes). Each
 * producer receives the raw JSON node definition and can return either
 * the real processing node (wrapped in a {@code CortexNodeAdapter}) or
 * {@code null} to fall back to a stub node.</p>
 *
 * <p>The registry is keyed by the {@code "type"} field of the JSON node
 * definition, with a case-insensitive lookup. Common examples of type
 * values include {@code "sha512"}, {@code "thumbnail"}, {@code "loom-fetch"}
 * — the loader will match against whatever the pipeline definition
 * writer chose.</p>
 */
@Singleton
public class RegistryNodeFactory implements NodeFactory {

	private static final Logger log = LoggerFactory.getLogger(RegistryNodeFactory.class);

	private final Map<String, Function<JsonObject, PipelineNode>> producers = new LinkedHashMap<>();

	@Inject
	public RegistryNodeFactory() {
	}

	/**
	 * Register a producer that builds a {@link PipelineNode} for the given
	 * node type. If a producer for the type is already registered it is
	 * overwritten.
	 *
	 * @param type     the {@code type} value in the pipeline JSON (case-insensitive)
	 * @param producer function that maps a JSON node definition to a concrete node
	 * @return this factory (fluent)
	 */
	public RegistryNodeFactory register(String type, Function<JsonObject, PipelineNode> producer) {
		if (type == null || type.isBlank()) {
			throw new IllegalArgumentException("type must not be null or blank");
		}
		if (producer == null) {
			throw new IllegalArgumentException("producer must not be null");
		}
		producers.put(type.toLowerCase(), producer);
		log.debug("Registered node producer for type '{}'", type);
		return this;
	}

	/**
	 * Return an unmodifiable snapshot of the registered node types.
	 */
	public java.util.Set<String> registeredTypes() {
		return java.util.Collections.unmodifiableSet(producers.keySet());
	}

	@Override
	public PipelineNode createNode(JsonObject nodeDef) {
		if (nodeDef == null) {
			return null;
		}
		String type = nodeDef.getString("type");
		if (type == null || type.isBlank()) {
			return null;
		}
		Function<JsonObject, PipelineNode> producer = producers.get(type.toLowerCase());
		if (producer == null) {
			log.debug("No producer registered for node type '{}'; falling back to stub", type);
			return null;
		}
		try {
			return producer.apply(nodeDef);
		} catch (Exception e) {
			log.error("Producer for node type '{}' failed", type, e);
			return null;
		}
	}
}
