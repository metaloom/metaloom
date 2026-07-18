package io.metaloom.loom.db.dagger;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Lazy;
import io.metaloom.loom.db.model.annotation.AnnotationDao;
import io.metaloom.loom.db.model.asset.AssetComponentDao;
import io.metaloom.loom.db.model.asset.AssetDao;
import io.metaloom.loom.db.model.asset.AssetLocationDao;
import io.metaloom.loom.db.model.asset.AssetBinaryDao;
import io.metaloom.loom.db.model.attachment.AttachmentDao;
import io.metaloom.loom.db.model.blacklist.BlacklistDao;
import io.metaloom.loom.db.model.chat.ChatDao;
import io.metaloom.loom.db.model.cluster.ClusterDao;
import io.metaloom.loom.db.model.collection.CollectionDao;
import io.metaloom.loom.db.model.comment.CommentDao;
import io.metaloom.loom.db.model.detection.DetectionDao;
import io.metaloom.loom.db.model.embedding.EmbeddingDao;
import io.metaloom.loom.db.model.group.GroupDao;
import io.metaloom.loom.db.model.library.LibraryDao;
import io.metaloom.loom.db.model.person.PersonDao;
import io.metaloom.loom.db.model.pipeline.PipelineDao;
import io.metaloom.loom.db.model.pipeline.PipelineRunDao;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTaskDao;
import io.metaloom.loom.db.model.pipeline.PipelineRunItemDao;
import io.metaloom.loom.db.model.pipeline.PipelineVersionDao;
import io.metaloom.loom.db.model.pool.AssetPoolDao;
import io.metaloom.loom.db.model.perm.PermissionDao;
import io.metaloom.loom.db.model.space.SpaceDao;
import io.metaloom.loom.db.model.reaction.ReactionDao;
import io.metaloom.loom.db.model.role.RoleDao;
import io.metaloom.loom.db.model.tag.TagDao;
import io.metaloom.loom.db.model.task.TaskDao;
import io.metaloom.loom.db.model.token.TokenDao;
import io.metaloom.loom.db.model.user.UserDao;
import io.metaloom.loom.db.model.webhook.WebhookDao;

@Singleton
public class DaoCollectionImpl implements DaoCollection {

	private final Lazy<UserDao> userDao;
	private final Lazy<GroupDao> groupDao;
	private final Lazy<RoleDao> roleDao;
	private final Lazy<PermissionDao> permissionDao;
	private final Lazy<AssetDao> assetDao;
	private final Lazy<AssetComponentDao> assetComponentDao;
	private final Lazy<AssetLocationDao> assetLocationDao;
	private final Lazy<AssetBinaryDao> assetBinaryDao;
	private final Lazy<AttachmentDao> attachmentDao;
	private final Lazy<WebhookDao> webhookDao;
	private final Lazy<CollectionDao> collectionDao;
	private final Lazy<LibraryDao> libraryDao;
	private final Lazy<SpaceDao> spaceDao;
	private final Lazy<BlacklistDao> blacklistDao;
	private final Lazy<TaskDao> taskDao;
	private final Lazy<ReactionDao> reactionDao;
	private final Lazy<AnnotationDao> annotationDao;
	private final Lazy<CommentDao> commentDao;
	private final Lazy<EmbeddingDao> embeddingDao;
	private final Lazy<ClusterDao> clusterDao;
	private final Lazy<TokenDao> tokenDao;
	private final Lazy<TagDao> tagDao;
	private final Lazy<PipelineDao> pipelineDao;
	private final Lazy<PipelineRunDao> pipelineRunDao;
	private final Lazy<PipelineVersionDao> pipelineVersionDao;
	private final Lazy<PipelineRunItemDao> pipelineRunItemDao;
	private final Lazy<PipelineNodeTaskDao> pipelineNodeTaskDao;
	private final Lazy<AssetPoolDao> assetPoolDao;
	private final Lazy<PersonDao> personDao;
	private final Lazy<DetectionDao> detectionDao;
	private final Lazy<ChatDao> chatDao;

