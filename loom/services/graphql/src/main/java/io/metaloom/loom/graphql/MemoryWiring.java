package io.metaloom.loom.graphql;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import graphql.GraphqlErrorException;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.TypeRuntimeWiring;
import io.metaloom.loom.api.memory.MemoryScope;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.memory.MemoryDenyRule;
import io.metaloom.loom.db.model.memory.MemoryDenyRuleDao;
import io.metaloom.loom.db.model.memory.MemoryEntry;
import io.metaloom.loom.db.model.memory.MemoryEntryDao;
import io.metaloom.loom.db.model.memory.MemoryEntryDao.MemoryScopeStats;
import io.metaloom.loom.db.model.perm.Permission;

/**
 * Wiring for the agent memory bank: memory entries and the instance wide denylist.
 */
public class MemoryWiring extends AbstractDomainWiring {

	/**
	 * Upper bound for the {@code limit} argument of {@code memoryEntries}. A scope can hold a lot of notes and the list query projects the full body.
	 */
	private static final int MAX_LIMIT = 500;

	private final MemoryEntryDao entryDao;
	private final MemoryDenyRuleDao denyRuleDao;

	public MemoryWiring(DaoCollection daos) {
		this.entryDao = daos.memoryEntryDao();
		this.denyRuleDao = daos.memoryDenyRuleDao();
	}

	@Override
	public void wire(RuntimeWiring.Builder builder) {

		// MemoryEntry
		DataFetcher<MemoryEntry> entryFetcher = env -> {
			requirePermission(env, Permission.READ_MEMORY);
			return entryDao.load(uuidArg(env, "uuid"));
		};

		DataFetcher<MemoryEntry> entryByPathFetcher = env -> {
			requirePermission(env, Permission.READ_MEMORY);
			return entryDao.loadByPath(scopeArg(env), uuidArg(env, "scopeUuid"), env.getArgument("memoryId"));
		};

		DataFetcher<List<MemoryEntry>> entriesFetcher = env -> {
			requirePermission(env, Permission.READ_MEMORY);
			Integer limit = env.getArgument("limit");
			int effectiveLimit = Math.min(limit == null ? 50 : limit, MAX_LIMIT);
			return orEmpty(entryDao.listByScope(scopeArg(env), uuidArg(env, "scopeUuid"), env.getArgument("prefix"), effectiveLimit));
		};

		DataFetcher<MemoryScopeStats> statsFetcher = env -> {
			requirePermission(env, Permission.READ_MEMORY);
			MemoryScopeStats stats = entryDao.stats(scopeArg(env), uuidArg(env, "scopeUuid"));
			return stats == null ? MemoryScopeStats.EMPTY : stats;
		};

		DataFetcher<String> entryScopeFetcher = env -> {
			MemoryEntry entry = env.getSource();
			return entry.getScope() == null ? null : entry.getScope().name();
		};

		// MemoryDenyRule
		DataFetcher<MemoryDenyRule> denyRuleFetcher = env -> {
			requirePermission(env, Permission.READ_MEMORY_DENY_RULE);
			return denyRuleDao.load(uuidArg(env, "uuid"));
		};

		DataFetcher<MemoryDenyRule> denyRuleByNameFetcher = env -> {
			requirePermission(env, Permission.READ_MEMORY_DENY_RULE);
			return denyRuleDao.loadByName(env.getArgument("name"));
		};

		DataFetcher<List<? extends MemoryDenyRule>> denyRulesFetcher = env -> {
			requirePermission(env, Permission.READ_MEMORY_DENY_RULE);
			Boolean enabledOnly = env.getArgument("enabledOnly");
			if (Boolean.TRUE.equals(enabledOnly)) {
				return orEmpty(denyRuleDao.loadEnabled());
			}
			return denyRuleDao.findAll().collect(Collectors.toList());
		};

		builder
			.type(TypeRuntimeWiring.newTypeWiring("Query")
				.dataFetcher("memoryEntry", entryFetcher)
				.dataFetcher("memoryEntryByPath", entryByPathFetcher)
				.dataFetcher("memoryEntries", entriesFetcher)
				.dataFetcher("memoryStats", statsFetcher)
				.dataFetcher("memoryDenyRule", denyRuleFetcher)
				.dataFetcher("memoryDenyRuleByName", denyRuleByNameFetcher)
				.dataFetcher("memoryDenyRules", denyRulesFetcher))
			.type(TypeRuntimeWiring.newTypeWiring("MemoryEntry")
				.dataFetcher("scope", entryScopeFetcher));
	}

	/**
	 * Read the mandatory {@code scope} argument. Enum arguments arrive as their name, which is exactly the wire form {@link MemoryScope#parse(String)}
	 * expects.
	 */
	private static MemoryScope scopeArg(DataFetchingEnvironment env) {
		Object raw = env.getArgument("scope");
		if (raw instanceof MemoryScope) {
			return (MemoryScope) raw;
		}
		MemoryScope scope = raw == null ? null : MemoryScope.parse(String.valueOf(raw));
		if (scope == null) {
			throw GraphqlErrorException.newErrorException()
				.message("Argument 'scope' is not a valid memory scope: " + raw)
				.extensions(Map.of("code", "BAD_USER_INPUT", "argument", "scope"))
				.build();
		}
		return scope;
	}

}
