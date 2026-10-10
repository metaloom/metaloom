package io.metaloom.cortex.node.captioning;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.video4j.VideoFile;

/**
 * {@link VideoCaptioner} without a decoder or a model: the video and the VLM client are mocks.
 */
class VideoCaptionerTest {

	@Test
	void testWholeStrategyFailsWhenNoFrameDecodes() throws Exception {
		// With no decoded frame the prompt went out with no image and the model invented a caption,
		// which was stored as SUCCESS.
		VideoFile video = mock(VideoFile.class);
		when(video.length()).thenReturn(100L);
		when(video.frameToImage()).thenReturn(null);
		when(video.path()).thenReturn("/tmp/broken.mp4");

		VideoVLMClient vlm = mock(VideoVLMClient.class);
		VideoCaptioner captioner = new VideoCaptioner(new CaptioningNodeOptions(), vlm);

		assertThrows(IOException.class, () -> captioner.caption(video));
		verify(vlm, never()).captionFrames(any(), anyString());
	}
}
