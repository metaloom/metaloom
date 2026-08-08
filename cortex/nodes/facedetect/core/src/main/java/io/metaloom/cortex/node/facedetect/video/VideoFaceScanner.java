package io.metaloom.cortex.node.facedetect.video;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.awt.Dimension;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.node.facedetect.FacedetectNodeOptions;
import io.metaloom.facedetection.client.FaceDetectionServerClient;
import io.metaloom.facedetection.client.model.DetectionResponse;
import io.metaloom.facedetection.client.model.FaceModel;
import io.metaloom.opencv.core.Mat;
import io.metaloom.video.facedetect.FaceVideoFrame;
import io.metaloom.video.facedetect.Facedetector;
import io.metaloom.video.facedetect.face.Face;
import io.metaloom.video.facedetect.face.FaceBox;
import io.metaloom.video.facedetect.inspireface.InspireFacedetector;
import io.metaloom.video4j.VideoFile;
import io.metaloom.video4j.VideoFrame;
import io.metaloom.video4j.opencv.CVUtils;
import io.metaloom.video4j.utils.ImageUtils;
import io.metaloom.video4j.utils.SimpleImageViewer;

public class VideoFaceScanner {

	private final static Logger logger = LoggerFactory.getLogger(VideoFaceScanner.class);

	/**
	 * Size to which the frame should be scaled down to before running initial face detection.
	 *
	 * <p>
	 * Retained as the fallback for the no-options constructor. The configured value is
	 * {@code videoScaleSize}, which defaults to 0 - no downscale at all - because that is what this
	 * scanner actually did for years: the rescale branch was commented out and then hard-disabled.
	 * </p>
	 */
	public static final int DETECTION_SCALE_SIZE = 640;

	/**
	 * Frames sampled per window, when no options are supplied.
	 *
	 * <p>
	 * The configured value is {@code videoChopRate}. That option existed for years with a default of
	 * 5 while this constant said 15, and nothing read the option - so wiring it up naively would have
	 * tripled the number of frames decoded per window for every existing pipeline. The option's
	 * default was changed to 15 to match what the scanner has really been doing.
	 * </p>
	 */
	public static final int WINDOW_STEPS = 15;

	/**
	 * Minimum acceptable sharpness (mean absolute Laplacian) of a padded face crop.
	 *
	 * <p>
	 * Was 10, which nothing can reach since the move to OpenCV 5: real faces in the test video
	 * measure 2.71–4.24 (median 3.43), so every face was rejected. That is worse than it sounds —
	 * {@link #scanWindow} stops scanning a window as soon as one frame yields no faces, so an
	 * unreachable threshold truncates the scan after a single frame rather than merely filtering.
	 * 2.0 keeps a floor against degenerate crops while passing genuine faces.
	 * </p>
	 *
	 * <p>
	 * <strong>Needs calibration.</strong> This value is derived from one video on the new OpenCV;
	 * the sharpest-first sort plus the cap in {@link #processFaces} is what actually selects quality.
	 * </p>
	 */
	private static final double BLUR_THRESHOLD = 2f;

	private final InspireFacedetector inspireface;
	//private SimpleImageViewer viewer = new SimpleImageViewer();

	/** Frames sampled per window; {@code videoChopRate}. */
	private final int windowSteps;

	/** Longest edge to rescale a frame to before detection, or 0 to detect at native resolution; {@code videoScaleSize}. */
	private final int scaleSize;

	@Inject
	public VideoFaceScanner(InspireFacedetector inspireface, FacedetectNodeOptions options) {
		this.inspireface = inspireface;
		this.windowSteps = options.getVideoChopRate() > 0 ? options.getVideoChopRate() : WINDOW_STEPS;
		this.scaleSize = Math.max(0, options.getVideoScaleSize());
	}

	/** Scan with the built-in defaults, for callers that have no node options - tests, mostly. */
	public VideoFaceScanner(InspireFacedetector inspireface) {
		this.inspireface = inspireface;
		this.windowSteps = WINDOW_STEPS;
		this.scaleSize = 0;
	}

	public VideoFaceScannerReport scan(VideoFile video, int maxWindowCount)
		throws InterruptedException, IOException, URISyntaxException {
		return scan(video, maxWindowCount, false);
	}

