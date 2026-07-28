package io.metaloom.cortex.s3.event;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.option.S3ClientOptions;
import io.vertx.core.json.JsonObject;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * Long-polls an SQS queue fed by S3 notifications (directly, or through SNS) and files the change
 * hints into the {@link S3EventBuffer}.
 *
 * <p>This is the AWS-side counterpart to {@link WebhookS3EventSource}: it needs no inbound port,
 * which is usually what makes it the right choice on AWS.</p>
 *
 * <p>Messages are deleted only <em>after</em> their hints are buffered. That keeps delivery
 * at-least-once rather than at-most-once - a duplicate hint costs one redundant HEAD, whereas a
 * lost one would hide an object until the next reconcile listing.</p>
 */
public class SqsS3EventSource implements S3EventSource {

	private static final Logger log = LoggerFactory.getLogger(SqsS3EventSource.class);

	private static final int MAX_MESSAGES = 10;
	private static final int WAIT_SECONDS = 20;
	/** Pause after a failure so a broken queue does not become a hot loop. */
	private static final long ERROR_BACKOFF_MS = 5_000;

	private final S3EventBuffer buffer;
	private final S3ClientOptions options;
	private final AtomicBoolean running = new AtomicBoolean();

	private SqsClient client;
	private Thread poller;

	public SqsS3EventSource(S3EventBuffer buffer, S3ClientOptions options) {
		this.buffer = buffer;
		this.options = options;
	}

	@Override
	public void start() {
		if (options == null || !options.getEvents().isEnabled()) {
			return;
		}
		String queueUrl = options.getEvents().getQueueUrl();
		if (queueUrl == null || queueUrl.isBlank()) {
			log.error("S3 SQS events are enabled but no queue URL is set "
				+ "(--s3-events-queue-url / CORTEX_S3_EVENTS_QUEUE_URL); the poller is NOT started");
			return;
		}
		if (!running.compareAndSet(false, true)) {
			return;
		}

		client = buildClient();
		poller = new Thread(() -> poll(queueUrl), "s3-sqs-event-poller");
		// A daemon thread so a worker shutdown is never held up by an in-flight long poll.
		poller.setDaemon(true);
		poller.start();
		log.info("Polling {} for S3 bucket notifications", queueUrl);
	}

	private SqsClient buildClient() {
		var builder = SqsClient.builder()
			.httpClientBuilder(UrlConnectionHttpClient.builder())
			.region(Region.of(options.getRegion() == null || options.getRegion().isBlank()
				? S3ClientOptions.DEFAULT_REGION
				: options.getRegion()));
		if (options.getEndpoint() != null && !options.getEndpoint().isBlank()) {
			builder.endpointOverride(URI.create(options.getEndpoint()));
		}
		String accessKey = options.getAccessKey();
		String secretKey = options.getSecretKey();
		if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
			builder.credentialsProvider(StaticCredentialsProvider.create(
				AwsBasicCredentials.create(accessKey, secretKey)));
		} else {
			builder.credentialsProvider(DefaultCredentialsProvider.create());
		}
		return builder.build();
	}

	private void poll(String queueUrl) {
		while (running.get()) {
			try {
				List<Message> messages = client.receiveMessage(ReceiveMessageRequest.builder()
					.queueUrl(queueUrl)
					.maxNumberOfMessages(MAX_MESSAGES)
					.waitTimeSeconds(WAIT_SECONDS)
					.build()).messages();

				if (messages.isEmpty()) {
					continue;
				}

				List<DeleteMessageBatchRequestEntry> handled = new ArrayList<>(messages.size());
				for (Message message : messages) {
					if (bufferHints(message)) {
						handled.add(DeleteMessageBatchRequestEntry.builder()
							.id(message.messageId())
							.receiptHandle(message.receiptHandle())
							.build());
					}
				}
				if (!handled.isEmpty()) {
					client.deleteMessageBatch(DeleteMessageBatchRequest.builder()
						.queueUrl(queueUrl).entries(handled).build());
				}
			} catch (Exception e) {
				if (!running.get()) {
					return;
				}
				log.warn("SQS poll failed; retrying in {}ms", ERROR_BACKOFF_MS, e);
				sleep();
			}
		}
	}

	/**
	 * @return true when the message was understood and may be deleted. An unparseable message is
	 *         also "handled" - leaving it on the queue would make it a poison pill that blocks
	 *         every later notification.
	 */
	private boolean bufferHints(Message message) {
		try {
			JsonObject payload = new JsonObject(message.body());
			// An SNS-wrapped notification carries the S3 envelope as a string in "Message".
			if (payload.containsKey("Message") && !payload.containsKey("Records")) {
				payload = new JsonObject(payload.getString("Message"));
			}
			for (S3ChangeHint hint : S3EventParser.parse(payload)) {
				buffer.record(hint);
			}
			return true;
		} catch (RuntimeException e) {
			log.warn("Discarding an unparseable SQS message ({})", message.messageId(), e);
			return true;
		}
	}

	private static void sleep() {
		try {
			Thread.sleep(ERROR_BACKOFF_MS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public void stop() {
		if (!running.compareAndSet(true, false)) {
			return;
		}
		if (poller != null) {
			poller.interrupt();
		}
		if (client != null) {
			client.close();
		}
	}
}
