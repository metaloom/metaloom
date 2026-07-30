package io.metaloom.loom.db.jooq.dagger;

import dagger.Binds;
import dagger.Module;
import io.metaloom.loom.db.jooq.dao.annotation.AnnotationDaoImpl;
import io.metaloom.loom.db.jooq.dao.asset.AssetDaoImpl;
import io.metaloom.loom.db.jooq.dao.asset.AssetNodeResultDaoImpl;
import io.metaloom.loom.db.jooq.dao.asset.comp.AssetComponentDaoImpl;
import io.metaloom.loom.db.jooq.dao.asset.location.AssetLocationDaoImpl;
import io.metaloom.loom.db.jooq.dao.asset.binary.AssetBinaryDaoImpl;
import io.metaloom.loom.db.jooq.dao.attachment.AttachmentDaoImpl;
import io.metaloom.loom.db.jooq.dao.blacklist.BlacklistDaoImpl;
import io.metaloom.loom.db.jooq.dao.chat.ChatDaoImpl;
import io.metaloom.loom.db.jooq.dao.chatsession.ChatSessionDaoImpl;
import io.metaloom.loom.db.jooq.dao.memory.MemoryDenyRuleDaoImpl;
import io.metaloom.loom.db.jooq.dao.memory.MemoryEntryDaoImpl;
import io.metaloom.loom.db.jooq.dao.skill.SkillDaoImpl;
import io.metaloom.loom.db.jooq.dao.skill.SkillVersionDaoImpl;
import io.metaloom.loom.db.jooq.dao.cluster.ClusterDaoImpl;
import io.metaloom.loom.db.jooq.dao.collection.CollectionDaoImpl;
import io.metaloom.loom.db.jooq.dao.comment.CommentDaoImpl;
import io.metaloom.loom.db.jooq.dao.embedding.EmbeddingDaoImpl;
import io.metaloom.loom.db.jooq.dao.pipeline.PipelineDaoImpl;
import io.metaloom.loom.db.jooq.dao.pipeline.PipelineRunDaoImpl;
import io.metaloom.loom.db.jooq.dao.pipeline.PipelineNodeTaskDaoImpl;
import io.metaloom.loom.db.jooq.dao.pipeline.PipelineRunItemDaoImpl;
import io.metaloom.loom.db.jooq.dao.pipeline.PipelineVersionDaoImpl;
import io.metaloom.loom.db.jooq.dao.pool.AssetPoolDaoImpl;
import io.metaloom.loom.db.jooq.dao.group.GroupDaoImpl;
import io.metaloom.loom.db.jooq.dao.library.LibraryDaoImpl;
import io.metaloom.loom.db.jooq.dao.loom.LoomDaoImpl;
import io.metaloom.loom.db.jooq.dao.perm.PermissionDaoImpl;
import io.metaloom.loom.db.jooq.dao.cortex.CortexInstanceDaoImpl;
import io.metaloom.loom.db.jooq.dao.detection.DetectionDaoImpl;
import io.metaloom.loom.db.jooq.dao.person.PersonDaoImpl;
import io.metaloom.loom.db.jooq.dao.space.SpaceDaoImpl;
import io.metaloom.loom.db.jooq.dao.reaction.ReactionDaoImpl;
import io.metaloom.loom.db.jooq.dao.role.RoleDaoImpl;
import io.metaloom.loom.db.jooq.dao.tag.TagDaoImpl;
import io.metaloom.loom.db.jooq.dao.task.TaskDaoImpl;
import io.metaloom.loom.db.jooq.dao.token.TokenDaoImpl;
import io.metaloom.loom.db.jooq.dao.user.UserDaoImpl;
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
import io.metaloom.loom.db.model.comment.CommentDao;
import io.metaloom.loom.db.model.embedding.EmbeddingDao;
import io.metaloom.loom.db.model.cortex.CortexInstanceDao;
import io.metaloom.loom.db.model.detection.DetectionDao;
import io.metaloom.loom.db.model.person.PersonDao;
import io.metaloom.loom.db.model.pipeline.PipelineDao;
import io.metaloom.loom.db.model.pipeline.PipelineRunDao;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTaskDao;
import io.metaloom.loom.db.model.pipeline.PipelineRunItemDao;
import io.metaloom.loom.db.model.pipeline.PipelineVersionDao;
import io.metaloom.loom.db.model.pool.AssetPoolDao;
import io.metaloom.loom.db.model.group.GroupDao;
import io.metaloom.loom.db.model.library.LibraryDao;
import io.metaloom.loom.db.model.loom.LoomDao;
import io.metaloom.loom.db.model.perm.PermissionDao;
import io.metaloom.loom.db.model.space.SpaceDao;
import io.metaloom.loom.db.model.reaction.ReactionDao;
import io.metaloom.loom.db.model.role.RoleDao;
import io.metaloom.loom.db.model.memory.MemoryDenyRuleDao;
import io.metaloom.loom.db.model.memory.MemoryEntryDao;
import io.metaloom.loom.db.model.skill.SkillDao;
import io.metaloom.loom.db.model.skill.SkillVersionDao;
import io.metaloom.loom.db.model.tag.TagDao;
import io.metaloom.loom.db.model.task.TaskDao;
import io.metaloom.loom.db.model.token.TokenDao;
import io.metaloom.loom.db.model.user.UserDao;

