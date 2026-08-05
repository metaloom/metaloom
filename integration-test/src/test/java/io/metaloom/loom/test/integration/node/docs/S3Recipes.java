package io.metaloom.loom.test.integration.node.docs;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.api.option.S3ClientOptions;
import io.metaloom.cortex.node.sink.s3.S3SinkNode;
import io.metaloom.cortex.node.sink.s3.S3SinkNodeOptions;
import io.metaloom.cortex.node.source.s3.S3DifferentialScanner;
import io.metaloom.cortex.node.source.s3.S3ObjectIndexStore;
import io.metaloom.cortex.node.source.s3.S3Selection;
import io.metaloom.cortex.node.source.s3.S3SourceNode;
import io.metaloom.cortex.s3.AwsS3ObjectStore;
import io.metaloom.cortex.s3.S3MediaMaterializer;
import io.metaloom.cortex.s3.S3ObjectStore;
import io.metaloom.cortex.s3.event.S3EventBuffer;
import io.metaloom.cortex.s3.S3Support;
import io.metaloom.fs.FileState;
import io.metaloom.loom.test.integration.node.docs.DocsFixtureRecipe.Outcome;
import io.metaloom.loom.test.integration.node.docs.DocsFixtureRecipe.Requirement;
import io.metaloom.loom.test.integration.node.docs.DocsFixtureRecipe.Upstream;
import io.vertx.core.json.JsonObject;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * The two S3 nodes, against a real object store.
 *
 * <p>
 * MinIO speaks S3, so the code path here is the one that talks to AWS in production — there is no
 * "local mode" being photographed. Start it with {@code ./start-minio.sh}; the recipes probe its
 * health endpoint and abort with that command if it is not answering.
 * </p>
 *
 * <p>
 * Both write under a fresh key prefix. A source that finds nothing new is a perfectly valid state
 * and a completely uninformative picture, so the scan has to be looking at objects it has not
 * indexed before.
 * </p>
 */
public final class S3Recipes {

	private static final String ENDPOINT = System.getProperty("loom.docsS3Endpoint", "http://127.0.0.1:9000");
	private static final String BUCKET = "media";
	private static final String ACCESS_KEY = "minioadmin";
	private static final String SECRET_KEY = "minioadmin";
	private static final String REGION = "us-east-1";

	private static final String HINT = "start an S3-compatible store with ./start-minio.sh";

	private S3Recipes() {
	}

	private static Requirement minioRunning() {
		return Requirement.service("object store", ENDPOINT + "/minio/health/live", HINT);
	}

	private static S3ClientOptions clientOptions() {
		return new S3ClientOptions()
			.setEndpoint(ENDPOINT)
			.setRegion(REGION)
			.setAccessKey(ACCESS_KEY)
			.setSecretKey(SECRET_KEY)
			.setPathStyleAccess(true);
	}

	private static S3Client admin() {
		return S3Client.builder()
			.endpointOverride(URI.create(ENDPOINT))
			.region(Region.of(REGION))
			.credentialsProvider(StaticCredentialsProvider.create(
				AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
			.forcePathStyle(true)
			.build();
	}

	private static void ensureBucket(S3Client client) {
		try {
			client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
		} catch (Exception e) {
			// Already there, which is the normal case on a second run.
		}
	}

	public static DocsFixtureRecipe s3Source() {
		return new DocsFixtureRecipe() {
			@Override
			public String kind() {
				return "s3-source";
			}

			@Override
			public Requirement requirement() {
				return minioRunning();
			}

			@Override
			public List<Upstream> upstream() {
				return List.of();
			}

			@Override
			public Outcome run(FixtureEnv env) throws Exception {
				String prefix = "docs/";
				try (S3Client admin = admin()) {
					ensureBucket(admin);
					admin.putObject(PutObjectRequest.builder().bucket(BUCKET)
						.key(prefix + env.video1().path().getFileName()).build(),
						RequestBody.fromFile(env.inLibrary(env.video1().path())));
					admin.putObject(PutObjectRequest.builder().bucket(BUCKET)
						.key(prefix + env.image1().path().getFileName()).build(),
						RequestBody.fromFile(env.inLibrary(env.image1().path())));
				}

				S3ObjectStore store = new AwsS3ObjectStore(clientOptions());
				Path index = Files.createTempDirectory("docs-fixture-s3-index");
				Path cache = Files.createTempDirectory("docs-fixture-s3-cache");
				S3DifferentialScanner scanner = new S3DifferentialScanner(store, new S3ObjectIndexStore(),
					new S3EventBuffer(), index, ENDPOINT, S3ClientOptions.DEFAULT_RECONCILE_INTERVAL_MS);
				S3SourceNode node = new S3SourceNode("s3-source", scanner,
					new S3MediaMaterializer(store, cache, 0, 0),
					new S3Selection(BUCKET, prefix, Set.of(), Set.of(FileState.NEW, FileState.MODIFIED), false, false));
				node.initialize();

				LoomMedia first = node.stream().blockingFirst();
				NodeResult result = node.process(first, NodeInputs.builder().build());
				return new Outcome(result, first.reference(),
					new JsonObject().put("bucket", BUCKET).put("prefix", prefix)
						.put("emitStates", "NEW, MODIFIED"));
			}
		};
	}

	public static DocsFixtureRecipe s3Sink() {
		return new DocsFixtureRecipe() {
			@Override
			public String kind() {
				return "s3-sink";
			}

			@Override
			public Requirement requirement() {
				return minioRunning();
			}

			@Override
			public List<Upstream> upstream() {
				// The sink's job is to take an artifact another node produced and put it in a bucket,
				// so the graph shows the node that produces one.
				return List.of(new Upstream("thumbnail", "thumbnail", "artifacts"));
			}

			@Override
			public Outcome run(FixtureEnv env) throws Exception {
				try (S3Client admin = admin()) {
					ensureBucket(admin);
				}
				CortexOptions cortex = env.cortexOptions("s3-sink");
				Path meta = cortex.getMetaPath();
				S3ObjectStore store = new AwsS3ObjectStore(clientOptions());
				S3Support support = S3Support.active(store,
					new S3MediaMaterializer(store, meta.resolve("s3_bin"), 0, 0), meta.resolve("s3-index"));

				// A real contact sheet, produced where the thumbnail node writes one — the artifact
				// the sink exists to upload. `.thumb` is the interesting part: it is a JPEG, and the
				// sink has to work that out for the object's content type.
				Path artifact = meta.resolve("thumbnail_bin/ab/contact-sheet.thumb");
				Files.createDirectories(artifact.getParent());
				Files.copy(env.inLibrary(env.image1().path()), artifact);

				S3SinkNode node = new S3SinkNode(null, cortex, new S3SinkNodeOptions(), support);
				JsonObject nodeDef = new JsonObject().put("id", "s3-sink").put("bucket", BUCKET);
				node.configure(nodeDef);
				node.initialize();

				NodeContext<LoomMedia> ctx = NodeContext.create(env.media(env.image1()),
					NodeInputs.builder().inputs(S3SinkNode.IN_ARTIFACTS, java.util.List.of(artifact.toString())).build());
				return new Outcome(node.process(ctx), env.displayPath(env.image1()), nodeDef);
			}
		};
	}
}
