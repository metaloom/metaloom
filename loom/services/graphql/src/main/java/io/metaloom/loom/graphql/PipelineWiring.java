package io.metaloom.loom.graphql;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import graphql.schema.DataFetcher;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.TypeRuntimeWiring;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineDao;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.model.pipeline.PipelineRunDao;
import io.metaloom.loom.db.model.pipeline.PipelineVersion;
import io.metaloom.loom.db.model.pipeline.PipelineVersionDao;

/**
 * Wiring for pipelines, their versions and their runs.
 */
public class PipelineWiring extends AbstractDomainWiring {

	private final PipelineDao pipelineDao;
	private final PipelineVersionDao versionDao;
	private final PipelineRunDao runDao;

	public PipelineWiring(DaoCollection daos) {
		this.pipelineDao = daos.pipelineDao();
		this.versionDao = daos.pipelineVersionDao();
		this.runDao = daos.pipelineRunDao();
	}

	@Override
	public void wire(RuntimeWiring.Builder builder) {

		// Pipeline
		DataFetcher<Pipeline> pipelineFetcher = env -> {
			requirePermission(env, Permission.READ_PIPELINE);
			return pipelineDao.load(uuidArg(env, "uuid"));
		};

		DataFetcher<List<? extends Pipeline>> pipelinesFetcher = env -> {
			requirePermission(env, Permission.READ_PIPELINE);
			return pipelineDao.findAll().collect(Collectors.toList());
		};

		// PipelineVersion
		DataFetcher<PipelineVersion> versionFetcher = env -> {
			requirePermission(env, Permission.READ_PIPELINE_VERSION);
			return versionDao.load(uuidArg(env, "uuid"));
		};

		DataFetcher<List<PipelineVersion>> versionsFetcher = env -> {
			requirePermission(env, Permission.READ_PIPELINE_VERSION);
			return orEmpty(versionDao.loadByPipeline(uuidArg(env, "pipelineUuid")));
		};

		DataFetcher<PipelineVersion> versionByNumberFetcher = env -> {
			requirePermission(env, Permission.READ_PIPELINE_VERSION);
			int versionNumber = env.getArgument("versionNumber");
			return versionDao.loadByPipelineAndVersion(uuidArg(env, "pipelineUuid"), versionNumber);
		};

		// PipelineRun
		DataFetcher<PipelineRun> runFetcher = env -> {
			requirePermission(env, Permission.READ_PIPELINE_RUN);
			return runDao.load(uuidArg(env, "uuid"));
		};

		DataFetcher<List<? extends PipelineRun>> runsFetcher = env -> {
			requirePermission(env, Permission.READ_PIPELINE_RUN);
			UUID pipelineUuid = uuidArg(env, "pipelineUuid");
			String status = env.getArgument("status");
			Stream<? extends PipelineRun> runs;
			if (pipelineUuid != null) {
				runs = orEmpty(runDao.loadByPipeline(pipelineUuid)).stream();
			} else if (status != null) {
				// Serve the status only case straight from the indexed DAO lookup.
				return orEmpty(runDao.loadByStatus(status));
			} else {
				runs = runDao.findAll();
			}
			if (status != null) {
				runs = runs.filter(run -> status.equals(run.getStatus()));
			}
			return runs.collect(Collectors.toList());
		};

		DataFetcher<PipelineRun> latestRunFetcher = env -> {
			requirePermission(env, Permission.READ_PIPELINE_RUN);
			return runDao.loadLatestByPipeline(uuidArg(env, "pipelineUuid"));
		};

		// Pipeline field resolvers
		DataFetcher<PipelineVersion> pipelineLatestVersionFetcher = env -> {
			requirePermission(env, Permission.READ_PIPELINE_VERSION);
			Pipeline pipeline = env.getSource();
			UUID latest = pipeline.getLatestVersionUuid();
			return latest == null ? null : versionDao.load(latest);
		};

		DataFetcher<List<PipelineVersion>> pipelineVersionsFetcher = env -> {
			requirePermission(env, Permission.READ_PIPELINE_VERSION);
			Pipeline pipeline = env.getSource();
			return orEmpty(versionDao.loadByPipeline(pipeline.getUuid()));
		};

		DataFetcher<List<PipelineRun>> pipelineRunsFetcher = env -> {
			requirePermission(env, Permission.READ_PIPELINE_RUN);
			Pipeline pipeline = env.getSource();
			return orEmpty(runDao.loadByPipeline(pipeline.getUuid()));
		};

		// Back references
		DataFetcher<Pipeline> versionPipelineFetcher = env -> {
			requirePermission(env, Permission.READ_PIPELINE);
			PipelineVersion version = env.getSource();
			return version.getPipelineUuid() == null ? null : pipelineDao.load(version.getPipelineUuid());
		};

		DataFetcher<Pipeline> runPipelineFetcher = env -> {
			requirePermission(env, Permission.READ_PIPELINE);
			PipelineRun run = env.getSource();
			return run.getPipelineUuid() == null ? null : pipelineDao.load(run.getPipelineUuid());
		};

		builder
			.type(TypeRuntimeWiring.newTypeWiring("Query")
				.dataFetcher("pipeline", pipelineFetcher)
				.dataFetcher("pipelines", pipelinesFetcher)
				.dataFetcher("pipelineVersion", versionFetcher)
				.dataFetcher("pipelineVersions", versionsFetcher)
				.dataFetcher("pipelineVersionByNumber", versionByNumberFetcher)
				.dataFetcher("pipelineRun", runFetcher)
				.dataFetcher("pipelineRuns", runsFetcher)
				.dataFetcher("latestPipelineRun", latestRunFetcher))
			.type(TypeRuntimeWiring.newTypeWiring("Pipeline")
				.dataFetcher("latestVersion", pipelineLatestVersionFetcher)
				.dataFetcher("versions", pipelineVersionsFetcher)
				.dataFetcher("runs", pipelineRunsFetcher))
			.type(TypeRuntimeWiring.newTypeWiring("PipelineVersion")
				.dataFetcher("pipeline", versionPipelineFetcher))
			.type(TypeRuntimeWiring.newTypeWiring("PipelineRun")
				.dataFetcher("pipeline", runPipelineFetcher));
	}

}
