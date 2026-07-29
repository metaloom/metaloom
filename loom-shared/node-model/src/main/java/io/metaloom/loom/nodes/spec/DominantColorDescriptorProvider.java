package io.metaloom.loom.nodes.spec;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.*;
import static io.metaloom.loom.nodes.spec.NodeCategory.ANALYSIS;
import static io.metaloom.loom.nodes.spec.NodeMode.PARALLEL;
import static io.metaloom.loom.nodes.spec.ParameterType.BOOLEAN;
import static io.metaloom.loom.nodes.spec.ParameterType.ENUM;
import static io.metaloom.loom.nodes.spec.ParameterType.ENUM_SET;
import static io.metaloom.loom.nodes.spec.ParameterType.INTEGER;
import static io.metaloom.loom.nodes.spec.ParameterType.NUMBER;
import static io.metaloom.loom.nodes.spec.PortSpec.one;
import static io.metaloom.loom.nodes.spec.PortSpec.optionalMany;

import java.util.List;

/**
 * Provides the node descriptor for the dominant-colour node.
 *
 * <p>
 * Its {@code detections} input is declared <strong>optional</strong>. This is the one analysis node
 * in the set that is fully useful with nothing upstream but the media itself, and marking the input
 * required would make the editor's palette suggest a facedetect dependency that does not exist.
 * </p>
 */
public class DominantColorDescriptorProvider implements NodeDescriptorProvider {

	private static final List<String> STANDARD_EVENTS = List.of(
		"NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED", "NODE_STATS");

