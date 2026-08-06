package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_EMBEDDING;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_EMBEDDING;
import static io.metaloom.loom.db.model.perm.Permission.READ_EMBEDDING;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_EMBEDDING;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.embedding.Embedding;
import io.metaloom.loom.db.model.embedding.EmbeddingDao;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.embedding.EmbeddingBulkCreateRequest;
import io.metaloom.loom.rest.model.embedding.EmbeddingBulkResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingCreateRequest;
import io.metaloom.loom.rest.model.embedding.EmbeddingUpdateRequest;
import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.vector.EmbeddingIndexSyncService;

@Singleton
public class EmbeddingEndpointService extends AbstractCRUDEndpointService<EmbeddingDao, Embedding> {

	private static final Logger log = LoggerFactory.getLogger(EmbeddingEndpointService.class);

	private final EmbeddingIndexSyncService vectorSync;

	@Inject
	public EmbeddingEndpointService(EmbeddingDao embeddingDao, DaoCollection daos, EmbeddingIndexSyncService vectorSync, LoomModelBuilder modelBuilder,
		LoomModelValidator validator) {
		super(embeddingDao, daos, modelBuilder, validator);
		this.vectorSync = vectorSync;
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID uuid) {
		delete(lrc, DELETE_EMBEDDING, uuid);
		// Best-effort, and after the fact: the row is gone from Postgres either way, and a rebuild would
		// not remove a vector whose source row no longer exists. Leaving it would let the index answer
		// with an embedding that has been deleted.
		vectorSync.remove(uuid);
	}

	@Override
	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_EMBEDDING, modelBuilder::toEmbeddingList);
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID uuid) {
		load(lrc, READ_EMBEDDING, () -> {
			return dao().load(uuid);
		}, modelBuilder::toResponse);
	}

	@Override
	public void create(LoomRoutingContext lrc) {
		create(lrc, CREATE_EMBEDDING, () -> {
			EmbeddingCreateRequest request = lrc.requestBody(EmbeddingCreateRequest.class);
			validator.validate(request);

			Embedding embedding = toEmbedding(lrc.userUuid(), request.getAssetUuid(), request);
			indexAfterWrite(embedding);
			return embedding;
		}, modelBuilder::toResponse);
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID uuid) {
		create(lrc, UPDATE_EMBEDDING, () -> {
			EmbeddingUpdateRequest request = lrc.requestBody(EmbeddingUpdateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();

			Embedding embedding = dao().load(uuid);
			update(request::getAssetUuid, embedding::setAssetUuid);
			update(request::getVector, embedding::setVector);
			update(request::getType, embedding::setType);
			update(request::getSource, embedding::setNodeKind);
			setEditor(embedding, userUuid);
			indexAfterWrite(embedding);
			return embedding;
		}, modelBuilder::toResponse);
	}

	public void createAttachment(UUID pathParamUUID) {
		// TODO Auto-generated method stub

	}

	public void listEmbeddingAttachments(UUID embeddingUuid) {

	}

	public void updateAttachment(UUID attachmentUuid) {

	}

	public void loadAttachment(UUID attachmentUuid) {

	}

	public void createEmbeddingAttachment(UUID pathParamUUID) {
		// TODO Auto-generated method stub
		
	}

	/**
	 * Create many embeddings for one asset in a single call.
	 *
	 * <p>
	 * Each item is upserted on its natural key, so a node that runs again rewrites its own rows rather than appending duplicates - the same contract
	 * {@code bulkCreateAssetDetections} offers, and the reason the two can be called in sequence without a cleanup step in between. A single bad item
	 * is counted as failed and the rest still land; the response reports both numbers so a partial success is never mistaken for a clean run.
	 * </p>
	 */
	public void bulkCreateAssetEmbeddings(LoomRoutingContext lrc, AssetId assetId) {
		checkPerm(lrc, CREATE_EMBEDDING, () -> {
			UUID assetUuid = resolveAssetUuid(assetId);
			EmbeddingBulkCreateRequest request = lrc.requestBody(EmbeddingBulkCreateRequest.class);
			List<EmbeddingCreateRequest> items = request.getEmbeddings();
			UUID userUuid = lrc.userUuid();

			EmbeddingBulkResponse response = new EmbeddingBulkResponse();
			response.setTotal(items.size());
			int failed = 0;

			List<Embedding> embeddings = new ArrayList<>();
			int index = 0;
			for (EmbeddingCreateRequest itemRequest : items) {
				try {
					validator.validate(itemRequest);
					Embedding embedding = toEmbedding(userUuid, assetUuid, itemRequest);
					// When the caller does not number its subjects, take the ordinal from batch position so each
					// row has a distinct natural key instead of the whole batch collapsing onto subject_index 0.
					if (itemRequest.getSubjectIndex() == null) {
						embedding.setSubjectIndex(index);
					}
					embeddings.add(embedding);
				} catch (Exception e) {
					log.warn("Bulk create embedding item failed: {}", e.getMessage());
					failed++;
				}
				index++;
			}

			int created = 0;
			for (Embedding embedding : embeddings) {
				dao().upsertEmbedding(embedding);
				response.add(modelBuilder.toResponse(embedding));
				created++;
			}
			// One index write for the whole batch, after every row is committed. Failure here is logged and
			// the rows stay dirty, so the periodic drain retries them - the bulk write itself still succeeds.
			vectorSync.index(embeddings);

			response.setCreated(created);
			response.setFailed(failed);
			lrc.send(response, 201);
		});
	}

	/**
	 * Push a freshly written embedding into the vector index.
	 *
	 * <p>
	 * Deferred until after the element is stored, because the index is keyed on the embedding uuid and that is assigned by the insert. Best-effort: the
	 * row stays {@code dirty} if this fails, and the drain picks it up.
	 * </p>
	 */
	private void indexAfterWrite(Embedding embedding) {
		if (embedding.getUuid() != null) {
			vectorSync.index(List.of(embedding));
		}
	}

	private UUID resolveAssetUuid(AssetId assetId) {
		if (assetId.isUUID()) {
			return assetId.uuid();
		} else {
			return daos().assetDao().loadBySHA512(assetId.hashsum()).getUuid();
		}
	}

	/**
	 * Build an embedding element from a create request, honouring every provenance field the request carries.
	 */
	private Embedding toEmbedding(UUID userUuid, UUID assetUuid, EmbeddingCreateRequest request) {
		Embedding embedding = dao().createEmbedding(userUuid, assetUuid, request.getVector(), request.getType());
		// "source" is the producing node kind and is part of the embedding identity
		// (asset, node kind, type, model, frame, subject) - honour it on create, not only on update.
		update(request::getSource, embedding::setNodeKind);
		update(request::getNodeKind, embedding::setNodeKind);
		update(request::getModel, embedding::setModel);
		update(request::getDimensions, embedding::setDimensions);
		update(request::getDetectionUuid, embedding::setDetectionUuid);
		update(request::getConfidence, embedding::setConfidence);
		update(request::getProducerVersion, embedding::setProducerVersion);
		update(request::getNormalized, embedding::setNormalized);
		if (request.getFrameNumber() != null) {
			embedding.setFrameNumber(request.getFrameNumber());
		}
		if (request.getSubjectIndex() != null) {
			embedding.setSubjectIndex(request.getSubjectIndex());
		}
		return embedding;
	}
}
