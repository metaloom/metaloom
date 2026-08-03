package io.metaloom.cortex.node.imagemanip;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.metaloom.cortex.api.node.spec.ParamDoc;
import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

/**
 * Options for the {@link ImageManipulationNode}.
 *
 * <p>
 * {@link #operations} is the spine: it names which operations run and in which order, and every other field configures one of them. A field belonging
 * to an operation that is not listed is simply never read, so a pipeline that only autorotates does not have to think about aspect ratios.
 * </p>
 *
 * <p>
 * <strong>Everything geometric is relative.</strong> {@link #cropX} and friends are fractions of the frame, {@link #subjectPadding} a fraction of the
 * subject box. A rectangle written in absolute pixels is correct at exactly one resolution - the argument {@code WatermarkNodeOptions} makes for
 * {@code scale}, and it applies to every knob here.
 * </p>
 */
public class ImageManipulationNodeOptions extends AbstractNodeOptions<ImageManipulationNodeOptions> {

	public static final String KEY = "image-manipulation";

	/** What {@link #operations} means when the author leaves it alone: fix the rotation, square up the ratio, bound the size. */
	public static final String DEFAULT_OPERATIONS = "AUTOROTATE,ASPECT,RESIZE";

	// Every field carries an explicit order: an ordered parameter anywhere sorts the unordered ones
	// behind it, and the three inherited common parameters are pinned at 10/20/30.
	@ParamDoc(label = "Operations",
		description = "Ordered, comma-separated: AUTOROTATE, CROP, SUBJECT_CROP, ASPECT, RESIZE. They run in the order given, in one pass. "
			+ "AUTOROTATE must come first when used - every later step measures against the frame it rewrites",
		order = 100)
	private String operations = DEFAULT_OPERATIONS;

	@ParamDoc(label = "Crop Left", description = "Left edge of the CROP window as a fraction of the width", min = "0.0", max = "1.0", step = "0.01",
		order = 110)
	private double cropX = 0.0d;

	@ParamDoc(label = "Crop Top", description = "Top edge of the CROP window as a fraction of the height", min = "0.0", max = "1.0", step = "0.01",
		order = 120)
	private double cropY = 0.0d;

	@ParamDoc(label = "Crop Width", description = "Width of the CROP window as a fraction of the frame width", min = "0.01", max = "1.0",
		step = "0.01", order = 130)
	private double cropWidth = 1.0d;

	@ParamDoc(label = "Crop Height", description = "Height of the CROP window as a fraction of the frame height", min = "0.01", max = "1.0",
		step = "0.01", order = 140)
	private double cropHeight = 1.0d;

	@ParamDoc(label = "Subject Types",
		description = "Comma-separated detection types SUBJECT_CROP frames, e.g. face. Leave as * to accept every type the port delivers",
		order = 150)
	private String subjectTypes = "face";

	@ParamDoc(label = "Minimum Confidence", description = "Detections scoring below this are ignored", min = "0.0", max = "1.0", step = "0.05",
		order = 160)
	private double minConfidence = 0.5d;

	@ParamDoc(label = "Subject Padding",
		description = "Breathing room around the subjects, as a fraction of their combined size. Relative to the subjects rather than the frame, "
			+ "so a small face is not swallowed and a large one not merely outlined",
		min = "0.0", max = "4.0", step = "0.05", order = 170)
	private double subjectPadding = 0.35d;

	@ParamDoc(label = "No Subject Found",
		description = "What SUBJECT_CROP does when nothing was detected: CENTRE the frame instead, SKIP the item, or FAIL it",
		order = 180)
	private SubjectFallback subjectFallback = SubjectFallback.CENTRE;

	@ParamDoc(label = "Target Aspect", description = "Target ratio as W:H, e.g. 16:9 or 1:1. Empty keeps the frame's own ratio", order = 190)
	private String targetAspect = "";

	@ParamDoc(label = "Aspect Mode", description = "CROP cuts the long axis and loses pixels; PAD grows the short one and loses none", order = 200)
	private AspectMode aspectMode = AspectMode.CROP;

