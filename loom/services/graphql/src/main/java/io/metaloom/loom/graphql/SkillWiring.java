package io.metaloom.loom.graphql;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import graphql.schema.DataFetcher;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.TypeRuntimeWiring;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.skill.Skill;
import io.metaloom.loom.db.model.skill.SkillDao;
import io.metaloom.loom.db.model.skill.SkillVersion;
import io.metaloom.loom.db.model.skill.SkillVersionDao;

/**
 * Wiring for skills and their immutable versions.
 */
public class SkillWiring extends AbstractDomainWiring {

	private final SkillDao skillDao;
	private final SkillVersionDao versionDao;

	public SkillWiring(DaoCollection daos) {
		this.skillDao = daos.skillDao();
		this.versionDao = daos.skillVersionDao();
	}

	@Override
	public void wire(RuntimeWiring.Builder builder) {

		// Skill
		DataFetcher<Skill> skillFetcher = env -> {
			requirePermission(env, Permission.READ_SKILL);
			return skillDao.load(uuidArg(env, "uuid"));
		};

		DataFetcher<List<? extends Skill>> skillsFetcher = env -> {
			requirePermission(env, Permission.READ_SKILL);
			return skillDao.findAll().collect(Collectors.toList());
		};

		// SkillVersion
		DataFetcher<SkillVersion> versionFetcher = env -> {
			requirePermission(env, Permission.READ_SKILL_VERSION);
			return versionDao.load(uuidArg(env, "uuid"));
		};

		DataFetcher<List<SkillVersion>> versionsFetcher = env -> {
			requirePermission(env, Permission.READ_SKILL_VERSION);
			return orEmpty(versionDao.loadBySkill(uuidArg(env, "skillUuid")));
		};

		DataFetcher<SkillVersion> versionByNumberFetcher = env -> {
			requirePermission(env, Permission.READ_SKILL_VERSION);
			int versionNumber = env.getArgument("versionNumber");
			return versionDao.loadBySkillAndVersion(uuidArg(env, "skillUuid"), versionNumber);
		};

		DataFetcher<SkillVersion> latestVersionFetcher = env -> {
			requirePermission(env, Permission.READ_SKILL_VERSION);
			return versionDao.loadLatestBySkill(uuidArg(env, "skillUuid"));
		};

		// Skill field resolvers
		DataFetcher<SkillVersion> skillActiveVersionFetcher = env -> {
			requirePermission(env, Permission.READ_SKILL_VERSION);
			Skill skill = env.getSource();
			UUID active = skill.getActiveVersionUuid();
			return active == null ? null : versionDao.load(active);
		};

		DataFetcher<SkillVersion> skillLatestVersionFetcher = env -> {
			requirePermission(env, Permission.READ_SKILL_VERSION);
			Skill skill = env.getSource();
			return versionDao.loadLatestBySkill(skill.getUuid());
		};

		DataFetcher<List<SkillVersion>> skillVersionsFetcher = env -> {
			requirePermission(env, Permission.READ_SKILL_VERSION);
			Skill skill = env.getSource();
			return orEmpty(versionDao.loadBySkill(skill.getUuid()));
		};

		// Back reference
		DataFetcher<Skill> versionSkillFetcher = env -> {
			requirePermission(env, Permission.READ_SKILL);
			SkillVersion version = env.getSource();
			return version.getSkillUuid() == null ? null : skillDao.load(version.getSkillUuid());
		};

		builder
			.type(TypeRuntimeWiring.newTypeWiring("Query")
				.dataFetcher("skill", skillFetcher)
				.dataFetcher("skills", skillsFetcher)
				.dataFetcher("skillVersion", versionFetcher)
				.dataFetcher("skillVersions", versionsFetcher)
				.dataFetcher("skillVersionByNumber", versionByNumberFetcher)
				.dataFetcher("latestSkillVersion", latestVersionFetcher))
			.type(TypeRuntimeWiring.newTypeWiring("Skill")
				.dataFetcher("activeVersion", skillActiveVersionFetcher)
				.dataFetcher("latestVersion", skillLatestVersionFetcher)
				.dataFetcher("versions", skillVersionsFetcher))
			.type(TypeRuntimeWiring.newTypeWiring("SkillVersion")
				.dataFetcher("skill", versionSkillFetcher));
	}

}
