package org.springaicommunity.acp.harness.event;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * A single observable moment in an agent turn, runtime-neutral.
 *
 * <p>
 * Every turn emits exactly one terminal event — {@link Completed} or {@link Failed} —
 * whether it ends normally, errors, times out, loses its transport, or is cancelled by
 * the consumer. Consumers switch over this type without a default branch, so a turn that
 * emitted no terminal event would hang them forever.
 */
public sealed interface AcpHarnessEvent {

	/** Assistant-visible text. */
	record Text(String text) implements AcpHarnessEvent {
	}

	/** Reasoning the agent chose to surface; not part of the answer. */
	record Thought(String text) implements AcpHarnessEvent {
	}

	/**
	 * A tool invocation has started.
	 *
	 * @param title the agent's description of the call, or null when it gave none
	 * @param kind what sort of operation the call is, or null when the agent did not say
	 */
	record ToolCallStarted(String id, @Nullable String title,
			AcpSchema.@Nullable ToolKind kind) implements AcpHarnessEvent {
	}

	/**
	 * Progress or completion of a previously started tool call.
	 *
	 * @param status the call's new status, or null when the update carries none
	 * @param content what the call produced so far, or null when the update carries none
	 */
	record ToolCallUpdated(String id, AcpSchema.@Nullable ToolCallStatus status,
			@Nullable List<AcpSchema.ToolCallContent> content) implements AcpHarnessEvent {
	}

	/** The agent's current plan. Replaces any plan emitted earlier in the turn. */
	record PlanUpdated(List<AcpSchema.PlanEntry> entries) implements AcpHarnessEvent {
	}

	/** The agent changed its own session configuration mid-turn. */
	record ConfigChanged(List<AcpSchema.SessionConfigOption> options) implements AcpHarnessEvent {
	}

	/** The agent switched session mode mid-turn. */
	record ModeChanged(String modeId) implements AcpHarnessEvent {
	}

	/**
	 * The agent's own accounting for the session: context window consumed, and cost when
	 * it says.
	 *
	 * <p>
	 * Deferred from M1 for a reason that turned out to be half right. goose returns token
	 * counts on the {@code session/prompt} response, so there was nowhere for an event to
	 * come from — but that field is goose's own and not in the schema, while
	 * {@code usage_update} <em>is</em>, and carries the numbers an application actually
	 * wants to watch. So this comes from the notification rather than the response, and
	 * means the same thing on every agent that sends it.
	 *
	 * <p>
	 * Cumulative for the session, not incremental for the turn. {@code used} counts
	 * against {@code size}, the context window; {@code cost} is optional and absent from
	 * most agents.
	 *
	 * @param costAmount what the session has cost so far, or null when the agent did not
	 * say
	 * @param costCurrency the currency of that amount, or null when the agent did not say
	 */
	record UsageUpdated(long contextUsed, long contextSize, @Nullable Double costAmount,
			@Nullable String costCurrency) implements AcpHarnessEvent {

		/**
		 * How much of the context window is gone, or null when the agent did not say how
		 * big it is.
		 */
		public @Nullable Double contextFraction() {
			return contextSize <= 0 ? null : (double) contextUsed / contextSize;
		}
	}

	/**
	 * Terminal: the turn ended and the agent said why.
	 *
	 * @param reason why the turn stopped, or null when the agent did not say
	 */
	record Completed(AcpSchema.@Nullable StopReason reason) implements AcpHarnessEvent {
	}

	/** Terminal: the turn did not reach the agent's own stop condition. */
	record Failed(Throwable cause) implements AcpHarnessEvent {
	}

	/** Whether this is the one terminal event of its turn. */
	default boolean terminal() {
		return this instanceof Completed || this instanceof Failed;
	}

}
