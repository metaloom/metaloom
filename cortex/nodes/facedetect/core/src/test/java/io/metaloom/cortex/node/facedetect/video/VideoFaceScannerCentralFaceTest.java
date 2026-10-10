package io.metaloom.cortex.node.facedetect.video;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.video.facedetect.face.Face;
import io.metaloom.video.facedetect.face.FaceBox;

/**
 * The embedding pass re-detects on a crop padded by a whole face width, so in a crowd the crop holds a neighbour too. The vector has to come from
 * the face the crop was cut for - the one at its centre - not from whichever face the detector happened to list first.
 */
class VideoFaceScannerCentralFaceTest {

	private static Face face(int x, int y, int w, int h, boolean embedded) {
		Face face = mock(Face.class);
		when(face.box()).thenReturn(FaceBox.create(x, y, w, h));
		when(face.hasEmbedding()).thenReturn(embedded);
		return face;
	}

	@Test
	void testPicksTheFaceAtTheCentreNotTheFirstListed() {
		Face neighbour = face(0, 0, 40, 40, true);
		Face subject = face(80, 80, 40, 40, true);

		assertSame(subject, VideoFaceScanner.centralFace(List.of(neighbour, subject), 200, 200));
	}

	@Test
	void testIgnoresFacesWithoutAVector() {
		Face subjectWithoutVector = face(80, 80, 40, 40, false);
		Face neighbour = face(0, 0, 40, 40, true);

		assertSame(neighbour, VideoFaceScanner.centralFace(List.of(subjectWithoutVector, neighbour), 200, 200));
	}

	@Test
	void testNothingFoundYieldsNull() {
		assertNull(VideoFaceScanner.centralFace(List.of(), 200, 200));
		assertNull(VideoFaceScanner.centralFace(null, 200, 200));
	}
}
