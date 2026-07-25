package io.metaloom.loom.test.fixture;

import java.time.Instant;
import java.util.UUID;

import io.metaloom.loom.api.annotation.AnnotationType;
import io.metaloom.loom.api.attachment.AttachmentType;
import io.metaloom.loom.api.embedding.EmbeddingType;
import io.metaloom.loom.core.dagger.LoomCoreComponent;
import io.metaloom.loom.db.model.annotation.Annotation;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetLocation;
import io.metaloom.loom.db.model.attachment.Attachment;
import io.metaloom.loom.db.model.blacklist.Blacklist;
import io.metaloom.loom.db.model.chat.Chat;
import io.metaloom.loom.db.model.cluster.Cluster;
import io.metaloom.loom.db.model.collection.Collection;
import io.metaloom.loom.db.model.comment.Comment;
import io.metaloom.loom.db.model.embedding.Embedding;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.library.Library;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.pool.AssetPool;
import io.metaloom.loom.db.model.space.Space;
import io.metaloom.loom.db.model.reaction.Reaction;
import io.metaloom.loom.db.model.role.Role;
import io.metaloom.loom.db.model.tag.AssetTag;
import io.metaloom.loom.db.model.tag.Tag;
import io.metaloom.loom.db.model.task.Task;
import io.metaloom.loom.db.model.token.Token;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.utils.StringUtils;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class TestFixtureProvider extends AbstractFixtureProvider {

	public TestFixtureProvider(LoomCoreComponent component) {
		super(component);

	}

	public void setup() {

		User user = setupACL();

		// Add library
		Library library = createLibrary(user, "4k B-roll");

		// Add asset pool
		AssetPool pool = createAssetPool(user);

		// Add Assets to library
		Asset asset = createAsset(library, user);
		AssetLocation assetLocation = createAssetLocation(library, asset, user);

		// Additional assets for richer MCP tool testing
		Asset videoAsset = createNamedAsset(user, "drone_cityscape.mp4", "video/mp4",
			"/tank/videos/drone_cityscape.mp4", 256_000_000L);
		Asset audioAsset = createNamedAsset(user, "podcast_episode_42.mp3", "audio/mpeg",
			"/tank/audio/podcast_episode_42.mp3", 48_000_000L);
		Asset pdfAsset = createNamedAsset(user, "annual_report_2025.pdf", "application/pdf",
			"/tank/docs/annual_report_2025.pdf", 2_500_000L);
		Asset imageAsset2 = createNamedAsset(user, "sunset_beach.png", "image/png",
			"/tank/images/sunset_beach.png", 8_400_000L);

		// Tag assets
		Tag assetTag = tagAsset(user, asset, "red");

		// Annotate asset
		Annotation annotation = annotateAsset(asset, user);
		Tag annotationTag = tagAnnotation(user, annotation, "important");

		// Store embedding + cluster
		// Three faces of the same asset: the embedding identity is
		// (asset, node kind, type, frame, subject), so they differ by subject index.
		Embedding embedding1 = createEmbedding(EMBEDDING_UUID, user, asset, 0);
		Embedding embedding2 = createEmbedding(UUID.randomUUID(), user, asset, 1);
		Embedding embedding3 = createEmbedding(UUID.randomUUID(), user, asset, 2);
		Cluster cluster = clusterEmbeddings(user, embedding1, embedding2, embedding3);

		// Attachment for embedding
		Attachment attachment1 = createAttachment(ATTACHMENT_UUID, embedding1, user);

		// Create space
		Space space = createSpace(user);

		// Group assets into collections and assign collection to space
		Collection collection = createCollection(COLLECTION_UUID, "Collection1", space, user, asset);
		Collection videoCollection = createCollection("Drone Footage", space, user, videoAsset);
		Collection docsCollection = createCollection("Reports", space, user, pdfAsset);

		// Create task
		Task task = createTask(user);
		Comment comment = commentOn(user, task, "The comment");
		Reaction reaction1 = reactOn(REACTION_1_UUID, user, comment);
		Reaction reaction2 = reactOn(REACTION_2_UUID, user, asset);
		Reaction reaction3 = reactOn(REACTION_3_UUID, user, task);
		Reaction reaction4 = reactOn(REACTION_4_UUID, user, annotation);

		// Create blacklist with multiple entries
		Blacklist blacklist = createBlacklist(user, asset, "blocked");

		// Create chat
		Chat chat = createChat(user);

	}

	private Comment commentOn(User user, Task task, String text) {
		Comment comment = commentDao().createComment(user, "Comment title", text);
		comment.setTaskUuid(task.getUuid());
		commentDao().store(comment);
		return comment;
	}

	private Blacklist createBlacklist(User user, Asset asset, String name) {
		Blacklist blacklist = blacklistDao().createBlacklist(user, asset, name);
		blacklist.setUuid(BLACKLIST_UUID);
		blacklistDao().store(blacklist);
		return blacklist;
	}

	private Chat createChat(User user) {
		Chat chat = chatDao().createChat(user.getUuid(), "Test chat session");
		chat.setUuid(CHAT_UUID);
		chat.setMessages(new JsonArray()
			.add(new JsonObject().put("role", "user").put("content", "Hello"))
			.add(new JsonObject().put("role", "assistant").put("content", "Hi there!")));
		chatDao().store(chat);
		return chat;
	}

	private Reaction reactOn(UUID uuid, User user, Asset asset) {
		Reaction reaction = reactionDao().createReaction(user, IMAGE_MIMETYPE);
		reaction.setUuid(uuid);
		reaction.setAssetUuid(asset.getUuid());
		reactionDao().store(reaction);
		return reaction;
	}

	private Reaction reactOn(UUID uuid, User user, Task task) {
		Reaction reaction = reactionDao().createReaction(user, IMAGE_MIMETYPE);
		reaction.setUuid(uuid);
		reaction.setTaskUuid(task.getUuid());
		reactionDao().store(reaction);
		return reaction;
	}

	private Reaction reactOn(UUID uuid, User user, Comment comment) {
		Reaction reaction = reactionDao().createReaction(user, IMAGE_MIMETYPE);
		reaction.setUuid(uuid);
		reaction.setCommentUuid(comment.getUuid());
		reactionDao().store(reaction);
		return reaction;
	}

	private Reaction reactOn(UUID uuid, User user, Annotation task) {
		Reaction reaction = reactionDao().createReaction(user, "thumbsup");
		reaction.setUuid(uuid);
		reaction.setAnnotationUuid(task.getUuid());
		reactionDao().store(reaction);
		return reaction;
	}

	private Task createTask(User user) {
		Task task = taskDao().createTask(user, "job1");
		task.setUuid(TASK_UUID);
		taskDao().store(task);
		return task;
	}

	private Collection createCollection(String name, Space space, User user, Asset asset) {
		return createCollection(null, name, space, user, asset);
	}

	private Collection createCollection(UUID uuid, String name, Space space, User user, Asset asset) {
		Collection collection = collectionDao().createCollection(user, name);
		if (uuid != null) {
			collection.setUuid(uuid);
		}
		collectionDao().store(collection);
		collectionDao().link(collection, asset);
		return collection;
	}

	private Space createSpace(User user) {
		Space space = spaceDao().createSpace(user, "test-space");
		space.setUuid(PROJECT_UUID);
		spaceDao().store(space);
		return space;
	}

	private Cluster clusterEmbeddings(User user, Embedding... embeddings) {
		Cluster cluster = clusterDao().createCluster(user, "Cats", "PERSON");
		cluster.setUuid(CLUSTER_UUID);
		clusterDao().store(cluster);
		for (Embedding embedding : embeddings) {
			clusterDao().link(cluster, embedding);
		}
		return cluster;
	}

	private Embedding createEmbedding(UUID uuid, User user, Asset asset, int subjectIndex) {
		Embedding embedding = embeddingDao().createEmbedding(user, asset, VECTOR_DATA, EmbeddingType.DLIB_FACE_RESNET_v1);
		embedding.setUuid(uuid);
		embedding.setSubjectIndex(subjectIndex);
		embeddingDao().store(embedding);
		return embedding;
	}

	private Tag tagAnnotation(User user, Annotation annotation, String tagName) {
		Tag tag = tagDao().createTag(user, tagName, "feedback");
		tag.setUuid(TAG_UUID);
		tagDao().store(tag);
		tagDao().tagAnnotation(tag, annotation);
		return tag;
	}

	private Annotation annotateAsset(Asset asset, User user) {
		Annotation annotation = annotationDao().createAnnotation(user, asset, "feedback here", AnnotationType.FEEDBACK);
		annotation.setUuid(ANNOTATION_UUID);
		annotationDao().store(annotation);
		return annotation;
	}

	private Tag tagAsset(User user, Asset asset, String name) {
		AssetTag tag = tagDao().createAssetTag(user, name, "colors");
		tagDao().store(tag);
		tagDao().tagAsset(tag, asset);
		return tag;
	}

	private Asset createAsset(Library library, User user) {
		Asset asset = assetDao().createAsset(user, SHA512SUM, IMAGE_MIMETYPE, DUMMY_IMAGE_FILENAME, DUMMY_IMAGE_ORIGIN, 42L);
		asset.setUuid(ASSET_UUID);
		asset.setFilename("bigbuckbunny.mp4");
		// The secondary hashes are what a fully processed asset looks like; tests that read
		// them back (e.g. the GraphQL asset query) need them populated.
		asset.setSHA256(SHA256SUM);
		asset.setMD5(MD5SUM);
		assetDao().store(asset);
		return asset;
	}

	/**
	 * Create a named asset with the given properties. Uses a random UUID
	 * and a deterministic SHA-512 hash derived from the filename.
	 */
	private Asset createNamedAsset(User user, String filename, String mimeType, String origin, long size) {
		// Generate a deterministic SHA-512 hex string from the filename hash code
		String hexSeed = String.format("%08x", filename.hashCode());
		String sha512Hex = (hexSeed).repeat(16); // 128 hex chars = 64 bytes
		SHA512 sha = SHA512.fromString(sha512Hex);
		Asset asset = assetDao().createAsset(user, sha, mimeType, filename, origin, size);
		assetDao().store(asset);
		return asset;
	}

	private Attachment createAttachment(UUID attachmentUuid, Embedding embedding, User user) {
		Attachment attachment = attachmentDao().createAttachment(user.getUuid(), SHA512SUM_3, DUMMY_IMAGE_FILENAME, 42L, IMAGE_MIMETYPE,
			AttachmentType.EMBEDDING_ATTACHMENT);
		attachment.setEmbeddingUuid(embedding.getUuid());
		attachment.setAssetUuid(embedding.getAssetUuid());
		attachment.setUuid(attachmentUuid);
		attachmentDao().store(attachment);
		return attachment;
	}

	private AssetLocation createAssetLocation(Library library, Asset asset, User user) {
		AssetLocation assetLocation = assetLocationDao().createAssetLocation("blume.mp4", asset.getUuid(), user.getUuid(), library.getUuid());
		assetLocation.setUuid(ASSET_LOCATION_UUID);
		assetLocationDao().store(assetLocation);
		return assetLocation;
	}

	private AssetPool createAssetPool(User user) {
		AssetPool pool = assetPoolDao().createAssetPool(user.getUuid(), "primary-storage");
		pool.setUuid(ASSET_POOL_UUID);
		pool.setFsPath("/tank/loom/binaries");
		assetPoolDao().store(pool);
		return pool;
	}

	private Library createLibrary(User user, String name) {
		Library library = libraryDao().createLibrary(user, name);
		library.setUuid(LIBRARY_UUID);
		libraryDao().store(library);
		return library;
	}

	private User setupACL() {

		// User
		User adminUser = userDao().createAdmin();
		adminUser.setUuid(ADMIN_UUID);
		adminUser.setCreator(adminUser);
		adminUser.setEditor(adminUser);
		adminUser.setEdited(Instant.now());
		adminUser.setCreated(Instant.now());
		adminUser.setPasswordHash(authService.encodePassword("finger"));
		userDao().store(adminUser);

		// Group + Assign User to Group
		Group group = groupDao().create(adminUser, "test-group");
		group.setUuid(GROUP_UUID);
		groupDao().store(group);
		groupDao().addUserToGroup(group, adminUser);

		// Role + Assign Role to Group + Role Permission
		Role role = roleDao().createRole(adminUser.getUuid(), "test-role");
		role.setUuid(ROLE_UUID);
		roleDao().store(role);
		groupDao().addRoleToGroup(group, role);
		for (Permission perm : Permission.values()) {
			permissionDao().grantRolePermission(role.getUuid(), perm, "test");
		}

		// Second user
		User joeDoeUser = userDao().createUser("joedoe");
		joeDoeUser.setUuid(USER_UUID);
		joeDoeUser.setCreator(adminUser);
		joeDoeUser.setEditor(adminUser);
		joeDoeUser.setEdited(Instant.now());
		joeDoeUser.setCreated(Instant.now());
		joeDoeUser.setPasswordHash(authService.encodePassword("finger"));
		userDao().store(joeDoeUser);
		permissionDao().grantUserPermission(joeDoeUser.getUuid(), Permission.READ_USER, "test");

		// Create token + Permissions
		String tokenValue = StringUtils.randomHumanString(6);
		Token token = tokenDao().createToken(joeDoeUser, "test_token", tokenValue);
		token.setUuid(TOKEN_UUID);
		tokenDao().store(token);

		return joeDoeUser;
	}

}
