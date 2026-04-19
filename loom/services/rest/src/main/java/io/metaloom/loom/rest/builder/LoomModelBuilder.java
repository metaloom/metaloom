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
	CollectionModelBuilder,
	ClusterModelBuilder,
	CommentModelBuilder,
	EmbeddingModelBuilder,
	UserModelBuilder,
	GroupModelBuilder,
	RoleModelBuilder,
	TagModelBuilder,
	TaskModelBuilder,
	TokenModelBuilder,
	LibraryModelBuilder,
	PipelineModelBuilder,
	SpaceModelBuilder,
	ReactionModelBuilder,
	WebhookModelBuilder {

	default GenericMessageResponse elementNotFound(String msg) {
		return new GenericMessageResponse().setMessage(msg);
	}

}
