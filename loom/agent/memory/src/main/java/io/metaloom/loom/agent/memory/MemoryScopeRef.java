package io.metaloom.loom.agent.memory;

import java.util.UUID;

import io.metaloom.loom.api.memory.MemoryScope;
import io.metaloom.loom.db.model.memory.MemoryEntryDao.MemoryScopeKey;

/**
 * A scope the caller actually has access to, with the label used when it is shown to a user or the model.
 *
 * <p>These are produced only by {@link MemoryScopeResolver} from server-side state. Tool arguments can select among them by equality but can never
 * introduce one.</p>
 *
 * @param scope
 *            The kind of scope
 * @param scopeUuid
 *            The user / group / space uuid the scope addresses
 * @param label
 *            Human readable name ("editors", "Marketing"); for the user scope simply "user"
 */
public record MemoryScopeRef(MemoryScope scope, UUID scopeUuid, String label) {

	public MemoryScopeKey key() {
		return new MemoryScopeKey(scope, scopeUuid);
	}

	/**
	 * How this scope is addressed in tool arguments and rendered in listings: {@code user}, {@code group:editors}, {@code space:Marketing}.
	 */
	public String ref() {
		return scope.isShared() ? scope.key() + ":" + label : scope.key();
	}

	/**
	 * The directory this scope maps to when memory is materialized into a session container.
	 */
	public String directory() {
		return scope.isShared() ? scope.key() + "/" + slug(label) : scope.key();
	}

	/**
	 * Reduce a label to a filesystem-safe directory segment.
	 */
	static String slug(String label) {
		if (label == null || label.isBlank()) {
			return "unnamed";
		}
		String slug = label.strip().toLowerCase().replaceAll("[^a-z0-9._-]+", "-").replaceAll("(^-+)|(-+$)", "");
		if (slug.isBlank()) {
			return "unnamed";
		}
		return slug.length() > 64 ? slug.substring(0, 64) : slug;
	}

}
