package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.READ_ASSET;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.api.options.SimilarityOptions;
import io.metaloom.loom.api.search.SimilarityHit;
import io.metaloom.loom.api.search.SimilarityIndex;
import io.metaloom.loom.db.model.asset.AssetComponentDao;
import io.metaloom.loom.db.model.asset.AssetFingerprintComp;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.similarity.SimilarAssetListResponse;
import io.metaloom.loom.rest.model.similarity.SimilarAssetResponse;
import io.metaloom.loom.rest.service.AbstractEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

/**
 * Serves the perceptual fingerprint similarity routes (spec/loom/SEARCH_LUCENE.md §5).
 *
 * <p>
 * <b>No silent degradation.</b> When the index is disabled or unavailable the routes answer 503 naming the reason rather than returning an empty list:
 * "no duplicates found" and "the duplicate index is switched off" must never look the same to a caller - a dedup node would happily conclude that a
 * corpus is duplicate-free.
 * </p>
 *
 * <p>
 * Not a CRUD resource, so this extends {@link AbstractEndpointService}: there is no element type and the hits are relevance-ranked, which the keyset
 * paging of the CRUD list routes cannot express.
 * </p>
 */
@Singleton
public class SimilarityEndpointService extends AbstractEndpointService {

	private static final Logger log = LoggerFactory.getLogger(SimilarityEndpointService.class);

	private final SimilarityIndex index;
	private final SimilarityOptions options;
	private final AssetComponentDao compDao;

	@Inject
	public SimilarityEndpointService(SimilarityIndex index, SimilarityOptions options, AssetComponentDao compDao, LoomModelBuilder modelBuilder,
		LoomModelValidator validator) {
		super(modelBuilder, validator);
		this.index = index;
		this.options = options;
		this.compDao = compDao;
	}

	/**
	 * {@code GET /api/v1/assets/:uuid/similar-assets} - near-duplicates of one asset.
	 *
	 * <p>
	 * Looks up the asset's stored fingerprint, queries the k-NN index and excludes the asset itself. An asset without a fingerprint yields an empty list
	 * (200), not a 404: "nothing similar" and "not fingerprinted yet" are both legitimate empty answers for an existing asset.
	 * </p>
	 */
	public void listSimilarAssets(LoomRoutingContext lrc, UUID assetUuid) {
		checkPerm(lrc, READ_ASSET, () -> {
			requireAvailable();

			String algorithm = param(lrc, "algorithm", options.getAlgorithm());
			int limit = intParam(lrc, "limit", options.getTopK());
			float threshold = floatParam(lrc, "threshold", options.getScoreThreshold());

			String fingerprint = loadFingerprint(assetUuid, algorithm);
			SimilarAssetListResponse response = new SimilarAssetListResponse();
			if (fingerprint == null) {
				lrc.send(response);
				return;
			}

			// One extra neighbour is requested because the query asset matches itself and is then dropped.
			List<SimilarityHit> hits = index.query(algorithm, fingerprint, limit + 1, threshold);
			int emitted = 0;
			for (SimilarityHit hit : hits) {
				if (assetUuid.equals(hit.assetUuid())) {
					continue;
				}
				if (emitted++ >= limit) {
					break;
				}
				response.add(new SimilarAssetResponse()
					.setAssetUuid(hit.assetUuid().toString())
					.setSha512(hit.sha512())
					.setScore(hit.score()));
			}
			lrc.send(response);
		});
	}

	/**
	 * {@code POST /api/v1/similarity-index/rebuild} - drop and rebuild the index from {@code asset_fingerprint_comp}.
	 *
	 * <p>
	 * The index is a derived cache, so this is always safe: it is the recovery path whenever a write hook was missed (a crash between the comp commit
	 * and the index write).
	 * </p>
	 */
	public void rebuild(LoomRoutingContext lrc) {
		// Rebuilding walks the whole fingerprint table and replaces the index; gate it on the strongest asset permission available.
		checkPerm(lrc, Permission.UPDATE_ASSET, () -> {
			requireAvailable();
			String algorithm = param(lrc, "algorithm", options.getAlgorithm());
			// The projection carries the owning asset's sha512sum, so a rebuilt index answers with the content hash rather than a null.
			// rebuildFromHex consumes and closes the stream.
			AtomicLong rebuilt = new AtomicLong();
			index.rebuildFromHex(compDao.streamHexFingerprintsByAlgorithm(algorithm)
				.peek(fingerprint -> rebuilt.incrementAndGet()));
			log.info("Rebuilt the fingerprint similarity index from {} fingerprint component(s)", rebuilt.get());
			lrc.sendNoContent();
		});
	}

	private void requireAvailable() {
		if (!options.isEnabled() || !index.isAvailable()) {
			throw new LoomRestException(503, LoomRestErrorCode.SEARCH_UNAVAILABLE,
				"The fingerprint similarity index is unavailable"
					+ (options.isEnabled() ? " (the index could not be opened)." : " (LOOM_SIMILARITY_ENABLED=false)."));
		}
	}

	/**
	 * The whole-asset fingerprint (window 0) for the given algorithm, or null when the asset has none.
	 */
	private String loadFingerprint(UUID assetUuid, String algorithm) {
		for (AssetFingerprintComp comp : compDao.loadFingerprintComps(assetUuid)) {
			if (algorithm.equals(comp.getAlgorithm()) && comp.getWindowIndex() == 0 && comp.getFingerprint() != null) {
				return comp.getFingerprint();
			}
		}
		return null;
	}

	private static String param(LoomRoutingContext lrc, String key, String fallback) {
		List<String> values = lrc.queryParam(key);
		return values == null || values.isEmpty() || values.get(0).isBlank() ? fallback : values.get(0);
	}

	private static int intParam(LoomRoutingContext lrc, String key, int fallback) {
		String raw = param(lrc, key, null);
		if (raw == null) {
			return fallback;
		}
		try {
			int value = Integer.parseInt(raw);
			if (value <= 0) {
				throw new NumberFormatException(raw);
			}
			return value;
		} catch (NumberFormatException e) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_QUERY_PARAMS, "The '" + key + "' parameter must be a positive integer.");
		}
	}

	private static float floatParam(LoomRoutingContext lrc, String key, float fallback) {
		String raw = param(lrc, key, null);
		if (raw == null) {
			return fallback;
		}
		try {
			return Float.parseFloat(raw);
		} catch (NumberFormatException e) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_QUERY_PARAMS, "The '" + key + "' parameter must be a number.");
		}
	}
}
