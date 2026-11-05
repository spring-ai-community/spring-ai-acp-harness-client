package org.springaicommunity.acp.harness.observation;

import org.jspecify.annotations.Nullable;

/**
 * Where a turn reports what it did, for whoever is counting.
 *
 * <p>
 * A small interface with no instrumentation types in it, for the same reason
 * {@code AcpAgentSettings} has no Spring types: the core has to build and test without
 * the thing on the classpath.
 * {@link org.springaicommunity.acp.harness.observation.MicrometerAcpAgentObservations} is
 * the implementation that turns these calls into Micrometer observations, and it is the
 * only class here that names Micrometer — so an application without it on the classpath
 * never loads it, and an application with it gets meters and tracing spans from the same
 * calls.
 *
 * <p>
 * The shape follows the turn rather than the metric. A turn starts, reports tool calls
 * and usage as they happen, and finishes exactly once — which is the invariant
 * {@code AcpAgentTurn} already upholds, and the reason an observation opened here is
 * guaranteed to be closed.
 */
public interface AcpAgentObservations {

	/** Records nothing. The default, and what the core is tested against. */
	AcpAgentObservations NONE = context -> TurnRecording.NONE;

	/**
	 * Begins recording a turn.
	 *
	 * <p>
	 * Called on the subscribing thread, before anything is sent. The returned recording
	 * is finished on whichever thread delivers the turn's terminal event, which is
	 * usually a different one — see {@code MicrometerAcpAgentObservations} for why that
	 * rules out the scoped Micrometer idiom.
	 */
	TurnRecording turnStarted(TurnContext context);

	/**
	 * What a turn is, for something that is counting turns.
	 *
	 * @param runtimeId the value of {@code spring.ai.acp.harness.runtime}
	 * @param sessionName the caller's name for the conversation, or a generated one
	 * @param model the model the session is really using, where the negotiated tier knows
	 * it, or null where nothing does
	 * @param ephemeral whether the session exists only for this turn
	 */
	record TurnContext(String runtimeId, String sessionName, @Nullable String model, boolean ephemeral) {
	}

	/** One turn in flight. Finished exactly once. */
	interface TurnRecording {

		TurnRecording NONE = new TurnRecording() {
		};

		/**
		 * A tool call the agent announced.
		 * @param title the agent's description of the call, or null when it gave none
		 * @param kind what sort of operation it is, or null when the agent did not say
		 */
		default void toolCallStarted(String toolCallId, @Nullable String title, @Nullable String kind) {
		}

		/**
		 * A tool call's status changed; terminal statuses end its observation.
		 * @param status the new status, or null when the update carried none
		 */
		default void toolCallUpdated(String toolCallId, @Nullable String status) {
		}

		/**
		 * The agent's own accounting for the session so far, from a {@code usage_update}
		 * notification.
		 *
		 * <p>
		 * Cumulative for the session rather than incremental for the turn, because that
		 * is what the notification carries.
		 * @param costAmount what the session has cost so far, or null when the agent did
		 * not say
		 * @param costCurrency the currency of that amount, or null when the agent did not
		 * say
		 */
		default void usage(long contextUsed, long contextSize, @Nullable Double costAmount,
				@Nullable String costCurrency) {
		}

		/**
		 * The turn reached the agent's own stop condition.
		 * @param stopReason why it stopped, or null when the agent did not say
		 */
		default void completed(@Nullable String stopReason) {
		}

		/**
		 * The turn ended without reaching one: an error, a timeout, or a consumer that
		 * walked away.
		 * @param cause what went wrong, or null when nothing threw, as for a cancellation
		 */
		default void failed(String reason, @Nullable Throwable cause) {
		}

	}

}
