package io.metaloom.cortex.node.captioning;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.Charset;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.cortex.common.node.media.AbstractMediaTest;
import io.metaloom.video4j.utils.ImageUtils;

/**
 * Exercises {@link SmolVLMClient} against a local SmolVLM endpoint — see {@link SmolVLMAvailability}.
 */
public class SmolVLMClientTest extends AbstractMediaTest {

	private SmolVLMClient client = new SmolVLMClient(SmolVLMAvailability.HOST, SmolVLMAvailability.PORT);

	@BeforeEach
	public void requireSmolVLM() {
		SmolVLMAvailability.assumeRunning();
	}

	@Test
	public void testByURL() throws Exception {
		String result = client.captionByURL("https://shop.manner.com/media/catalog/product/1/7/1700_neapolitaner_grosspkg_18er.jpg");
		assertThat(result).as("The endpoint must return a caption").isNotBlank();
	}

	@Test
	public void testByBase64Data() throws IOException, URISyntaxException {
		String data = load("/image_base64.dat");
		String result = client.captionByImageData(data);
		assertThat(result).as("The endpoint must return a caption").isNotBlank();
	}

	@Test
	public void testImageBase64() throws IOException, URISyntaxException {
		BufferedImage image = ImageUtils.load(image1().path().toFile());
		String result = client.captionByImage(image, 512);
		assertThat(result).as("The endpoint must return a caption").isNotBlank();
	}

	private String load(String path) throws IOException {
		InputStream ins = SmolVLMClientTest.class.getResourceAsStream(path);
		return IOUtils.toString(ins, Charset.defaultCharset());
	}
}
