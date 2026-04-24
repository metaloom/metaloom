package io.metaloom.loom.core.boot;

import java.time.Instant;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetAudioComp;
import io.metaloom.loom.db.model.asset.AssetComponentDao;
import io.metaloom.loom.db.model.asset.AssetDao;
import io.metaloom.loom.db.model.asset.AssetDocComp;
import io.metaloom.loom.db.model.asset.AssetGeoComp;
import io.metaloom.loom.db.model.asset.AssetImageComp;
import io.metaloom.loom.db.model.asset.AssetJsonComp;
import io.metaloom.loom.db.model.asset.AssetTranscriptComp;
import io.metaloom.loom.db.model.asset.AssetVideoComp;
import io.metaloom.loom.db.model.collection.Collection;
import io.metaloom.loom.db.model.collection.CollectionDao;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.group.GroupDao;
import io.metaloom.loom.db.model.library.Library;
import io.metaloom.loom.db.model.library.LibraryDao;
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
import io.metaloom.loom.db.model.task.Task;
import io.metaloom.loom.db.model.task.TaskDao;
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
	private static final String DEMO_LIBRARY_CAMPAIGNS = "Campaign Media";
	private static final String DEMO_LIBRARY_ARCHIVE = "Archive Footage";
	private static final String DEMO_LIBRARY_AUDIO = "Audio Sessions";
	private static final String DEMO_PIPELINE_SIMPLE = "Quick Hash";
	private static final String DEMO_PIPELINE_MEDIUM = "Ingest & Proxy";
	private static final String DEMO_PIPELINE_COMPLEX = "Full Processing";
	private static final String DEMO_POOL_PRODUCTION = "Production Storage";
	private static final String DEMO_POOL_INGEST = "Ingest Hot Storage";
	private static final String DEMO_POOL_ARCHIVE = "Archive S3";

	private final UserDao userDao;
	private final AssetDao assetDao;
	private final SpaceDao spaceDao;
	private final TagDao tagDao;
	private final CollectionDao collectionDao;
	private final LibraryDao libraryDao;
	private final PipelineDao pipelineDao;
	private final AssetPoolDao assetPoolDao;
	private final GroupDao groupDao;
	private final RoleDao roleDao;
	private final PermissionDao permissionDao;
	private final TaskDao taskDao;

	@Inject
	public DemoDatabaseInitializer(UserDao userDao, AssetDao assetDao, SpaceDao spaceDao,
		TagDao tagDao, CollectionDao collectionDao, LibraryDao libraryDao, PipelineDao pipelineDao, AssetPoolDao assetPoolDao,
		GroupDao groupDao, RoleDao roleDao, PermissionDao permissionDao, TaskDao taskDao) {
		this.userDao = userDao;
		this.assetDao = assetDao;
		this.spaceDao = spaceDao;
		this.tagDao = tagDao;
		this.collectionDao = collectionDao;
		this.libraryDao = libraryDao;
		this.pipelineDao = pipelineDao;
		this.assetPoolDao = assetPoolDao;
		this.groupDao = groupDao;
		this.roleDao = roleDao;
		this.permissionDao = permissionDao;
		this.taskDao = taskDao;
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

		// --- Libraries ---
		createLibrary(admin, DEMO_LIBRARY_CAMPAIGNS);
		createLibrary(admin, DEMO_LIBRARY_ARCHIVE);
		createLibrary(admin, DEMO_LIBRARY_AUDIO);

		// --- Pipelines ---
		// 1) Simple pipeline: Source → Hash → Output
		createPipeline(admin, DEMO_PIPELINE_SIMPLE,
			"Simple pipeline that hashes incoming assets and stores the result.",
			true, 1, false,
			new JsonObject()
				.put("nodes", new JsonArray()
					.add(node("pn1", "filesystem-source", "File Source", "Watch local folder", 60, 120))
					.add(node("pn2", "sha256", "SHA-256 Hash", "Compute SHA-256 digest", 300, 120))
					.add(node("pn3", "loom", "Loom Output", "Persist to Loom", 540, 120)))
				.put("edges", new JsonArray()
					.add(edge("pe1", "pn1", "pn2"))
					.add(edge("pe2", "pn2", "pn3"))));

		// 2) Medium pipeline: Source → Filter → Hash + Fingerprint → Output
		createPipeline(admin, DEMO_PIPELINE_MEDIUM,
			"Ingest pipeline with MIME-type filtering, hashing, fingerprinting, and proxy generation.",
			true, 5, false,
			new JsonObject()
				.put("nodes", new JsonArray()
					.add(node("pn1", "filesystem-source", "File Source", "Watch ingest folder", 60, 160))
					.add(node("pn2", "filter-mimetype", "MIME Filter", "Accept video and image types", 260, 160))
					.add(node("pn3", "sha256", "SHA-256 Hash", "Compute hash", 460, 60))
					.add(node("pn4", "fingerprint", "Fingerprint", "Audio/video fingerprint", 460, 260))
					.add(node("pn5", "loom", "Loom Output", "Store results", 680, 160)))
				.put("edges", new JsonArray()
					.add(edge("pe1", "pn1", "pn2"))
					.add(edge("pe2", "pn2", "pn3"))
					.add(edge("pe3", "pn2", "pn4"))
					.add(edge("pe4", "pn3", "pn5"))
					.add(edge("pe5", "pn4", "pn5"))));

		// 3) Complex pipeline: Source → Filter → Hash + Fingerprint + Resize → Face Detection → Loom Output
		createPipeline(admin, DEMO_PIPELINE_COMPLEX,
			"Full processing pipeline with filtering, analysis, face detection, and multi-output.",
			true, 10, false,
			new JsonObject()
				.put("nodes", new JsonArray()
					.add(node("pn1", "filesystem-source", "File Source", "Watch production folder", 60, 200))
					.add(node("pn2", "filter-mimetype", "MIME Filter", "Accept media types", 240, 200))
					.add(node("pn3", "sha256", "SHA-256 Hash", "Compute SHA-256", 440, 60))
					.add(node("pn4", "fingerprint", "Fingerprint", "Chromaprint fingerprint", 440, 200))
					.add(node("pn5", "resize", "Resize Proxy", "Generate 720p proxy", 440, 340))
					.add(node("pn6", "face-detect", "Face Detection", "Detect faces with InspireFace", 660, 130))
					.add(node("pn7", "loom", "Loom Output", "Persist metadata", 880, 130))
					.add(node("pn8", "s3-output", "S3 Delivery", "Upload proxies to S3", 880, 340)))
				.put("edges", new JsonArray()
					.add(edge("pe1", "pn1", "pn2"))
					.add(edge("pe2", "pn2", "pn3"))
					.add(edge("pe3", "pn2", "pn4"))
					.add(edge("pe4", "pn2", "pn5"))
					.add(edge("pe5", "pn3", "pn6"))
					.add(edge("pe6", "pn4", "pn6"))
					.add(edge("pe7", "pn6", "pn7"))
					.add(edge("pe8", "pn5", "pn8"))));

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
			Permission.CREATE_LIBRARY, Permission.READ_LIBRARY, Permission.UPDATE_LIBRARY, Permission.DELETE_LIBRARY,
			Permission.CREATE_TAG, Permission.READ_TAG, Permission.UPDATE_TAG, Permission.DELETE_TAG,
			Permission.TAG_ASSET, Permission.UNTAG_ASSET,
			Permission.CREATE_TASK, Permission.READ_TASK, Permission.UPDATE_TASK, Permission.DELETE_TASK,
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
			Permission.READ_TASK,
			Permission.READ_COMMENT, Permission.READ_ANNOTATION,
			Permission.READ_USER, Permission.READ_GROUP, Permission.READ_ROLE,
			Permission.READ_SPACE, Permission.READ_LIBRARY, Permission.READ_PIPELINE, Permission.READ_ASSET_POOL,
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

		// --- Tasks ---
		createTask(admin, "Review metadata quality", "Check imported assets for missing descriptions and keywords.");
		createTask(admin, "Approve campaign cut", "Review the latest campaign cut and approve for publishing.");
		createTask(admin, "Tag city timelapse", "Assign accurate tags to timelapse-city.mp4 for discoverability.");

		log.info("Demo data initialization complete — created {} assets, {} tags, {} collections, {} pipelines, {} users, {} groups, {} roles, {} tasks.",
			imageAssets.length + videoAssets.length + audioAssets.length + docAssets.length,
			8, 2, 3, 2, 2, 2, 3);
	}

	private Task createTask(User admin, String title, String description) {
		Task task = taskDao.createTask(admin.getUuid(), title);
		task.setUuid(UUIDUtils.randomUUID());
		task.setCreator(admin);
		task.setEditor(admin);
		task.setCreated(Instant.now());
		task.setEdited(Instant.now());
		task.setDescription(description);
		taskDao.store(task);
		log.info("Created demo task: {}", title);
		return task;
	}

	private Library createLibrary(User admin, String name) {
		Library library = libraryDao.createLibrary(admin.getUuid(), name);
		library.setUuid(UUIDUtils.randomUUID());
		library.setCreator(admin);
		library.setEditor(admin);
		library.setCreated(Instant.now());
		library.setEdited(Instant.now());
		libraryDao.store(library);
		log.info("Created demo library: {}", name);
		return library;
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

	private Pipeline createPipeline(User admin, String name, String description,
			boolean enabled, int priority, boolean dryRun, JsonObject definition) {
		Pipeline pipeline = pipelineDao.createPipeline(admin.getUuid(), name);
		pipeline.setUuid(UUIDUtils.randomUUID());
		pipeline.setCreator(admin);
		pipeline.setEditor(admin);
		pipeline.setCreated(Instant.now());
		pipeline.setEdited(Instant.now());
		pipeline.setDescription(description);
		pipeline.setEnabled(enabled);
		pipeline.setPriority(priority);
		pipeline.setDryRun(dryRun);
		pipeline.setDefinition(definition);
		pipelineDao.store(pipeline);
		log.info("Created demo pipeline: {}", name);
		return pipeline;
	}

	private static JsonObject node(String id, String type, String label, String description, int x, int y) {
		return new JsonObject()
			.put("id", id)
			.put("type", type)
			.put("label", label)
			.put("description", description)
			.put("position", new JsonObject().put("x", x).put("y", y))
			.put("data", new JsonObject());
	}

	private static JsonObject edge(String id, String source, String target) {
		return new JsonObject()
			.put("id", id)
			.put("source", source)
			.put("target", target);
	}
}
