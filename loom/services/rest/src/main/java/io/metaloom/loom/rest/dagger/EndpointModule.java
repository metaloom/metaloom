package io.metaloom.loom.rest.dagger;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import io.metaloom.loom.rest.endpoint.RESTEndpoint;
import io.metaloom.loom.rest.endpoint.impl.AnnotationEndpoint;
import io.metaloom.loom.rest.endpoint.impl.AssetEndpoint;
import io.metaloom.loom.rest.endpoint.impl.AssetBinaryEndpoint;
import io.metaloom.loom.rest.endpoint.impl.AssetPoolEndpoint;
import io.metaloom.loom.rest.endpoint.impl.AttachmentEndpoint;
import io.metaloom.loom.rest.endpoint.impl.CollectionEndpoint;
import io.metaloom.loom.rest.endpoint.impl.CommentEndpoint;
import io.metaloom.loom.rest.endpoint.impl.EmbeddingEndpoint;
import io.metaloom.loom.rest.endpoint.impl.GraphQLEndpoint;
import io.metaloom.loom.rest.endpoint.impl.GroupEndpoint;
import io.metaloom.loom.rest.endpoint.impl.NodeDescriptorEndpoint;
import io.metaloom.loom.rest.endpoint.impl.LibraryEndpoint;
import io.metaloom.loom.rest.endpoint.impl.OAuth2Endpoint;
import io.metaloom.loom.rest.endpoint.impl.PipelineEndpoint;
import io.metaloom.loom.rest.endpoint.impl.PipelineEventEndpoint;
import io.metaloom.loom.rest.endpoint.impl.ProcessorEndpoint;
import io.metaloom.loom.rest.endpoint.impl.LoginEndpoint;
import io.metaloom.loom.rest.endpoint.impl.SpaceEndpoint;
import io.metaloom.loom.rest.endpoint.impl.RESTInfoEndpoint;
import io.metaloom.loom.rest.endpoint.impl.RoleEndpoint;
import io.metaloom.loom.rest.endpoint.impl.TagEndpoint;
import io.metaloom.loom.rest.endpoint.impl.TaskEndpoint;
import io.metaloom.loom.rest.endpoint.impl.UserEndpoint;
import io.metaloom.loom.rest.endpoint.impl.WebhookEndpoint;

@Module
public class EndpointModule {

	@ElementsIntoSet
	@Provides
	@RESTEndpoints
	static Set<RESTEndpoint> endpoints(
		UserEndpoint userEndpoint,
		RoleEndpoint roleEndpoint,
		GroupEndpoint groupEndpoint,
		AnnotationEndpoint annotationEndpoint,
		AssetEndpoint assetEndpoint,
		AssetBinaryEndpoint assetBinaryEndpoint,
		AssetPoolEndpoint assetPoolEndpoint,
		CollectionEndpoint collectionEndpoint,
		EmbeddingEndpoint embeddingEndpoint,
		TaskEndpoint taskEndoint,
		TagEndpoint tagEndpoint,
		WebhookEndpoint webhookEndpoint,
		LibraryEndpoint libraryEndpoint,
		PipelineEndpoint pipelineEndpoint,
		PipelineEventEndpoint pipelineEventEndpoint,
		ProcessorEndpoint processorEndpoint,
		SpaceEndpoint spaceEndpoint,
		LoginEndpoint loginEndpoint,
		OAuth2Endpoint oauth2Endpoint,
		RESTInfoEndpoint restInfoEndpoint,
		CommentEndpoint commentEndpoint,
		AttachmentEndpoint attachmentEndpoint,
		GraphQLEndpoint graphQLEndpoint,
		NodeDescriptorEndpoint nodeDescriptorEndpoint) {
		return new HashSet<>(Arrays.asList(
			userEndpoint,
			roleEndpoint,
			groupEndpoint,
			annotationEndpoint,
			assetEndpoint,
			assetBinaryEndpoint,
			assetPoolEndpoint,
			collectionEndpoint,
			embeddingEndpoint,
			taskEndoint,
			tagEndpoint,
			webhookEndpoint,
			libraryEndpoint,
			pipelineEndpoint,
			pipelineEventEndpoint,
			processorEndpoint,
			spaceEndpoint,
			loginEndpoint,
			oauth2Endpoint,
			restInfoEndpoint,
			attachmentEndpoint,
			commentEndpoint,
			graphQLEndpoint,
			nodeDescriptorEndpoint));
	}
}
