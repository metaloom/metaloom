package io.metaloom.loom.rest.builder;

import io.metaloom.loom.rest.model.message.GenericMessageResponse;

public interface LoomModelBuilder extends
	AssetLocationModelBuilder,
	AssetBinaryModelBuilder,
	AssetComponentModelBuilder,
	AssetPoolModelBuilder,
	AnnotationModelBuilder,
	AttachmentModelBuilder,
	AssetModelBuilder,
	BlacklistModelBuilder,
	ChatModelBuilder,
	ChatSessionModelBuilder,
	NotificationModelBuilder,
	SkillModelBuilder,
	MemoryDenyRuleModelBuilder,
	CollectionModelBuilder,
	RemixModelBuilder,
	ShareModelBuilder,
	ClusterModelBuilder,
	CommentModelBuilder,
	DetectionModelBuilder,
	EmbeddingModelBuilder,
	TranscriptModelBuilder,
	UserModelBuilder,
	GroupModelBuilder,
	RoleModelBuilder,
	TagModelBuilder,
	TaskModelBuilder,
	TokenModelBuilder,
	LibraryModelBuilder,
	PersonModelBuilder,
	PipelineModelBuilder,
	SpaceModelBuilder,
	ReactionModelBuilder,
	NodeResultModelBuilder,
	JsonCompModelBuilder,
	FingerprintCompModelBuilder,
	SegmentCompModelBuilder {

	default GenericMessageResponse elementNotFound(String msg) {
		return new GenericMessageResponse().setMessage(msg);
	}

}
