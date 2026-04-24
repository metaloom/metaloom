package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.blacklist.BlacklistCreateRequest;
import io.metaloom.loom.rest.model.blacklist.BlacklistListResponse;
import io.metaloom.loom.rest.model.blacklist.BlacklistResponse;
import io.metaloom.loom.rest.model.blacklist.BlacklistUpdateRequest;

public interface BlacklistMethods {

	LoomClientRequest<BlacklistResponse> loadBlacklist(UUID blacklistUuid);

	LoomClientRequest<BlacklistResponse> createBlacklist(BlacklistCreateRequest request);

	LoomClientRequest<BlacklistResponse> updateBlacklist(UUID blacklistUuid, BlacklistUpdateRequest request);

	LoomClientRequest<BlacklistListResponse> listBlacklists();

	LoomClientRequest<NoResponse> deleteBlacklist(UUID blacklistUuid);

}
