package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_ASSET;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_ASSET;
import static io.metaloom.loom.db.model.perm.Permission.READ_ASSET;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_ASSET;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetAudioComp;
import io.metaloom.loom.db.model.asset.AssetComponent;
import io.metaloom.loom.db.model.asset.AssetComponentDao;
import io.metaloom.loom.db.model.asset.AssetDao;
import io.metaloom.loom.db.model.asset.AssetDocComp;
import io.metaloom.loom.db.model.asset.AssetGeoComp;
import io.metaloom.loom.db.model.asset.AssetImageComp;
import io.metaloom.loom.db.model.asset.AssetVideoComp;
import io.metaloom.loom.api.embedding.EmbeddingType;
import io.metaloom.loom.db.model.embedding.Embedding;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.asset.AssetBulkCreateRequest;
import io.metaloom.loom.rest.model.asset.AssetBulkItemResponse;
import io.metaloom.loom.rest.model.asset.AssetBulkItemResponse.BulkItemStatus;
import io.metaloom.loom.rest.model.asset.AssetBulkResponse;
import io.metaloom.loom.rest.model.asset.AssetBulkUpdateEntry;
import io.metaloom.loom.rest.model.asset.AssetBulkUpdateRequest;
import io.metaloom.loom.rest.model.asset.AssetCreateRequest;
import io.metaloom.loom.rest.model.asset.AssetModel;
import io.metaloom.loom.rest.model.asset.AssetUpdateRequest;
import io.metaloom.loom.rest.model.asset.info.AudioInfo;
import io.metaloom.loom.rest.model.asset.info.ConsistencyInfo;
import io.metaloom.loom.rest.model.asset.info.DocumentInfo;
import io.metaloom.loom.rest.model.asset.info.FileInfo;
import io.metaloom.loom.rest.model.asset.info.GeoLocationInfo;
import io.metaloom.loom.rest.model.asset.info.HashInfo;
import io.metaloom.loom.rest.model.asset.info.ImageInfo;
import io.metaloom.loom.rest.model.asset.info.MediaInfo;
import io.metaloom.loom.rest.model.asset.info.VideoInfo;
import io.metaloom.loom.rest.model.asset.location.AssetS3Meta;
import io.metaloom.loom.rest.model.embedding.EmbeddingCreateRequest;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.vector.EmbeddingIndexSyncService;
import io.metaloom.utils.hash.SHA512;

@Singleton
public class AssetEndpointService extends AbstractCRUDEndpointService<AssetDao, Asset> {

	private static final Logger log = LoggerFactory.getLogger(AssetEndpointService.class);

	private final EmbeddingIndexSyncService vectorSync;

	@Inject
	public AssetEndpointService(AssetDao assetDao, DaoCollection daos, EmbeddingIndexSyncService vectorSync, LoomModelBuilder modelBuilder,
		LoomModelValidator validator) {
		super(assetDao, daos, modelBuilder, validator);
		this.vectorSync = vectorSync;
	}

