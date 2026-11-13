package org.springaicommunity.acp.harness.client;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.springaicommunity.acp.harness.config.AcpHarnessOptions;
import org.springaicommunity.acp.harness.event.AcpHarnessEvent;
import org.springaicommunity.acp.harness.protocol.AcpProtocol;
import org.springaicommunity.acp.harness.runtime.AcpHarnessNotice;
import org.springaicommunity.acp.harness.session.AcpHarnessSession;
import org.springaicommunity.acp.harness.session.AcpHarnessSessions;
import org.springaicommunity.acp.harness.session.SessionPrincipal;

import reactor.core.publisher.Flux;

/**
 * Sends requests to an ACP agent, whichever agent that happens to be.
 *
 * <pre>{@code
 * String answer = client.prompt()
 *         .session("review-123")
 *         .user("Inspect this change")
 *         .call()
 *         .content();
 *
 * Flux<AcpHarnessEvent> events = client.prompt()
 *         .user("Now focus on security")
 *         .stream()
 *         .events();
 * }</pre>
 *
 * <p>
 * A prompt without an explicit {@code session} runs in a throwaway session that is closed
 * when the turn ends.
 */
public interface AcpHarnessClient extends AutoCloseable {

	/** Starts a prompt. Nothing is sent until {@code call()} or {@code stream()}. */
	PromptSpec prompt();

	/** Shorthand for {@code prompt().user(text)}. */
	default PromptSpec prompt(String text) {
		return prompt().user(text);
	}

	/** The id of the runtime behind this client, e.g. {@code goose}. */
	String runtimeId();

	/**
	 * What the agent called itself during {@code initialize}, or null when it did not
	 * say.
	 */
	@Nullable
	AcpHarnessInfo harnessInfo();

	/**
	 * The ACP version this connection settled on.
	 *
	 * <p>
	 * Not simply what the agent answered: an agent can answer a version nobody offered —
	 * goose 1.51 echoes whatever it is given, including versions that do not exist — so
	 * this is the result of reconciling the offer with the answer. See
	 * {@code AcpProtocol}.
	 */
	default int protocolVersion() {
		return AcpProtocol.V1;
	}

	/**
	 * Whether this connection is still usable.
	 *
	 * <p>
	 * Optimistic by contract: a transport that cannot tell answers {@code true}, because
	 * the alternative — reporting "possibly dead" for every stdio agent — would make the
	 * answer useless to the one caller that needs it. What it does promise is that
	 * {@code false} is never wrong.
	 */
	default boolean isAlive() {
		return true;
	}

	/**
	 * What the agent reported about itself outside the protocol, most recently seen last.
	 *
	 * <p>
	 * Empty for every runtime that cannot report anything, which is all of them but Goose
	 * today, and empty for a client that attached to an agent it did not start. A
	 * non-empty list is the agent's own words — the motivating case is an MCP server it
	 * could not load, which ACP has no way to tell a client about at all. See
	 * {@code AcpHarnessLogWatcher}.
	 */
	default List<AcpHarnessNotice> notices() {
		return List.of();
	}

	/**
	 * Closes the named sessions that have been idle longer than the configured TTL.
	 *
	 * <p>
	 * On the interface rather than hidden in a lifecycle bean because a session costs
	 * memory on the agent for as long as it is open, and an application that knows its
	 * own quiet periods can reclaim them sooner than a timer would. A pooled client
	 * sweeps on a schedule of its own, so most applications never call this.
	 */
	default void evictIdleSessions() {
	}

	/**
	 * The conversations this agent has, as opposed to the turns run in them: list, load,
	 * resume, delete, close.
	 *
	 * <p>
	 * Every one of them is optional in ACP and the runtimes disagree about which they
	 * implement, so ask {@code sessions().supports(...)} before spending a round trip on
	 * finding out.
	 */
	AcpHarnessSessions sessions();

	/**
	 * The named session, once a turn has opened it; null until then.
	 *
	 * <p>
	 * The way to find out what the negotiated tier actually achieved:
	 * {@code AcpHarnessSession
	 * .configuration()} says, per option, whether the request was applied and by which
	 * mechanism. With {@code on-unsupported: warn} that is the difference between the
	 * model an application asked for and the model it is talking to.
	 */
	@Nullable
	AcpHarnessSession session(String name);

	/**
	 * Opens the named session now, or returns the one already open, without prompting.
	 *
	 * <p>
	 * A turn does this for itself, so this is for the case where the answer is wanted
	 * before the question: what the agent advertises, and what the negotiated tier
	 * managed to apply, are both known as soon as the session exists. Also the only way
	 * to find out that a requested model is unsupported without paying for a turn to
	 * discover it.
	 */
	AcpHarnessSession openSession(String name);

