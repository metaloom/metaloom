package io.metaloom.loom.client.http.impl;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.Vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.client.common.LoomBinaryResponse;
import io.metaloom.loom.client.http.AbstractLoomOkHttpClient;
import io.metaloom.loom.client.http.LoomClientHttpRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.annotation.AnnotationCreateRequest;
import io.metaloom.loom.rest.model.annotation.AnnotationListResponse;
import io.metaloom.loom.rest.model.annotation.AnnotationResponse;
import io.metaloom.loom.rest.model.annotation.AnnotationUpdateRequest;
import io.metaloom.loom.rest.model.asset.AssetCreateRequest;
import io.metaloom.loom.rest.model.asset.AssetListResponse;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.asset.AssetUpdateRequest;
import io.metaloom.loom.rest.model.asset.AssetBulkCreateRequest;
import io.metaloom.loom.rest.model.asset.AssetBulkResponse;
import io.metaloom.loom.rest.model.asset.AssetBulkUpdateRequest;
import io.metaloom.loom.rest.model.asset.AssetComponentCreateRequest;
import io.metaloom.loom.rest.model.asset.AssetComponentListResponse;
import io.metaloom.loom.rest.model.asset.AssetComponentResponse;
import io.metaloom.loom.rest.model.asset.AssetComponentUpdateRequest;
import io.metaloom.loom.rest.model.asset.location.AssetLocationCreateRequest;
import io.metaloom.loom.rest.model.asset.location.AssetLocationListResponse;
import io.metaloom.loom.rest.model.asset.location.AssetLocationResponse;
import io.metaloom.loom.rest.model.asset.location.AssetLocationUpdateRequest;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryCreateRequest;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryListResponse;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryResponse;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryUpdateRequest;
import io.metaloom.loom.rest.model.attachment.AttachmentListResponse;
import io.metaloom.loom.rest.model.attachment.AttachmentResponse;
import io.metaloom.loom.rest.model.attachment.AttachmentUpdateRequest;
import io.metaloom.loom.rest.model.auth.AuthLoginRequest;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.metaloom.loom.rest.model.health.HealthCheckResponse;
import io.metaloom.loom.rest.model.pool.AssetPoolCreateRequest;
import io.metaloom.loom.rest.model.pool.AssetPoolListResponse;
import io.metaloom.loom.rest.model.pool.AssetPoolResponse;
import io.metaloom.loom.rest.model.pool.AssetPoolUpdateRequest;
import io.metaloom.loom.rest.model.cluster.ClusterCreateRequest;
import io.metaloom.loom.rest.model.cluster.ClusterListResponse;
import io.metaloom.loom.rest.model.cluster.ClusterResponse;
import io.metaloom.loom.rest.model.cluster.ClusterUpdateRequest;
import io.metaloom.loom.rest.model.blacklist.BlacklistCreateRequest;
import io.metaloom.loom.rest.model.blacklist.BlacklistListResponse;
import io.metaloom.loom.rest.model.blacklist.BlacklistResponse;
import io.metaloom.loom.rest.model.blacklist.BlacklistUpdateRequest;
import io.metaloom.loom.rest.model.chat.ChatCreateRequest;
import io.metaloom.loom.rest.model.chat.ChatListResponse;
import io.metaloom.loom.rest.model.chat.ChatResponse;
import io.metaloom.loom.rest.model.chat.ChatUpdateRequest;
import io.metaloom.loom.rest.model.skill.SkillCreateRequest;
import io.metaloom.loom.rest.model.skill.SkillListResponse;
import io.metaloom.loom.rest.model.skill.SkillResponse;
import io.metaloom.loom.rest.model.skill.SkillUpdateRequest;
import io.metaloom.loom.rest.model.skill.SkillVersionListResponse;
import io.metaloom.loom.rest.model.collection.CollectionCreateRequest;
import io.metaloom.loom.rest.model.collection.CollectionListResponse;
import io.metaloom.loom.rest.model.collection.CollectionResponse;
import io.metaloom.loom.rest.model.collection.CollectionUpdateRequest;
import io.metaloom.loom.rest.model.comment.CommentCreateRequest;
import io.metaloom.loom.rest.model.comment.CommentListResponse;
import io.metaloom.loom.rest.model.comment.CommentResponse;
import io.metaloom.loom.rest.model.comment.CommentUpdateRequest;
import io.metaloom.loom.rest.model.detection.DetectionBulkCreateRequest;
import io.metaloom.loom.rest.model.detection.DetectionBulkResponse;
import io.metaloom.loom.rest.model.detection.DetectionCreateRequest;
import io.metaloom.loom.rest.model.detection.DetectionListResponse;
import io.metaloom.loom.rest.model.detection.DetectionResponse;
import io.metaloom.loom.rest.model.detection.DetectionUpdateRequest;
import io.metaloom.loom.rest.model.embedding.EmbeddingCreateRequest;
import io.metaloom.loom.rest.model.embedding.EmbeddingListResponse;
import io.metaloom.loom.rest.model.graphql.GraphQLRequest;
import io.metaloom.loom.rest.model.graphql.GraphQLResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingUpdateRequest;
import io.metaloom.loom.rest.model.group.GroupCreateRequest;
import io.metaloom.loom.rest.model.group.GroupListResponse;
import io.metaloom.loom.rest.model.group.GroupResponse;
import io.metaloom.loom.rest.model.group.GroupUpdateRequest;
import io.metaloom.loom.rest.model.library.LibraryCreateRequest;
import io.metaloom.loom.rest.model.library.LibraryListResponse;
import io.metaloom.loom.rest.model.library.LibraryResponse;
import io.metaloom.loom.rest.model.library.LibraryUpdateRequest;
import io.metaloom.loom.rest.model.person.PersonCreateRequest;
import io.metaloom.loom.rest.model.person.PersonListResponse;
import io.metaloom.loom.rest.model.person.PersonResponse;
import io.metaloom.loom.rest.model.person.PersonUpdateRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineCreateRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunItemListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunRecord;
import io.metaloom.loom.rest.model.pipeline.PipelineRunRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineRunResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunStatsResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineUpdateRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineVersionListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineVersionRestoreRequest;
import io.metaloom.loom.rest.model.info.RESTInfoResponse;
import io.metaloom.loom.rest.model.message.GenericMessageResponse;
import io.metaloom.loom.rest.model.space.SpaceCreateRequest;
import io.metaloom.loom.rest.model.space.SpaceListResponse;
import io.metaloom.loom.rest.model.space.SpaceResponse;
import io.metaloom.loom.rest.model.space.SpaceUpdateRequest;
import io.metaloom.loom.rest.model.reaction.ReactionCreateRequest;
import io.metaloom.loom.rest.model.reaction.ReactionListResponse;
import io.metaloom.loom.rest.model.reaction.ReactionResponse;
import io.metaloom.loom.rest.model.reaction.ReactionUpdateRequest;
import io.metaloom.loom.rest.model.role.RoleCreateRequest;
import io.metaloom.loom.rest.model.role.RoleListResponse;
import io.metaloom.loom.rest.model.role.RoleResponse;
import io.metaloom.loom.rest.model.role.RoleUpdateRequest;
import io.metaloom.loom.rest.model.tag.TagCreateRequest;
import io.metaloom.loom.rest.model.tag.TagListResponse;
import io.metaloom.loom.rest.model.tag.TagRatingRequest;
import io.metaloom.loom.rest.model.tag.TagRatingResponse;
import io.metaloom.loom.rest.model.tag.TagResponse;
import io.metaloom.loom.rest.model.tag.TagUpdateRequest;
import io.metaloom.loom.rest.model.task.TaskCreateRequest;
import io.metaloom.loom.rest.model.task.TaskListResponse;
import io.metaloom.loom.rest.model.task.TaskResponse;
import io.metaloom.loom.rest.model.task.TaskUpdateRequest;
import io.metaloom.loom.rest.model.token.TokenCreateRequest;
import io.metaloom.loom.rest.model.token.TokenListResponse;
import io.metaloom.loom.rest.model.token.TokenResponse;
import io.metaloom.loom.rest.model.token.TokenUpdateRequest;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompListResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;
import io.metaloom.loom.rest.model.fingerprintcomp.FingerprintCompCreateRequest;
import io.metaloom.loom.rest.model.fingerprintcomp.FingerprintCompListResponse;
import io.metaloom.loom.rest.model.fingerprintcomp.FingerprintCompResponse;
import io.metaloom.loom.rest.model.segmentcomp.SegmentCompCreateRequest;
import io.metaloom.loom.rest.model.segmentcomp.SegmentCompListResponse;
import io.metaloom.loom.rest.model.segmentcomp.SegmentCompResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultCreateRequest;
import io.metaloom.loom.rest.model.noderesult.NodeResultListResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.metaloom.loom.rest.model.transcript.TranscriptCreateRequest;
import io.metaloom.loom.rest.model.transcript.TranscriptListResponse;
import io.metaloom.loom.rest.model.transcript.TranscriptResponse;
import io.metaloom.loom.rest.model.transcript.TranscriptUpdateRequest;
import io.metaloom.loom.rest.model.user.UserCreateRequest;
import io.metaloom.loom.rest.model.user.UserListResponse;
import io.metaloom.loom.rest.model.user.UserResponse;
import io.metaloom.loom.rest.model.user.UserUpdateRequest;
import okhttp3.OkHttpClient;