	/**
	 * Scan a video for faces, optionally computing a recognition embedding for each face that survives selection.
	 *
	 * @param withEmbeddings
	 *            whether to compute embeddings. They are computed <b>after</b> selection, not during detection: the scan finds faces across every window
	 *            and then keeps only the sharpest handful, so embedding during detection would pay for a vector per candidate and then throw nearly all
	 *            of them away
	 */
	public VideoFaceScannerReport scan(VideoFile video, int maxWindowCount, boolean withEmbeddings)
		throws InterruptedException, IOException, URISyntaxException {
		VideoFaceScannerReport report = new VideoFaceScannerReport();

		// Locate potential windows
		// List<FrameWindow> windows = identifyPotentialWindows(video, windowCount);
		List<FrameWindow> windows = VideoFaceScannerUtils.splitWindows(video.length(), windowSteps, maxWindowCount);
		report.setWindowInfo(maxWindowCount, windows.size());
		logger.info("Split Window: {} windows to be scanned.", windows.size());

		// Process the windows and locate the best faces
		List<VideoFace> allFaces = new ArrayList<>();
		for (FrameWindow window : windows) {
			allFaces.addAll(processWindow(video, window));
		}

		// Now process all found faces
		try {
			report.setFaces(processFaces(allFaces));
		} catch (Exception e) {
			e.printStackTrace();
		}

		if (withEmbeddings) {
			embed(report.getFaces());
		}

		logger.info("Got total of {} faces in {} windows", allFaces.size(), windows.size());
		return report;
	}

	/**
	 * Compute a recognition embedding for each selected face from the crop already taken during the scan.
	 *
	 * <p>
	 * Re-running detection on the crop is what the InspireFace recognition path needs - the embedding is taken from an aligned, cropped face rather than
	 * from the full frame. A crop the detector no longer recognises as a face yields no vector; that face keeps its box and is simply not matchable,
	 * which is the honest outcome and better than attaching some other face's vector to it.
	 * </p>
	 */
	private void embed(List<VideoFace> faces) {
		if (faces == null || faces.isEmpty()) {
			return;
		}
		int embedded = 0;
		for (VideoFace face : faces) {
			BufferedImage crop = face.getImage();
			if (crop == null) {
				continue;
			}
			try {
				List<? extends Face> found = inspireface.detectFaces(crop, true);
				if (found != null && !found.isEmpty() && found.get(0).hasEmbedding()) {
					face.setEmbedding(found.get(0).getEmbedding());
					embedded++;
				}
			} catch (Exception e) {
				logger.warn("Could not compute an embedding for a face: {}", e.getMessage());
			}
		}
		logger.info("Computed embeddings for {} of {} selected face(s)", embedded, faces.size());
	}

	private List<VideoFace> processFaces(List<VideoFace> faces) {
		faces = faces.stream().sorted(this::blurComperator).toList();
		// for (VideoFace face : faces) {
		// System.out.println(face.getBlurriness());
		// }
		List<VideoFace> output = new ArrayList<>();
		for (VideoFace face : faces) {
			// Keep the sharpest faces. This used to also require face.hasEmbedding(), which no face
			// could satisfy: embeddings were attached by processFace() via a remote InsightFace HTTP
			// service, and that call is commented out just above. Nothing replaced it, so the filter
			// silently discarded every face and the video path always reported zero — however many
			// were actually detected. FacedetectNode consumes only the box and frame index, so
			// requiring an embedding gated the pipeline on data no consumer reads.
			output.add(face);
			if (output.size() >= 10) {
				break;
			}
		}

		// double blurriness = 0;
		// for (VideoFace face : faces) {
		// blurriness += face.getBlurriness();
		// }
		// double avgBlurriness = blurriness / (double)faces.size();
		// System.out.println(avgBlurriness);
		// while(faces.size() > 5) {
		// faces.stream().sorted(this::blurComperator).limit(faces.size()-1);
		// }
		return output;
	}

	/**
	 * @throws URISyntaxException
	 * @throws IOException
	 *             Scan the given window of the video and locate the best faces.
	 * 
	 * @param video
	 * @param window
	 * @return
	 * @throws InterruptedException
	 */
	public List<VideoFace> processWindow(VideoFile video, FrameWindow window)
		throws InterruptedException, IOException, URISyntaxException {
		if (logger.isDebugEnabled()) {
			logger.debug("Tuning window: {}", window);
		}

		List<VideoFace> faces = scanWindow(video, window, windowSteps);
		// Only keep the sharpest face from the window
		// faces = faces.stream().sorted(this::blurComperator).limit(1).toList();
		logger.info("Window scan of window {} yield {} faces", window, faces.size());
		return faces;
	}

