package io.metaloom.loom.db.dagger;

import io.metaloom.loom.db.model.annotation.AnnotationDao;
import io.metaloom.loom.db.model.asset.AssetComponentDao;
import io.metaloom.loom.db.model.asset.AssetNodeResultDao;
import io.metaloom.loom.db.model.asset.AssetDao;
import io.metaloom.loom.db.model.asset.AssetLocationDao;
import io.metaloom.loom.db.model.asset.AssetBinaryDao;
import io.metaloom.loom.db.model.attachment.AttachmentDao;
import io.metaloom.loom.db.model.blacklist.BlacklistDao;
import io.metaloom.loom.db.model.chat.ChatDao;
import io.metaloom.loom.db.model.chatsession.ChatSessionDao;
import io.metaloom.loom.db.model.cluster.ClusterDao;
import io.metaloom.loom.db.model.cortex.CortexInstanceDao;
import io.metaloom.loom.db.model.collection.CollectionDao;
import io.metaloom.loom.db.model.comment.CommentDao;
import io.metaloom.loom.db.model.embedding.EmbeddingDao;
import io.metaloom.loom.db.model.group.GroupDao;
import io.metaloom.loom.db.model.library.LibraryDao;
import io.metaloom.loom.db.model.loom.LoomDao;
import io.metaloom.loom.db.model.perm.PermissionDao;
import io.metaloom.loom.db.model.space.SpaceDao;
import io.metaloom.loom.db.model.reaction.ReactionDao;
import io.metaloom.loom.db.model.role.RoleDao;
import io.metaloom.loom.db.model.memory.MemoryDenyRuleDao;
import io.metaloom.loom.db.model.memory.MemoryEntryDao;
import io.metaloom.loom.db.model.notification.NotificationDao;
import io.metaloom.loom.db.model.skill.SkillDao;
import io.metaloom.loom.db.model.skill.SkillVersionDao;
import io.metaloom.loom.db.model.tag.TagDao;
import io.metaloom.loom.db.model.task.TaskDao;
import io.metaloom.loom.db.model.token.TokenDao;
import io.metaloom.loom.db.model.user.UserDao;
import io.metaloom.loom.db.model.person.PersonDao;
import io.metaloom.loom.db.model.detection.DetectionDao;
import io.metaloom.loom.db.model.pipeline.PipelineDao;
import io.metaloom.loom.db.model.pipeline.PipelineRunDao;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTaskDao;
import io.metaloom.loom.db.model.pipeline.PipelineRunItemDao;
import io.metaloom.loom.db.model.pipeline.PipelineVersionDao;
import io.metaloom.loom.db.model.pool.AssetPoolDao;
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

	// Asset

	AssetLocationDao assetLocationDao();

	AssetBinaryDao assetBinaryDao();

	AssetDao assetDao();

	AssetComponentDao assetComponentDao();

	AssetNodeResultDao assetNodeResultDao();

	// Deduplication review

	io.metaloom.loom.db.model.dedup.DedupGroupDao dedupGroupDao();

	// Attachment

	AttachmentDao attachmentDao();

	// Management

	SpaceDao spaceDao();

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

	// Pipeline

	PipelineDao pipelineDao();

	PipelineRunDao pipelineRunDao();

	PipelineVersionDao pipelineVersionDao();

	PipelineRunItemDao pipelineRunItemDao();

	PipelineNodeTaskDao pipelineNodeTaskDao();

	// Asset Pool

	AssetPoolDao assetPoolDao();

	// Person

	PersonDao personDao();

	// Detection

	DetectionDao detectionDao();

	// Chat

	ChatDao chatDao();

	// Skill

	SkillDao skillDao();

	NotificationDao notificationDao();

	SkillVersionDao skillVersionDao();

	// Chat Session

	ChatSessionDao chatSessionDao();

	// Agent Memory

	MemoryEntryDao memoryEntryDao();

	MemoryDenyRuleDao memoryDenyRuleDao();

	// Cortex Instance

	CortexInstanceDao cortexInstanceDao();

	/**
	 * Announced node contracts, and which worker claims which.
	 *
	 * <p>What makes a custom node stay authorable while no worker is online: the contract is durable,
	 * the worker's presence is not.</p>
	 */
	io.metaloom.loom.db.model.nodes.NodeDescriptorRecordDao nodeDescriptorDao();

	// System

	LoomDao loomDao();

}
