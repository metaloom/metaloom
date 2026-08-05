package io.metaloom.loom.rest.dagger;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import io.metaloom.loom.rest.endpoint.RESTEndpoint;
import io.metaloom.loom.rest.endpoint.impl.AnnotationEndpoint;
import io.metaloom.loom.rest.endpoint.impl.AssetComponentEndpoint;
import io.metaloom.loom.rest.endpoint.impl.AssetEndpoint;
import io.metaloom.loom.rest.endpoint.impl.AssetBinaryEndpoint;
import io.metaloom.loom.rest.endpoint.impl.AssetPoolEndpoint;
import io.metaloom.loom.rest.endpoint.impl.AttachmentEndpoint;
import io.metaloom.loom.rest.endpoint.impl.BlacklistEndpoint;
import io.metaloom.loom.rest.endpoint.impl.ChatEndpoint;
import io.metaloom.loom.rest.endpoint.impl.ClusterEndpoint;
import io.metaloom.loom.rest.endpoint.impl.CollectionEndpoint;
import io.metaloom.loom.rest.endpoint.impl.CommentEndpoint;
import io.metaloom.loom.rest.endpoint.impl.EmbeddingEndpoint;
import io.metaloom.loom.rest.endpoint.impl.GraphQLEndpoint;
import io.metaloom.loom.rest.endpoint.impl.GroupEndpoint;
import io.metaloom.loom.rest.endpoint.impl.HealthEndpoint;
import io.metaloom.loom.rest.endpoint.impl.NodeDescriptorEndpoint;
import io.metaloom.loom.rest.endpoint.impl.LibraryEndpoint;
import io.metaloom.loom.rest.endpoint.impl.MeEndpoint;
import io.metaloom.loom.rest.endpoint.impl.OAuth2Endpoint;
import io.metaloom.loom.rest.endpoint.impl.PersonEndpoint;
import io.metaloom.loom.rest.endpoint.impl.PipelineEndpoint;
import io.metaloom.loom.rest.endpoint.impl.PipelineEventEndpoint;
import io.metaloom.loom.rest.endpoint.impl.ProcessorEndpoint;
import io.metaloom.loom.rest.endpoint.impl.LoginEndpoint;
import io.metaloom.loom.rest.endpoint.impl.ReactionEndpoint;
import io.metaloom.loom.rest.endpoint.impl.NotificationEndpoint;
import io.metaloom.loom.rest.endpoint.impl.SkillEndpoint;
import io.metaloom.loom.rest.endpoint.impl.SpaceEndpoint;
import io.metaloom.loom.rest.endpoint.impl.RESTInfoEndpoint;
import io.metaloom.loom.rest.endpoint.impl.RoleEndpoint;
import io.metaloom.loom.rest.endpoint.impl.DedupGroupEndpoint;
import io.metaloom.loom.rest.endpoint.impl.SearchEndpoint;
import io.metaloom.loom.rest.endpoint.impl.SimilarityIndexEndpoint;
import io.metaloom.loom.rest.endpoint.impl.TagEndpoint;
import io.metaloom.loom.rest.endpoint.impl.TaskEndpoint;
import io.metaloom.loom.rest.endpoint.impl.TokenEndpoint;
import io.metaloom.loom.rest.endpoint.impl.UserEndpoint;

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
		AssetComponentEndpoint assetComponentEndpoint,
		AssetPoolEndpoint assetPoolEndpoint,
		BlacklistEndpoint blacklistEndpoint,
		ChatEndpoint chatEndpoint,
		NotificationEndpoint notificationEndpoint,
		SkillEndpoint skillEndpoint,
		ClusterEndpoint clusterEndpoint,
		CollectionEndpoint collectionEndpoint,
		EmbeddingEndpoint embeddingEndpoint,
		GraphQLEndpoint graphQLEndpoint,
		HealthEndpoint healthEndpoint,
		TaskEndpoint taskEndoint,
		TagEndpoint tagEndpoint,
		LibraryEndpoint libraryEndpoint,
		PersonEndpoint personEndpoint,
		PipelineEndpoint pipelineEndpoint,
		PipelineEventEndpoint pipelineEventEndpoint,
		ProcessorEndpoint processorEndpoint,
		SpaceEndpoint spaceEndpoint,
		LoginEndpoint loginEndpoint,
		OAuth2Endpoint oauth2Endpoint,
		RESTInfoEndpoint restInfoEndpoint,
		SearchEndpoint searchEndpoint,
		CommentEndpoint commentEndpoint,
		AttachmentEndpoint attachmentEndpoint,
		ReactionEndpoint reactionEndpoint,
		TokenEndpoint tokenEndpoint,
		MeEndpoint meEndpoint,
		NodeDescriptorEndpoint nodeDescriptorEndpoint,
		DedupGroupEndpoint dedupGroupEndpoint,
		SimilarityIndexEndpoint similarityIndexEndpoint) {
		return new HashSet<>(Arrays.asList(
			userEndpoint,
			roleEndpoint,
			groupEndpoint,
			annotationEndpoint,
			assetEndpoint,
			assetBinaryEndpoint,
			assetComponentEndpoint,
			assetPoolEndpoint,
			blacklistEndpoint,
			chatEndpoint,
			notificationEndpoint,
			skillEndpoint,
			clusterEndpoint,
			collectionEndpoint,
			embeddingEndpoint,
			graphQLEndpoint,
			healthEndpoint,
			taskEndoint,
			tagEndpoint,
			libraryEndpoint,
			personEndpoint,
			pipelineEndpoint,
			pipelineEventEndpoint,
			processorEndpoint,
			spaceEndpoint,
			loginEndpoint,
			oauth2Endpoint,
			restInfoEndpoint,
			searchEndpoint,
			attachmentEndpoint,
			commentEndpoint,
			reactionEndpoint,
			tokenEndpoint,
			meEndpoint,
			nodeDescriptorEndpoint,
			dedupGroupEndpoint,
			similarityIndexEndpoint));
	}
}
