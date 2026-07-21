package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.annotation.AnnotationType;
import io.metaloom.loom.api.embedding.EmbeddingType;
import io.metaloom.loom.api.reaction.ReactionType;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.rest.model.annotation.AnnotationCreateRequest;
import io.metaloom.loom.rest.model.annotation.AnnotationResponse;
import io.metaloom.loom.rest.model.annotation.AreaInfo;
import io.metaloom.loom.rest.model.asset.AssetCreateRequest;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.asset.info.AudioInfo;
import io.metaloom.loom.rest.model.asset.info.DocumentInfo;
import io.metaloom.loom.rest.model.asset.info.GeoLocationInfo;
import io.metaloom.loom.rest.model.asset.info.ImageInfo;
import io.metaloom.loom.rest.model.asset.info.MediaInfo;
import io.metaloom.loom.rest.model.asset.info.VideoInfo;
import io.metaloom.loom.rest.model.asset.location.AssetLocationCreateRequest;
import io.metaloom.loom.rest.model.asset.location.AssetLocationFilesystemInfo;
import io.metaloom.loom.rest.model.asset.location.AssetLocationResponse;
import io.metaloom.loom.rest.model.asset.location.FileKey;
import io.metaloom.loom.rest.model.attachment.AttachmentResponse;
import io.metaloom.loom.rest.model.collection.CollectionCreateRequest;
import io.metaloom.loom.rest.model.collection.CollectionResponse;
import io.metaloom.loom.rest.model.comment.CommentCreateRequest;
import io.metaloom.loom.rest.model.comment.CommentListResponse;
import io.metaloom.loom.rest.model.comment.CommentResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingCreateRequest;
import io.metaloom.loom.rest.model.embedding.EmbeddingResponse;
import io.metaloom.loom.rest.model.library.LibraryCreateRequest;
import io.metaloom.loom.rest.model.library.LibraryResponse;
import io.metaloom.loom.rest.model.reaction.ReactionCreateRequest;
import io.metaloom.loom.rest.model.reaction.ReactionResponse;
import io.metaloom.loom.rest.model.reaction.ReactionUpdateRequest;
import io.metaloom.loom.rest.model.tag.TagCreateRequest;
import io.metaloom.loom.rest.model.tag.TagResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineCreateRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineListResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineUpdateRequest;
import io.metaloom.loom.rest.model.task.TaskCreateRequest;
import io.metaloom.loom.api.task.TaskPriority;
import io.metaloom.loom.rest.model.task.TaskResponse;
import io.metaloom.loom.test.TestEnvHelper;
import io.metaloom.loom.test.data.TestDataCollection;
import io.vertx.core.json.JsonObject;

public class CombinedEndpointTest extends AbstractEndpointTest {

