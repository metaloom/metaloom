package io.metaloom.loom.rest.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;

import io.metaloom.loom.rest.json.LoomJson;
import io.metaloom.loom.rest.model.RestRequestModel;
import io.vertx.core.json.JsonObject;

/**
 * Validator which asserts that a full replace (PUT) request body carries every replaceable field of the request model.
 *
 * The set of replaceable fields is derived from the Jackson introspection of the model. This matches the actual wire format (including
 * {@code @JsonProperty} renames) and automatically excludes fields which have no accessors and can thus never be sent by a client. Properties annotated
 * with {@link ReplaceOptional} are excluded.
 */
public final class ReplaceValidator {

	private static final Map<Class<?>, Set<String>> CACHE = new ConcurrentHashMap<>();

	private ReplaceValidator() {
	}

	/**
	 * Return the JSON property names which must be present in a full replace (PUT) body for the given request model.
	 *
	 * @param clazz
	 * @return
	 */
	public static Set<String> replaceableFields(Class<? extends RestRequestModel> clazz) {
		return CACHE.computeIfAbsent(clazz, c -> {
			JavaType type = LoomJson.mapper.getTypeFactory().constructType(c);
			BeanDescription desc = LoomJson.mapper.getDeserializationConfig().introspect(type);
			Set<String> names = new LinkedHashSet<>();
			for (BeanPropertyDefinition prop : desc.findProperties()) {
				if (isReplaceOptional(prop)) {
					continue;
				}
				names.add(prop.getName());
			}
			return Collections.unmodifiableSet(names);
		});
	}

	/**
	 * Assert that the given raw request body carries every replaceable field of the request model.
	 *
	 * A field which is present but set to {@code null} counts as present - an explicit null clears the field. Only absent fields are rejected.
	 *
	 * @param body
	 * @param clazz
	 * @throws ValidationException
	 *             When the body is missing or incomplete. Mapped to HTTP 400 by the server failure handler.
	 */
	public static void assertComplete(JsonObject body, Class<? extends RestRequestModel> clazz) {
		if (body == null) {
			throw new ValidationException("A JSON object body is required for a full replace (PUT) request.");
		}
		List<String> missing = new ArrayList<>();
		for (String field : replaceableFields(clazz)) {
			if (!body.containsKey(field)) {
				missing.add(field);
			}
		}
		if (!missing.isEmpty()) {
			throw new ValidationException("A full replace (PUT) request must contain all replaceable fields. Missing: " + String.join(", ", missing)
				+ ". Use PATCH for a partial update.");
		}
	}

	private static boolean isReplaceOptional(BeanPropertyDefinition prop) {
		// The annotation may sit on the field, the getter or the setter
		return hasAnnotation(prop.getField()) || hasAnnotation(prop.getGetter()) || hasAnnotation(prop.getSetter());
	}

	private static boolean hasAnnotation(AnnotatedMember member) {
		return member != null && member.hasAnnotation(ReplaceOptional.class);
	}

}
