package org.springaicommunity.acp.harness.permission;

import org.jspecify.annotations.Nullable;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * Asks a person how to answer an agent's permission request.
 *
 * <p>
 * The human half of {@link PermissionPolicy#ask(PermissionPrompt)}, and a bean for the
 * same reason {@code AuthorizationPrompt} is one: only the application knows where its
 * person is — a terminal, a web page, a chat thread. Implementations may block for as
 * long as the person takes; the client calls them off the transport's threads so the rest
 * of the turn keeps flowing meanwhile.
 *
 * <p>
 * If the turn ends first — cancelled, or timed out — the client answers the agent
 * {@code cancelled} without waiting, as ACP requires, and interrupts the thread calling
 * {@link #ask}. An implementation should take the question down and return when
 * interrupted; whatever it returns after that is discarded, so a late "allow" allows
 * nothing.
 */
@FunctionalInterface
public interface PermissionPrompt {

	/**
	 * @param question what the agent wants to do, and the choices it offered
	 * @return the option the person chose, or null to cancel the request
	 */
	AcpSchema.@Nullable PermissionOption ask(PermissionQuestion question);

}
