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

}
