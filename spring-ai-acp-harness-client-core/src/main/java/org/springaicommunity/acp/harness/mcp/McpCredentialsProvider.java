package org.springaicommunity.acp.harness.mcp;

import org.jspecify.annotations.Nullable;
import org.springaicommunity.acp.harness.config.McpServerSpec;
import org.springaicommunity.acp.harness.session.SessionPrincipal;

/**
 * Decides which HTTP MCP servers a session reaches through the loopback proxy, and with
 * whose credentials.
 *
 * <p>
 * Asked once per server each time a session is opened, loaded or resumed, on the caller's
 * thread. Returning credentials puts that server behind the proxy for that session: the
 * agent is told a {@code http://127.0.0.1} URL unique to the session and never sees a
 * token, and the proxy asks the returned {@link McpCredentials} for headers on every
 * request. Returning null leaves the server exactly as configured, handed to the agent
 * verbatim — which is what happens to every server when no provider is configured at all.
 * The exception is a server declared without a URL: returning null leaves it out of that
 * session, and returning credentials whose {@link McpCredentials#upstream()} names the
 * URL is the only way it is offered.
 *
 * <p>
 * Throwing refuses the session. That is the right answer for a user who has not yet
 * signed in to a server the application needs: it happens before {@code session/new} is
 * sent, on a thread that can still send the user somewhere to sign in, instead of in the
 * middle of a turn where nobody is there to do it.
 */
@FunctionalInterface
public interface McpCredentialsProvider {

	/**
	 * @param server the server as configured, with its real URL and any configured
	 * headers
	 * @param principal who the session is for, or null for a session opened on nobody's
	 * behalf
	 * @return the credentials to call the server with, or null to hand it to the agent as
	 * configured
	 */
	@Nullable
	McpCredentials credentialsFor(McpServerSpec.Http server, @Nullable SessionPrincipal principal);

	/** The default: every server is handed to the agent as configured. */
	McpCredentialsProvider NONE = (server, principal) -> null;

	/** {@link #NONE}, one instance, so settings built with it compare equal. */
	static McpCredentialsProvider none() {
		return NONE;
	}

}
