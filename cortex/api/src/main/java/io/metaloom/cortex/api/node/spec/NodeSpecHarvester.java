package io.metaloom.cortex.api.node.spec;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.metaloom.cortex.api.node.InputPort;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.option.node.CortexNodeOptions;
import io.metaloom.loom.nodes.spec.Cardinality;
import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.nodes.spec.NodeParameter;
import io.metaloom.loom.nodes.spec.ParameterType;
import io.metaloom.loom.nodes.spec.PortGroup;
import io.metaloom.loom.nodes.spec.PortSpec;

/**
 * Builds a {@link NodeDescriptor} by reflecting over a node class and its options class.
 *
 * <p>
 * This exists because the contract used to be typed twice: once as the {@code InputPort} constants a
 * node actually executes against, and once by hand in a {@code NodeDescriptorProvider} compiled into
 * Loom. Two sources meant drift, and a whole conformance test whose only job was to police it. Here
 * the descriptor is <em>computed from</em> the executing declarations, so the drift is not detected —
 * it is unrepresentable, and the conformance test can be deleted rather than maintained.
 * </p>
 *
 * <p>
 * Roughly 80% of a descriptor is recovered this way: every port's id, content type, cardinality and
 * value type, every parameter's key, type and default, every enum's allowed values. The remaining 20%
 * — labels, descriptions, icons, categories, bounds — is prose and intent, and comes from
 * {@link NodeSpec}, {@link PortDoc} and {@link ParamDoc} on those same declarations.
 * </p>
 *
 * <h2>Class loading</h2>
 *
 * <p>
 * 🔴 Reflecting a class runs its static initializers, and some nodes load native libraries there
 * ({@code FingerprintNode} calls {@code Video4j.init()}). Harvest only the nodes a worker actually
 * registered — those get loaded on their first task anyway — rather than sweeping the classpath. The
 * long-term fix is to run this same harvester at build time and read a resource at startup; node
 * authors would see no difference, because only the invocation site moves.
 * </p>
 */
public final class NodeSpecHarvester {

	/**
	 * What all but a handful of nodes advertise. Kept here rather than repeated per node — it was
	 * copy-pasted into every hand-written provider, which is how three of them ended up disagreeing.
	 */
	public static final List<String> STANDARD_EVENTS = List.of(
		"NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED", "NODE_STATS");

	private NodeSpecHarvester() {
	}

	/**
	 * Harvest the descriptor for one node class.
	 *
	 * @param nodeClass
	 *            a class annotated with {@link NodeSpec}
	 * @throws IllegalArgumentException
	 *             when the class carries no {@link NodeSpec}. Deliberately loud: a silently null
	 *             descriptor would mean a node that runs and cannot be authored, which is the exact
	 *             defect this machinery removes
	 */
	public static NodeDescriptor harvest(Class<?> nodeClass) {
		if (nodeClass == null) {
			throw new IllegalArgumentException("nodeClass must not be null");
		}
		NodeSpec spec = nodeClass.getAnnotation(NodeSpec.class);
		if (spec == null) {
			throw new IllegalArgumentException(nodeClass.getName() + " is not annotated with @NodeSpec, so it has no "
				+ "declarable contract. Add the annotation, or exclude the class from the harvest.");
		}

		NodeDescriptor descriptor = new NodeDescriptor()
			.setNodeId(spec.nodeId())
			.setName(spec.name())
			.setDescription(emptyToNull(spec.description()))
			.setIcon(emptyToNull(spec.icon()))
			.setCategory(spec.category())
			.setDynamicPorts(spec.dynamicPorts())
			.setDefaultConcurrency(spec.defaultConcurrency())
			.setDefaultMode(spec.defaultMode())
			.setDefaultBlocking(spec.defaultBlocking())
			.setEvents(spec.events().length == 0 ? STANDARD_EVENTS : List.of(spec.events()))
			.setVersion(resolveVersion(spec, nodeClass));

		descriptor.setInputPorts(harvestPorts(nodeClass, InputPort.class));
		descriptor.setOutputPorts(harvestPorts(nodeClass, OutputPort.class));
		descriptor.setInputGroups(harvestGroups(spec.inputGroups()));
		descriptor.setOutputGroups(harvestGroups(spec.outputGroups()));
		descriptor.setParameters(harvestParameters(resolveOptionsClass(spec, nodeClass), spec.parameters()));

		return descriptor;
	}

	/** Harvest several node classes, in the given order. */
	public static List<NodeDescriptor> harvestAll(Collection<Class<?>> nodeClasses) {
		List<NodeDescriptor> descriptors = new ArrayList<>(nodeClasses.size());
		for (Class<?> nodeClass : nodeClasses) {
			descriptors.add(harvest(nodeClass));
		}
		return descriptors;
	}

