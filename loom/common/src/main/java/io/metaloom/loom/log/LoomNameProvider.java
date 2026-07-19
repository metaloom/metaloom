package io.metaloom.loom.log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Supplies the name an instance is known by in its logs.
 *
 * <p>A name makes interleaved output readable. Two workers writing to the same
 * aggregated log are otherwise distinguishable only by container id, and a line saying
 * "Processor disconnected" tells you nothing about which process observed it.</p>
 *
 * <h2>The name is resolved once</h2>
 *
 * <p>Deliberately: a name that changed between lines would be worse than no name.
 * {@link #getName()} caches, so every line from a process carries the same name for as
 * long as it runs. A restart is a new name unless one was configured, which is the
 * honest signal - to anything watching, a restarted process <em>is</em> a new one.</p>
 *
 * <h2>Nothing here may throw or log</h2>
 *
 * <p>This is called by a logback converter while logback is configuring itself.
 * Logging from inside it would recurse, and throwing would take down logging for the
 * whole process - so failures fall back to {@link #FALLBACK_NAME} silently. A missing
 * word list is a cosmetic problem and must not be more than that.</p>
 */
public final class LoomNameProvider {

	/** System property that pins the name, e.g. {@code -Dloom.name=ingest-3}. */
	public static final String NAME_PROPERTY = "loom.name";

	/** Environment variable that pins the name. Takes effect in containers. */
	public static final String NAME_ENV = "LOOM_NAME";

	/** Used when no name can be generated. Never null, so the pattern always renders. */
	public static final String FALLBACK_NAME = "loom";

	private static final String ADJECTIVES_RESOURCE = "/json/adjectives.json";
	private static final String NAMES_RESOURCE = "/json/names.json";

	private static volatile LoomNameProvider instance;

	private final List<String> adjectives;
	private final List<String> names;
	private volatile String resolvedName;

	private LoomNameProvider() {
		this.adjectives = load(ADJECTIVES_RESOURCE);
		this.names = load(NAMES_RESOURCE);
	}

	public static LoomNameProvider getInstance() {
		LoomNameProvider local = instance;
		if (local == null) {
			synchronized (LoomNameProvider.class) {
				local = instance;
				if (local == null) {
					local = new LoomNameProvider();
					instance = local;
				}
			}
		}
		return local;
	}

	/**
	 * The name for this process: configured if one was given, otherwise generated once
	 * and kept.
	 */
	public String getName() {
		String local = resolvedName;
		if (local == null) {
			synchronized (this) {
				local = resolvedName;
				if (local == null) {
					local = resolve();
					resolvedName = local;
				}
			}
		}
		return local;
	}

	private String resolve() {
		String configured = configuredName();
		return configured != null ? configured : getRandomName();
	}

	/**
	 * An explicitly configured name, or null.
	 *
	 * <p>The system property wins over the environment so a single process can be
	 * renamed without editing how it is deployed.</p>
	 */
	private static String configuredName() {
		String value = trimToNull(System.getProperty(NAME_PROPERTY));
		if (value == null) {
			value = trimToNull(System.getenv(NAME_ENV));
		}
		return value;
	}

	/**
	 * A fresh "adjective Noun" pair.
	 *
	 * <p>Public because it is occasionally wanted for something other than the log name
	 * - naming a run, say. Most callers want {@link #getName()}, which is stable.</p>
	 */
	public String getRandomName() {
		if (adjectives.isEmpty() || names.isEmpty()) {
			return FALLBACK_NAME;
		}
		String adjective = adjectives.get(ThreadLocalRandom.current().nextInt(adjectives.size()));
		String name = names.get(ThreadLocalRandom.current().nextInt(names.size()));
		return adjective + " " + name;
	}

	/**
	 * Read one word list.
	 *
	 * @return the words, or an empty list if the resource is missing or unreadable
	 */
	private static List<String> load(String resource) {
		try (InputStream ins = LoomNameProvider.class.getResourceAsStream(resource)) {
			if (ins == null) {
				return Collections.emptyList();
			}
			JsonObject json = new JsonObject(new String(ins.readAllBytes(), StandardCharsets.UTF_8));
			JsonArray data = json.getJsonArray("data");
			if (data == null) {
				return Collections.emptyList();
			}
			List<String> words = new ArrayList<>(data.size());
			for (int i = 0; i < data.size(); i++) {
				String word = trimToNull(data.getString(i));
				if (word != null) {
					words.add(word);
				}
			}
			return Collections.unmodifiableList(words);
		} catch (IOException | RuntimeException e) {
			// Silent on purpose - see the class javadoc. A name is a nicety; logging
			// must work regardless.
			return Collections.emptyList();
		}
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
