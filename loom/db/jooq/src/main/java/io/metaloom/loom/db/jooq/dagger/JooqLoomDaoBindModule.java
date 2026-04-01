package io.metaloom.loom.db.jooq.dagger;

import dagger.Binds;
import dagger.Module;
import io.metaloom.loom.db.jooq.dao.annotation.AnnotationDaoImpl;
import io.metaloom.loom.db.jooq.dao.asset.AssetDaoImpl;
import io.metaloom.loom.db.jooq.dao.asset.comp.AssetComponentDaoImpl;
import io.metaloom.loom.db.jooq.dao.asset.location.AssetLocationDaoImpl;
import io.metaloom.loom.db.jooq.dao.attachment.AttachmentDaoImpl;
import io.metaloom.loom.db.jooq.dao.blacklist.BlacklistDaoImpl;
import io.metaloom.loom.db.jooq.dao.cluster.ClusterDaoImpl;
import io.metaloom.loom.db.jooq.dao.collection.CollectionDaoImpl;
import io.metaloom.loom.db.jooq.dao.comment.CommentDaoImpl;
import io.metaloom.loom.db.jooq.dao.embedding.EmbeddingDaoImpl;
import io.metaloom.loom.db.jooq.dao.group.GroupDaoImpl;
import io.metaloom.loom.db.jooq.dao.library.LibraryDaoImpl;
import io.metaloom.loom.db.jooq.dao.perm.PermissionDaoImpl;
import io.metaloom.loom.db.jooq.dao.project.ProjectDaoImpl;
import io.metaloom.loom.db.jooq.dao.reaction.ReactionDaoImpl;
import io.metaloom.loom.db.jooq.dao.role.RoleDaoImpl;
import io.metaloom.loom.db.jooq.dao.tag.TagDaoImpl;
import io.metaloom.loom.db.jooq.dao.task.TaskDaoImpl;
import io.metaloom.loom.db.jooq.dao.token.TokenDaoImpl;
import io.metaloom.loom.db.jooq.dao.user.UserDaoImpl;
import io.metaloom.loom.db.jooq.dao.webhook.WebhookDaoImpl;
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
	abstract WebhookDao webhookDao(WebhookDaoImpl dao);

	@Binds
	abstract AssetDao assetBinaryDao(AssetDaoImpl dao);

	@Binds
	abstract AssetComponentDao assetComponentDao(AssetComponentDaoImpl dao);

	@Binds
	abstract AssetLocationDao assetDao(AssetLocationDaoImpl dao);

	@Binds
	abstract AttachmentDao attachmentDao(AttachmentDaoImpl dao);

	@Binds
	abstract CollectionDao collectionDao(CollectionDaoImpl dao);

	@Binds
	abstract PermissionDao permissionDao(PermissionDaoImpl dao);

	@Binds
	abstract LibraryDao libraryDao(LibraryDaoImpl dao);

	@Binds
	abstract ProjectDao projectDao(ProjectDaoImpl dao);

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

}