	@Override
	public List<NodeDescriptor> getDescriptors() {
		return List.of(
			new NodeDescriptor()
				.setKind("dominant-color")
				.setName("Dominant Colour")
				.setDescription("Find the dominant colours of an image by clustering its pixels in CIELAB - for the whole "
					+ "frame, a configured region, and every upstream detection box. Reports each colour as HEX, RGB, HSL "
					+ "and CIELAB plus a readable name in English and German. No model and no GPU required.")
				.setIcon("palette")
				.setCategory(ANALYSIS)
				.setInputPorts(List.of(
					one("media", MEDIA_IMAGE)
						.describedAs("Image", "The image whose pixels are clustered in CIELAB"),
					optionalMany("detections", DETECTION_ANY)
						.describedAs("Detections", "Boxes to measure individually. Leave unwired to measure only the whole frame and any fixed region")))
				.setOutputPorts(List.of(
					one("result", STRUCT_COLOR)
						.describedAs("Colour Result", "Per measured region: the ranked palette with HEX, RGB, HSL and CIELAB values"),
					one("hex", SCALAR_STRING)
						.describedAs("Hex", "Dominant colour of the whole frame as #RRGGBB"),
					one("term", SCALAR_STRING)
						.describedAs("Colour Term", "Language-neutral name of the dominant colour, e.g. dark_blue - the stable key to match on"),
					one("name_en", SCALAR_STRING)
						.describedAs("Name (English)", "Readable English name of the dominant colour"),
					one("name_de", SCALAR_STRING)
						.describedAs("Name (German)", "Readable German name of the dominant colour"),
					one("region_count", SCALAR_INTEGER)
						.describedAs("Region Count", "How many regions were measured, counting the whole frame and the fixed region")))
				.setParameters(List.of(
					commonEnabled(), commonProcessIncomplete(), commonRetryFailed(),

					new NodeParameter().setKey("clusterCount").setType(INTEGER).setDefaultValue(5)
						.setLabel("Colours per Region")
						.setDescription("How many colours to extract from each region, ranked by pixel share")
						.setMin(1).setMax(16),
					new NodeParameter().setKey("maxSamples").setType(INTEGER).setDefaultValue(40000)
						.setLabel("Max Samples")
						.setDescription("Upper bound on pixels visited per region; the sampling stride is derived from it")
						.setMin(256).setMax(1000000),
					new NodeParameter().setKey("maxIterations").setType(INTEGER).setDefaultValue(30)
						.setLabel("Max Iterations")
						.setDescription("Clustering iteration budget")
						.setMin(1).setMax(500),
					new NodeParameter().setKey("convergenceEpsilon").setType(NUMBER).setDefaultValue(0.5)
						.setLabel("Convergence Epsilon")
						.setDescription("Stop once the largest colour shift falls below this; 0.5 is about the smallest "
							+ "difference a human can see")
						.setMin(0.01).setStep(0.1),
					new NodeParameter().setKey("seed").setType(INTEGER).setDefaultValue(42)
						.setLabel("Seed")
						.setDescription("Clustering seed. Fixed so the same image always yields the same palette"),
					new NodeParameter().setKey("alphaThreshold").setType(INTEGER).setDefaultValue(128)
						.setLabel("Alpha Threshold")
						.setDescription("Pixels more transparent than this are ignored rather than blended with a background")
						.setMin(0).setMax(255),

					new NodeParameter().setKey("includeWholeImage").setType(BOOLEAN).setDefaultValue(true)
						.setLabel("Measure Whole Image")
						.setDescription("Report the dominant colour of the full frame"),
					new NodeParameter().setKey("useDetections").setType(BOOLEAN).setDefaultValue(true)
						.setLabel("Measure Detections")
						.setDescription("Report a colour for every bounding box an upstream detector produced"),
					new NodeParameter().setKey("regionX").setType(NUMBER).setDefaultValue(0.0)
						.setLabel("Region X").setDescription("Left edge of a fixed region to measure").setMin(0.0).setStep(0.05),
					new NodeParameter().setKey("regionY").setType(NUMBER).setDefaultValue(0.0)
						.setLabel("Region Y").setDescription("Top edge of a fixed region to measure").setMin(0.0).setStep(0.05),
					new NodeParameter().setKey("regionW").setType(NUMBER).setDefaultValue(0.0)
						.setLabel("Region Width").setDescription("Width of a fixed region; 0 disables it").setMin(0.0).setStep(0.05),
					new NodeParameter().setKey("regionH").setType(NUMBER).setDefaultValue(0.0)
						.setLabel("Region Height").setDescription("Height of a fixed region; 0 disables it").setMin(0.0).setStep(0.05),
					new NodeParameter().setKey("regionCoordinates").setType(ENUM)
						.setValues(List.of("NORMALIZED", "ABSOLUTE_PIXELS"))
						.setDefaultValue("NORMALIZED")
						.setLabel("Region Coordinates")
						.setDescription("Whether the fixed region is expressed as fractions of the image or in pixels"),

					new NodeParameter().setKey("minRegionPixels").setType(INTEGER).setDefaultValue(64)
						.setLabel("Min Region Pixels")
						.setDescription("Regions smaller than this are dropped - clustering them would fit noise")
						.setMin(1),
					new NodeParameter().setKey("maxRegions").setType(INTEGER).setDefaultValue(32)
						.setLabel("Max Regions")
						.setDescription("Largest-first cap on detection regions; the whole image and fixed region are exempt")
						.setMin(1),
					new NodeParameter().setKey("emitPalette").setType(BOOLEAN).setDefaultValue(true)
						.setLabel("Emit Palette")
						.setDescription("Emit the full ranked palette per region rather than only the dominant colour"),

					new NodeParameter().setKey("achromaticChroma").setType(NUMBER).setDefaultValue(12.0)
						.setLabel("Achromatic Threshold")
						.setDescription("Below this saturation a colour is named black, grey or white instead of by hue")
						.setMin(0.0).setMax(60.0).setStep(1.0),
					new NodeParameter().setKey("blackLightness").setType(NUMBER).setDefaultValue(20.0)
						.setLabel("Black Threshold")
						.setDescription("Lightness below which an unsaturated colour is called black")
						.setMin(0.0).setMax(100.0).setStep(1.0),
					new NodeParameter().setKey("whiteLightness").setType(NUMBER).setDefaultValue(85.0)
						.setLabel("White Threshold")
						.setDescription("Lightness above which an unsaturated colour is called white")
						.setMin(0.0).setMax(100.0).setStep(1.0)))
				.setDefaultConcurrency(4)
				.setDefaultMode(PARALLEL)
				.setEvents(STANDARD_EVENTS));
	}

	private static NodeParameter commonEnabled() {
		return new NodeParameter().setKey("enabled").setType(BOOLEAN).setDefaultValue(true)
			.setLabel("Enabled").setDescription("Whether this node is active in the pipeline");
	}

	private static NodeParameter commonProcessIncomplete() {
		return new NodeParameter().setKey("processIncomplete").setType(BOOLEAN).setDefaultValue(false)
			.setLabel("Process Incomplete").setDescription("Process media files that are still being written");
	}

	private static NodeParameter commonRetryFailed() {
		return new NodeParameter().setKey("retryFailed").setType(BOOLEAN).setDefaultValue(false)
			.setLabel("Retry Failed").setDescription("Retry processing media that previously failed");
	}
}