	/**
	 * @throws InterruptedException
	 * @throws URISyntaxException
	 * @throws IOException
	 *             Scan the given window area for faces using dlib. Run a blurriness filter and return only the top two faces.
	 * 
	 * @param video
	 * @param nWindow
	 * @param from
	 * @param to
	 * @param windowSteps
	 * @return
	 */
	private List<VideoFace> scanWindow(VideoFile video, FrameWindow window, int windowSteps)
		throws IOException, URISyntaxException, InterruptedException {

		int nWindow = window.number();
		long from = window.from();
		long to = window.to();

		List<VideoFace> faces = new ArrayList<>();
		// long start = System.currentTimeMillis();
		long nFrame = from;
		logger.info("Scanning window {} from {} to {} with steps {}", nWindow, from, to, windowSteps);
		while (nFrame + windowSteps < to) {

			// Skip frames
			// long startSkip = System.currentTimeMillis();
			nFrame += windowSteps;
			video.seekToFrame(nFrame);
			// System.out.println("Skip took: " + (System.currentTimeMillis() - startSkip));

			// Stop processing when we found n faces
			if (faces.size() > 10) {
				// System.out.println("Got enough faces");
				break;
			}

			// Every decoded frame is a native buffer the size of the video (a 1080x1920 source is 6 MB
			// a frame) and Mat has no cleaner - the GC never touches it. Held only for the length of
			// one detection, the scan costs one frame; leaked, it cost one per step of every window.
			List<VideoFace> dFaces;
			VideoFrame frame = video.frame();
			if (frame == null) {
				break;
			}
			try {
				dFaces = processFrame(frame);
			} finally {
				free(frame);
			}
			faces.addAll(dFaces);

			// No faces found. Lets skip a few frames
			if (dFaces.isEmpty()) {
				break;
			}
		}
		// Sort by blurriness and return the found two

		// .map(face -> {
		// double f = face.get(BLURRINESS_KEY);
		// BufferedImage img = face.get(IMAGE_KEY);
		// Graphics g = img.getGraphics();
		// g.setColor(Color.RED);
		// g.drawString("B: " + f, 10, 10);
		// g.dispose();
		// ImageUtils.show(img);
		// return face;
		// }).toList();

		// try {
		// System.in.read();
		// } catch (IOException e) {
		// // TODO Auto-generated catch block
		// e.printStackTrace();
		// }
		return faces;
	}

	private List<VideoFace> processFrame(VideoFrame frame) {
		List<VideoFace> faces = new ArrayList<>();
		// FaceVideoFrame faceFrame = dlibDetectFaces(frame);
		FaceVideoFrame faceFrame = detectFaces(inspireface, frame);

		if (faceFrame != null && faceFrame.hasFaces()) {

			// viewer.show(frame);
			// DLIB_DETECTOR.markFaces(frame);
			// DLIB_DETECTOR.markLandmarks(frame);
			// viewer2.show(frame);

			// Process each found face
			for (Face face : faceFrame.faces()) {
				VideoFace videoFace = new VideoFace(face);

				videoFace.setFrame(faceFrame.number());
				// Crop to the face and calculate the blurriness
				// System.out.println("Crop to: " + face + " from " + faceFrame.height() + " x " + faceFrame.width());

				// Mat faceImage = faceFrame.mat().clone();
				int padding = (int) ((double) face.box().getWidth() * 1d);
				// The crop is a native buffer of its own and the blur measurement allocates several more
				// from it, so it is freed on both exits - the face survives as a BufferedImage on the
				// heap, which is what the report carries. This used to be a commented-out release().
				Mat faceImage = cropToFace(faceFrame, face, padding);
				try {
					double blurriness = CVUtils.blurriness(faceImage);
					face.setBluriness(blurriness);
					if (blurriness < BLUR_THRESHOLD) {
						//viewer.show(faceImage);
						// ImageUtils.show(faceImage);
						logger.warn("Omitting face due to blur check: {}", blurriness);
						// Skipped due to bad quality
						continue;
					} else {
						logger.info("Bluriness is {}", face.getBluriness());
					}
					BufferedImage croppedFaceImage = ImageUtils.matToBufferedImage(faceImage);
					// System.out.println("Cropped: " + croppedFaceImage.getWidth() + " x " + croppedFaceImage.getHeight());
					// croppedFaceImage = ImageUtils.scale(croppedFaceImage, croppedFaceImage.getWidth()*2, croppedFaceImage.getHeight()*2);
					// BufferedImage croppedFaceImage = ImageUtils.matToBufferedImage(frame.mat());
					videoFace.setImage(croppedFaceImage);
					// logger.info("Adding face for window " + nWindow + " now " + faces.size() + " in total for this window.");
					faces.add(videoFace);
				} finally {
					CVUtils.free(faceImage);
				}
			}

		}
		return faces;

	}

