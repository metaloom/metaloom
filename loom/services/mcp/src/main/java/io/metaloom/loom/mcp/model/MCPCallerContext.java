package io.metaloom.loom.mcp.model;

import java.util.Set;
import java.util.UUID;

/**
 * Server-resolved identity of the caller of an MCP tool.
 *
 * <p>Tools which declare {@link MCPToolDescriptor#requiresIdentity()} receive this alongside their arguments. Every field is derived on the server — the
 * loom user of the request, the groups that user belongs to, the space of the originating chat — and <b>never</b> from the tool arguments, which are fully
 * controlled by the model. Scope-like tool arguments may only act as <i>filters</i> over what this context resolves to.</p>
 *
 * @param userUuid
 *            The loom user uuid of the caller, or {@code null} when unauthenticated
 * @param userName
 *            The username of the caller (used for provenance stamps), may be {@code null}
 * @param groupUuids
 *            The groups the caller belongs to (never null, may be empty)
 * @param spaceUuid
 *            The space (project) the originating chat belongs to, or {@code null}
 * @param chatUuid
 *            The chat the call originates from, or {@code null} for non-chat callers (e.g. external MCP clients)
 */
public record MCPCallerContext(UUID userUuid, String userName, Set<UUID> groupUuids, UUID spaceUuid, UUID chatUuid) {

	/**
	 * Context of an unauthenticated caller. Tools which require identity refuse to run with this.
	 */
	public static final MCPCallerContext ANONYMOUS = new MCPCallerContext(null, null, Set.of(), null, null);

	public MCPCallerContext {
		groupUuids = groupUuids == null ? Set.of() : Set.copyOf(groupUuids);
	}

	public boolean isAuthenticated() {
		return userUuid != null;
	}

}
