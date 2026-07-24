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
import io.metaloom.loom.db.model.collection.CollectionDao;
import io.metaloom.loom.db.model.cortex.CortexInstanceDao;
import io.metaloom.loom.db.model.comment.CommentDao;
import io.metaloom.loom.db.model.embedding.EmbeddingDao;
import io.metaloom.loom.db.model.group.GroupDao;
import io.metaloom.loom.db.model.library.LibraryDao;
import io.metaloom.loom.db.model.loom.LoomDao;
import io.metaloom.loom.db.model.perm.PermissionDao;
import io.metaloom.loom.db.model.detection.DetectionDao;
import io.metaloom.loom.db.model.person.PersonDao;
import io.metaloom.loom.db.model.pipeline.PipelineDao;
import io.metaloom.loom.db.model.pipeline.PipelineRunDao;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTaskDao;
import io.metaloom.loom.db.model.pipeline.PipelineRunItemDao;
import io.metaloom.loom.db.model.pipeline.PipelineVersionDao;
import io.metaloom.loom.db.model.pool.AssetPoolDao;
import io.metaloom.loom.db.model.space.SpaceDao;
import io.metaloom.loom.db.model.reaction.ReactionDao;
import io.metaloom.loom.db.model.role.RoleDao;
import io.metaloom.loom.db.model.skill.SkillDao;
import io.metaloom.loom.db.model.skill.SkillVersionDao;
import io.metaloom.loom.db.model.tag.TagDao;
import io.metaloom.loom.db.model.task.TaskDao;
import io.metaloom.loom.db.model.token.TokenDao;
import io.metaloom.loom.db.model.user.UserDao;
import io.metaloom.loom.db.model.webhook.WebhookDao;

public interface DaoProvider extends DaoCollection {

	DaoCollection daos();

	// ACL

	default UserDao userDao() {
		return daos().userDao();
	}

	default GroupDao groupDao() {
		return daos().groupDao();
	}

	default RoleDao roleDao() {
		return daos().roleDao();
	}

	default PermissionDao permissionDao() {
		return daos().permissionDao();
	}

	default TokenDao tokenDao() {
		return daos().tokenDao();
	}

	// Connectivity

	default WebhookDao webhookDao() {
		return daos().webhookDao();
	}

	// Asset

	default AssetDao assetDao() {
		return daos().assetDao();
	}

	default AssetLocationDao assetLocationDao() {
		return daos().assetLocationDao();
	}

	default AssetBinaryDao assetBinaryDao() {
		return daos().assetBinaryDao();
	}

	default AssetComponentDao assetComponentDao() {
		return daos().assetComponentDao();
	}

	default AssetNodeResultDao assetNodeResultDao() {
		return daos().assetNodeResultDao();
	}
	
	// Attachment
	
	default AttachmentDao attachmentDao() {
		return daos().attachmentDao();
	}


	// Management

	default SpaceDao spaceDao() {
		return daos().spaceDao();
	}

	default LibraryDao libraryDao() {
		return daos().libraryDao();
	}

	default CollectionDao collectionDao() {
		return daos().collectionDao();
	}

	default BlacklistDao blacklistDao() {
		return daos().blacklistDao();
	}

	// Tagging

	default TagDao tagDao() {
		return daos().tagDao();
	}

	// Embedding

	default EmbeddingDao embeddingDao() {
		return daos().embeddingDao();
	}

	default ClusterDao clusterDao() {
		return daos().clusterDao();
	}

	// Social

	default TaskDao taskDao() {
		return daos().taskDao();
	}

	default AnnotationDao annotationDao() {
		return daos().annotationDao();
	}

	default ReactionDao reactionDao() {
		return daos().reactionDao();
	}

	default CommentDao commentDao() {
		return daos().commentDao();
	}

	// Pipeline

	default PipelineDao pipelineDao() {
		return daos().pipelineDao();
	}

	default PipelineRunDao pipelineRunDao() {
		return daos().pipelineRunDao();
	}

	default PipelineVersionDao pipelineVersionDao() {
		return daos().pipelineVersionDao();
	}

	default PipelineRunItemDao pipelineRunItemDao() {
		return daos().pipelineRunItemDao();
	}

	default PipelineNodeTaskDao pipelineNodeTaskDao() {
		return daos().pipelineNodeTaskDao();
	}

	// Asset Pool

	default AssetPoolDao assetPoolDao() {
		return daos().assetPoolDao();
	}

	// Person

	default PersonDao personDao() {
		return daos().personDao();
	}

	// Detection

	default DetectionDao detectionDao() {
		return daos().detectionDao();
	}

	// Chat

	default ChatDao chatDao() {
		return daos().chatDao();
	}

	// Skill

	default SkillDao skillDao() {
		return daos().skillDao();
	}

	default SkillVersionDao skillVersionDao() {
		return daos().skillVersionDao();
	}

	// Chat Session

	default ChatSessionDao chatSessionDao() {
		return daos().chatSessionDao();
	}

	// Cortex Instance

	default CortexInstanceDao cortexInstanceDao() {
		return daos().cortexInstanceDao();
	}

	// System

	default LoomDao loomDao() {
		return daos().loomDao();
	}

}
