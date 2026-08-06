package io.metaloom.loom.db.model.embedding;

import java.time.LocalDateTime;
import java.util.UUID;

import io.metaloom.loom.db.CUDElement;

/**
 * An embedding vector extracted from an asset.
 *
 * <p>
 * Identity: <code>(asset_uuid, node_kind, type, frame_number, subject_index)</code>. The geometry of the region the vector was computed from lives on
 * the linked detection rather than being duplicated here.
 * </p>
 */
public interface Embedding extends CUDElement<Embedding> {

	UUID getAssetUuid();

	Embedding setAssetUuid(UUID assetUuid);

	Float[] getVector();

	Embedding setVector(Float[] vectorData);

	/**
	 * Return the kind of node that produced this embedding (e.g. "facedetect", "manual").
	 */
	String getNodeKind();

	Embedding setNodeKind(String nodeKind);

	/**
	 * Return the model or algorithm version of the producer. Never null - an unknown version is the empty string.
	 */
	String getProducerVersion();

	Embedding setProducerVersion(String producerVersion);

	/**
	 * Return the readable model identifier, e.g. inspireface-r18.
	 */
	String getModel();

	Embedding setModel(String model);

	/**
	 * Return the length of the vector. Guards against comparing vectors produced by different models.
	 */
	Integer getDimensions();

	Embedding setDimensions(Integer dimensions);

	/**
	 * Return the producer's confidence in this vector, or null when it reports none.
	 */
	Float getConfidence();

	Embedding setConfidence(Float confidence);

	/**
	 * Return the detection this vector was computed from, or null for whole-image and audio-window embeddings.
	 */
	UUID getDetectionUuid();

	Embedding setDetectionUuid(UUID detectionUuid);

	/**
	 * Return the frame this embedding belongs to; 0 for images.
	 */
	int getFrameNumber();

	Embedding setFrameNumber(int frameNumber);

	/**
	 * Return the ordinal of the subject within the frame, used when there is no detection to key on.
	 */
	int getSubjectIndex();

	Embedding setSubjectIndex(int subjectIndex);

	/**
	 * Return the free-text embedding type, e.g. "face" or "clip". Deliberately not an enum: the embedding model is expected to change, and a new model
	 * must not require a code change and a redeploy to be storable.
	 */
	String getType();

	Embedding setType(String type);

	/**
	 * Return whether this row still has to be written to the vector index. New rows start dirty; {@code EmbeddingSyncService} clears it once the row has
	 * been drained, which is what makes the export incremental and self-healing.
	 */
	Boolean getDirty();

	Embedding setDirty(Boolean dirty);

	/**
	 * Return when this row was last drained into the vector index.
	 */
	LocalDateTime getSyncedAt();

	Embedding setSyncedAt(LocalDateTime syncedAt);

	/**
	 * Return the index layout version this row was written under, so a stale index can be recognised rather than silently queried.
	 */
	Integer getIndexVersion();

	Embedding setIndexVersion(Integer indexVersion);

	/**
	 * Return whether the vector was unit-normalized at write time. With normalized vectors cosine and inner product rank identically.
	 */
	Boolean getNormalized();

	Embedding setNormalized(Boolean normalized);

}