	public void delete(LoomRoutingContext lrc, AssetId assetId) {
		if (assetId.isUUID()) {
			delete(lrc, assetId.uuid());
		} else {
			// Resolved to a uuid first so this path cleans the vector index too. Deleting by hash and
			// deleting by uuid must leave the same state behind; routing one of them around the cleanup
			// would make an asset's face vectors survive depending only on how it was addressed.
			Asset asset = dao().loadBySHA512(assetId.hashsum());
			if (asset == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Element not found.");
			}
			delete(lrc, asset.getUuid());
		}
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID uuid) {
		delete(lrc, DELETE_ASSET, uuid);
		// Every embedding of this asset cascaded away in SQL, so a rebuild would not clear them from the
		// vector index either - the source rows it would read no longer exist. Told explicitly, or the
		// index keeps returning face matches pointing at an asset that is gone.
		vectorSync.removeAsset(uuid);
	}

	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_ASSET, modelBuilder::toAssetList);
	}

	public void load(LoomRoutingContext lrc, AssetId assetId) {
		if (assetId.isUUID()) {
			load(lrc, assetId.uuid());
		} else {
			load(lrc, () -> {
				return dao().loadBySHA512(assetId.hashsum());
			});
		}
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID uuid) {
		load(lrc, () -> {
			return dao().load(uuid);
		});
	}

	private void load(LoomRoutingContext lrc, Supplier<Asset> loader) {
		load(lrc, READ_ASSET, () -> {
			return loader.get();
		}, modelBuilder::toResponse);
	}

	public void update(LoomRoutingContext lrc, AssetId assetId) {
		if (assetId.isUUID()) {
			update(lrc, assetId.uuid());
		} else {
			update(lrc, () -> {
				return dao().loadBySHA512(assetId.hashsum());
			});
		}
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID uuid) {
		update(lrc, () -> {
			return dao().load(uuid);
		});
	}

	protected void update(LoomRoutingContext lrc, Supplier<Asset> loader) {
		update(lrc, UPDATE_ASSET, () -> {
			AssetUpdateRequest request = lrc.requestBody(AssetUpdateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			Asset asset = loader.get();

			AssetS3Meta s3Info = request.getS3();
			if (s3Info != null) {
				update(s3Info::getBucket, asset::setS3BucketName);
				update(s3Info::getObjectPath, asset::setS3ObjectPath);
			}

			updateAssetFields(request, asset);

			setEditor(asset, userUuid);
			return asset;
		}, modelBuilder::toResponse);
	}

	public void create(LoomRoutingContext lrc) {
		create(lrc, CREATE_ASSET, () -> {
			AssetCreateRequest request = lrc.requestBody(AssetCreateRequest.class);
			validator.validate(request);
			return createAsset(lrc.userUuid(), request);
		}, modelBuilder::toResponse);
	}

	/**
	 * Create, persist and fully materialize an asset (embeddings + component records) from a create request. Shared by the JSON create endpoint and
	 * the multipart upload endpoint so both go through identical persistence logic. The caller is responsible for permission checks, validation and
	 * for publishing any {@code asset.created} event.
	 *
	 * @param userUuid
	 *            the creator
	 * @param request
	 *            the create request. Mandatory fields ({@code hashes.sha512} and {@code file.{mimeType,filename,origin,size}}) must be present.
	 * @return the stored asset (carrying its generated UUID)
	 */
	public Asset createAsset(UUID userUuid, AssetCreateRequest request) {
		HashInfo hashes = request.getHashes();

		// Mandatory fields
		SHA512 sha512sum = hashes.getSHA512();
		String mimeType = request.getFile().getMimeType();
		String filename = request.getFile().getFilename();
		String origin = request.getFile().getOrigin();
		Long size = request.getFile().getSize();

		Asset asset = dao().createAsset(userUuid, sha512sum, mimeType, filename, origin, size);
		updateAssetFields(request, asset);

		// Store asset first so it gets a UUID (needed for FK constraints on child entities)
		dao().store(asset);

		// Create initial embedding for asset
		for (EmbeddingCreateRequest embeddingRequest : request.getEmbeddings()) {
			daos().embeddingDao().store(toEmbedding(userUuid, asset.getUuid(), embeddingRequest));
		}

		// Create component records
		createComponents(userUuid, asset.getUuid(), request);

		return asset;
	}

	private void createComponents(UUID userUuid, UUID assetUuid, AssetCreateRequest model) {
		AssetComponentDao compDao = daos().assetComponentDao();

		ImageInfo imageInfo = model.getImage();
		if (imageInfo != null) {
			AssetImageComp comp = compDao.createImageComp(userUuid, assetUuid, AssetComponent.NODE_KIND_MANUAL);
			comp.setImageDominantColor(imageInfo.getDominantColor());
			MediaInfo mediaInfo = model.getMedia();
			if (mediaInfo != null) {
				comp.setMediaWidth(mediaInfo.getWidth());
				comp.setMediaHeight(mediaInfo.getHeight());
			}
			compDao.storeImageComp(comp);
		}

		VideoInfo videoInfo = model.getVideo();
		if (videoInfo != null) {
			AssetVideoComp comp = compDao.createVideoComp(userUuid, assetUuid, AssetComponent.NODE_KIND_MANUAL);
			comp.setVideoEncoding(videoInfo.getEncoding());
			comp.setVideoBitrate(videoInfo.getBitrate());
			MediaInfo mediaInfo = model.getMedia();
			if (mediaInfo != null) {
				comp.setMediaWidth(mediaInfo.getWidth());
				comp.setMediaHeight(mediaInfo.getHeight());
				comp.setMediaDuration(mediaInfo.getDuration());
			}
			compDao.storeVideoComp(comp);
		}

		AudioInfo audioInfo = model.getAudio();
		if (audioInfo != null) {
			AssetAudioComp comp = compDao.createAudioComp(userUuid, assetUuid, AssetComponent.NODE_KIND_MANUAL);
			comp.setAudioEncoding(audioInfo.getEncoding());
			comp.setAudioSamplingRate(audioInfo.getSamplingRate());
			comp.setAudioBpm(audioInfo.getBpm());
			comp.setAudioChannels(audioInfo.getChannels());
			comp.setAudioBitrate(audioInfo.getBitrate());
			MediaInfo mediaInfo = model.getMedia();
			if (mediaInfo != null) {
				comp.setMediaDuration(mediaInfo.getDuration());
			}
			compDao.storeAudioComp(comp);
		}

		GeoLocationInfo geoInfo = model.getGeo();
		if (geoInfo != null) {
			AssetGeoComp comp = compDao.createGeoComp(userUuid, assetUuid, AssetComponent.NODE_KIND_MANUAL);
			comp.setGeoLon(geoInfo.getLon());
			comp.setGeoLat(geoInfo.getLat());
			comp.setGeoAlias(geoInfo.getAlias());
			compDao.storeGeoComp(comp);
		}

		DocumentInfo docInfo = model.getDocument();
		if (docInfo != null) {
			AssetDocComp comp = compDao.createDocComp(userUuid, assetUuid, AssetComponent.NODE_KIND_MANUAL);
			comp.setDocWordCount(docInfo.getWordCount() != null ? docInfo.getWordCount().intValue() : null);
			compDao.storeDocComp(comp);
		}
	}

	private void updateAssetFields(AssetModel<?> model, Asset asset) {
		update(model::getMeta, asset::setMeta);

		FileInfo fileInfo = model.getFile();
		if (fileInfo != null) {
			update(fileInfo::getFirstSeen, asset::setFirstSeen);
			update(fileInfo::getFilename, asset::setFilename);
			update(fileInfo::getOrigin, asset::setInitialOrigin);
			update(fileInfo::getMimeType, asset::setMimeType);
			update(fileInfo::getSize, asset::setSize);
		}

		HashInfo hashes = model.getHashes();
		if (hashes != null) {
			update(hashes::getMD5, asset::setMD5);
			update(hashes::getSHA256, asset::setSHA256);
			update(hashes::getSHA512, asset::setSHA512);
			update(hashes::getChunkHash, asset::setChunkHash);
		}

		ConsistencyInfo consistency = model.getConsistency();
		if (consistency != null) {
			update(consistency::getZeroChunkCount, asset::setZeroChunkCount);
		}
	}

	/**
	 * Default batch size for processing bulk requests. The request is chunked into batches of this size
	 * to bound memory and reduce DB round-trips while avoiding loading the entire payload at once.
	 */
	private static final int BULK_BATCH_SIZE = 50;

	/**
	 * Bulk create assets. Processes items in batches of {@link #BULK_BATCH_SIZE} to apply simple
	 * backpressure and reduce peak memory usage on the server.
	 */
	public void bulkCreate(LoomRoutingContext lrc) {
		checkPerm(lrc, CREATE_ASSET, () -> {
			AssetBulkCreateRequest request = lrc.requestBody(AssetBulkCreateRequest.class);
			List<AssetCreateRequest> items = request.getAssets();
			UUID userUuid = lrc.userUuid();

			AssetBulkResponse response = new AssetBulkResponse();
			response.setTotal(items.size());
			int created = 0;
			int failed = 0;

			// Process in batches for backpressure
			for (int batchStart = 0; batchStart < items.size(); batchStart += BULK_BATCH_SIZE) {
				int batchEnd = Math.min(batchStart + BULK_BATCH_SIZE, items.size());
				List<AssetCreateRequest> batch = items.subList(batchStart, batchEnd);

				// Prepare assets for this batch
				List<Asset> batchAssets = new ArrayList<>();
				List<Integer> batchIndices = new ArrayList<>();

				for (int i = 0; i < batch.size(); i++) {
					int globalIndex = batchStart + i;
					AssetCreateRequest itemRequest = batch.get(i);
					try {
						validator.validate(itemRequest);

						HashInfo hashes = itemRequest.getHashes();
						SHA512 sha512sum = hashes.getSHA512();
						String mimeType = itemRequest.getFile().getMimeType();
						String filename = itemRequest.getFile().getFilename();
						String origin = itemRequest.getFile().getOrigin();
						Long size = itemRequest.getFile().getSize();

						Asset asset = dao().createAsset(userUuid, sha512sum, mimeType, filename, origin, size);
						updateAssetFields(itemRequest, asset);
						batchAssets.add(asset);
						batchIndices.add(globalIndex);
					} catch (Exception e) {
						log.warn("Bulk create item {} failed validation: {}", globalIndex, e.getMessage());
						AssetBulkItemResponse itemResponse = new AssetBulkItemResponse();
						itemResponse.setIndex(globalIndex);
						itemResponse.setStatus(BulkItemStatus.FAILED);
						itemResponse.setError(e.getMessage());
						if (itemRequest.getHashes() != null && itemRequest.getHashes().getSHA512() != null) {
							itemResponse.setSha512(itemRequest.getHashes().getSHA512().toString());
						}
						response.add(itemResponse);
						failed++;
					}
				}

				// Batch store
				try {
					dao().storeBatch(batchAssets);
				} catch (Exception e) {
					// If batch fails, fall back to individual inserts
					log.warn("Batch store failed, falling back to individual inserts: {}", e.getMessage());
					for (int i = 0; i < batchAssets.size(); i++) {
						try {
							dao().store(batchAssets.get(i));
						} catch (Exception e2) {
							log.warn("Individual store failed for batch index {}: {}", batchIndices.get(i), e2.getMessage());
							batchAssets.set(i, null); // Mark as failed
						}
					}
				}

				// Create components and build response items
				for (int i = 0; i < batchAssets.size(); i++) {
					int globalIndex = batchIndices.get(i);
					Asset asset = batchAssets.get(i);
					AssetCreateRequest itemRequest = items.get(globalIndex);

					if (asset == null) {
						AssetBulkItemResponse itemResponse = new AssetBulkItemResponse();
						itemResponse.setIndex(globalIndex);
						itemResponse.setStatus(BulkItemStatus.FAILED);
						itemResponse.setError("Failed to store asset");
						if (itemRequest.getHashes() != null && itemRequest.getHashes().getSHA512() != null) {
							itemResponse.setSha512(itemRequest.getHashes().getSHA512().toString());
						}
						response.add(itemResponse);
						failed++;
						continue;
					}

					try {
						// Create embeddings. These used to be built and then dropped on the floor - the DAO call
						// created the element and nothing ever stored it, so a bulk upload silently persisted none
						// of the embeddings it was handed, unlike the single-asset path directly above.
						for (EmbeddingCreateRequest embeddingRequest : itemRequest.getEmbeddings()) {
							daos().embeddingDao().store(toEmbedding(userUuid, asset.getUuid(), embeddingRequest));
						}

						// Create component records
						createComponents(userUuid, asset.getUuid(), itemRequest);

						AssetBulkItemResponse itemResponse = new AssetBulkItemResponse();
						itemResponse.setIndex(globalIndex);
						itemResponse.setUuid(asset.getUuid());
						itemResponse.setStatus(BulkItemStatus.CREATED);
						if (asset.getSHA512() != null) {
							itemResponse.setSha512(asset.getSHA512().toString());
						}
						response.add(itemResponse);
						created++;
					} catch (Exception e) {
						log.warn("Bulk create item {} component creation failed: {}", globalIndex, e.getMessage());
						AssetBulkItemResponse itemResponse = new AssetBulkItemResponse();
						itemResponse.setIndex(globalIndex);
						itemResponse.setUuid(asset.getUuid());
						itemResponse.setStatus(BulkItemStatus.FAILED);
						itemResponse.setError(e.getMessage());
						if (asset.getSHA512() != null) {
							itemResponse.setSha512(asset.getSHA512().toString());
						}
						response.add(itemResponse);
						failed++;
					}
				}

				// Allow GC to clean up batch data
				batchAssets.clear();
				batchIndices.clear();
			}

			response.setCreated(created);
			response.setFailed(failed);
			lrc.send(response, 200);
		});
	}

	/**
	 * Bulk update assets. Processes items in batches of {@link #BULK_BATCH_SIZE} to apply simple
	 * backpressure. Assets are identified by SHA-512 hash.
	 */
	public void bulkUpdate(LoomRoutingContext lrc) {
		checkPerm(lrc, UPDATE_ASSET, () -> {
			AssetBulkUpdateRequest request = lrc.requestBody(AssetBulkUpdateRequest.class);
			List<AssetBulkUpdateEntry> entries = request.getAssets();
			UUID userUuid = lrc.userUuid();

			AssetBulkResponse response = new AssetBulkResponse();
			response.setTotal(entries.size());
			int updated = 0;
			int failed = 0;

			// Process in batches for backpressure
			for (int batchStart = 0; batchStart < entries.size(); batchStart += BULK_BATCH_SIZE) {
				int batchEnd = Math.min(batchStart + BULK_BATCH_SIZE, entries.size());
				List<AssetBulkUpdateEntry> batch = entries.subList(batchStart, batchEnd);

				for (int i = 0; i < batch.size(); i++) {
					int globalIndex = batchStart + i;
					AssetBulkUpdateEntry entry = batch.get(i);

					try {
						HashInfo hashes = entry.getHashes();
						if (hashes == null || hashes.getSHA512() == null) {
							throw new IllegalArgumentException("SHA-512 hash is required to identify the asset");
						}
						SHA512 sha512 = hashes.getSHA512();

						Asset asset = dao().loadBySHA512(sha512);
						if (asset == null) {
							throw new IllegalArgumentException("Asset not found for SHA-512: " + sha512);
						}

						AssetUpdateRequest updateReq = entry.getUpdate();
						if (updateReq != null) {
							updateAssetFields(updateReq, asset);
						}

						// Also update hashes from the entry-level hashes (e.g. MD5, SHA256)
						if (hashes.getMD5() != null) {
							asset.setMD5(hashes.getMD5());
						}
						if (hashes.getSHA256() != null) {
							asset.setSHA256(hashes.getSHA256());
						}

						setEditor(asset, userUuid);
						dao().update(asset);

						AssetBulkItemResponse itemResponse = new AssetBulkItemResponse();
						itemResponse.setIndex(globalIndex);
						itemResponse.setUuid(asset.getUuid());
						itemResponse.setSha512(sha512.toString());
						itemResponse.setStatus(BulkItemStatus.UPDATED);
						response.add(itemResponse);
						updated++;
					} catch (Exception e) {
						log.warn("Bulk update item {} failed: {}", globalIndex, e.getMessage());
						AssetBulkItemResponse itemResponse = new AssetBulkItemResponse();
						itemResponse.setIndex(globalIndex);
						itemResponse.setStatus(BulkItemStatus.FAILED);
						itemResponse.setError(e.getMessage());
						if (entry.getHashes() != null && entry.getHashes().getSHA512() != null) {
							itemResponse.setSha512(entry.getHashes().getSHA512().toString());
						}
						response.add(itemResponse);
						failed++;
					}
				}
			}

			response.setCreated(updated);
			response.setFailed(failed);
			lrc.send(response, 200);
		});
	}

	/**
	 * Build an embedding from an inline asset-create request.
	 *
	 * <p>
	 * The type used to be hardcoded to {@code VIDEO4J_FINGERPRINT_V2} here, which meant the caller's own {@code type} was read off the wire, validated
	 * and then thrown away - every embedding attached to an asset was recorded as a video fingerprint whatever it actually was. It is honoured now, and
	 * the fingerprint type remains only as the default for callers that send none.
	 * </p>
	 */
	private Embedding toEmbedding(UUID userUuid, UUID assetUuid, EmbeddingCreateRequest request) {
		String type = request.getType() == null ? EmbeddingType.VIDEO4J_FINGERPRINT_V2.name() : request.getType();
		Embedding embedding = daos().embeddingDao().createEmbedding(userUuid, assetUuid, request.getVector(), type);
		if (request.getModel() != null) {
			embedding.setModel(request.getModel());
		}
		if (request.getSource() != null) {
			embedding.setNodeKind(request.getSource());
		}
		return embedding;
	}
}