	/**
	 * Opens the named session on behalf of {@code principal}, or returns the one already
	 * open for them.
	 *
	 * <p>
	 * The principal is who the session's MCP credentials are asked for, and who it
	 * belongs to: a name already open for someone else throws
	 * {@code SessionOwnershipException}. The one-argument form asks
	 * {@code McpSettings.principals()} instead.
	 * @param principal who the session is for, or null for nobody
	 */
	default AcpHarnessSession openSession(String name, @Nullable SessionPrincipal principal) {
		if (principal == null) {
			return openSession(name);
		}
		throw new UnsupportedOperationException(getClass().getName() + " does not support session principals");
	}

	/**
	 * What the agent sends on the named session while no turn is running there.
	 *
	 * <p>
	 * A turn's events end with its terminal event, but an agent is not obliged to stay
	 * quiet afterwards: OpenCode 2.3 runs a turn of its own when a subagent it left
	 * running in the background reports back, and streams its reply with no prompt
	 * outstanding. That reply arrives here, mapped as a turn's events are, rather than
	 * nowhere.
	 *
	 * <p>
	 * Hot and best-effort: events go to whoever is subscribed when they arrive, and are
	 * dropped with nobody subscribed, so subscribe while the session is open and before
	 * the activity is expected. Never emits a terminal event, since the agent signals the
	 * end of a turn it started itself in no way the protocol defines; completes when the
	 * session is closed or forgotten. A client that cannot deliver them returns an empty
	 * Flux.
	 * @throws IllegalStateException if no session of that name is open
	 * @throws org.springaicommunity.acp.harness.session.SessionOwnershipException if the
	 * session belongs to someone else
	 */
	default Flux<AcpHarnessEvent> unprompted(String name) {
		return Flux.empty();
	}

	/**
	 * Same, for a caller acting for {@code principal}, who must be the session's owner.
	 * The one-argument form asks {@code McpSettings.principals()} instead.
	 * @param principal who the caller acts for, or null for nobody
	 */
	default Flux<AcpHarnessEvent> unprompted(String name, @Nullable SessionPrincipal principal) {
		if (principal == null) {
			return unprompted(name);
		}
		throw new UnsupportedOperationException(getClass().getName() + " does not support session principals");
	}

	@Override
	void close();

	/** Builds one turn. */
	interface PromptSpec {

		/**
		 * Runs in a named, reusable conversation. Repeat calls with the same name keep
		 * context.
		 */
		PromptSpec session(String name);

		/** Appends user text to this turn's prompt. */
		PromptSpec user(String text);

		/** Per-request overrides; null for none. */
		PromptSpec options(@Nullable AcpHarnessOptions options);

		/** Per-request overrides, built inline. */
		PromptSpec options(Consumer<AcpHarnessOptions.Builder> customizer);

		/**
		 * Runs on behalf of {@code principal}: their MCP credentials, their session.
		 *
		 * <p>
		 * Without it the principal comes from {@code McpSettings.principals()}, asked
		 * when {@link #call()} or {@link #stream()} is invoked — on the caller's thread,
		 * which is what lets a resolver read a thread-bound security context. On a
		 * reactive stack, pass it here.
		 *
		 * <p>
		 * An implementation that cannot honour a principal must refuse one rather than
		 * ignore it: silently running a user's turn with nobody's credentials is worse
		 * than failing.
		 * @param principal who the turn is for, or null for nobody
		 */
		default PromptSpec principal(@Nullable SessionPrincipal principal) {
			if (principal == null) {
				return this;
			}
			throw new UnsupportedOperationException(getClass().getName() + " does not support session principals");
		}

		/**
		 * How long to wait when the named session is still running an earlier turn,
		 * before failing with {@link AcpHarnessSessionBusyException}.
		 *
		 * <p>
		 * A session runs one turn at a time, and by default a second turn waits for the
		 * first for up to the turn's own timeout, holding the thread that opens it. A
		 * caller that should not hold a thread for that long, such as a server answering
		 * many users from a shared pool, passes {@link Duration#ZERO} to fail at once.
		 * Has no effect on a turn without a session name, which is never busy.
		 *
		 * <p>
		 * An implementation that cannot fail early may ignore this and wait as before:
		 * unlike {@link #principal(SessionPrincipal)}, ignoring it changes how long a
		 * caller waits, not whose turn runs.
		 * @param wait how long to wait, zero for not at all, or null for the default
		 */
		default PromptSpec waitIfBusy(@Nullable Duration wait) {
			return this;
		}

		/** Runs the turn and blocks for the assistant text. */
		AcpHarnessResponse call();

		/** Runs the turn and streams it. Cancelling the subscription cancels the turn. */
		AcpHarnessStream stream();

	}

	/** A finished turn. */
	interface AcpHarnessResponse {

		/** The assistant text, with thoughts and tool activity excluded. */
		String content();

		/** Why the turn ended. */
		AcpHarnessEvent.Completed completion();

	}

	/** A turn in progress. */
	interface AcpHarnessStream {

		/** Every event, ending with exactly one terminal event. */
		Flux<AcpHarnessEvent> events();

		/** Assistant text only. */
		default Flux<String> content() {
			return events().filter(AcpHarnessEvent.Text.class::isInstance)
				.map(AcpHarnessEvent.Text.class::cast)
				.map(AcpHarnessEvent.Text::text);
		}

	}

}