	@Test
	public void testBasics() throws LoomClientException, FileNotFoundException, IOException {
		TestDataCollection env = TestEnvHelper.prepareTestdata("combined");
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			// Create library for assets being created
			LibraryCreateRequest libraryRequest = new LibraryCreateRequest();
			libraryRequest.setName(LIBRARY_NAME_2);
			LibraryResponse library = client.createLibrary(libraryRequest).sync().body();

			// Create an asset with component data
			AssetCreateRequest assetRequest = new AssetCreateRequest();
			assetRequest.setRequired(DUMMY_IMAGE_FILENAME, IMAGE_MIMETYPE, ASSET_SHA512SUM, 42L, DUMMY_IMAGE_ORIGIN);
			assetRequest.addVideoFingerprint(VIDEO_FINGERPRINT);

			// Add geo component
			GeoLocationInfo geoInfo = new GeoLocationInfo();
			geoInfo.setLat(48.137154);
			geoInfo.setLon(11.576124);
			geoInfo.setAlias("Munich");
			assetRequest.setGeo(geoInfo);

			// Add image component
			ImageInfo imageInfo = new ImageInfo();
			imageInfo.setDominantColor("#FF5733");
			imageInfo.setWidth(1920);
			imageInfo.setHeight(1080);
			assetRequest.setImage(imageInfo);

			// Add video component
			VideoInfo videoInfo = new VideoInfo();
			videoInfo.setBitrate(5000);
			videoInfo.setEncoding("H264");
			videoInfo.setWidth(1920);
			videoInfo.setHeight(1080);
			videoInfo.setDuration(120000L);
			assetRequest.setVideo(videoInfo);

			// Add media info (used for video/audio duration & dimensions)
			MediaInfo mediaInfo = new MediaInfo();
			mediaInfo.setDuration(120000L);
			mediaInfo.setWidth(1920);
			mediaInfo.setHeight(1080);
			assetRequest.setMedia(mediaInfo);

			// Add audio component
			AudioInfo audioInfo = new AudioInfo();
			audioInfo.setBpm(128);
			audioInfo.setChannels(2);
			audioInfo.setEncoding("AAC");
			audioInfo.setSamplingRate(44100);
			audioInfo.setBitrate(320);
			assetRequest.setAudio(audioInfo);

			// Add document component
			DocumentInfo docInfo = new DocumentInfo();
			docInfo.setWordCount(1500L);
			assetRequest.setDocument(docInfo);

			AssetResponse asset = client.createAsset(assetRequest).sync().body();

			// Verify component data in response
			assertNotNull(asset.getUuid(), "Asset UUID should not be null");

			// Verify geo components
			assertNotNull(asset.getGeoComponents(), "Geo components should not be null");
			assertFalse(asset.getGeoComponents().isEmpty(), "Geo components should not be empty");
			assertEquals(48.137154, asset.getGeoComponents().get(0).getLat(), 0.001);
			assertEquals(11.576124, asset.getGeoComponents().get(0).getLon(), 0.001);
			assertEquals("Munich", asset.getGeoComponents().get(0).getAlias());

			// Verify backward-compat single geo field
			assertNotNull(asset.getGeo(), "Backward-compat geo should not be null");
			assertEquals(48.137154, asset.getGeo().getLat(), 0.001);

			// Verify image components
			assertNotNull(asset.getImageComponents(), "Image components should not be null");
			assertFalse(asset.getImageComponents().isEmpty(), "Image components should not be empty");
			assertEquals("#FF5733", asset.getImageComponents().get(0).getDominantColor());

			// Verify video components
			assertNotNull(asset.getVideoComponents(), "Video components should not be null");
			assertFalse(asset.getVideoComponents().isEmpty(), "Video components should not be empty");
			assertEquals("H264", asset.getVideoComponents().get(0).getEncoding());
			assertEquals(Integer.valueOf(5000), asset.getVideoComponents().get(0).getBitrate());

			// Verify audio components
			assertNotNull(asset.getAudioComponents(), "Audio components should not be null");
			assertFalse(asset.getAudioComponents().isEmpty(), "Audio components should not be empty");
			assertEquals(Integer.valueOf(128), asset.getAudioComponents().get(0).getBpm());
			assertEquals(Integer.valueOf(2), asset.getAudioComponents().get(0).getChannels());
			assertEquals("AAC", asset.getAudioComponents().get(0).getEncoding());

			// Verify document components
			assertNotNull(asset.getDocumentComponents(), "Document components should not be null");
			assertFalse(asset.getDocumentComponents().isEmpty(), "Document components should not be empty");
			assertEquals(Long.valueOf(1500), asset.getDocumentComponents().get(0).getWordCount());

			for (long i = 0; i < 10; i++) {
				EmbeddingCreateRequest embeddingRequest = new EmbeddingCreateRequest();
				embeddingRequest.setType(EmbeddingType.DLIB_FACE_RESNET_v1);
				embeddingRequest.setArea(AreaInfo.create(20, 40, 200, 200));
				embeddingRequest.setVector(VECTOR_DATA);
				embeddingRequest.setAssetUuid(asset.getUuid());
				embeddingRequest.setSource("embedding_" + i);
				embeddingRequest.setMeta(new JsonObject().put("test", "1234"));
				EmbeddingResponse embedding = client.createEmbedding(embeddingRequest).sync().body();
				Path videoPath = env.video1().path();
				try (InputStream stream = Files.newInputStream(videoPath)) {
					AttachmentResponse uploadResponse = client.uploadAttachment(DUMMY_VIDEO_FILENAME, VIDEO_MIMETYPE, stream).sync().body();
				}
			}

			// Create a location for the asset
			AssetLocationCreateRequest locationRequest = new AssetLocationCreateRequest();
			locationRequest.setLibraryUuid(library.getUuid());
			locationRequest.setAssetUuid(asset.getUuid());
			locationRequest.setFilesystem(
				new AssetLocationFilesystemInfo().setLastSeen(Instant.now()).setPath(DUMMY_IMAGE_ORIGIN).setFilekey(new FileKey(42L, 42L, 42L, 42L)));
			AssetLocationResponse location = client.createLocation(locationRequest).sync().body();

			// Collection are used to group assets together (e.g. for a set of remixes)
			CollectionCreateRequest collectionRequest = new CollectionCreateRequest();
			collectionRequest.setName(COLLECTION_NAME_2);
			CollectionResponse collection = client.createCollection(collectionRequest).sync().body();

			// Add annotation + task
			AnnotationCreateRequest annotationRequest = new AnnotationCreateRequest();
			annotationRequest.setArea(2L, 42L);
			annotationRequest.setDescription("This area needs to be reworked");
			annotationRequest.setTitle("Feedback on intro");
			annotationRequest.setType(AnnotationType.FEEDBACK);
			annotationRequest.setAssetUuid(asset.getUuid());
			AnnotationResponse annotation = client.createAnnotation(annotationRequest).sync().body();

			TaskCreateRequest taskRequest = new TaskCreateRequest();
			taskRequest.setTitle("Update Intro");
			taskRequest.setPriority(TaskPriority.CRITICAL);
			TaskResponse task = client.createTask(taskRequest).sync().body();

			// Tag
			TagCreateRequest tagRequest = new TagCreateRequest();
			tagRequest.setName("black");
			tagRequest.setCollection("colors");
			TagResponse tag = client.createTag(tagRequest).sync().body();

			// Comment
			CommentCreateRequest commentRequest = new CommentCreateRequest();
			commentRequest.setTitle("Feedback");
			commentRequest.setText("ABCDEFG");
			CommentResponse comment = client.createComment(commentRequest).sync().body();

			// Task comment
			CommentCreateRequest taskCommentRequest = new CommentCreateRequest();
			taskCommentRequest.setTitle("Task feedback");
			taskCommentRequest.setText("Please also update the outro");
			CommentResponse taskComment = client.createTaskComment(task.getUuid(), taskCommentRequest).sync().body();
			assertNotNull(taskComment.getUuid(), "The created task comment should have a uuid");

			CommentListResponse taskComments = client.listTaskComments(task.getUuid()).sync().body();
			assertTrue(taskComments.getData().stream().anyMatch(c -> c.getUuid().equals(taskComment.getUuid())),
				"The task comment listing should contain the created comment");

			TaskResponse reloadedTask = client.loadTask(task.getUuid()).sync().body();
			assertTrue(reloadedTask.getComments().stream().anyMatch(c -> c.getUuid().equals(taskComment.getUuid())),
				"The reloaded task should embed the created comment");

			// Reactions
			ReactionCreateRequest reactionRequest = new ReactionCreateRequest();
			reactionRequest.setRating(42);
			reactionRequest.setType(ReactionType.PLUS_ONE);
			ReactionResponse reaction1 = client.createAssetReaction(asset.getUuid(), reactionRequest).sync().body();
			ReactionResponse reaction2 = client.createCommentReaction(comment.getUuid(), reactionRequest).sync().body();
			ReactionResponse reaction3 = client.createTaskReaction(task.getUuid(), reactionRequest).sync().body();

			// The rating and type must round-trip through create and the reload.
			assertEquals(42, reaction1.getRating(), "The created asset reaction should carry the rating");
			assertEquals(ReactionType.PLUS_ONE, reaction1.getType(), "The created asset reaction should carry the type");
			ReactionResponse reloadedReaction = client.loadAssetReaction(asset.getUuid(), reaction1.getUuid()).sync().body();
			assertEquals(42, reloadedReaction.getRating(), "The reloaded asset reaction should still carry the rating");

			// Updating the reaction persists the new rating.
			ReactionUpdateRequest reactionUpdateRequest = new ReactionUpdateRequest();
			reactionUpdateRequest.setRating(7);
			reactionUpdateRequest.setType(ReactionType.THUMBSUP);
			ReactionResponse updatedReaction = client.updateAssetReaction(asset.getUuid(), reaction1.getUuid(), reactionUpdateRequest).sync().body();
			assertEquals(7, updatedReaction.getRating(), "The updated asset reaction should carry the new rating");
			assertEquals(ReactionType.THUMBSUP, updatedReaction.getType(), "The updated asset reaction should carry the new type");

			// Pipeline CRUD
			PipelineCreateRequest pipelineRequest = new PipelineCreateRequest();
			pipelineRequest.setName("test-pipeline");
			pipelineRequest.setDescription("A test pipeline for combined endpoint test");
			pipelineRequest.setDefinition(new JsonObject()
				.put("nodes", new JsonObject()
					.put("sha512", new JsonObject()
						.put("name", "SHA-512 Hash")
						.put("mode", "PARALLEL")
						.put("concurrency", 4))));
			pipelineRequest.setEnabled(true);
			pipelineRequest.setPriority(10);
			pipelineRequest.setDryRun(false);
			PipelineResponse pipeline = client.createPipeline(pipelineRequest).sync().body();
			assertNotNull(pipeline.getUuid(), "Pipeline UUID should not be null");
			assertNotNull(pipeline.getMeta(), "Pipeline meta should not be null");
			// The response is flattened: the version fields are served inline, no second request needed.
			assertNotNull(pipeline.getVersionUuid(), "Pipeline version UUID should not be null");
			assertEquals(1, pipeline.getVersionNumber(), "A freshly created pipeline is at version 1");
			assertEquals("test-pipeline", pipeline.getName());
			assertEquals("A test pipeline for combined endpoint test", pipeline.getDescription());
			assertNotNull(pipeline.getDefinition(), "Pipeline definition should be served inline");
			assertEquals(true, pipeline.isEnabled());
			assertEquals(10, pipeline.getPriority());
			assertEquals(false, pipeline.isDryRun());

			// Load pipeline
			PipelineResponse loadedPipeline = client.loadPipeline(pipeline.getUuid()).sync().body();
			assertNotNull(loadedPipeline);
			assertEquals(pipeline.getUuid(), loadedPipeline.getUuid());
			assertEquals(pipeline.getVersionUuid(), loadedPipeline.getVersionUuid());
			assertEquals("test-pipeline", loadedPipeline.getName());
			assertNotNull(loadedPipeline.getDefinition(), "Loaded pipeline definition should be served inline");

			// Update pipeline — creates a new version, so the flattened response advances to v2
			PipelineUpdateRequest pipelineUpdateRequest = new PipelineUpdateRequest();
			pipelineUpdateRequest.setName("updated-pipeline");
			pipelineUpdateRequest.setDescription("Updated description");
			pipelineUpdateRequest.setEnabled(false);
			pipelineUpdateRequest.setPriority(20);
			PipelineResponse updatedPipeline = client.updatePipeline(pipeline.getUuid(), pipelineUpdateRequest).sync().body();
			assertEquals(pipeline.getUuid(), updatedPipeline.getUuid(), "The pipeline UUID is stable across versions");
			assertNotNull(updatedPipeline.getMeta(), "Updated pipeline meta should not be null");
			assertNotNull(updatedPipeline.getVersionUuid(), "Updated pipeline version UUID should not be null");
			assertNotEquals(pipeline.getVersionUuid(), updatedPipeline.getVersionUuid(), "An update must produce a new version");
			assertEquals(2, updatedPipeline.getVersionNumber(), "An update advances the version number");
			assertEquals("updated-pipeline", updatedPipeline.getName());
			assertEquals("Updated description", updatedPipeline.getDescription());
			assertEquals(false, updatedPipeline.isEnabled());
			assertEquals(20, updatedPipeline.getPriority());
			// Not supplied in the update request — must be carried over from the previous version.
			assertNotNull(updatedPipeline.getDefinition(), "Definition should carry over from the previous version");

			// List pipelines — entries are flattened too, so the definition is present without a per-item fetch
			PipelineListResponse pipelineList = client.listPipelines().sync().body();
			assertNotNull(pipelineList);
			assertFalse(pipelineList.getData().isEmpty(), "Pipeline list should not be empty");
			PipelineResponse listed = pipelineList.getData().stream()
				.filter(p -> pipeline.getUuid().equals(p.getUuid()))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Created pipeline missing from list"));
			assertEquals("updated-pipeline", listed.getName(), "List entries render from the latest version");
			assertEquals(2, listed.getVersionNumber());
			assertNotNull(listed.getDefinition(), "List entries should carry the definition inline");

			// Delete pipeline
			client.deletePipeline(pipeline.getUuid()).sync().body();
		}

	}

}
