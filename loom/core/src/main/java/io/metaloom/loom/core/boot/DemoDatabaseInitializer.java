package io.metaloom.loom.core.boot;

import java.time.Instant;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetDao;
import io.metaloom.loom.db.model.collection.Collection;
import io.metaloom.loom.db.model.collection.CollectionDao;
import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineDao;
import io.metaloom.loom.db.model.project.Project;
import io.metaloom.loom.db.model.project.ProjectDao;
import io.metaloom.loom.db.model.tag.AssetTag;
import io.metaloom.loom.db.model.tag.TagDao;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.model.user.UserDao;
import io.metaloom.utils.UUIDUtils;
import io.metaloom.utils.hash.SHA512;

/**
 * Populates the database with demo content so that the demo container starts with useful sample data (assets, projects, tags, collections, pipelines).
 */
@Singleton
public class DemoDatabaseInitializer {

	private static final Logger log = LoggerFactory.getLogger(DemoDatabaseInitializer.class);

	private static final String DEMO_PROJECT_NAME = "Demo Project";
	private static final String DEMO_COLLECTION_IMAGES = "Demo Images";
	private static final String DEMO_COLLECTION_VIDEOS = "Demo Videos";
	private static final String DEMO_PIPELINE_NAME = "Default Pipeline";

	private final UserDao userDao;
	private final AssetDao assetDao;
	private final ProjectDao projectDao;
	private final TagDao tagDao;
	private final CollectionDao collectionDao;
	private final PipelineDao pipelineDao;

	@Inject
	public DemoDatabaseInitializer(UserDao userDao, AssetDao assetDao, ProjectDao projectDao,
		TagDao tagDao, CollectionDao collectionDao, PipelineDao pipelineDao) {
		this.userDao = userDao;
		this.assetDao = assetDao;
		this.projectDao = projectDao;
		this.tagDao = tagDao;
		this.collectionDao = collectionDao;
		this.pipelineDao = pipelineDao;
	}

