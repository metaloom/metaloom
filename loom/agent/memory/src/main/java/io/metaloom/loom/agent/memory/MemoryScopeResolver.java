package io.metaloom.loom.agent.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.memory.MemoryScope;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.api.options.MemoryOptions;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.space.Space;
import io.metaloom.loom.mcp.model.MCPCallerContext;

/**
 * Turns a resolved caller identity into the list of memory scopes that caller may use.
 *
 * <p>This is the only place scopes come from. A scope named in a tool argument is matched against this list by equality; an unknown value yields one
 * uniform "no such scope" message, so it cannot be used to probe which groups or spaces exist.</p>
 */
@Singleton
public class MemoryScopeResolver {

	private static final Logger log = LoggerFactory.getLogger(MemoryScopeResolver.class);

	private final DaoCollection daos;

	private final LoomOptions options;

	@Inject
	public MemoryScopeResolver(DaoCollection daos, LoomOptions options) {
		this.daos = daos;
		this.options = options;
	}

	private MemoryOptions cfg() {
		return options.getMemory();
	}

	/**
	 * The scopes available to the given caller, always starting with the private user scope.
	 *
	 * <p>A chat without a space simply has no space scope — there is deliberately no fallback to the user scope, because silently redirecting a
	 * shared-intent write into a private scope is worse than refusing it.</p>
	 */
	public List<MemoryScopeRef> resolve(MCPCallerContext ctx) {
		List<MemoryScopeRef> scopes = new ArrayList<>();
		if (ctx == null || !ctx.isAuthenticated()) {
			return scopes;
		}
		scopes.add(new MemoryScopeRef(MemoryScope.USER, ctx.userUuid(), "user"));

		if (!cfg().isSharedScopesEnabled()) {
			return scopes;
		}

		if (ctx.spaceUuid() != null) {
			String label = labelOfSpace(ctx.spaceUuid());
			if (label != null) {
				scopes.add(new MemoryScopeRef(MemoryScope.SPACE, ctx.spaceUuid(), label));
			}
		}
		for (UUID groupUuid : ctx.groupUuids()) {
			String label = labelOfGroup(groupUuid);
			if (label != null) {
				scopes.add(new MemoryScopeRef(MemoryScope.GROUP, groupUuid, label));
			}
		}
		return scopes;
	}

	/**
	 * Select one scope from the caller's available set.
	 *
	 * @param scope
	 *            The requested kind; null defaults to {@link MemoryScope#USER}
	 * @param ref
	 *            Optional group/space selector — a label or a uuid. Ignored for the user scope. When omitted and exactly one scope of the requested kind
	 *            exists, that one is used.
	 * @throws MemoryException
	 *             when the caller has no such scope
	 */
	public MemoryScopeRef select(List<MemoryScopeRef> available, MemoryScope scope, String ref) {
		MemoryScope wanted = scope == null ? MemoryScope.USER : scope;
		List<MemoryScopeRef> candidates = available.stream().filter(s -> s.scope() == wanted).toList();
		if (candidates.isEmpty()) {
			throw new MemoryException(noSuchScopeMessage(wanted, available));
		}
		if (ref == null || ref.isBlank()) {
			if (candidates.size() == 1) {
				return candidates.get(0);
			}
			throw new MemoryException("This conversation has several " + wanted.key() + " scopes ("
				+ candidates.stream().map(MemoryScopeRef::label).reduce((a, b) -> a + ", " + b).orElse("")
				+ "). Name the one you mean.");
		}
		String needle = ref.strip();
		return candidates.stream()
			.filter(s -> s.label().equalsIgnoreCase(needle) || s.scopeUuid().toString().equalsIgnoreCase(needle))
			.findFirst()
			.orElseThrow(() -> new MemoryException(noSuchScopeMessage(wanted, available)));
	}

	/**
	 * One uniform message for every unavailable scope. It must not reveal whether the scope exists for somebody else.
	 */
	private String noSuchScopeMessage(MemoryScope wanted, List<MemoryScopeRef> available) {
		if (wanted == MemoryScope.SPACE) {
			return "This chat is not associated with a space (project). Use scope 'user'"
				+ (available.stream().anyMatch(s -> s.scope() == MemoryScope.GROUP) ? " or 'group'." : ".");
		}
		String usable = available.stream().map(MemoryScopeRef::ref).reduce((a, b) -> a + ", " + b).orElse("none");
		return "No '" + wanted.key() + "' memory scope is available in this conversation. Available scopes: " + usable + ".";
	}

	private String labelOfSpace(UUID spaceUuid) {
		try {
			Space space = daos.spaceDao().load(spaceUuid);
			return space == null ? null : space.getName();
		} catch (Exception e) {
			log.warn("Could not resolve space {} for memory scoping", spaceUuid, e);
			return null;
		}
	}

	private String labelOfGroup(UUID groupUuid) {
		try {
			Group group = daos.groupDao().load(groupUuid);
			return group == null ? null : group.getName();
		} catch (Exception e) {
			log.warn("Could not resolve group {} for memory scoping", groupUuid, e);
			return null;
		}
	}

}