	private Mat cropToFace(FaceVideoFrame frame, Face face, int padding) {
		FaceBox box = face.box();
		int startX = Math.max(1, box.getStartX());
		int startY = Math.max(1, box.getStartY());
		int width = Math.max(1, box.getWidth());
		int height = Math.max(1, box.getHeight());
		// crop2 hands back a submat - a view onto the frame, but still a native header that has to be
		// given back. Only the clone outlives this method.
		Mat roi = CVUtils.crop2(frame.mat(), new java.awt.Point(startX, startY), new Dimension(width, height), padding);
		try {
			return roi.clone();
		} finally {
			CVUtils.free(roi);
		}
	}

	/**
	 * Release the native buffer behind a decoded frame. {@link Mat} carries no cleaner, so a frame the
	 * GC collects leaks its pixels for the lifetime of the process.
	 */
	private static void free(VideoFrame frame) {
		if (frame == null) {
			return;
		}
		try {
			frame.close();
		} catch (Exception e) {
			logger.warn("Could not release a video frame", e);
		}
	}

	public FaceVideoFrame detectFaces(Facedetector detector, VideoFrame frame) {
		long start = System.currentTimeMillis();
		Mat original = frame.mat();

		// Driven by videoScaleSize, which defaults to 0 - detect at native resolution, which is what this
		// scanner has always really done. Only rescale when the frame is meaningfully taller than the
		// target; the 128px margin stops a frame that is already near the target from paying for a resize
		// that buys nothing.
		boolean scaleDown = scaleSize > 0 && frame.height() >= scaleSize + 128;
		Mat smaller = null;
		if (scaleDown) {
			// Resize to smaller size for detection
			smaller = original.clone();
			double aspectRatio = (double) original.height() / (double) original.width();
			int width = (int) ((double) scaleSize * aspectRatio);
			logger.info("Scaling down image to width {} ", width);
			CVUtils.resize(smaller, smaller, scaleSize, width);
			frame.setMat(smaller);
		}

		// Run detection
		FaceVideoFrame videoFrame = detector.detectFaces(frame);

		if (scaleDown) {
			// Reset original and free smaller version
			videoFrame.setMat(original);
			frame.setMat(original);

			for (Face face : videoFrame.faces()) {
				FaceBox box = face.box();

				double startXFactor = (double) box.getStartX() / (double) smaller.width();
				box.setStartX((int) ((double) original.width() * startXFactor));

				double startYFactor = (double) box.getStartY() / (double) smaller.height();
				box.setStartY((int) ((double) original.height() * startYFactor));

				double heightFactor = (double) box.getHeight() / (double) smaller.height();
				box.setHeight((int) ((double) original.height() * heightFactor));

				double widthFactor = (double) box.getWidth() / (double) smaller.width();
				box.setWidth((int) ((double) original.width() * widthFactor));

				// VideoFrame image = FacedetectorUtils.cropToFace(videoFrame, face);
				// ImageUtils.show(image.mat());
			}
			smaller.release();
		}
		logger.debug("Inspireface duration: {}ms, faces: {}", System.currentTimeMillis() - start, videoFrame.hasFaces());
		return videoFrame;
	}