	/** Whether this class declares a contract at all. */
	public static boolean isAnnotated(Class<?> nodeClass) {
		return nodeClass != null && nodeClass.isAnnotationPresent(NodeSpec.class);
	}

	// ── Ports ────────────────────────────────────────────────────────────────────────────────────

	private static List<PortSpec> harvestPorts(Class<?> nodeClass, Class<?> portType) {
		List<Field> fields = staticFieldsOfType(nodeClass, portType);
		List<PortSpec> ports = new ArrayList<>(fields.size());
		for (int i = 0; i < fields.size(); i++) {
			Field field = fields.get(i);
			Object value = read(field, null);
			if (value == null) {
				continue;
			}
			PortDoc doc = field.getAnnotation(PortDoc.class);
			if (doc != null && doc.hidden()) {
				// A resolver-owned port on a dynamicPorts node: declared because the node executes
				// against it, deliberately absent from the static contract.
				continue;
			}
			PortSpec port = value instanceof InputPort<?> in
				? new PortSpec(in.id(), in.contentType(), orOne(in.cardinality()))
				: new PortSpec(((OutputPort<?>) value).id(), ((OutputPort<?>) value).contentType(),
					orOne(((OutputPort<?>) value).cardinality()));

			if (doc != null) {
				port.setLabel(orDerived(doc.label(), port.getId()));
				port.setDescription(emptyToNull(doc.description()));
				port.setRequired(doc.required());
				port.setSelective(doc.selective());
				port.setGroup(emptyToNull(doc.group()));
			} else {
				port.setLabel(titleCase(port.getId()));
			}
			ports.add(port);
		}
		// Declaration order by default; an explicit @PortDoc(order) wins. Stable, so ports that share
		// the default order keep the order the class declared them in.
		List<Integer> orders = new ArrayList<>(fields.size());
		for (Field field : fields) {
			PortDoc doc = field.getAnnotation(PortDoc.class);
			if (doc != null && doc.hidden()) {
				// Must skip in lockstep with the loop above, or the two lists stop lining up and the
				// sort below pairs a port with another port's order.
				continue;
			}
			orders.add(doc == null ? Integer.MAX_VALUE : doc.order());
		}
		if (orders.stream().anyMatch(o -> o != Integer.MAX_VALUE)) {
			List<PortSpec> sorted = new ArrayList<>(ports);
			Map<PortSpec, Integer> keyed = new LinkedHashMap<>();
			for (int i = 0; i < ports.size(); i++) {
				keyed.put(ports.get(i), orders.get(i));
			}
			sorted.sort(Comparator.comparingInt(keyed::get));
			return sorted;
		}
		return ports;
	}

