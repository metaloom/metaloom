package io.metaloom.cortex.s3.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.vertx.core.json.JsonObject;

public class S3EventParserTest {

	/** A real MinIO ObjectCreated notification, trimmed to the fields that matter. */
	private static final String MINIO_PUT = """
		{
		  "EventName": "s3:ObjectCreated:Put",
		  "Key": "media/2026/clip.mp4",
		  "Records": [{
		    "eventName": "s3:ObjectCreated:Put",
		    "s3": {
		      "bucket": {"name": "media"},
		      "object": {"key": "2026/clip.mp4", "size": 1234, "eTag": "abc123"}
		    }
		  }]
		}""";

	/** A real AWS S3 ObjectRemoved notification. */
	private static final String AWS_DELETE = """
		{
		  "Records": [{
		    "eventVersion": "2.1",
		    "eventSource": "aws:s3",
		    "awsRegion": "eu-central-1",
		    "eventName": "s3:ObjectRemoved:Delete",
		    "s3": {
		      "bucket": {"name": "media", "arn": "arn:aws:s3:::media"},
		      "object": {"key": "2026/gone.mp4", "sequencer": "005F"}
		    }
		  }]
		}""";

	@Test
	public void testParsesAMinioCreateEvent() {
		var hints = S3EventParser.parse(new JsonObject(MINIO_PUT));

		assertThat(hints).singleElement().satisfies(hint -> {
			assertThat(hint.bucket()).isEqualTo("media");
			assertThat(hint.key()).isEqualTo("2026/clip.mp4");
			assertThat(hint.removed()).isFalse();
		});
	}

	@Test
	public void testParsesAnAwsDeleteEvent() {
		var hints = S3EventParser.parse(new JsonObject(AWS_DELETE));

		assertThat(hints).singleElement().satisfies(hint -> {
			assertThat(hint.key()).isEqualTo("2026/gone.mp4");
			assertThat(hint.removed()).isTrue();
		});
	}

	@Test
	public void testKeysAreUrlDecoded() {
		// Keys arrive percent-encoded with spaces as '+'. Skipping the decode would produce keys
		// that do not exist, and every HEAD would 404.
		String payload = """
			{"Records":[{"eventName":"s3:ObjectCreated:Put","s3":{
			  "bucket":{"name":"media"},"object":{"key":"2026/my+holiday%20clip%C3%BC.mp4"}}}]}""";

		var hints = S3EventParser.parse(new JsonObject(payload));

		assertThat(hints).singleElement()
			.extracting(S3ChangeHint::key).isEqualTo("2026/my holiday clipü.mp4");
	}

	@Test
	public void testAllCreatedAndRemovedSubtypesAreRecognised() {
		for (String name : new String[] { "s3:ObjectCreated:Put", "s3:ObjectCreated:Post",
			"s3:ObjectCreated:Copy", "s3:ObjectCreated:CompleteMultipartUpload" }) {
			var hints = S3EventParser.parse(new JsonObject(event(name, "k.mp4")));
			assertThat(hints).as(name).singleElement().extracting(S3ChangeHint::removed).isEqualTo(false);
		}
		for (String name : new String[] { "s3:ObjectRemoved:Delete", "s3:ObjectRemoved:DeleteMarkerCreated" }) {
			var hints = S3EventParser.parse(new JsonObject(event(name, "k.mp4")));
			assertThat(hints).as(name).singleElement().extracting(S3ChangeHint::removed).isEqualTo(true);
		}
	}

	@Test
	public void testUninterestingEventTypesAreIgnored() {
		assertThat(S3EventParser.parse(new JsonObject(event("s3:ObjectAccessed:Get", "k.mp4")))).isEmpty();
		assertThat(S3EventParser.parse(new JsonObject(event("s3:TestEvent", "k.mp4")))).isEmpty();
	}

	@Test
	public void testDirectoryPlaceholdersAreIgnored() {
		// A zero-byte object whose key ends in a slash is a folder marker, never media.
		assertThat(S3EventParser.parse(new JsonObject(event("s3:ObjectCreated:Put", "2026/")))).isEmpty();
	}

	@Test
	public void testMalformedRecordsAreSkippedWithoutLosingTheBatch() {
		String payload = """
			{"Records":[
			  {"eventName":"s3:ObjectCreated:Put"},
			  {"eventName":"s3:ObjectCreated:Put","s3":{"bucket":{"name":"media"}}},
			  {"eventName":"s3:ObjectCreated:Put","s3":{"bucket":{"name":"media"},"object":{"key":"good.mp4"}}}
			]}""";

		// One bad notification must not cost the good hints delivered alongside it.
		assertThat(S3EventParser.parse(new JsonObject(payload)))
			.singleElement().extracting(S3ChangeHint::key).isEqualTo("good.mp4");
	}

	@Test
	public void testEmptyAndAbsentPayloadsAreSafe() {
		assertThat(S3EventParser.parse(null)).isEmpty();
		assertThat(S3EventParser.parse(new JsonObject())).isEmpty();
		assertThat(S3EventParser.parse(new JsonObject("{\"Records\":[]}"))).isEmpty();
	}

	@Test
	public void testAKeyWithAnInvalidEscapeIsUsedVerbatim() {
		var hints = S3EventParser.parse(new JsonObject(event("s3:ObjectCreated:Put", "100%sure.mp4")));

		assertThat(hints).singleElement().extracting(S3ChangeHint::key).isEqualTo("100%sure.mp4");
	}

	private static String event(String eventName, String key) {
		return "{\"Records\":[{\"eventName\":\"" + eventName + "\",\"s3\":{"
			+ "\"bucket\":{\"name\":\"media\"},\"object\":{\"key\":\"" + key + "\"}}}]}";
	}
}
