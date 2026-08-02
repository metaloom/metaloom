package io.metaloom.cortex.cloud.auth;

import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.locks.ReentrantLock;

import io.vertx.core.json.JsonObject;

/**
 * Caches an access token until shortly before it expires.
 *
 * <p>Two properties matter here and both are easy to get wrong:</p>
 * <ul>
 * <li><b>One fetch, not N.</b> A worker runs many node tasks concurrently and each may materialize
 * cloud media. A plain {@code synchronized} accessor would serialize every request behind the cache
 * read; a plain double-check without a lock would let a token expiry stampede the endpoint. So the
 * hot path is a {@code volatile} read and the cold path takes a lock and re-checks.</li>
 * <li><b>Refresh early.</b> A token handed out in its last second is a request that fails in
 * flight, so {@link #SKEW_MS} of margin is subtracted from the expiry.</li>
 * </ul>
 */
public abstract class AbstractCachingTokenSource implements CloudTokenSource {

	/** Refresh a minute before the stated expiry rather than at it. */
	static final long SKEW_MS = 60_000;

	/** Used when a provider omits {@code expires_in}; both document an hour. */
	static final long FALLBACK_TTL_MS = 3_600_000;

	private record Token(String value, long expiresAtMillis) {
	}

	private final Clock clock;
	private final ReentrantLock refreshLock = new ReentrantLock();

	private volatile Token token;

	protected AbstractCachingTokenSource(Clock clock) {
		this.clock = clock == null ? Clock.systemUTC() : clock;
	}

	/**
	 * Perform the actual grant.
	 *
	 * @return the token endpoint's response, which must carry {@code access_token}
	 * @throws IOException when the grant fails
	 */
	protected abstract JsonObject fetch() throws IOException;

	@Override
	public String accessToken() throws IOException {
		Token cached = token;
		if (isUsable(cached)) {
			return cached.value();
		}
		refreshLock.lock();
		try {
			// Re-check: while we waited for the lock another caller may have refreshed.
			cached = token;
			if (isUsable(cached)) {
				return cached.value();
			}
			JsonObject response = fetch();
			long ttlSeconds = response.getLong("expires_in", FALLBACK_TTL_MS / 1000);
			Token fresh = new Token(response.getString("access_token"), clock.millis() + ttlSeconds * 1000);
			token = fresh;
			return fresh.value();
		} finally {
			refreshLock.unlock();
		}
	}

	@Override
	public void invalidate() {
		token = null;
	}

	private boolean isUsable(Token candidate) {
		return candidate != null && clock.millis() < candidate.expiresAtMillis() - SKEW_MS;
	}

	protected Clock clock() {
		return clock;
	}
}