/**
 * Implementation for the {@link io.metaloom.loom.client.http.LoomHttpClient}
 */
public class LoomHttpClientImpl extends AbstractLoomOkHttpClient {

	public static final Logger log = LoggerFactory.getLogger(LoomHttpClientImpl.class);

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Create a new client with a default timeout of 10s for all timeouts (connect, read and write).
	 * 
	 * @param okClient
	 * @param scheme
	 * @param hostname
	 * @param port
	 * @param pathPrefix
	 * @param connectTimeout
	 * @param readTimeout
	 * @param writeTimeout
	 */
	public LoomHttpClientImpl(OkHttpClient okClient, String scheme, String hostname, int port, String pathPrefix, Duration connectTimeout, Duration readTimeout,
		Duration writeTimeout) {
		super(okClient, scheme, hostname, port, pathPrefix, connectTimeout, readTimeout, writeTimeout);
	}

	public static class Builder {

		private String scheme = "http";
		private String hostname = "localhost";
		private String pathPrefix = "";
		private int port = 6333;

		private Duration connectTimeout = Duration.ofMillis(10_000);
		private Duration readTimeout = Duration.ofMillis(10_000);
		private Duration writeTimeout = Duration.ofMillis(10_000);
		private OkHttpClient okClient;

		/**
		 * Verify the builder and build the client.
		 * 
		 * @return
		 */
		public LoomHttpClientImpl build() {
			Objects.requireNonNull(scheme, "A protocol scheme has to be specified.");
			Objects.requireNonNull(hostname, "A hostname has to be specified.");

			if (okClient == null) {
				okClient = createDefaultOkHttpClient();
			}

			return new LoomHttpClientImpl(okClient, scheme, hostname, port, pathPrefix, connectTimeout, readTimeout, writeTimeout);
		}

		private OkHttpClient createDefaultOkHttpClient() {
			okhttp3.OkHttpClient.Builder builder = new OkHttpClient.Builder();
			builder.connectTimeout(connectTimeout);
			builder.readTimeout(readTimeout);
			builder.writeTimeout(writeTimeout);
			// // Disable gzip
			// builder.addInterceptor(chain -> {
			// Request request = chain.request();
			// Request newRequest;
			// try {
			// newRequest = request.newBuilder().addHeader("Accept-Encoding", "identity").build();
			// } catch (Exception e) {
			// log.error("Error while creating new request", e);
			// return chain.proceed(request);
			// }
			// return chain.proceed(newRequest);
			// });
			return builder.build();
		}

		/**
		 * Set the protocol scheme to be used for the client (e.g.: http, https).
		 * 
		 * @param scheme
		 * @return Fluent API
		 */
		public Builder setScheme(String scheme) {
			this.scheme = scheme;
			return this;
		}

		/**
		 * Set the hostname for the client.
		 * 
		 * @param hostname
		 * @return Fluent API
		 */
		public Builder setHostname(String hostname) {
			this.hostname = hostname;
			return this;
		}

		/**
		 * Set the path prefix the Loom API is mounted under.
		 *
		 * <p>Only needed when Loom sits behind a reverse proxy that serves it from a
		 * sub-path - e.g. {@code https://example.com/loom/api/v1/...} needs
		 * {@code setPathPrefix("loom")}. Defaults to the empty string.</p>
		 *
		 * @param pathPrefix prefix without surrounding slashes; null is treated as empty
		 * @return Fluent API
		 */
		public Builder setPathPrefix(String pathPrefix) {
			this.pathPrefix = pathPrefix == null ? "" : pathPrefix;
			return this;
		}

		/**
		 * Set a custom http client to be used. A default client will be generated if non is specified.
		 * 
		 * @param okClient
		 * @return
		 */
		public Builder setOkHttpClient(OkHttpClient okClient) {
			this.okClient = okClient;
			return this;
		}

		/**
		 * Set the port to connect to. (e.g. 6333).
		 * 
		 * @param port
		 * @return Fluent API
		 */
		public Builder setPort(int port) {
			this.port = port;
			return this;
		}

		/**
		 * Set connection timeout.
		 * 
		 * @param connectTimeout
		 * @return Fluent API
		 */
		public Builder setConnectTimeout(Duration connectTimeout) {
			if (okClient != null) {
				throw new RuntimeException("Please configure the timeout on the okHttpClient you provided.");
			}
			this.connectTimeout = connectTimeout;
			return this;
		}

		/**
		 * Set read timeout for the client.
		 * 
		 * @param readTimeout
		 * @return Fluent API
		 */
		public Builder setReadTimeout(Duration readTimeout) {
			if (okClient != null) {
				throw new RuntimeException("Please configure the timeout on the okHttpClient you provided.");
			}
			this.readTimeout = readTimeout;
			return this;
		}

		/**
		 * Set write timeout for the client.
		 * 
		 * @param writeTimeout
		 * @return Fluent API
		 */
		public Builder setWriteTimeout(Duration writeTimeout) {
			if (okClient != null) {
				throw new RuntimeException("Please configure the timeout on the okHttpClient you provided.");
			}
			this.writeTimeout = writeTimeout;
			return this;
		}

	}

