package org.springaicommunity.acp.harness.client;

import org.jspecify.annotations.Nullable;

/** Something went wrong talking to the agent. */
public class AcpHarnessClientException extends RuntimeException {

	public AcpHarnessClientException(String message) {
		super(message);
	}

	/**
	 * @param cause what went wrong underneath, or null when nothing did
	 */
	public AcpHarnessClientException(String message, @Nullable Throwable cause) {
		super(message, cause);
	}

}