	@ParamDoc(label = "Padding Fill",
		description = "What fills the margins PAD creates. BLUR uses a blurred enlargement of the image itself - the vertical-video fix - "
			+ "COLOR draws the classic bars",
		order = 210)
	private PadFill padFill = PadFill.BLUR;

	@ParamDoc(label = "Padding Colour", description = "Margin colour as #RRGGBB, used when the fill is COLOR", order = 220)
	private String padColor = "#000000";

	@ParamDoc(label = "Blur Radius", description = "How far the blurred backdrop is smeared. Larger is softer and less recognisable", min = "1",
		max = "200", order = 230)
	private int blurRadius = 24;

	@ParamDoc(label = "Backdrop Overscan",
		description = "How much larger than the canvas the blurred backdrop is drawn. Below about 1.05 its own edges can appear inside the frame",
		min = "1.0", max = "2.0", step = "0.05", order = 240)
	private double blurZoom = 1.15d;

	@ParamDoc(label = "Maximum Long Edge", description = "RESIZE bound in pixels, applied to whichever edge is longer. 0 disables resizing", min = "0",
		order = 250)
	private int maxLongEdge = 0;

	@ParamDoc(label = "Allow Upscaling",
		description = "Enlarge a frame that is already smaller than the bound. Off by default: upscaling invents detail and inflates the artifact",
		order = 260)
	private boolean allowUpscale = false;

	@ParamDoc(label = "Output Format", description = "PNG is lossless and keeps transparency; JPEG is far smaller and right for photographs",
		order = 270)
	private OutputFormat outputFormat = OutputFormat.JPEG;

	@ParamDoc(label = "JPEG Quality", description = "Encoder quality, 0 to 1. Ignored for PNG", min = "0.1", max = "1.0", step = "0.05", order = 280)
	private double jpegQuality = 0.90d;

	@ParamDoc(label = "Flatten Colour",
		description = "What shows through where the image was transparent, when writing a format that cannot carry alpha", order = 290)
	private String backgroundColor = "#FFFFFF";

	public ImageManipulationNodeOptions() {
		// A decode, a handful of raster passes and an encode. Generous next to an API call, and nowhere
		// near the ten minutes a video re-encode needs.
		setTimeoutMs(60_000);
	}

	@Override
	protected ImageManipulationNodeOptions self() {
		return this;
	}

	/**
	 * The parsed, ordered operation chain.
	 *
	 * @return the operations to apply, in order; empty when the option is blank
	 * @throws IllegalArgumentException when a token is not an operation name. {@link #validate()} catches that at pipeline start, so a node that has
	 *                                  been validated never sees this
	 */
	public List<Op> operationChain() {
		List<Op> chain = new ArrayList<>();
		if (operations == null || operations.isBlank()) {
			return chain;
		}
		for (String token : operations.split(",")) {
			if (token.isBlank()) {
				continue;
			}
			Op op = Op.parse(token);
			if (op == null) {
				throw new IllegalArgumentException("Not an operation: " + token.trim());
			}
			chain.add(op);
		}
		return chain;
	}

	/** The accepted detection types, lowercased; empty means every type. */
	public Set<String> subjectTypeSet() {
		return SubjectBoxes.types(subjectTypes);
	}

	public String getOperations() {
		return operations;
	}

	public ImageManipulationNodeOptions setOperations(String operations) {
		this.operations = operations;
		return this;
	}

	public double getCropX() {
		return cropX;
	}

	public ImageManipulationNodeOptions setCropX(double cropX) {
		this.cropX = cropX;
		return this;
	}

	public double getCropY() {
		return cropY;
	}

	public ImageManipulationNodeOptions setCropY(double cropY) {
		this.cropY = cropY;
		return this;
	}

	public double getCropWidth() {
		return cropWidth;
	}

	public ImageManipulationNodeOptions setCropWidth(double cropWidth) {
		this.cropWidth = cropWidth;
		return this;
	}

	public double getCropHeight() {
		return cropHeight;
	}

