package io.metaloom.loom.rest.service.impl;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Rate limit for failed share password attempts.
 *
 * <p>
 * {@code POST /shares/:slug/sessions} is the only unauthenticated password check in the API and it is reachable by anyone who has the link. bcrypt's
 * cost factor makes each guess expensive, but "expensive" is a throughput limit, not a bound: a link protected by a four-word password would still
 * fall to a patient script, and the same script would keep a core busy hashing while it worked.
 * </p>
 *
 * <p>
 * Counted per slug rather than per client address. The client address is the wrong key here for two reasons: reviewers behind one corporate NAT would
 * lock each other out, and an attacker with a handful of addresses would sidestep it entirely. Keying on the slug bounds the attempts against the
 * thing actually being attacked. The cost is that somebody who forgets a password can lock a link for a quarter of an hour - which is why the window
 * is short and the allowance generous enough for honest fumbling.
 * </p>
 *
 * <p>
 * In-memory and per-process. An installation running several REST nodes multiplies the allowance by the node count, which is a real weakening and is
 * documented rather than hidden: a shared counter needs a store this service does not otherwise require, and the alternative to an imperfect limit
 * here is no limit at all.
 * </p>
 */
@Singleton
public class ShareThrottle {

	/** Failed attempts tolerated inside {@link #WINDOW} before the slug is refused. */
	public static final int MAX_FAILURES = 10;

	/** How long the failure count is remembered, and how long a locked slug stays locked. */
	public static final Duration WINDOW = Duration.ofMinutes(15);

	/**
	 * Above this many tracked slugs the map is swept of lapsed entries before a new one is added.
	 *
	 * <p>
	 * Without a bound, every slug ever guessed at stays in memory - which is a slow leak an attacker controls simply by requesting made-up slugs.
	 * </p>
	 */
	private static final int SWEEP_THRESHOLD = 10_000;

	private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

	@Inject
	public ShareThrottle() {
	}

	/**
	 * Whether this slug is currently refused because of repeated failures.
	 */
	public boolean isThrottled(String slug) {
		Attempts entry = attempts.get(slug);
		if (entry == null) {
			return false;
		}
		if (entry.hasLapsed()) {
			attempts.remove(slug, entry);
			return false;
		}
		return entry.count.get() >= MAX_FAILURES;
	}

	/**
	 * Record a wrong password.
	 */
	public void recordFailure(String slug) {
		if (attempts.size() > SWEEP_THRESHOLD) {
			attempts.values().removeIf(Attempts::hasLapsed);
		}
		attempts.compute(slug, (key, existing) -> {
			if (existing == null || existing.hasLapsed()) {
				return new Attempts();
			}
			existing.count.incrementAndGet();
			return existing;
		});
	}

	/**
	 * Forget the failures for a slug. Called on a successful redemption, so one person mistyping twice before getting it right does not spend the
	 * allowance for everyone else holding the same link.
	 */
	public void recordSuccess(String slug) {
		attempts.remove(slug);
	}

	private static final class Attempts {

		private final AtomicInteger count = new AtomicInteger(1);
		private final Instant firstFailure = Instant.now();

		private boolean hasLapsed() {
			return firstFailure.plus(WINDOW).isBefore(Instant.now());
		}
	}
}
