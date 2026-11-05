package org.springaicommunity.acp.harness.mcp.oauth;

import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springaicommunity.acp.harness.session.SessionPrincipal;
import org.springaicommunity.acp.harness.session.SessionPrincipalResolver;

/**
 * The signed-in user of the current request, as a session principal.
 *
 * <p>
 * Correct because a resolver is asked on the caller's thread when a turn is described,
 * and in a servlet application that is the request's thread, where
 * {@link SecurityContextHolder} holds its user. On a reactive stack there is no such
 * thread; pass the principal to the prompt instead.
 *
 * <p>
 * An anonymous user is nobody. Spring Security keeps an anonymous user's OAuth tokens in
 * the HTTP session rather than in the {@code OAuth2AuthorizedClientService} the proxy
 * reads, so treating one as a principal would send them to sign in to an MCP server and
 * then never find the token.
 */
public final class SecurityContextPrincipalResolver implements SessionPrincipalResolver {

	@Override
	public @Nullable SessionPrincipal current() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()
				|| authentication instanceof AnonymousAuthenticationToken) {
			return null;
		}
		return SessionPrincipal.of(authentication.getName());
	}

}