	public ImageManipulationNodeOptions setCropHeight(double cropHeight) {
		this.cropHeight = cropHeight;
		return this;
	}

	public String getSubjectTypes() {
		return subjectTypes;
	}

	public ImageManipulationNodeOptions setSubjectTypes(String subjectTypes) {
		this.subjectTypes = subjectTypes;
		return this;
	}

	public double getMinConfidence() {
		return minConfidence;
	}

	public ImageManipulationNodeOptions setMinConfidence(double minConfidence) {
		this.minConfidence = minConfidence;
		return this;
	}

	public double getSubjectPadding() {
		return subjectPadding;
	}

	public ImageManipulationNodeOptions setSubjectPadding(double subjectPadding) {
		this.subjectPadding = subjectPadding;
		return this;
	}

	public SubjectFallback getSubjectFallback() {
		return subjectFallback;
	}

	public ImageManipulationNodeOptions setSubjectFallback(SubjectFallback subjectFallback) {
		this.subjectFallback = subjectFallback;
		return this;
	}

	public String getTargetAspect() {
		return targetAspect;
	}

	public ImageManipulationNodeOptions setTargetAspect(String targetAspect) {
		this.targetAspect = targetAspect;
		return this;
	}

	public AspectMode getAspectMode() {
		return aspectMode;
	}

	public ImageManipulationNodeOptions setAspectMode(AspectMode aspectMode) {
		this.aspectMode = aspectMode;
		return this;
	}

	public PadFill getPadFill() {
		return padFill;
	}

	public ImageManipulationNodeOptions setPadFill(PadFill padFill) {
		this.padFill = padFill;
		return this;
	}

	public String getPadColor() {
		return padColor;
	}

	public ImageManipulationNodeOptions setPadColor(String padColor) {
		this.padColor = padColor;
		return this;
	}

	public int getBlurRadius() {
		return blurRadius;
	}

	public ImageManipulationNodeOptions setBlurRadius(int blurRadius) {
		this.blurRadius = blurRadius;
		return this;
	}

	public double getBlurZoom() {
		return blurZoom;
	}

	public ImageManipulationNodeOptions setBlurZoom(double blurZoom) {
		this.blurZoom = blurZoom;
		return this;
	}

	public int getMaxLongEdge() {
		return maxLongEdge;
	}

	public ImageManipulationNodeOptions setMaxLongEdge(int maxLongEdge) {
		this.maxLongEdge = maxLongEdge;
		return this;
	}

	public boolean isAllowUpscale() {
		return allowUpscale;
	}

	public ImageManipulationNodeOptions setAllowUpscale(boolean allowUpscale) {
		this.allowUpscale = allowUpscale;
		return this;
	}

	public OutputFormat getOutputFormat() {
		return outputFormat;
	}

	public ImageManipulationNodeOptions setOutputFormat(OutputFormat outputFormat) {
		this.outputFormat = outputFormat;
		return this;
	}

	public double getJpegQuality() {
		return jpegQuality;
	}

	public ImageManipulationNodeOptions setJpegQuality(double jpegQuality) {
		this.jpegQuality = jpegQuality;
		return this;
	}

	public String getBackgroundColor() {
		return backgroundColor;
	}

