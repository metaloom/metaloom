package io.metaloom.loom.core.boot;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import io.metaloom.loom.agent.memory.MemoryHeader;
import io.metaloom.loom.api.memory.MemoryScope;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.api.task.TaskPriority;
import io.metaloom.loom.api.task.TaskStatus;
import io.metaloom.loom.db.model.annotation.Annotation;
import io.metaloom.loom.db.model.annotation.AnnotationDao;
import io.metaloom.loom.api.annotation.AnnotationType;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetAudioComp;
import io.metaloom.loom.db.model.asset.AssetBinary;
import io.metaloom.loom.db.model.asset.AssetBinaryDao;
import io.metaloom.loom.db.model.asset.AssetComponentDao;
import io.metaloom.loom.db.model.asset.AssetDao;
import io.metaloom.loom.db.model.asset.AssetDocComp;
import io.metaloom.loom.db.model.asset.AssetGeoComp;
import io.metaloom.loom.db.model.asset.AssetImageComp;
import io.metaloom.loom.db.model.asset.AssetJsonComp;
import io.metaloom.loom.db.model.asset.AssetTranscriptComp;
import io.metaloom.loom.db.model.asset.AssetVideoComp;
import io.metaloom.loom.db.model.blacklist.Blacklist;
import io.metaloom.loom.db.model.blacklist.BlacklistDao;
import io.metaloom.loom.db.model.chat.Chat;
import io.metaloom.loom.db.model.chat.ChatDao;
import io.metaloom.loom.db.model.chatsession.ChatSession;
import io.metaloom.loom.db.model.chatsession.ChatSessionContextRef;
import io.metaloom.loom.db.model.chatsession.ChatSessionDao;
import io.metaloom.loom.db.model.chatsession.ChatSessionSkillPin;
import io.metaloom.loom.db.model.cluster.Cluster;
import io.metaloom.loom.db.model.cluster.ClusterDao;
import io.metaloom.loom.db.model.detection.Detection;
import io.metaloom.loom.db.model.detection.DetectionDao;
import io.metaloom.loom.db.model.person.Person;
import io.metaloom.loom.db.model.person.PersonDao;
import io.metaloom.loom.db.model.collection.Collection;
import io.metaloom.loom.db.model.collection.CollectionDao;
import io.metaloom.loom.db.model.comment.Comment;
import io.metaloom.loom.db.model.comment.CommentDao;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.group.GroupDao;
import io.metaloom.loom.db.model.memory.MemoryDenyRule;
import io.metaloom.loom.db.model.memory.MemoryDenyRuleDao;
import io.metaloom.loom.db.model.memory.MemoryEntry;
import io.metaloom.loom.db.model.memory.MemoryEntryDao;
import io.metaloom.loom.db.model.library.Library;
import io.metaloom.loom.db.model.library.LibraryDao;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.perm.PermissionDao;
import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineDao;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.model.pipeline.PipelineRunDao;
import io.metaloom.loom.db.model.pipeline.PipelineVersion;
import io.metaloom.loom.db.model.pipeline.PipelineVersionDao;
import io.metaloom.loom.db.model.pool.AssetPool;
import io.metaloom.loom.db.model.pool.AssetPoolDao;
import io.metaloom.loom.db.model.reaction.Reaction;
import io.metaloom.loom.db.model.reaction.ReactionDao;
import io.metaloom.loom.db.model.role.Role;
import io.metaloom.loom.db.model.role.RoleDao;
import io.metaloom.loom.db.model.skill.Skill;
import io.metaloom.loom.db.model.skill.SkillDao;
import io.metaloom.loom.db.model.skill.SkillVersion;
import io.metaloom.loom.db.model.skill.SkillVersionDao;
import io.metaloom.loom.db.model.space.Space;
import io.metaloom.loom.db.model.space.SpaceDao;
import io.metaloom.loom.db.model.tag.AssetTag;
import io.metaloom.loom.db.model.tag.TagDao;
import io.metaloom.loom.db.model.task.Task;
import io.metaloom.loom.db.model.task.TaskDao;
import io.metaloom.loom.db.model.token.Token;
import io.metaloom.loom.db.model.token.TokenDao;
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
	private static final String DEMO_PIPELINE_SCRIPT = "Reading Time (Script)";
	private static final String DEMO_PIPELINE_S3 = "Cloud Bucket Ingest";
	private static final String DEMO_PIPELINE_S3_PUBLISH = "Thumbnail Publishing";
	private static final String DEMO_PIPELINE_TRANSCRIPTION = "Media Transcription";

	/**
	 * The demo script node's body. Small on purpose - it is there to be read and edited, not admired.
	 *
	 * <p>
	 * Reads {@code data.text}, which is where the script node surfaces whatever is wired into its
	 * {@code text} input port - here Tika's {@code content}. It used to read
	 * {@code upstream['pn2']['tika_content']}: a node id the pipeline author picked plus an output
	 * name, which broke the moment either was renamed and returned nothing rather than failing. That
	 * binding no longer exists, so this seeded pipeline threw a {@code ReferenceError} on every run.
	 * </p>
	 */
	private static final String DEMO_SCRIPT = """
		// Estimate reading time from the text wired into the 'text' input port (Tika's content).
		const text = data.text || '';
		const words = text.split(/\\s+/).filter(w => w.length > 0).length;
		const minutes = Math.max(1, Math.round(words / params.wordsPerMinute));

		out.integer('reading_minutes', minutes);
		out.string('length_band', minutes <= 2 ? 'short' : minutes <= 10 ? 'medium' : 'long');
		log.info(words + ' words, about ' + minutes + ' minute(s)');
		""";
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
	private final AnnotationDao annotationDao;
	private final ReactionDao reactionDao;
	private final TokenDao tokenDao;
	private final CommentDao commentDao;
	private final BlacklistDao blacklistDao;
	private final MemoryDenyRuleDao memoryDenyRuleDao;
	private final ClusterDao clusterDao;
	private final PersonDao personDao;
	private final DetectionDao detectionDao;
	private final AssetComponentDao assetComponentDao;
	private final ChatDao chatDao;
	private final PipelineVersionDao pipelineVersionDao;
	private final PipelineRunDao pipelineRunDao;
	private final AssetBinaryDao assetBinaryDao;
	private final SkillDao skillDao;
	private final SkillVersionDao skillVersionDao;
	private final ChatSessionDao chatSessionDao;
	private final MemoryEntryDao memoryEntryDao;
	private final LoomOptions options;

	/** Running detection ordinal per {@code asset|nodeKind|frame}; see {@link #createDetection}. */
	private final Map<String, Integer> detectionOrdinals = new HashMap<>();

	@Inject
	public DemoDatabaseInitializer(UserDao userDao, AssetDao assetDao, SpaceDao spaceDao,
		TagDao tagDao, CollectionDao collectionDao, LibraryDao libraryDao, PipelineDao pipelineDao, AssetPoolDao assetPoolDao,
		GroupDao groupDao, RoleDao roleDao, PermissionDao permissionDao, TaskDao taskDao,
		AnnotationDao annotationDao, ReactionDao reactionDao, TokenDao tokenDao,
		CommentDao commentDao, BlacklistDao blacklistDao, MemoryDenyRuleDao memoryDenyRuleDao, ClusterDao clusterDao, PersonDao personDao,
		DetectionDao detectionDao,
		AssetComponentDao assetComponentDao, ChatDao chatDao, PipelineVersionDao pipelineVersionDao,
		PipelineRunDao pipelineRunDao, AssetBinaryDao assetBinaryDao, SkillDao skillDao, SkillVersionDao skillVersionDao,
		ChatSessionDao chatSessionDao, MemoryEntryDao memoryEntryDao, LoomOptions options) {
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
		this.annotationDao = annotationDao;
		this.reactionDao = reactionDao;
		this.tokenDao = tokenDao;
		this.commentDao = commentDao;
		this.blacklistDao = blacklistDao;
		this.memoryDenyRuleDao = memoryDenyRuleDao;
		this.clusterDao = clusterDao;
		this.personDao = personDao;
		this.detectionDao = detectionDao;
		this.assetComponentDao = assetComponentDao;
		this.chatDao = chatDao;
		this.pipelineVersionDao = pipelineVersionDao;
		this.pipelineRunDao = pipelineRunDao;
		this.assetBinaryDao = assetBinaryDao;
		this.skillDao = skillDao;
		this.skillVersionDao = skillVersionDao;
		this.chatSessionDao = chatSessionDao;
		this.memoryEntryDao = memoryEntryDao;
		this.options = options;
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

		// --- Asset Pools ---
		// Created before the libraries because a library points at the pool its binaries go to. Two
		// filesystem pools and one S3 pool, so the demo shows both storage types rather than implying
		// Loom only writes to disk.
		createAssetPool(admin, DEMO_POOL_PRODUCTION, "/mnt/media/production", null, null, null);
		createAssetPool(admin, DEMO_POOL_INGEST, "/mnt/fast-ssd/ingest", null, null, null);
		AssetPool archivePool = createAssetPool(admin, DEMO_POOL_ARCHIVE, null, "metaloom-archive-prod", "eu-central-1",
			"https://s3.eu-central-1.amazonaws.com");

		// --- Libraries ---
		// Campaigns and audio stay on the default local upload directory: that is where the seeded demo
		// image bytes actually are, and pointing them at a pool would describe a location nothing wrote
		// to. The archive library carries no seeded binaries, so it is the honest place to show an
		// S3-backed library.
		Library campaignLibrary = createLibrary(admin, DEMO_LIBRARY_CAMPAIGNS, null);
		createLibrary(admin, DEMO_LIBRARY_ARCHIVE, archivePool);
		createLibrary(admin, DEMO_LIBRARY_AUDIO, null);

		// --- Pipelines ---
		// 1) Simple pipeline: Source → Hash → Output
		Pipeline simplePipeline = createPipeline(admin, DEMO_PIPELINE_SIMPLE,
			"Simple pipeline that hashes incoming assets and stores the result.",
			true, 1, false,
			simpleDefinition());

		// 2) Medium pipeline: Source → Filter → Hash + Fingerprint → Output
		Pipeline mediumPipeline = createPipeline(admin, DEMO_PIPELINE_MEDIUM,
			"Ingest pipeline with MIME-type filtering, hashing, fingerprinting, and proxy generation.",
			true, 5, false,
			mediumDefinition());

		// 3) Complex pipeline: Source → Filter → Hash + Fingerprint + Facedetect → Facedescription → Loom.
		// This is also the sequence demo: facedetect emits one element per detected face, and
		// facedescription declares a sequence input, so it gathers the whole set for an image and
		// runs once with all of them - emitting one description per face in the same order. Nothing
		// in the definition says so; it follows from the two ports' cardinalities, and the engine
		// works it out when the graph is parsed. (A node declaring a *single* detection input would
		// instead run once per face; no shipped kind does that today.)
		Pipeline complexPipeline = createPipeline(admin, DEMO_PIPELINE_COMPLEX,
			"Full processing pipeline with filtering, analysis, per-face description, and multi-output.",
			true, 10, false,
			complexDefinition());

		// 4) S3 pipeline: S3 Source → Hash → Loom. Shows the one source that needs no shared media
		// mount: it emits s3:// references and each worker fetches the objects it is given, so this
		// graph runs across machines that share nothing but access to the bucket. The source is
		// differential, so a re-run only picks up objects that are new or changed.
		createPipeline(admin, DEMO_PIPELINE_S3,
			"Ingests new and changed objects from an S3 bucket, hashing each one. Re-runs skip everything unchanged.",
			true, 5, false,
			s3IngestDefinition());

		// 5) Publishing pipeline: Source → Hash → Thumbnail → S3 Sink. The counterpart to the
		// ingest pipeline above: produced bytes normally stay on the worker that made them, and
		// the sink is what turns each thumbnail into a retrievable Loom asset. Producer and sink
		// must share a worker - the sink reads the contact sheet off local disk.
		createPipeline(admin, DEMO_PIPELINE_S3_PUBLISH,
			"Generates thumbnails and publishes them to an S3 bucket, registering each one as its own asset.",
			true, 5, false,
			s3PublishDefinition());

		// 6) Script pipeline: Source → Tika → Script. Demonstrates the one node whose behaviour is
		// configuration rather than code, so the demo carries a real script and real declared outputs.
		createPipeline(admin, DEMO_PIPELINE_SCRIPT,
			"Extracts document text, then derives a reading-time estimate and a length band with a small script.",
			true, 3, false,
			scriptDefinition());

		// 7) Transcription pipeline: Source → Filter → Whisper → Sentiment. The demo answer to
		// "show me the pipeline that transcribes our media": a short, linear graph that reads well
		// as the compact diagram the chat agent draws for get_pipeline.
		createPipeline(admin, DEMO_PIPELINE_TRANSCRIPTION,
			"Transcribes speech in audio and video with Whisper and scores the sentiment of the transcript.",
			true, 4, false,
			transcriptionDefinition());

		// --- Pipeline Runs ---
		// History so the run views and the statistics chart have something to show on a
		// fresh demo. One run per status: a clean success, a partial failure, a live run and
		// a suspended one.
		createPipelineRun(admin, simplePipeline, "SUCCESS", 1, 128, 128, 0, 0, 42_000L);
		createPipelineRun(admin, simplePipeline, "SUCCESS", 4, 96, 94, 0, 2, 31_500L);
		createPipelineRun(admin, mediumPipeline, "PARTIAL", 2, 64, 58, 6, 0, 187_000L);
		createPipelineRun(admin, mediumPipeline, "FAILED", 6, 12, 0, 12, 0, 9_800L);
		createPipelineRun(admin, complexPipeline, "PAUSED", 0, 512, 240, 3, 1, null);
		createPipelineRun(admin, complexPipeline, "RUNNING", 0, 340, 180, 0, 4, null);

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
			Permission.CREATE_BLACKLIST, Permission.READ_BLACKLIST, Permission.UPDATE_BLACKLIST, Permission.DELETE_BLACKLIST,
			Permission.CREATE_CHAT, Permission.READ_CHAT, Permission.UPDATE_CHAT, Permission.DELETE_CHAT,
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
			Permission.READ_BLACKLIST,
			Permission.READ_CHAT,
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

		// --- Tokens (API Keys) ---
		createDemoToken(admin, "CI Pipeline Key", "demo-ci-token-value");
		createDemoToken(admin, "Mobile App Key", "demo-mobile-token-value");

		// --- Assets ---
		// Image assets are created with real bytes on disk (see createImageAsset), so the asset
		// browser and the detail view render an actual preview instead of a type placeholder.
		Asset[] imageAssets = {
			createImageAsset(admin, campaignLibrary, "sunset-beach.jpg", "image/jpeg", "/demo/photos/sunset-beach.jpg", Palette.SUNSET),
			createImageAsset(admin, campaignLibrary, "mountain-lake.jpg", "image/jpeg", "/demo/photos/mountain-lake.jpg", Palette.LAKE),
			createImageAsset(admin, campaignLibrary, "city-skyline.png", "image/png", "/demo/photos/city-skyline.png", Palette.CITY),
			createImageAsset(admin, campaignLibrary, "portrait-studio.jpg", "image/jpeg", "/demo/photos/portrait-studio.jpg", Palette.STUDIO),
			createImageAsset(admin, campaignLibrary, "forest-trail.jpg", "image/jpeg", "/demo/photos/forest-trail.jpg", Palette.FOREST),
			createImageAsset(admin, campaignLibrary, "autumn-leaves.jpg", "image/jpeg", "/demo/photos/autumn-leaves.jpg", Palette.AUTUMN),
			createImageAsset(admin, campaignLibrary, "snow-peaks.jpg", "image/jpeg", "/demo/photos/snow-peaks.jpg", Palette.SNOW),
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

		// A scanned page: an image by format, a document by content. It carries the demo `vlm` component below.
		Asset scanAsset = createImageAsset(admin, campaignLibrary, "scanned-invoice.png", "image/png", "/demo/docs/scanned-invoice.png",
			Palette.SCAN);
		tagDao.tagAsset(tagImage, scanAsset);
		tagDao.tagAsset(tagDocument, scanAsset);

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
		// Every task is attached to the asset it is about, so the Tasks tab of the asset detail
		// view has content and the task board shows a realistic mix of statuses and priorities.
		createAssetTask(admin, imageAssets[0], "Colour-grade the hero shot",
			"The white balance drifts warm in the top-left quadrant — regrade before the campaign export.",
			TaskPriority.HIGH, TaskStatus.PENDING, 3);
		createAssetTask(admin, imageAssets[2], "Clear building rights",
			"Confirm the property release for the skyline before this goes into the paid campaign.",
			TaskPriority.CRITICAL, TaskStatus.REVIEW, 1);
		createAssetTask(admin, imageAssets[3], "Retouch studio portrait",
			"Light skin retouching and a tighter crop for the 1:1 social variant.",
			TaskPriority.MEDIUM, TaskStatus.ACCEPTED, 7);
		createAssetTask(admin, videoAssets[1], "Tag city timelapse",
			"Assign accurate location and time-of-day tags to timelapse-city.mp4 for discoverability.",
			TaskPriority.LOW, TaskStatus.PENDING, 14);
		createAssetTask(admin, videoAssets[2], "Approve interview cut",
			"Review the latest interview cut and approve it for publishing.",
			TaskPriority.HIGH, TaskStatus.REVIEW, 2);
		createAssetTask(admin, audioAssets[1], "Check transcript accuracy",
			"Spot-check the ASR transcript of the podcast episode against the audio.",
			TaskPriority.MEDIUM, TaskStatus.PENDING, 5);
		createTask(admin, "Review metadata quality", "Check imported assets for missing descriptions and keywords.",
			TaskPriority.LOW, TaskStatus.PENDING, 21);

		// --- Annotations ---
		Annotation ann1 = annotationDao.createAnnotation(admin, imageAssets[0], "Color correction needed", AnnotationType.FEEDBACK);
		ann1.setDescription("The white balance is slightly off in the top-left quadrant.");
		ann1.setAreaStartX(0);
		ann1.setAreaStartY(0);
		ann1.setAreaWidth(500);
		ann1.setAreaHeight(400);
		annotationDao.store(ann1);

		Annotation ann2 = annotationDao.createAnnotation(admin, videoAssets[0], "Audio peak", AnnotationType.FEEDBACK);
		ann2.setDescription("Transient peak exceeds -3dB at this timestamp.");
		ann2.setTimeFrom(8000L);
		ann2.setTimeTo(9000L);
		annotationDao.store(ann2);

		Annotation ann3 = annotationDao.createAnnotation(admin, imageAssets[1], "Crop suggestion", AnnotationType.FEEDBACK);
		ann3.setDescription("Consider a tighter crop for the hero banner variant.");
		annotationDao.store(ann3);

		log.info("Created {} demo annotations", 3);

		// --- Reactions ---
		Reaction rx1 = reactionDao.createReaction(admin, "THUMBSUP");
		rx1.setAssetUuid(imageAssets[0].getUuid());
		reactionDao.store(rx1);

		Reaction rx2 = reactionDao.createReaction(admin, "SATISFIED");
		rx2.setAssetUuid(imageAssets[1].getUuid());
		reactionDao.store(rx2);

		Reaction rx3 = reactionDao.createReaction(admin, "PLUS_ONE");
		rx3.setAssetUuid(videoAssets[0].getUuid());
		reactionDao.store(rx3);

		log.info("Created {} demo reactions", 3);

		// --- Comments ---
		createComment(admin, "Review notes", "The white balance looks slightly off in the second half.");
		createComment(admin, "Approved", "Looks great, ready for distribution.");
		createComment(admin, "Tagging feedback", "Please add location tags for the city timelapse assets.");
		log.info("Created {} demo comments", 3);

		// --- Skills ---
		// Two versions each, because a one-version skill hides the whole version UI: the version
		// chip, the history list and the revert action all need something to point at.
		Skill gradingSkill = createDemoSkill(admin, "campaign-grading",
			"Grade a campaign still against the house look before export.",
			"""
				# Campaign grading

				Check the still against the campaign look before it leaves Loom.

				1. Pull the reference frame from the `Campaign Media` library.
				2. Compare white balance and contrast; note any drift over 200K.
				3. Record the verdict as a FEEDBACK annotation on the asset.
				""",
			"Grade a campaign still against the house look, and flag rights issues, before export.",
			"""
				# Campaign grading

				Check the still against the campaign look before it leaves Loom.

				1. Pull the reference frame from the `Campaign Media` library.
				2. Compare white balance and contrast; note any drift over 200K.
				3. Check for recognisable people or property that need a release.
				4. Record the verdict as a FEEDBACK annotation on the asset, and open a task
				   when a release is missing.
				""",
			true);

		Skill taggingSkill = createDemoSkill(admin, "hierarchical-tagging",
			"Apply the house tagging convention to a collection.",
			"""
				# Hierarchical tagging

				Use `location/city/landmark` for place and one time-of-day tag.
				""",
			"Apply the house tagging convention to a collection, including time-of-day tags.",
			"""
				# Hierarchical tagging

				Use `location/city/landmark` for place and exactly one time-of-day tag
				(`golden-hour`, `blue-hour`, `daylight`, `night`).

				Never invent a landmark: leave the third level off when it is not identifiable.
				""",
			true);

		createDemoSkill(admin, "transcript-qa",
			"Spot-check an ASR transcript against its audio.",
			"""
				# Transcript QA

				Sample three sections and compare them to the audio.
				""",
			"Spot-check an ASR transcript against its audio and correct the section titles.",
			"""
				# Transcript QA

				Sample three sections spread across the running time and compare them to the audio.
				Correct section titles so they describe the content, not the timecode.
				""",
			false);

		// --- Chats and chat sessions ---
		Chat gradingChat = createDemoChat(admin, "Asset review discussion", new JsonArray()
			.add(new JsonObject().put("role", "user").put("content", "Can you check the quality of the recently uploaded landscape photos?"))
			.add(new JsonObject().put("role", "assistant").put("content", "I reviewed the 5 landscape photos. Three have excellent resolution, but two appear to have compression artifacts.")
				.put("references", new JsonArray()
					.add(new JsonObject().put("type", "asset").put("label", "mountain_sunrise.jpg"))
					.add(new JsonObject().put("type", "asset").put("label", "forest_fog.jpg"))))
			.add(new JsonObject().put("role", "user").put("content", "Should we re-upload the two with artifacts?"))
			.add(new JsonObject().put("role", "assistant").put("content", "Yes, I recommend re-uploading from the original RAW files to preserve quality.")));

		Chat taggingChat = createDemoChat(admin, "Tagging strategy", new JsonArray()
			.add(new JsonObject().put("role", "user").put("content", "What tagging convention should we use for the city timelapse collection?"))
			.add(new JsonObject().put("role", "assistant").put("content", "I suggest using hierarchical tags: location/city/landmark, and adding time-of-day tags like golden-hour or blue-hour."))
			.add(new JsonObject().put("role", "user").put("content", "Good idea. Can you apply those to the existing assets?"))
			.add(new JsonObject().put("role", "assistant").put("content", "Done. I tagged 12 timelapse assets with the new convention.")
				.put("references", new JsonArray()
					.add(new JsonObject().put("type", "collection").put("label", "Demo Videos")))));

		Chat exportChat = createDemoChat(admin, "Q3 campaign export", new JsonArray()
			.add(new JsonObject().put("role", "user").put("content", "Prepare the Q3 campaign stills for export. Use the grading skill and the tagging convention we agreed on."))
			.add(new JsonObject().put("role", "assistant").put("content", "I graded 7 stills against the campaign reference. Two drifted warm and are regraded; one still needs a property release before it can ship.")
				.put("references", new JsonArray()
					.add(new JsonObject().put("type", "asset").put("label", "sunset-beach.jpg"))
					.add(new JsonObject().put("type", "asset").put("label", "city-skyline.png"))
					.add(new JsonObject().put("type", "skill").put("label", "campaign-grading"))))
			.add(new JsonObject().put("role", "user").put("content", "Open a task for the release and tag everything else."))
			.add(new JsonObject().put("role", "assistant").put("content", "Done — a CRITICAL task is on city-skyline.png, and the remaining stills carry location and time-of-day tags.")
				.put("references", new JsonArray()
					.add(new JsonObject().put("type", "task").put("label", "Clear building rights"))
					.add(new JsonObject().put("type", "skill").put("label", "hierarchical-tagging")))));
		log.info("Created {} demo chats", 3);

		// The two earlier conversations are published so the third can pull them in as context:
		// this is what the Chat Sessions detail view is built to show, and an unpublished session
		// cannot be referenced at all.
		ChatSession gradingSession = createDemoChatSession(admin, gradingChat, "Campaign look review",
			"How the Q3 campaign stills were reviewed against the house look, and what was rejected.",
			new String[] { "campaign", "review" }, true);
		ChatSession taggingSession = createDemoChatSession(admin, taggingChat, "Tagging convention",
			"The agreed hierarchical tagging convention for city timelapse footage.",
			new String[] { "tagging", "convention" }, true);
		ChatSession exportSession = createDemoChatSession(admin, exportChat, "Q3 campaign export",
			"Grading, rights check and tagging for the Q3 campaign export — builds on the two sessions above.",
			new String[] { "campaign", "export" }, false);

		// Context: the export session reads the review conversation and inherits the tagging
		// session's skills without dragging in its chat history.
		chatSessionDao.replaceContextRefs(exportSession.getUuid(), List.of(
			new ChatSessionContextRef(exportSession.getUuid(), gradingSession.getUuid(), true, true, false, 0),
			new ChatSessionContextRef(exportSession.getUuid(), taggingSession.getUuid(), false, true, false, 1)));

		// Pin the skill versions that were active while the session ran, so it stays reproducible.
		chatSessionDao.replaceSkillPins(exportSession.getUuid(), List.of(
			new ChatSessionSkillPin(exportSession.getUuid(), gradingSkill.getUuid(), 2),
			new ChatSessionSkillPin(exportSession.getUuid(), taggingSkill.getUuid(), 2)));
		log.info("Created {} demo chat sessions ({} context refs, {} skill pins)", 3, 2, 2);

		// --- Agent memory ---
		createMemoryEntry(admin, "house-style.md", "House look for campaign stills",
			"""
				Campaign stills are graded to the house look: neutral white balance (5600K ±200K),
				no crushed blacks, and highlight roll-off kept soft.

				Deviations are recorded as a FEEDBACK annotation on the asset, never as a comment —
				comments are not picked up by the review workflow.
				""");
		createMemoryEntry(admin, "conventions/tagging.md", "Tagging convention",
			"""
				Place tags are hierarchical: `location/city/landmark`. The third level is omitted
				when the landmark is not identifiable — do not guess it.

				Exactly one time-of-day tag per asset: `golden-hour`, `blue-hour`, `daylight` or `night`.
				""");
		createMemoryEntry(admin, "projects/q3-campaign.md", "Q3 campaign",
			"""
				The Q3 campaign ships from the `Campaign Media` library. Stills need a property
				release whenever a recognisable building fills more than a third of the frame.

				Rights questions go to Media Ops as a CRITICAL task, not as a chat message.
				""");
		log.info("Created {} demo memory entries", 3);

		// --- Blacklist entries ---
		createBlacklist(admin, imageAssets[0], "Duplicate low-res variant");
		createBlacklist(admin, videoAssets[1], "Copyright strike - pending review");
		log.info("Created {} demo blacklist entries", 2);

		// --- Memory deny rules ---
		// Two shapes of rule, because they are the two an admin will actually write:
		// several phrases folded into one rule via alternation, and a credential format.
		createMemoryDenyRule(admin, "Confidential project codenames",
			"(?i)\\b(project bluebird|operation nightfall|codename raven)\\b",
			"This note names a confidential project codename. Summarise the work without naming the project.");
		createMemoryDenyRule(admin, "AWS access key id",
			"AKIA[0-9A-Z]{16}",
			"This note looks like it contains an AWS access key. Credentials must never be stored in memory — rotate the key if it is real.");
		log.info("Created {} demo memory deny rules", 2);

		// --- Persons ---
		createPerson(admin, "jdoe", "John", "Doe");
		createPerson(admin, "asmith", "Alice", "Smith");
		createPerson(admin, "bwilson", "Bob", "Wilson");
		log.info("Created {} demo persons", 3);

		// --- Clusters ---
		createCluster(admin, "Face Cluster A", "face");
		createCluster(admin, "Face Cluster B", "face");
		createCluster(admin, "Face Cluster C", "face");
		log.info("Created {} demo clusters", 3);

		// --- Detections ---
		// Face detections on image assets
		createDetection(admin, imageAssets[0], "facedetection", 0, 0.3f, 0.2f, 0.12f, 0.2f, 0.97f,
			new JsonObject().put("gender", "male").put("age", 30));
		createDetection(admin, imageAssets[0], "facedetection", 0, 0.55f, 0.15f, 0.1f, 0.18f, 0.94f,
			new JsonObject().put("gender", "female").put("age", 25));
		createDetection(admin, imageAssets[3], "facedetection", 0, 0.42f, 0.15f, 0.13f, 0.22f, 0.91f,
			new JsonObject().put("gender", "male").put("age", 45));

		// Face detections on video assets (different frames)
		createDetection(admin, videoAssets[0], "facedetection", 60, 0.4f, 0.1f, 0.15f, 0.22f, 0.92f,
			new JsonObject().put("gender", "female").put("age", 28));
		createDetection(admin, videoAssets[0], "facedetection", 180, 0.2f, 0.3f, 0.1f, 0.18f, 0.89f,
			new JsonObject().put("gender", "male").put("age", 35));
		createDetection(admin, videoAssets[2], "facedetection", 300, 0.45f, 0.2f, 0.1f, 0.18f, 0.96f,
			new JsonObject().put("gender", "female").put("age", 32));

		// Object detections on image assets
		createDetection(admin, imageAssets[0], "objectdetection", 0, 0.1f, 0.4f, 0.25f, 0.3f, 0.95f,
			new JsonObject().put("label", "car"));
		createDetection(admin, imageAssets[0], "objectdetection", 0, 0.5f, 0.2f, 0.12f, 0.35f, 0.92f,
			new JsonObject().put("label", "person"));
		createDetection(admin, imageAssets[2], "objectdetection", 0, 0.05f, 0.05f, 0.4f, 0.7f, 0.96f,
			new JsonObject().put("label", "building"));

		// Object detections on video assets
		createDetection(admin, videoAssets[0], "objectdetection", 30, 0.75f, 0.1f, 0.2f, 0.5f, 0.88f,
			new JsonObject().put("label", "tree"));
		createDetection(admin, videoAssets[1], "objectdetection", 60, 0.6f, 0.3f, 0.1f, 0.3f, 0.91f,
			new JsonObject().put("label", "person"));

		log.info("Created {} demo detections", 11);

		// --- Transcripts ---
		// Transcript for drone-coastal.mp4 (videoAssets[0]) — 3 sections
		createTranscript(admin, videoAssets[0], "en", "whisper-1", "asr-pipeline",
			"Welcome everyone to the quarterly update. We have a packed agenda today covering product launches, financial results, and team updates.",
			"First up, let's discuss the new product launch. The campaign alpha assets are performing exceptionally well across all channels. Social engagement is up forty percent compared to last quarter.",
			"Moving on to financials. Q1 revenue came in twelve percent above target. Our media pipeline automation reduced processing costs by nearly a third. The investment in the new encoding infrastructure is already paying dividends.");

		// Transcript for interview-clip.mov (videoAssets[2]) — 2 sections
		createTranscript(admin, videoAssets[2], "en", "whisper-1", "asr-pipeline",
			"Let's talk about the highlight reel we produced for the championship finals. The broadcast team pulled together the package in record time using our automated workflows.",
			"Finally, some team updates. We're welcoming two new members to Media Ops next week. Please make sure to update your project permissions and onboard them into the relevant pipelines.");

		// Transcript for podcast-episode1.mp3 (audioAssets[1]) — 5 sections
		createTranscript(admin, audioAssets[1], "en", "whisper-1", "asr-pipeline",
			"Welcome everyone to the quarterly update. We have a packed agenda today covering product launches, financial results, and team updates.",
			"First up, let's discuss the new product launch. The campaign alpha assets are performing exceptionally well across all channels. Social engagement is up forty percent compared to last quarter.",
			"Moving on to financials. Q1 revenue came in twelve percent above target. Our media pipeline automation reduced processing costs by nearly a third. The investment in the new encoding infrastructure is already paying dividends.",
			"Let's talk about the highlight reel we produced for the championship finals. The broadcast team pulled together the package in record time using our automated workflows.",
			"Finally, some team updates. We're welcoming two new members to Media Ops next week. Please make sure to update your project permissions and onboard them into the relevant pipelines.");

		log.info("Created {} demo transcripts", 3);

		// --- VLM (olmOCR) document transcription ---
		createVlmOlmOcrComp(admin, scanAsset);

		// --- Captioning (image + video) ---
		createImageCaptioningComp(admin, imageAssets[0]);
		createVideoCaptioningComp(admin, videoAssets[1]);

		// --- Dominant colour ---
		createDominantColorComp(admin, imageAssets[0]);

		log.info(
			"Demo data initialization complete — created {} assets ({} with previewable binaries), {} tags, {} collections, {} pipelines, {} users, "
				+ "{} groups, {} roles, {} tasks, {} skills, {} chat sessions, {} memory entries, {} annotations, {} reactions.",
			imageAssets.length + videoAssets.length + audioAssets.length + docAssets.length + 1,
			imageAssets.length + 1, 8, 2, 3, 2, 2, 2, 7, 3, 3, 3, 3, 3);
	}

	/**
	 * @param dueInDays how far out the due date sits; the task board colours overdue and near-due tasks differently, so the spread matters
	 */
	private Task createTask(User admin, String title, String description, TaskPriority priority, TaskStatus status, int dueInDays) {
		Task task = taskDao.createTask(admin.getUuid(), title);
		task.setUuid(UUIDUtils.randomUUID());
		task.setCreator(admin);
		task.setEditor(admin);
		task.setCreated(Instant.now());
		task.setEdited(Instant.now());
		task.setDescription(description);
		task.setPriority(priority);
		task.setStatus(status);
		task.setDueDate(Instant.now().plus(dueInDays, ChronoUnit.DAYS));
		taskDao.store(task);
		log.info("Created demo task: {}", title);
		return task;
	}

	/**
	 * A task about a specific asset. The link is what makes the asset detail view's Tasks tab non-empty.
	 */
	private Task createAssetTask(User admin, Asset asset, String title, String description, TaskPriority priority, TaskStatus status,
		int dueInDays) {
		Task task = createTask(admin, title, description, priority, status, dueInDays);
		taskDao.assignToAsset(task.getUuid(), asset.getUuid());
		log.info("Assigned demo task '{}' to asset {}", title, asset.getFilename());
		return task;
	}

	private Chat createDemoChat(User admin, String title, JsonArray messages) {
		Chat chat = chatDao.createChat(admin.getUuid(), title);
		chat.setUuid(UUIDUtils.randomUUID());
		chat.setMessages(messages);
		chat.setCreated(Instant.now());
		chat.setEdited(Instant.now());
		chatDao.store(chat);
		log.info("Created demo chat: {}", title);
		return chat;
	}

	/**
	 * @param admin
	 *            creator
	 * @param name
	 *            library name
	 * @param pool
	 *            storage pool binaries uploaded into this library go to, or null for the local upload directory
	 * @return the stored library
	 */
	private Library createLibrary(User admin, String name, AssetPool pool) {
		Library library = libraryDao.createLibrary(admin.getUuid(), name);
		library.setUuid(UUIDUtils.randomUUID());
		library.setPoolUuid(pool == null ? null : pool.getUuid());
		library.setCreator(admin);
		library.setEditor(admin);
		library.setCreated(Instant.now());
		library.setEdited(Instant.now());
		libraryDao.store(library);
		log.info("Created demo library: {} ({})", name, pool == null ? "local storage" : "pool " + pool.getName());
		return library;
	}

	/**
	 * A denylist entry. The message is what the agent is told on rejection, so it explains the problem and what to do instead — never echoing the match.
	 */
	private MemoryDenyRule createMemoryDenyRule(User admin, String name, String pattern, String message) {
		MemoryDenyRule rule = memoryDenyRuleDao.createMemoryDenyRule(admin.getUuid(), name, pattern, message);
		rule.setUuid(UUIDUtils.randomUUID());
		rule.setCreator(admin);
		rule.setEditor(admin);
		rule.setCreated(Instant.now());
		rule.setEdited(Instant.now());
		memoryDenyRuleDao.store(rule);
		log.info("Created demo memory deny rule: {}", name);
		return rule;
	}

	private Comment createComment(User admin, String title, String text) {
		Comment comment = commentDao.createComment(admin.getUuid(), title, text);
		comment.setUuid(UUIDUtils.randomUUID());
		comment.setCreator(admin);
		comment.setEditor(admin);
		comment.setCreated(Instant.now());
		comment.setEdited(Instant.now());
		commentDao.store(comment);
		log.info("Created demo comment: {}", title);
		return comment;
	}

	private Blacklist createBlacklist(User admin, Asset asset, String name) {
		Blacklist blacklist = blacklistDao.createBlacklist(admin.getUuid(), asset.getUuid(), name);
		blacklist.setUuid(UUIDUtils.randomUUID());
		blacklist.setCreator(admin);
		blacklist.setEditor(admin);
		blacklist.setCreated(Instant.now());
		blacklist.setEdited(Instant.now());
		blacklistDao.store(blacklist);
		log.info("Created demo blacklist entry: {}", name);
		return blacklist;
	}

	private Person createPerson(User admin, String alias, String firstname, String lastname) {
		Person person = personDao.createPerson(admin.getUuid(), alias);
		person.setUuid(UUIDUtils.randomUUID());
		person.setFirstname(firstname);
		person.setLastname(lastname);
		person.setCreator(admin);
		person.setEditor(admin);
		person.setCreated(Instant.now());
		person.setEdited(Instant.now());
		personDao.store(person);
		log.info("Created demo person: {} ({} {})", alias, firstname, lastname);
		return person;
	}

	private Cluster createCluster(User admin, String name, String type) {
		Cluster cluster = clusterDao.createCluster(admin.getUuid(), name, type);
		cluster.setUuid(UUIDUtils.randomUUID());
		cluster.setCreator(admin);
		cluster.setEditor(admin);
		cluster.setCreated(Instant.now());
		cluster.setEdited(Instant.now());
		clusterDao.store(cluster);
		log.info("Created demo cluster: {} ({})", name, type);
		return cluster;
	}

	/**
	 * {@code detection} is unique on {@code (asset_uuid, node_kind, frame_number, detection_index)}, so two detections in the same frame of the same
	 * asset must be numbered — without this the second insert aborts the whole seeding run and everything after it (transcripts, VLM component) is
	 * silently missing from the demo.
	 */
	private Detection createDetection(User admin, Asset asset, String type, int frameNumber,
		float bboxX, float bboxY, float bboxWidth, float bboxHeight, float confidence, JsonObject meta) {
		Detection detection = detectionDao.createDetection(admin.getUuid(), type);
		// Faces are what the facedetect node produces; the demo's object boxes have no node behind
		// them yet, so they keep the DAO's "manual" attribution.
		if ("facedetection".equals(type)) {
			detection.setNodeKind("facedetect");
		}
		String frameKey = asset.getUuid() + "|" + detection.getNodeKind() + "|" + frameNumber;
		detection.setDetectionIndex(detectionOrdinals.merge(frameKey, 1, Integer::sum) - 1);
		detection.setUuid(UUIDUtils.randomUUID());
		detection.setAssetUuid(asset.getUuid());
		detection.setFrameNumber(frameNumber);
		detection.setBboxX(bboxX);
		detection.setBboxY(bboxY);
		detection.setBboxWidth(bboxWidth);
		detection.setBboxHeight(bboxHeight);
		detection.setConfidence(confidence);
		detection.setMeta(meta);
		detection.setCreator(admin);
		detection.setEditor(admin);
		detection.setCreated(Instant.now());
		detection.setEdited(Instant.now());
		detectionDao.store(detection);
		log.info("Created demo detection: {} on {} (frame {})", type, asset.getFilename(), frameNumber);
		return detection;
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

	/**
	 * A skill with a v1 and a v2, mirroring what {@code SkillEndpointService} writes: the versioned body lives in {@code skill_version} rows and the
	 * skill row only points at the active one.
	 *
	 * <p>Two versions rather than one is deliberate — with a single version the version chip, the history list and the revert action in the skill UI
	 * have nothing to show.</p>
	 */
	private Skill createDemoSkill(User admin, String name, String description, String content,
		String v2Description, String v2Content, boolean published) {
		Skill skill = skillDao.createSkill(admin.getUuid(), name, description, content);
		skill.setUuid(UUIDUtils.randomUUID());
		skill.setCreator(admin);
		skill.setEditor(admin);
		skill.setCreated(Instant.now());
		skill.setEdited(Instant.now());
		skill.setEnabled(true);
		skill.setPublished(published);
		skill.setMeta(new JsonObject());
		skillDao.store(skill);

		appendSkillVersion(admin, skill, 1, description, content);
		appendSkillVersion(admin, skill, 2, v2Description, v2Content);
		skillDao.update(skill);

		log.info("Created demo skill: {} (2 versions, published={})", name, published);
		return skill;
	}

	private void appendSkillVersion(User admin, Skill skill, int versionNumber, String description, String content) {
		SkillVersion version = skillVersionDao.createVersion(admin.getUuid(), skill.getUuid(), versionNumber, description, content, skill.getMeta());
		version.setUuid(UUIDUtils.randomUUID());
		version.setCreator(admin);
		version.setEditor(admin);
		version.setCreated(Instant.now());
		version.setEdited(Instant.now());
		skillVersionDao.store(version);
		skill.setActiveVersionUuid(version.getUuid());
		skill.setActiveVersionNumber(versionNumber);
		skill.setDescription(description);
		skill.setContent(content);
	}

	private ChatSession createDemoChatSession(User admin, Chat chat, String name, String description, String[] tags, boolean published) {
		ChatSession session = chatSessionDao.createChatSession(admin.getUuid(), name, description);
		session.setUuid(UUIDUtils.randomUUID());
		session.setChatUuid(chat.getUuid());
		session.setTags(tags);
		session.setPublished(published);
		session.setCreator(admin);
		session.setEditor(admin);
		session.setCreated(Instant.now());
		session.setEdited(Instant.now());
		chatSessionDao.store(session);
		log.info("Created demo chat session: {} (published={})", name, published);
		return session;
	}

	/**
	 * A note in the agent's memory bank. The frontmatter is never stored — it is rendered from the row — so this writes only body, title and the
	 * derived size/digest, exactly as {@code MemoryService.put} does.
	 */
	private MemoryEntry createMemoryEntry(User admin, String memoryId, String title, String body) {
		MemoryEntry entry = memoryEntryDao.createMemoryEntry(admin.getUuid(), MemoryScope.USER, admin.getUuid(), memoryId);
		entry.setUuid(UUIDUtils.randomUUID());
		entry.setTitle(title);
		entry.setBody(body);
		entry.setSize(body.getBytes(StandardCharsets.UTF_8).length);
		entry.setVersion(1);
		entry.setCreator(admin);
		entry.setEditor(admin);
		entry.setCreated(Instant.now());
		entry.setEdited(Instant.now());
		entry.setMeta(new JsonObject());
		entry.setSha256(sha256Hex(MemoryHeader.renderFile(entry, admin.getUsername())));
		memoryEntryDao.store(entry);
		log.info("Created demo memory entry: {}", memoryId);
		return entry;
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
		pipeline.setMeta(new JsonObject());
		pipelineDao.store(pipeline);

		// Create v1 version in pipeline_version table
		PipelineVersion version = pipelineVersionDao.createVersion(
			admin.getUuid(),
			pipeline.getUuid(),
			1,
			name,
			description,
			// Stamped exactly as the REST create path does, so demo data is not the one
			// corner of the system that stores definitions without naming their format.
			io.metaloom.loom.pipeline.graph.PipelineGraphParser.stampVersion(definition),
			enabled,
			priority,
			dryRun,
			new JsonObject()
		);
		pipelineVersionDao.store(version);

		// Update pipeline with latest version reference
		pipeline.setLatestVersionUuid(version.getUuid());
		pipelineDao.update(pipeline);

		log.info("Created demo pipeline: {}", name);
		return pipeline;
	}

	/**
	 * Give a demo pipeline some run history.
	 *
	 * <p>Without this a fresh demo shows an empty run list and a flat statistics chart, which
	 * makes the run views look broken rather than merely unused. The spread of statuses is
	 * deliberate: one of each so the status colouring, the failure counters and the daily
	 * chart all have something to render.</p>
	 *
	 * @param daysAgo how far back the run started; the statistics endpoint reports a
	 *                two-week window by default, so these stay inside it
	 */
	private PipelineRun createPipelineRun(User admin, Pipeline pipeline, String status, int daysAgo,
		int mediaCount, int successCount, int failureCount, int skippedCount, Long durationMs) {

		PipelineRun run = pipelineRunDao.createPipelineRun(admin.getUuid(), pipeline.getUuid(), 1);
		run.setUuid(UUIDUtils.randomUUID());
		Instant started = Instant.now().minus(daysAgo, java.time.temporal.ChronoUnit.DAYS);
		run.setStarted(started);
		run.setStatus(status);
		run.setMediaCount(mediaCount);
		run.setSuccessCount(successCount);
		run.setFailureCount(failureCount);
		run.setSkippedCount(skippedCount);
		run.setDryRun(false);
		run.setMeta(new JsonObject());
		// A run that is still live has no end: leaving finished/duration unset is what
		// distinguishes RUNNING and PAUSED from a completed run.
		if (durationMs != null) {
			run.setDurationMs(durationMs);
			run.setFinished(started.plusMillis(durationMs));
		}
		if ("FAILED".equals(status)) {
			run.setErrorMessage("Node 'facedetect' failed: model file not found");
		}
		pipelineRunDao.store(run);
		return run;
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

	/**
	 * A node carrying per-instance options. {@code options} is the key Loom's graph parser reads and
	 * forwards to the worker - the {@code data} bag above is in-editor state only.
	 */
	private static JsonObject node(String id, String type, String label, String description, int x, int y, JsonObject options) {
		return node(id, type, label, description, x, y).put("options", options);
	}


	/**
	 * The demo pipeline definitions, as static methods so they can be validated without a database.
	 *
	 * <p>
	 * {@code DemoPipelineDefinitionTest} parses each one through the real {@link
	 * io.metaloom.loom.pipeline.graph.PipelineGraphParser} against the real descriptor registry. That
	 * guard matters: before node kinds and ports were checked, one of these graphs referenced three
	 * kinds that had never existed and nobody noticed, because a definition nothing validates looks
	 * exactly like one that works.
	 * </p>
	 */
	static JsonObject simpleDefinition() {
		return new JsonObject()
				.put("nodes", new JsonArray()
					.add(node("pn1", "filesystem-source", "File Source", "Watch local folder", 60, 120))
					.add(node("pn2", "sha256", "SHA-256 Hash", "Compute SHA-256 digest", 300, 120)))
				.put("edges", new JsonArray()
					.add(edge("pe1", "pn1", "media", "pn2", "media")));
	}

	static JsonObject mediumDefinition() {
		return new JsonObject()
				.put("nodes", new JsonArray()
					.add(node("pn1", "filesystem-source", "File Source", "Watch ingest folder", 60, 160))
					.add(node("pn2", "filter-mimetype", "MIME Filter", "Accept video and image types", 260, 160))
					.add(node("pn3", "sha256", "SHA-256 Hash", "Compute hash", 460, 60))
					.add(node("pn4", "fingerprint", "Fingerprint", "Audio/video fingerprint", 460, 260)))
				.put("edges", new JsonArray()
					.add(edge("pe1", "pn1", "media", "pn2", "media"))
					.add(edge("pe2", "pn2", "media", "pn3", "media"))
					.add(edge("pe3", "pn2", "media", "pn4", "media")));
	}

	static JsonObject complexDefinition() {
		return new JsonObject()
				.put("nodes", new JsonArray()
					.add(node("pn1", "filesystem-source", "File Source", "Watch production folder", 60, 200))
					.add(node("pn2", "filter-mimetype", "MIME Filter", "Accept media types", 240, 200))
					.add(node("pn3", "sha256", "SHA-256 Hash", "Compute SHA-256", 440, 40))
					.add(node("pn4", "fingerprint", "Fingerprint", "Video fingerprint", 440, 150))
					.add(node("pn5", "facedetect", "Face Detection", "Detect faces with InspireFace", 440, 270))
					.add(node("pn6", "facedescription", "Face Description", "Describe each detected face", 680, 270))
					.add(node("pn8", "thumbnail", "Thumbnail", "Generate a contact sheet", 440, 390))
					.add(node("pn9", "s3-sink", "S3 Delivery", "Upload the contact sheet", 680, 390,
						new JsonObject().put("bucket", "media"))))
				.put("edges", new JsonArray()
					.add(edge("pe1", "pn1", "media", "pn2", "media"))
					.add(edge("pe2", "pn2", "media", "pn3", "media"))
					.add(edge("pe3", "pn2", "media", "pn4", "media"))
					.add(edge("pe4", "pn2", "media", "pn5", "image"))
					.add(edge("pe5", "pn5", "detections", "pn6", "detections"))
					.add(edge("pe6", "pn2", "media", "pn8", "media"))
					.add(edge("pe7", "pn8", "thumbnail", "pn9", "artifacts")));
	}

	static JsonObject s3IngestDefinition() {
		return new JsonObject()
				.put("nodes", new JsonArray()
					.add(node("pn1", "s3-source", "S3 Source", "Pick up new objects from the media bucket", 60, 160,
						new JsonObject()
							.put("bucket", "media")
							.put("prefix", "incoming/")
							.put("suffixes", "mp4,mkv,mov,jpg,jpeg,png")
							.put("emitStates", new JsonArray().add("NEW").add("MODIFIED"))))
					.add(node("pn2", "sha512", "SHA-512 Hash", "Compute the content identity", 300, 160)))
				.put("edges", new JsonArray()
					.add(edge("pe1", "pn1", "media", "pn2", "media")));
	}

	static JsonObject s3PublishDefinition() {
		return new JsonObject()
				.put("nodes", new JsonArray()
					.add(node("pn1", "filesystem-source", "File Source", "Watch the media folder", 60, 160))
					.add(node("pn2", "sha512", "SHA-512 Hash", "Content identity, required by the key template", 260, 160))
					.add(node("pn3", "thumbnail", "Thumbnail", "Generate a contact sheet", 460, 160))
					.add(node("pn4", "s3-sink", "S3 Publish", "Upload the contact sheet and register it", 680, 160,
						new JsonObject()
							.put("bucket", "media")
							.put("createAssets", true))))
				.put("edges", new JsonArray()
					.add(edge("pe1", "pn1", "media", "pn2", "media"))
					.add(edge("pe2", "pn1", "media", "pn3", "media"))
					.add(edge("pe3", "pn3", "thumbnail", "pn4", "artifacts")));
	}

	/**
	 * Speech to text: only audio and video reach Whisper, whose transcript is then scored for sentiment.
	 *
	 * <p>
	 * The MIME filter is what makes this safe to point at a mixed folder — {@code whisper} declares an
	 * XOR over its audio and video inputs, so a still image arriving on the media port would have
	 * nothing to bind to.
	 * </p>
	 */
	static JsonObject transcriptionDefinition() {
		return new JsonObject()
				.put("nodes", new JsonArray()
					.add(node("pn1", "filesystem-source", "Media Source", "Watch the recordings folder", 60, 160))
					.add(node("pn2", "filter-mimetype", "Audio/Video Filter", "Accept audio and video only", 260, 160,
						new JsonObject().put("mimeTypes", "audio/*,video/*")))
					.add(node("pn3", "whisper", "Transcribe", "Speech to text with Whisper", 480, 160))
					.add(node("pn4", "sentiment", "Transcript Sentiment", "Score the tone of the transcript", 700, 160)))
				.put("edges", new JsonArray()
					.add(edge("pe1", "pn1", "media", "pn2", "media"))
					.add(edge("pe2", "pn2", "media", "pn3", "video"))
					.add(edge("pe3", "pn3", "transcript", "pn4", "text")));
	}

	static JsonObject scriptDefinition() {
		return new JsonObject()
				.put("nodes", new JsonArray()
					.add(node("pn1", "filesystem-source", "File Source", "Watch documents folder", 60, 160))
					.add(node("pn2", "tika", "Tika", "Extract document text", 280, 160))
					.add(node("pn3", "script", "Reading Time", "Derive reading time from the extracted text", 520, 160,
						new JsonObject()
							.put("engine", "js")
							.put("script", DEMO_SCRIPT)
							.put("params", new JsonObject().put("wordsPerMinute", 200))
							.put("outputs", new JsonArray()
								.add(new JsonObject().put("key", "reading_minutes").put("type", "INTEGER"))
								.add(new JsonObject().put("key", "length_band").put("type", "STRING"))))))
				.put("edges", new JsonArray()
					.add(edge("pe1", "pn1", "media", "pn2", "media"))
					.add(edge("pe2", "pn2", "content", "pn3", "text")));
	}

	/**
	 * An edge from one node's output port to another node's input port.
	 *
	 * <p>
	 * Ports are mandatory: a graph says which <em>value</em> flows where, not merely which nodes are
	 * adjacent. Two nodes may be joined by several edges carrying different data, so the node pair
	 * alone no longer identifies an edge.
	 * </p>
	 */
	private static JsonObject edge(String id, String source, String sourcePort, String target, String targetPort) {
		return new JsonObject()
			.put("id", id)
			.put("source", source)
			.put("sourcePort", sourcePort)
			.put("target", target)
			.put("targetPort", targetPort);
	}

	private Token createDemoToken(User admin, String name, String tokenValue) {
		Token token = tokenDao.createToken(admin.getUuid(), name, tokenValue);
		token.setUuid(UUIDUtils.randomUUID());
		token.setCreator(admin);
		token.setEditor(admin);
		token.setCreated(Instant.now());
		token.setEdited(Instant.now());
		tokenDao.store(token);
		log.info("Created demo token: {}", name);
		return token;
	}

	/**
	 * Build a word-level JSON structure from a text and a starting time offset.
	 * Returns a JsonObject with "words" (array) and "endTime" (number).
	 */
	private static JsonObject buildWords(String text, double startTime) {
		String[] tokens = text.split("\\s+");
		JsonArray words = new JsonArray();
		double t = startTime;
		for (String w : tokens) {
			double dur = 0.25;
			double gap = 0.1;
			double end = Math.round((t + dur) * 100.0) / 100.0;
			words.add(new JsonObject()
				.put("word", w)
				.put("startTime", Math.round(t * 100.0) / 100.0)
				.put("endTime", end)
				.put("confidence", 0.92));
			t = end + gap;
		}
		return new JsonObject()
			.put("words", words)
			.put("endTime", Math.round(t * 100.0) / 100.0);
	}

	/**
	 * Create a transcript component with word-level JSON sections for an asset.
	 */
	private void createTranscript(User admin, Asset asset, String lang, String model, String source, String... sectionTexts) {
		String[] sectionTitles = { "Introduction", "Product Launch Update", "Financial Results", "Broadcast Highlights", "Team Updates" };

		JsonArray sections = new JsonArray();
		double cursor = 0;
		StringBuilder fullText = new StringBuilder();
		for (int i = 0; i < sectionTexts.length; i++) {
			JsonObject wResult = buildWords(sectionTexts[i], cursor);
			double endTime = wResult.getDouble("endTime");
			sections.add(new JsonObject()
				.put("id", "ts" + (i + 1))
				.put("title", sectionTitles[i % sectionTitles.length])
				.put("startTime", cursor)
				.put("endTime", endTime)
				.put("words", wResult.getJsonArray("words")));
			if (fullText.length() > 0) {
				fullText.append(" ");
			}
			fullText.append(sectionTexts[i]);
			cursor = endTime + 0.5;
		}

		// "source" is the producing node kind; the section timings above are seconds, the
		// duration column is milliseconds.
		AssetTranscriptComp comp = assetComponentDao.createTranscriptComp(admin.getUuid(), asset.getUuid(), source);
		comp.setLang(lang);
		comp.setModel(model);
		comp.setProducerVersion(model);
		comp.setTranscriptText(fullText.toString());
		comp.setDuration((long) Math.ceil(cursor * 1000));
		comp.setTranscriptJson(new JsonObject().put("sections", sections));
		assetComponentDao.upsertTranscriptComp(comp);
		log.info("Created demo transcript for asset: {} ({} sections)", asset.getFilename(), sectionTexts.length);
	}

	/**
	 * Create the JSON component a {@code vlm} node writes for the olmOCR preset: the transcribed page plus what the model observed about it. Keys mirror
	 * the olmOCR front matter names, so the demo row has the same shape a real run produces.
	 */
	private void createVlmOlmOcrComp(User admin, Asset asset) {
		String page = """
			# ACME Media Services

			**Invoice** 2026-0142  ·  **Date** 2026-03-14

			<table>
			<tr><th>Description</th><th>Qty</th><th>Amount</th></tr>
			<tr><td>Archive ingest &amp; transcoding</td><td>1</td><td>4,200.00</td></tr>
			<tr><td>Automated metadata enrichment</td><td>1</td><td>1,850.00</td></tr>
			<tr><td>Storage (12 months)</td><td>1</td><td>960.00</td></tr>
			</table>

			**Total due:** 7,010.00 EUR — payable within 30 days.
			""";

		AssetJsonComp comp = assetComponentDao.createJsonComp(admin.getUuid(), asset.getUuid(), "vlm");
		comp.setSchemaType("vlm");
		comp.setVariant("olmocr");
		comp.setProducerVersion("allenai/olmOCR-2-7B-1025-FP8");
		comp.setData(new JsonObject()
			.put("primary_language", "en")
			.put("is_rotation_valid", true)
			.put("rotation_correction", 0)
			.put("is_table", true)
			.put("is_diagram", false)
			.put("natural_text", page)
			.put("truncated", false));
		assetComponentDao.upsertJsonComp(comp);
		log.info("Created demo vlm/olmocr component for asset: {}", asset.getFilename());
	}

	/**
	 * Create the JSON component a {@code dominant-color} node writes: the palette of the whole frame plus one entry per detected face. Shape mirrors a real
	 * run ({@code schemaType=dominant-color}, one row per asset with every region inside {@code data.regions}).
	 *
	 * <p>The colours match the caption seeded for the same asset - a sunset over hills - so the demo reads coherently: a vivid orange dominant with a violet
	 * sky behind it.
	 */
	private void createDominantColorComp(User admin, Asset asset) {
		AssetJsonComp comp = assetComponentDao.createJsonComp(admin.getUuid(), asset.getUuid(), "dominant-color");
		comp.setSchemaType("dominant-color");
		comp.setVariant("");
		comp.setProducerVersion("dominant-color/1");
		comp.setData(new JsonObject()
			.put("image", new JsonObject().put("width", 1920).put("height", 1080))
			.put("sampling", new JsonObject().put("maxSamples", 40000).put("clusterCount", 5).put("seed", 42).put("alphaThreshold", 128))
			.put("regions", new JsonArray()
				.add(new JsonObject()
					.put("id", "whole")
					.put("source", "image")
					.put("kind", "IMAGE")
					.put("bbox", new JsonObject().put("x", 0).put("y", 0).put("w", 1920).put("h", 1080))
					.put("pixels", 39204)
					.put("converged", true)
					.put("dominant", demoColor(0.4712d, "#E2711D", 226, 113, 29, 30.5d, 77.3d, 50.0d,
						60.13d, 38.21d, 62.35d, 73.13d, 58.5d, "orange", "MEDIUM", "STRONG", "orange", "Orange", 3.91d))
					.put("palette", new JsonArray()
						.add(demoColor(0.4712d, "#E2711D", 226, 113, 29, 30.5d, 77.3d, 50.0d,
							60.13d, 38.21d, 62.35d, 73.13d, 58.5d, "orange", "MEDIUM", "STRONG", "orange", "Orange", 3.91d))
						.add(demoColor(0.3105d, "#6B4E8C", 107, 78, 140, 268.7d, 28.4d, 42.7d,
							37.94d, 25.11d, -28.63d, 38.08d, 311.3d, "purple", "DARK", "MUTED", "dark purple", "dunkles Violett", 5.02d))
						.add(demoColor(0.2183d, "#F2C57C", 242, 197, 124, 37.1d, 81.6d, 71.8d,
							82.05d, 6.94d, 41.22d, 41.80d, 80.4d, "yellow", "LIGHT", "MUTED", "light yellow", "helles Gelb", 9.44d))))
				.add(new JsonObject()
					.put("id", "face-0")
					.put("source", "facedetect")
					.put("kind", "DETECTION")
					.put("label", "face")
					.put("type", "face")
					.put("frame", 0)
					.put("confidence", 0.94d)
					.put("bbox", new JsonObject().put("x", 612).put("y", 288).put("w", 216).put("h", 216))
					.put("pixels", 6400)
					.put("converged", true)
					.put("dominant", demoColor(0.6418d, "#C68642", 198, 134, 66, 30.9d, 50.0d, 51.8d,
						61.02d, 15.36d, 39.85d, 42.71d, 68.9d, "brown", "MEDIUM", "MUTED", "muted brown", "gedämpftes Braun", 7.68d))
					.put("palette", new JsonArray()
						.add(demoColor(0.6418d, "#C68642", 198, 134, 66, 30.9d, 50.0d, 51.8d,
							61.02d, 15.36d, 39.85d, 42.71d, 68.9d, "brown", "MEDIUM", "MUTED", "muted brown", "gedämpftes Braun", 7.68d))
						.add(demoColor(0.3582d, "#3A2A1E", 58, 42, 30, 25.7d, 31.8d, 17.3d,
							19.71d, 6.12d, 9.84d, 11.59d, 58.1d, "brown", "VERY_DARK", "ACHROMATIC", "black", "Schwarz", 0d)))))
			.put("truncated", new JsonObject().put("regions", 0).put("dropped", 0)));
		assetComponentDao.upsertJsonComp(comp);
		log.info("Created demo dominant-color component for asset: {}", asset.getFilename());
	}

	/** One palette entry in the shape the dominant-color node emits. */
	private static JsonObject demoColor(double share, String hex, int r, int g, int b, double h, double s, double l,
		double labL, double labA, double labB, double chroma, double hue,
		String term, String lightness, String chromaBand, String en, String de, double distance) {
		return new JsonObject()
			.put("share", share)
			.put("hex", hex)
			.put("rgb", new JsonObject().put("r", r).put("g", g).put("b", b))
			.put("hsl", new JsonObject().put("h", h).put("s", s).put("l", l))
			.put("lab", new JsonObject().put("l", labL).put("a", labA).put("b", labB))
			.put("lch", new JsonObject().put("c", chroma).put("h", hue))
			.put("name", new JsonObject()
				.put("term", term)
				.put("lightness", lightness)
				.put("chroma", chromaBand)
				.put("en", en)
				.put("de", de)
				.put("distance", distance));
	}

	/**
	 * Create the JSON component a {@code captioning} node writes for an image: a natural-language caption of the still frame. Shape mirrors a real run
	 * ({@code schemaType=caption}, {@code data.caption}).
	 */
	private void createImageCaptioningComp(User admin, Asset asset) {
		AssetJsonComp comp = assetComponentDao.createJsonComp(admin.getUuid(), asset.getUuid(), "captioning");
		comp.setSchemaType("caption");
		comp.setVariant("");
		comp.setProducerVersion("SmolVLM");
		comp.setData(new JsonObject()
			.put("caption", "A warm sunset over rolling hills, the sky washed in orange and violet."));
		assetComponentDao.upsertJsonComp(comp);
		log.info("Created demo captioning/caption component for asset: {}", asset.getFilename());
	}

	/**
	 * Create the JSON component a {@code captioning} node writes for a video: a natural-language description of the clip plus a per-scene timeline. Shape
	 * mirrors a real scene-strategy run ({@code schemaType=video-caption}; {@code data} carries {@code variant}, {@code model}, {@code frameCount} and a
	 * {@code scenes} array).
	 */
	private void createVideoCaptioningComp(User admin, Asset asset) {
		JsonArray scenes = new JsonArray()
			.add(new JsonObject().put("seq", 0).put("fromFrame", 0).put("toFrame", 120)
				.put("caption", "An aerial shot sweeps along a rugged coastline as waves break on the rocks below."))
			.add(new JsonObject().put("seq", 1).put("fromFrame", 121).put("toFrame", 260)
				.put("caption", "The camera turns inland over green cliffs dotted with grazing sheep."));

		AssetJsonComp comp = assetComponentDao.createJsonComp(admin.getUuid(), asset.getUuid(), "captioning");
		comp.setSchemaType("video-caption");
		comp.setVariant("");
		comp.setProducerVersion("qwen25vl-awq");
		comp.setData(new JsonObject()
			.put("caption",
				"Scene 1 [frames 0-120]: An aerial shot sweeps along a rugged coastline as waves break on the rocks below.\n"
					+ "Scene 2 [frames 121-260]: The camera turns inland over green cliffs dotted with grazing sheep.")
			.put("variant", "scene")
			.put("model", "qwen25vl-awq")
			.put("frameCount", 6)
			.put("scenes", scenes));
		assetComponentDao.upsertJsonComp(comp);
		log.info("Created demo captioning/video-caption component for asset: {}", asset.getFilename());
	}

	// -- demo image binaries -------------------------------------------------
	//
	// The asset browser renders a preview from GET /assets/:uuid/binary/data, which needs real
	// bytes on disk and an asset_location row pointing at them. Without those every demo asset
	// falls back to a type placeholder icon, which is what "no thumbnails are displayed" means.
	// The images are synthesised rather than shipped so the repository carries no binary blobs
	// and the demo never claims to show photography it does not have.

	/**
	 * How one demo image is painted. Deterministic: same palette in, same bytes out, so a re-run stores the same content-addressed file.
	 */
	private enum Palette {

		SUNSET(1600, 1067, 0x2A1B4D, 0xF2704B, 0xFFC98B, 0x2A1B4D, Style.HILLS, 11),
		LAKE(1600, 1067, 0x0B2E4A, 0x7FC6D9, 0xE8F4F8, 0x14324D, Style.PEAKS, 23),
		CITY(1600, 1067, 0x1B1035, 0x6C4BA6, 0xFFD37A, 0x120A26, Style.SKYLINE, 37),
		STUDIO(1067, 1067, 0x1C1C22, 0x3A3A46, 0xD9C3A5, 0x1C1C22, Style.PORTRAIT, 41),
		FOREST(1600, 1067, 0x0E2A1B, 0x4E8C4A, 0xBFD98C, 0x0E2A1B, Style.FOLIAGE, 53),
		AUTUMN(1600, 1067, 0x3B1F0B, 0xC9702A, 0xF2C46B, 0x3B1F0B, Style.FOLIAGE, 67),
		// Snow-lit peaks: the ridges start near-white and darken layer by layer, so this reads as
		// a different scene from LAKE rather than a recolour of the same one.
		SNOW(1600, 1067, 0x22384F, 0x9FBBD1, 0xFFFFFF, 0xE4EDF4, Style.PEAKS, 71),
		SCAN(1240, 1754, 0xF4F1EA, 0xFFFFFF, 0x8A8577, 0xF4F1EA, Style.DOCUMENT, 89);

		private enum Style {
			HILLS, PEAKS, SKYLINE, PORTRAIT, FOLIAGE, DOCUMENT
		}

		private final int width;
		private final int height;
		private final int top;
		private final int bottom;
		private final int accent;
		/** Base colour of the silhouette layers, darkened per layer. */
		private final int ridge;
		private final Style style;
		private final long seed;

		Palette(int width, int height, int top, int bottom, int accent, int ridge, Style style, long seed) {
			this.width = width;
			this.height = height;
			this.top = top;
			this.bottom = bottom;
			this.accent = accent;
			this.ridge = ridge;
			this.style = style;
			this.seed = seed;
		}
	}

	/**
	 * Create an asset backed by real bytes: paint the image, store it content-addressed under the configured upload directory, and record the
	 * {@code asset_location} row the download endpoint resolves.
	 *
	 * <p>The asset's sha512 and size are the ones of the stored file — a demo asset whose hash did not match its bytes would break the very dedupe
	 * story the product is built on.</p>
	 */
	private Asset createImageAsset(User admin, Library library, String filename, String mimeType, String origin, Palette palette) {
		byte[] bytes = renderDemoImage(palette, mimeType);
		SHA512 sha512 = SHA512.fromString(hex(digest("SHA-512", bytes)));

		Asset asset = assetDao.createAsset(admin, sha512, mimeType, filename, origin, bytes.length);
		asset.setUuid(UUIDUtils.randomUUID());
		asset.setCreator(admin);
		asset.setEditor(admin);
		asset.setCreated(Instant.now());
		asset.setEdited(Instant.now());
		asset.setFirstSeen(Instant.now());
		assetDao.store(asset);

		String path = storeBinary(bytes, sha512);
		if (path != null) {
			AssetBinary binary = assetBinaryDao.createAssetBinary(path, asset.getUuid(), admin.getUuid(), library.getUuid());
			binary.setUuid(UUIDUtils.randomUUID());
			binary.setMimeType(mimeType);
			binary.setCreator(admin);
			binary.setEditor(admin);
			binary.setCreated(Instant.now());
			binary.setEdited(Instant.now());
			assetBinaryDao.store(binary);
		}
		log.info("Created demo asset with binary: {} ({} bytes)", filename, bytes.length);
		return asset;
	}

	/**
	 * Write the bytes into the upload directory using the same {@code ab/cd/ef/<sha512>} layout the upload endpoint uses.
	 *
	 * @return the stored path, or null when the directory is not writable — the asset is still created, it just has no preview
	 */
	private String storeBinary(byte[] bytes, SHA512 sha512) {
		String hex = sha512.toString();
		Path dir = Paths.get(options.getStorage().getUploadDirectory(), hex.substring(0, 2), hex.substring(2, 4), hex.substring(4, 6));
		Path target = dir.resolve(hex);
		try {
			Files.createDirectories(dir);
			if (!Files.exists(target)) {
				Files.write(target, bytes);
			}
			return target.toString();
		} catch (IOException e) {
			log.warn("Could not store demo binary at {} — the asset will have no preview", target, e);
			return null;
		}
	}

	/**
	 * Paint one demo image. No text is drawn: the JRE in the demo container has no fontconfig, and a missing font would fail the whole seeding run.
	 */
	private static byte[] renderDemoImage(Palette palette, String mimeType) {
		int w = palette.width;
		int h = palette.height;
		BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

		Color top = new Color(palette.top);
		Color bottom = new Color(palette.bottom);
		Color accent = new Color(palette.accent);
		Color ridge = new Color(palette.ridge);
		Random rnd = new Random(palette.seed);

		g.setPaint(new GradientPaint(0, 0, top, 0, h, bottom));
		g.fillRect(0, 0, w, h);

		switch (palette.style) {
		case HILLS -> {
			g.setColor(accent);
			int r = (int) (h * 0.22);
			g.fill(new Ellipse2D.Double(w * 0.66, h * 0.18, r, r));
			paintRidges(g, w, h, 3, 0.55, 0.06, ridge, rnd);
		}
		case PEAKS -> {
			g.setColor(accent);
			int r = (int) (h * 0.14);
			g.fill(new Ellipse2D.Double(w * 0.16, h * 0.12, r, r));
			paintRidges(g, w, h, 4, 0.45, 0.22, ridge, rnd);
		}
		case SKYLINE -> {
			g.setColor(accent);
			int r = (int) (h * 0.12);
			g.fill(new Ellipse2D.Double(w * 0.74, h * 0.14, r, r));
			paintSkyline(g, w, h, ridge, rnd);
		}
		case PORTRAIT -> paintPortrait(g, w, h, accent, ridge);
		case FOLIAGE -> paintFoliage(g, w, h, accent, ridge, rnd);
		case DOCUMENT -> paintDocument(g, w, h, accent);
		}

		g.dispose();

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			String format = "image/png".equals(mimeType) ? "png" : "jpg";
			ImageIO.write(image, format, out);
		} catch (IOException e) {
			// An in-memory write cannot fail for a supported format; treat it as a bug rather than a seeding hiccup.
			throw new IllegalStateException("Could not encode the demo image as " + mimeType, e);
		}
		return out.toByteArray();
	}

	/** Stacked silhouette ridges — gentle for hills, jagged for peaks. */
	private static void paintRidges(Graphics2D g, int w, int h, int layers, double baseline, double jaggedness, Color shade, Random rnd) {
		for (int layer = 0; layer < layers; layer++) {
			double y = h * (baseline + layer * 0.13);
			GeneralPath path = new GeneralPath();
			path.moveTo(0, h);
			path.lineTo(0, y);
			int steps = 8 + layer * 2;
			for (int i = 1; i <= steps; i++) {
				double x = w * i / (double) steps;
				double peak = y - h * jaggedness * rnd.nextDouble() - h * 0.02;
				path.lineTo(x, peak);
			}
			path.lineTo(w, h);
			path.closePath();
			g.setColor(blend(shade, Color.BLACK, 0.12 + layer * 0.16));
			g.fill(path);
		}
	}

	private static void paintSkyline(Graphics2D g, int w, int h, Color shade, Random rnd) {
		for (int layer = 0; layer < 3; layer++) {
			g.setColor(blend(shade, Color.BLACK, 0.2 + layer * 0.22));
			double base = h * (0.62 + layer * 0.11);
			int x = -(int) (w * 0.02);
			while (x < w) {
				int bw = (int) (w * (0.03 + rnd.nextDouble() * 0.05));
				int bh = (int) (h * (0.08 + rnd.nextDouble() * (0.3 - layer * 0.07)));
				g.fillRect(x, (int) base - bh, bw, (int) (h - base + bh));
				x += bw + (int) (w * 0.008);
			}
		}
	}

	/** A lit backdrop with a soft key light and a centred subject silhouette. */
	private static void paintPortrait(Graphics2D g, int w, int h, Color accent, Color shade) {
		g.setPaint(new GradientPaint(w * 0.3f, 0, blend(accent, shade, 0.55), w, h, shade));
		g.fillRect(0, 0, w, h);
		g.setColor(blend(shade, Color.BLACK, 0.45));
		g.fill(new Ellipse2D.Double(w * 0.28, h * 0.52, w * 0.44, h * 0.66));
		g.setColor(blend(shade, Color.BLACK, 0.35));
		g.fill(new Ellipse2D.Double(w * 0.38, h * 0.26, w * 0.24, h * 0.3));
	}

	private static void paintFoliage(Graphics2D g, int w, int h, Color accent, Color shade, Random rnd) {
		g.setColor(blend(shade, Color.BLACK, 0.35));
		g.fillRect(0, (int) (h * 0.72), w, (int) (h * 0.28));
		for (int i = 0; i < 90; i++) {
			double size = h * (0.03 + rnd.nextDouble() * 0.09);
			double x = rnd.nextDouble() * w;
			double y = h * 0.1 + rnd.nextDouble() * h * 0.8;
			g.setColor(blend(accent, shade, rnd.nextDouble()));
			g.fill(new Ellipse2D.Double(x, y, size * 1.4, size));
		}
		g.setStroke(new BasicStroke((float) (w * 0.012)));
		g.setColor(blend(shade, Color.BLACK, 0.5));
		for (int i = 0; i < 4; i++) {
			int x = (int) (w * (0.12 + i * 0.25));
			g.drawLine(x, (int) (h * 0.2), x + (int) (w * 0.02), h);
		}
	}

	/** A scanned page: a paper field with grey bars standing in for text and a boxed table. */
	private static void paintDocument(Graphics2D g, int w, int h, Color ink) {
		int margin = (int) (w * 0.1);
		int y = (int) (h * 0.12);
		g.setColor(blend(ink, Color.BLACK, 0.25));
		g.fillRect(margin, y, (int) (w * 0.42), (int) (h * 0.018));
		y += (int) (h * 0.06);
		g.setColor(blend(ink, Color.WHITE, 0.35));
		for (int i = 0; i < 4; i++) {
			g.fillRect(margin, y, (int) (w * (0.5 + (i % 3) * 0.1)), (int) (h * 0.009));
			y += (int) (h * 0.022);
		}
		y += (int) (h * 0.03);
		int rows = 5;
		int rowH = (int) (h * 0.035);
		g.setStroke(new BasicStroke(Math.max(1f, w * 0.0015f)));
		for (int r = 0; r < rows; r++) {
			g.setColor(r == 0 ? blend(ink, Color.BLACK, 0.15) : blend(ink, Color.WHITE, 0.55));
			g.fillRect(margin, y + r * rowH, w - 2 * margin, rowH);
			g.setColor(blend(ink, Color.WHITE, 0.2));
			g.drawRect(margin, y + r * rowH, w - 2 * margin, rowH);
		}
		y += rows * rowH + (int) (h * 0.05);
		g.setColor(blend(ink, Color.BLACK, 0.25));
		g.fillRect(margin, y, (int) (w * 0.34), (int) (h * 0.014));
	}

	private static Color blend(Color color, Color into, double amount) {
		double a = Math.max(0, Math.min(1, amount));
		return new Color(
			(int) (color.getRed() * (1 - a) + into.getRed() * a),
			(int) (color.getGreen() * (1 - a) + into.getGreen() * a),
			(int) (color.getBlue() * (1 - a) + into.getBlue() * a));
	}

	private static String sha256Hex(String text) {
		return hex(digest("SHA-256", text.getBytes(StandardCharsets.UTF_8)));
	}

	private static byte[] digest(String algorithm, byte[] data) {
		try {
			return MessageDigest.getInstance(algorithm).digest(data);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(algorithm + " is required by every JRE", e);
		}
	}

	private static String hex(byte[] bytes) {
		StringBuilder builder = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			builder.append(Character.forDigit((b >> 4) & 0xF, 16));
			builder.append(Character.forDigit(b & 0xF, 16));
		}
		return builder.toString();
	}
}
