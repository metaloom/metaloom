package io.metaloom.loom.cortex.dedup;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.dedup.DedupNodeOptions;
import io.metaloom.cortex.node.dedup.FingerprintDedupApplyNode;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.client.http.impl.LoomClientResponseImpl;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.asset.info.ConsistencyInfo;
import io.metaloom.loom.rest.model.asset.info.FileInfo;
import io.metaloom.loom.rest.model.asset.info.HashInfo;
import io.metaloom.loom.rest.model.dedup.DedupGroupListResponse;
import io.metaloom.loom.rest.model.dedup.DedupGroupMemberModel;
import io.metaloom.loom.rest.model.dedup.DedupGroupResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.metaloom.utils.hash.HashUtils;
import io.metaloom.utils.hash.SHA512;

/**
 * Unit tests for the fingerprint-dedup <b>apply</b> node - the half of the workflow that actually moves bytes.
 *
 * <p>
 * Two invariants dominate here and are each pinned by their own test: a file is moved <b>only</b> for a group a human confirmed, and only after the
 * file that would be kept has been re-verified against the live filesystem. Everything else - a missing, incomplete, smaller, already-trashed or
 * content-changed KEEP - must leave the duplicate exactly where it is.
 * </p>
 */
class FingerprintDedupApplyNodeTest {

	private static final String ALGO = "metaloom-multisector-v1";

	@TempDir
	File tempDir;

	private final UUID dupUuid = UUID.randomUUID();
	private final UUID keepUuid = UUID.randomUUID();

	private LoomHttpClient client;
	private CortexOptions cortexOptions;
	private StubLoomMedia media;
	private File dupFile;
	private File keepFile;
	private Path dupFolder;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setup() throws Exception {
		cortexOptions = new CortexOptions().setMetaPath(tempDir.toPath());
		client = mock(LoomHttpClient.class);

		// A ledger write always succeeds.
		LoomClientRequest<NodeResultResponse> ledger = mock(LoomClientRequest.class);
		when(ledger.sync()).thenReturn(new LoomClientResponseImpl<>(new NodeResultResponse().setUuid(UUID.randomUUID()), 201, "Created", Map.of()));
		when(client.createAssetNodeResult(any(), any())).thenReturn(ledger);

		dupFolder = new File(tempDir, "duplicates").toPath();

		dupFile = new File(tempDir, "dup.mp4");
		Files.write(dupFile.toPath(), "the smaller copy".getBytes());
		keepFile = new File(tempDir, "keep.mp4");
		Files.write(keepFile.toPath(), "the larger original copy of the very same footage".getBytes());

		media = new StubLoomMedia(dupFile.getAbsolutePath(), true, false, false, false);
		media.setSHA512(HashUtils.computeSHA512(dupFile));
	}

	private FingerprintDedupApplyNode node() {
		return new FingerprintDedupApplyNode(client, cortexOptions, new DedupNodeOptions().setDupFolder(dupFolder));
	}

	/** The asset record of the duplicate, as {@code fetchAsset} resolves it from the media handle's hash. */
	private AssetResponse dupAsset() {
		return new AssetResponse()
			.setUuid(dupUuid)
			.setFile(new FileInfo().setFilename(dupFile.getAbsolutePath()).setSize(dupFile.length()))
			.setConsistency(new ConsistencyInfo().setZeroChunkCount(0L));
	}

	/** The asset record of the keep. Passing an explicit hash lets a test simulate a file that changed after discovery. */
	private AssetResponse keepAsset(File file, Long zeroChunkCount, SHA512 recordedHash) {
		return new AssetResponse()
			.setUuid(keepUuid)
			.setFile(new FileInfo().setFilename(file.getAbsolutePath()).setSize(file.length()))
			.setConsistency(new ConsistencyInfo().setZeroChunkCount(zeroChunkCount))
			.setHashes(recordedHash == null ? null : new HashInfo().setSHA512(recordedHash));
	}

	private DedupGroupResponse group(String status, String roleOfThisAsset) {
		return new DedupGroupResponse()
			.setUuid(UUID.randomUUID().toString())
			.setAlgorithm(ALGO)
			.setStatus(status)
			.setKeepAssetUuid(keepUuid.toString())
			.setMembers(List.of(
				new DedupGroupMemberModel().setAssetUuid(keepUuid.toString()).setRole(DedupGroupMemberModel.ROLE_KEEP),
				new DedupGroupMemberModel().setAssetUuid(dupUuid.toString()).setRole(roleOfThisAsset)));
	}