	@Inject
	public DaoCollectionImpl(Lazy<UserDao> userDao, Lazy<PermissionDao> permissionDao,
		Lazy<RoleDao> roleDao, Lazy<GroupDao> groupDao, Lazy<AssetLocationDao> assetLocationDao, Lazy<AssetBinaryDao> assetBinaryDao, Lazy<AssetDao> assetDao,
		Lazy<AssetComponentDao> assetComponentDao,
		Lazy<WebhookDao> webhookDao, Lazy<CollectionDao> collectionDao, Lazy<LibraryDao> libraryDao,
		Lazy<AnnotationDao> annotationDao, Lazy<TaskDao> taskDao, Lazy<ReactionDao> reactionDao,
		Lazy<BlacklistDao> blacklistDao, Lazy<CommentDao> commentDao, Lazy<SpaceDao> spaceDao,
		Lazy<ClusterDao> clusterDao, Lazy<EmbeddingDao> embeddingDao, Lazy<TokenDao> tokenDao,
		Lazy<TagDao> tagDao, Lazy<AttachmentDao> attachmentDao, Lazy<PipelineDao> pipelineDao,
		Lazy<PipelineRunDao> pipelineRunDao, Lazy<PipelineVersionDao> pipelineVersionDao,
		Lazy<PipelineRunItemDao> pipelineRunItemDao, Lazy<PipelineNodeTaskDao> pipelineNodeTaskDao,
		Lazy<AssetPoolDao> assetPoolDao, Lazy<PersonDao> personDao,
		Lazy<DetectionDao> detectionDao, Lazy<ChatDao> chatDao) {
		this.userDao = userDao;
		this.groupDao = groupDao;
		this.roleDao = roleDao;
		this.assetDao = assetDao;
		this.assetComponentDao = assetComponentDao;
		this.permissionDao = permissionDao;
		this.assetLocationDao = assetLocationDao;
		this.assetBinaryDao = assetBinaryDao;
		this.webhookDao = webhookDao;
		this.libraryDao = libraryDao;
		this.collectionDao = collectionDao;
		this.annotationDao = annotationDao;
		this.taskDao = taskDao;
		this.reactionDao = reactionDao;
		this.blacklistDao = blacklistDao;
		this.commentDao = commentDao;
		this.spaceDao = spaceDao;
		this.clusterDao = clusterDao;
		this.embeddingDao = embeddingDao;
		this.tokenDao = tokenDao;
		this.tagDao = tagDao;
		this.attachmentDao = attachmentDao;
		this.pipelineDao = pipelineDao;
		this.pipelineRunDao = pipelineRunDao;
		this.pipelineVersionDao = pipelineVersionDao;
		this.pipelineRunItemDao = pipelineRunItemDao;
		this.pipelineNodeTaskDao = pipelineNodeTaskDao;
		this.assetPoolDao = assetPoolDao;
		this.personDao = personDao;
		this.detectionDao = detectionDao;
		this.chatDao = chatDao;
	}

	@Override
	public AssetLocationDao assetLocationDao() {
		return assetLocationDao.get();
	}

	@Override
	public AssetBinaryDao assetBinaryDao() {
		return assetBinaryDao.get();
	}

	@Override
	public AttachmentDao attachmentDao() {
		return attachmentDao.get();
	}

	@Override
	public AssetDao assetDao() {
		return assetDao.get();
	}

	@Override
	public AssetComponentDao assetComponentDao() {
		return assetComponentDao.get();
	}

	@Override
	public WebhookDao webhookDao() {
		return webhookDao.get();
	}

	@Override
	public UserDao userDao() {
		return userDao.get();
	}

	@Override
	public GroupDao groupDao() {
		return groupDao.get();
	}

	@Override
	public RoleDao roleDao() {
		return roleDao.get();
	}

	@Override
	public PermissionDao permissionDao() {
		return permissionDao.get();
	}

	@Override
	public LibraryDao libraryDao() {
		return libraryDao.get();
	}

	@Override
	public CollectionDao collectionDao() {
		return collectionDao.get();
	}

	@Override
	public AnnotationDao annotationDao() {
		return annotationDao.get();
	}

	@Override
	public ReactionDao reactionDao() {
		return reactionDao.get();
	}

	@Override
	public TaskDao taskDao() {
		return taskDao.get();
	}

	@Override
	public BlacklistDao blacklistDao() {
		return blacklistDao.get();
	}

	@Override
	public CommentDao commentDao() {
		return commentDao.get();
	}

	@Override
	public SpaceDao spaceDao() {
		return spaceDao.get();
	}

	@Override
	public ClusterDao clusterDao() {
		return clusterDao.get();
	}

	@Override
	public EmbeddingDao embeddingDao() {
		return embeddingDao.get();
	}

	@Override
	public TokenDao tokenDao() {
		return tokenDao.get();
	}

	@Override
	public TagDao tagDao() {
		return tagDao.get();
	}

	@Override
	public PipelineDao pipelineDao() {
		return pipelineDao.get();
	}

	@Override
	public PipelineRunDao pipelineRunDao() {
		return pipelineRunDao.get();
	}

	@Override
	public PipelineVersionDao pipelineVersionDao() {
		return pipelineVersionDao.get();
	}

	@Override
	public PipelineRunItemDao pipelineRunItemDao() {
		return pipelineRunItemDao.get();
	}

	@Override
	public PipelineNodeTaskDao pipelineNodeTaskDao() {
		return pipelineNodeTaskDao.get();
	}

	@Override
	public AssetPoolDao assetPoolDao() {
		return assetPoolDao.get();
	}

	@Override
	public PersonDao personDao() {
		return personDao.get();
	}

	@Override
	public DetectionDao detectionDao() {
		return detectionDao.get();
	}

	@Override
	public ChatDao chatDao() {
		return chatDao.get();
	}
}
