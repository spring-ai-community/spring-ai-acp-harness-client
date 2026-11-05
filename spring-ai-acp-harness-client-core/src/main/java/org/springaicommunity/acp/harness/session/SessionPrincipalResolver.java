package org.springaicommunity.acp.harness.session;

import org.jspecify.annotations.Nullable;

/**
 * Finds the principal a session is being opened for when the caller did not name one.
 *
 * <p>
 * Asked on the caller's own thread, at the moment a turn is described or a session is
 * opened — never later, on whichever thread the agent's reply arrives on. That is what
 * lets an implementation read a thread-bound security context: in a servlet application
 * the resolver can simply return the current {@code Authentication}'s name, and every
 * prompt sent from a request is attributed to the person who made it without the
 * application saying so each time.
 *
 * <p>
 * A principal given explicitly — {@code PromptSpec.principal(...)} or
 * {@code openSession(name, principal)} — always wins, and is the only reliable choice on
 * a reactive stack, where there is no thread to read a context from.
 */
@FunctionalInterface
public interface SessionPrincipalResolver {

	/**
	 * Null when there is nobody in particular: a scheduled job, a single-user
	 * application.
	 */
	@Nullable
	SessionPrincipal current();

	/**
	 * The default: no principal, which is what every application without users has always
	 * had.
	 */
	SessionPrincipalResolver NONE = () -> null;

	/** {@link #NONE}, one instance, so settings built with it compare equal. */
	static SessionPrincipalResolver none() {
		return NONE;
	}

}
