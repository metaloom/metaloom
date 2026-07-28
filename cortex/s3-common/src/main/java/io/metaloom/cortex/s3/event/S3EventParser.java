package io.metaloom.cortex.s3.event;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Parses the S3 event-notification envelope into change hints.
 *
 * <p>MinIO and AWS emit the same shape, so one parser serves both transports:</p>
 *
 * <pre>
 * { "Records": [ {
 *     "eventName": "s3:ObjectCreated:Put",
 *     "s3": {
 *       "bucket": { "name": "media" },
 *       "object": { "key": "2026/my+clip.mp4", "eTag": "...", "size": 1234 }
 *     } } ] }
 * </pre>
 *
 * <p>Two details that are easy to get wrong and are handled here:</p>
 * <ul>
 * <li><b>Keys are URL-encoded in the payload.</b> {@code my clip.mp4} arrives as
 * {@code my+clip.mp4}. Skipping the decode yields keys that do not exist.</li>
 * <li><b>Event names vary by suffix.</b> {@code ObjectCreated:Put},
 * {@code ObjectCreated:CompleteMultipartUpload}, {@code ObjectRemoved:Delete} and others, so the
 * family is matched by prefix rather than the exact name.</li>
 * </ul>
 *
 * <p>A malformed record is skipped rather than failing the batch: one bad notification must not
 * cost every other hint in the same delivery.</p>
 */
public final class S3EventParser {

	private static final Logger log = LoggerFactory.getLogger(S3EventParser.class);

	private static final String CREATED_PREFIX = "s3:ObjectCreated:";
	private static final String REMOVED_PREFIX = "s3:ObjectRemoved:";

	private S3EventParser() {
	}

	/**
	 * @param payload the event envelope
	 * @return the hints it carries, never null
	 */
	public static List<S3ChangeHint> parse(JsonObject payload) {
		List<S3ChangeHint> hints = new ArrayList<>();
		if (payload == null) {
			return hints;
		}
		JsonArray records = payload.getJsonArray("Records");
		if (records == null) {
			// MinIO's test event and some gateway health pings carry no records at all.
			return hints;
		}

		for (int i = 0; i < records.size(); i++) {
			try {
				JsonObject record = records.getJsonObject(i);
				if (record == null) {
					continue;
				}
				String eventName = record.getString("eventName", "");
				boolean created = eventName.startsWith(CREATED_PREFIX);
				boolean removed = eventName.startsWith(REMOVED_PREFIX);
				if (!created && !removed) {
					continue;
				}

				JsonObject s3 = record.getJsonObject("s3");
				if (s3 == null) {
					continue;
				}
				JsonObject bucket = s3.getJsonObject("bucket");
				JsonObject object = s3.getJsonObject("object");
				if (bucket == null || object == null) {
					continue;
				}
				String bucketName = bucket.getString("name");
				String key = decodeKey(object.getString("key"));
				if (bucketName == null || bucketName.isBlank() || key == null || key.isBlank()) {
					continue;
				}
				// A key ending in a slash is a directory placeholder, never media.
				if (key.endsWith("/")) {
					continue;
				}
				hints.add(new S3ChangeHint(bucketName, key, removed));
			} catch (RuntimeException e) {
				log.debug("Skipping malformed S3 event record at index {}", i, e);
			}
		}
		return hints;
	}

	/**
	 * Object keys arrive URL-encoded, with spaces as {@code +}.
	 */
	static String decodeKey(String raw) {
		if (raw == null) {
			return null;
		}
		try {
			return URLDecoder.decode(raw, StandardCharsets.UTF_8);
		} catch (IllegalArgumentException e) {
			// A key containing a literal % that is not a valid escape. Better to use it verbatim
			// than to drop the notification entirely.
			log.debug("Could not URL-decode object key '{}'; using it verbatim", raw);
			return raw;
		}
	}
}