	// private FaceVideoFrame insightfaceDetectFaces(VideoFrame frame) throws IOException, URISyntaxException, InterruptedException {
	// long start = System.currentTimeMillis();
	// Mat original = frame.mat();
	//
	// boolean scaleDown = frame.height() >= DETECTION_SCALE_SIZE + 128;
	// Mat smaller = null;
	// if (scaleDown) {
	// // Resize to 512
	// smaller = original.clone();
	// double aspectRatio = (double) original.height() / (double) original.width();
	// int width = (int) ((double) DETECTION_SCALE_SIZE * aspectRatio);
	// CVUtils.resize(smaller, smaller, DETECTION_SCALE_SIZE, width);
	// frame.setMat(smaller);
	// }
	//
	// // Run detection
	// FaceVideoFrame videoFrame = INSIGHTFACE_DETECTOR.detectFaces(frame);
	//
	// if (scaleDown) {
	// // Reset original and free smaller version
	// videoFrame.setMat(original);
	// frame.setMat(original);
	//
	// for (Face face : videoFrame.faces()) {
	// FaceBox box = face.box();
	//
	// double startXFactor = (double) box.getStartX() / (double) smaller.width();
	// box.setStartX((int) ((double) original.width() * startXFactor));
	//
	// double startYFactor = (double) box.getStartY() / (double) smaller.height();
	// box.setStartY((int) ((double) original.height() * startYFactor));
	//
	// double heightFactor = (double) box.getHeight() / (double) smaller.height();
	// box.setHeight((int) ((double) original.height() * heightFactor));
	//
	// double widthFactor = (double) box.getWidth() / (double) smaller.width();
	// box.setWidth((int) ((double) original.width() * widthFactor));
	//
	// // VideoFrame image = FacedetectorUtils.cropToFace(videoFrame, face);
	// // ImageUtils.show(image.mat());
	// }
	// smaller.release();
	// }
	// System.out.println("InsightFace Duration: " + (System.currentTimeMillis() - start) + " " + videoFrame.hasFaces());
	// return videoFrame;
	// }

	private void processFaceWithClient(FaceDetectionServerClient client, Face dFace) {
		BufferedImage croppedFaceImage = dFace.get("image");
		try {
			// Graphics g = croppedFaceImage.getGraphics();
			// g.setColor(Color.RED);
			// g.drawString("B: " + blurriness, 10, 10);
			// g.dispose();
			// 2. Send image to
			// ImageUtils.show(croppedFaceImage);
			String imageData = ImageUtils.toBase64JPG(croppedFaceImage);
			DetectionResponse response = client.detectByImageData(imageData);
			List<FaceModel> detectedFaces = response.getFaces();
			if (detectedFaces.size() > 1) {
				logger.warn("I found more than one face in the cropped face image.");
				return;
			}
			if (detectedFaces.size() == 0) {
				logger.warn("Insightfaces did not find face");
			}
			// ArrayList<? extends Face> faces = new ArrayList<>();
			// ImageUtils.show(croppedFaceImage);
			for (FaceModel face : detectedFaces) {
				dFace.setEmbedding(face.getEmbedding());
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Scan the video and segment it in the given amount of windows. A window is an area in the video which will be inspected for faces.
	 * 
	 * @param video
	 * @param windowCount
	 * @return
	 * @throws InterruptedException
	 */
	public List<FrameWindow> identifyPotentialWindows(VideoFile video, int windowCount) throws InterruptedException {
		List<FrameWindow> windows = new ArrayList<>();
		long totalFrames = video.length();
		long spread = totalFrames / windowCount;
		logger.info("Window scan using spread: {} potential window count: {}", (totalFrames / spread), spread);

		int nWindow = 0;
		for (long nFrame = spread; nFrame < totalFrames - spread; nFrame += spread) {
			// logger.info("Scanning frame for potential window " + nFrame);
			video.seekToFrame(nFrame);
			VideoFrame frame = video.frame();
			if (frame == null) {
				break;
			}
			FaceVideoFrame faceFrame;
			try {
				CVUtils.resize(frame, 512);
				faceFrame = inspireface.detectFaces(frame);
			} finally {
				free(frame);
			}

			// The middle frame of the vindow contains a face - thus add it to the list of windows.
			if (faceFrame.hasFaces()) {
				logger.info("[W" + nWindow + "] Adding window with " + faceFrame.faces().size() + " faces.");
				// DLIB_DETECTOR.markLandmarks(faceFrame);
				// DLIB_DETECTOR.markFaces(faceFrame);
				windows.add(new FrameWindow(nWindow, nFrame, nFrame + spread));
				// viewer.show(faceFrame);
				nWindow++;
			}
		}

		return windows;
	}

	private int blurComperator(VideoFace f1, VideoFace f2) {
		double b1 = f1.getBlurriness();
		double b2 = f2.getBlurriness();
		return Double.compare(b2, b1);
	}

}
