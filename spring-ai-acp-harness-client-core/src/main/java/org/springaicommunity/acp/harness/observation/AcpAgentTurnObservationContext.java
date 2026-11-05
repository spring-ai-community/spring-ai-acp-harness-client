package org.springaicommunity.acp.harness.observation;

import org.jspecify.annotations.Nullable;

import io.micrometer.observation.Observation;

/**
 * What one turn was, for the convention that tags it.
 *
 * <p>
 * Mutable after creation on purpose: the outcome and the usage numbers arrive during the
 * turn, and a convention reads them when the observation stops.
 */
public class AcpAgentTurnObservationContext extends Observation.Context {

	private final AcpAgentObservations.TurnContext turn;

	private String outcome = "unknown";

	private @Nullable Long contextUsed;

	private @Nullable Long contextSize;

	private @Nullable String cost;

	public AcpAgentTurnObservationContext(AcpAgentObservations.TurnContext turn) {
		this.turn = turn;
	}

	public AcpAgentObservations.TurnContext turn() {
		return turn;
	}

	public String outcome() {
		return outcome;
	}

	public void outcome(@Nullable String outcome) {
		this.outcome = outcome == null || outcome.isBlank() ? "unknown" : outcome;
	}

	/** Context tokens used, or null before the agent reported any usage. */
	public @Nullable Long contextUsed() {
		return contextUsed;
	}

	/** The context window, or null before the agent reported any usage. */
	public @Nullable Long contextSize() {
		return contextSize;
	}

	/** The cost so far with its currency, or null when the agent did not say. */
	public @Nullable String cost() {
		return cost;
	}

	/**
	 * The latest {@code usage_update}; each one replaces the last, because they are
	 * cumulative.
	 */
	public void usage(long used, long size, @Nullable Double amount, @Nullable String currency) {
		this.contextUsed = used;
		this.contextSize = size;
		this.cost = amount == null ? null : amount + (currency == null ? "" : " " + currency);
	}

}
