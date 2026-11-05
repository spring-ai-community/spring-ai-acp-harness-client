package org.springaicommunity.acp.harness.observation;

import org.jspecify.annotations.Nullable;

import io.micrometer.observation.Observation;

/**
 * What one tool call was. Its status arrives after it starts, so the field is not final.
 */
public class ToolCallObservationContext extends Observation.Context {

	private final String runtimeId;

	private final String toolCallId;

	private final @Nullable String title;

	private final @Nullable String kind;

	private String status = "unfinished";

	/**
	 * @param title the agent's description of the call, or null when it gave none
	 * @param kind what sort of operation it is, or null when the agent did not say
	 */
	public ToolCallObservationContext(String runtimeId, String toolCallId, @Nullable String title,
			@Nullable String kind) {
		this.runtimeId = runtimeId;
		this.toolCallId = toolCallId;
		this.title = title;
		this.kind = kind;
	}

	public String runtimeId() {
		return runtimeId;
	}

	public String toolCallId() {
		return toolCallId;
	}

	/** The agent's description of the call, or null when it gave none. */
	public @Nullable String title() {
		return title;
	}

	/** What sort of operation the call is, or null when the agent did not say. */
	public @Nullable String kind() {
		return kind;
	}

	public String status() {
		return status;
	}

	public void status(@Nullable String status) {
		this.status = status == null || status.isBlank() ? "unfinished" : status;
	}

}