	@SuppressWarnings("unchecked")
	private <T extends io.metaloom.loom.rest.model.RestResponseModel<T>> LoomClientRequest<T> request(T body) throws Exception {
		LoomClientRequest<T> req = mock(LoomClientRequest.class);
		when(req.sync()).thenReturn(new LoomClientResponseImpl<>(body, 200, "OK", Map.of()));
		return req;
	}

	/**
	 * Wire the client for one group and one keep record. Build all request mocks before stubbing - nesting a stubbed mock inside another
	 * {@code when()} confuses Mockito.
	 */
	@SuppressWarnings("unchecked")
	private void wire(DedupGroupResponse group, AssetResponse keep) throws Exception {
		LoomClientRequest<AssetResponse> dupReq = request(dupAsset());
		LoomClientRequest<AssetResponse> keepReq = request(keep);
		DedupGroupListResponse groups = new DedupGroupListResponse();
		groups.add(group);
		LoomClientRequest<DedupGroupListResponse> groupsReq = request(groups);

		when(client.loadAsset(nullable(SHA512.class))).thenReturn(dupReq);
		when(client.loadAsset(eq(keepUuid))).thenReturn(keepReq);
		when(client.listAssetDedupGroups(eq(dupUuid))).thenReturn(groupsReq);
	}

	private boolean dupWasMoved() {
		return !dupFile.exists() && new File(dupFolder.toFile(), dupFile.getName()).exists();
	}

	// --- the happy path ---------------------------------------------------------------------------

	@Test
	void testMovesTheDuplicateOfAConfirmedGroup() throws Exception {
		wire(group("CONFIRMED", DedupGroupMemberModel.ROLE_DUP), keepAsset(keepFile, 0L, HashUtils.computeSHA512(keepFile)));

		assertThat(node().process(NodeContext.create(media))).isSuccess();

		assertTrue(dupWasMoved(), "A confirmed duplicate must end up in the dups folder");
		assertTrue(keepFile.exists(), "The keep must never be touched");
	}

	// --- the propose/apply invariant --------------------------------------------------------------

	@Test
	void testPendingGroupsAreNeverApplied() throws Exception {
		wire(group("PENDING", DedupGroupMemberModel.ROLE_DUP), keepAsset(keepFile, 0L, HashUtils.computeSHA512(keepFile)));

		assertThat(node().process(NodeContext.create(media))).isSkipped();

		assertFalse(dupWasMoved(), "A proposal a human has not yet seen must never move a file");
	}

	@Test
	void testRejectedGroupsAreNeverApplied() throws Exception {
		wire(group("REJECTED", DedupGroupMemberModel.ROLE_DUP), keepAsset(keepFile, 0L, HashUtils.computeSHA512(keepFile)));

		assertThat(node().process(NodeContext.create(media))).isSkipped();

		assertFalse(dupWasMoved(), "A rejected decision must never move a file");
	}

	@Test
	void testTheKeepOfAConfirmedGroupIsNeverMoved() throws Exception {
		// This asset is the KEEP of the group, not a DUP - the node must not act on its own keeper.
		wire(group("CONFIRMED", DedupGroupMemberModel.ROLE_KEEP), keepAsset(keepFile, 0L, HashUtils.computeSHA512(keepFile)));

		assertThat(node().process(NodeContext.create(media))).isSkipped();

		assertFalse(dupWasMoved());
	}

	// --- the live safeguards ----------------------------------------------------------------------

	@Test
	void testAMissingKeepBlocksTheMove() throws Exception {
		File gone = new File(tempDir, "vanished.mp4");
		wire(group("CONFIRMED", DedupGroupMemberModel.ROLE_DUP),
			new AssetResponse().setUuid(keepUuid)
				.setFile(new FileInfo().setFilename(gone.getAbsolutePath()).setSize(9999L))
				.setConsistency(new ConsistencyInfo().setZeroChunkCount(0L)));

		assertThat(node().process(NodeContext.create(media))).isSkipped();

		assertFalse(dupWasMoved(), "Deleting the only remaining copy because the keep disappeared is the worst possible outcome");
	}

