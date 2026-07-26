package io.metaloom.cli.client;

import java.util.List;
import java.util.UUID;

import io.metaloom.loom.rest.model.group.GroupResponse;
import io.metaloom.loom.rest.model.info.RESTInfoResponse;
import io.metaloom.loom.rest.model.library.LibraryResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunItemRecord;
import io.metaloom.loom.rest.model.pipeline.PipelineRunRecord;
import io.metaloom.loom.rest.model.pipeline.PipelineRunRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineRunResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunStatsResponse;
import io.metaloom.loom.rest.model.pool.AssetPoolResponse;
import io.metaloom.loom.rest.model.role.RoleResponse;
import io.metaloom.loom.rest.model.space.SpaceResponse;
import io.metaloom.loom.rest.model.user.UserResponse;

/**
 * The slice of the Loom API the CLI actually uses.
 *
 * <p>Exists for two reasons.</p>
 *
 * <p><strong>Testability.</strong> {@code LoomHttpClient} aggregates 33 method interfaces;
 * faking it to test a command means implementing all of them. Faking this is a few lines,
 * which is the difference between commands being tested and not.</p>
 *
 * <p><strong>Ergonomics.</strong> Every underlying call is
 * {@code client.x(...).sync().body()} wrapped in a {@code LoomClientException} catch. Doing
 * that once here keeps the commands free of transport boilerplate and guarantees every
 * failure goes through {@link ClientErrors}.</p>
 *
 * <p>Paged endpoints are exposed as plain lists: the CLI asks for one page and says so when
 * there are more, rather than pretending to iterate the whole collection.</p>
 */
public interface LoomApi {

	// AUTH

	/** @return the bearer token */
	String login(String username, String password);

	UserResponse me();

	// INFO

	RESTInfoResponse info();

	String health();

	// PIPELINES

	List<PipelineResponse> listPipelines();

	PipelineResponse loadPipeline(UUID uuid);

	/**
	 * Resolve a pipeline by name or UUID.
	 *
	 * @param nameOrUuid what the user typed
	 * @return the pipeline
	 * @throws CliException 404 when nothing matches, 409 when a name is ambiguous
	 */
	PipelineResponse resolvePipeline(String nameOrUuid);

	void deletePipeline(UUID uuid);

	PipelineRunResponse runPipeline(UUID pipelineUuid, PipelineRunRequest request);

	// RUNS

	List<PipelineRunRecord> listRuns(UUID pipelineUuid);

	PipelineRunRecord loadRun(UUID pipelineUuid, UUID runUuid);

	List<PipelineRunItemRecord> listRunItems(UUID pipelineUuid, UUID runUuid);

	String pauseRun(UUID pipelineUuid, UUID runUuid);

	String resumeRun(UUID pipelineUuid, UUID runUuid);

	String cancelRun(UUID pipelineUuid, UUID runUuid);

	PipelineRunStatsResponse runStats();

	// ORG

	List<SpaceResponse> listSpaces();

	List<LibraryResponse> listLibraries();

	List<AssetPoolResponse> listPools();

	// IAM

	List<UserResponse> listUsers();

	List<GroupResponse> listGroups();

	List<RoleResponse> listRoles();
}
