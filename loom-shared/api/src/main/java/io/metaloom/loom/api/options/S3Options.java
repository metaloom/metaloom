package io.metaloom.loom.api.options;

/**
 * Connection settings shared by every S3-backed {@code asset_pool}.
 *
 * <p>
 * The split between this class and the pool row is deliberate: the <em>pool</em> says which bucket (and, optionally, which region and endpoint) holds
 * a library's bytes, and it is user-editable through {@code POST /api/v1/pools}. The <em>credentials</em> live here, on the process, and never enter
 * the database, a REST response or a backup. A bucket name is configuration; a secret key is not.
 * </p>
 *
 * <p>
 * Names mirror Cortex's {@code CORTEX_S3_*} settings ({@code S3ClientOptions}) so that an operator wiring the same MinIO into both components sets the
 * same five things twice rather than learning two vocabularies.
 * </p>
 *
 * <p>
 * Leaving the keys unset is valid and is the right choice on EKS/ECS: the AWS default credential provider chain then applies, which picks up IRSA,
 * instance roles and {@code ~/.aws} without Loom having to know about any of them.
 * </p>
 */
public class S3Options implements Option {

	public static final String DEFAULT_REGION = "us-east-1";

	@EnvironmentVariable(name = "LOOM_S3_ENDPOINT", description = "S3 endpoint URL used when a pool does not name its own. Set this for MinIO/Ceph; leave empty for real AWS.")
	private String endpoint;

	@EnvironmentVariable(name = "LOOM_S3_REGION", description = "S3 region used when a pool does not name its own.")
	private String region = DEFAULT_REGION;

	@EnvironmentVariable(name = "LOOM_S3_ACCESS_KEY", description = "S3 access key. Leave unset to use the AWS default credential provider chain (IRSA, instance role, ~/.aws).", isSensitive = true)
	private String accessKey;

	@EnvironmentVariable(name = "LOOM_S3_SECRET_KEY", description = "S3 secret key. Leave unset to use the AWS default credential provider chain.", isSensitive = true)
	private String secretKey;

	@EnvironmentVariable(name = "LOOM_S3_PATH_STYLE", description = "Force path-style bucket addressing. Defaults to on whenever a custom endpoint is set, which is what MinIO and most gateways require.")
	private Boolean pathStyleAccess;

	public String getEndpoint() {
		return endpoint;
	}

	public S3Options setEndpoint(String endpoint) {
		this.endpoint = endpoint;
		return this;
	}

	public String getRegion() {
		return region;
	}

	public S3Options setRegion(String region) {
		this.region = region;
		return this;
	}

	public String getAccessKey() {
		return accessKey;
	}

	public S3Options setAccessKey(String accessKey) {
		this.accessKey = accessKey;
		return this;
	}

	public String getSecretKey() {
		return secretKey;
	}

	public S3Options setSecretKey(String secretKey) {
		this.secretKey = secretKey;
		return this;
	}

	public Boolean getPathStyleAccess() {
		return pathStyleAccess;
	}

	public S3Options setPathStyleAccess(Boolean pathStyleAccess) {
		this.pathStyleAccess = pathStyleAccess;
		return this;
	}

	/**
	 * Path-style is required by MinIO and most S3-compatible gateways and is wrong for real AWS. Rather than make every MinIO operator remember a
	 * flag, it defaults to "on when a custom endpoint is set" and stays explicitly overridable.
	 *
	 * @param poolEndpoint
	 *            the endpoint the pool declares, may be null
	 * @return whether to address buckets path-style
	 */
	public boolean isPathStyleAccess(String poolEndpoint) {
		if (pathStyleAccess != null) {
			return pathStyleAccess;
		}
		String effective = poolEndpoint != null && !poolEndpoint.isBlank() ? poolEndpoint : endpoint;
		return effective != null && !effective.isBlank();
	}

	@Override
	public void validate(OptionErrors errors) {
		// An access key without its secret is a misconfiguration that would otherwise surface as an
		// opaque 403 from the bucket on the first upload, long after startup.
		boolean hasAccess = accessKey != null && !accessKey.isBlank();
		boolean hasSecret = secretKey != null && !secretKey.isBlank();
		if (hasAccess != hasSecret) {
			errors.add("accessKey",
				"LOOM_S3_ACCESS_KEY and LOOM_S3_SECRET_KEY must be set together. Set both, or neither to use the AWS default credential chain.");
		}
	}

	@Override
	public void overrideWithEnv() {
		OptionUtils.applyEnv("LOOM_S3_ENDPOINT", this::setEndpoint);
		OptionUtils.applyEnv("LOOM_S3_REGION", this::setRegion);
		OptionUtils.applyEnvSensitive("LOOM_S3_ACCESS_KEY", this::setAccessKey);
		OptionUtils.applyEnvSensitive("LOOM_S3_SECRET_KEY", this::setSecretKey);
		OptionUtils.applyEnvBoolean("LOOM_S3_PATH_STYLE", this::setPathStyleAccess);
	}
}