@Module
public abstract class JooqLoomDaoBindModule {

	@Binds
	abstract UserDao bindLoomUserDao(UserDaoImpl e);

	@Binds
	abstract GroupDao bindGroupDao(GroupDaoImpl e);

	@Binds
	abstract RoleDao roleDao(RoleDaoImpl dao);

	@Binds
	abstract TokenDao tokenDao(TokenDaoImpl dao);

	@Binds
	abstract TagDao tagDao(TagDaoImpl dao);

	@Binds
	abstract AssetDao assetDao(AssetDaoImpl dao);

	@Binds
	abstract AssetComponentDao assetComponentDao(AssetComponentDaoImpl dao);

	@Binds
	abstract AssetNodeResultDao assetNodeResultDao(AssetNodeResultDaoImpl dao);

	@Binds
	abstract io.metaloom.loom.db.model.dedup.DedupGroupDao dedupGroupDao(io.metaloom.loom.db.jooq.dao.dedup.DedupGroupDaoImpl dao);

	@Binds
	abstract AssetLocationDao assetLocationDao(AssetLocationDaoImpl dao);

	@Binds
	abstract AssetBinaryDao assetBinaryDao(AssetBinaryDaoImpl dao);

	@Binds
	abstract AttachmentDao attachmentDao(AttachmentDaoImpl dao);

	@Binds
	abstract CollectionDao collectionDao(CollectionDaoImpl dao);

	@Binds
	abstract PermissionDao permissionDao(PermissionDaoImpl dao);

	@Binds
	abstract LibraryDao libraryDao(LibraryDaoImpl dao);

	@Binds
	abstract SpaceDao spaceDao(SpaceDaoImpl dao);

	@Binds
	abstract TaskDao taskDao(TaskDaoImpl dao);

	@Binds
	abstract CommentDao commentDao(CommentDaoImpl dao);

	@Binds
	abstract ReactionDao reactionDao(ReactionDaoImpl dao);

	@Binds
	abstract BlacklistDao blacklistDao(BlacklistDaoImpl dao);

	@Binds
	abstract AnnotationDao annotationDao(AnnotationDaoImpl dao);

	@Binds
	abstract EmbeddingDao embeddingDao(EmbeddingDaoImpl dao);

	@Binds
	abstract ClusterDao clusterDao(ClusterDaoImpl dao);

	@Binds
	abstract PipelineDao pipelineDao(PipelineDaoImpl dao);

	@Binds
	abstract PipelineRunDao pipelineRunDao(PipelineRunDaoImpl dao);

	@Binds
	abstract PipelineVersionDao pipelineVersionDao(PipelineVersionDaoImpl dao);

	@Binds
	abstract PipelineRunItemDao pipelineRunItemDao(PipelineRunItemDaoImpl dao);

	@Binds
	abstract PipelineNodeTaskDao pipelineNodeTaskDao(PipelineNodeTaskDaoImpl dao);

	@Binds
	abstract AssetPoolDao assetPoolDao(AssetPoolDaoImpl dao);

	@Binds
	abstract PersonDao personDao(PersonDaoImpl dao);

	@Binds
	abstract DetectionDao detectionDao(DetectionDaoImpl dao);

	@Binds
	abstract ChatDao chatDao(ChatDaoImpl dao);

	@Binds
	abstract SkillDao skillDao(SkillDaoImpl dao);

	@Binds
	abstract SkillVersionDao skillVersionDao(SkillVersionDaoImpl dao);

	@Binds
	abstract ChatSessionDao chatSessionDao(ChatSessionDaoImpl dao);

	@Binds
	abstract MemoryEntryDao memoryEntryDao(MemoryEntryDaoImpl dao);

	@Binds
	abstract MemoryDenyRuleDao memoryDenyRuleDao(MemoryDenyRuleDaoImpl dao);

	@Binds
	abstract CortexInstanceDao cortexInstanceDao(CortexInstanceDaoImpl dao);

	@Binds
	abstract LoomDao loomDao(LoomDaoImpl dao);

}
