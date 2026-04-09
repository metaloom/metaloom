package io.metaloom.cortex.node.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.payload.TextPayload;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.media.AbstractMediaTest;

public class OCRNodeTest extends AbstractMediaTest {

	private OCRNode mockNode() {
		return mockNode("deu");
	}

	private OCRNode mockNode(String language) {
		OCRNodeOptions options = new OCRNodeOptions()
			.setTessDataPath("/usr/share/tesseract-ocr/5/tessdata")
			.setLanguage(language);
		OCRProvider provider = new Tess4jOCRProvider(options.getTessDataPath());
		// null client = offline mode (no Loom server needed for tests)
		return new OCRNode(null, new CortexOptions(), options, provider);
	}

	@Test
	public void testOCROnEinsteinImage() throws IOException {
		OCRNode node = mockNode();
		LoomMedia media = media(data.root().resolve("ocr/albert_einstein.png"));
		NodeResult result = node.process(media);

		assertEquals(ResultState.SUCCESS, result.getState());

		String text = result.get(OCRNode.OUTPUT_OCR_TEXT);
		assertNotNull(text);
		assertTrue(text.contains("Albert Einstein"), "OCR text should contain 'Albert Einstein', got: " + text.substring(0, Math.min(200, text.length())));
		assertTrue(text.contains("Physiker"), "OCR text should contain 'Physiker'");

		// Verify the output map contains the OCR text
		assertNotNull(result.getOutputs().get(OCRNode.OUTPUT_OCR_TEXT.key()));
	}

	@Test
	public void testSkipsNonImageMedia() throws IOException {
		OCRNode node = mockNode();
		LoomMedia media = mediaVideo1();
		NodeResult result = node.process(media);

		assertEquals(ResultState.SKIPPED, result.getState());
	}

	@Test
	public void testDisabled() throws IOException {
		OCRNodeOptions options = new OCRNodeOptions();
		options.setEnabled(false);
		OCRProvider provider = new Tess4jOCRProvider(options.getTessDataPath());
		OCRNode node = new OCRNode(null, new CortexOptions(), options, provider);

		LoomMedia media = mediaImage1();
		NodeResult result = node.process(media);

		assertEquals(ResultState.SKIPPED, result.getState());
	}
}
