package io.metaloom.cortex.action.whisper;

import java.io.IOException;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.ggerganov.whispercpp.WhisperCpp;
import io.github.ggerganov.whispercpp.bean.WhisperSegment;
import io.github.ggerganov.whispercpp.params.WhisperContextParams;
import io.github.ggerganov.whispercpp.params.WhisperFullParams;
import io.github.ggerganov.whispercpp.params.WhisperSamplingStrategy;
import io.metaloom.asr.whisper.AudioExtractor;
import io.metaloom.cortex.media.whisper.TranscriptionSegment;
import io.metaloom.cortex.media.whisper.WhisperResult;

@Singleton
public class WhisperMediaProcessor {

	public static final Logger log = LoggerFactory.getLogger(WhisperMediaProcessor.class);

	private WhisperOptions options;

	@Inject
	public WhisperMediaProcessor(WhisperOptions options) {
		this.options = options;
	}

	/**
	 * Process the media file at the given path and return the whisper transcription result.
	 *
	 * @param mediaPath
	 *            absolute path to the media file (video or audio)
	 * @return the transcription result containing segments
	 * @throws Exception
	 */
	public WhisperResult process(String mediaPath) throws Exception {
		WhisperResult result = new WhisperResult();

		WhisperCpp whisper = new WhisperCpp();
		try {
			WhisperContextParams.ByValue ctxParams = whisper.getContextDefaultParams();
			ctxParams.useGpu(options.isUseGpu());
			ctxParams.gpu_device = options.getGpuDevice();
			log.info("Initializing whisper context (GPU: {}, device: {})", options.isUseGpu(), options.getGpuDevice());
			whisper.initContext(options.getModelPath(), ctxParams);
			WhisperFullParams.ByValue whisperParams = whisper.getFullDefaultParams(WhisperSamplingStrategy.WHISPER_SAMPLING_BEAM_SEARCH);

			whisperParams.temperature = options.getTemperature();
			whisperParams.temperature_inc = options.getTemperatureInc();
			whisperParams.language = options.getLanguage();

			AudioExtractor.decodeAudioToPCM(mediaPath, ac -> {
				try {
					List<WhisperSegment> whisperSegmentList = whisper.fullTranscribeWithTime(whisperParams, ac.getAudio());
					for (WhisperSegment whisperSegment : whisperSegmentList) {
						long start = whisperSegment.getStart();
						long end = whisperSegment.getEnd();
						String text = whisperSegment.getSentence();

						if (log.isDebugEnabled()) {
							log.debug("Segment [{} - {}]: {}", start, end, text);
						}

						result.addTranscriptionSegment(new TranscriptionSegment(text, start, end));
					}
				} catch (Exception e) {
					log.error("Error during whisper transcription", e);
					throw new RuntimeException(e);
				}
			});

		} catch (IOException e) {
			log.error("Error processing media file: " + mediaPath, e);
			throw e;
		} finally {
			whisper.close();
		}

		return result;
	}

}
