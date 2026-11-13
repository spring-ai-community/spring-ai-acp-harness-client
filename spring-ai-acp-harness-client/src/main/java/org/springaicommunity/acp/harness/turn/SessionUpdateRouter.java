package org.springaicommunity.acp.harness.turn;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * Fans the client's single stream of {@code session/update} notifications out to the turn
 * that is listening for each session.
 *
 * <p>
 * This exists because of a shape in the ACP SDK that is easy to miss: {@code prompt()}
 * returns a {@code Mono<PromptResponse>} carrying only a stop reason, and every piece of
 * streamed content arrives instead through <em>one</em> {@code sessionUpdateHandler}
 * registered once at client build time, shared by every session. Something has to
 * demultiplex it. This is that something.
 *
 * <p>
 * Dispatch is synchronous on purpose. The SDK completes a prompt only after its update
 * handlers have finished with every notification sent before the answer, so work done
 * inline is done before the turn's terminal event; a handler that deferred its work would
 * let that event overtake the last content chunk it was supposed to follow.
 */
public final class SessionUpdateRouter {

	private static final Logger logger = LoggerFactory.getLogger(SessionUpdateRouter.class);

	private final Map<String, Consumer<AcpSchema.SessionUpdate>> listeners = new ConcurrentHashMap<>();

	private volatile @Nullable BiConsumer<String, AcpSchema.SessionUpdate> noTurn;

	/**
	 * Hands updates for a session no turn is listening to to {@code handler}, with the
	 * session's id, instead of dropping them.
	 *
	 * <p>
	 * An agent may write to a session between turns: OpenCode 2.3, for one, runs a turn
	 * of its own when a background subagent reports back, and streams it with no prompt
	 * outstanding. Called synchronously, like a turn's listener.
	 * @param handler what receives them, or null to drop them again
	 */
	public void whenNoTurn(@Nullable BiConsumer<String, AcpSchema.SessionUpdate> handler) {
		this.noTurn = handler;
	}

	/**
	 * Directs updates for {@code sessionId} to {@code listener} until {@link #unregister}
	 * is called.
	 * @throws IllegalStateException if a turn is already listening to this session
	 */
	public void register(String sessionId, Consumer<AcpSchema.SessionUpdate> listener) {
		Consumer<AcpSchema.SessionUpdate> previous = listeners.putIfAbsent(sessionId, listener);
		if (previous != null) {
			throw new IllegalStateException("session " + sessionId + " already has an active turn");
		}
	}

	public void unregister(String sessionId) {
		listeners.remove(sessionId);
	}

	/**
	 * Stops directing updates for {@code sessionId} to {@code listener}, and leaves a
	 * registration that belongs to anyone else in place.
	 */
	public void unregister(String sessionId, Consumer<AcpSchema.SessionUpdate> listener) {
		listeners.remove(sessionId, listener);
	}

	/** The callback handed to {@code AcpClient.async(...).sessionUpdateHandler(...)}. */
	public void accept(AcpSchema.SessionNotification notification) {
		if (notification == null || notification.sessionId() == null) {
			return;
		}
		Consumer<AcpSchema.SessionUpdate> listener = listeners.get(notification.sessionId());
		try {
			if (listener != null) {
				listener.accept(notification.update());
				return;
			}
			BiConsumer<String, AcpSchema.SessionUpdate> handler = noTurn;
			if (handler == null) {
				// Expected between turns: a turn the agent never finished winding down
				// stops listening after a grace period, and anything later is dropped.
				logger.trace("No active turn for session {}; dropping update", notification.sessionId());
				return;
			}
			handler.accept(notification.sessionId(), notification.update());
		}
		catch (RuntimeException ex) {
			// Never let one listener break the shared notification pump.
			logger.warn("Listener for session {} threw; continuing", notification.sessionId(), ex);
		}
	}

	int activeTurns() {
		return listeners.size();
	}

}