	/**
	 * Public static fields of the given port type, superclass-first so an inherited port precedes the
	 * node's own — the order a reader of the class hierarchy would expect.
	 */
	private static List<Field> staticFieldsOfType(Class<?> nodeClass, Class<?> portType) {
		List<Class<?>> hierarchy = new ArrayList<>();
		for (Class<?> c = nodeClass; c != null && c != Object.class; c = c.getSuperclass()) {
			hierarchy.add(0, c);
		}
		List<Field> fields = new ArrayList<>();
		for (Class<?> c : hierarchy) {
			for (Field field : c.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers()) && portType.isAssignableFrom(field.getType())
					&& !field.isSynthetic()) {
					fields.add(field);
				}
			}
		}
		return fields;
	}

	private static List<PortGroup> harvestGroups(PortGroupDoc[] docs) {
		List<PortGroup> groups = new ArrayList<>(docs.length);
		for (PortGroupDoc doc : docs) {
			PortGroup group = new PortGroup()
				.setId(doc.id())
				.setMode(doc.mode())
				.setRequired(doc.required());
			group.setLabel(orDerived(doc.label(), doc.id()));
			groups.add(group);
		}
		return groups;
	}

	// ── Parameters ───────────────────────────────────────────────────────────────────────────────

	private static List<NodeParameter> harvestParameters(Class<?> optionsClass, ParamOverride[] overrides) {
		if (optionsClass == null) {
			return List.of();
		}
		Object defaults = instantiate(optionsClass);
		Map<String, ParamOverride> overrideByKey = new LinkedHashMap<>();
		for (ParamOverride override : overrides) {
			overrideByKey.put(override.key(), override);
		}

		List<Class<?>> hierarchy = new ArrayList<>();
		for (Class<?> c = optionsClass; c != null && c != Object.class; c = c.getSuperclass()) {
			// Superclass-first, so the common enabled/processIncomplete/retryFailed lead the form.
			hierarchy.add(0, c);
		}

		List<NodeParameter> parameters = new ArrayList<>();
		List<Integer> orders = new ArrayList<>();
		for (Class<?> c : hierarchy) {
			for (Field field : c.getDeclaredFields()) {
				int modifiers = field.getModifiers();
				if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || field.isSynthetic()) {
					continue;
				}
				ParamDoc doc = field.getAnnotation(ParamDoc.class);
				ParamOverride override = overrideByKey.get(field.getName());

				// An override on the node wins over whatever the field (or the field's base class) said,
				// in both directions: it un-hides an inherited hidden field, and it can hide one the base
				// leaves visible.
				boolean hidden = override != null ? override.hidden() : doc != null && doc.hidden();
				if (hidden) {
					continue;
				}
				parameters.add(override != null
					? toParameter(field, override, defaults)
					: toParameter(field, doc, defaults));
				orders.add(override != null ? override.order() : doc == null ? Integer.MAX_VALUE : doc.order());
			}
		}
		if (orders.stream().anyMatch(o -> o != Integer.MAX_VALUE)) {
			Map<NodeParameter, Integer> keyed = new LinkedHashMap<>();
			for (int i = 0; i < parameters.size(); i++) {
				keyed.put(parameters.get(i), orders.get(i));
			}
			List<NodeParameter> sorted = new ArrayList<>(parameters);
			sorted.sort(Comparator.comparingInt(keyed::get));
			return sorted;
		}
		return parameters;
	}

	private static NodeParameter toParameter(Field field, ParamDoc doc, Object defaults) {
		ParameterType type = resolveParameterType(field, doc);
		NodeParameter parameter = new NodeParameter()
			.setKey(field.getName())
			.setType(type)
			.setDefaultValue(authoredDefault(doc == null ? "" : doc.defaultValue(),
				doc != null && doc.omitDefault(), field, defaults, type));

		if (doc != null) {
			parameter.setLabel(orDerived(doc.label(), field.getName()));
			parameter.setDescription(emptyToNull(doc.description()));
			parameter.setMin(parseNumber(doc.min()));
			parameter.setMax(parseNumber(doc.max()));
			parameter.setStep(parseNumber(doc.step()));
			parameter.setLanguage(emptyToNull(doc.language()));
			parameter.setRows(doc.rows() == 0 ? null : doc.rows());
			if (doc.values().length > 0) {
				parameter.setValues(List.of(doc.values()));
			}
		} else {
			parameter.setLabel(titleCase(field.getName()));
		}
		// Only a choice parameter has a choice list. A field whose Java type is an enum but whose
		// authored type is STRING - gpsPolicy, dateFallback on the metadata node - is a free string on
		// the wire, and attaching the constant names to it would advertise a closed set the contract
		// does not promise.
		if (parameter.getValues() == null && (type == ParameterType.ENUM || type == ParameterType.ENUM_SET)) {
			parameter.setValues(enumValuesOf(field));
		}
		return parameter;
	}

	/**
	 * The same mapping as {@link #toParameter(Field, ParamDoc, Object)}, driven by a node-level
	 * {@link ParamOverride} instead of a field annotation.
	 */
	private static NodeParameter toParameter(Field field, ParamOverride override, Object defaults) {
		ParameterType type = override.type().length > 0 ? override.type()[0] : resolveParameterType(field, null);
		NodeParameter parameter = new NodeParameter()
			.setKey(field.getName())
			.setType(type)
			.setDefaultValue(authoredDefault(override.defaultValue(), override.omitDefault(), field, defaults, type))
			.setLabel(orDerived(override.label(), field.getName()))
			.setDescription(emptyToNull(override.description()))
			.setMin(parseNumber(override.min()))
			.setMax(parseNumber(override.max()))
			.setStep(parseNumber(override.step()))
			.setLanguage(emptyToNull(override.language()));
		parameter.setRows(override.rows() == 0 ? null : override.rows());
		if (override.values().length > 0) {
			parameter.setValues(List.of(override.values()));
		} else if (type == ParameterType.ENUM || type == ParameterType.ENUM_SET) {
			parameter.setValues(enumValuesOf(field));
		}
		return parameter;
	}

	/**
	 * The default to advertise: authored if given, otherwise derived from the field, otherwise none.
	 */
	private static Object authoredDefault(String authored, boolean omit, Field field, Object defaults, ParameterType type) {
		if (!authored.isEmpty()) {
			return authored;
		}
		return omit ? null : defaultValueOf(field, defaults, type);
	}

	/**
	 * The value a default-constructed options instance holds.
	 *
	 * <p>
	 * Read from a real instance rather than inferred, so an initializer as ordinary as
	 * {@code private int sentimentPort = 9110} lands in the palette without being restated. A null
	 * stays null: {@code NodeParameter} omits it, which is how an optional override with no default
	 * ({@code modelDe}) renders as an empty field rather than the string "null".
	 * </p>
	 *
	 * <p>
	 * The default must also be something the <em>declared</em> type can hold. A {@code List<String>}
	 * field authored as {@link ParameterType#STRING} - {@code excludeKeys} on the metadata node - would
	 * otherwise hand the editor a {@code []} to render in a single-line text box. The aggregate types
	 * keep their aggregate defaults, which is what makes {@code filter}'s empty {@code buckets} list
	 * survive.
	 * </p>
	 */
	private static Object defaultValueOf(Field field, Object defaults, ParameterType type) {
		if (defaults == null) {
			return null;
		}
		Object value = read(field, defaults);
		if (value == null) {
			return null;
		}
		if (value instanceof Enum<?> constant) {
			return constant.name();
		}
		// Vert.x JSON types are aggregates that are neither Collection nor Map. Left alone, Jackson
		// bean-serializes them - an empty JsonArray becomes {"list":[],"empty":true} rather than [] -
		// so unwrap to the underlying List/Map first and let the rule below judge that.
		Object unwrapped = unwrapVertxJson(value);
		if ((unwrapped instanceof Collection<?> || unwrapped instanceof Map<?, ?>) && !holdsAggregate(type)) {
			return null;
		}
		if (unwrapped != value) {
			return unwrapped;
		}
		// The same rule applied to a scalar the declared type cannot hold. {@code dupFolder} on the dedup
		// nodes is a {@link java.nio.file.Path} authored as STRING, and Jackson renders a Path as an
		// absolute {@code file:} URI - so the editor would pre-fill "file:///home/worker/duplicates"
		// instead of "duplicates", with the answer depending on the working directory of whichever
		// worker happened to announce it.
		if (type == ParameterType.STRING && !(value instanceof String)) {
			return value.toString();
		}
		return value;
	}

	/**
	 * The underlying {@code List}/{@code Map} of a Vert.x {@code JsonArray}/{@code JsonObject}.
	 *
	 * <p>
	 * Reflective rather than a compile-time dependency: {@code cortex/api} does not depend on
	 * vertx-core, and adding that dependency to every node module to serialize a default correctly
	 * would be a poor trade.
	 * </p>
	 *
	 * @return the unwrapped aggregate, or the value itself when it is not a Vert.x JSON type
	 */
	private static Object unwrapVertxJson(Object value) {
		if (value == null) {
			return null;
		}
		String type = value.getClass().getName();
		if (!"io.vertx.core.json.JsonArray".equals(type) && !"io.vertx.core.json.JsonObject".equals(type)) {
			return value;
		}
		try {
			return value.getClass().getMethod(type.endsWith("JsonArray") ? "getList" : "getMap").invoke(value);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return value;
		}
	}

	/** Whether a parameter of this type can carry a list or a bag as its default. */
	private static boolean holdsAggregate(ParameterType type) {
		return type == ParameterType.ENUM_SET || type == ParameterType.JSON || type == ParameterType.PORT_LIST;
	}

	private static List<String> enumValuesOf(Field field) {
		Class<?> type = field.getType();
		if (!type.isEnum()) {
			return null;
		}
		List<String> values = new ArrayList<>();
		for (Object constant : type.getEnumConstants()) {
			values.add(((Enum<?>) constant).name());
		}
		return values;
	}

	private static ParameterType resolveParameterType(Field field, ParamDoc doc) {
		if (doc != null && doc.type().length > 0) {
			return doc.type()[0];
		}
		Class<?> type = field.getType();
		if (type == boolean.class || type == Boolean.class) {
			return ParameterType.BOOLEAN;
		}
		if (type == int.class || type == Integer.class || type == long.class || type == Long.class
			|| type == short.class || type == Short.class) {
			return ParameterType.INTEGER;
		}
		if (type == double.class || type == Double.class || type == float.class || type == Float.class) {
			return ParameterType.NUMBER;
		}
		if (type.isEnum()) {
			return ParameterType.ENUM;
		}
		if (Collection.class.isAssignableFrom(type)) {
			return ParameterType.ENUM_SET;
		}
		if (Map.class.isAssignableFrom(type)) {
			return ParameterType.JSON;
		}
		return ParameterType.STRING;
	}

	// ── Options class resolution ─────────────────────────────────────────────────────────────────

	/**
	 * The options class whose fields become parameters: the explicit {@code @NodeSpec(optionsClass)}
	 * if given, otherwise the first type argument in the node's generic superclass chain that is a
	 * {@link CortexNodeOptions}.
	 */
	static Class<?> resolveOptionsClass(NodeSpec spec, Class<?> nodeClass) {
		if (spec.optionsClass() != void.class) {
			return spec.optionsClass();
		}
		for (Class<?> c = nodeClass; c != null && c != Object.class; c = c.getSuperclass()) {
			Type generic = c.getGenericSuperclass();
			if (!(generic instanceof ParameterizedType parameterized)) {
				continue;
			}
			for (Type argument : parameterized.getActualTypeArguments()) {
				if (argument instanceof Class<?> candidate && CortexNodeOptions.class.isAssignableFrom(candidate)) {
					return candidate;
				}
			}
		}
		return null;
	}

	// ── Helpers ──────────────────────────────────────────────────────────────────────────────────

	private static String resolveVersion(NodeSpec spec, Class<?> nodeClass) {
		if (!spec.version().isEmpty()) {
			return spec.version();
		}
		// Maven writes Implementation-Version into the jar manifest, which is how a worker announces
		// 1.0.0-SNAPSHOT without any author typing it. Absent when running from target/classes.
		Package pkg = nodeClass.getPackage();
		return pkg == null ? null : pkg.getImplementationVersion();
	}

	private static Object instantiate(Class<?> optionsClass) {
		try {
			var constructor = optionsClass.getDeclaredConstructor();
			constructor.setAccessible(true);
			return constructor.newInstance();
		} catch (ReflectiveOperationException | RuntimeException e) {
			// A parameter list with no defaults is worse than none at all, but it is still a usable
			// contract - ports and keys are unaffected. Fail the whole harvest and the node vanishes.
			return null;
		}
	}

	private static Object read(Field field, Object target) {
		try {
			field.setAccessible(true);
			return field.get(target);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	private static Cardinality orOne(Cardinality cardinality) {
		return cardinality == null ? Cardinality.ONE : cardinality;
	}

	private static String emptyToNull(String value) {
		return value == null || value.isEmpty() ? null : value;
	}

	private static String orDerived(String authored, String source) {
		return authored == null || authored.isEmpty() ? titleCase(source) : authored;
	}

	/**
	 * Parse an authored bound, preserving whether it was written as an integer.
	 *
	 * <p>
	 * {@code "1"} becomes {@code Integer 1} and serializes as {@code 1}; {@code "0.0"} becomes
	 * {@code Double 0.0} and serializes as {@code 0.0}. Collapsing both to double would quietly turn
	 * every integer bound in every form into a float.
	 * </p>
	 */
	static Number parseNumber(String value) {
		if (value == null || value.isEmpty()) {
			return null;
		}
		String trimmed = value.trim();
		try {
			if (trimmed.indexOf('.') >= 0 || trimmed.indexOf('e') >= 0 || trimmed.indexOf('E') >= 0) {
				return Double.valueOf(trimmed);
			}
			long parsed = Long.parseLong(trimmed);
			// Deliberately not a ternary: mixing Integer and Long branches triggers binary numeric
			// promotion, so both would unbox, widen to long and re-box as Long - and every "1" bound
			// would come back a Long after all the trouble taken to keep it an int.
			if (parsed >= Integer.MIN_VALUE && parsed <= Integer.MAX_VALUE) {
				return Integer.valueOf((int) parsed);
			}
			return Long.valueOf(parsed);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Not a numeric bound: '" + value + "'", e);
		}
	}

	/** {@code sentimentPort} → {@code Sentiment Port}; {@code max_chars} → {@code Max Chars}. */
	static String titleCase(String identifier) {
		if (identifier == null || identifier.isEmpty()) {
			return identifier;
		}
		StringBuilder sb = new StringBuilder(identifier.length() + 4);
		boolean startOfWord = true;
		for (int i = 0; i < identifier.length(); i++) {
			char c = identifier.charAt(i);
			if (c == '_' || c == '-') {
				sb.append(' ');
				startOfWord = true;
				continue;
			}
			if (Character.isUpperCase(c) && i > 0 && !startOfWord) {
				sb.append(' ');
				sb.append(c);
				continue;
			}
			sb.append(startOfWord ? Character.toUpperCase(c) : c);
			startOfWord = false;
		}
		return sb.toString();
	}
}
