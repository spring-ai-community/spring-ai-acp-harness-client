package org.springaicommunity.acp.harness.session;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeParseException;

import org.jspecify.annotations.Nullable;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * A conversation the agent has kept, as reported by {@code session/list}.
 *
 * <p>
 * Not an {@link AcpAgentSession}: this one is not open, has no turn permit and belongs to
 * the agent rather than to this client. It becomes an {@code AcpAgentSession} only by
 * being loaded or resumed, which is the distinction {@link AcpAgentSessions} exists to
 * make explicit.
 *
 * @param sessionId the agent's id for the session
 * @param cwd the working directory it was stored against, or null when the agent did not
 * say
 * @param title the agent's title for it, or null when it has none
 * @param updatedAt when it last changed, or null when the agent did not say or said
 * something unparseable
 */
public record StoredSession(String sessionId, @Nullable Path cwd, @Nullable String title, @Nullable Instant updatedAt) {

	static StoredSession from(AcpSchema.SessionInfo info) {
		return new StoredSession(info.sessionId(), info.cwd() == null ? null : Paths.get(info.cwd()), info.title(),
				parseInstant(info.updatedAt()));
	}

	/**
	 * ACP says {@code updatedAt} is an RFC 3339 timestamp, but it is an agent-supplied
	 * string and a listing is not worth failing over one unparseable field.
	 */
	private static @Nullable Instant parseInstant(@Nullable String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Instant.parse(value);
		}
		catch (DateTimeParseException ex) {
			return null;
		}
	}

}