	@Test
	void testAnIncompleteKeepBlocksTheMove() throws Exception {
		wire(group("CONFIRMED", DedupGroupMemberModel.ROLE_DUP), keepAsset(keepFile, 7L, HashUtils.computeSHA512(keepFile)));

		assertThat(node().process(NodeContext.create(media))).isSkipped();

		assertFalse(dupWasMoved(), "Never discard the more complete file");
	}

	@Test
	void testAKeepSmallerThanTheDuplicateBlocksTheMove() throws Exception {
		File smaller = new File(tempDir, "smaller-keep.mp4");
		Files.write(smaller.toPath(), "tiny".getBytes());
		wire(group("CONFIRMED", DedupGroupMemberModel.ROLE_DUP), keepAsset(smaller, 0L, HashUtils.computeSHA512(smaller)));

		assertThat(node().process(NodeContext.create(media))).isSkipped();

		assertFalse(dupWasMoved(), "A keep smaller than the duplicate means the keep selection was wrong");
	}

	/**
	 * The safeguard the node was missing: existence, size and completeness all still hold for a file whose bytes were replaced after discovery, and
	 * that file is no longer the duplicate's counterpart.
	 */
	@Test
	void testAKeepWhoseContentChangedBlocksTheMove() throws Exception {
		SHA512 recordedAtDiscovery = HashUtils.computeSHA512(keepFile);
		// Same length, different bytes - only the hash can tell.
		Files.write(keepFile.toPath(), "the LARGER original copy of some other footage!!!!".getBytes());
		wire(group("CONFIRMED", DedupGroupMemberModel.ROLE_DUP), keepAsset(keepFile, 0L, recordedAtDiscovery));

		assertThat(node().process(NodeContext.create(media))).isSkipped();

		assertFalse(dupWasMoved(), "The keep is no longer the file the reviewer decided about");
	}

	@Test
	void testAKeepWithNoRecordedHashIsStillApplied() throws Exception {
		// Nothing to compare against is not the same as a mismatch; the other four safeguards still apply.
		wire(group("CONFIRMED", DedupGroupMemberModel.ROLE_DUP), keepAsset(keepFile, 0L, null));

		assertThat(node().process(NodeContext.create(media))).isSuccess();

		assertTrue(dupWasMoved());
	}

	// --- idempotency ------------------------------------------------------------------------------

	@Test
	void testADuplicateAlreadyInTheDupsFolderIsSkipped() throws Exception {
		File moved = new File(dupFolder.toFile(), "dup.mp4");
		Files.createDirectories(dupFolder);
		Files.write(moved.toPath(), "the smaller copy".getBytes());
		media = new StubLoomMedia(moved.getAbsolutePath(), true, false, false, false);
		media.setSHA512(HashUtils.computeSHA512(moved));

		LoomClientRequest<AssetResponse> dupReq = request(new AssetResponse()
			.setUuid(dupUuid)
			.setFile(new FileInfo().setFilename(moved.getAbsolutePath()).setSize(moved.length()))
			.setConsistency(new ConsistencyInfo().setZeroChunkCount(0L)));
		LoomClientRequest<AssetResponse> keepReq = request(keepAsset(keepFile, 0L, HashUtils.computeSHA512(keepFile)));
		DedupGroupListResponse groups = new DedupGroupListResponse();
		groups.add(group("CONFIRMED", DedupGroupMemberModel.ROLE_DUP));
		LoomClientRequest<DedupGroupListResponse> groupsReq = request(groups);
		when(client.loadAsset(nullable(SHA512.class))).thenReturn(dupReq);
		when(client.loadAsset(eq(keepUuid))).thenReturn(keepReq);
		when(client.listAssetDedupGroups(eq(dupUuid))).thenReturn(groupsReq);

		assertThat(node().process(NodeContext.create(media))).isSkipped();

		assertTrue(moved.exists(), "Re-running apply over an already-moved duplicate must be a no-op");
	}

	@Test
	void testNoGroupsAtAllIsSkipped() throws Exception {
		LoomClientRequest<AssetResponse> dupReq = request(dupAsset());
		LoomClientRequest<DedupGroupListResponse> groupsReq = request(new DedupGroupListResponse());
		when(client.loadAsset(nullable(SHA512.class))).thenReturn(dupReq);
		when(client.listAssetDedupGroups(eq(dupUuid))).thenReturn(groupsReq);

		assertThat(node().process(NodeContext.create(media))).isSkipped();

		assertFalse(dupWasMoved());
	}
}
