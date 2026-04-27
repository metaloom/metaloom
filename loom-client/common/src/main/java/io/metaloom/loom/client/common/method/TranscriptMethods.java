package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.transcript.TranscriptCreateRequest;
import io.metaloom.loom.rest.model.transcript.TranscriptListResponse;
import io.metaloom.loom.rest.model.transcript.TranscriptResponse;
import io.metaloom.loom.rest.model.transcript.TranscriptUpdateRequest;

public interface TranscriptMethods {

	LoomClientRequest<TranscriptResponse> createAssetTranscript(UUID assetUuid, TranscriptCreateRequest request);

	LoomClientRequest<TranscriptResponse> loadAssetTranscript(UUID assetUuid, UUID transcriptUuid);

	LoomClientRequest<TranscriptResponse> updateAssetTranscript(UUID assetUuid, UUID transcriptUuid, TranscriptUpdateRequest request);

	LoomClientRequest<TranscriptListResponse> listAssetTranscripts(UUID assetUuid);

	LoomClientRequest<NoResponse> deleteAssetTranscript(UUID assetUuid, UUID transcriptUuid);

}
