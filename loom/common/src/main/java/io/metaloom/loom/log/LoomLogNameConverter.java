package io.metaloom.loom.log;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback converter backing the {@code %loomName} pattern word.
 *
 * <p>Declared in {@code logback.default.xml} as:</p>
 *
 * <pre>
 * &lt;conversionRule conversionWord="loomName"
 *     converterClass="io.metaloom.loom.log.LoomLogNameConverter"/&gt;
 * </pre>
 *
 * <p>The pattern referenced this class long before it existed, so every line rendered
 * as {@code %PARSER_ERROR[loomName]}. That went unnoticed because the file it lives in
 * is named {@code logback.default.xml} and logback only auto-loads
 * {@code logback.xml} - nothing ever read the configuration until the container images
 * began passing {@code -Dlogback.configurationFile}.</p>
 *
 * <p>Set {@code LOOM_NAME} or {@code -Dloom.name} to pin the name; otherwise one is
 * generated per process. See {@link LoomNameProvider}.</p>
 */
public class LoomLogNameConverter extends ClassicConverter {

	@Override
	public String convert(ILoggingEvent event) {
		// Never throws and never logs: this runs on every logging call, including
		// those made while logback is still starting up.
		return LoomNameProvider.getInstance().getName();
	}
}
