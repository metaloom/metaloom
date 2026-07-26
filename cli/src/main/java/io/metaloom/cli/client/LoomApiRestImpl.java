package io.metaloom.cli.client;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import io.metaloom.cli.ExitCode;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.rest.model.common.AbstractListResponse;
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
 * {@link LoomApi} backed by {@link LoomHttpClient}.
 *
 * <p>Every call funnels through {@link #call}, so there is exactly one place where a
 * transport failure becomes a {@link CliException} with an exit code.</p>
 */
public class LoomApiRestImpl implements LoomApi {

	private final LoomHttpClient client;

	public LoomApiRestImpl(LoomHttpClient client) {
		this.client = client;
	}

	/**
	 * Execute a request, converting any failure into a {@link CliException}.
	 *
	 * @param what    what was being attempted, for the error message
	 * @param request the call
	 */
	private <T extends io.metaloom.loom.rest.model.RestResponseModel<T>> T call(String what,
		Callable<LoomClientRequest<T>> request) {
		try {
			return request.call().sync().body();
		} catch (Exception e) {
			throw ClientErrors.toCliException(e, what);
		}
	}

	private static <T extends io.metaloom.loom.rest.model.RestResponseModel<T>> List<T> dataOf(
		AbstractListResponse<?, T> response) {
		return response == null || response.getData() == null ? List.of() : response.getData();
	}

	// AUTH

	@Override
	public String login(String username, String password) {
		return call("login", () -> client.login(username, password)).getToken();
	}

	@Override
	public UserResponse me() {
		return call("load the current user", client::me);
	}

	// INFO

	@Override
	public RESTInfoResponse info() {
		return call("load server info", client::restInfo);
	}

	@Override
	public String health() {
		return call("check server health", client::health).getStatus();
	}

	// PIPELINES

	@Override
	public List<PipelineResponse> listPipelines() {
		return dataOf(call("list pipelines", client::listPipelines));
	}

	@Override
	public PipelineResponse loadPipeline(UUID uuid) {
		return call("load pipeline " + uuid, () -> client.loadPipeline(uuid));
	}

	@Override
	public PipelineResponse resolvePipeline(String nameOrUuid) {
		if (nameOrUuid == null || nameOrUuid.isBlank()) {
			throw new CliException(ExitCode.USAGE, "A pipeline name or UUID must be given.");
		}
		UUID uuid = tryParseUuid(nameOrUuid);
		if (uuid != null) {
			return loadPipeline(uuid);
		}
		// Names are not unique at the schema level, so an ambiguous match is a real
		// possibility and must be reported rather than silently resolved to the first hit.
		List<PipelineResponse> matches = listPipelines().stream()
			.filter(p -> nameOrUuid.equals(p.getName()))
			.toList();
		if (matches.isEmpty()) {
			throw new CliException(ExitCode.NOT_FOUND, "No pipeline named '" + nameOrUuid + "'.");
		}
		if (matches.size() > 1) {
			throw new CliException(ExitCode.CONFLICT,
				"'" + nameOrUuid + "' matches " + matches.size() + " pipelines. Use the UUID instead.");
		}
		return matches.get(0);
	}

	@Override
	public void deletePipeline(UUID uuid) {
		call("delete pipeline " + uuid, () -> client.deletePipeline(uuid));
	}

	@Override
	public PipelineRunResponse runPipeline(UUID pipelineUuid, PipelineRunRequest request) {
		return call("start a run of pipeline " + pipelineUuid, () -> client.runPipeline(pipelineUuid, request));
	}

	// RUNS

	@Override
	public List<PipelineRunRecord> listRuns(UUID pipelineUuid) {
		return dataOf(call("list runs", () -> client.listPipelineRuns(pipelineUuid)));
	}

	@Override
	public PipelineRunRecord loadRun(UUID pipelineUuid, UUID runUuid) {
		return call("load run " + runUuid, () -> client.loadPipelineRun(pipelineUuid, runUuid));
	}

	@Override
	public List<PipelineRunItemRecord> listRunItems(UUID pipelineUuid, UUID runUuid) {
		return dataOf(call("list run items", () -> client.listPipelineRunItems(pipelineUuid, runUuid)));
	}

	@Override
	public String pauseRun(UUID pipelineUuid, UUID runUuid) {
		return call("pause run " + runUuid, () -> client.pausePipelineRun(pipelineUuid, runUuid)).getMessage();
	}

	@Override
	public String resumeRun(UUID pipelineUuid, UUID runUuid) {
		return call("resume run " + runUuid, () -> client.resumePipelineRun(pipelineUuid, runUuid)).getMessage();
	}

	@Override
	public String cancelRun(UUID pipelineUuid, UUID runUuid) {
		return call("cancel run " + runUuid, () -> client.cancelPipelineRun(pipelineUuid, runUuid)).getMessage();
	}

	@Override
	public PipelineRunStatsResponse runStats() {
		return call("load run statistics", client::loadPipelineRunStats);
	}

	// ORG

	@Override
	public List<SpaceResponse> listSpaces() {
		return dataOf(call("list spaces", client::listSpaces));
	}

	@Override
	public List<LibraryResponse> listLibraries() {
		return dataOf(call("list libraries", client::listLibraries));
	}

	@Override
	public List<AssetPoolResponse> listPools() {
		return dataOf(call("list asset pools", client::listPools));
	}

	// IAM

	@Override
	public List<UserResponse> listUsers() {
		return dataOf(call("list users", client::listUsers));
	}

	@Override
	public List<GroupResponse> listGroups() {
		return dataOf(call("list groups", client::listGroups));
	}

	@Override
	public List<RoleResponse> listRoles() {
		return dataOf(call("list roles", client::listRoles));
	}

	/** @return the parsed UUID, or null when the text is not one */
	public static UUID tryParseUuid(String value) {
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