	// REST Methods

	@Override
	public LoomClientHttpRequest<AuthLoginResponse> login(String username, String password) {
		AuthLoginRequest request = new AuthLoginRequest();
		request.setUsername(username);
		request.setPassword(password);
		return postRequest("/login", request, AuthLoginResponse.class);
	}

	@Override
	public LoomClientHttpRequest<UserResponse> loadUser(UUID uuid) {
		return getRequest("users/" + uuid, UserResponse.class);
	}

	@Override
	public LoomClientHttpRequest<UserResponse> createUser(UserCreateRequest request) {
		return postRequest("users", request, UserResponse.class);
	}

	@Override
	public LoomClientHttpRequest<UserResponse> updateUser(UUID userUuid, UserUpdateRequest request) {
		return postRequest("users/" + userUuid, request, UserResponse.class);
	}

	@Override
	public LoomClientHttpRequest<UserResponse> patchUser(UUID userUuid, UserUpdateRequest request) {
		return patchRequest("users/" + userUuid, request, UserResponse.class);
	}

	@Override
	public LoomClientHttpRequest<UserResponse> replaceUser(UUID userUuid, UserUpdateRequest request) {
		return putRequest("users/" + userUuid, request, UserResponse.class);
	}

	@Override
	public LoomClientHttpRequest<UserListResponse> listUsers() {
		return getRequest("users", UserListResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> deleteUser(UUID userUuid) {
		return deleteRequest("users/" + userUuid);
	}

	// ASSET

	/**
	 * Build the URL path segment for an asset identifier.
	 * UUID-based:   "assets/{uuid}"
	 * SHA-512-based: "assets/sha512/{hash}"
	 */
	private String assetPath(AssetId assetId) {
		if (assetId.isUUID()) {
			return "assets/" + assetId.uuid();
		} else {
			return "assets/sha512/" + assetId.hashsum();
		}
	}

	@Override
	public LoomClientHttpRequest<AssetResponse> loadAsset(AssetId assetId) {
		return getRequest(assetPath(assetId), AssetResponse.class);
	}

	@Override
	public LoomClientHttpRequest<AssetResponse> createAsset(AssetCreateRequest request) {
		return postRequest("assets", request, AssetResponse.class);
	}

	@Override
	public LoomClientHttpRequest<AssetResponse> updateAsset(AssetId assetId, AssetUpdateRequest request) {
		return postRequest(assetPath(assetId), request, AssetResponse.class);
	}

	@Override
	public LoomClientHttpRequest<AssetResponse> patchAsset(AssetId assetId, AssetUpdateRequest request) {
		return patchRequest(assetPath(assetId), request, AssetResponse.class);
	}

	@Override
	public LoomClientHttpRequest<AssetResponse> replaceAsset(AssetId assetId, AssetUpdateRequest request) {
		return putRequest(assetPath(assetId), request, AssetResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> deleteAsset(AssetId assetId) {
		return deleteRequest(assetPath(assetId));
	}

	@Override
	public LoomClientHttpRequest<AssetListResponse> listAssets() {
		return getRequest("assets", AssetListResponse.class);
	}

	@Override
	public LoomClientHttpRequest<AssetBulkResponse> bulkCreateAssets(AssetBulkCreateRequest request) {
		return postRequest("assets/bulk/create", request, AssetBulkResponse.class);
	}

	@Override
	public LoomClientHttpRequest<AssetBulkResponse> bulkUpdateAssets(AssetBulkUpdateRequest request) {
		return postRequest("assets/bulk/update", request, AssetBulkResponse.class);
	}

	// ASSET + TAG

	@Override
	public LoomClientHttpRequest<TagResponse> tagAsset(AssetId assetId, TagCreateRequest request) {
		return postRequest(assetPath(assetId) + "/tags", request, TagResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> untagAsset(AssetId assetId, UUID tagUuid) {
		return deleteRequest(assetPath(assetId) + "/tags/" + tagUuid);
	}

	// LOCATION

	@Override
	public LoomClientHttpRequest<NoResponse> deleteLocation(UUID locationUuid) {
		return deleteRequest("locations/" + locationUuid);
	}

	@Override
	public LoomClientHttpRequest<AssetLocationResponse> createLocation(AssetLocationCreateRequest request) {
		return postRequest("locations", request, AssetLocationResponse.class);
	}

	@Override
	public LoomClientHttpRequest<AssetLocationResponse> updateLocation(UUID locationUuid, AssetLocationUpdateRequest request) {
		return postRequest("locations/" + locationUuid, request, AssetLocationResponse.class);
	}

	@Override
	public LoomClientHttpRequest<AssetLocationResponse> loadLocation(UUID locationUuid) {
		return getRequest("locations/" + locationUuid, AssetLocationResponse.class);
	}

	@Override
	public LoomClientHttpRequest<AssetLocationListResponse> listLocations() {
		return getRequest("locations", AssetLocationListResponse.class);
	}

	// CLUSTER

	@Override
	public LoomClientHttpRequest<ClusterResponse> loadCluster(UUID uuid) {
		return getRequest("/clusters/" + uuid, ClusterResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> deleteCluster(UUID uuid) {
		return deleteRequest("/clusters/" + uuid);
	}

	@Override
	public LoomClientHttpRequest<ClusterResponse> updateCluster(UUID clusterUuid, ClusterUpdateRequest request) {
		return postRequest("clusters/" + clusterUuid, request, ClusterResponse.class);
	}

	@Override
	public LoomClientHttpRequest<ClusterResponse> createCluster(ClusterCreateRequest request) {
		return postRequest("clusters", request, ClusterResponse.class);
	}

	@Override
	public LoomClientHttpRequest<ClusterListResponse> listClusters() {
		return getRequest("clusters", ClusterListResponse.class);
	}

	// PERSON

	@Override
	public LoomClientHttpRequest<PersonResponse> loadPerson(UUID uuid) {
		return getRequest("persons/" + uuid, PersonResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> deletePerson(UUID uuid) {
		return deleteRequest("persons/" + uuid);
	}

	@Override
	public LoomClientHttpRequest<PersonResponse> updatePerson(UUID personUuid, PersonUpdateRequest request) {
		return postRequest("persons/" + personUuid, request, PersonResponse.class);
	}

	@Override
	public LoomClientHttpRequest<PersonResponse> createPerson(PersonCreateRequest request) {
		return postRequest("persons", request, PersonResponse.class);
	}

	@Override
	public LoomClientHttpRequest<PersonListResponse> listPersons() {
		return getRequest("persons", PersonListResponse.class);
	}

	// SPACE

	@Override
	public LoomClientHttpRequest<SpaceResponse> loadSpace(UUID spaceUuid) {
		return getRequest("spaces/" + spaceUuid, SpaceResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> deleteSpace(UUID spaceUuid) {
		return deleteRequest("spaces/" + spaceUuid);
	}

	@Override
	public LoomClientHttpRequest<SpaceResponse> createSpace(SpaceCreateRequest request) {
		return postRequest("spaces", request, SpaceResponse.class);
	}

	@Override
	public LoomClientHttpRequest<SpaceResponse> updateSpace(UUID spaceUuid, SpaceUpdateRequest request) {
		return postRequest("spaces/" + spaceUuid, request, SpaceResponse.class);
	}

	@Override
	public LoomClientHttpRequest<SpaceListResponse> listSpaces() {
		return getRequest("spaces", SpaceListResponse.class);
	}

	// LIBRARY

	@Override
	public LoomClientHttpRequest<LibraryResponse> loadLibrary(UUID libraryUuid) {
		return getRequest("libraries/" + libraryUuid, LibraryResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> deleteLibrary(UUID libraryUuid) {
		return deleteRequest("libraries/" + libraryUuid);
	}

	@Override
	public LoomClientHttpRequest<LibraryListResponse> listLibraries() {
		return getRequest("libraries", LibraryListResponse.class);
	}

	@Override
	public LoomClientHttpRequest<LibraryResponse> updateLibrary(UUID libraryUuid, LibraryUpdateRequest request) {
		return postRequest("libraries/" + libraryUuid, request, LibraryResponse.class);
	}

	@Override
	public LoomClientHttpRequest<LibraryResponse> createLibrary(LibraryCreateRequest request) {
		return postRequest("libraries", request, LibraryResponse.class);
	}

	// PIPELINE

	@Override
	public LoomClientHttpRequest<PipelineResponse> loadPipeline(UUID pipelineUuid) {
		return getRequest("pipelines/" + pipelineUuid, PipelineResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> deletePipeline(UUID pipelineUuid) {
		return deleteRequest("pipelines/" + pipelineUuid);
	}

	@Override
	public LoomClientHttpRequest<PipelineListResponse> listPipelines() {
		return getRequest("pipelines", PipelineListResponse.class);
	}

	public LoomClientHttpRequest<PipelineRunListResponse> listPipelineRuns(UUID pipelineUuid) {
		return getRequest("pipelines/" + pipelineUuid + "/runs", PipelineRunListResponse.class);
	}

	@Override
	public LoomClientHttpRequest<PipelineRunStatsResponse> loadPipelineRunStats() {
		return getRequest("pipelines/runs/stats", PipelineRunStatsResponse.class);
	}

	@Override
	public LoomClientHttpRequest<PipelineRunRecord> loadPipelineRun(UUID pipelineUuid, UUID runUuid) {
		return getRequest("pipelines/" + pipelineUuid + "/runs/" + runUuid, PipelineRunRecord.class);
	}

	@Override
	public LoomClientHttpRequest<PipelineRunItemListResponse> listPipelineRunItems(UUID pipelineUuid, UUID runUuid) {
		return getRequest("pipelines/" + pipelineUuid + "/runs/" + runUuid + "/items", PipelineRunItemListResponse.class);
	}

	@Override
	public LoomClientHttpRequest<PipelineResponse> updatePipeline(UUID pipelineUuid, PipelineUpdateRequest request) {
		return postRequest("pipelines/" + pipelineUuid, request, PipelineResponse.class);
	}

	@Override
	public LoomClientHttpRequest<PipelineResponse> createPipeline(PipelineCreateRequest request) {
		return postRequest("pipelines", request, PipelineResponse.class);
	}

	@Override
	public LoomClientHttpRequest<PipelineRunResponse> runPipeline(UUID pipelineUuid, PipelineRunRequest request) {
		return postRequest("pipelines/" + pipelineUuid + "/run", request, PipelineRunResponse.class);
	}

	@Override
	public LoomClientHttpRequest<GenericMessageResponse> pausePipelineRun(UUID pipelineUuid, UUID runUuid) {
		return postRequest("pipelines/" + pipelineUuid + "/runs/" + runUuid + "/pause", GenericMessageResponse.class);
	}

	@Override
	public LoomClientHttpRequest<GenericMessageResponse> resumePipelineRun(UUID pipelineUuid, UUID runUuid) {
		return postRequest("pipelines/" + pipelineUuid + "/runs/" + runUuid + "/resume", GenericMessageResponse.class);
	}

	@Override
	public LoomClientHttpRequest<GenericMessageResponse> cancelPipelineRun(UUID pipelineUuid, UUID runUuid) {
		return postRequest("pipelines/" + pipelineUuid + "/runs/" + runUuid + "/cancel", GenericMessageResponse.class);
	}

	@Override
	public LoomClientHttpRequest<PipelineVersionListResponse> listPipelineVersions(UUID pipelineUuid) {
		return getRequest("pipelines/" + pipelineUuid + "/versions", PipelineVersionListResponse.class);
	}

	@Override
	public LoomClientHttpRequest<PipelineResponse> loadPipelineVersion(UUID pipelineUuid, int version) {
		return getRequest("pipelines/" + pipelineUuid + "/versions/" + version, PipelineResponse.class);
	}

	@Override
	public LoomClientHttpRequest<PipelineResponse> restorePipelineVersion(UUID pipelineUuid, int version,
		PipelineVersionRestoreRequest request) {
		return postRequest("pipelines/" + pipelineUuid + "/versions/" + version + "/restore", request, PipelineResponse.class);
	}

	// INFO

	@Override
	public LoomClientHttpRequest<RESTInfoResponse> restInfo() {
		// The empty path targets /api/v1 itself.
		return getRequest("", RESTInfoResponse.class);
	}

	@Override
	public LoomClientHttpRequest<UserResponse> me() {
		return getRequest("me", UserResponse.class);
	}

	// ANNOTATION

	@Override
	public LoomClientHttpRequest<AnnotationResponse> loadAnnotation(UUID annotationUuid) {
		return getRequest("annotations/" + annotationUuid, AnnotationResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> deleteAnnotation(UUID annotationUuid) {
		return deleteRequest("annotations/" + annotationUuid);
	}

	@Override
	public LoomClientHttpRequest<AnnotationResponse> updateAnnotation(UUID annotationUuid, AnnotationUpdateRequest request) {
		return postRequest("annotations/" + annotationUuid, request, AnnotationResponse.class);
	}

	@Override
	public LoomClientHttpRequest<AnnotationResponse> createAnnotation(AnnotationCreateRequest request) {
		return postRequest("annotations", request, AnnotationResponse.class);
	}

	@Override
	public LoomClientHttpRequest<AnnotationListResponse> listAnnotations() {
		return getRequest("annotations", AnnotationListResponse.class);
	}

	// COLLECTION

	@Override
	public LoomClientHttpRequest<CollectionResponse> loadCollection(UUID collectionUuid) {
		return getRequest("collections/" + collectionUuid, CollectionResponse.class);
	}

	@Override
	public LoomClientHttpRequest<CollectionResponse> createCollection(CollectionCreateRequest request) {
		return postRequest("collections", request, CollectionResponse.class);
	}

	@Override
	public LoomClientHttpRequest<CollectionResponse> updateCollection(UUID collectionUuid, CollectionUpdateRequest request) {
		return postRequest("collections/" + collectionUuid, request, CollectionResponse.class);
	}

	@Override
	public LoomClientHttpRequest<CollectionListResponse> listCollections() {
		return getRequest("collections", CollectionListResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> deleteCollection(UUID collectionUuid) {
		return deleteRequest("collections/" + collectionUuid);
	}

	// TASK REACTION

	public LoomClientHttpRequest<ReactionResponse> loadTaskReaction(UUID taskUuid, UUID reactionUuid) {
		return getRequest("tasks/" + taskUuid + "/reactions/" + reactionUuid, ReactionResponse.class);
	}

	public LoomClientHttpRequest<ReactionResponse> createTaskReaction(UUID taskUuid, ReactionCreateRequest request) {
		return postRequest("tasks/" + taskUuid + "/reactions", request, ReactionResponse.class);
	}

	public LoomClientHttpRequest<ReactionResponse> updateTaskReaction(UUID taskUuid, UUID reactionUuid, ReactionUpdateRequest request) {
		return postRequest("tasks/" + taskUuid + "/reactions/" + reactionUuid, request, ReactionResponse.class);
	}

	public LoomClientHttpRequest<ReactionListResponse> listTaskReaction(UUID taskUuid) {
		return getRequest("tasks/" + taskUuid + "/reactions", ReactionListResponse.class);
	}

	public LoomClientHttpRequest<NoResponse> deleteTaskReaction(UUID taskUuid, UUID reactionUuid) {
		return deleteRequest("tasks/" + taskUuid + "/reactions/" + reactionUuid);
	}

	// ASSET REACTION

	public LoomClientHttpRequest<ReactionResponse> loadAssetReaction(AssetId assetId, UUID reactionUuid) {
		return getRequest(assetPath(assetId) + "/reactions/" + reactionUuid, ReactionResponse.class);
	}

	public LoomClientHttpRequest<ReactionResponse> createAssetReaction(AssetId assetId, ReactionCreateRequest request) {
		return postRequest(assetPath(assetId) + "/reactions", request, ReactionResponse.class);
	}

	public LoomClientHttpRequest<ReactionResponse> updateAssetReaction(AssetId assetId, UUID reactionUuid, ReactionUpdateRequest request) {
		return postRequest(assetPath(assetId) + "/reactions/" + reactionUuid, request, ReactionResponse.class);
	}

	public LoomClientHttpRequest<ReactionListResponse> listAssetReaction(AssetId assetId) {
		return getRequest(assetPath(assetId) + "/reactions", ReactionListResponse.class);
	}

	public LoomClientHttpRequest<NoResponse> deleteAssetReaction(AssetId assetId, UUID reactionUuid) {
		return deleteRequest(assetPath(assetId) + "/reactions/" + reactionUuid);
	}

	// ASSET DETECTION

	public LoomClientHttpRequest<DetectionResponse> loadAssetDetection(AssetId assetId, UUID detectionUuid) {
		return getRequest(assetPath(assetId) + "/detections/" + detectionUuid, DetectionResponse.class);
	}

	public LoomClientHttpRequest<DetectionResponse> createAssetDetection(AssetId assetId, DetectionCreateRequest request) {
		return postRequest(assetPath(assetId) + "/detections", request, DetectionResponse.class);
	}

	public LoomClientHttpRequest<DetectionResponse> updateAssetDetection(AssetId assetId, UUID detectionUuid, DetectionUpdateRequest request) {
		return postRequest(assetPath(assetId) + "/detections/" + detectionUuid, request, DetectionResponse.class);
	}

	public LoomClientHttpRequest<DetectionListResponse> listAssetDetections(AssetId assetId) {
		return getRequest(assetPath(assetId) + "/detections", DetectionListResponse.class);
	}

	public LoomClientHttpRequest<NoResponse> deleteAssetDetection(AssetId assetId, UUID detectionUuid) {
		return deleteRequest(assetPath(assetId) + "/detections/" + detectionUuid);
	}

	public LoomClientHttpRequest<DetectionBulkResponse> bulkCreateAssetDetections(AssetId assetId, DetectionBulkCreateRequest request) {
		return postRequest(assetPath(assetId) + "/detections/bulk", request, DetectionBulkResponse.class);
	}

	// COMMENT REACTION

	public LoomClientHttpRequest<ReactionResponse> loadCommentReaction(UUID commentUuid, UUID reactionUuid) {
		return getRequest("comments/" + commentUuid + "/reactions/" + reactionUuid, ReactionResponse.class);
	}

	public LoomClientHttpRequest<ReactionResponse> createCommentReaction(UUID commentUuid, ReactionCreateRequest request) {
		return postRequest("comments/" + commentUuid + "/reactions", request, ReactionResponse.class);
	}

	public LoomClientHttpRequest<ReactionResponse> updateCommentReaction(UUID commentUuid, UUID reactionUuid, ReactionUpdateRequest request) {
		return postRequest("comments/" + commentUuid + "/reactions/" + reactionUuid, request, ReactionResponse.class);
	}

	public LoomClientHttpRequest<ReactionListResponse> listCommentReaction(UUID commentUuid) {
		return getRequest("comments/" + commentUuid + "/reactions", ReactionListResponse.class);
	}

	public LoomClientHttpRequest<NoResponse> deleteCommentReaction(UUID commentUuid, UUID reactionUuid) {
		return deleteRequest("comments/" + commentUuid + "/reactions/" + reactionUuid);
	}

	// EMBEDDING

	@Override
	public LoomClientHttpRequest<EmbeddingResponse> loadEmbedding(UUID embeddingUuid) {
		return getRequest("embeddings/" + embeddingUuid, EmbeddingResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> deleteEmbedding(UUID embeddingUuid) {
		return deleteRequest("embeddings/" + embeddingUuid);
	}

	@Override
	public LoomClientHttpRequest<EmbeddingListResponse> listEmbeddings() {
		return getRequest("embeddings", EmbeddingListResponse.class);
	}

	@Override
	public LoomClientHttpRequest<EmbeddingResponse> updateEmbedding(UUID embeddingUuid, EmbeddingUpdateRequest request) {
		return postRequest("embeddings/" + embeddingUuid, request, EmbeddingResponse.class);
	}

	@Override
	public LoomClientHttpRequest<EmbeddingResponse> createEmbedding(EmbeddingCreateRequest request) {
		return postRequest("embeddings", request, EmbeddingResponse.class);
	}

	// ASSET COMPONENT

	@Override
	public LoomClientHttpRequest<AssetComponentListResponse> listAssetComponents(UUID assetUuid) {
		return getRequest("assets/" + assetUuid + "/components", AssetComponentListResponse.class);
	}

	@Override
	public LoomClientHttpRequest<AssetComponentResponse> createAssetComponent(UUID assetUuid, AssetComponentCreateRequest request) {
		return postRequest("assets/" + assetUuid + "/components", request, AssetComponentResponse.class);
	}

	@Override
	public LoomClientHttpRequest<AssetComponentResponse> loadAssetComponent(UUID assetUuid, UUID compUuid) {
		return getRequest("assets/" + assetUuid + "/components/" + compUuid, AssetComponentResponse.class);
	}

	@Override
	public LoomClientHttpRequest<AssetComponentResponse> updateAssetComponent(UUID assetUuid, UUID compUuid, AssetComponentUpdateRequest request) {
		return postRequest("assets/" + assetUuid + "/components/" + compUuid, request, AssetComponentResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> deleteAssetComponent(UUID assetUuid, UUID compUuid) {
		return deleteRequest("assets/" + assetUuid + "/components/" + compUuid);
	}

	// ASSET TRANSCRIPT

	@Override
	public LoomClientHttpRequest<TranscriptResponse> createAssetTranscript(UUID assetUuid, TranscriptCreateRequest request) {
		return postRequest("assets/" + assetUuid + "/transcripts", request, TranscriptResponse.class);
	}

	@Override
	public LoomClientHttpRequest<TranscriptResponse> loadAssetTranscript(UUID assetUuid, UUID transcriptUuid) {
		return getRequest("assets/" + assetUuid + "/transcripts/" + transcriptUuid, TranscriptResponse.class);
	}

	@Override
	public LoomClientHttpRequest<TranscriptResponse> updateAssetTranscript(UUID assetUuid, UUID transcriptUuid, TranscriptUpdateRequest request) {
		return postRequest("assets/" + assetUuid + "/transcripts/" + transcriptUuid, request, TranscriptResponse.class);
	}

	@Override
	public LoomClientHttpRequest<TranscriptListResponse> listAssetTranscripts(UUID assetUuid) {
		return getRequest("assets/" + assetUuid + "/transcripts", TranscriptListResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> deleteAssetTranscript(UUID assetUuid, UUID transcriptUuid) {
		return deleteRequest("assets/" + assetUuid + "/transcripts/" + transcriptUuid);
	}

	// ASSET NODE RESULT (processing ledger)

	@Override
	public LoomClientHttpRequest<NodeResultResponse> createAssetNodeResult(UUID assetUuid, NodeResultCreateRequest request) {
		return postRequest("assets/" + assetUuid + "/node-results", request, NodeResultResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NodeResultResponse> loadAssetNodeResult(UUID assetUuid, UUID nodeResultUuid) {
		return getRequest("assets/" + assetUuid + "/node-results/" + nodeResultUuid, NodeResultResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NodeResultListResponse> listAssetNodeResults(UUID assetUuid) {
		return getRequest("assets/" + assetUuid + "/node-results", NodeResultListResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> deleteAssetNodeResult(UUID assetUuid, UUID nodeResultUuid) {
		return deleteRequest("assets/" + assetUuid + "/node-results/" + nodeResultUuid);
	}

	// ASSET JSON COMPONENT (generic node result sink)

	@Override
	public LoomClientHttpRequest<JsonCompResponse> createAssetJsonComp(UUID assetUuid, JsonCompCreateRequest request) {
		return postRequest("assets/" + assetUuid + "/json-comps", request, JsonCompResponse.class);
	}

	@Override
	public LoomClientHttpRequest<JsonCompResponse> loadAssetJsonComp(UUID assetUuid, UUID compUuid) {
		return getRequest("assets/" + assetUuid + "/json-comps/" + compUuid, JsonCompResponse.class);
	}

	@Override
	public LoomClientHttpRequest<JsonCompListResponse> listAssetJsonComps(UUID assetUuid) {
		return getRequest("assets/" + assetUuid + "/json-comps", JsonCompListResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> deleteAssetJsonComp(UUID assetUuid, UUID compUuid) {
		return deleteRequest("assets/" + assetUuid + "/json-comps/" + compUuid);
	}

	// ASSET FINGERPRINT COMPONENT

	@Override
	public LoomClientHttpRequest<FingerprintCompResponse> createAssetFingerprintComp(UUID assetUuid, FingerprintCompCreateRequest request) {
		return postRequest("assets/" + assetUuid + "/fingerprints", request, FingerprintCompResponse.class);
	}

	@Override
	public LoomClientHttpRequest<FingerprintCompResponse> loadAssetFingerprintComp(UUID assetUuid, UUID compUuid) {
		return getRequest("assets/" + assetUuid + "/fingerprints/" + compUuid, FingerprintCompResponse.class);
	}

	@Override
	public LoomClientHttpRequest<FingerprintCompListResponse> listAssetFingerprintComps(UUID assetUuid) {
		return getRequest("assets/" + assetUuid + "/fingerprints", FingerprintCompListResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> deleteAssetFingerprintComp(UUID assetUuid, UUID compUuid) {
		return deleteRequest("assets/" + assetUuid + "/fingerprints/" + compUuid);
	}

	// ASSET SEGMENT COMPONENT (scenes, silence, shots, chapters)

	@Override
	public LoomClientHttpRequest<SegmentCompListResponse> createAssetSegmentComps(UUID assetUuid, SegmentCompCreateRequest request) {
		return postRequest("assets/" + assetUuid + "/segments", request, SegmentCompListResponse.class);
	}

	@Override
	public LoomClientHttpRequest<SegmentCompResponse> loadAssetSegmentComp(UUID assetUuid, UUID compUuid) {
		return getRequest("assets/" + assetUuid + "/segments/" + compUuid, SegmentCompResponse.class);
	}

	@Override
	public LoomClientHttpRequest<SegmentCompListResponse> listAssetSegmentComps(UUID assetUuid) {
		return getRequest("assets/" + assetUuid + "/segments", SegmentCompListResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> deleteAssetSegmentComp(UUID assetUuid, UUID compUuid) {
		return deleteRequest("assets/" + assetUuid + "/segments/" + compUuid);
	}

	// GROUP

	@Override
	public LoomClientHttpRequest<GroupResponse> loadGroup(UUID groupUuid) {
		return getRequest("groups/" + groupUuid, GroupResponse.class);
	}

	@Override
	public LoomClientHttpRequest<GroupListResponse> listGroups() {
		return getRequest("groups", GroupListResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> deleteGroup(UUID groupUuid) {
		return deleteRequest("groups/" + groupUuid);
	}

	@Override
	public LoomClientHttpRequest<GroupResponse> patchGroup(UUID uuid, GroupUpdateRequest request) {
		return patchRequest("groups/" + uuid, request, GroupResponse.class);
	}

	@Override
	public LoomClientHttpRequest<GroupResponse> replaceGroup(UUID uuid, GroupUpdateRequest request) {
		return putRequest("groups/" + uuid, request, GroupResponse.class);
	}

	@Override
	public LoomClientHttpRequest<GroupResponse> updateGroup(UUID uuid, GroupUpdateRequest request) {
		return postRequest("groups/" + uuid, request, GroupResponse.class);
	}

	@Override
	public LoomClientHttpRequest<GroupResponse> createGroup(GroupCreateRequest request) {
		return postRequest("groups", request, GroupResponse.class);
	}

	// COMMENT

	@Override
	public LoomClientHttpRequest<CommentListResponse> listComments() {
		return getRequest("comments", CommentListResponse.class);
	}

	@Override
	public LoomClientHttpRequest<CommentListResponse> listCommentsForAnnotation(UUID annotationUuid) {
		return getRequest("annotation/" + annotationUuid + "/comments", CommentListResponse.class);
	}

	@Override
	public LoomClientHttpRequest<CommentResponse> loadComment(UUID uuid) {
		return getRequest("comments/" + uuid, CommentResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> deleteComment(UUID uuid) {
		return deleteRequest("comments/" + uuid);
	}

	@Override
	public LoomClientHttpRequest<CommentResponse> updateComment(UUID uuid, CommentUpdateRequest request) {
		return postRequest("comments/" + uuid, request, CommentResponse.class);
	}

	@Override
	public LoomClientHttpRequest<CommentResponse> createComment(CommentCreateRequest request) {
		return postRequest("comments", request, CommentResponse.class);
	}

	@Override
	public LoomClientHttpRequest<CommentResponse> createTaskComment(UUID taskUuid, CommentCreateRequest request) {
		return postRequest("tasks/" + taskUuid + "/comments", request, CommentResponse.class);
	}

	@Override
	public LoomClientHttpRequest<CommentListResponse> listTaskComments(UUID taskUuid) {
		return getRequest("tasks/" + taskUuid + "/comments", CommentListResponse.class);
	}

	// BLACKLIST

	@Override
	public LoomClientHttpRequest<BlacklistListResponse> listBlacklists() {
		return getRequest("blacklists", BlacklistListResponse.class);
	}

	@Override
	public LoomClientHttpRequest<BlacklistResponse> loadBlacklist(UUID uuid) {
		return getRequest("blacklists/" + uuid, BlacklistResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> deleteBlacklist(UUID uuid) {
		return deleteRequest("blacklists/" + uuid);
	}

	@Override
	public LoomClientHttpRequest<BlacklistResponse> updateBlacklist(UUID uuid, BlacklistUpdateRequest request) {
		return postRequest("blacklists/" + uuid, request, BlacklistResponse.class);
	}

	@Override
	public LoomClientHttpRequest<BlacklistResponse> createBlacklist(BlacklistCreateRequest request) {
		return postRequest("blacklists", request, BlacklistResponse.class);
	}

	// CHAT

	@Override
	public LoomClientHttpRequest<ChatListResponse> listChats() {
		return getRequest("chats", ChatListResponse.class);
	}

	@Override
	public LoomClientHttpRequest<ChatResponse> loadChat(UUID uuid) {
		return getRequest("chats/" + uuid, ChatResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> deleteChat(UUID uuid) {
		return deleteRequest("chats/" + uuid);
	}

	@Override
	public LoomClientHttpRequest<ChatResponse> updateChat(UUID uuid, ChatUpdateRequest request) {
		return postRequest("chats/" + uuid, request, ChatResponse.class);
	}

	@Override
	public LoomClientHttpRequest<ChatResponse> createChat(ChatCreateRequest request) {
		return postRequest("chats", request, ChatResponse.class);
	}

	// SKILL

	@Override
	public LoomClientHttpRequest<SkillListResponse> listSkills() {
		return getRequest("skills", SkillListResponse.class);
	}

	@Override
	public LoomClientHttpRequest<SkillResponse> loadSkill(UUID uuid) {
		return getRequest("skills/" + uuid, SkillResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> deleteSkill(UUID uuid) {
		return deleteRequest("skills/" + uuid);
	}

	@Override
	public LoomClientHttpRequest<SkillResponse> updateSkill(UUID uuid, SkillUpdateRequest request) {
		return postRequest("skills/" + uuid, request, SkillResponse.class);
	}

	@Override
	public LoomClientHttpRequest<SkillResponse> createSkill(SkillCreateRequest request) {
		return postRequest("skills", request, SkillResponse.class);
	}

	@Override
	public LoomClientHttpRequest<SkillListResponse> listSkillLibrary() {
		return getRequest("skills/library", SkillListResponse.class);
	}

	@Override
	public LoomClientHttpRequest<SkillResponse> installSkill(UUID uuid) {
		return postRequest("skills/" + uuid + "/install", SkillResponse.class);
	}

	@Override
	public LoomClientHttpRequest<SkillVersionListResponse> listSkillVersions(UUID uuid) {
		return getRequest("skills/" + uuid + "/versions", SkillVersionListResponse.class);
	}

	@Override
	public LoomClientHttpRequest<SkillResponse> loadSkillVersion(UUID uuid, int versionNumber) {
		return getRequest("skills/" + uuid + "/versions/" + versionNumber, SkillResponse.class);
	}

	@Override
	public LoomClientHttpRequest<SkillResponse> restoreSkillVersion(UUID uuid, int versionNumber) {
		return postRequest("skills/" + uuid + "/versions/" + versionNumber + "/restore", SkillResponse.class);
	}

	// ROLE

	@Override
	public LoomClientHttpRequest<RoleResponse> loadRole(UUID uuid) {
		return getRequest("roles/" + uuid, RoleResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> deleteRole(UUID uuid) {
		return deleteRequest("roles/" + uuid);
	}

	@Override
	public LoomClientHttpRequest<RoleResponse> updateRole(UUID uuid, RoleUpdateRequest request) {
		return postRequest("roles/" + uuid, request, RoleResponse.class);
	}

	@Override
	public LoomClientHttpRequest<RoleResponse> createRole(RoleCreateRequest request) {
		return postRequest("roles", request, RoleResponse.class);
	}

	@Override
	public LoomClientHttpRequest<RoleListResponse> listRoles() {
		return getRequest("roles", RoleListResponse.class);
	}

	// TASK

	@Override
	public LoomClientHttpRequest<TaskResponse> loadTask(UUID uuid) {
		return getRequest("tasks/" + uuid, TaskResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> deleteTask(UUID uuid) {
		return deleteRequest("tasks/" + uuid);
	}

	@Override
	public LoomClientHttpRequest<TaskListResponse> listTasks() {
		return getRequest("tasks", TaskListResponse.class);
	}

	@Override
	public LoomClientHttpRequest<TaskResponse> updateTask(UUID uuid, TaskUpdateRequest request) {
		return postRequest("tasks/" + uuid, request, TaskResponse.class);
	}

	@Override
	public LoomClientHttpRequest<TaskResponse> createTask(TaskCreateRequest request) {
		return postRequest("tasks", request, TaskResponse.class);
	}

	// TASK - ASSET

	@Override
	public LoomClientHttpRequest<TaskListResponse> listAssetTasks(AssetId assetId) {
		return getRequest(assetPath(assetId) + "/tasks", TaskListResponse.class);
	}

	@Override
	public LoomClientHttpRequest<TaskResponse> assignTaskToAsset(AssetId assetId, UUID taskUuid) {
		return postRequest(assetPath(assetId) + "/tasks/" + taskUuid, TaskResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> unassignTaskFromAsset(AssetId assetId, UUID taskUuid) {
		return deleteRequest(assetPath(assetId) + "/tasks/" + taskUuid);
	}

	// TASK - ANNOTATION

	@Override
	public LoomClientHttpRequest<TaskListResponse> listAnnotationTasks(UUID annotationUuid) {
		return getRequest("annotations/" + annotationUuid + "/tasks", TaskListResponse.class);
	}

	@Override
	public LoomClientHttpRequest<TaskResponse> assignTaskToAnnotation(UUID annotationUuid, UUID taskUuid) {
		return postRequest("annotations/" + annotationUuid + "/tasks/" + taskUuid, TaskResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> unassignTaskFromAnnotation(UUID annotationUuid, UUID taskUuid) {
		return deleteRequest("annotations/" + annotationUuid + "/tasks/" + taskUuid);
	}

	// ATTACHMENT

	@Override
	public LoomClientHttpRequest<AttachmentResponse> uploadAttachment(String filename, String mimeType, InputStream fileData) {
		Objects.requireNonNull(filename, "filename must not be null");
		Objects.requireNonNull(fileData, "fileData must not be null");
		Objects.requireNonNull(mimeType, "mimeType must not be null");

		if (mimeType.isEmpty()) {
			throw new IllegalArgumentException("The mimeType of the attachment cannot be empty.");
		}

		// TODO handle escaping of filename
		String boundary = "--------Geg2Oob";
		StringBuilder multiPartFormData = new StringBuilder();

		// multiPartFormData.append("--").append(boundary).append("\r\n");
		// multiPartFormData.append("Content-Disposition: form-data; name=\"language\"\r\n");
		// multiPartFormData.append("\r\n");
		// multiPartFormData.append(languageTag).append("\r\n");

		multiPartFormData.append("--").append(boundary).append("\r\n");
		multiPartFormData.append("Content-Disposition: form-data; name=\"" + "shohY6d" + "\"; filename=\"").append(filename).append("\"\r\n");
		multiPartFormData.append("Content-Type: ").append(mimeType).append("\r\n");
		multiPartFormData.append("Content-Transfer-Encoding: binary\r\n" + "\r\n");

		InputStream prefix;
		InputStream suffix;
		try {
			prefix = new ByteArrayInputStream(multiPartFormData.toString().getBytes("utf-8"));
			suffix = new ByteArrayInputStream(("\r\n--" + boundary + "--").getBytes("utf-8"));
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}

		Vector<InputStream> streams = new Vector<>(Arrays.asList(
			prefix,
			fileData,
			suffix));
		SequenceInputStream completeStream = new SequenceInputStream(streams.elements());
		String bodyContentType = "multipart/form-data;boundary=" + boundary;

		return postUploadRequest("attachments", AttachmentResponse.class, completeStream, bodyContentType);
	}

	@Override
	public LoomClientHttpRequest<AttachmentListResponse> listAttachments() {
		return getRequest("/attachments", AttachmentListResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> deleteAttachment(UUID uuid) {
		return deleteRequest("/attachments/" + uuid);
	}

	@Override
	public LoomClientHttpRequest<AttachmentResponse> loadAttachment(UUID uuid) {
		return getRequest("/attachments/" + uuid, AttachmentResponse.class);
	}

	@Override
	public LoomClientHttpRequest<LoomBinaryResponse> downloadAttachment(UUID uuid) {
		return getDownloadRequest("/attachments/" + uuid);
	}

	@Override
	public LoomClientHttpRequest<AttachmentResponse> updateAttachment(UUID uuid, AttachmentUpdateRequest request) {
		return postRequest("/attachments/" + uuid, request, AttachmentResponse.class);
	}

	// TAG

	@Override
	public LoomClientHttpRequest<TagResponse> loadTag(UUID uuid) {
		return getRequest("/tags/" + uuid, TagResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> deleteTag(UUID uuid) {
		return deleteRequest("/tags/" + uuid);
	}

	@Override
	public LoomClientHttpRequest<TagListResponse> listTags() {
		return getRequest("/tags", TagListResponse.class);
	}

	@Override
	public LoomClientHttpRequest<TagResponse> updateTag(UUID uuid, TagUpdateRequest request) {
		return postRequest("/tags/" + uuid, request, TagResponse.class);
	}

	@Override
	public LoomClientHttpRequest<TagResponse> createTag(TagCreateRequest request) {
		return postRequest("/tags", request, TagResponse.class);
	}

	@Override
	public LoomClientHttpRequest<TagRatingResponse> rateTag(UUID uuid, TagRatingRequest request) {
		return postRequest("/tags/" + uuid + "/rating", request, TagRatingResponse.class);
	}

	@Override
	public LoomClientHttpRequest<TagRatingResponse> loadTagRating(UUID uuid) {
		return getRequest("/tags/" + uuid + "/rating", TagRatingResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> deleteTagRating(UUID uuid) {
		return deleteRequest("/tags/" + uuid + "/rating");
	}

	// TOKEN

	@Override
	public LoomClientHttpRequest<TokenResponse> loadToken(UUID uuid) {
		return getRequest("/tokens/" + uuid, TokenResponse.class);
	}

	@Override
	public LoomClientHttpRequest<TokenListResponse> listTokens() {
		return getRequest("/tokens", TokenListResponse.class);
	}

	@Override
	public LoomClientHttpRequest<TokenResponse> updateToken(UUID uuid, TokenUpdateRequest request) {
		return postRequest("/tokens/" + uuid, request, TokenResponse.class);
	}

	@Override
	public LoomClientHttpRequest<TokenResponse> createToken(TokenCreateRequest request) {
		return postRequest("/tokens", request, TokenResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> deleteToken(UUID uuid) {
		return deleteRequest("/tokens/" + uuid);
	}

	// POOL

	@Override
	public LoomClientHttpRequest<AssetPoolResponse> loadPool(UUID poolUuid) {
		return getRequest("pools/" + poolUuid, AssetPoolResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> deletePool(UUID poolUuid) {
		return deleteRequest("pools/" + poolUuid);
	}

	@Override
	public LoomClientHttpRequest<AssetPoolResponse> createPool(AssetPoolCreateRequest request) {
		return postRequest("pools", request, AssetPoolResponse.class);
	}

	@Override
	public LoomClientHttpRequest<AssetPoolResponse> updatePool(UUID poolUuid, AssetPoolUpdateRequest request) {
		return postRequest("pools/" + poolUuid, request, AssetPoolResponse.class);
	}

	@Override
	public LoomClientHttpRequest<AssetPoolListResponse> listPools() {
		return getRequest("pools", AssetPoolListResponse.class);
	}

	// BINARY

	@Override
	public LoomClientHttpRequest<AssetBinaryResponse> loadBinary(UUID binaryUuid) {
		return getRequest("binaries/" + binaryUuid, AssetBinaryResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> deleteBinary(UUID binaryUuid) {
		return deleteRequest("binaries/" + binaryUuid);
	}

	@Override
	public LoomClientHttpRequest<AssetBinaryResponse> createBinary(AssetBinaryCreateRequest request) {
		return postRequest("binaries", request, AssetBinaryResponse.class);
	}

	@Override
	public LoomClientHttpRequest<AssetBinaryResponse> updateBinary(UUID binaryUuid, AssetBinaryUpdateRequest request) {
		return postRequest("binaries/" + binaryUuid, request, AssetBinaryResponse.class);
	}

	@Override
	public LoomClientHttpRequest<AssetBinaryListResponse> listBinaries() {
		return getRequest("binaries", AssetBinaryListResponse.class);
	}

	@Override
	public LoomClientHttpRequest<AssetBinaryResponse> loadAssetBinary(UUID assetUuid) {
		return getRequest("assets/" + assetUuid + "/binary", AssetBinaryResponse.class);
	}

	@Override
	public LoomClientHttpRequest<AssetBinaryResponse> createAssetBinary(UUID assetUuid, AssetBinaryCreateRequest request) {
		return postRequest("assets/" + assetUuid + "/binary", request, AssetBinaryResponse.class);
	}

	@Override
	public LoomClientHttpRequest<NoResponse> deleteAssetBinary(UUID assetUuid) {
		return deleteRequest("assets/" + assetUuid + "/binary");
	}

	// GRAPHQL

	@Override
	public LoomClientHttpRequest<GraphQLResponse> executeGraphQL(GraphQLRequest request) {
		return postRequest("graphql", request, GraphQLResponse.class);
	}

	@Override
	public LoomClientHttpRequest<HealthCheckResponse> health() {
		return getRequest("health", HealthCheckResponse.class);
	}

}
