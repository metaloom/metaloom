package io.metaloom.loom.auth;

import java.util.UUID;
import java.util.function.Function;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.metaloom.loom.db.model.perm.ResourcePermissionSet;

@Singleton
public class PermissionCache {

	public static final long DEFAULT_MAX_SIZE = 10_000;

	private Cache<UUID, ResourcePermissionSet> cache;

	@Inject
	public PermissionCache() {
		this.cache = createCache();
	}

	private Cache<UUID, ResourcePermissionSet> createCache() {
		return Caffeine.newBuilder().maximumSize(DEFAULT_MAX_SIZE).build();

	}

	public ResourcePermissionSet get(UUID userUuid, Function<UUID, ResourcePermissionSet> mapper) {
		return cache.get(userUuid, mapper);
	}

	/**
	 * Drop the cached permission set of a single user, so the next authorization check reloads it.
	 *
	 * @param userUuid
	 */
	public void invalidate(UUID userUuid) {
		cache.invalidate(userUuid);
	}

	/**
	 * Drop every cached permission set.
	 *
	 * <p>
	 * The cache has no expiry, so a grant which is written to the database is invisible to already-authenticated sessions until their entry is
	 * dropped. Any write that changes who holds which permission must call this. Per-user invalidation is not usable for role edits: resolving "which
	 * users does this role reach" means traversing <code>role_group</code> and <code>user_group</code> backwards, and neither index supports that
	 * direction. Role and group edits are rare administrative actions, so dropping the whole cache is the cheaper trade.
	 * </p>
	 */
	public void invalidateAll() {
		cache.invalidateAll();
	}

}
