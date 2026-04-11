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
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.group.GroupDao;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.perm.PermissionDao;
import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineDao;
import io.metaloom.loom.db.model.pool.AssetPool;
import io.metaloom.loom.db.model.pool.AssetPoolDao;
import io.metaloom.loom.db.model.role.Role;
import io.metaloom.loom.db.model.role.RoleDao;
import io.metaloom.loom.db.model.space.Space;
import io.metaloom.loom.db.model.space.SpaceDao;
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

	private static final String DEMO_SPACE_NAME = "Demo Space";
	private static final String DEMO_COLLECTION_IMAGES = "Demo Images";
	private static final String DEMO_COLLECTION_VIDEOS = "Demo Videos";
	private static final String DEMO_PIPELINE_NAME = "Default Pipeline";
	private static final String DEMO_POOL_PRODUCTION = "Production Storage";
	private static final String DEMO_POOL_INGEST = "Ingest Hot Storage";
	private static final String DEMO_POOL_ARCHIVE = "Archive S3";

	private final UserDao userDao;
	private final AssetDao assetDao;
	private final SpaceDao spaceDao;
	private final TagDao tagDao;
	private final CollectionDao collectionDao;
	private final PipelineDao pipelineDao;
	private final AssetPoolDao assetPoolDao;
	private final GroupDao groupDao;
	private final RoleDao roleDao;
	private final PermissionDao permissionDao;

	@Inject
	public DemoDatabaseInitializer(UserDao userDao, AssetDao assetDao, SpaceDao spaceDao,
		TagDao tagDao, CollectionDao collectionDao, PipelineDao pipelineDao, AssetPoolDao assetPoolDao,
		GroupDao groupDao, RoleDao roleDao, PermissionDao permissionDao) {
		this.userDao = userDao;
		this.assetDao = assetDao;
		this.spaceDao = spaceDao;
		this.tagDao = tagDao;
		this.collectionDao = collectionDao;
		this.pipelineDao = pipelineDao;
		this.assetPoolDao = assetPoolDao;
		this.groupDao = groupDao;
		this.roleDao = roleDao;
		this.permissionDao = permissionDao;
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

		// --- Space ---
		Space space = spaceDao.createSpace(adminUuid, DEMO_SPACE_NAME);
		space.setUuid(UUIDUtils.randomUUID());
		space.setCreator(admin);
		space.setEditor(admin);
		space.setCreated(Instant.now());
		space.setEdited(Instant.now());
		spaceDao.store(space);
		log.info("Created demo space: {}", DEMO_SPACE_NAME);

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

		// --- Asset Pools ---
		createAssetPool(admin, DEMO_POOL_PRODUCTION, "/mnt/media/production", null, null, null);
		createAssetPool(admin, DEMO_POOL_INGEST, "/mnt/fast-ssd/ingest", null, null, null);
		createAssetPool(admin, DEMO_POOL_ARCHIVE, null, "metaloom-archive-prod", "eu-central-1", "https://s3.eu-central-1.amazonaws.com");

		// --- Users ---
		User editor = createDemoUser(admin, "editor", "editor1234", "editor@example.com", "Emily", "Editor");
		User viewer = createDemoUser(admin, "viewer", "viewer1234", "viewer@example.com", "Victor", "Viewer");

		// --- Roles ---
		Role editorRole = createDemoRole(admin, "Editor");
		Role viewerRole = createDemoRole(admin, "Viewer");

		// Grant editor permissions (full CRUD on assets, tags, collections, comments, annotations)
		for (Permission perm : new Permission[] {
			Permission.CREATE_ASSET, Permission.READ_ASSET, Permission.UPDATE_ASSET, Permission.DELETE_ASSET,
			Permission.CREATE_TAG, Permission.READ_TAG, Permission.UPDATE_TAG, Permission.DELETE_TAG,
			Permission.TAG_ASSET, Permission.UNTAG_ASSET,
			Permission.CREATE_COLLECTION, Permission.READ_COLLECTION, Permission.UPDATE_COLLECTION, Permission.DELETE_COLLECTION,
			Permission.CREATE_COMMENT, Permission.READ_COMMENT, Permission.UPDATE_COMMENT, Permission.DELETE_COMMENT,
			Permission.CREATE_ANNOTATION, Permission.READ_ANNOTATION, Permission.UPDATE_ANNOTATION, Permission.DELETE_ANNOTATION,
			Permission.READ_USER, Permission.READ_GROUP, Permission.READ_ROLE,
			Permission.READ_SPACE, Permission.READ_PIPELINE, Permission.READ_ASSET_POOL,
		}) {
			permissionDao.grantRolePermission(editorRole.getUuid(), perm);
		}
		log.info("Granted editor permissions to role: {}", editorRole.getName());

		// Grant viewer permissions (read-only)
		for (Permission perm : new Permission[] {
			Permission.READ_ASSET, Permission.READ_TAG, Permission.READ_COLLECTION,
			Permission.READ_COMMENT, Permission.READ_ANNOTATION,
			Permission.READ_USER, Permission.READ_GROUP, Permission.READ_ROLE,
			Permission.READ_SPACE, Permission.READ_PIPELINE, Permission.READ_ASSET_POOL,
		}) {
			permissionDao.grantRolePermission(viewerRole.getUuid(), perm);
		}
		log.info("Granted viewer permissions to role: {}", viewerRole.getName());

		// --- Groups ---
		Group editorsGroup = createDemoGroup(admin, "Editors");
		Group viewersGroup = createDemoGroup(admin, "Viewers");

		// Wire users to groups
		groupDao.addUserToGroup(editorsGroup, editor);
		groupDao.addUserToGroup(viewersGroup, viewer);
		log.info("Assigned users to groups");

		// Wire roles to groups
		groupDao.addRoleToGroup(editorsGroup, editorRole);
		groupDao.addRoleToGroup(viewersGroup, viewerRole);
		log.info("Assigned roles to groups");

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
			createAsset(admin, "space-brief.pdf", "application/pdf", 1_200_000, "/demo/docs/space-brief.pdf"),
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

		log.info("Demo data initialization complete — created {} assets, {} tags, {} collections, {} pipeline, {} users, {} groups, {} roles.",
			imageAssets.length + videoAssets.length + audioAssets.length + docAssets.length,
			8, 2, 1, 2, 2, 2);
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

	private AssetPool createAssetPool(User admin, String name, String fsPath, String s3Bucket, String s3Region, String s3Endpoint) {
		AssetPool pool = assetPoolDao.createAssetPool(admin.getUuid(), name);
		pool.setUuid(UUIDUtils.randomUUID());
		pool.setCreator(admin);
		pool.setEditor(admin);
		pool.setCreated(Instant.now());
		pool.setEdited(Instant.now());
		if (fsPath != null) {
			pool.setFsPath(fsPath);
		}
		if (s3Bucket != null) {
			pool.setS3Bucket(s3Bucket);
		}
		if (s3Region != null) {
			pool.setS3Region(s3Region);
		}
		if (s3Endpoint != null) {
			pool.setS3Endpoint(s3Endpoint);
		}
		assetPoolDao.store(pool);
		log.info("Created demo asset pool: {}", name);
		return pool;
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

	private User createDemoUser(User admin, String username, String password, String email, String firstname, String lastname) {
		User user = userDao.createUser(admin.getUuid(), username);
		user.setUuid(UUIDUtils.randomUUID());
		user.setCreator(admin);
		user.setEditor(admin);
		user.setCreated(Instant.now());
		user.setEdited(Instant.now());
		user.setEmail(email);
		user.setFirstname(firstname);
		user.setLastname(lastname);
		user.setEnabled(true);
		user.setPasswordHash(password);
		userDao.store(user);
		log.info("Created demo user: {}", username);
		return user;
	}

	private Group createDemoGroup(User admin, String name) {
		Group group = groupDao.createGroup(admin.getUuid(), name);
		group.setUuid(UUIDUtils.randomUUID());
		group.setCreator(admin);
		group.setEditor(admin);
		group.setCreated(Instant.now());
		group.setEdited(Instant.now());
		groupDao.store(group);
		log.info("Created demo group: {}", name);
		return group;
	}

	private Role createDemoRole(User admin, String name) {
		Role role = roleDao.createRole(admin.getUuid(), name);
		role.setUuid(UUIDUtils.randomUUID());
		role.setCreator(admin);
		role.setEditor(admin);
		role.setCreated(Instant.now());
		role.setEdited(Instant.now());
		roleDao.store(role);
		log.info("Created demo role: {}", name);
		return role;
	}
}
