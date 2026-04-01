package io.metaloom.loom.db.dagger;

import io.metaloom.loom.db.model.annotation.AnnotationDao;
import io.metaloom.loom.db.model.asset.AssetComponentDao;
import io.metaloom.loom.db.model.asset.AssetDao;
import io.metaloom.loom.db.model.asset.AssetLocationDao;
import io.metaloom.loom.db.model.attachment.AttachmentDao;
import io.metaloom.loom.db.model.blacklist.BlacklistDao;
import io.metaloom.loom.db.model.cluster.ClusterDao;
import io.metaloom.loom.db.model.collection.CollectionDao;
import io.metaloom.loom.db.model.comment.CommentDao;
import io.metaloom.loom.db.model.embedding.EmbeddingDao;
import io.metaloom.loom.db.model.group.GroupDao;
import io.metaloom.loom.db.model.library.LibraryDao;
import io.metaloom.loom.db.model.perm.PermissionDao;
import io.metaloom.loom.db.model.project.ProjectDao;
import io.metaloom.loom.db.model.reaction.ReactionDao;
import io.metaloom.loom.db.model.role.RoleDao;
import io.metaloom.loom.db.model.tag.TagDao;
import io.metaloom.loom.db.model.task.TaskDao;
import io.metaloom.loom.db.model.token.TokenDao;
import io.metaloom.loom.db.model.user.UserDao;
import io.metaloom.loom.db.model.webhook.WebhookDao;

/**
 * Aggregated list of all loom DAOs.
 */
public interface DaoCollection {

	// ACL

	UserDao userDao();

	GroupDao groupDao();

	RoleDao roleDao();

	PermissionDao permissionDao();

	TokenDao tokenDao();

	// Connectivity

	WebhookDao webhookDao();

	// Asset

	AssetLocationDao assetLocationDao();

	AssetDao assetDao();

	AssetComponentDao assetComponentDao();

	// Attachment

	AttachmentDao attachmentDao();

	// Management

	ProjectDao projectDao();

	LibraryDao libraryDao();

	CollectionDao collectionDao();

	BlacklistDao blacklistDao();

	// Tagging

	TagDao tagDao();

	// Embedding

	EmbeddingDao embeddingDao();

	ClusterDao clusterDao();

	// Social

	TaskDao taskDao();

	AnnotationDao annotationDao();

	ReactionDao reactionDao();

	CommentDao commentDao();

}
