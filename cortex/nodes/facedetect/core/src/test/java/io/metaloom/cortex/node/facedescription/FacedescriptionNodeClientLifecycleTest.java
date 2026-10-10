package io.metaloom.cortex.node.facedescription;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;

import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.facedetect.FacedetectNodeOptions;

/**
 * The lifecycle of the {@link OpenAIClient} that {@link FacedescriptionNode#processFace} builds per
 * face.
 *
 * <p>
 * Each client owns an OkHttp dispatcher and a connection pool. The node used to build one per face
 * and drop it on the floor, so a worker accumulated their threads for every face it ever described.
 * The contract pinned here is that the client is closed on every exit - including the exit the
 * caller never sees, three failed model calls answered with a {@code null} description.
 * </p>
 */
class FacedescriptionNodeClientLifecycleTest {

	/** A node whose client factory hands out the given client instead of building a real one. */
	private static FacedescriptionNode node(OpenAIClient client) {
		return new FacedescriptionNode(null, new CortexOptions(), new FacedetectNodeOptions(), new ObjectMapper(), null) {
			@Override
			OpenAIClient newClient() {
				return client;
			}
		};
	}

	@Test
	void testClientIsClosedWhenEveryModelCallFails() throws Exception {
		OpenAIClient client = mock(OpenAIClient.class);
		when(client.chat()).thenThrow(new RuntimeException("vision backend down"));

		FaceDescription description = node(client).processFace(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB));

		assertNull(description, "Three failed model calls answer null rather than throwing");
		verify(client, times(3)).chat();
		verify(client).close();
	}
}