	public ImageManipulationNodeOptions setBackgroundColor(String backgroundColor) {
		this.backgroundColor = backgroundColor;
		return this;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>
	 * Everything a pipeline author can get wrong is caught here rather than per item. {@code RegistryNodeRegistrar} runs this at node construction, so
	 * a mistyped operation name or a malformed aspect ratio surfaces when the pipeline starts instead of on every image it touches.
	 * </p>
	 */
	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>(validateCommon());

		List<Op> chain = validateOperations(errors);

		if (cropX < 0d || cropX > 1d) {
			errors.add("cropX must be within [0,1], got " + cropX);
		}
		if (cropY < 0d || cropY > 1d) {
			errors.add("cropY must be within [0,1], got " + cropY);
		}
		if (cropWidth <= 0d || cropWidth > 1d) {
			errors.add("cropWidth must be within (0,1], got " + cropWidth);
		}
		if (cropHeight <= 0d || cropHeight > 1d) {
			errors.add("cropHeight must be within (0,1], got " + cropHeight);
		}
		// A window whose origin plus size leaves the frame is clamped at run time rather than refused, but
		// one that starts outside it altogether is a typo the author wants to hear about.
		if (cropX >= 1d && chain.contains(Op.CROP)) {
			errors.add("cropX must leave at least one column inside the frame, got " + cropX);
		}
		if (cropY >= 1d && chain.contains(Op.CROP)) {
			errors.add("cropY must leave at least one row inside the frame, got " + cropY);
		}

		if (minConfidence < 0d || minConfidence > 1d) {
			errors.add("minConfidence must be within [0,1], got " + minConfidence);
		}
		if (subjectPadding < 0d) {
			errors.add("subjectPadding must not be negative, got " + subjectPadding);
		}
		if (subjectFallback == null) {
			errors.add("subjectFallback must not be null");
		}

		if (!ManipulationGeometry.isAspect(targetAspect)) {
			errors.add("targetAspect must be a W:H ratio such as 16:9, got " + targetAspect);
		}
		// An ASPECT step with no ratio to reach is not an error worth failing a pipeline over, but it is
		// always a mistake - the operation would silently do nothing.
		if (chain.contains(Op.ASPECT) && (targetAspect == null || targetAspect.isBlank())) {
			errors.add("targetAspect must be set when the operations include ASPECT");
		}
		if (aspectMode == null) {
			errors.add("aspectMode must not be null");
		}
		if (padFill == null) {
			errors.add("padFill must not be null");
		}
		if (!ManipulationGeometry.isColor(padColor)) {
			errors.add("padColor must be a #RRGGBB colour, got " + padColor);
		}
		if (blurRadius < 1) {
			errors.add("blurRadius must be at least 1, got " + blurRadius);
		}
		if (blurZoom < 1d) {
			errors.add("blurZoom must be at least 1.0, got " + blurZoom);
		}

		if (maxLongEdge < 0) {
			errors.add("maxLongEdge must not be negative, got " + maxLongEdge);
		}
		if (allowUpscale && maxLongEdge <= 0) {
			errors.add("allowUpscale needs a maxLongEdge to scale towards");
		}

		if (outputFormat == null) {
			errors.add("outputFormat must not be null");
		}
		if (jpegQuality <= 0d || jpegQuality > 1d) {
			errors.add("jpegQuality must be within (0,1], got " + jpegQuality);
		}
		if (!ManipulationGeometry.isColor(backgroundColor)) {
			errors.add("backgroundColor must be a #RRGGBB colour, got " + backgroundColor);
		}

		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}

	/**
	 * Check the chain itself: parseable, no repeats, and AUTOROTATE first.
	 *
	 * @return the parsed chain, or an empty list when it could not be parsed
	 */
	private List<Op> validateOperations(List<String> errors) {
		List<Op> chain;
		try {
			chain = operationChain();
		} catch (IllegalArgumentException e) {
			errors.add(e.getMessage() + ". Expected a comma-separated list of AUTOROTATE, CROP, SUBJECT_CROP, ASPECT, RESIZE");
			return List.of();
		}
		if (chain.isEmpty()) {
			errors.add("operations must name at least one of AUTOROTATE, CROP, SUBJECT_CROP, ASPECT, RESIZE");
			return chain;
		}
		Set<Op> seen = new LinkedHashSet<>();
		for (Op op : chain) {
			if (!seen.add(op)) {
				errors.add("operations lists " + op + " more than once");
			}
		}
		// 🔴 Not a stylistic preference. Autorotation redefines the coordinate space every later operation
		// - and every detection box - is measured in, so a chain that rotates in the middle would apply
		// the crop rectangles it was given against a frame that no longer exists.
		int index = chain.indexOf(Op.AUTOROTATE);
		if (index > 0) {
			errors.add("AUTOROTATE must be the first operation, it is at position " + (index + 1)
				+ " - every later operation measures against the frame it rewrites");
		}
		return chain;
	}
}
