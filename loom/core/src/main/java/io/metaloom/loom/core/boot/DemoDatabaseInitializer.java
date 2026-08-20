package io.metaloom.loom.core.boot;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
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

import io.metaloom.loom.api.pipeline.PipelineRunStatus;
import io.metaloom.loom.api.uuid.LoomUUID;
import io.metaloom.loom.agent.memory.MemoryHeader;
import io.metaloom.loom.api.memory.MemoryScope;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.api.options.SimilarityOptions;
import io.metaloom.loom.api.reaction.ReactionType;
import io.metaloom.loom.api.task.TaskPriority;
import io.metaloom.loom.api.task.TaskStatus;
import io.metaloom.loom.db.model.annotation.Annotation;
import io.metaloom.loom.db.model.annotation.AnnotationDao;
import io.metaloom.loom.api.annotation.AnnotationType;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetBinary;
import io.metaloom.loom.db.model.asset.AssetBinaryDao;
import io.metaloom.loom.db.model.asset.AssetComponentDao;
import io.metaloom.loom.db.model.asset.AssetDao;
import io.metaloom.loom.db.model.asset.AssetFingerprintComp;
import io.metaloom.loom.db.model.asset.AssetImageComp;
import io.metaloom.loom.db.model.asset.AssetJsonComp;
import io.metaloom.loom.db.model.asset.AssetTranscriptComp;
import io.metaloom.loom.db.model.asset.AssetVideoComp;
import io.metaloom.loom.api.attachment.AttachmentType;
import io.metaloom.loom.db.model.attachment.Attachment;
import io.metaloom.loom.db.model.attachment.AttachmentDao;
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
import io.metaloom.loom.db.model.dedup.DedupGroup;
import io.metaloom.loom.db.model.dedup.DedupGroupDao;
import io.metaloom.loom.db.model.dedup.DedupGroupMember;
import io.metaloom.loom.db.model.detection.Detection;
import io.metaloom.loom.db.model.detection.DetectionDao;
import io.metaloom.loom.db.model.review.ReviewStatus;
import io.metaloom.loom.db.model.embedding.Embedding;
import io.metaloom.loom.db.model.embedding.EmbeddingDao;
import io.metaloom.loom.db.model.person.Person;
import io.metaloom.loom.db.model.person.PersonDao;
import io.metaloom.loom.db.model.collection.Collection;
import io.metaloom.loom.auth.AuthenticationService;
import io.metaloom.loom.db.model.collection.CollectionDao;
import io.metaloom.loom.db.model.remix.Remix;
import io.metaloom.loom.db.model.remix.RemixDao;
import io.metaloom.loom.db.model.remix.RemixRole;
import io.metaloom.loom.db.model.share.Share;
import io.metaloom.loom.db.model.share.ShareAnnotation;
import io.metaloom.loom.db.model.share.ShareAnnotationKind;
import io.metaloom.loom.db.model.share.ShareComment;
import io.metaloom.loom.db.model.share.ShareDao;
import io.metaloom.loom.db.model.share.ShareFeedbackDao;
import io.metaloom.loom.db.model.share.ShareReaction;
import io.metaloom.loom.db.model.share.ShareReactionType;
import io.metaloom.loom.db.model.comment.Comment;
import io.metaloom.loom.db.model.comment.CommentDao;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.group.GroupDao;
import io.metaloom.loom.db.model.notification.Notification;
import io.metaloom.loom.db.model.notification.NotificationDao;
import io.metaloom.loom.api.notification.NotificationType;
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
	private static final String DEMO_PIPELINE_REVIEW = "Review Triage";

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

	/**
	 * Name of the demo remix. Fixed so the documentation and the screenshot script can name the same thing the demo container shows.
	 */
	private static final String DEMO_REMIX_NAME = "Team meeting — cuts";

	/**
	 * The demo's video files, and the metadata a probe would report for them.
	 *
	 * <p>
	 * Hard-coded because the demo container ships no ffprobe and no video decoder — the JRE image is an Alpine JRE with an AWT stack and nothing
	 * else. The values below are {@code ffprobe} output for the files in {@code demo-content/videos/}, quoted rather than guessed; the derived clips'
	 * commands are in that directory's README. Without an {@code asset_video_comp} row the UI has no duration and its timeline divides by zero.
	 * </p>
	 *
	 * @param filename
	 *            the name the asset carries in the demo
	 * @param source
	 *            the file below the demo content directory
	 * @param width
	 *            frame width in pixels
	 * @param height
	 *            frame height in pixels
	 * @param durationMs
	 *            duration in milliseconds, which is the unit {@code AssetVideoComp} stores
	 * @param frameCount
	 *            number of frames in the video stream
	 * @param fps
	 *            frames per second
	 * @param sizeBytes
	 *            length of the file, used as the asset's size where the file itself is not there to be measured
	 */
	private record DemoVideo(String filename, String source, int width, int height, long durationMs, long frameCount, float fps,
		long sizeBytes) {
	}

	private static final DemoVideo DEMO_VIDEO_MEETING = new DemoVideo("team-meeting.mp4",
		"videos/video-01-work-meeting-around-table.mp4", 1920, 1080, 28_267L, 848L, 30f, 5_895_293L);

	private static final DemoVideo DEMO_VIDEO_TRAFFIC = new DemoVideo("city-traffic.mp4",
		"videos/video-02-busy-street-traffic.mp4", 1920, 1080, 13_367L, 401L, 30f, 11_342_857L);

	private static final DemoVideo DEMO_VIDEO_MEETING_CUT = new DemoVideo("team-meeting-cut.mp4",
		"videos/video-01-work-meeting-around-table-cut.mp4", 1920, 1080, 10_000L, 300L, 30f, 2_154_678L);

	/** The deduplication proposal's duplicate: the same footage as {@link #DEMO_VIDEO_TRAFFIC}, re-encoded smaller. */
	private static final DemoVideo DEMO_VIDEO_TRAFFIC_DUPLICATE = new DemoVideo("city-traffic-720p.mp4",
		"videos/video-02-busy-street-traffic-720p.mp4", 1280, 720, 13_367L, 401L, 30f, 4_019_459L);

	/**
	 * The demo's photographs: the name the asset carries, the file it is seeded from, and the palette painted in its place when there is no demo
	 * content directory.
	 *
	 * <p>
	 * Every entry has a palette so that both modes seed the same roster. A server with no {@code demo-content/} therefore still has sixteen image
	 * assets under these names — painted rather than photographed, which is the degraded state and reads as one.
	 * </p>
	 */
	private record DemoImage(String filename, String source, Palette palette) {
	}

	/**
	 * Order matters: the tags, tasks, comments, detections and remix below index into this list, and the asset browser shows it in seeded order.
	 * The six {@code image-*} photographs come first because they are the ones with people and objects in them.
	 */
	private static final DemoImage[] DEMO_IMAGES = {
		new DemoImage("street-crossing.jpg", "images/image-01-people-crossing-street.jpg", Palette.CITY),
		new DemoImage("coworkers-laptop.jpg", "images/image-02-coworkers-laptop-table.jpg", Palette.STUDIO),
		new DemoImage("friends-outdoors.jpg", "images/image-03-three-friends-outdoors.jpg", Palette.STUDIO),
		new DemoImage("cyclist-city.jpg", "images/image-04-man-riding-bicycle.jpg", Palette.CITY),
		new DemoImage("woman-walking-dog.jpg", "images/image-05-woman-walking-dog.jpg", Palette.FOREST),
		new DemoImage("street-food-vendor.jpg", "images/image-06-street-food-vendor.jpg", Palette.CITY),
		new DemoImage("curved-architecture.jpg", "images/artistic-01-curved-architecture.jpg", Palette.CITY),
		new DemoImage("abstract-facade.jpg", "images/artistic-02-abstract-facade.jpg", Palette.CITY),
		new DemoImage("sand-dune.jpg", "images/artistic-03-sand-dune-abstract.jpg", Palette.SUNSET),
		new DemoImage("sea-stack-beach.jpg", "images/artistic-04-sea-stack-black-beach.jpg", Palette.SUNSET),
		new DemoImage("misty-forest-path.jpg", "images/artistic-05-misty-forest-path.jpg", Palette.FOREST),
		new DemoImage("waterfall-long-exposure.jpg", "images/artistic-06-waterfall-long-exposure.jpg", Palette.FOREST),
		new DemoImage("alpine-lake-autumn.jpg", "images/artistic-07-alpine-lake-autumn.jpg", Palette.LAKE),
		new DemoImage("glowing-autumn-forest.jpg", "images/artistic-08-glowing-autumn-forest.jpg", Palette.AUTUMN),
		new DemoImage("mountain-lake-reflection.jpg", "images/artistic-09-mountain-lake-reflection.jpg", Palette.SNOW),
		new DemoImage("autumn-forest-path.jpg", "images/artistic-10-autumn-forest-path.jpg", Palette.AUTUMN)
	};

	// Indices into DEMO_IMAGES, named because the wiring below is about what is in the picture, not about its position.
	private static final int IMG_CROSSING = 0;
	private static final int IMG_COWORKERS = 1;
	private static final int IMG_FRIENDS = 2;
	private static final int IMG_CYCLIST = 3;
	private static final int IMG_DOG_WALKER = 4;
	private static final int IMG_FOOD_VENDOR = 5;
	private static final int IMG_ARCHITECTURE = 6;
	private static final int IMG_SEA_STACK = 9;
	private static final int IMG_FOREST_PATH = 10;
	private static final int IMG_ALPINE_LAKE = 12;
	private static final int IMG_MOUNTAIN_LAKE = 14;

	/**
	 * A detection box, in the normalised 0..1 coordinates {@code detection} stores.
	 *
	 * <p>
	 * Normalised, so the boxes survive the initializer resizing the photograph on its way in.
	 * </p>
	 */
	private record Box(float x, float y, float width, float height) {
	}

	// The four faces in demo-content/images/image-02-coworkers-laptop-table.jpg, measured against the
	// photograph. These are not decoration: GET /assets/:uuid/detections/:uuid/crop cuts exactly this
	// rectangle out of the stored bytes, so a box that is nearly right produces a crop that is wrong.
	private static final Box FACE_COWORKER_BLOND = new Box(0.187f, 0.037f, 0.094f, 0.172f);
	private static final Box FACE_COWORKER_STANDING = new Box(0.537f, 0.008f, 0.081f, 0.155f);
	private static final Box FACE_COWORKER_GLASSES = new Box(0.398f, 0.325f, 0.102f, 0.225f);
	private static final Box FACE_COWORKER_SEATED = new Box(0.717f, 0.458f, 0.081f, 0.192f);

	// The three faces in demo-content/images/image-03-three-friends-outdoors.jpg.
	private static final Box FACE_FRIEND_PROFILE = new Box(0.272f, 0.183f, 0.089f, 0.233f);
	private static final Box FACE_FRIEND_CENTRE = new Box(0.411f, 0.283f, 0.089f, 0.200f);
	private static final Box FACE_FRIEND_RIGHT = new Box(0.620f, 0.275f, 0.089f, 0.183f);

	/**
	 * The one frame of the meeting clip that also exists as a still.
	 *
	 * <p>
	 * The clip's face detections all sit on it, and their crops are cut from it. Nothing in the server container can decode a video, so a box on any
	 * other frame would be a row the face panel could only render as an empty placeholder.
	 * </p>
	 */
	private static final String DEMO_VIDEO_MEETING_POSTER = "videos/video-01-work-meeting-around-table-poster.jpg";

	// Two of the four faces on that frame, measured against it. Everyone in this clip is turned towards
	// somebody else, which is why both are scored below the frontal boxes above.
	private static final Box FACE_MEETING_LEFT_WOMAN = new Box(0.209f, 0.285f, 0.056f, 0.118f);
	private static final Box FACE_MEETING_RIGHT_MAN = new Box(0.582f, 0.222f, 0.063f, 0.125f);

	/**
	 * Where a demo account picture or person image comes from.
	 *
	 * <p>
	 * Two sources per face: a file under the demo content directory, and the 512x512 crop shipped in the jar for installations that have no such
	 * directory. The shipped crops are cut from three Pexels portraits, so three of these faces are the same people in both modes; the two that are
	 * not have no shipped crop at all and their person records are only seeded when the media is present. Five people sharing three faces would read
	 * as a clustering bug rather than as demo data.
	 * </p>
	 *
	 * @param source
	 *            the file below the demo content directory
	 * @param cropEdge
	 *            side of the square to cut from an uncropped original, or 0 when the file is already a square portrait
	 * @param cropX
	 *            left edge of that square, in source pixels
	 * @param cropY
	 *            top edge of that square, in source pixels
	 * @param close
	 *            the shipped crop used as the tight framing when there is no demo content, or null when this face has none
	 * @param wide
	 *            the shipped crop used as the wide framing, or null
	 */
	private record DemoFaceSource(String source, int cropEdge, int cropX, int cropY, Portrait close, Portrait wide) {
	}

	/**
	 * The faces the demo puts names to.
	 *
	 * <p>
	 * {@code ADMIN} is the primary account picture. Its geometry is the {@code frost-wide} crop recorded in
	 * {@code loom/core/src/main/resources/demo/portraits/README.txt}, reused rather than re-derived, because the shipped crop and the one cut here
	 * have to be the same face in the same framing — otherwise the demo's own account changes appearance depending on which image it is running.
	 * </p>
	 */
	private static final class DemoFace {

		static final DemoFaceSource ADMIN = new DemoFaceSource("users/primary-pexels-merlin-11167639.jpg", 1800, 322, 450,
			Portrait.FROST_CLOSE, Portrait.FROST_WIDE);

		static final DemoFaceSource EDITOR = new DemoFaceSource("users/user-03-man-black-shirt.jpg", 0, 0, 0,
			Portrait.VIOLET_CLOSE, Portrait.VIOLET_WIDE);

		static final DemoFaceSource JOHN = new DemoFaceSource("persons/person-02-man-blue-shirt.jpg", 0, 0, 0,
			Portrait.TEAL_CLOSE, Portrait.TEAL_WIDE);

		static final DemoFaceSource ALICE = new DemoFaceSource("persons/person-01-woman-long-hair.jpg", 0, 0, 0,
			Portrait.FROST_CLOSE, Portrait.FROST_WIDE);

		static final DemoFaceSource BOB = new DemoFaceSource("persons/person-04-man-beard.jpg", 0, 0, 0,
			Portrait.VIOLET_CLOSE, Portrait.VIOLET_WIDE);

		/** No shipped crop: Carol exists only where the demo media does. */
		static final DemoFaceSource CAROL = new DemoFaceSource("persons/user-02-woman-glasses.jpg", 0, 0, 0, null, null);

		/** No shipped crop: Dana exists only where the demo media does. */
		static final DemoFaceSource DANA = new DemoFaceSource("persons/person-08-older-woman.jpg", 0, 0, 0, null, null);

		private DemoFace() {
		}
	}

	/** How much tighter the second framing of a person's gallery is cut than the first. */
	private static final double PORTRAIT_CLOSE_ZOOM = 0.72;

	/** How much wider than its box a face crop is cut. A detector's box stops at the hairline. */
	private static final double FACE_CROP_MARGIN = 0.45;

	/**
	 * Fixed slugs for the two demo share links.
	 *
	 * <p>
	 * Fixed rather than generated so the documentation, the screenshot script and anybody following the getting-started guide can all name the same
	 * URL. A real link's slug is 128 random bits; these are readable on purpose, and are only ever this predictable in the demo container.
	 * </p>
	 */
	private static final String DEMO_SHARE_SLUG_OPEN = "demoOpenCollection001";

	private static final String DEMO_SHARE_SLUG_LOCKED = "demoLockedAssetLink01";

	/** Quoted verbatim in the customer-facing documentation. */
	private static final String DEMO_SHARE_PASSWORD = "amber-lantern-42";

	/** Bytes of bit data behind a 256 component fingerprint vector - see {@link #demoFingerprintHex(byte[])}. */
	private static final int FINGERPRINT_BIT_BYTES = 256 / 8;

	/** The node kind {@code FingerprintNode} writes its components under. */
	private static final String DEMO_FINGERPRINT_NODE_KIND = "fingerprint";

	/**
	 * The node kind the seeded image and video components are attributed to.
	 *
	 * <p>
	 * {@code metadata} is the node that would have produced them on a real ingest, and the read side coalesces components by producer precedence — so
	 * an invented kind would sort somewhere nothing expects. These rows are seeded rather than produced, but they are the shape that node writes.
	 * </p>
	 */
	private static final String DEMO_MEDIA_NODE_KIND = "metadata";

	/** Bit pattern of the original of the seeded near-duplicate pair; the re-encode flips one of its bits. */
	private static final int DEMO_FINGERPRINT_BASE_BYTE = 0xB4;

	/**
	 * Bit patterns for the demo videos that are nobody's duplicate.
	 *
	 * <p>
	 * One per video, and all four differ from {@link #DEMO_FINGERPRINT_BASE_BYTE} and from each other in at least two bits of every byte - at least 64
	 * of the 256. Reusing one pattern for two videos would make them a perfect-score duplicate pair of each other, which is the opposite of what they
	 * are seeded to show.
	 * </p>
	 */
	private static final int[] DEMO_FINGERPRINT_UNRELATED_BYTES = { 0x4B, 0x2D, 0x96, 0x69 };

	/**
	 * The score the seeded near-duplicate pair reaches, and therefore the score the seeded dedup proposal over the same two assets records.
	 *
	 * <p>
	 * Fixed by the arithmetic rather than chosen: the index scores Euclidean neighbours as {@code 1 / (1 + d²)} over 0/1 components, so a single
	 * differing bit is {@code 1 / 2}.
	 * </p>
	 */
	private static final float DEMO_FINGERPRINT_PAIR_SCORE = 0.5f;

	private final UserDao userDao;
	private final AssetDao assetDao;
	private final SpaceDao spaceDao;
	private final TagDao tagDao;
	private final CollectionDao collectionDao;
	private final LibraryDao libraryDao;
	private final PipelineDao pipelineDao;
	private final AssetPoolDao assetPoolDao;
	private final GroupDao groupDao;

	private final NotificationDao notificationDao;
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

	private final EmbeddingDao embeddingDao;
	private final PersonDao personDao;

	private final AttachmentDao attachmentDao;
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
	private final DedupGroupDao dedupGroupDao;
	private final RemixDao remixDao;
	private final ShareDao shareDao;
	private final ShareFeedbackDao shareFeedbackDao;
	private final AuthenticationService authService;
	private final LoomOptions options;

	/**
	 * The checked-in media, or an unavailable library when this installation ships none.
	 *
	 * <p>
	 * Resolved in {@link #init()} rather than in the constructor: the initializer is built by Dagger at wiring time, and the relative fall-back path
	 * is only meaningful once the process has its working directory.
	 * </p>
	 */
	private DemoMediaLibrary media = new DemoMediaLibrary(null);

	/** Running detection ordinal per {@code asset|nodeKind|frame}; see {@link #createDetection}. */
	private final Map<String, Integer> detectionOrdinals = new HashMap<>();

	@Inject
	public DemoDatabaseInitializer(UserDao userDao, AssetDao assetDao, SpaceDao spaceDao,
		TagDao tagDao, CollectionDao collectionDao, LibraryDao libraryDao, PipelineDao pipelineDao, AssetPoolDao assetPoolDao,
		GroupDao groupDao, RoleDao roleDao, PermissionDao permissionDao, TaskDao taskDao, NotificationDao notificationDao,
		AnnotationDao annotationDao, ReactionDao reactionDao, TokenDao tokenDao,
		CommentDao commentDao, BlacklistDao blacklistDao, MemoryDenyRuleDao memoryDenyRuleDao, ClusterDao clusterDao, PersonDao personDao,
		DetectionDao detectionDao, EmbeddingDao embeddingDao,
		AssetComponentDao assetComponentDao, ChatDao chatDao, PipelineVersionDao pipelineVersionDao,
		PipelineRunDao pipelineRunDao, AssetBinaryDao assetBinaryDao, SkillDao skillDao, SkillVersionDao skillVersionDao,
		ChatSessionDao chatSessionDao, MemoryEntryDao memoryEntryDao, DedupGroupDao dedupGroupDao, AttachmentDao attachmentDao,
		ShareDao shareDao, ShareFeedbackDao shareFeedbackDao, RemixDao remixDao, AuthenticationService authService, LoomOptions options) {
		this.userDao = userDao;
		this.assetDao = assetDao;
		this.spaceDao = spaceDao;
		this.tagDao = tagDao;
		this.collectionDao = collectionDao;
		this.shareDao = shareDao;
		this.remixDao = remixDao;
		this.shareFeedbackDao = shareFeedbackDao;
		this.authService = authService;
		this.libraryDao = libraryDao;
		this.pipelineDao = pipelineDao;
		this.assetPoolDao = assetPoolDao;
		this.groupDao = groupDao;
		this.notificationDao = notificationDao;
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
		this.attachmentDao = attachmentDao;
		this.detectionDao = detectionDao;
		this.embeddingDao = embeddingDao;
		this.assetComponentDao = assetComponentDao;
		this.chatDao = chatDao;
		this.pipelineVersionDao = pipelineVersionDao;
		this.pipelineRunDao = pipelineRunDao;
		this.assetBinaryDao = assetBinaryDao;
		this.skillDao = skillDao;
		this.skillVersionDao = skillVersionDao;
		this.chatSessionDao = chatSessionDao;
		this.memoryEntryDao = memoryEntryDao;
		this.dedupGroupDao = dedupGroupDao;
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
		media = new DemoMediaLibrary(options.getDemo().resolveContentDirectory());
		if (media.isAvailable()) {
			log.info("Populating demo data from {}…", media.root());
		} else {
			// Not a failure: the demo seed runs on every installation, and only the demo image carries the
			// media. Everywhere else the pictures are painted, which is what this branch has always done.
			log.info("Populating demo data (no demo content directory — images will be painted)…");
		}

		// --- Space ---
		Space space = spaceDao.createSpace(adminUuid, DEMO_SPACE_NAME);
		space.setUuid(LoomUUID.timeOrdered());
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
		// A curated tag, in the namespace the review screen coins tags into. The demo's other tags
		// live in "category" and "type", which is exactly why the review screen offers the whole
		// vocabulary rather than one namespace's worth: scoping it would show an empty autocomplete
		// on the reference deployment.
		AssetTag tagHero = createAssetTag(admin, "hero", "default");

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

		// 2) Medium pipeline: Source → Filter → Hash + Fingerprint + Metadata → Output
		Pipeline mediumPipeline = createPipeline(admin, DEMO_PIPELINE_MEDIUM,
			"Ingest pipeline with a routing filter, hashing, fingerprinting, and metadata extraction.",
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

		// 8) Review triage: Source → Filter(RATING) → publish / mark. The one demo pipeline whose
		// routing comes from a person rather than from the bytes, and the reason the review screen is
		// worth using: a rating given in the workflow view decides where the file goes. It is also the
		// only seeded graph with configured buckets - see reviewTriageDefinition().
		createPipeline(admin, DEMO_PIPELINE_REVIEW,
			"Routes assets by the rating reviewers gave them: publishes the keepers, tags the rejects and the unreviewed.",
			true, 6, false,
			reviewTriageDefinition());

		// --- Pipeline Runs ---
		// History so the run views and the statistics chart have something to show on a
		// fresh demo. One run per status: a clean success, a partial failure, a live run and
		// a suspended one.
		createPipelineRun(admin, simplePipeline, PipelineRunStatus.SUCCESS, 1, 128, 128, 0, 0, 42_000L);
		createPipelineRun(admin, simplePipeline, PipelineRunStatus.SUCCESS, 4, 96, 94, 0, 2, 31_500L);
		createPipelineRun(admin, mediumPipeline, PipelineRunStatus.PARTIAL, 2, 64, 58, 6, 0, 187_000L);
		createPipelineRun(admin, mediumPipeline, PipelineRunStatus.FAILED, 6, 12, 0, 12, 0, 9_800L);
		createPipelineRun(admin, complexPipeline, PipelineRunStatus.PAUSED, 0, 512, 240, 3, 1, null);
		createPipelineRun(admin, complexPipeline, PipelineRunStatus.RUNNING, 0, 340, 180, 0, 4, null);

		// --- Users ---
		User editor = createDemoUser(admin, "editor", "editor1234", "editor@example.com", "Emily", "Editor");
		User viewer = createDemoUser(admin, "viewer", "viewer1234", "viewer@example.com", "Victor", "Viewer");

		// Account pictures. Two of the three, deliberately: every screen that renders a username has to
		// look right both with a picture and with the initials fall-back, and a demo where everybody has
		// one only ever exercises half of that.
		createUserAvatar(admin, admin, "admin-avatar.jpg", DemoFace.ADMIN);
		createUserAvatar(admin, editor, "editor-avatar.jpg", DemoFace.EDITOR);

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
			// The reviewer's pair: open the duplicate queue and decide a group. Without UPDATE_DEDUP the workflow screen offers a button that 403s.
			Permission.READ_DEDUP, Permission.UPDATE_DEDUP,
			// Ad-hoc node execution: run a node on chosen assets without drawing a pipeline first. The
			// editor is the role the demo's assistant runs as, and this is the permission that lets it
			// gather information about an asset rather than only read what was already computed.
			Permission.EXECUTE_MCP_NODE,
			// The monitoring screen reads GET /api/v1/metrics. Both demo roles get it: instance health
			// is not a privileged secret, and a dashboard of dashes teaches a new user nothing about
			// what the screen is for.
			Permission.READ_METRIC,
			// The admin area's Database Integrity tab. Granted to the editor but not the viewer: the
			// report names the uuids of rows that are wrong, which is a read of the catalogue rather
			// than of a counter, and a read-only demo account has no use for it.
			Permission.READ_DB_INTEGRITY,
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
			// Read only: a viewer can watch the duplicate queue but not decide anything in it.
			Permission.READ_DEDUP,
			Permission.READ_METRIC,
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
		// Images and videos are created with real bytes on disk, so the asset browser, the detail view
		// and both share links render actual media instead of a type placeholder. Where there is no demo
		// content directory the images are painted and the videos become rows without bytes, which is
		// what the demo did everywhere before the media was checked in.
		Asset[] imageAssets = new Asset[DEMO_IMAGES.length];
		for (int i = 0; i < DEMO_IMAGES.length; i++) {
			imageAssets[i] = createImageAsset(admin, campaignLibrary, DEMO_IMAGES[i]);
		}

		Asset[] videoAssets = {
			createVideoAsset(admin, campaignLibrary, DEMO_VIDEO_MEETING),
			createVideoAsset(admin, campaignLibrary, DEMO_VIDEO_TRAFFIC),
			createVideoAsset(admin, campaignLibrary, DEMO_VIDEO_MEETING_CUT),
		};

		// One frame pulled out of the cut. An image by format, and the third member of the remix below —
		// which is the point of a remix: it groups things that are versions of one another regardless of
		// media type.
		Asset stillAsset = createMediaImageAsset(admin, campaignLibrary, "team-meeting-still.jpg",
			"videos/video-01-work-meeting-around-table-still.jpg", Palette.STUDIO);

		// The demo has no audio and no PDF to seed: every clip in demo-content/ is silent and the set
		// carries no document. These stay rows without bytes, which is also a case worth having on the
		// screen — the asset browser has to look right for material Loom only knows about.
		// Attributed to Emily rather than to the admin, so the catalogue can answer a question about a
		// person. "What did Emily add?" is one of the first things anybody asks the chat agent, and on a
		// demo where a single account uploaded everything it is unanswerable - find_assets resolves the
		// name correctly and then honestly reports nothing.
		Asset[] audioAssets = {
			createAsset(editor, "ambient-rain.mp3", "audio/mpeg", 8_500_000, "/demo/audio/ambient-rain.mp3"),
			createAsset(editor, "podcast-episode1.mp3", "audio/mpeg", 45_000_000, "/demo/audio/podcast-episode1.mp3"),
		};

		Asset[] docAssets = {
			createAsset(editor, "space-brief.pdf", "application/pdf", 1_200_000, "/demo/docs/space-brief.pdf"),
			createAsset(admin, "meeting-notes.pdf", "application/pdf", 340_000, "/demo/docs/meeting-notes.pdf"),
		};

		// A scanned page: an image by format, a document by content. It carries the demo `vlm` component
		// below. Painted rather than photographed even with demo content present — the set has no scan,
		// and a photograph of a landscape would not read as one.
		Asset scanAsset = createPaintedImageAsset(admin, campaignLibrary, "scanned-invoice.png", "image/png",
			"/demo/docs/scanned-invoice.png", Palette.SCAN);
		tagDao.tagAsset(tagImage, scanAsset);
		tagDao.tagAsset(tagDocument, scanAsset);

		// Tag images
		for (Asset a : imageAssets) {
			tagDao.tagAsset(tagImage, a);
		}
		tagDao.tagAsset(tagImage, stillAsset);
		tagDao.tagAsset(tagCity, imageAssets[IMG_CROSSING]);
		tagDao.tagAsset(tagPortrait, imageAssets[IMG_COWORKERS]);
		tagDao.tagAsset(tagPortrait, imageAssets[IMG_FRIENDS]);
		tagDao.tagAsset(tagCity, imageAssets[IMG_CYCLIST]);
		tagDao.tagAsset(tagCity, imageAssets[IMG_FOOD_VENDOR]);
		tagDao.tagAsset(tagCity, imageAssets[IMG_ARCHITECTURE]);
		tagDao.tagAsset(tagNature, imageAssets[IMG_DOG_WALKER]);
		tagDao.tagAsset(tagNature, imageAssets[IMG_SEA_STACK]);
		tagDao.tagAsset(tagNature, imageAssets[IMG_FOREST_PATH]);
		tagDao.tagAsset(tagNature, imageAssets[IMG_ALPINE_LAKE]);
		tagDao.tagAsset(tagNature, imageAssets[IMG_MOUNTAIN_LAKE]);
		tagDao.tagAsset(tagLandscape, imageAssets[IMG_SEA_STACK]);
		tagDao.tagAsset(tagLandscape, imageAssets[IMG_ALPINE_LAKE]);
		tagDao.tagAsset(tagLandscape, imageAssets[IMG_MOUNTAIN_LAKE]);
		// The one curated tag: on the same asset the demo rates 9, so the review screen shows an asset
		// that already carries both kinds of decision. tagAsset defaults node_kind to 'manual', which is
		// what makes it read as a person's tag rather than a pipeline's.
		tagDao.tagAsset(tagHero, imageAssets[IMG_CROSSING]);

		// Tag videos
		for (Asset a : videoAssets) {
			tagDao.tagAsset(tagVideo, a);
		}
		tagDao.tagAsset(tagPortrait, videoAssets[0]);
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
		collectionDao.link(imagesCollection, stillAsset);
		for (Asset a : videoAssets) {
			collectionDao.link(videosCollection, a);
		}

		// --- Tasks ---
		// Every task is attached to the asset it is about, so the Tasks tab of the asset detail
		// view has content and the task board shows a realistic mix of statuses and priorities.
		Task taskColourGrade = createAssetTask(admin, imageAssets[IMG_CROSSING], "Colour-grade the hero shot",
			"The dusk white balance drifts cool across the crossing — regrade before the campaign export.",
			TaskPriority.HIGH, TaskStatus.PENDING, 3);
		Task taskBuildingRights = createAssetTask(admin, imageAssets[IMG_ARCHITECTURE], "Clear building rights",
			"Confirm the property release for the facade before this goes into the paid campaign.",
			TaskPriority.CRITICAL, TaskStatus.REVIEW, 1);
		Task taskRetouch = createAssetTask(admin, imageAssets[IMG_COWORKERS], "Retouch the team shot",
			"Light retouching and a tighter crop for the 1:1 social variant.",
			TaskPriority.MEDIUM, TaskStatus.ACCEPTED, 7);
		createAssetTask(admin, videoAssets[1], "Tag the traffic clip",
			"Assign accurate location and time-of-day tags to city-traffic.mp4 for discoverability.",
			TaskPriority.LOW, TaskStatus.PENDING, 14);
		Task taskInterviewCut = createAssetTask(admin, videoAssets[2], "Approve the meeting cut",
			"Review the latest cut of the meeting footage and approve it for publishing.",
			TaskPriority.HIGH, TaskStatus.REVIEW, 2);
		Task taskTranscript = createAssetTask(admin, audioAssets[1], "Check transcript accuracy",
			"Spot-check the ASR transcript of the podcast episode against the audio.",
			TaskPriority.MEDIUM, TaskStatus.PENDING, 5);
		Task metadataTask = createTask(admin, "Review metadata quality",
			"Check imported assets for missing descriptions and keywords.",
			TaskPriority.LOW, TaskStatus.PENDING, 21);

		// --- Task assignees ---
		// A mix of user-assigned, group-assigned and unassigned tasks, so the avatar column
		// and the "@group" chips both have something to show on a fresh container. Groups and
		// users are resolved by name rather than threaded down from the ACL section above,
		// which runs in a different method.
		assignDemoTask(taskColourGrade, "editor", null);
		assignDemoTask(taskBuildingRights, "editor", "Editors");
		assignDemoTask(taskInterviewCut, null, "Editors");
		assignDemoTask(taskTranscript, "viewer", null);
		assignDemoTask(metadataTask, "editor", "Viewers");
		// taskRetouch and taskTimelapse are deliberately left unassigned — an all-assigned
		// board would hide the empty-assignee rendering.

		// --- Notifications ---
		// The assignments above run through the DAO rather than the REST layer, so no dispatch
		// happened. Seed the admin's inbox by hand instead, with a mix of read and unread so the
		// bell shows a badge AND the popover shows both renderings on first boot.
		seedDemoNotification(admin, NotificationType.TASK_ASSIGNED, false,
			"editor assigned you \"Clear building rights\"",
			"Confirm the property release for the facade before this goes into the paid campaign.",
			taskBuildingRights);
		seedDemoNotification(admin, NotificationType.TASK_COMMENT, false,
			"editor commented on \"Approve the meeting cut\"",
			"The second cut is tighter — take another look when you get a moment.",
			taskInterviewCut);
		seedDemoNotification(admin, NotificationType.TASK_STATUS_CHANGED, true,
			"editor moved \"Retouch the team shot\" from PENDING to ACCEPTED",
			null, taskRetouch);

		// --- Annotations ---
		Annotation ann1 = annotationDao.createAnnotation(admin, imageAssets[IMG_CROSSING], "Color correction needed", AnnotationType.FEEDBACK);
		ann1.setDescription("The white balance is slightly off in the top-left quadrant.");
		ann1.setAreaStartX(0);
		ann1.setAreaStartY(0);
		ann1.setAreaWidth(500);
		ann1.setAreaHeight(400);
		annotationDao.store(ann1);

		Annotation ann2 = annotationDao.createAnnotation(admin, videoAssets[0], "Speaker cut off", AnnotationType.FEEDBACK);
		ann2.setDescription("The person on the left leaves frame mid-sentence here.");
		ann2.setTimeFrom(8000L);
		ann2.setTimeTo(9000L);
		annotationDao.store(ann2);

		Annotation ann3 = annotationDao.createAnnotation(admin, imageAssets[IMG_COWORKERS], "Crop suggestion", AnnotationType.FEEDBACK);
		ann3.setDescription("Consider a tighter crop for the hero banner variant.");
		annotationDao.store(ann3);

		log.info("Created {} demo annotations", 3);

		// --- Reactions ---
		Reaction rx1 = reactionDao.createReaction(admin, "THUMBSUP");
		rx1.setAssetUuid(imageAssets[IMG_CROSSING].getUuid());
		reactionDao.store(rx1);

		Reaction rx2 = reactionDao.createReaction(admin, "SATISFIED");
		rx2.setAssetUuid(imageAssets[IMG_COWORKERS].getUuid());
		reactionDao.store(rx2);

		Reaction rx3 = reactionDao.createReaction(admin, "PLUS_ONE");
		rx3.setAssetUuid(videoAssets[0].getUuid());
		reactionDao.store(rx3);

		log.info("Created {} demo reactions", 3);

		// --- Ratings ---
		// A rating is a reaction of its own type carrying a number, which is why one can sit on the
		// crossing shot alongside the THUMBSUP above: the unique index is per (creator, type, asset).
		// Three values spanning the scale, so the demo rating filter below has something to route -
		// a 9 down 'keep', a 2 down 'trash', and a 7 that matches neither and lands in 'other'.
		createRating(admin, imageAssets[IMG_CROSSING], 9);
		createRating(admin, imageAssets[IMG_CYCLIST], 2);
		createRating(admin, videoAssets[0], 7);
		log.info("Created {} demo ratings", 3);

		// --- Comments ---
		createComment(admin, "Review notes", "The white balance looks slightly off in the second half.");
		createComment(admin, "Approved", "Looks great, ready for distribution.");
		createComment(admin, "Tagging feedback", "Please add location tags for the city traffic assets.");
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
			.add(new JsonObject().put("role", "user").put("content", "What tagging convention should we use for the city traffic collection?"))
			.add(new JsonObject().put("role", "assistant").put("content", "I suggest using hierarchical tags: location/city/landmark, and adding time-of-day tags like golden-hour or blue-hour."))
			.add(new JsonObject().put("role", "user").put("content", "Good idea. Can you apply those to the existing assets?"))
			.add(new JsonObject().put("role", "assistant").put("content", "Done. I tagged 12 street assets with the new convention.")
				.put("references", new JsonArray()
					.add(new JsonObject().put("type", "collection").put("label", "Demo Videos")))));

		Chat exportChat = createDemoChat(admin, "Q3 campaign export", new JsonArray()
			.add(new JsonObject().put("role", "user").put("content", "Prepare the Q3 campaign stills for export. Use the grading skill and the tagging convention we agreed on."))
			.add(new JsonObject().put("role", "assistant").put("content", "I graded 7 stills against the campaign reference. Two drifted warm and are regraded; one still needs a property release before it can ship.")
				.put("references", new JsonArray()
					.add(new JsonObject().put("type", "asset").put("label", "street-crossing.jpg"))
					.add(new JsonObject().put("type", "asset").put("label", "curved-architecture.jpg"))
					.add(new JsonObject().put("type", "skill").put("label", "campaign-grading"))))
			.add(new JsonObject().put("role", "user").put("content", "Open a task for the release and tag everything else."))
			.add(new JsonObject().put("role", "assistant").put("content", "Done — a CRITICAL task is on curved-architecture.jpg, and the remaining stills carry location and time-of-day tags.")
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
			"The agreed hierarchical tagging convention for city street footage.",
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
		createBlacklist(admin, imageAssets[IMG_CROSSING], "Duplicate low-res variant");
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
		// Each gets pictures of their own and one of them as the avatar. Person images reference no asset
		// (V2.90), so this is also the demo of the property that matters: deleting the material somebody
		// was found in leaves their picture standing.
		//
		// One face per person, in two framings where they have two pictures — a gallery of two different
		// people under one name would misread as a clustering bug rather than as demo data. With demo
		// content present the second framing is a tighter crop of the same photograph, which is what a
		// second framing of one face is; without it, the two shipped crops of one portrait do the job.
		Person johnDoe = createPerson(admin, "jdoe", "John", "Doe");
		createPersonImage(admin, johnDoe, "john-doe-portrait.jpg", DemoFace.JOHN, true, true);
		createPersonImage(admin, johnDoe, "john-doe-profile.jpg", DemoFace.JOHN, false, false);

		Person aliceSmith = createPerson(admin, "asmith", "Alice", "Smith");
		createPersonImage(admin, aliceSmith, "alice-smith-portrait.jpg", DemoFace.ALICE, true, true);
		createPersonImage(admin, aliceSmith, "alice-smith-profile.jpg", DemoFace.ALICE, false, false);

		Person bobWilson = createPerson(admin, "bwilson", "Bob", "Wilson");
		createPersonImage(admin, bobWilson, "bob-wilson-portrait.jpg", DemoFace.BOB, false, true);

		// Two more, one picture each — but only where there is demo media to give them a face of their
		// own. The jar ships three faces; a fourth and fifth person would have to reuse one, and two
		// people wearing the same face is exactly what a broken clustering run looks like.
		int personCount = 3;
		if (media.isAvailable()) {
			Person carolReed = createPerson(admin, "creed", "Carol", "Reed");
			createPersonImage(admin, carolReed, "carol-reed-portrait.jpg", DemoFace.CAROL, true, true);

			Person danaOkafor = createPerson(admin, "dokafor", "Dana", "Okafor");
			createPersonImage(admin, danaOkafor, "dana-okafor-portrait.jpg", DemoFace.DANA, true, true);
			personCount = 5;
		}
		log.info("Created {} demo persons", personCount);

		// --- Clusters ---
		createCluster(admin, "Face Cluster A", "face");
		createCluster(admin, "Face Cluster B", "face");
		createCluster(admin, "Face Cluster C", "face");
		log.info("Created {} demo clusters", 3);

		// --- Detections ---
		// The boxes are measured against the photographs they sit on rather than invented. Two screens
		// draw them: the asset detail overlay puts them on the picture, and each one is also cut out and
		// stored as a FACE_CROP attachment below, which is what the face panel and the cluster review
		// screen show. A plausible-looking box on a real picture is a crop of somebody's elbow, and
		// those screens are then a grid of elbows.
		//
		// Four faces on the coworkers shot, three on the friends shot — which is how many are in them.
		String coworkersSource = DEMO_IMAGES[IMG_COWORKERS].source();
		String friendsSource = DEMO_IMAGES[IMG_FRIENDS].source();

		Detection faceOne = seedFace(admin, imageAssets[IMG_COWORKERS], coworkersSource, 0, FACE_COWORKER_BLOND, 0.97f,
			new JsonObject().put("gender", "male").put("age", 32));
		Detection faceTwo = seedFace(admin, imageAssets[IMG_COWORKERS], coworkersSource, 0, FACE_COWORKER_STANDING, 0.95f,
			new JsonObject().put("gender", "male").put("age", 36));
		seedFace(admin, imageAssets[IMG_COWORKERS], coworkersSource, 0, FACE_COWORKER_GLASSES, 0.98f,
			new JsonObject().put("gender", "male").put("age", 29));
		seedFace(admin, imageAssets[IMG_COWORKERS], coworkersSource, 0, FACE_COWORKER_SEATED, 0.93f,
			new JsonObject().put("gender", "female").put("age", 27));

		seedFace(admin, imageAssets[IMG_FRIENDS], friendsSource, 0, FACE_FRIEND_CENTRE, 0.96f,
			new JsonObject().put("gender", "female").put("age", 26));
		seedFace(admin, imageAssets[IMG_FRIENDS], friendsSource, 0, FACE_FRIEND_RIGHT, 0.94f,
			new JsonObject().put("gender", "female").put("age", 24));
		// In profile, and scored like one. A detector reports a turned head with less confidence, and a
		// demo where every box is over 0.9 teaches that the score never means anything.
		seedFace(admin, imageAssets[IMG_FRIENDS], friendsSource, 0, FACE_FRIEND_PROFILE, 0.71f,
			new JsonObject().put("gender", "male").put("age", 31).put("pose", "profile"));

		// Face detections on the meeting clip.
		//
		// All on frame 30, and only on frame 30, because that is the one frame of the clip that exists
		// as a still: the crops are cut from the poster beside it. Boxes on a frame nothing can decode
		// would be a face panel of empty rectangles — the server container ships no imaging natives, so
		// it cannot open the video at all.
		seedFace(admin, videoAssets[0], DEMO_VIDEO_MEETING_POSTER, 30, FACE_MEETING_LEFT_WOMAN, 0.88f,
			new JsonObject().put("gender", "female").put("age", 28).put("pose", "profile"));
		seedFace(admin, videoAssets[0], DEMO_VIDEO_MEETING_POSTER, 30, FACE_MEETING_RIGHT_MAN, 0.90f,
			new JsonObject().put("gender", "male").put("age", 35).put("pose", "profile"));

		// A face group awaiting review, as the facedetect node would leave it.
		//
		// Without this the review screen is empty in the demo, which reads as "the feature does not
		// work" rather than "nothing has been proposed yet". Two of the four faces on the coworkers shot
		// become one pending proposal; a reviewer confirms or rejects it.
		//
		// Deliberately not attached to any of the five demo people: their portraits are stock and do not
		// appear in this footage (demo-content/README.md says so), and a seeded match the recogniser
		// would never make is the one thing this demo must not claim.
		createPendingFaceCluster(admin, imageAssets[IMG_COWORKERS], faceOne, faceTwo);

		// Object detections on image assets — the crossing shot, which is the one with traffic in it.
		Detection car = createDetection(admin, imageAssets[IMG_CROSSING], "objectdetection", 0, 0.667f, 0.383f, 0.267f, 0.150f, 0.95f,
			new JsonObject().put("label", "car"));
		Detection person = createDetection(admin, imageAssets[IMG_CROSSING], "objectdetection", 0, 0.072f, 0.275f, 0.139f, 0.708f, 0.92f,
			new JsonObject().put("label", "person"));
		createDetection(admin, imageAssets[IMG_CROSSING], "objectdetection", 0, 0.472f, 0.242f, 0.194f, 0.758f, 0.97f,
			new JsonObject().put("label", "person"));
		createDetection(admin, imageAssets[IMG_CROSSING], "objectdetection", 0, 0.172f, 0.075f, 0.122f, 0.192f, 0.90f,
			new JsonObject().put("label", "traffic light"));
		createDetection(admin, imageAssets[IMG_CYCLIST], "objectdetection", 0, 0.18f, 0.42f, 0.64f, 0.50f, 0.94f,
			new JsonObject().put("label", "bicycle"));
		createDetection(admin, imageAssets[IMG_DOG_WALKER], "objectdetection", 0, 0.52f, 0.55f, 0.26f, 0.36f, 0.93f,
			new JsonObject().put("label", "dog"));

		// Two of the boxes have been reviewed, the rest are still pending.
		//
		// All-pending would demonstrate the queue but not the outcome, and it is the outcome that is
		// hard to picture: a confirmation can carry a correction, so "the model said car, the reviewer
		// said van" is one row rather than a rejection plus a re-entry, and a rejected box is kept as
		// the record that the producer was wrong rather than deleted.
		detectionDao.updateReview(car.getUuid(), ReviewStatus.CONFIRMED, "van", admin.getUuid());
		detectionDao.updateReview(person.getUuid(), ReviewStatus.REJECTED, null, admin.getUuid());

		// Object detections on the traffic clip, which is the object-detection sample.
		createDetection(admin, videoAssets[1], "objectdetection", 30, 0.41f, 0.52f, 0.11f, 0.24f, 0.91f,
			new JsonObject().put("label", "car"));
		createDetection(admin, videoAssets[1], "objectdetection", 120, 0.62f, 0.30f, 0.19f, 0.28f, 0.88f,
			new JsonObject().put("label", "bus"));

		log.info("Created {} demo detections", 17);

		// --- Transcripts ---
		// The demo clips are silent — every file in demo-content/videos/ is a video stream and nothing
		// else — so these are seeded rather than produced, and the meeting footage is the one they are
		// written for: what a transcript of four people round a table would say.
		//
		// Transcript for team-meeting.mp4 (videoAssets[0]) — 3 sections
		createTranscript(admin, videoAssets[0], "en", "whisper-1", "asr-pipeline",
			"Welcome everyone to the quarterly update. We have a packed agenda today covering product launches, financial results, and team updates.",
			"First up, let's discuss the new product launch. The campaign alpha assets are performing exceptionally well across all channels. Social engagement is up forty percent compared to last quarter.",
			"Moving on to financials. Q1 revenue came in twelve percent above target. Our media pipeline automation reduced processing costs by nearly a third. The investment in the new encoding infrastructure is already paying dividends.");

		// Transcript for team-meeting-cut.mp4 (videoAssets[2]) — 2 sections
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
		// Both on assets that carry detections, so the caption, the palette and the boxes on the same
		// screen are about the same picture.
		createImageCaptioningComp(admin, imageAssets[IMG_COWORKERS]);
		createVideoCaptioningComp(admin, videoAssets[1]);

		// --- Dominant colour ---
		createDominantColorComp(admin, imageAssets[IMG_COWORKERS], FACE_COWORKER_GLASSES);

		// --- Deduplication review queue ---
		// Over the traffic clip, because that is the pair the checked-in media actually is: the same
		// footage at two bitrates. The meeting clip and its cut are a remix, not a duplicate.
		Asset dupAsset = seedDemoDedupGroup(admin, campaignLibrary, videoAssets[1]);

		// --- Fingerprints for the similarity index ---
		// Seeded after the dedup group so both features demo off the same two videos: the proposal in
		// the review queue and the k-NN hit behind it describe one pair, not two unrelated fixtures.
		//
		// The meeting clip is the one unrelated video. Its cut deliberately gets no fingerprint at all:
		// a cut of the source really is a near-duplicate of it, so seeding it as "unrelated" — 64 of 256
		// bits away — would put a claim in the index that the fingerprint node would never make.
		seedFingerprintComps(assetComponentDao, adminUuid, videoAssets[1], dupAsset, videoAssets[0]);

		// --- Remix: an original, the cut made from it, and a still pulled out of that cut ---
		seedDemoRemix(admin, videoAssets[0], videoAssets[2], stillAsset);

		// --- Customer-facing share links ---
		seedDemoShares(admin, videosCollection, videoAssets[0]);

		// "with media" counts the assets that have bytes behind them: the photographs, the still and the
		// videos when there is a content directory. The audio and PDF rows never do, and the dedup
		// duplicate is counted with the videos it was cut from.
		int withMedia = imageAssets.length + 1 + (media.isAvailable() ? videoAssets.length + 1 : 0) + 1;
		log.info(
			"Demo data initialization complete — created {} assets ({} with media), {} tags, {} collections, {} pipelines, {} users, "
				+ "{} groups, {} roles, {} tasks, {} skills, {} chat sessions, {} memory entries, {} annotations, {} reactions.",
			imageAssets.length + videoAssets.length + audioAssets.length + docAssets.length + 3,
			withMedia, 8, 2, 3, 2, 2, 2, 7, 3, 3, 3, 3, 3);
	}

	/**
	 * @param dueInDays how far out the due date sits; the task board colours overdue and near-due tasks differently, so the spread matters
	 */
	private Task createTask(User admin, String title, String description, TaskPriority priority, TaskStatus status, int dueInDays) {
		Task task = taskDao.createTask(admin.getUuid(), title);
		task.setUuid(LoomUUID.timeOrdered());
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
	 * Give a demo task an owner: a user, a group, or both.
	 *
	 * <p>
	 * Resolved by name because the ACL section that creates the demo users and groups runs in a different method. A missing name is logged and
	 * skipped rather than thrown - {@code BootstrapInitializer} swallows failures here, so an exception would silently truncate everything seeded
	 * after this point.
	 * </p>
	 *
	 * @param username  the demo user to assign to, or null
	 * @param groupName the demo group to assign to, or null
	 */
	private void assignDemoTask(Task task, String username, String groupName) {
		if (username != null) {
			User user = userDao.loadByUsername(username);
			if (user == null) {
				log.warn("Demo user '{}' not found; leaving task '{}' unassigned", username, task.getTitle());
			} else {
				taskDao.assignUser(task.getUuid(), user.getUuid(), task.getCreatorUuid());
				log.info("Assigned demo task '{}' to user {}", task.getTitle(), username);
			}
		}
		if (groupName != null) {
			Group group = groupDao.loadByName(groupName);
			if (group == null) {
				log.warn("Demo group '{}' not found; leaving task '{}' unassigned", groupName, task.getTitle());
			} else {
				taskDao.assignGroup(task.getUuid(), group.getUuid(), task.getCreatorUuid());
				log.info("Assigned demo task '{}' to group {}", task.getTitle(), groupName);
			}
		}
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
		chat.setUuid(LoomUUID.timeOrdered());
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
		library.setUuid(LoomUUID.timeOrdered());
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
		rule.setUuid(LoomUUID.timeOrdered());
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
		comment.setUuid(LoomUUID.timeOrdered());
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
		blacklist.setUuid(LoomUUID.timeOrdered());
		blacklist.setCreator(admin);
		blacklist.setEditor(admin);
		blacklist.setCreated(Instant.now());
		blacklist.setEdited(Instant.now());
		blacklistDao.store(blacklist);
		log.info("Created demo blacklist entry: {}", name);
		return blacklist;
	}

	/**
	 * Give a person one of their own pictures, and optionally make it their avatar.
	 *
	 * <p>
	 * The bytes go to the same content-addressed upload directory an asset binary would use, but the row references no asset - only the person (V2.90).
	 * That is the whole point of the model: a person's picture is theirs, and deleting the material they were found in cannot take it away.
	 * </p>
	 */
	private Attachment createPersonImage(User admin, Person person, String filename, DemoFaceSource face, boolean close, boolean avatar) {
		byte[] bytes = loadFace(face, close);
		if (bytes == null) {
			return null;
		}
		SHA512 sha512 = SHA512.fromString(hex(digest("SHA-512", bytes)));
		storeBinary(bytes, sha512);

		Attachment image = attachmentDao.createAttachment(admin.getUuid(), sha512, filename, bytes.length, "image/jpeg", AttachmentType.PERSON_IMAGE);
		image.setUuid(LoomUUID.timeOrdered());
		image.setPersonUuid(person.getUuid());
		image.setCreator(admin);
		image.setEditor(admin);
		image.setCreated(Instant.now());
		image.setEdited(Instant.now());
		attachmentDao.store(image);

		if (avatar) {
			person.setAvatarAttachmentUuid(image.getUuid());
			personDao.update(person);
		}
		log.info("Created demo person image: {} for {} ({} bytes)", filename, person.getAlias(), bytes.length);
		return image;
	}

	/**
	 * Give a user account its picture.
	 *
	 * <p>
	 * The same content-addressed storage a person image uses, pointed at the account instead (V2.93). Unlike a person there is at most one, so this
	 * is called once per account rather than building a gallery.
	 * </p>
	 */
	private Attachment createUserAvatar(User admin, User owner, String filename, DemoFaceSource face) {
		byte[] bytes = loadFace(face, true);
		if (bytes == null) {
			return null;
		}
		SHA512 sha512 = SHA512.fromString(hex(digest("SHA-512", bytes)));
		storeBinary(bytes, sha512);

		Attachment avatar = attachmentDao.createAttachment(admin.getUuid(), sha512, filename, bytes.length, "image/jpeg", AttachmentType.USER_AVATAR);
		avatar.setUuid(LoomUUID.timeOrdered());
		avatar.setUserUuid(owner.getUuid());
		avatar.setCreator(admin);
		avatar.setEditor(admin);
		avatar.setCreated(Instant.now());
		avatar.setEdited(Instant.now());
		attachmentDao.store(avatar);

		// The attachment row has to exist before the account can point at it: the two foreign keys form a
		// cycle, and this order is what resolves it.
		owner.setAvatarAttachmentUuid(avatar.getUuid());
		userDao.update(owner);
		log.info("Created demo account picture: {} for {} ({} bytes)", filename, owner.getUsername(), bytes.length);
		return avatar;
	}

	private Person createPerson(User admin, String alias, String firstname, String lastname) {
		Person person = personDao.createPerson(admin.getUuid(), alias);
		person.setUuid(LoomUUID.timeOrdered());
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

	/**
	 * Seed one machine-proposed face cluster, with the embeddings that make it a real group.
	 *
	 * <p>
	 * Shaped exactly as {@code FacedetectNode} writes them - no name, no creator, status PENDING, keyed by
	 * {@code (asset, node_kind, cluster_index)} - so the demo exercises the same review path a real run
	 * produces rather than a hand-made approximation of it.
	 * </p>
	 */
	private void createPendingFaceCluster(User admin, Asset asset, Detection... detections) {
		Cluster cluster = clusterDao.createMachineCluster(Cluster.TYPE_FACE, "facedetect", asset.getUuid(), 0);
		cluster.setProducerVersion("inspireface-pikachu-r18");
		cluster.setModel("inspireface-pikachu-r18");
		cluster.setScore(0.93f);
		clusterDao.upsertCluster(cluster);

		int subject = 0;
		for (Detection detection : detections) {
			// A short vector rather than a realistic 512-d one: nothing in the demo compares them, and a
			// wall of floats in the seed would only obscure what this is demonstrating.
			Float[] vector = new Float[] { 0.1f * (subject + 1), 0.2f, 0.3f };
			Embedding embedding = embeddingDao.createEmbedding(admin.getUuid(), asset.getUuid(), vector, "face");
			embedding.setNodeKind("facedetect");
			embedding.setModel("inspireface-pikachu-r18");
			embedding.setDetectionUuid(detection.getUuid());
			embedding.setSubjectIndex(subject);
			embedding.setNormalized(true);
			embeddingDao.store(embedding);

			clusterDao.link(cluster.getUuid(), embedding.getUuid(), 0.95f, "AUTO");
			subject++;
		}
		log.info("Created a pending demo face cluster with {} member(s)", detections.length);
	}

	private Cluster createCluster(User admin, String name, String type) {
		Cluster cluster = clusterDao.createCluster(admin.getUuid(), name, type);
		cluster.setUuid(LoomUUID.timeOrdered());
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
		// Both kinds now have a producer node, so both are attributed to one. Leaving the object boxes
		// on the DAO's "manual" attribution made the demo library look like somebody had drawn them by
		// hand, which is exactly the wrong story for a catalogue selling automated enrichment.
		// "face", not "facedetection": that is what FacedetectNode actually writes. The demo seeded the
		// longer string and the UI filtered on it, so the two agreed with each other and neither agreed
		// with the pipeline - the asset face panel worked against demo data and was empty for every real
		// asset. The three names in play are the node kind (facedetect), the options key (facedetection)
		// and the detection type (face); only the last one belongs here.
		if ("face".equals(type)) {
			detection.setNodeKind("facedetect");
		} else if ("objectdetection".equals(type)) {
			detection.setNodeKind("objectdetect");
			// Promote the class out of meta into the indexed column the schema added for it. The demo
			// predates objectdetect and only ever carried it in the JSON blob, where nothing can find it.
			if (meta != null && meta.getString("label") != null) {
				detection.setLabel(meta.getString("label"));
			}
		}
		String frameKey = asset.getUuid() + "|" + detection.getNodeKind() + "|" + frameNumber;
		detection.setDetectionIndex(detectionOrdinals.merge(frameKey, 1, Integer::sum) - 1);
		detection.setUuid(LoomUUID.timeOrdered());
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

	/** A detection whose box was measured against the picture rather than typed out four floats at a time. */
	private Detection createDetection(User admin, Asset asset, String type, int frameNumber, Box box, float confidence, JsonObject meta) {
		return createDetection(admin, asset, type, frameNumber, box.x(), box.y(), box.width(), box.height(), confidence, meta);
	}

	/**
	 * One face detection, together with the crop the product serves for it.
	 *
	 * @param source
	 *            the file below the demo content directory the box was measured against — the photograph itself for a still, and the poster frame for
	 *            the clip, since that is the frame the boxes sit on
	 */
	private Detection seedFace(User admin, Asset asset, String source, int frameNumber, Box box, float confidence, JsonObject meta) {
		Detection detection = createDetection(admin, asset, "face", frameNumber, box, confidence, meta);
		createFaceCrop(admin, asset, detection, source, box);
		return detection;
	}

	/**
	 * Cut one face out of the picture it was found in and store it as a {@code FACE_CROP} attachment.
	 *
	 * <p>
	 * {@code GET /assets/:uuid/detections/:uuid/crop} serves a <em>stored</em> attachment rather than cutting one on demand — the server container
	 * ships no imaging natives and could not decode a video frame at all, so the producing node writes the crop it already cut to compute the
	 * embedding. Without these rows the face panel and the cluster review screen fall back to an icon, which is what they did for every demo asset
	 * before the media was real.
	 * </p>
	 *
	 * <p>
	 * The crop is padded around the box, because a detector's box is tight on the face and a picture <em>of</em> somebody wants their hair and chin in
	 * it. Nothing is stored where there is no demo media to cut from: the painted fall-back has no faces in it to find.
	 * </p>
	 */
	private void createFaceCrop(User admin, Asset asset, Detection detection, String source, Box box) {
		if (!media.isAvailable()) {
			return;
		}
		byte[] bytes = media.regionCrop(source, box.x(), box.y(), box.width(), box.height(), FACE_CROP_MARGIN);
		if (bytes == null) {
			return;
		}
		SHA512 sha512 = SHA512.fromString(hex(digest("SHA-512", bytes)));
		if (storeBinary(bytes, sha512) == null) {
			return;
		}

		String filename = "face-" + detection.getUuid() + ".jpg";
		Attachment crop = attachmentDao.createAttachment(admin.getUuid(), sha512, filename, bytes.length, "image/jpeg", AttachmentType.FACE_CROP);
		crop.setUuid(LoomUUID.timeOrdered());
		crop.setDetectionUuid(detection.getUuid());
		crop.setAssetUuid(asset.getUuid());
		crop.setCreator(admin);
		crop.setEditor(admin);
		crop.setCreated(Instant.now());
		crop.setEdited(Instant.now());
		attachmentDao.store(crop);
	}

	/**
	 * Seed a workflow star rating: an asset reaction of type {@code RATING} carrying the value.
	 *
	 * @param rating
	 *            1-10, as the review screen writes it
	 */
	private Reaction createRating(User admin, Asset asset, int rating) {
		Reaction reaction = reactionDao.createReaction(admin, ReactionType.RATING.name());
		reaction.setAssetUuid(asset.getUuid());
		reaction.setRating(rating);
		reactionDao.store(reaction);
		log.info("Created demo rating: {} on {}", rating, asset.getFilename());
		return reaction;
	}

	private AssetTag createAssetTag(User admin, String name, String collection) {
		AssetTag tag = tagDao.createAssetTag(admin, name, collection);
		tag.setUuid(LoomUUID.timeOrdered());
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
		col.setUuid(LoomUUID.timeOrdered());
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
		pool.setUuid(LoomUUID.timeOrdered());
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

	/**
	 * @param creator
	 *            who the asset is attributed to. Not always the admin: a catalogue where one account uploaded everything cannot demonstrate any
	 *            question of the form "what did <i>she</i> add", which the chat agent's {@code find_assets} answers by resolving a person's name.
	 */
	private Asset createAsset(User creator, String filename, String mimeType, long size, String origin) {
		String hashHex = String.format("%0128x", new java.math.BigInteger(1, filename.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
			.substring(0, 128);
		// Pad to 128 hex chars
		while (hashHex.length() < 128) {
			hashHex = hashHex + "0";
		}
		SHA512 sha512 = SHA512.fromString(hashHex);
		Asset asset = assetDao.createAsset(creator, sha512, mimeType, filename, origin, size);
		asset.setUuid(LoomUUID.timeOrdered());
		asset.setCreator(creator);
		asset.setEditor(creator);
		asset.setCreated(Instant.now());
		asset.setEdited(Instant.now());
		asset.setFirstSeen(Instant.now());
		assetDao.store(asset);
		log.info("Created demo asset: {}", filename);
		return asset;
	}

	/**
	 * One remix, so the asset browser has a remix card to show on first boot.
	 *
	 * <p>
	 * Modelled on the realistic case rather than the minimal one: a source video, a second video cut from it, and a still pulled out of it. That mix
	 * is the point - a remix groups things that are versions of one another regardless of media type, which is what distinguishes it from a
	 * collection (a topic) and from a dedup group (the same bytes twice).
	 * </p>
	 */
	private Remix seedDemoRemix(User admin, Asset source, Asset derivedVideo, Asset derivedStill) {
		Remix remix = remixDao.createRemix(admin.getUuid(), DEMO_REMIX_NAME);
		remix.setUuid(LoomUUID.timeOrdered());
		remix.setDescription("The original meeting footage, the shorter cut made from it, and a still frame pulled out of that cut.");
		remixDao.store(remix);

		remixDao.linkAsset(remix.getUuid(), source.getUuid(), RemixRole.SOURCE, 0, admin.getUuid());
		remixDao.linkAsset(remix.getUuid(), derivedVideo.getUuid(), RemixRole.DERIVED, 1, admin.getUuid());
		remixDao.linkAsset(remix.getUuid(), derivedStill.getUuid(), RemixRole.DERIVED, 2, admin.getUuid());

		log.info("Created demo remix: {} ({} members)", DEMO_REMIX_NAME, remixDao.countAssets(remix.getUuid()));
		return remix;
	}

	/**
	 * Two share links, so the customer-facing area has something to open on first boot.
	 *
	 * <p>
	 * One of each kind, because they answer different questions. The open collection link shows what a client sees when there is nothing in the way:
	 * a tiled set of clips, downloadable, with comments and marks turned on so the feedback surface has something in it. The password-protected asset
	 * link shows the front door - it is the one worth opening second, and the one the getting-started guide quotes the password for.
	 * </p>
	 *
	 * <p>
	 * The collection link already carries feedback from a visitor called "Maria from Acme". Seeding it matters more than it looks: an empty feedback
	 * panel and a broken feedback panel render identically, and the owner-side Feedback tab is otherwise impossible to evaluate without opening a
	 * second browser and typing a review by hand.
	 * </p>
	 *
	 * <p>
	 * Neither link expires. A demo container that has been running for a fortnight should still be able to show the feature.
	 * </p>
	 */
	private void seedDemoShares(User admin, Collection videosCollection, Asset featuredVideo) {
		Share collectionShare = shareDao.createCollectionShare(admin.getUuid(), videosCollection.getUuid(), DEMO_SHARE_SLUG_OPEN);
		collectionShare.setUuid(LoomUUID.timeOrdered());
		collectionShare.setAllowDownload(true);
		collectionShare.setShowMetadata(true);
		collectionShare.setAllowComments(true);
		collectionShare.setAllowReactions(true);
		collectionShare.setAllowAnnotations(true);
		// Already opened once, by somebody who gave a name - so the share list has a visitor to show rather than
		// "not opened yet" on every row.
		collectionShare.setVisitorName("Maria from Acme");
		collectionShare.setFirstVisitedAt(Instant.now().minusSeconds(3 * 24 * 3600));
		collectionShare.setLastViewedAt(Instant.now().minusSeconds(2 * 3600));
		collectionShare.setViewCount(4);
		shareDao.store(collectionShare);
		log.info("Created demo share link: /ui/share/{}", DEMO_SHARE_SLUG_OPEN);

		Share assetShare = shareDao.createAssetShare(admin.getUuid(), featuredVideo.getUuid(), DEMO_SHARE_SLUG_LOCKED);
		assetShare.setUuid(LoomUUID.timeOrdered());
		// Hashed with the same encoder a login uses. Storing the clear password here would be the one place in the
		// codebase where a share password existed in readable form, which is exactly what the column comment forbids.
		assetShare.setPasswordHash(authService.encodePassword(DEMO_SHARE_PASSWORD));
		assetShare.setAllowDownload(false);
		assetShare.setShowMetadata(true);
		assetShare.setAllowComments(true);
		assetShare.setAllowReactions(true);
		assetShare.setAllowAnnotations(false);
		shareDao.store(assetShare);
		log.info("Created demo share link (password {}): /ui/share/{}", DEMO_SHARE_PASSWORD, DEMO_SHARE_SLUG_LOCKED);

		seedDemoShareFeedback(collectionShare, featuredVideo);
	}

	/**
	 * What the demo's customer said back: a comment with a reply, a mark on the timeline, and a sign-off.
	 */
	private void seedDemoShareFeedback(Share share, Asset video) {
		String author = share.getVisitorName();

		ShareComment note = shareFeedbackDao.createComment(share.getUuid(), video.getUuid(), author,
			"The second cut runs long - could we lose the establishing shot at the top?");
		shareFeedbackDao.storeComment(note);

		ShareComment reply = shareFeedbackDao.createComment(share.getUuid(), video.getUuid(), author,
			"Ignore that, the client wants the wide. Leave it as is.");
		reply.setParentUuid(note.getUuid());
		shareFeedbackDao.storeComment(reply);

		ShareAnnotation mark = shareFeedbackDao.createAnnotation(share.getUuid(), video.getUuid(),
			ShareAnnotationKind.SPATIOTEMPORAL, author);
		mark.setTimeFrom(14.25).setTimeTo(19.5);
		// Normalised 0..1 against the frame, which is what the viewer draws with - see V2.99.
		mark.setAreaX(0.42).setAreaY(0.18).setAreaWidth(0.16).setAreaHeight(0.22);
		mark.setText("The logo is clipped on the right here.");
		shareFeedbackDao.storeAnnotation(mark);

		ShareReaction approval = shareFeedbackDao.createReaction(share.getUuid(), ShareReactionType.APPROVE, author);
		approval.setAssetUuid(video.getUuid());
		shareFeedbackDao.storeReaction(approval);

		log.info("Seeded demo share feedback for {}", share.getSlug());
	}

	/**
	 * Put one duplicate proposal in the review queue, so the deduplication workflow has something real to open on first boot.
	 *
	 * <p>
	 * A lower-bitrate re-encode of an existing demo video is created as the duplicate: same footage, smaller file, so the machine's KEEP choice (the
	 * largest complete candidate) is the obvious one and a reviewer can still see why they might override it.
	 * </p>
	 *
	 * <p>
	 * The re-encode is a real file where there is demo media — {@code ffmpeg} scaled the traffic clip to 720p, and the README beside it records the
	 * command — so the proposal is over two files a hasher would genuinely reduce to the same neighbourhood, not over two rows.
	 * </p>
	 *
	 * <p>
	 * Deliberately <b>PENDING</b> and never CONFIRMED. A confirmed group is an instruction to the apply node to move a file, and the demo's media sits
	 * in a content-addressed store the apply node does not own - the first apply run would report failures over seeded fiction.
	 * </p>
	 *
	 * @return the duplicate asset, so {@link #seedFingerprintComps} can give the same pair the fingerprints this proposal claims to come from
	 */
	private Asset seedDemoDedupGroup(User admin, Library library, Asset keepAsset) {
		Asset dupAsset = createVideoAsset(admin, library, DEMO_VIDEO_TRAFFIC_DUPLICATE);

		DedupGroup group = dedupGroupDao.createGroup(admin.getUuid(), "metaloom-multisector-v1");
		group.setKeepAssetUuid(keepAsset.getUuid());
		// The group score is the *minimum* member score - how close a call the whole proposal is. It is
		// DEMO_FINGERPRINT_PAIR_SCORE because that is what the fingerprints seeded for these two assets
		// actually score against each other: a review queue that disagrees with the similarity index it
		// claims to come from teaches a reader arithmetic that does not exist.
		group.setScore(DEMO_FINGERPRINT_PAIR_SCORE);
		dedupGroupDao.storeGroup(group);

		// The member sizes are the assets' own, so the "keep the largest complete candidate" rule the
		// machine applied can be checked against the two rows the reviewer is shown.
		dedupGroupDao.addMember(group.getUuid(), keepAsset.getUuid(), DedupGroupMember.ROLE_KEEP, 1.0f, keepAsset.getSize(), 0L);
		dedupGroupDao.addMember(group.getUuid(), dupAsset.getUuid(), DedupGroupMember.ROLE_DUP, DEMO_FINGERPRINT_PAIR_SCORE,
			dupAsset.getSize(), 0L);

		log.info("Created demo dedup review group: {} vs {}", keepAsset.getFilename(), dupAsset.getFilename());
		return dupAsset;
	}

	/**
	 * Perceptual fingerprints for the demo videos, so the similarity index has a corpus to be switched on over.
	 *
	 * <p>
	 * Without these rows the <code>fingerprint</code> index reports zero documents on <code>/admin/indices</code> and
	 * <code>GET /assets/:uuid/similar-assets</code> answers an empty list for every demo asset, which reads as a broken feature rather than an empty
	 * one. See spec/loom/SEARCH_LUCENE.md.
	 * </p>
	 *
	 * <p>
	 * <b>Independent of <code>LOOM_SIMILARITY_ENABLED</code>.</b> These are <code>asset_fingerprint_comp</code> rows, and that table is the
	 * system-of-record; the Lucene index is a derived cache. Seeding it here would be wrong even if the index were open - the demo writes through the
	 * DAO rather than the REST layer, so no write hook runs. An operator who switches similarity on runs a <code>REINDEX</code> job and the index is
	 * built from exactly these rows.
	 * </p>
	 *
	 * <p>
	 * <b>The distances are chosen, not arbitrary.</b> A fingerprint decodes to 256 components that are each 0 or 1, and the index scores Euclidean
	 * neighbours as <code>1 / (1 + d²)</code> - so with 0/1 components the score is <code>1 / (1 + hamming)</code>. One differing bit is therefore
	 * {@link #DEMO_FINGERPRINT_PAIR_SCORE 0.5}, the highest score any pair of <em>distinct</em> fingerprints can reach, and the unrelated videos sit at
	 * least 64 bits away (0.016 and below), well under the 0.10 floor. A query for the original consequently returns its re-encode and nothing else.
	 * </p>
	 *
	 * <p>
	 * Static, and taking its DAO rather than reading the field, so a test can drive this exact step: {@link #init()} only runs against an empty asset
	 * table and the pooled test database is pre-populated, so the seeding could otherwise never be exercised.
	 * </p>
	 *
	 * @param original      the video the near-duplicate was re-encoded from
	 * @param nearDuplicate the re-encode; its bit data differs from the original's in a single byte, by a single bit
	 * @param unrelated     videos that are nobody's duplicate, each far from the pair and from each other
	 * @return the components written, in the order the assets were given
	 */
	static List<AssetFingerprintComp> seedFingerprintComps(AssetComponentDao assetComponentDao, UUID userUuid, Asset original,
		Asset nearDuplicate, Asset... unrelated) {
		if (unrelated.length > DEMO_FINGERPRINT_UNRELATED_BYTES.length) {
			throw new IllegalArgumentException("Only " + DEMO_FINGERPRINT_UNRELATED_BYTES.length
				+ " distinct unrelated fingerprint patterns are defined; a fifth video would silently become a duplicate of the first.");
		}

		byte[] originalBits = new byte[FINGERPRINT_BIT_BYTES];
		Arrays.fill(originalBits, (byte) DEMO_FINGERPRINT_BASE_BYTE);
		byte[] nearDuplicateBits = originalBits.clone();
		// One bit, in one byte: the re-encode of the same footage.
		nearDuplicateBits[7] ^= 0x01;

		List<AssetFingerprintComp> comps = new ArrayList<>();
		comps.add(seedFingerprintComp(assetComponentDao, userUuid, original, demoFingerprintHex(originalBits)));
		comps.add(seedFingerprintComp(assetComponentDao, userUuid, nearDuplicate, demoFingerprintHex(nearDuplicateBits)));
		for (int i = 0; i < unrelated.length; i++) {
			comps.add(seedFingerprintComp(assetComponentDao, userUuid, unrelated[i], demoFingerprintHex(DEMO_FINGERPRINT_UNRELATED_BYTES[i])));
		}

		log.info("Created {} demo fingerprint components ({} and {} are the near-duplicate pair)", comps.size(),
			original.getFilename(), nearDuplicate.getFilename());
		return comps;
	}

	/**
	 * One fingerprint component, in the shape {@code FingerprintNode} writes: node kind {@code fingerprint}, the default algorithm and window 0, which
	 * is the only window the index reads.
	 */
	private static AssetFingerprintComp seedFingerprintComp(AssetComponentDao assetComponentDao, UUID userUuid, Asset asset, String hex) {
		AssetFingerprintComp comp = assetComponentDao.createFingerprintComp(userUuid, asset.getUuid(), DEMO_FINGERPRINT_NODE_KIND);
		comp.setAlgorithm(SimilarityOptions.DEFAULT_ALGORITHM);
		comp.setWindowIndex(0);
		comp.setFingerprint(hex);
		return assetComponentDao.upsertFingerprintComp(comp);
	}

	/**
	 * A valid v2 multi-sector fingerprint hex: 2 bytes version, 1 pad, 2 bytes vector size (256), 1 pad, then 32 bytes of bit data.
	 *
	 * <p>
	 * Shared with the endpoint tests rather than copied into them. The layout is video4j's ({@code MultiSectorFingerprintCodec}), it is not validated
	 * on the way into {@code asset_fingerprint_comp}, and a value the codec rejects is logged and skipped deep inside the Lucene module - so a second
	 * hand-written copy of the layout drifts into an index that is simply, quietly empty.
	 * </p>
	 *
	 * @param bits the 32 bytes of bit data behind the 256 component vector
	 */
	public static String demoFingerprintHex(byte[] bits) {
		if (bits.length != FINGERPRINT_BIT_BYTES) {
			throw new IllegalArgumentException("A 256 bit fingerprint carries exactly " + FINGERPRINT_BIT_BYTES + " bytes of bit data, got "
				+ bits.length);
		}
		StringBuilder builder = new StringBuilder("0002" + "00" + "0100" + "00");
		for (byte b : bits) {
			builder.append(String.format("%02x", b & 0xFF));
		}
		return builder.toString();
	}

	/**
	 * The same layout, with every one of the 32 bit-data bytes set to {@code fillByte}.
	 */
	public static String demoFingerprintHex(int fillByte) {
		byte[] bits = new byte[FINGERPRINT_BIT_BYTES];
		Arrays.fill(bits, (byte) fillByte);
		return demoFingerprintHex(bits);
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
		skill.setUuid(LoomUUID.timeOrdered());
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
		version.setUuid(LoomUUID.timeOrdered());
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
		session.setUuid(LoomUUID.timeOrdered());
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
		entry.setUuid(LoomUUID.timeOrdered());
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
		user.setUuid(LoomUUID.timeOrdered());
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
		group.setUuid(LoomUUID.timeOrdered());
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
		role.setUuid(LoomUUID.timeOrdered());
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
		pipeline.setUuid(LoomUUID.timeOrdered());
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
	private PipelineRun createPipelineRun(User admin, Pipeline pipeline, PipelineRunStatus status, int daysAgo,
		int mediaCount, int successCount, int failureCount, int skippedCount, Long durationMs) {

		PipelineRun run = pipelineRunDao.createPipelineRun(admin.getUuid(), pipeline.getUuid(), 1);
		run.setUuid(LoomUUID.timeOrdered());
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
		if (status == PipelineRunStatus.FAILED) {
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

	/**
	 * Ingest: identity, similarity and the metadata the files already carry.
	 *
	 * <p>
	 * The metadata node belongs here rather than in one of the analysis pipelines because it needs no
	 * model and no sidecar - unlike the GPU nodes, which are deliberately left out of the demo
	 * because the demo container has nothing to run them on. Reading a photo's title, keywords,
	 * licence and GPS position is part of taking the file in.
	 * </p>
	 *
	 * <p>
	 * The tag node is here for the same reason, and it is what makes the metadata <em>findable</em>:
	 * the envelope it reads lands in {@code asset_json_comp}, which no query reaches, while a tag goes
	 * into {@code tag_asset} and is folded into the asset's search document by a trigger. Two rules
	 * are enough to show it.
	 * </p>
	 */
	static JsonObject mediumDefinition() {
		return new JsonObject()
				.put("nodes", new JsonArray()
					.add(node("pn1", "filesystem-source", "File Source", "Watch ingest folder", 60, 160))
					.add(node("pn2", "filter", "Media Filter", "Route each item; unrouted items flow out the 'other' port", 260, 160))
					.add(node("pn3", "sha256", "SHA-256 Hash", "Compute hash", 460, 60))
					.add(node("pn4", "fingerprint", "Fingerprint", "Audio/video fingerprint", 460, 260))
					.add(node("pn5", "metadata", "Asset Metadata", "Read the metadata inside each file", 460, 400))
					.add(node("pn6", "tag", "Auto Tag", "Tag what the metadata says about the file", 700, 400,
						new JsonObject()
							.put("collection", "auto")
							.put("rules", new JsonArray()
								.add(new JsonObject()
									.put("id", "geotagged")
									.put("tag", "geotagged")
									.put("when", new JsonArray().add(new JsonObject()
										.put("input", "struct").put("path", "geo.lat").put("op", "EXISTS"))))
								.add(new JsonObject()
									.put("id", "licensed")
									.put("tag", "licensed")
									.put("when", new JsonArray().add(new JsonObject()
										.put("input", "struct").put("path", "rights.licenseId").put("op", "NOT_BLANK"))))))))
				.put("edges", new JsonArray()
					.add(edge("pe1", "pn1", "media", "pn2", "media"))
					// The filter routes each item down one branch; with no buckets configured every
					// item lands on the always-present 'other' port, which is what hashing consumes.
					.add(edge("pe2", "pn2", "other", "pn3", "media"))
					.add(edge("pe3", "pn2", "other", "pn4", "media"))
					// Straight off the source, deliberately bypassing the filter: this node
					// reads images, documents, audio and video alike, so filtering ahead of it would
					// only throw away metadata the library wants.
					.add(edge("pe4", "pn1", "media", "pn5", "media"))
					// The tag node needs the item as well as the metadata: 'media' is its required
					// port and 'struct' is what the rules address by path.
					.add(edge("pe5", "pn1", "media", "pn6", "media"))
					.add(edge("pe6", "pn5", "metadata", "pn6", "struct")));
	}

	/**
	 * The full production shape: route, identify, analyse and deliver.
	 *
	 * <p>
	 * {@code objectdetect} is deliberately <em>not</em> here, for the same reason as the GPU nodes:
	 * the demo container ships neither the YOLO runtime nor an ONNX model, so seeding a pipeline that
	 * cannot run would present a broken graph as an example. The demo's object detections are still
	 * attributed to it — the rows are real data about what the node produces, and only the execution
	 * is missing.
	 * </p>
	 */
	static JsonObject complexDefinition() {
		return new JsonObject()
				.put("nodes", new JsonArray()
					.add(node("pn1", "filesystem-source", "File Source", "Watch production folder", 60, 200))
					.add(node("pn2", "filter", "Media Filter", "Route each item; unrouted items flow out the 'other' port", 240, 200))
					.add(node("pn3", "sha256", "SHA-256 Hash", "Compute SHA-256", 440, 40))
					.add(node("pn4", "fingerprint", "Fingerprint", "Video fingerprint", 440, 150))
					.add(node("pn5", "facedetect", "Face Detection", "Detect faces with InspireFace", 440, 270))
					.add(node("pn6", "facedescription", "Face Description", "Describe each detected face", 680, 270))
					.add(node("pn8", "thumbnail", "Thumbnail", "Generate a contact sheet", 440, 390))
					.add(node("pn9", "s3-sink", "S3 Delivery", "Upload the contact sheet", 680, 390,
						new JsonObject().put("bucket", "media"))))
				.put("edges", new JsonArray()
					.add(edge("pe1", "pn1", "media", "pn2", "media"))
					// One filter fans its 'other' branch out to hashing, fingerprinting, face
					// detection and thumbnailing: with no buckets configured every item lands there.
					.add(edge("pe2", "pn2", "other", "pn3", "media"))
					.add(edge("pe3", "pn2", "other", "pn4", "media"))
					.add(edge("pe4", "pn2", "other", "pn5", "image"))
					.add(edge("pe5", "pn5", "detections", "pn6", "detections"))
					.add(edge("pe6", "pn2", "other", "pn8", "media"))
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
	 * Speech to text: only audio and video reach Whisper, whose transcript is then scored for sentiment
	 * and translated into English.
	 *
	 * <p>
	 * The filter sits between the source and {@code whisper} as the place a routing rule belongs —
	 * {@code whisper} declares an XOR over its audio and video inputs, so once buckets are configured
	 * the non-audio/video items route elsewhere instead of arriving with nothing to bind to. With no
	 * buckets set the demo simply passes everything through the {@code other} branch.
	 * </p>
	 *
	 * <p>
	 * Both consumers hang off the same {@code transcript} port. That is what typing it {@code text/*}
	 * buys: nothing about the transcript says who reads it, so scoring it and translating it are two
	 * edges rather than two options on one node.
	 * </p>
	 */
	static JsonObject transcriptionDefinition() {
		return new JsonObject()
				.put("nodes", new JsonArray()
					.add(node("pn1", "filesystem-source", "Media Source", "Watch the recordings folder", 60, 160))
					.add(node("pn2", "filter", "Media Filter", "Route each item; unrouted items flow out the 'other' port", 260, 160))
					.add(node("pn3", "whisper", "Transcribe", "Speech to text with Whisper", 480, 160))
					.add(node("pn4", "sentiment", "Transcript Sentiment", "Score the tone of the transcript", 700, 80))
					.add(node("pn5", "translate", "Translate to English", "Translate the transcript into English", 700, 240,
						new JsonObject().put("targetLanguage", "en"))))
				.put("edges", new JsonArray()
					.add(edge("pe1", "pn1", "media", "pn2", "media"))
					.add(edge("pe2", "pn2", "other", "pn3", "video"))
					.add(edge("pe3", "pn3", "transcript", "pn4", "text"))
					.add(edge("pe4", "pn3", "transcript", "pn5", "text")));
	}

	/**
	 * The manual-review loop, closed: what a person decided in the review screen decides where the
	 * file goes.
	 *
	 * <p>
	 * This is the only demo pipeline whose routing depends on <em>human</em> input rather than on the
	 * bytes. A reviewer rates an asset 1-10 in the workflow view, that rating is stored on the asset,
	 * and this graph reads it back: {@code >=8} is published, {@code <=2} is marked for removal, and
	 * an asset nobody has rated is tagged so it can be found and reviewed. Everything in between
	 * matches no bucket and leaves through {@code other}, which nothing consumes — an item with no
	 * decision attached is simply not acted on.
	 * </p>
	 *
	 * <p>
	 * It is also the first demo pipeline with buckets at all. The others route everything through
	 * {@code other} because a definition assembled in code used to resolve no bucket ports: the
	 * parser handed the resolver Vert.x objects it could not read. See
	 * {@code PipelineGraphParser.readOptions}.
	 * </p>
	 *
	 * <p>
	 * There is no "delete" node and this graph deliberately does not invent one — the low-rated
	 * branch <em>tags</em>, so the removal stays a separate, deliberate act on a findable set rather
	 * than a side effect of a pipeline run.
	 * </p>
	 */
	static JsonObject reviewTriageDefinition() {
		return new JsonObject()
				.put("nodes", new JsonArray()
					.add(node("pn1", "filesystem-source", "File Source", "Watch the reviewed folder", 60, 220))
					.add(node("pn2", "filter", "Rating Filter", "Route each item by the rating reviewers gave it", 260, 220,
						new JsonObject()
							.put("filterBy", "RATING")
							.put("buckets", new JsonArray()
								.add(new JsonObject().put("id", "keep").put("label", "Keep").put("match", ">=8"))
								.add(new JsonObject().put("id", "trash").put("label", "Trash").put("match", "<=2"))
								.add(new JsonObject().put("id", "unreviewed").put("label", "Unreviewed").put("match", "unrated")))))
					.add(node("pn3", "thumbnail", "Thumbnail", "Generate a contact sheet for the keepers", 500, 80))
					.add(node("pn4", "s3-sink", "Publish", "Upload the contact sheet", 720, 80,
						new JsonObject().put("bucket", "media")))
					.add(node("pn5", "tag", "Mark For Removal", "Tag what reviewers rated 2 or lower", 500, 240,
						new JsonObject()
							.put("tagBy", "RULES")
							.put("collection", "review")
							.put("rules", new JsonArray()
								.add(new JsonObject().put("id", "rejected").put("tag", "rejected")))))
					.add(node("pn6", "tag", "Mark For Review", "Tag what nobody has rated yet", 500, 400,
						new JsonObject()
							.put("tagBy", "RULES")
							.put("collection", "review")
							.put("rules", new JsonArray()
								.add(new JsonObject().put("id", "needs-review").put("tag", "needs-review"))))))
				.put("edges", new JsonArray()
					.add(edge("pe1", "pn1", "media", "pn2", "media"))
					// One edge per bucket port. The node writes exactly one of them per item, and the
					// engine skips every consumer wired to a port that carried nothing - that silence
					// is the routing.
					.add(edge("pe2", "pn2", "keep", "pn3", "media"))
					.add(edge("pe3", "pn3", "thumbnail", "pn4", "artifacts"))
					.add(edge("pe4", "pn2", "trash", "pn5", "media"))
					.add(edge("pe5", "pn2", "unreviewed", "pn6", "media")));
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
		token.setUuid(LoomUUID.timeOrdered());
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
	 * <p>The colours match the photograph and the caption seeded for the same asset - four colleagues round a light wooden table - so the demo reads
	 * coherently: warm tan wood, an off-white wall behind it, and one slate-blue shirt. The {@code face-0} region is the box the demo actually seeds
	 * as a detection on this asset, in the pixel coordinates of the stored (resized) picture; a region over an area no detector reported would make
	 * the two screens that show them contradict each other.
	 */
	private void createDominantColorComp(User admin, Asset asset, Box face) {
		int width = 1600;
		int height = 1067;
		AssetJsonComp comp = assetComponentDao.createJsonComp(admin.getUuid(), asset.getUuid(), "dominant-color");
		comp.setSchemaType("dominant-color");
		comp.setVariant("");
		comp.setProducerVersion("dominant-color/1");
		comp.setData(new JsonObject()
			.put("image", new JsonObject().put("width", width).put("height", height))
			.put("sampling", new JsonObject().put("maxSamples", 40000).put("clusterCount", 5).put("seed", 42).put("alphaThreshold", 128))
			.put("regions", new JsonArray()
				.add(new JsonObject()
					.put("id", "whole")
					.put("source", "image")
					.put("kind", "IMAGE")
					.put("bbox", new JsonObject().put("x", 0).put("y", 0).put("w", width).put("h", height))
					.put("pixels", 39204)
					.put("converged", true)
					.put("dominant", demoColor(0.4183d, "#C8A87C", 200, 168, 124, 34.7d, 40.9d, 63.5d,
						70.69d, 5.53d, 27.10d, 27.66d, 78.5d, "brown", "LIGHT", "MUTED", "tan", "Hellbraun", 4.62d))
					.put("palette", new JsonArray()
						.add(demoColor(0.4183d, "#C8A87C", 200, 168, 124, 34.7d, 40.9d, 63.5d,
							70.69d, 5.53d, 27.10d, 27.66d, 78.5d, "brown", "LIGHT", "MUTED", "tan", "Hellbraun", 4.62d))
						.add(demoColor(0.3306d, "#E8E4DC", 232, 228, 220, 40.0d, 20.7d, 88.6d,
							90.69d, -0.04d, 4.37d, 4.38d, 90.5d, "white", "VERY_LIGHT", "ACHROMATIC", "off white", "Cremeweiß", 2.84d))
						.add(demoColor(0.2511d, "#3E4A5B", 62, 74, 91, 215.2d, 19.0d, 30.0d,
							31.06d, -0.24d, -11.59d, 11.59d, 268.8d, "blue", "DARK", "MUTED", "slate blue", "Schieferblau", 6.15d))))
				.add(new JsonObject()
					.put("id", "face-0")
					.put("source", "facedetect")
					.put("kind", "DETECTION")
					.put("label", "face")
					.put("type", "face")
					.put("frame", 0)
					.put("confidence", 0.98d)
					.put("bbox", new JsonObject()
						.put("x", Math.round(face.x() * width))
						.put("y", Math.round(face.y() * height))
						.put("w", Math.round(face.width() * width))
						.put("h", Math.round(face.height() * height)))
					.put("pixels", 6400)
					.put("converged", true)
					.put("dominant", demoColor(0.6418d, "#C68642", 198, 134, 66, 30.9d, 53.7d, 51.8d,
						61.18d, 18.04d, 45.59d, 49.03d, 68.4d, "brown", "MEDIUM", "MUTED", "muted brown", "gedämpftes Braun", 7.68d))
					.put("palette", new JsonArray()
						.add(demoColor(0.6418d, "#C68642", 198, 134, 66, 30.9d, 53.7d, 51.8d,
							61.18d, 18.04d, 45.59d, 49.03d, 68.4d, "brown", "MEDIUM", "MUTED", "muted brown", "gedämpftes Braun", 7.68d))
						.add(demoColor(0.3582d, "#3A2A1E", 58, 42, 30, 25.7d, 31.8d, 17.3d,
							18.58d, 5.50d, 10.72d, 12.05d, 62.8d, "brown", "VERY_DARK", "ACHROMATIC", "black", "Schwarz", 0d)))))
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
			.put("caption", "Four colleagues gathered around a laptop at a light wooden table, laughing at something on the screen."));
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
			.add(new JsonObject().put("seq", 0).put("fromFrame", 0).put("toFrame", 200)
				.put("caption", "A raised view of a busy intersection: pedestrians cross in both directions while taxis wait at the line."))
			.add(new JsonObject().put("seq", 1).put("fromFrame", 201).put("toFrame", 400)
				.put("caption", "The lights change and traffic moves off, a bus pulling away behind a cyclist."));

		AssetJsonComp comp = assetComponentDao.createJsonComp(admin.getUuid(), asset.getUuid(), "captioning");
		comp.setSchemaType("video-caption");
		comp.setVariant("");
		comp.setProducerVersion("qwen25vl-awq");
		comp.setData(new JsonObject()
			.put("caption",
				"Scene 1 [frames 0-200]: A raised view of a busy intersection: pedestrians cross in both directions while taxis wait at the line.\n"
					+ "Scene 2 [frames 201-400]: The lights change and traffic moves off, a bus pulling away behind a cyclist.")
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
	//
	// Where a demo content directory is present (the demo container ships one at /demo-content) the
	// bytes are the checked-in photographs and clips. Where it is not — every plain server, because
	// this seed has no flag and runs everywhere — images are painted from the palettes below and the
	// videos become rows without bytes, which is what the demo did before the media was checked in.
	// Faces are the one thing that is never painted: a gradient does not read as a person, so the six
	// shipped crops stand in for the account and person pictures.

	/**
	 * The shipped portrait photographs, used for account pictures and person images where there is no demo content directory.
	 *
	 * <p>
	 * Three faces, each in a wide and a close framing, checked in under {@code demo/portraits/} as 512x512 JPEGs — the size an avatar (48-72px) and a
	 * person's picture gallery actually need. The two framings of one face are the same person, which is what a person's gallery is: several pictures of
	 * them. Sources are Pexels photographs (free licence, no attribution required); see {@code demo/portraits/README.txt}, which also records the crop
	 * geometry {@link DemoFace#ADMIN} reuses to cut the same framing out of the uncropped original.
	 * </p>
	 */
	private enum Portrait {

		TEAL_WIDE("portrait-teal-wide.jpg"),
		TEAL_CLOSE("portrait-teal-close.jpg"),
		FROST_WIDE("portrait-frost-wide.jpg"),
		FROST_CLOSE("portrait-frost-close.jpg"),
		VIOLET_WIDE("portrait-violet-wide.jpg"),
		VIOLET_CLOSE("portrait-violet-close.jpg");

		private final String resource;

		Portrait(String resource) {
			this.resource = resource;
		}
	}

	/**
	 * Read one shipped portrait off the classpath.
	 *
	 * @return the JPEG bytes, or null when the resource is missing — the caller then skips the picture rather than failing the whole seeding run, the same
	 *         way an unwritable upload directory only costs a preview
	 */
	private static byte[] loadPortrait(Portrait portrait) {
		String path = "/demo/portraits/" + portrait.resource;
		try (InputStream in = DemoDatabaseInitializer.class.getResourceAsStream(path)) {
			if (in == null) {
				log.warn("Demo portrait {} is not on the classpath — the picture will be skipped", path);
				return null;
			}
			return in.readAllBytes();
		} catch (IOException e) {
			log.warn("Could not read demo portrait {} — the picture will be skipped", path, e);
			return null;
		}
	}

	/**
	 * One face, from the demo media where there is any and from the shipped crops otherwise.
	 *
	 * @param close
	 *            the tight framing rather than the wide one — what an avatar wants, and the second picture in a person's gallery
	 * @return 512x512 JPEG bytes, or null when neither source has this face, in which case the caller skips the picture
	 */
	private byte[] loadFace(DemoFaceSource face, boolean close) {
		if (media.isAvailable()) {
			byte[] bytes = face.cropEdge() > 0
				// An uncropped original has one recorded square and no second framing — it is an account
				// picture, and an account has at most one. Only the already-square portraits, which are
				// person gallery material, are re-cut tighter for a second picture of the same face.
				? media.portraitCrop(face.source(), face.cropEdge(), face.cropX(), face.cropY())
				: media.portrait(face.source(), close ? PORTRAIT_CLOSE_ZOOM : 1.0);
			if (bytes != null) {
				return bytes;
			}
		}
		Portrait shipped = close ? face.close() : face.wide();
		if (shipped == null) {
			// A face that only exists in the demo media. The caller creates no picture, and the person it
			// belongs to is not seeded at all — see the persons section of init().
			return null;
		}
		return loadPortrait(shipped);
	}

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
	 * One photograph from the demo roster: the checked-in file where there is one, the painted stand-in where there is not.
	 */
	private Asset createImageAsset(User admin, Library library, DemoImage image) {
		return createMediaImageAsset(admin, library, image.filename(), image.source(), image.palette());
	}

	/**
	 * An image asset backed by a file below the demo content directory, falling back to a painted one.
	 *
	 * <p>
	 * The stored bytes are the photograph resized to {@link DemoMediaLibrary#MAX_IMAGE_EDGE} — see that constant for why the original is not what the
	 * demo serves. Always {@code image/jpeg}: the library re-encodes, so the mime type describes what was stored rather than what was read.
	 * </p>
	 */
	private Asset createMediaImageAsset(User admin, Library library, String filename, String source, Palette fallback) {
		byte[] bytes = media.isAvailable() ? media.image(source) : null;
		if (bytes == null) {
			return createPaintedImageAsset(admin, library, filename, "image/jpeg", "/demo/photos/" + filename, fallback);
		}
		return createBinaryBackedAsset(admin, library, filename, "image/jpeg", "/demo/photos/" + filename, bytes, true);
	}

	/**
	 * An image asset whose bytes are painted rather than photographed.
	 *
	 * <p>
	 * The palette is salted with the filename, so two assets sharing a palette still get different bytes — and therefore different content hashes,
	 * which is what an asset is keyed by.
	 * </p>
	 */
	private Asset createPaintedImageAsset(User admin, Library library, String filename, String mimeType, String origin, Palette palette) {
		byte[] bytes = renderDemoImage(palette, mimeType, filename.hashCode());
		return createBinaryBackedAsset(admin, library, filename, mimeType, origin, bytes, true);
	}

	/**
	 * One demo video: the checked-in clip where there is one, and otherwise a row with no bytes.
	 *
	 * <p>
	 * The {@code asset_video_comp} row is written either way. Its numbers describe the file the demo is about, which is a fact about that file rather
	 * than about whether this installation happens to carry it — and without a duration the asset detail timeline divides by zero.
	 * </p>
	 */
	private Asset createVideoAsset(User admin, Library library, DemoVideo video) {
		String origin = "/demo/videos/" + video.filename();
		Path source = media.isAvailable() ? media.file(video.source()) : null;

		Asset asset = source != null
			? createFileBackedAsset(admin, library, video.filename(), "video/mp4", origin, source)
			: createAsset(admin, video.filename(), "video/mp4", video.sizeBytes(), origin);

		seedVideoComp(admin, asset, video);
		return asset;
	}

	/**
	 * Create an asset backed by a file on disk: hash it as it is copied, store it content-addressed under the configured upload directory, and record
	 * the {@code asset_location} row the download endpoint resolves.
	 *
	 * <p>
	 * Streamed rather than read into a byte array: the demo container runs with a 512 MB heap and the largest clip is 11 MB, which is survivable but
	 * pointless to hold.
	 * </p>
	 */
	private Asset createFileBackedAsset(User admin, Library library, String filename, String mimeType, String origin, Path source) {
		SHA512 sha512 = sha512Of(source);
		long size;
		try {
			size = Files.size(source);
		} catch (IOException e) {
			log.warn("Could not measure demo media {} — the asset will be created without bytes", source, e);
			return createAsset(admin, filename, mimeType, 0, origin);
		}
		if (sha512 == null) {
			return createAsset(admin, filename, mimeType, size, origin);
		}

		Asset asset = storeAssetRow(admin, sha512, mimeType, filename, origin, size);
		String path = storeBinary(source, sha512);
		linkBinary(admin, library, asset, path, mimeType);
		log.info("Created demo asset with binary: {} ({} bytes, from {})", filename, size, source.getFileName());
		return asset;
	}

	/**
	 * Create an asset backed by bytes already in memory.
	 *
	 * <p>
	 * The asset's sha512 and size are the ones of the stored file — a demo asset whose hash did not match its bytes would break the very dedupe story
	 * the product is built on.
	 * </p>
	 *
	 * @param describeImage
	 *            whether to write the {@code asset_image_comp} row carrying the stored picture's dimensions
	 */
	private Asset createBinaryBackedAsset(User admin, Library library, String filename, String mimeType, String origin, byte[] bytes,
		boolean describeImage) {
		SHA512 sha512 = SHA512.fromString(hex(digest("SHA-512", bytes)));
		Asset asset = storeAssetRow(admin, sha512, mimeType, filename, origin, bytes.length);

		String path = storeBinary(bytes, sha512);
		linkBinary(admin, library, asset, path, mimeType);
		if (describeImage) {
			seedImageComp(admin, asset, bytes);
		}
		log.info("Created demo asset with binary: {} ({} bytes)", filename, bytes.length);
		return asset;
	}

	private Asset storeAssetRow(User admin, SHA512 sha512, String mimeType, String filename, String origin, long size) {
		Asset asset = assetDao.createAsset(admin, sha512, mimeType, filename, origin, size);
		asset.setUuid(LoomUUID.timeOrdered());
		asset.setCreator(admin);
		asset.setEditor(admin);
		asset.setCreated(Instant.now());
		asset.setEdited(Instant.now());
		asset.setFirstSeen(Instant.now());
		assetDao.store(asset);
		return asset;
	}

	/** Record the {@code asset_location} row, unless the bytes could not be written and there is nothing to point at. */
	private void linkBinary(User admin, Library library, Asset asset, String path, String mimeType) {
		if (path == null) {
			return;
		}
		AssetBinary binary = assetBinaryDao.createAssetBinary(path, asset.getUuid(), admin.getUuid(), library.getUuid());
		binary.setUuid(LoomUUID.timeOrdered());
		binary.setMimeType(mimeType);
		binary.setCreator(admin);
		binary.setEditor(admin);
		binary.setCreated(Instant.now());
		binary.setEdited(Instant.now());
		assetBinaryDao.store(binary);
	}

	/**
	 * Describe the picture that was actually stored.
	 *
	 * <p>
	 * Measured from the stored bytes rather than from the palette or from the source file, because the initializer resizes on the way in: the
	 * dimensions the UI shows have to be the dimensions of the file it can download.
	 * </p>
	 */
	private void seedImageComp(User admin, Asset asset, byte[] bytes) {
		BufferedImage decoded;
		try {
			decoded = ImageIO.read(new ByteArrayInputStream(bytes));
		} catch (IOException e) {
			log.warn("Could not measure the stored demo image for {} — it will have no dimensions", asset.getFilename(), e);
			return;
		}
		if (decoded == null) {
			return;
		}
		AssetImageComp comp = assetComponentDao.createImageComp(admin.getUuid(), asset.getUuid(), DEMO_MEDIA_NODE_KIND);
		comp.setStreamIndex(0);
		comp.setMediaWidth(decoded.getWidth());
		comp.setMediaHeight(decoded.getHeight());
		comp.setImageEncoding("image/jpeg".equals(asset.getMimeType()) ? "jpeg" : "png");
		assetComponentDao.upsertImageComp(comp);
	}

	/** Describe the video stream, from the table beside {@link DemoVideo} rather than from a probe the container does not ship. */
	private void seedVideoComp(User admin, Asset asset, DemoVideo video) {
		AssetVideoComp comp = assetComponentDao.createVideoComp(admin.getUuid(), asset.getUuid(), DEMO_MEDIA_NODE_KIND);
		comp.setStreamIndex(0);
		comp.setMediaWidth(video.width());
		comp.setMediaHeight(video.height());
		comp.setMediaDuration(video.durationMs());
		comp.setFrameCount(video.frameCount());
		comp.setFps(video.fps());
		comp.setVideoEncoding("h264");
		comp.setRotation(0);
		assetComponentDao.upsertVideoComp(comp);
	}

	/**
	 * Hash a file without holding it.
	 *
	 * @return the digest, or null when the file cannot be read — the caller then creates the asset without bytes
	 */
	private static SHA512 sha512Of(Path source) {
		try (InputStream in = Files.newInputStream(source);
			DigestInputStream digest = new DigestInputStream(in, MessageDigest.getInstance("SHA-512"))) {
			byte[] buffer = new byte[64 * 1024];
			while (digest.read(buffer) != -1) {
				// Reading is the work; the digest is updated as a side effect.
			}
			return SHA512.fromString(hex(digest.getMessageDigest().digest()));
		} catch (IOException | NoSuchAlgorithmException e) {
			log.warn("Could not hash demo media {} — the asset will be created without bytes", source, e);
			return null;
		}
	}

	/**
	 * Write the bytes into the upload directory using the same {@code ab/cd/ef/<sha512>} layout the upload endpoint uses.
	 *
	 * @return the stored path, or null when the directory is not writable — the asset is still created, it just has no preview
	 */
	private String storeBinary(byte[] bytes, SHA512 sha512) {
		Path target = binaryTarget(sha512);
		try {
			Files.createDirectories(target.getParent());
			if (!Files.exists(target)) {
				Files.write(target, bytes);
			}
			return target.toString();
		} catch (IOException e) {
			log.warn("Could not store demo binary at {} — the asset will have no preview", target, e);
			return null;
		}
	}

	/** The same, copying a file rather than writing a buffer. */
	private String storeBinary(Path source, SHA512 sha512) {
		Path target = binaryTarget(sha512);
		try {
			Files.createDirectories(target.getParent());
			if (!Files.exists(target)) {
				Files.copy(source, target);
			}
			return target.toString();
		} catch (IOException e) {
			log.warn("Could not store demo binary at {} — the asset will have no preview", target, e);
			return null;
		}
	}

	private Path binaryTarget(SHA512 sha512) {
		String hex = sha512.toString();
		return Paths.get(options.getStorage().getUploadDirectory(), hex.substring(0, 2), hex.substring(2, 4), hex.substring(4, 6))
			.resolve(hex);
	}

	/**
	 * Paint one demo image. No text is drawn: the JRE in the demo container has no fontconfig, and a missing font would fail the whole seeding run.
	 *
	 * <p>
	 * Deterministic: the same palette and salt in, the same bytes out, so a re-run stores the same content-addressed file.
	 * </p>
	 *
	 * @param seedSalt
	 *            mixed into the palette's own seed, so two assets painted from one palette still differ. They have to: an asset is keyed by the hash
	 *            of its bytes, and the roster has sixteen entries sharing seven palettes.
	 */
	private static byte[] renderDemoImage(Palette palette, String mimeType, long seedSalt) {
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
		Random rnd = new Random(palette.seed * 31 + seedSalt);

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

	/**
	 * Put one entry in a user's inbox.
	 *
	 * <p>
	 * Written straight through the DAO: the demo seeder does not go through REST, so
	 * {@code NotificationDispatcher} never runs and nothing would otherwise land in the bell.
	 * </p>
	 */
	private void seedDemoNotification(User recipient, NotificationType type, boolean read, String title, String body, Task task) {
		Notification notification = notificationDao.createNotification(recipient.getUuid(), recipient.getUuid(), type, title);
		notification.setBody(body);
		notification.setRead(read);
		if (read) {
			notification.setReadAt(Instant.now());
		}
		if (task != null) {
			notification.setTaskUuid(task.getUuid());
		}
		notificationDao.store(notification);
		log.info("Created demo notification: {}", title);
	}
}