	/**
	 * Populate the database with demo data. This method is idempotent — it only inserts demo data when the database is empty of assets.
	 */
	public void init() {
		// Only populate when no assets exist yet
		if (!assetDao.loadPage(null, 1, null, null, null).isEmpty()) {
			log.info("Demo data already present — skipping initialization.");
			return;
		}

		User admin = userDao.loadAdmin();
		if (admin == null) {
			log.warn("Admin user not found — skipping demo data initialization.");
			return;
		}
		UUID adminUuid = admin.getUuid();
		log.info("Populating demo data…");

		// --- Project ---
		Project project = projectDao.createProject(adminUuid, DEMO_PROJECT_NAME);
		project.setUuid(UUIDUtils.randomUUID());
		project.setCreator(admin);
		project.setEditor(admin);
		project.setCreated(Instant.now());
		project.setEdited(Instant.now());
		projectDao.store(project);
		log.info("Created demo project: {}", DEMO_PROJECT_NAME);

		// --- Tags ---
		AssetTag tagNature = createAssetTag(admin, "nature", "category");
		AssetTag tagCity = createAssetTag(admin, "city", "category");
		AssetTag tagPortrait = createAssetTag(admin, "portrait", "category");
		AssetTag tagLandscape = createAssetTag(admin, "landscape", "category");
		AssetTag tagVideo = createAssetTag(admin, "video", "type");
		AssetTag tagImage = createAssetTag(admin, "image", "type");
		AssetTag tagAudio = createAssetTag(admin, "audio", "type");
		AssetTag tagDocument = createAssetTag(admin, "document", "type");

		// --- Collections ---
		Collection imagesCollection = createCollection(admin, DEMO_COLLECTION_IMAGES);
		Collection videosCollection = createCollection(admin, DEMO_COLLECTION_VIDEOS);

		// --- Pipeline ---
		Pipeline pipeline = pipelineDao.createPipeline(adminUuid, DEMO_PIPELINE_NAME);
		pipeline.setUuid(UUIDUtils.randomUUID());
		pipeline.setCreator(admin);
		pipeline.setEditor(admin);
		pipeline.setCreated(Instant.now());
		pipeline.setEdited(Instant.now());
		pipeline.setDescription("Default processing pipeline for demo assets");
		pipeline.setEnabled(true);
		pipeline.setPriority(1);
		pipelineDao.store(pipeline);
		log.info("Created demo pipeline: {}", DEMO_PIPELINE_NAME);

		// --- Assets ---
		Asset[] imageAssets = {
			createAsset(admin, "sunset-beach.jpg", "image/jpeg", 2_540_000, "/demo/photos/sunset-beach.jpg"),
			createAsset(admin, "mountain-lake.jpg", "image/jpeg", 3_120_000, "/demo/photos/mountain-lake.jpg"),
			createAsset(admin, "city-skyline.png", "image/png", 5_830_000, "/demo/photos/city-skyline.png"),
			createAsset(admin, "portrait-studio.jpg", "image/jpeg", 1_980_000, "/demo/photos/portrait-studio.jpg"),
			createAsset(admin, "forest-trail.jpg", "image/jpeg", 4_200_000, "/demo/photos/forest-trail.jpg"),
			createAsset(admin, "autumn-leaves.jpg", "image/jpeg", 2_100_000, "/demo/photos/autumn-leaves.jpg"),
			createAsset(admin, "snow-peaks.jpg", "image/jpeg", 3_800_000, "/demo/photos/snow-peaks.jpg"),
		};

		Asset[] videoAssets = {
			createAsset(admin, "drone-coastal.mp4", "video/mp4", 52_000_000, "/demo/videos/drone-coastal.mp4"),
			createAsset(admin, "timelapse-city.mp4", "video/mp4", 38_000_000, "/demo/videos/timelapse-city.mp4"),
			createAsset(admin, "interview-clip.mov", "video/quicktime", 120_000_000, "/demo/videos/interview-clip.mov"),
		};

		Asset[] audioAssets = {
			createAsset(admin, "ambient-rain.mp3", "audio/mpeg", 8_500_000, "/demo/audio/ambient-rain.mp3"),
			createAsset(admin, "podcast-episode1.mp3", "audio/mpeg", 45_000_000, "/demo/audio/podcast-episode1.mp3"),
		};

		Asset[] docAssets = {
			createAsset(admin, "project-brief.pdf", "application/pdf", 1_200_000, "/demo/docs/project-brief.pdf"),
			createAsset(admin, "meeting-notes.pdf", "application/pdf", 340_000, "/demo/docs/meeting-notes.pdf"),
		};

		// Tag images
		for (Asset a : imageAssets) {
			tagDao.tagAsset(tagImage, a);
		}
		tagDao.tagAsset(tagNature, imageAssets[0]);
		tagDao.tagAsset(tagNature, imageAssets[1]);
		tagDao.tagAsset(tagLandscape, imageAssets[1]);
		tagDao.tagAsset(tagCity, imageAssets[2]);
		tagDao.tagAsset(tagPortrait, imageAssets[3]);
		tagDao.tagAsset(tagNature, imageAssets[4]);
		tagDao.tagAsset(tagNature, imageAssets[5]);
		tagDao.tagAsset(tagLandscape, imageAssets[6]);

		// Tag videos
		for (Asset a : videoAssets) {
			tagDao.tagAsset(tagVideo, a);
		}
		tagDao.tagAsset(tagNature, videoAssets[0]);
		tagDao.tagAsset(tagCity, videoAssets[1]);
		tagDao.tagAsset(tagPortrait, videoAssets[2]);

		// Tag audio
		for (Asset a : audioAssets) {
			tagDao.tagAsset(tagAudio, a);
		}

		// Tag documents
		for (Asset a : docAssets) {
			tagDao.tagAsset(tagDocument, a);
		}

		// Add to collections
		for (Asset a : imageAssets) {
			collectionDao.link(imagesCollection, a);
		}
		for (Asset a : videoAssets) {
			collectionDao.link(videosCollection, a);
		}

		log.info("Demo data initialization complete — created {} assets, {} tags, {} collections, {} pipeline.",
			imageAssets.length + videoAssets.length + audioAssets.length + docAssets.length,
			8, 2, 1);
	}

	private AssetTag createAssetTag(User admin, String name, String collection) {
		AssetTag tag = tagDao.createAssetTag(admin, name, collection);
		tag.setUuid(UUIDUtils.randomUUID());
		tag.setCreator(admin);
		tag.setEditor(admin);
		tag.setCreated(Instant.now());
		tag.setEdited(Instant.now());
		tagDao.store(tag);
		log.info("Created demo tag: {} ({})", name, collection);
		return tag;
	}

	private Collection createCollection(User admin, String name) {
		Collection col = collectionDao.createCollection(admin, name);
		col.setUuid(UUIDUtils.randomUUID());
		col.setCreator(admin);
		col.setEditor(admin);
		col.setCreated(Instant.now());
		col.setEdited(Instant.now());
		collectionDao.store(col);
		log.info("Created demo collection: {}", name);
		return col;
	}

	private Asset createAsset(User admin, String filename, String mimeType, long size, String origin) {
		String hashHex = String.format("%0128x", new java.math.BigInteger(1, filename.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
			.substring(0, 128);
		// Pad to 128 hex chars
		while (hashHex.length() < 128) {
			hashHex = hashHex + "0";
		}
		SHA512 sha512 = SHA512.fromString(hashHex);
		Asset asset = assetDao.createAsset(admin, sha512, mimeType, filename, origin, size);
		asset.setUuid(UUIDUtils.randomUUID());
		asset.setCreator(admin);
		asset.setEditor(admin);
		asset.setCreated(Instant.now());
		asset.setEdited(Instant.now());
		asset.setFirstSeen(Instant.now());
		assetDao.store(asset);
		log.info("Created demo asset: {}", filename);
		return asset;
	}
}
