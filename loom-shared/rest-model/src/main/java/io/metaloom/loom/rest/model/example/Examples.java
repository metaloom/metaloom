package io.metaloom.loom.rest.model.example;

import io.metaloom.loom.rest.model.annotation.AnnotationExamples;
import io.metaloom.loom.rest.model.asset.AssetComponentExamples;
import io.metaloom.loom.rest.model.asset.AssetExamples;
import io.metaloom.loom.rest.model.asset.location.AssetLocationExamples;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryExamples;
import io.metaloom.loom.rest.model.attachment.AttachmentExamples;
import io.metaloom.loom.rest.model.blacklist.BlacklistExamples;
import io.metaloom.loom.rest.model.pool.AssetPoolExamples;
import io.metaloom.loom.rest.model.auth.AuthLoginExamples;
import io.metaloom.loom.rest.model.cluster.ClusterExamples;
import io.metaloom.loom.rest.model.collection.CollectionExamples;
import io.metaloom.loom.rest.model.comment.CommentExamples;
import io.metaloom.loom.rest.model.detection.DetectionExamples;
import io.metaloom.loom.rest.model.embedding.EmbeddingExamples;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.metaloom.loom.rest.model.group.GroupExamples;
import io.metaloom.loom.rest.model.library.LibraryExamples;
import io.metaloom.loom.rest.model.person.PersonExamples;
import io.metaloom.loom.rest.model.pipeline.PipelineExamples;
import io.metaloom.loom.rest.model.processor.ProcessorExamples;
import io.metaloom.loom.rest.model.space.SpaceExamples;
import io.metaloom.loom.rest.model.reaction.ReactionExamples;
import io.metaloom.loom.rest.model.role.RoleExamples;
import io.metaloom.loom.rest.model.tag.TagExamples;
import io.metaloom.loom.rest.model.task.TaskExamples;
import io.metaloom.loom.rest.model.token.TokenExamples;
import io.metaloom.loom.rest.model.transcript.TranscriptExamples;
import io.metaloom.loom.rest.model.user.UserExamples;
import io.metaloom.loom.rest.model.webhook.WebhookExamples;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface Examples extends
	AnnotationExamples,
	AuthLoginExamples,
	AssetExamples,
	AssetComponentExamples,
	AssetLocationExamples,
	AssetBinaryExamples,
	AssetPoolExamples,
	AttachmentExamples,
	BlacklistExamples,
	ClusterExamples,
	CommentExamples,
	CollectionExamples,
	DetectionExamples,
	EmbeddingExamples,
	TaskExamples,
	TagExamples,
	TokenExamples,
	RoleExamples,
	UserExamples,
	ReactionExamples,
	GroupExamples,
	SpaceExamples,
	LibraryExamples,
	PersonExamples,
	PipelineExamples,
	ProcessorExamples,
	TranscriptExamples,
	WebhookExamples {

	default Example deleteResponseExample() {
		return new ExampleImpl(null, "The delete response", HttpResponseStatus.NO_CONTENT);
	}
}
