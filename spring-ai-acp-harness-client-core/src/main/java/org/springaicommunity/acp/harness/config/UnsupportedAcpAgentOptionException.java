package org.springaicommunity.acp.harness.config;

import org.jspecify.annotations.Nullable;

/**
 * Thrown when a portable option cannot be honored by the selected runtime and
 * {@link OnUnsupported#FAIL} is in force.
 *
 * <p>
 * The analogue of the store-specific {@code UnsupportedOperationException} a Spring Data
 * repository throws for a query its backend cannot express. The message carries the
 * reason the resolver reached that conclusion, because "goose cannot honor model=gpt-4o"
 * is only actionable once you know whether the option is missing or the value is.
 */
public class UnsupportedAcpAgentOptionException extends RuntimeException {

	private final String option;

	private final String runtime;

	/**
	 * @param detail why the resolver gave up, or null when the message says it all
	 * @param cause what the agent answered, or null when it was never asked
	 */
	public UnsupportedAcpAgentOptionException(String option, String value, String runtime, @Nullable String detail,
			@Nullable Throwable cause) {
		super("runtime '" + runtime + "' cannot honor " + option + "='" + value + "'"
				+ (detail == null ? "" : ": " + detail), cause);
		this.option = option;
		this.runtime = runtime;
	}

	public String option() {
		return option;
	}

	public String runtime() {
		return runtime;
	}

}
